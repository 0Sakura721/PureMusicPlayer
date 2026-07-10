package com.puremusicplayer.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量偏好存储（SharedPreferences）。
 * 记录可视化 / 歌词动画 / 动态主题 等开关，以及播放设置。
 */
object Prefs {
    private const val NAME = "puremusic_prefs"
    private const val KEY_VISUALIZER = "visualizer"
    private const val KEY_LYRICS = "lyrics_anim"
    private const val KEY_DYNAMIC_THEME = "dynamic_theme"
    private const val KEY_PLAY_MODE = "play_mode"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    var visualizerEnabled: Boolean
        get() = sp.getBoolean(KEY_VISUALIZER, true)
        set(v) = sp.edit().putBoolean(KEY_VISUALIZER, v).apply()

    var lyricsAnimEnabled: Boolean
        get() = sp.getBoolean(KEY_LYRICS, true)
        set(v) = sp.edit().putBoolean(KEY_LYRICS, v).apply()

    var dynamicThemeEnabled: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_THEME, true)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC_THEME, v).apply()

    var playModeOrdinal: Int
        get() = sp.getInt(KEY_PLAY_MODE, 2) // 默认 REPEAT_ALL
        set(v) = sp.edit().putInt(KEY_PLAY_MODE, v).apply()
}
