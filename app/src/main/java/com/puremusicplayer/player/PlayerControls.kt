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
    const val ACTION_SET_SPEED = "com.puremusicplayer.action.SET_SPEED"
    const val ACTION_SLEEP = "com.puremusicplayer.action.SLEEP"

    const val EXTRA_POSITION = "extra_position"
    const val EXTRA_MODE = "extra_mode"
    const val EXTRA_SPEED = "extra_speed"
    const val EXTRA_SLEEP_MIN = "extra_sleep_min"       // 分钟；<=0 表示取消
    const val EXTRA_SLEEP_FINISH = "extra_sleep_finish" // 1 = 播完当前歌曲后停止

    /** 播放指定曲目：UI 需先设置 PlayerManager.playlist 与 currentIndex */
    fun play(context: Context) = send(context, ACTION_PLAY, foreground = true)

    /** 跳转到播放队列中的指定位置（用于播放队列点击切歌） */
    fun jump(context: Context, index: Int) {
        PlayerManager.currentIndex = index
        send(context, ACTION_PLAY, foreground = true)
    }

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

    /** 设置播放速度（倍数） */
    fun setSpeed(context: Context, speed: Float) {
        val i = Intent(context, PlayerService::class.java)
            .setAction(ACTION_SET_SPEED)
            .putExtra(EXTRA_SPEED, speed)
        context.startService(i)
    }

    /**
     * 设置睡眠定时器。
     * @param minutes 倒计时分钟数（<=0 表示取消）
     * @param finishCurrentTrack 为 true 时忽略分钟数，在当前歌曲播完后停止
     */
    fun sleep(context: Context, minutes: Int = 0, finishCurrentTrack: Boolean = false) {
        val i = Intent(context, PlayerService::class.java)
            .setAction(ACTION_SLEEP)
            .putExtra(EXTRA_SLEEP_MIN, minutes)
            .putExtra(EXTRA_SLEEP_FINISH, if (finishCurrentTrack) 1 else 0)
        context.startService(i)
    }

    private fun send(context: Context, action: String, foreground: Boolean = false) {
        val i = Intent(context, PlayerService::class.java).setAction(action)
        if (foreground) context.startForegroundService(i) else context.startService(i)
    }
}
