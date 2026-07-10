package com.puremusicplayer.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets

/**
 * 读取音频文件内嵌歌词（零依赖、纯 Kotlin 字节解析，保持轻量）。
 * 支持：
 *  - FLAC / OGG：Vorbis Comment 的 LYRICS / UNSYNCEDLYRICS
 *  - MP3：ID3v2 的 USLT / ULT 帧
 *  - M4A / MP4：meta/ilst 下的 ©lyr 原子
 *  - WAV：RIFF LIST/INFO 下的 lyr / ILT 块
 * 仅读取文件头部的元数据块，不加载整首。
 */
object EmbeddedLyrics {

    private const val M4A_BUDGET = 24 * 1024 * 1024 // M4A 容器扫描上限，避免极端情况下读整首大文件

    fun read(context: Context, song: Song): String? {
        val uri = song.contentUri()
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(16)
                val n = stream.read(head)
                if (n <= 0) return null
                val prefix = head.copyOf(n)
                val combined = SequenceInputStream(java.io.ByteArrayInputStream(prefix), stream)
                when (detect(prefix)) {
                    Format.FLAC -> readFlacLyrics(combined)
                    Format.OGG -> readOggLyrics(combined)
                    Format.MP3 -> readMp3Lyrics(combined)
                    Format.MP4 -> readM4ALyrics(combined)
                    Format.WAV -> readWavLyrics(combined)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 格式探测 ----------
    private enum class Format { FLAC, OGG, MP3, MP4, WAV, UNKNOWN }

    private fun detect(head: ByteArray): Format {
        if (eq(head, 'f', 'L', 'a', 'C')) return Format.FLAC
        if (eq(head, 'O', 'g', 'g', 'S')) return Format.OGG
        if (eq(head, 'I', 'D', '3')) return Format.MP3
        if (head.size >= 8 && eq(head.copyOfRange(4, 8), 'f', 't', 'y', 'p')) return Format.MP4
        if (head.size >= 12 && eq(head, 'R', 'I', 'F', 'F') && eq(head.copyOfRange(8, 12), 'W', 'A', 'V', 'E'))
            return Format.WAV
        return Format.UNKNOWN
    }

    private fun eq(b: ByteArray, vararg c: Char): Boolean {
        if (b.size < c.size) return false
        for (i in c.indices) if ((b[i].toInt() and 0xFF).toChar() != c[i]) return false
        return true
    }

    // ---------- FLAC（Vorbis Comment） ----------
    private fun readFlacLyrics(stream: InputStream): String? {
        val dis = DataInputStream(BufferedInputStream(stream))
        val magic = ByteArray(4)
        if (!readFully(dis, magic) || !eq(magic, 'f', 'L', 'a', 'C')) return null

        var lastBlock = false
        while (!lastBlock) {
            val header = dis.read()
            if (header < 0) break
            lastBlock = (header and 0x80) != 0
            val type = header and 0x7F
            val len = (dis.read() shl 16) or (dis.read() shl 8) or dis.read()
            if (len < 0) break
            val block = ByteArray(len)
            if (!readFully(dis, block)) break
            if (type == 4) { // VORBIS_COMMENT
                val lyrics = parseVorbisLyrics(block)
                if (lyrics != null) return lyrics
            }
        }
        return null
    }

    // ---------- OGG（Vorbis Comment，需先解开页/分片组装 packet） ----------
    private fun readOggLyrics(stream: InputStream): String? {
        val dis = DataInputStream(BufferedInputStream(stream))

        val packet = ByteArrayOutputStream()
        var vorbisCount = 0

        while (true) {
            // OGG 页头共 27 字节（含 "OggS" 魔数）：header_type 在 offset 5，段数在 offset 26
            val header = ByteArray(27)
            if (!readFully(dis, header)) break
            if (!eq(header, 'O', 'g', 'g', 'S')) return null
            val nsegs = header[26].toInt() and 0xFF
            val segTable = ByteArray(nsegs)
            if (!readFully(dis, segTable)) break

            var dataLen = 0
            for (s in segTable) dataLen += s.toInt() and 0xFF
            val data = ByteArray(dataLen)
            if (!readFully(dis, data)) break

            packet.write(data)
            var complete = false
            for (s in segTable) if ((s.toInt() and 0xFF) < 255) complete = true

            if (complete) {
                val pkt = packet.toByteArray()
                packet.reset()
                if (pkt.size >= 6 && eq(pkt.copyOfRange(0, 6), 'v', 'o', 'r', 'b', 'i', 's')) {
                    vorbisCount++
                    if (vorbisCount == 2) { // 第二个 vorbis packet = comment header
                        val lyrics = parseVorbisLyrics(pkt.copyOfRange(6, pkt.size))
                        if (lyrics != null) return lyrics
                    }
                }
            }
            if ((header[5].toInt() and 0x04) != 0) break // eos
        }
        return null
    }

    // ---------- MP3（ID3v2 USLT / ULT） ----------
    private fun readMp3Lyrics(stream: InputStream): String? {
        val dis = DataInputStream(BufferedInputStream(stream))
        val id3 = ByteArray(10)
        if (!readFully(dis, id3) || !eq(id3, 'I', 'D', '3')) return null
        val major = id3[3].toInt() and 0xFF
        val flags = id3[4].toInt() and 0xFF
        var remaining = syncsafe(id3, 6)

        // 扩展头（ID3v2.3+）
        if (major >= 3 && (flags and 0x40) != 0) {
            val eh = ByteArray(4)
            if (!readFully(dis, eh)) return null
            val extSize = if (major >= 4) syncsafe(eh, 0) else readBE32(eh, 0)
            skipFully(dis, extSize - 4)
            remaining -= extSize
        }

        val idLen = if (major == 2) 3 else 4
        val sizeLen = if (major == 2) 3 else 4
        val flagsLen = if (major == 2) 0 else 2
        val fhSize = idLen + sizeLen + flagsLen

        while (remaining >= fhSize) {
            val header = ByteArray(fhSize)
            if (!readFully(dis, header)) break
            remaining -= fhSize
            val id = String(header, 0, idLen, StandardCharsets.ISO_8859_1)
            val frameSize = when {
                major == 2 -> readBE24(header, idLen)
                major >= 4 -> syncsafe(header, idLen)
                else -> readBE32(header, idLen)
            }
            if (frameSize <= 0 || frameSize > remaining) break

            if (id == "USLT" || id == "ULT") {
                val content = ByteArray(frameSize)
                if (!readFully(dis, content)) break
                val lyrics = parseUslt(content)
                if (lyrics != null) return lyrics
            } else {
                skipFully(dis, frameSize)
            }
            remaining -= frameSize
        }
        return null
    }

    private fun parseUslt(content: ByteArray): String? {
        if (content.size < 4) return null
        val encoding = content[0].toInt() and 0xFF
        val termLen = if (encoding == 1 || encoding == 2) 2 else 1
        var pos = 4 // encoding(1) + language(3)
        while (pos + termLen <= content.size) {
            var match = true
            for (k in 0 until termLen) {
                if ((content[pos + k].toInt() and 0xFF) != 0) { match = false; break }
            }
            if (match) break
            pos++
        }
        val textStart = pos + termLen
        if (textStart >= content.size) return null
        val textBytes = content.copyOfRange(textStart, content.size)
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
        val text = String(textBytes, charset).trim()
        return if (text.isNotEmpty()) text else null
    }

    // ---------- M4A / MP4（©lyr 原子） ----------
    private fun readM4ALyrics(stream: InputStream): String? {
        val buf = boundedRead(stream, M4A_BUDGET) ?: return null
        return parseM4AAtoms(buf, 0, buf.size, true)
    }

    private val M4A_CONTAINERS = setOf("moov", "udta", "ilst")

    private fun parseM4AAtoms(buf: ByteArray, start: Int, end: Int, top: Boolean): String? {
        var off = start
        while (off + 8 <= end) {
            var size = (readBE32(buf, off).toLong() and 0xFFFFFFFFL)
            val type = String(buf, off + 4, 4, StandardCharsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                size = readBE64(buf, off + 8)
                headerSize = 16
            }
            if (size == 0L) break
            val payloadStart = off + headerSize
            val payloadEnd = (off + size).toInt().coerceAtMost(end)
            if (payloadEnd <= payloadStart) { off = payloadEnd; continue }

            when {
                type == "meta" ->
                    parseM4AAtoms(buf, payloadStart + 4, payloadEnd, false)?.let { return it }
                M4A_CONTAINERS.contains(type) ->
                    parseM4AAtoms(buf, payloadStart, payloadEnd, false)?.let { return it }
                type == "©lyr" ->
                    readM4ALyricData(buf, payloadStart, payloadEnd)?.let { return it }
            }
            off = (off + size).toInt()
        }
        return null
    }

    private fun readM4ALyricData(buf: ByteArray, start: Int, end: Int): String? {
        var off = start
        while (off + 8 <= end) {
            val size = (readBE32(buf, off).toLong() and 0xFFFFFFFFL)
            val type = String(buf, off + 4, 4, StandardCharsets.ISO_8859_1)
            if (size == 0L) break
            if (type == "data") {
                val valStart = off + 16 // 8 头 + 4 version/flags + 4 reserved
                val valEnd = (off + size).toInt().coerceAtMost(end)
                if (valEnd > valStart) {
                    val text = decodeMp4Text(buf, valStart, valEnd - valStart)
                    if (text.isNotBlank()) return text
                }
                return null
            }
            off = (off + size).toInt()
        }
        return null
    }

    private fun decodeMp4Text(buf: ByteArray, start: Int, len: Int): String {
        val bytes = buf.copyOfRange(start, start + len)
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_16)
        }
    }

    // ---------- 通用字节工具 ----------
    private fun parseVorbisLyrics(block: ByteArray): String? {
        if (block.size < 8) return null
        var off = readLE32(block, 0) + 4 // 跳过 vendor 字符串
        val count = readLE32(block, off)
        off += 4

        val collected = mutableListOf<String>()
        repeat(count) {
            if (off + 4 > block.size) return@repeat
            val clen = readLE32(block, off)
            off += 4
            if (off + clen > block.size) return@repeat
            val comment = String(block, off, clen, StandardCharsets.UTF_8)
            off += clen
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS") {
                    collected.add(comment.substring(eq + 1))
                }
            }
        }
        return if (collected.isNotEmpty()) collected.joinToString("\n") else null
    }

    private fun readLE32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readBE32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

    private fun readBE24(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 16) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                (b[off + 2].toInt() and 0xFF)

    private fun readBE64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[off + i].toInt() and 0xFF).toLong()
        return v
    }

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
                ((b[off + 1].toInt() and 0x7F) shl 14) or
                ((b[off + 2].toInt() and 0x7F) shl 7) or
                (b[off + 3].toInt() and 0x7F)

    private fun readFully(dis: DataInputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = dis.read(block, read, block.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(dis: DataInputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val s = dis.skip(remaining.toLong())
            if (s <= 0) {
                val b = ByteArray(minOf(8192, remaining))
                if (dis.read(b) <= 0) break
                remaining -= b.size
            } else {
                remaining -= s.toInt()
            }
        }
    }

    private fun boundedRead(stream: InputStream, max: Int): ByteArray? {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = stream.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            bos.write(buf, 0, n)
            total += n
        }
        val out = bos.toByteArray()
        return if (out.isNotEmpty()) out else null
    }

    // ---------- WAV（RIFF LIST/INFO 的 lyr / ILT） ----------
    private fun readWavLyrics(stream: InputStream): String? {
        val dis = DataInputStream(BufferedInputStream(stream))
        val riff = ByteArray(4)
        if (!readFully(dis, riff) || !eq(riff, 'R', 'I', 'F', 'F')) return null
        skipFully(dis, 4) // RIFF 大小
        val wave = ByteArray(4)
        if (!readFully(dis, wave) || !eq(wave, 'W', 'A', 'V', 'E')) return null

        while (true) {
            val id = ByteArray(4)
            if (!readFully(dis, id)) break
            val size = readLE32Stream(dis)
            if (size < 0) break

            if (eq(id, 'L', 'I', 'S', 'T')) {
                // LIST 块：紧跟 4 字节类型（如 INFO），其内为子块
                val type = ByteArray(4)
                if (!readFully(dis, type)) break
                val text = parseWavList(dis, size - 4)
                if (text != null) return text
            } else if (eq(id, 'l', 'y', 'r', ' ') || eq(id, 'I', 'L', 'T', ' ')) {
                // 顶层歌词块（少见）
                val bytes = ByteArray(size)
                if (!readFully(dis, bytes)) break
                val t = decodeWavText(bytes)
                if (t.isNotBlank()) return t
                if (size % 2 == 1) dis.read()
            } else {
                skipFully(dis, size + (size % 2))
            }
        }
        return null
    }

    private fun parseWavList(dis: DataInputStream, length: Int): String? {
        var left = length
        while (left >= 8) {
            val subId = ByteArray(4)
            if (!readFully(dis, subId)) break
            val subSize = readLE32Stream(dis)
            if (subSize < 0) break
            left -= 8
            if (eq(subId, 'l', 'y', 'r', ' ') || eq(subId, 'I', 'L', 'T', ' ')) {
                val bytes = ByteArray(subSize)
                if (!readFully(dis, bytes)) break
                left -= subSize
                if (subSize % 2 == 1) { dis.read(); left -= 1 }
                val t = decodeWavText(bytes)
                if (t.isNotBlank()) return t
            } else {
                val pad = subSize + (subSize % 2)
                skipFully(dis, pad)
                left -= pad
            }
        }
        return null
    }

    private fun decodeWavText(bytes: ByteArray): String {
        // 去掉尾部空字节与空白，再按 UTF-8 解码（失败回退 ISO-8859-1）
        var end = bytes.size
        while (end > 0 && (bytes[end - 1].toInt() and 0xFF) <= 0x20) end--
        val clean = bytes.copyOfRange(0, end)
        if (clean.isEmpty()) return ""
        return try {
            String(clean, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            String(clean, StandardCharsets.ISO_8859_1)
        }
    }

    private fun readLE32Stream(dis: DataInputStream): Int {
        val b = ByteArray(4)
        if (!readFully(dis, b)) return -1
        return readLE32(b, 0)
    }
}
