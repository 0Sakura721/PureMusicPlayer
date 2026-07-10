package com.puremusicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 音频文件技术信息（零依赖、纯字节解析，保持轻量）。
 * 用于播放页封面下方展示，如「FLAC · 24 bit · 96 kHz」。
 *
 * 字段含义：
 *  - format：容器/编码格式（FLAC / WAV / MP3 / OGG / M4A）
 *  - bitDepth：位深（bit）；有损格式（MP3/OGG/AAC）为 0
 *  - sampleRate：采样率（Hz）
 *  - bitrate：码率（kbps）；无损/未知时为 0
 *  - channels：声道数
 */
data class AudioFileInfo(
    val format: String,
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val channels: Int = 0
) {
    /** 展示字符串，如「FLAC · 24 bit · 96 kHz」 */
    fun toDisplay(): String {
        val parts = mutableListOf<String>()
        parts += format
        if (bitDepth > 0) parts += "$bitDepth bit"
        if (sampleRate > 0) parts += "${sampleRate / 1000} kHz"
        // 有损格式用码率补充（无损已用位深表达）
        if (bitrate > 0 && bitDepth == 0) parts += "$bitrate kbps"
        return parts.joinToString(" · ")
    }

    companion object {
        private const val HEADER_BUDGET = 512 * 1024       // FLAC/WAV/MP3/OGG 仅读头部
        private const val M4A_BUDGET = 6 * 1024 * 1024     // M4A 容器较大，给一个上限

        private const val F_FLAC = "FLAC"
        private const val F_WAV = "WAV"
        private const val F_MP3 = "MP3"
        private const val F_OGG = "OGG"
        private const val F_M4A = "M4A"

        /**
         * 探测一首歌的文件信息。在后台线程调用（可能读取数 MB）。
         * 读取失败时返回 null，调用方应静默忽略。
         */
        fun probe(context: Context, song: Song): AudioFileInfo? {
            val uri = song.contentUri()
            val fmt = detectFormat(context, uri) ?: return null
            val budget = if (fmt == F_M4A) M4A_BUDGET else HEADER_BUDGET
            val bytes = readBytes(context.contentResolver, uri, budget) ?: return null
            return when (fmt) {
                F_FLAC -> parseFlac(bytes)
                F_WAV -> parseWav(bytes)
                F_MP3 -> parseMp3(bytes)
                F_OGG -> parseOgg(bytes)
                F_M4A -> parseM4A(bytes)
                else -> null
            }
        }

        // ---------- 格式探测 ----------
        private fun detectFormat(context: Context, uri: Uri): String? {
            val head = ByteArray(16)
            return try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val n = s.read(head)
                    if (n <= 0) return null
                    val b = head.copyOf(n)
                    when {
                        eq(b, 'f', 'L', 'a', 'C') -> F_FLAC
                        eq(b, 'O', 'g', 'g', 'S') -> F_OGG
                        eq(b, 'I', 'D', '3') -> F_MP3
                        b.size >= 12 && eq(b, 'R', 'I', 'F', 'F') && eq(b.copyOfRange(8, 12), 'W', 'A', 'V', 'E') -> F_WAV
                        b.size >= 8 && eq(b.copyOfRange(4, 8), 'f', 't', 'y', 'p') -> F_M4A
                        else -> null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun eq(b: ByteArray, vararg c: Char): Boolean {
            if (b.size < c.size) return false
            for (i in c.indices) if ((b[i].toInt() and 0xFF).toChar() != c[i]) return false
            return true
        }

        // ---------- FLAC（STREAMINFO 块） ----------
        private fun parseFlac(b: ByteArray): AudioFileInfo? {
            if (b.size < 8 + 34) return null
            if (!eq(b, 'f', 'L', 'a', 'C')) return null
            // block 头：1 字节 type/last + 3 字节长度（应为 34）
            val len = (b[5].toInt() and 0xFF shl 16) or (b[6].toInt() and 0xFF shl 8) or (b[7].toInt() and 0xFF)
            if (len != 34) return null
            val block = b.copyOfRange(8, 8 + 34)
            // 位域相对 block 起点：采样率 20bit@84，声道 3bit@104，位深 5bit@107
            val sampleRate = readBitsBE(block, 84, 20)
            val channels = readBitsBE(block, 104, 3) + 1
            val bits = readBitsBE(block, 107, 5) + 1
            return AudioFileInfo(F_FLAC, bits, sampleRate, 0, channels)
        }

        // ---------- WAV（fmt 块） ----------
        private fun parseWav(b: ByteArray): AudioFileInfo? {
            if (b.size < 12) return null
            var off = 12
            while (off + 8 <= b.size) {
                val id = String(b, off, 4, Charsets.US_ASCII)
                val size = le32(b, off + 4)
                if (id == "fmt ") {
                    val start = off + 8
                    val channels = le16(b, start + 2)
                    val sampleRate = le32(b, start + 4)
                    val bits = le16(b, start + 14)
                    return AudioFileInfo(F_WAV, bits, sampleRate, 0, channels)
                }
                off += 8 + size + (size and 1)
            }
            return null
        }

        // ---------- MP3（首帧帧头） ----------
        private fun parseMp3(b: ByteArray): AudioFileInfo {
            for (i in 0 until b.size - 3) {
                if ((b[i].toInt() and 0xFF) != 0xFF) continue
                val b1 = b[i + 1].toInt() and 0xFF
                if ((b1 and 0xE0) != 0xE0) continue          // 同步字 11 位
                val version = (b1 shr 3) and 0x03             // 3=MPEG1,2=MPEG2,0=MPEG2.5
                val layer = (b1 shr 1) and 0x03              // 3=LayerI,2=II,1=III
                val b2 = b[i + 2].toInt() and 0xFF
                val brIndex = (b2 shr 4) and 0x0F
                val srIndex = (b2 shr 2) and 0x03
                val b3 = b[i + 3].toInt() and 0xFF
                val channelMode = (b3 shr 6) and 0x03
                if (layer == 0 || brIndex == 0 || brIndex == 15 || srIndex == 3) continue

                val sampleRate = when (version) {
                    3 -> intArrayOf(44100, 48000, 32000)[srIndex]
                    2 -> intArrayOf(22050, 24000, 16000)[srIndex]
                    else -> intArrayOf(11025, 12000, 8000)[srIndex]
                }
                val brTable = when (version) {
                    3 -> when (layer) {                 // MPEG1
                        3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
                        2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
                        else -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                    }
                    else -> when (layer) {              // MPEG2 / 2.5
                        3 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
                        else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                    }
                }
                val bitrate = brTable.getOrElse(brIndex) { 0 }
                val channels = if (channelMode == 3) 1 else 2
                return AudioFileInfo(F_MP3, 0, sampleRate, bitrate, channels)
            }
            return AudioFileInfo(F_MP3)
        }

        // ---------- OGG（Vorbis identification 头） ----------
        private fun parseOgg(b: ByteArray): AudioFileInfo? {
            var off = 0
            val packet = ByteArrayOutputStream()
            while (off + 27 <= b.size) {
                if (!eq(b.copyOfRange(off, off + 4), 'O', 'g', 'g', 'S')) return null
                val headerType = b[off + 5].toInt() and 0xFF
                val nsegs = b[off + 26].toInt() and 0xFF
                off += 27
                if (off + nsegs > b.size) return null
                var dataLen = 0
                for (s in 0 until nsegs) dataLen += b[off + s].toInt() and 0xFF
                off += nsegs
                if (off + dataLen > b.size) return null
                packet.write(b, off, dataLen)
                var complete = false
                for (s in 0 until nsegs) if ((b[off - nsegs + s].toInt() and 0xFF) < 255) complete = true
                off += dataLen
                if (complete) {
                    val pkt = packet.toByteArray()
                    packet.reset()
                    if (pkt.size >= 7 && eq(pkt.copyOfRange(0, 6), 'v', 'o', 'r', 'b', 'i', 's')) {
                        // identification 头：magic(6) + version(4) + channels(1) + sampleRate(4) + ...
                        val channels = pkt[10].toInt() and 0xFF
                        val sampleRate = le32(pkt, 11)
                        val nominal = le32(pkt, 15)
                        val bitrate = if (nominal > 0) nominal / 1000 else 0
                        return AudioFileInfo(F_OGG, 0, sampleRate, bitrate, channels)
                    }
                }
                if ((headerType and 0x04) != 0) break // eos
            }
            return null
        }

        // ---------- M4A（esds → AudioSpecificConfig） ----------
        private fun parseM4A(b: ByteArray): AudioFileInfo? {
            val esds = findAtom(b, "esds") ?: return AudioFileInfo(F_M4A)
            // esds 载荷：version(1)+flags(3) + 描述符
            val payload = esds.copyOfRange(4, esds.size)
            val dsi = findDescriptor(payload, 0x05) ?: return AudioFileInfo(F_M4A)
            if (dsi.size < 2) return AudioFileInfo(F_M4A)
            val audioObjectType = (dsi[0].toInt() and 0xFF shr 3) and 0x1F
            val freqIndex = ((dsi[0].toInt() and 0x07) shl 1) or ((dsi[1].toInt() and 0xFF) shr 7)
            val channels = (dsi[1].toInt() and 0x78) shr 3
            val sampleRate = when (freqIndex) {
                0 -> 96000; 1 -> 88200; 2 -> 64000; 3 -> 48000; 4 -> 44100
                5 -> 32000; 6 -> 24000; 7 -> 22050; 8 -> 16000; 9 -> 12000
                10 -> 11025; 11 -> 8000; 12 -> 7350
                else -> 0
            }
            // 码率（如有）从 DecoderConfig 描述符取 avgBitrate
            var bitrate = 0
            val decCfg = findDescriptor(payload, 0x04)
            if (decCfg != null && decCfg.size >= 13) {
                bitrate = le32(decCfg, 9) / 1000
            }
            return AudioFileInfo(F_M4A, 0, sampleRate, bitrate, channels)
        }

        // 在字节流中查找名为 type 的 atom，返回含 8 字节头部（size+type）的完整块
        private fun findAtom(b: ByteArray, type: String): ByteArray? {
            var off = 0
            while (off + 8 <= b.size) {
                var size = le32(b, off).toLong() and 0xFFFFFFFFL
                val t = String(b, off + 4, 4, Charsets.US_ASCII)
                if (size == 1L) { size = le64(b, off + 8); }
                if (size <= 0L || off + size > b.size) break
                if (t == type) return b.copyOfRange(off, (off + size).toInt())
                // 下钻到可能含 esds 的容器
                if (t == "moov" || t == "trak" || t == "mdia" || t == "minf" ||
                    t == "stbl" || t == "stsd" || t == "mp4a" || t == "udta" || t == "edts"
                ) {
                    findAtom(b.copyOfRange(off + 8, (off + size).toInt()), type)?.let { return it }
                }
                off += size.toInt()
            }
            return null
        }

        // 在描述符数据（跳过 version+flags 后）中查找指定 tag 的描述符，返回其 data 段。
        // 描述符可嵌套，故未命中时递归进入描述符内部继续查找。
        private fun findDescriptor(payload: ByteArray, tag: Int): ByteArray? {
            var off = 4 // 跳过 version(1)+flags(3)
            while (off + 2 <= payload.size) {
                val t = payload[off].toInt() and 0xFF
                val (hlen, dlen) = readDescriptorLength(payload, off + 1)
                val dataStart = off + 1 + hlen
                val dataEnd = (dataStart + dlen).coerceAtMost(payload.size)
                if (t == tag) return payload.copyOfRange(dataStart, dataEnd)
                if (dataEnd > dataStart) {
                    findDescriptor(payload.copyOfRange(dataStart, dataEnd), tag)?.let { return it }
                }
                off = dataEnd
            }
            return null
        }

        // ---------- 通用字节工具 ----------
        private fun readDescriptorLength(b: ByteArray, start: Int): Pair<Int, Int> {
            var size = 0
            var i = start
            var hlen = 0
            while (i < b.size) {
                val byte = b[i].toInt() and 0xFF
                size = (size shl 7) or (byte and 0x7F)
                hlen++
                i++
                if ((byte and 0x80) == 0) break
            }
            return Pair(hlen, size)
        }

        private fun readBitsBE(b: ByteArray, bitStart: Int, len: Int): Int {
            var value = 0
            for (i in 0 until len) {
                val bitIndex = bitStart + i
                val byteIndex = bitIndex ushr 3
                val bitInByte = 7 - (bitIndex and 7)
                val bit = (b[byteIndex].toInt() ushr bitInByte) and 1
                value = (value shl 1) or bit
            }
            return value
        }

        private fun le16(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF) or
                    ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or
                    ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun le64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0..7) v = (v shl 8) or (b[off + i].toInt() and 0xFF).toLong()
            return v
        }

        private fun readBytes(resolver: ContentResolver, uri: Uri, max: Int): ByteArray? {
            return try {
                resolver.openInputStream(uri)?.use { s ->
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (total < max) {
                        val n = s.read(buf, 0, minOf(buf.size, max - total))
                        if (n < 0) break
                        bos.write(buf, 0, n)
                        total += n
                    }
                    val out = bos.toByteArray()
                    if (out.isNotEmpty()) out else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
