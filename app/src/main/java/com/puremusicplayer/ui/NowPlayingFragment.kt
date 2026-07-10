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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import coil.Coil
import coil.load
import coil.request.ImageRequest
import com.puremusicplayer.data.Song
import com.puremusicplayer.databinding.FragmentNowPlayingBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager
import com.puremusicplayer.player.PlayMode
import com.puremusicplayer.util.Prefs
import com.puremusicplayer.util.formatMs

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var seeking = false
    private var accentColor = Color.parseColor("#6C5CE7")
    private val inactiveTint = Color.parseColor("#777788")
    private var queueSheet: QueueBottomSheet? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Prefs.init(requireContext())
        accentColor = ContextCompat.getColor(requireContext(), com.puremusicplayer.R.color.brand_primary)

        applySettings()
        setupControls()
        observe()
    }

    override fun onDestroyView() {
        binding.visualizerView.unregister()
        queueSheet?.dismiss()
        queueSheet = null
        super.onDestroyView()
        _binding = null
    }

    private fun applySettings() {
        binding.lyricsView.setAnimate(Prefs.lyricsAnimEnabled)
        if (Prefs.visualizerEnabled) {
            binding.visualizerView.visibility = View.VISIBLE
            binding.visualizerView.register()
        } else {
            binding.visualizerView.unregister()
            binding.visualizerView.visibility = View.GONE
        }
    }

    private fun setupControls() {
        binding.btnPlay.setOnClickListener { PlayerControls.toggle(requireContext()) }
        binding.btnNext.setOnClickListener { PlayerControls.next(requireContext()) }
        binding.btnPrev.setOnClickListener { PlayerControls.prev(requireContext()) }
        binding.btnShuffle.setOnClickListener { cycleShuffle() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnQueue.setOnClickListener { openQueue() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrent.text = formatMs(progress.toLong())
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
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.ivArt.load(song.albumArtUri)
            if (Prefs.dynamicThemeEnabled) applyDynamicTheme(song.albumArtUri)
            else setAccent(accentColor)
            updateModeIcons()
            // 队列弹层打开时同步高亮当前曲目
            if (queueSheet?.isAdded == true) queueSheet?.refresh()
        })

        PlayerManager.isPlaying.observe(viewLifecycleOwner) { playing ->
            binding.btnPlay.setImageResource(
                if (playing) com.puremusicplayer.R.drawable.ic_pause
                else com.puremusicplayer.R.drawable.ic_play
            )
        }

        PlayerManager.duration.observe(viewLifecycleOwner) { d ->
            binding.seekBar.max = d
            binding.tvTotal.text = formatMs(d.toLong())
        }

        PlayerManager.progress.observe(viewLifecycleOwner) { p ->
            if (!seeking) binding.seekBar.progress = p
            binding.tvCurrent.text = formatMs(p.toLong())
            binding.lyricsView.update(p.toLong())
        }

        PlayerManager.lyrics.observe(viewLifecycleOwner) { list ->
            binding.lyricsView.setLyrics(list)
        }

        PlayerManager.hasLyrics.observe(viewLifecycleOwner) { has ->
            binding.tvNoLyrics.visibility = if (has) View.GONE else View.VISIBLE
            binding.lyricsView.visibility = if (has) View.VISIBLE else View.GONE
        }
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
        binding.lyricsView.setAccent(color)
        binding.visualizerView.setColor(color)
        val csl = ColorStateList.valueOf(color)
        binding.seekBar.progressTintList = csl
        binding.seekBar.thumbTintList = csl
        binding.btnPlay.setColorFilter(color)
        updateModeIcons()
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
        binding.btnShuffle.setColorFilter(
            if (PlayerManager.playMode == PlayMode.SHUFFLE) active else inactiveTint
        )
        when (PlayerManager.playMode) {
            PlayMode.REPEAT_ONE -> {
                binding.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(active)
            }
            PlayMode.REPEAT_ALL -> {
                binding.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(active)
            }
            else -> {
                binding.btnRepeat.setImageResource(com.puremusicplayer.R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(inactiveTint)
            }
        }
    }
}
