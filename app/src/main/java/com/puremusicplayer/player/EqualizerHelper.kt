package com.puremusicplayer.player

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * 轻量均衡器封装（基于 Android 框架原生 Equalizer API，零额外依赖）。
 * 支持预设切换，自动绑定到 MediaPlayer 的音频会话。
 *
 * 保持精简：不引入自定义频段滑块 UI，仅提供常用音效预设（Normal / Rock / Pop / Jazz / Classical / Bass Boost）。
 * 设置页可开关均衡器，播放页可快速切换预设。
 */
object EqualizerHelper {

    private const val TAG = "EqualizerHelper"

    private var equalizer: Equalizer? = null
    private var enabled = false

    /** 预设名称与对应的频段增益值（归一化 -100..100） */
    enum class Preset(val label: String, val profile: Map<String, Int>) {
        NORMAL("普通", mapOf()),
        ROCK("摇滚", mapOf("63" to 40, "250" to 30, "1k" to -20, "4k" to 10, "16k" to 50)),
        POP("流行", mapOf("63" to -10, "250" to 25, "1k" to 30, "4k" to 20, "16k" to 10)),
        JAZZ("爵士", mapOf("63" to 20, "250" to 10, "1k" to 0, "4k" to 10, "16k" to 30)),
        CLASSICAL("古典", mapOf("63" to 30, "250" to 20, "1k" to -10, "4k" to -10, "16k" to 40)),
        BASS("重低音", mapOf("63" to 60, "250" to 40, "1k" to -20, "4k" to -30, "16k" to -30))
    }

    /** 初始化并绑定到指定音频会话 */
    fun init(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        try {
            equalizer = Equalizer(0, audioSessionId)
            Log.d(TAG, "Equalizer initialized for session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init Equalizer: ${e.message}")
            equalizer = null
        }
    }

    /** 开启/关闭均衡器 */
    fun setEnabled(on: Boolean) {
        enabled = on
        equalizer?.enabled = on
        Log.d(TAG, "Equalizer enabled=$on")
    }

    fun isEnabled(): Boolean = enabled

    /** 应用预设 */
    fun applyPreset(preset: Preset) {
        val eq = equalizer ?: return
        try {
            val bands = eq.numberOfBands
            // 先将所有频段重置为 0
            for (i in 0 until bands) {
                eq.setBandLevel(i.toShort(), 0)
            }
            // 再按预设调整个别频段
            for ((freqStr, gain) in preset.profile) {
                val freqHz = when (freqStr) {
                    "63" -> 63; "250" -> 250; "1k" -> 1000
                    "4k" -> 4000; "16k" -> 16000
                    else -> continue
                }
                // 找到最接近该频率的频段
                var bestBand: Short = -1
                var bestDiff = Long.MAX_VALUE
                for (b in 0 until bands) {
                    val centerFreq = eq.getCenterFreq(b.toShort()).toLong()
                    val diff = kotlin.math.abs(centerFreq - freqHz.toLong())
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestBand = b.toShort()
                    }
                }
                if (bestBand >= 0) {
                    val milliBels = (gain * 15).coerceIn(
                        eq.bandLevelRange[0].toInt(),
                        eq.bandLevelRange[1].toInt()
                    )
                    eq.setBandLevel(bestBand, milliBels.toShort())
                }
            }
            if (enabled) eq.enabled = true
            Log.d(TAG, "Preset '${preset.label}' applied")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply preset: ${e.message}")
        }
    }

    /** 释放均衡器资源 */
    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) { }
        equalizer = null
        enabled = false
    }
}
