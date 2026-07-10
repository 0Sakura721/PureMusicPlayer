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
        val lines = mutableListOf<LyricLine>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val tags = TIME_TAG.findAll(line).toList()
                if (tags.isEmpty()) return@forEachLine
                val text = line.replace(TIME_TAG, "").trim()
                for (tag in tags) {
                    val min = tag.groupValues[1].toLongOrNull() ?: 0L
                    val sec = tag.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = tag.groupValues[3]
                    val frac = when {
                        fracStr.isEmpty() -> 0L
                        else -> fracStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    }
                    val timeMs = (min * 60 + sec) * 1000 + frac
                    lines.add(LyricLine(timeMs, text))
                }
            }
        }
        lines.sortBy { it.timeMs }
        return lines
    }
}
