package com.puremusicplayer.player

import android.content.Context
import android.content.Intent
import com.puremusicplayer.player.PlayerService

/**
 * 向 PlayerService 发送控制指令的轻量封装。
 * 首次播放用 startForegroundService，后续控制用 startService。
 */
object PlayerControls {
    const val ACTION_PLAY = "com.puremusicplayer.action.PLAY"
    const val ACTION_PAUSE = "com.puremusicplayer.action.PAUSE"
    const val ACTION_PLAY_PAUSE = "com.puremusicplayer.action.PLAY_PAUSE"
    const val ACTION_NEXT = "com.puremusicplayer.action.NEXT"
    const val ACTION_PREV = "com.puremusicplayer.action.PREV"
    const val ACTION_SEEK = "com.puremusicplayer.action.SEEK"
    const val ACTION_SET_MODE = "com.puremusicplayer.action.SET_MODE"

    const val EXTRA_POSITION = "extra_position"
    const val EXTRA_MODE = "extra_mode"

    /** 播放指定曲目：UI 需先设置 PlayerManager.playlist 与 currentIndex */
    fun play(context: Context) = send(context, ACTION_PLAY, foreground = true)

    fun pause(context: Context) = send(context, ACTION_PAUSE)
    fun toggle(context: Context) = send(context, ACTION_PLAY_PAUSE)
    fun next(context: Context) = send(context, ACTION_NEXT)
    fun prev(context: Context) = send(context, ACTION_PREV)

    fun seek(context: Context, posMs: Int) {
        val i = Intent(context, PlayerService::class.java)
            .setAction(ACTION_SEEK)
            .putExtra(EXTRA_POSITION, posMs)
        context.startService(i)
    }

    fun setMode(context: Context, mode: PlayMode) {
        val i = Intent(context, PlayerService::class.java)
            .setAction(ACTION_SET_MODE)
            .putExtra(EXTRA_MODE, mode.ordinal)
        context.startService(i)
    }

    private fun send(context: Context, action: String, foreground: Boolean = false) {
        val i = Intent(context, PlayerService::class.java).setAction(action)
        if (foreground) context.startForegroundService(i) else context.startService(i)
    }
}
