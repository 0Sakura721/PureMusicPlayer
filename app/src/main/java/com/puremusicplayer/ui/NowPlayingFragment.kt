package com.puremusicplayer.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.puremusicplayer.R
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import coil.Coil
import coil.load
import coil.request.ImageRequest
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.puremusicplayer.data.AudioFileInfo
import com.puremusicplayer.data.Song
import com.puremusicplayer.databinding.FragmentNowPlayingBinding
import com.puremusicplayer.databinding.PageCoverBinding
import com.puremusicplayer.databinding.PageLyricsBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager
import com.puremusicplayer.player.PlayMode
import com.puremusicplayer.util.Prefs
import com.puremusicplayer.util.formatMs

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private lateinit var cover: PageCoverBinding
    private lateinit var lyricsPage: PageLyricsBinding

    private var seeking = false
    private var accentColor = Color.parseColor("#6C5CE7")
    private val inactiveTint = Color.parseColor("#777788")
    private var queueSheet: QueueBottomSheet? = null

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private val SPEED_LABELS = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Prefs.init(requireContext())
        val brand = ContextCompat.getColor(requireContext(), com.puremusicplayer.R.color.brand_primary)
        accentColor = if (Prefs.accentColor >= 0) Prefs.accentColor else brand

        setupPager()
        applySettings()
        setupControls()
        observe()
    }

    override fun onDestroyView() {
        cover.visualizerView.unregister()
        queueSheet?.dismiss()
        queueSheet = null
        super.onDestroyView()
        _binding = null
    }

    // ---------- 封面 / 歌词 分页 ----------
    private fun setupPager() {
        val coverView = layoutInflater.inflate(R.layout.page_cover, binding.viewPager, false)
        val lyricsView = layoutInflater.inflate(R.layout.page_lyrics, binding.viewPager, false)
        cover = PageCoverBinding.bind(coverView)
        lyricsPage = PageLyricsBinding.bind(lyricsView)

        binding.viewPager.adapter = NpPagerAdapter(listOf(coverView, lyricsView))
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = if (pos == 0) getString(R.string.np_page_cover) else getString(R.string.np_page_lyrics)
        }.attach()
    }

    private fun applySettings() {
        lyricsPage.lyricsView.setAnimate(Prefs.lyricsAnimEnabled)
        cover.btnSpeed.text = speedLabel(Prefs.playbackSpeed)
        updateSleepView(PlayerManager.sleepRemaining.value ?: 0)
        if (Prefs.visualizerEnabled) {
            cover.visualizerView.visibility = View.VISIBLE
            cover.visualizerView.setStyle(Prefs.visualizerStyle)
            cover.visualizerView.register()
        } else {
            cover.visualizerView.unregister()
            cover.visualizerView.visibility = View.GONE
        }
    }

    private fun setupControls() {
        binding.btnQueue.setOnClickListener { openQueue() }

        cover.btnPlay.setOnClickListener { PlayerControls.toggle(requireContext()) }
        cover.btnNext.setOnClickListener { PlayerControls.next(requireContext()) }
        cover.btnPrev.setOnClickListener { PlayerControls.prev(requireContext()) }
        cover.btnShuffle.setOnClickListener { cycleShuffle() }
        cover.btnRepeat.setOnClickListener { cycleRepeat() }
        cover.btnFav.setOnClickListener { toggleFav() }
        cover.btnSleep.setOnClickListener { showSleepDialog() }
        cover.btnSpeed.setOnClickListener { cycleSpeed() }
        cover.btnEq.setOnClickListener { showEqDialog() }

        cover.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) cover.tvCurrent.text = formatMs(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                PlayerControls.seek(requireContext(), seekBar?.progress ?: 0)
            }
        })
    }

    private fun observe() {
        PlayerManager.currentSong.observe(viewLifecycleOwner, Observer { song ->
            song ?: return@Observer
            cover.tvTitle.text = song.title
            cover.tvArtist.text = song.artist
            cover.ivArt.load(song.albumArtUri)
            showFileInfo(song)
            // DIY 个性强调色优先；其次按封面取动态色；最后回退品牌色
            val diy = Prefs.accentColor
            if (diy >= 0) setAccent(diy)
            else if (Prefs.dynamicThemeEnabled) applyDynamicTheme(song.albumArtUri)
            else setAccent(accentColor)
            updateModeIcons()
            updateFavIcon()
            // 队列弹层打开时同步高亮当前曲目
            if (queueSheet?.isAdded == true) queueSheet?.refresh()
        })

        PlayerManager.isPlaying.observe(viewLifecycleOwner) { playing ->
            cover.btnPlay.setImageResource(
                if (playing) com.puremusicplayer.R.drawable.ic_pause
                else com.puremusicplayer.R.drawable.ic_play
            )
        }

        PlayerManager.duration.observe(viewLifecycleOwner) { d ->
            cover.seekBar.max = d
            cover.tvTotal.text = formatMs(d.toLong())
        }

        PlayerManager.progress.observe(viewLifecycleOwner) { p ->
            if (!seeking) cover.seekBar.progress = p
            cover.tvCurrent.text = formatMs(p.toLong())
            lyricsPage.lyricsView.update(p.toLong())
        }

        PlayerManager.lyrics.observe(viewLifecycleOwner) { list ->
            lyricsPage.lyricsView.setLyrics(list)
        }

        PlayerManager.hasLyrics.observe(viewLifecycleOwner) { has ->
            lyricsPage.tvNoLyrics.visibility = if (has) View.GONE else View.VISIBLE
            lyricsPage.lyricsView.visibility = if (has) View.VISIBLE else View.GONE
        }

        // 收藏集合变化（迷你栏/列表也可能修改）时刷新当前曲爱心
        PlayerManager.favorites.observe(viewLifecycleOwner) { updateFavIcon() }

        // 睡眠定时器倒计时
        PlayerManager.sleepRemaining.observe(viewLifecycleOwner) { updateSleepView(it) }
    }

    // ---------- 文件信息（格式 / 位深 / 采样率） ----------
    private fun showFileInfo(song: Song) {
        if (song.audioInfo != null) {
            cover.tvFileInfo.text = song.audioInfo!!.toDisplay()
            return
        }
        cover.tvFileInfo.text = getString(R.string.file_info_loading)
        Thread {
            val info = AudioFileInfo.probe(requireContext(), song)
            song.audioInfo = info
            val text = info?.toDisplay() ?: ""
            binding.root.post { cover.tvFileInfo.text = text }
        }.start()
    }

    // ---------- 播放队列 ----------
    private fun openQueue() {
        val sheet = QueueBottomSheet()
        queueSheet = sheet
        sheet.show(childFragmentManager, "queue")
    }

    // ---------- 动态主题（按封面取色） ----------
    private fun applyDynamicTheme(uri: Uri?) {
        if (uri == null) {
            setAccent(accentColor)
            return
        }
        val request = ImageRequest.Builder(requireContext())
            .data(uri)
            .size(96, 96)
            .target { drawable ->
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return@target
                androidx.palette.graphics.Palette.from(bmp).generate { palette ->
                    val color = palette?.dominantSwatch?.rgb
                        ?: palette?.vibrantSwatch?.rgb
                        ?: return@generate
                    setAccent(color)
                }
            }
            .build()
        Coil.imageLoader(requireContext()).enqueue(request)
    }

    private fun setAccent(color: Int) {
        accentColor = color
        lyricsPage.lyricsView.setAccent(color)
        cover.visualizerView.setColor(color)
        val csl = ColorStateList.valueOf(color)
        cover.seekBar.progressTintList = csl
        cover.seekBar.thumbTintList = csl
        cover.btnPlay.setColorFilter(color)
        updateModeIcons()
        updateFavIcon()
        updateEqButton()
    }

    // ---------- 收藏 ----------
    private fun toggleFav() {
        val song = PlayerManager.current() ?: return
        PlayerManager.toggleFav(song.favKey())
        updateFavIcon()
    }

    private fun updateFavIcon() {
        val song = PlayerManager.current() ?: return
        val fav = PlayerManager.favorites.value?.contains(song.favKey()) == true
        cover.btnFav.setImageResource(
            if (fav) com.puremusicplayer.R.drawable.ic_heart
            else com.puremusicplayer.R.drawable.ic_heart_outline
        )
        cover.btnFav.setColorFilter(if (fav) accentColor else inactiveTint)
    }

    // ---------- 倍速播放 ----------
    private fun cycleSpeed() {
        val cur = Prefs.playbackSpeed
        var idx = SPEEDS.indexOfFirst { kotlin.math.abs(it - cur) < 0.001f }
        idx = (idx + 1) % SPEEDS.size
        val speed = SPEEDS[idx]
        Prefs.playbackSpeed = speed
        PlayerControls.setSpeed(requireContext(), speed)
        cover.btnSpeed.text = SPEED_LABELS[idx]
    }

    private fun speedLabel(speed: Float): String {
        val idx = SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.001f }
        return if (idx >= 0) SPEED_LABELS[idx] else "1.0×"
    }

    // ---------- 睡眠定时器 ----------
    private fun showSleepDialog() {
        val opts = arrayOf(
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_45),
            getString(R.string.sleep_60),
            getString(R.string.sleep_end_track),
            getString(R.string.sleep_cancel)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sleep_timer)
            .setItems(opts) { dlg, which ->
                when (which) {
                    0 -> PlayerControls.sleep(requireContext(), 15)
                    1 -> PlayerControls.sleep(requireContext(), 30)
                    2 -> PlayerControls.sleep(requireContext(), 45)
                    3 -> PlayerControls.sleep(requireContext(), 60)
                    4 -> PlayerControls.sleep(requireContext(), finishCurrentTrack = true)
                    5 -> PlayerControls.sleep(requireContext()) // 取消
                }
                dlg.dismiss()
            }
            .show()
    }

    // ---------- 均衡器 ----------
    private fun showEqDialog() {
        val presets = com.puremusicplayer.player.EqualizerHelper.Preset.values()
        val items = presets.map { it.label }.toTypedArray()
        val current = Prefs.equalizerPreset.coerceIn(0, presets.size - 1)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.equalizer)
            .setSingleChoiceItems(items, current) { dlg, which ->
                Prefs.equalizerPreset = which
                PlayerControls.setEqPreset(requireContext(), which)
                updateEqButton()
                dlg.dismiss()
            }
            .setNeutralButton(if (Prefs.equalizerEnabled) R.string.eq_disable else R.string.eq_enable) { dlg, _ ->
                val on = !Prefs.equalizerEnabled
                Prefs.equalizerEnabled = on
                PlayerControls.toggleEq(requireContext(), on)
                updateEqButton()
                dlg.dismiss()
            }
            .show()
    }

    private fun updateEqButton() {
        cover.btnEq.setColorFilter(if (Prefs.equalizerEnabled) accentColor else inactiveTint)
    }

    private fun updateSleepView(remain: Int) {
        when {
            remain <= 0 -> {
                cover.tvSleep.visibility = View.GONE
                cover.btnSleep.setColorFilter(inactiveTint)
            }
            remain == -1 -> {
                cover.tvSleep.visibility = View.VISIBLE
                cover.tvSleep.text = getString(R.string.sleep_status_end_track)
                cover.btnSleep.setColorFilter(accentColor)
            }
            else -> {
                val m = remain / 60
                val s = remain % 60
                cover.tvSleep.visibility = View.VISIBLE
                cover.tvSleep.text = getString(R.string.sleep_status, "%d:%02d".format(m, s))
                cover.btnSleep.setColorFilter(accentColor)
            }
        }
    }

    // ---------- 播放模式 ----------
    private fun cycleShuffle() {
        PlayerManager.playMode = if (PlayerManager.playMode == PlayMode.SHUFFLE)
            PlayMode.REPEAT_ALL else PlayMode.SHUFFLE
        Prefs.playModeOrdinal = PlayerManager.playMode.ordinal
        updateModeIcons()
    }

    private fun cycleRepeat() {
        PlayerManager.playMode = when (PlayerManager.playMode) {
            PlayMode.ORDER -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.ORDER
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ALL
        }
        Prefs.playModeOrdinal = PlayerManager.playMode.ordinal
        updateModeIcons()
    }

    private fun updateModeIcons() {
        val active = accentColor
        cover.btnShuffle.setColorFilter(
            if (PlayerManager.playMode == PlayMode.SHUFFLE) active else inactiveTint
        )
        when (PlayerManager.playMode) {
            PlayMode.REPEAT_ONE -> {
                cover.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat_one)
                cover.btnRepeat.setColorFilter(active)
            }
            PlayMode.REPEAT_ALL -> {
                cover.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat)
                cover.btnRepeat.setColorFilter(active)
            }
            else -> {
                cover.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat)
                cover.btnRepeat.setColorFilter(inactiveTint)
            }
        }
    }
}
