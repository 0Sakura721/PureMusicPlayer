package com.puremusicplayer.player

import android.media.audiofx.Equalizer
import android.util.Log

object EqualizerHelper {
    private const val TAG = "EqualizerHelper"
    private var equalizer: Equalizer? = null
    private var enabled = false

    enum class Preset(val label: String, val profile: Map<String, Int>) {
        NORMAL("普通", mapOf()),
        ROCK("摇滚", mapOf("63" to 40, "250" to 30, "1k" to -20, "4k" to 10, "16k" to 50)),
        POP("流行", mapOf("63" to -10, "250" to 25, "1k" to 30, "4k" to 20, "16k" to 10)),
        JAZZ("爵士", mapOf("63" to 20, "250" to 10, "1k" to 0, "4k" to 10, "16k" to 30)),
        CLASSICAL("古典", mapOf("63" to 30, "250" to 20, "1k" to -10, "4k" to -10, "16k" to 40)),
        BASS("重低音", mapOf("63" to 60, "250" to 40, "1k" to -20, "4k" to -30, "16k" to -30))
    }

    fun init(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        try { equalizer = Equalizer(0, audioSessionId) } catch (_: Exception) { equalizer = null }
    }

    fun setEnabled(on: Boolean) { enabled = on; equalizer?.enabled = on }

    fun isEnabled(): Boolean = enabled
    fun isInitialized(): Boolean = equalizer != null

    fun applyPreset(preset: Preset) {
        val eq = equalizer ?: return
        try {
            val bands = eq.numberOfBands
            for (i in 0 until bands) eq.setBandLevel(i.toShort(), 0.toShort())
            if (preset.profile.isEmpty()) return
            // 多数设备 EQ 为 5 频段：60/230/910/3600/14000 Hz
            val map = mapOf("63" to 0, "250" to 1, "1k" to 2, "4k" to 3, "16k" to 4)
            for ((freqStr, gain) in preset.profile) {
                val idx = map[freqStr] ?: continue
                if (idx < bands) eq.setBandLevel(idx.toShort(), (gain * 15).toShort())
            }
            if (enabled) eq.enabled = true
        } catch (_: Exception) {}
    }

    fun release() {
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) {}
        equalizer = null; enabled = false
    }

    val numberOfBands: Int get() = equalizer?.numberOfBands?.toInt() ?: 0
    fun getEq(): Equalizer? = equalizer
}
