package com.puremusicplayer.player

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 一句歌词：时间戳（毫秒）+ 原文 + 可选译文。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

/**
 * 轻量 LRC 解析器（原创实现，不依赖第三方库）。
 * 支持同一行多个时间标签（[00:01.00][00:05.00]text），
 * 支持 [mm:ss.xx] 与 [mm:ss.xxx] 两种精度。
 *
 * 双语/翻译识别：
 *  - 相同时间戳的相邻行：后一行作为前一行译文（双语 LRC 最常见格式，适用任意语言对）。
 *  - 单句内用分隔符（/ ｜ | ／ 、）连接的中外双语：仅当两侧脚本不同
 *    （一侧含中日韩表意文字，另一侧不含）才判定为译文，避免误拆 "Verse 1 / 2"。
 */
object LyricsParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val INLINE_SEP = Regex("\\s*[\\/｜|／、]\\s*")

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
        val timed = mutableListOf<LyricLine>()
        var hasTimed = false

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            hasTimed = true
            val content = line.replace(TIME_TAG, "").trim()
            val (orig, trans) = splitInline(content)
            for (tag in tags) {
                timed.add(LyricLine(parseTag(tag), orig, trans))
            }
        }

        val base: List<LyricLine> = if (hasTimed) timed else rawLines.mapNotNull { t ->
            t.trim().takeIf { it.isNotEmpty() }?.let { c ->
                val (o, tr) = splitInline(c)
                LyricLine(0, o, tr)
            }
        }

        // 仅对带时间戳的歌词做“同时间戳合并译文”，纯文本兜底不合并（否则全部 time=0 会合并成一句）
        val merged = if (hasTimed) mergeSameTime(base) else base
        return merged.sortedBy { it.timeMs }
    }

    private fun parseTag(tag: MatchResult): Long {
        val min = tag.groupValues[1].toLongOrNull() ?: 0L
        val sec = tag.groupValues[2].toLongOrNull() ?: 0L
        val fracStr = tag.groupValues[3]
        val frac = when {
            fracStr.isEmpty() -> 0L
            else -> fracStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        }
        return (min * 60 + sec) * 1000 + frac
    }

    /**
     * 行内译文识别：用分隔符切开为「原文 / 译文」。
     * 仅当两侧脚本不同（一侧含中日韩表意文字，另一侧不含）才判定为译文，
     * 避免把 "Verse 1 / 2" 这类同语言内容误拆。
     */
    private fun splitInline(text: String): Pair<String, String?> {
        if (text.isEmpty()) return "" to null
        val parts = text.split(INLINE_SEP).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return text to null
        val a = parts[0]
        val b = parts[1]
        if (hasCjk(a) == hasCjk(b)) return text to null
        return a to b
    }

    /** 合并相同时间戳的相邻行：后一行作为前一行译文（常见于双语 LRC） */
    private fun mergeSameTime(list: List<LyricLine>): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var i = 0
        while (i < list.size) {
            val cur = list[i]
            if (i + 1 < list.size && cur.translation == null &&
                list[i + 1].timeMs == cur.timeMs
            ) {
                out.add(cur.copy(translation = list[i + 1].text))
                i += 2
            } else {
                out.add(cur)
                i += 1
            }
        }
        return out
    }

    /** 是否包含中日韩表意文字（含日文假名、韩文音节） */
    private fun hasCjk(s: String): Boolean = s.any { c ->
        c in '\u4E00'..'\u9FFF' || c in '\u3040'..'\u30FF' || c in '\uAC00'..'\uD7AF'
    }
}
