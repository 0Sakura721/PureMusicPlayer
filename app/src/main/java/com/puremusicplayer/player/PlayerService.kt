package com.puremusicplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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

    private var foregroundStarted = false

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
                mp.start()
                PlayerManager.isPlaying.value = true
                PlayerManager.duration.value = mp.duration
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
        startProgressLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            PlayerControls.ACTION_PLAY -> {
                if (PlayerManager.currentIndex < 0 && PlayerManager.playlist.isNotEmpty()) {
                    PlayerManager.currentIndex = 0
                }
                playAt(PlayerManager.currentIndex, true)
            }
            PlayerControls.ACTION_PAUSE -> pause()
            PlayerControls.ACTION_PLAY_PAUSE -> togglePlayPause()
            PlayerControls.ACTION_NEXT -> playAt(step(true), true)
            PlayerControls.ACTION_PREV -> playAt(step(false), true)
            PlayerControls.ACTION_SEEK ->
                seekTo(intent.getIntExtra(PlayerControls.EXTRA_POSITION, 0))
            PlayerControls.ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(PlayerControls.EXTRA_MODE, PlayMode.REPEAT_ALL.ordinal)
                PlayerManager.playMode = PlayMode.values()[mode]
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        releaseVisualizer()
        mediaPlayer.release()
        mediaSession?.release()
        super.onDestroy()
    }

    // ---------- 播放控制 ----------
    private fun playAt(index: Int, start: Boolean) {
        val list = PlayerManager.playlist
        if (index !in list.indices) return
        PlayerManager.currentIndex = index
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
        if (PlayerManager.current() == null && PlayerManager.playlist.isNotEmpty()) {
            playAt(0, true); return
        }
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
            PlayerManager.isPlaying.value = true
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            PlayerManager.isPlaying.value = false
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun togglePlayPause() {
        if (mediaPlayer.isPlaying) pause() else resume()
    }

    private fun seekTo(posMs: Int) {
        if (posMs in 0..mediaPlayer.duration) {
            mediaPlayer.seekTo(posMs)
            PlayerManager.progress.value = posMs
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
            else -> playAt(step(true), true)
        }
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
    private fun startProgressLoop() {
        progressRunnable = object : Runnable {
            override fun run() {
                if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                    PlayerManager.progress.value = mediaPlayer.currentPosition
                    if (progressTick++ % 2 == 0) updatePlaybackState()
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressRunnable!!)
    }
}
