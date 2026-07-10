package com.puremusicplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.puremusicplayer.databinding.ActivityMainBinding
import com.puremusicplayer.ui.LibraryFragment
import com.puremusicplayer.ui.NowPlayingFragment
import com.puremusicplayer.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16 强制边到边：由我们自行处理系统栏 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()

        if (savedInstanceState == null) switchFragment(LibraryFragment())
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> switchFragment(LibraryFragment())
                R.id.nav_now_playing -> switchFragment(NowPlayingFragment())
                R.id.nav_settings -> switchFragment(SettingsFragment())
            }
            true
        }
    }

    /** 供 LibraryFragment 在选曲后跳转到“正在播放” */
    fun switchToNowPlaying() {
        binding.bottomNav.selectedItemId = R.id.nav_now_playing
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
