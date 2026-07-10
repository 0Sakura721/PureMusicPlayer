package com.puremusicplayer.player

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 一句歌词：时间戳（毫秒）+ 文本。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 轻量 LRC 解析器（原创实现，不依赖第三方库）。
 * 支持同一行多个时间标签（[00:01.00][00:05.00]text），
 * 支持 [mm:ss.xx] 与 [mm:ss.xxx] 两种精度。
 */
object LyricsParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(input: InputStream): List<LyricLine> {
        val text = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        return parseText(text)
    }

    /**
     * 从完整文本解析歌词。
     * - 含 [mm:ss.xx] 等时间标签：按 LRC 解析为多行带时间歌词。
     * - 完全无时间标签（如 FLAC 内嵌的纯文本歌词）：逐行兜底展示，
     *   避免一首有词却因缺时间戳而被整段丢弃。
     */
    fun parseText(text: String): List<LyricLine> {
        val rawLines = text.split('\n')
        val lines = mutableListOf<LyricLine>()
        var hasTimed = false

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            hasTimed = true
            val content = line.replace(TIME_TAG, "").trim()
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: 0L
                val sec = tag.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = tag.groupValues[3]
                val frac = when {
                    fracStr.isEmpty() -> 0L
                    else -> fracStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                }
                val timeMs = (min * 60 + sec) * 1000 + frac
                lines.add(LyricLine(timeMs, content))
            }
        }

        // 纯文本兜底：没有一行带时间标签时，整段逐行展示
        if (!hasTimed) {
            for (raw in rawLines) {
                val t = raw.trim()
                if (t.isNotEmpty()) lines.add(LyricLine(0, t))
            }
        }

        lines.sortBy { it.timeMs }
        return lines
    }
}
