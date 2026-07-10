package com.puremusicplayer

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import coil.load
import com.puremusicplayer.R
import com.puremusicplayer.databinding.ActivityMainBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager
import com.puremusicplayer.util.Prefs
import com.puremusicplayer.ui.LibraryFragment
import com.puremusicplayer.ui.NowPlayingFragment
import com.puremusicplayer.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Android 13+ 预测性返回（Predictive Back）：非曲库页时拦截返回并切回曲库，
    // 曲库页时注销回调，交由系统默认退出（带预测性返回主页动画）。
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.bottomNav.selectedItemId = R.id.nav_library
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        applyThemeMode()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16 强制边到边：由我们自行处理系统栏 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()

        // 注册预测性返回回调（依赖 manifest 中 enableOnBackInvokedCallback=true）
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState == null) switchFragment(LibraryFragment())
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> switchFragment(LibraryFragment())
                R.id.nav_now_playing -> switchFragment(NowPlayingFragment())
                R.id.nav_settings -> switchFragment(SettingsFragment())
            }
            updateBackCallback()
            true
        }
        updateBackCallback()
        setupMiniPlayer()
    }

    /** 主页常驻迷你播放器：有歌曲时显示，点击展开播放页，含播放/下一首控制 */
    /** 按偏好应用明暗主题模式（需在 setContentView 前调用） */
    private fun applyThemeMode() {
        val mode = when (Prefs.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setupMiniPlayer() {
        val mini = binding.miniPlayer
        mini.root.setOnClickListener { switchToNowPlaying() }
        mini.miniPlay.setOnClickListener { PlayerControls.toggle(this) }
        mini.miniNext.setOnClickListener { PlayerControls.next(this) }

        PlayerManager.currentSong.observe(this, Observer { song ->
            if (song == null) {
                mini.root.visibility = View.GONE
                return@Observer
            }
            mini.root.visibility = View.VISIBLE
            mini.miniTitle.text = song.title
            mini.miniArtist.text = song.artist
            if (song.albumArtUri != null) mini.miniArt.load(song.albumArtUri)
            else mini.miniArt.setImageDrawable(null)
        })

        PlayerManager.isPlaying.observe(this) { playing ->
            mini.miniPlay.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    /** 供 LibraryFragment 在选曲后跳转到“正在播放” */
    fun switchToNowPlaying() {
        binding.bottomNav.selectedItemId = R.id.nav_now_playing
        updateBackCallback()
    }

    /** 仅在“非曲库页”启用返回拦截，避免曲库页被拦截导致无法退出 */
    private fun updateBackCallback() {
        backCallback.isEnabled = binding.bottomNav.selectedItemId != R.id.nav_library
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .commit()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHost) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = nav)
            insets
        }
    }
}
