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
    private const val KEY_MUSIC_TREE_URI = "music_tree_uri"
    private const val KEY_MUSIC_DIR_NAME = "music_dir_name"
    private const val KEY_THEME_MODE = "theme_mode"       // 0 跟随系统 / 1 浅色 / 2 深色
    private const val KEY_VIS_STYLE = "vis_style"         // 0 条形 / 1 圆形 / 2 波形
    private const val KEY_ACCENT = "accent_color"         // DIY 强调色；-1 表示使用默认
    private const val KEY_FAVORITES = "favorites"          // 收藏歌曲主键集合（Set<String>）
    private const val KEY_PLAYBACK_SPEED = "playback_speed" // 倍速：1.0 为正常
    private const val KEY_PAUSE_ON_UNPLUG = "pause_on_unplug" // 耳机/蓝牙拔出自动暂停

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

    /** 用户选定的音乐目录（SAF 文档树 Uri 字符串）；为空表示扫描整个媒体库 */
    var musicTreeUri: String?
        get() = sp.getString(KEY_MUSIC_TREE_URI, null)
        set(v) = sp.edit().putString(KEY_MUSIC_TREE_URI, v).apply()

    var musicDirName: String?
        get() = sp.getString(KEY_MUSIC_DIR_NAME, null)
        set(v) = sp.edit().putString(KEY_MUSIC_DIR_NAME, v).apply()

    /** 明暗主题模式：0 跟随系统 / 1 浅色 / 2 深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, 0)
        set(v) = sp.edit().putInt(KEY_THEME_MODE, v).apply()

    /** 可视化样式：0 条形 / 1 圆形 / 2 波形 */
    var visualizerStyle: Int
        get() = sp.getInt(KEY_VIS_STYLE, 0)
        set(v) = sp.edit().putInt(KEY_VIS_STYLE, v).apply()

    /** DIY 个性强调色；-1 表示使用默认品牌色 */
    var accentColor: Int
        get() = sp.getInt(KEY_ACCENT, -1)
        set(v) = sp.edit().putInt(KEY_ACCENT, v).apply()

    /** 收藏歌曲的主键集合（Song.favKey）；返回可变副本，写回请用 favorites = set */
    var favorites: MutableSet<String>
        get() = (sp.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()
        set(v) = sp.edit().putStringSet(KEY_FAVORITES, v.toSet()).apply()

    fun isFavorite(key: String): Boolean = favorites.contains(key)

    /** 切换收藏状态，返回切换后的“是否已收藏” */
    fun toggleFavorite(key: String): Boolean {
        val set = favorites
        val nowFav = if (set.contains(key)) {
            set.remove(key); false
        } else {
            set.add(key); true
        }
        favorites = set
        return nowFav
    }

    /** 倍速播放：1.0 为正常速度，范围 0.5~2.0 */
    var playbackSpeed: Float
        get() = sp.getFloat(KEY_PLAYBACK_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        set(v) = sp.edit().putFloat(KEY_PLAYBACK_SPEED, v.coerceIn(0.5f, 2.0f)).apply()

    /** 耳机/蓝牙拔出时自动暂停（默认开启） */
    var pauseOnUnplug: Boolean
        get() = sp.getBoolean(KEY_PAUSE_ON_UNPLUG, true)
        set(v) = sp.edit().putBoolean(KEY_PAUSE_ON_UNPLUG, v).apply()
}
