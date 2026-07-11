package com.puremusicplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.puremusicplayer.MainActivity
import com.puremusicplayer.R
import com.puremusicplayer.data.EmbeddedLyrics
import com.puremusicplayer.data.MusicRepository
import com.puremusicplayer.data.Song
import com.puremusicplayer.util.Prefs
import com.puremusicplayer.player.EqualizerHelper

/**
 * 媒体播放前台服务。
 *
 * 整合（均为平台原生能力，零额外媒体依赖，保持精简）：
 *  - MediaPlayer：本地音频播放内核
 *  - Visualizer：音频 FFT，驱动可视化（无需麦克风权限）
 *  - android.media.session.MediaSession：锁屏 / 控制中心 / 媒体键统一控制
 *  - 前台通知（Notification.MediaStyle）：后台持续播放
 *
 * 状态通过 PlayerManager 暴露给 UI；UI 通过 PlayerControls 发送指令。
 */
class PlayerService : android.app.Service() {

    private lateinit var mediaPlayer: MediaPlayer
    private var mediaSession: MediaSession? = null
    private var visualizer: Visualizer? = null
    private var notificationManager: NotificationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressTick = 0
    /** 本次播放需要续播到的位置（毫秒）；0 表示从头播放 */
    private var resumePosition = 0
    /** MediaPlayer 是否已准备就绪（prepareAsync 完成）。
     *  未准备时不可直接 start()/seekTo()，否则抛 IllegalStateException。
     *  这是“失败 CL”续播崩溃的根因：服务刚创建、歌曲已由 restoreQueue 恢复，
     *  但 MediaPlayer 还没 prepare，此时点播放会直接 start() 而崩溃。 */
    private var prepared = false

    private var foregroundStarted = false

    // 睡眠定时器
    private var sleepEndTime = 0L
    private var sleepFinishTrack = false
    private var sleepRunnable: Runnable? = null

    // 耳机/蓝牙拔出自动暂停
    private var noisyReceiver: BroadcastReceiver? = null

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "puremusic_playback"
        private const val REQ_PREV = 1
        private const val REQ_PLAY = 2
        private const val REQ_NEXT = 3
    }

    // ---------- 生命周期 ----------
    override fun onCreate() {
        super.onCreate()
        notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener { mp ->
                // 标记已准备就绪：此后才可安全地 start() / seekTo()
                prepared = true
                // 续播：回到上次保存的播放位置（由 restoreQueue 经 pendingResumePosition 传入）
                val savedPos = resumePosition
                resumePosition = 0
                if (savedPos > 0 && savedPos < mp.duration) {
                    mp.seekTo(savedPos)
                    PlayerManager.progress.value = savedPos
                }
                mp.start()
                PlayerManager.isPlaying.value = true
                PlayerManager.duration.value = mp.duration
                // 若服务创建时音频会话尚未就绪导致均衡器/可视化未初始化，此时会话已有效，补初始化
                if (Prefs.equalizerEnabled && !EqualizerHelper.isInitialized()) {
                    EqualizerHelper.init(mp.audioSessionId)
                    EqualizerHelper.setEnabled(true)
                    val presets = EqualizerHelper.Preset.values()
                    EqualizerHelper.applyPreset(presets[Prefs.equalizerPreset.coerceIn(0, presets.size - 1)])
                }
                // 与均衡器兜底对称：会话此刻一定有效，若可视化尚未建立则补建，保证首曲可视化可用
                if (visualizer == null) ensureVisualizer(mp.audioSessionId)
                applyPlaybackSpeed(mp)
                updatePlaybackState()
                updateNotification()
            }
            setOnCompletionListener { onTrackEnded() }
            setOnErrorListener { _, _, _ ->
                PlayerManager.isPlaying.value = false
                false
            }
        }

        setupVisualizer()
        setupMediaSession()
        registerNoisyReceiver()
        startProgressLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            PlayerControls.ACTION_PLAY -> {
                if (PlayerManager.currentIndex < 0 && PlayerManager.playlist.isNotEmpty()) {
                    PlayerManager.currentIndex = 0
                }
                // 显式播放当前选择：始终（重新）准备并播放，续播位置一并应用
                prepareResumePosition()
                playAt(PlayerManager.currentIndex, true)
            }
            PlayerControls.ACTION_PAUSE -> pause()
            PlayerControls.ACTION_PLAY_PAUSE -> {
                if (mediaPlayer.isPlaying) pause()
                else { prepareResumePosition(); playOrResume() }
            }
            PlayerControls.ACTION_NEXT -> { resumePosition = 0; playAt(step(true), true) }
            PlayerControls.ACTION_PREV -> { resumePosition = 0; playAt(step(false), true) }
            PlayerControls.ACTION_SEEK ->
                seekTo(intent.getIntExtra(PlayerControls.EXTRA_POSITION, 0))
            PlayerControls.ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(PlayerControls.EXTRA_MODE, PlayMode.REPEAT_ALL.ordinal)
                PlayerManager.playMode = PlayMode.values()[mode]
                updateNotification()
            }
            PlayerControls.ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(PlayerControls.EXTRA_SPEED, 1.0f)
                Prefs.playbackSpeed = speed
                if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) applyPlaybackSpeed(mediaPlayer)
            }
            PlayerControls.ACTION_SLEEP -> {
                val min = intent.getIntExtra(PlayerControls.EXTRA_SLEEP_MIN, 0)
                val finish = intent.getIntExtra(PlayerControls.EXTRA_SLEEP_FINISH, 0) == 1
                if (min <= 0 && !finish) cancelSleepTimer() else startSleepTimer(min, finish)
            }
            PlayerControls.ACTION_SET_EQ -> {
                val on = intent.getIntExtra(PlayerControls.EXTRA_EQ_ENABLED, 0) == 1
                Prefs.equalizerEnabled = on
                EqualizerHelper.setEnabled(on)
            }
            PlayerControls.ACTION_SET_EQ_PRESET -> {
                val idx = intent.getIntExtra(PlayerControls.EXTRA_EQ_PRESET, 0)
                Prefs.equalizerPreset = idx
                val presets = EqualizerHelper.Preset.values()
                EqualizerHelper.applyPreset(presets[idx.coerceIn(0, presets.size - 1)])
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelSleepTimer()
        unregisterNoisyReceiver()
        releaseVisualizer()
        PlayerManager.saveQueue(this)
        mediaPlayer.release()
        mediaSession?.release()
        super.onDestroy()
    }

    // ---------- 播放控制 ----------
    private fun playAt(index: Int, start: Boolean) {
        val list = PlayerManager.playlist
        if (index !in list.indices) return
        PlayerManager.currentIndex = index
        PlayerManager.saveQueue(this)
        prepared = false   // 即将重新准备，标记为未就绪
        val song = list[index]
        try {
            mediaPlayer.reset()
            val uri = song.contentUri()
            mediaPlayer.setDataSource(applicationContext, uri)
            mediaPlayer.prepareAsync()
            PlayerManager.currentSong.value = song
            PlayerManager.progress.value = 0
            updateMediaSessionMetadata(song)
            loadLyrics(song)
        } catch (e: Exception) {
            // 单曲读取失败：尝试跳到下一首
            if (list.size > 1) playAt(step(true), true)
        }
    }

    private fun resume() {
        prepareResumePosition()
        playOrResume()
    }

    private fun pause() {
        if (!prepared || !mediaPlayer.isPlaying) return
        mediaPlayer.pause()
        PlayerManager.isPlaying.value = false
        PlayerManager.saveQueue(this)
        updatePlaybackState()
        // 若非「通知栏常驻」，暂停后移除前台通知以减少干扰
        if (!Prefs.persistentNotification) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            } catch (_: Exception) {}
        } else {
            updateNotification()
        }
    }

    /**
     * 计算续播位置：仅当「恢复出的歌曲」与当前曲目一致时，
     * 才把待续播位置写入服务级 resumePosition；随后清空 PlayerManager 的待续播信息，
     * 避免误续播到其它歌曲。与 PR#1 修复的「续播位置被清零」对称。
     */
    private fun prepareResumePosition() {
        val song = PlayerManager.playlist.getOrNull(PlayerManager.currentIndex)
        resumePosition = if (song != null && song.favKey() == PlayerManager.resumeSongKey) {
            PlayerManager.pendingResumePosition
        } else 0
        PlayerManager.resumeSongKey = null
        PlayerManager.pendingResumePosition = 0
    }

    /**
     * 播放或恢复当前曲目：
     *  - 已准备就绪且处于暂停态 → 直接 start（瞬时恢复，含暂停位置）
     *  - 未准备就绪（如刚创建服务、续播场景，MediaPlayer 尚未 prepare）→ 走 playAt 准备
     *  这避免了「失败 CL」中对未准备播放器调用 start() 的崩溃。
     */
    private fun playOrResume() {
        // 无有效曲目时回退到列表首曲
        if (PlayerManager.currentIndex !in PlayerManager.playlist.indices) {
            if (PlayerManager.playlist.isNotEmpty()) PlayerManager.currentIndex = 0
            else return
        }
        if (prepared && ::mediaPlayer.isInitialized && !mediaPlayer.isPlaying) {
            mediaPlayer.start()
            PlayerManager.isPlaying.value = true
            updatePlaybackState()
            updateNotification()
        } else if (!prepared) {
            playAt(PlayerManager.currentIndex, true)
        }
    }

    private fun seekTo(posMs: Int) {
        if (!prepared) return
        if (posMs in 0..mediaPlayer.duration) {
            mediaPlayer.seekTo(posMs)
            PlayerManager.progress.value = posMs
            PlayerManager.saveQueue(this)
            updatePlaybackState()
        }
    }

    private fun onTrackEnded() {
        when (PlayerManager.playMode) {
            PlayMode.REPEAT_ONE -> {
                mediaPlayer.seekTo(0)
                mediaPlayer.start()
                PlayerManager.isPlaying.value = true
                updatePlaybackState()
            }
            PlayMode.ORDER -> {
                // 顺序播放：播完最后一首后停止，不循环
                val last = PlayerManager.currentIndex >= PlayerManager.playlist.size - 1
                if (last) {
                    if (sleepFinishTrack) {
                        sleepFinishTrack = false
                        cancelSleepTimer()
                        PlayerManager.sleepRemaining.value = 0
                    }
                    PlayerManager.isPlaying.value = false
                    updatePlaybackState()
                    updateNotification()
                } else {
                    if (sleepFinishTrack) {
                        sleepFinishTrack = false
                        cancelSleepTimer()
                        PlayerManager.sleepRemaining.value = 0
                        pause()
                    } else {
                        resumePosition = 0
                        playAt(step(true), true)
                    }
                }
            }
            else -> {
                // 「播完当前歌曲后停止」：自然结束后暂停
                if (sleepFinishTrack) {
                    sleepFinishTrack = false
                    cancelSleepTimer()
                    PlayerManager.sleepRemaining.value = 0
                    pause()
                } else {
                    resumePosition = 0   // 下一首从头播放，避免误带续播位置
                    playAt(step(true), true)
                }
            }
        }
    }

    // ---------- 倍速播放（原生 MediaPlayer.setPlaybackParams，API 23+） ----------
    private fun applyPlaybackSpeed(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val speed = Prefs.playbackSpeed
        if (speed == 1.0f) return
        try {
            val params = mp.playbackParams
            params.speed = speed
            mp.playbackParams = params
        } catch (_: Exception) {
            // 部分设备/格式不支持调速，忽略
        }
    }

    // ---------- 睡眠定时器 ----------
    private fun startSleepTimer(minutes: Int, finishTrack: Boolean) {
        cancelSleepTimer()
        if (finishTrack) {
            sleepFinishTrack = true
            PlayerManager.sleepRemaining.value = -1
            return
        }
        if (minutes <= 0) {
            PlayerManager.sleepRemaining.value = 0
            return
        }
        sleepFinishTrack = false
        sleepEndTime = System.currentTimeMillis() + minutes * 60_000L
        sleepRunnable = object : Runnable {
            override fun run() {
                val rem = ((sleepEndTime - System.currentTimeMillis()) / 1000).toInt()
                if (rem <= 0) doSleep()
                else {
                    PlayerManager.sleepRemaining.value = rem
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(sleepRunnable!!)
    }

    /** 倒计时结束：渐隐音量并暂停 */
    private fun doSleep() {
        cancelSleepTimer()
        PlayerManager.sleepRemaining.value = 0
        fadeAndPause()
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
        sleepFinishTrack = false
        if (PlayerManager.sleepRemaining.value != 0) PlayerManager.sleepRemaining.value = 0
    }

    /** 在约 2 秒内把音量由当前值渐隐到 0，再暂停（避免突兀静音） */
    private fun fadeAndPause() {
        if (!::mediaPlayer.isInitialized || !mediaPlayer.isPlaying) {
            pause()
            return
        }
        val steps = 8
        var i = 0
        val runnable = object : Runnable {
            override fun run() {
                i++
                val v = (1f - i.toFloat() / steps).coerceAtLeast(0f)
                try { mediaPlayer.setVolume(v, v) } catch (_: Exception) {}
                if (i < steps) handler.postDelayed(this, 250)
                else {
                    try { mediaPlayer.setVolume(1f, 1f) } catch (_: Exception) {}
                    pause()
                }
            }
        }
        handler.post(runnable)
    }

    // ---------- 耳机/蓝牙拔出自动暂停 ----------
    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    if (Prefs.pauseOnUnplug && ::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                        pause()
                    }
                }
            }
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterNoisyReceiver() {
        try { noisyReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        noisyReceiver = null
    }

    /** 计算下一首 / 上一首索引（含随机与循环） */
    private fun step(forward: Boolean): Int {
        val list = PlayerManager.playlist
        if (list.isEmpty()) return -1
        if (PlayerManager.playMode == PlayMode.SHUFFLE) {
            if (list.size == 1) return 0
            var n = PlayerManager.currentIndex
            while (n == PlayerManager.currentIndex) n = (Math.random() * list.size).toInt()
            return n
        }
        val cur = PlayerManager.currentIndex
        val n = if (forward) cur + 1 else cur - 1
        return (n + list.size) % list.size
    }

    // ---------- 歌词 ----------
    private fun loadLyrics(song: Song) {
        PlayerManager.lyrics.value = emptyList()
        PlayerManager.hasLyrics.value = false

        // 优先外置 .lrc 文件
        val lrcUri = MusicRepository.findLrcUri(this, song)
        if (lrcUri != null) {
            Thread {
                try {
                    contentResolver.openInputStream(lrcUri)?.use { stream ->
                        val lines = LyricsParser.parse(stream)
                        PlayerManager.lyrics.postValue(lines)
                        PlayerManager.hasLyrics.postValue(lines.isNotEmpty())
                    }
                } catch (_: Exception) {
                    // 歌词解析失败则静默忽略
                }
            }.start()
            return
        }

        // 回退：读取 FLAC / OGG / MP3 / M4A 等文件内嵌歌词
        val embedded = EmbeddedLyrics.read(this, song)
        if (embedded != null) {
            Thread {
                try {
                    val lines = LyricsParser.parseText(embedded)
                    PlayerManager.lyrics.postValue(lines)
                    PlayerManager.hasLyrics.postValue(lines.isNotEmpty())
                } catch (_: Exception) {
                    // 内嵌歌词解析失败则静默忽略
                }
            }.start()
        }
    }

    // ---------- 可视化 ----------
    private fun setupVisualizer() {
        try {
            val sessionId = mediaPlayer.audioSessionId
            if (sessionId == 0) return
            // 初始化均衡器
            EqualizerHelper.init(sessionId)
            if (Prefs.equalizerEnabled) {
                EqualizerHelper.setEnabled(true)
                val presets = EqualizerHelper.Preset.values()
                val idx = Prefs.equalizerPreset.coerceIn(0, presets.size - 1)
                EqualizerHelper.applyPreset(presets[idx])
            }
            ensureVisualizer(sessionId)
        } catch (_: Exception) {
            visualizer = null
        }
    }

    /** 为指定有效音频会话创建可视化（若尚未创建）；会话无效或已创建则跳过 */
    private fun ensureVisualizer(sessionId: Int) {
        if (sessionId <= 0 || visualizer != null) return
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer?, waveform: ByteArray?, s: Int
                        ) {
                            waveform?.let { PlayerManager.dispatchWave(it) }
                        }

                        override fun onFftDataCapture(
                            v: Visualizer?, fft: ByteArray?, s: Int
                        ) {
                            fft?.let { PlayerManager.dispatchFft(it) }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    true,
                    true
                )
                enabled = true
            }
        } catch (_: Exception) {
            visualizer = null
        }
    }

    private fun releaseVisualizer() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        EqualizerHelper.release()
    }

    // ---------- MediaSession（框架原生，API 21+） ----------
    @Suppress("DEPRECATION")
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "PureMusicSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(sessionCallback)
            isActive = true
        }
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = resume()
        override fun onPause() = pause()
        override fun onStop() = pause()
        override fun onSkipToNext() = playAt(step(true), true)
        override fun onSkipToPrevious() = playAt(step(false), true)
        override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
    }

    private fun updateMediaSessionMetadata(song: Song) {
        val meta = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadata.METADATA_KEY_DURATION, song.duration.toLong())
            putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, song.albumArtUri?.toString())
        }.build()
        mediaSession?.setMetadata(meta)
    }

    private fun updatePlaybackState() {
        val state = if (mediaPlayer.isPlaying)
            PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = (
                PlaybackState.ACTION_PLAY
                        or PlaybackState.ACTION_PAUSE
                        or PlaybackState.ACTION_PLAY_PAUSE
                        or PlaybackState.ACTION_SKIP_TO_NEXT
                        or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackState.ACTION_SEEK_TO
                ).toLong()
        val pos = if (::mediaPlayer.isInitialized) mediaPlayer.currentPosition.toLong() else 0L
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, pos, 1f)
                .setActions(actions)
                .build()
        )
    }

    // ---------- 通知（框架原生 MediaStyle，API 21+） ----------
    private fun ensureForeground() {
        if (!foregroundStarted) {
            foregroundStarted = true
            createChannel()
            startForeground(NOTIF_ID, buildNotification())
        } else {
            notificationManager?.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() = notificationManager?.notify(NOTIF_ID, buildNotification())

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val song = PlayerManager.current()
        val playing = PlayerManager.isPlaying.value == true
        val ctx = this

        val contentIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val prevPi = PendingIntent.getService(
            ctx, REQ_PREV,
            Intent(ctx, PlayerService::class.java).setAction(PlayerControls.ACTION_PREV),
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPi = PendingIntent.getService(
            ctx, REQ_PLAY,
            Intent(ctx, PlayerService::class.java)
                .setAction(if (playing) PlayerControls.ACTION_PAUSE else PlayerControls.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextPi = PendingIntent.getService(
            ctx, REQ_NEXT,
            Intent(ctx, PlayerService::class.java).setAction(PlayerControls.ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID)
        else
            Notification.Builder(ctx)
        ).apply {
            setSmallIcon(R.drawable.ic_now_playing)
            setContentTitle(song?.title ?: getString(R.string.app_name))
            setContentText(song?.artist ?: "")
            setSubText(song?.album ?: "")
            setContentIntent(contentIntent)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(Notification.CATEGORY_TRANSPORT)
            addAction(
                Notification.Action.Builder(
                    R.drawable.ic_previous, getString(R.string.action_previous), prevPi
                ).build()
            )
            addAction(
                Notification.Action.Builder(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) getString(R.string.action_pause) else getString(R.string.action_play),
                    playPi
                ).build()
            )
            addAction(
                Notification.Action.Builder(
                    R.drawable.ic_next, getString(R.string.action_next), nextPi
                ).build()
            )
        }

        mediaSession?.sessionToken?.let { token ->
            builder.style = Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
        }
        return builder.build()
    }

    // ---------- 进度循环 ----------
    private var progressRunnable: Runnable? = null
    private var saveTick = 0   // 进度持久化计数：每约 15s 写一次，保证续播位置不过期
    private fun startProgressLoop() {
        progressRunnable = object : Runnable {
            override fun run() {
                if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                    PlayerManager.progress.value = mediaPlayer.currentPosition
                    if (progressTick++ % 2 == 0) updatePlaybackState()
                    // 周期持久化播放进度（约 15s），即便被系统强杀也能恢复到近似位置
                    if (saveTick++ >= 30) {
                        saveTick = 0
                        PlayerManager.saveQueue(this@PlayerService)
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressRunnable!!)
    }
}
