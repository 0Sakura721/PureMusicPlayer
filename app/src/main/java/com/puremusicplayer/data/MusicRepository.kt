package com.puremusicplayer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 本地曲库数据源。
 * 通过 MediaStore 扫描设备上的音乐文件，并支持按“同名 .lrc”匹配歌词文件。
 *
 * 设计取向（Salt 风）：只读取本地媒体库，不做任何上传/网络请求。
 */
object MusicRepository {

    /**
     * 扫描外部存储中的音乐。
     * 过滤条件：IS_MUSIC=1 且时长 > 30s，剔除铃声/闹铃等短音频。
     */
    fun loadSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val resolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} = ? AND ${MediaStore.Audio.Media.DURATION} > ?"
        val selectionArgs = arrayOf("1", "30000")
        // 用 COLLATE NOCASE（各 Android 版本稳定支持）。
        // 注意：避免 COLLATE LOCALIZED —— 部分版本（含 Android 16 的 ICU 变更）会让 query() 抛 SQLiteException。
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            // 列索引在查询成功后解析一次；用 getColumnIndex（缺失返回 -1）而非 OrThrow，避免缺失列直接崩溃
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

            if (idCol < 0) return@use  // 关键列缺失则安全跳过，不抛异常

            while (cursor.moveToNext()) {
                songs.add(
                    Song(
                        id = cursor.getLong(idCol),
                        title = if (titleCol >= 0) (cursor.getString(titleCol) ?: "未知标题") else "未知标题",
                        artist = if (artistCol >= 0) (cursor.getString(artistCol) ?: "未知艺术家") else "未知艺术家",
                        album = if (albumCol >= 0) (cursor.getString(albumCol) ?: "未知专辑") else "未知专辑",
                        albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L,
                        duration = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                        data = if (dataCol >= 0) (cursor.getString(dataCol) ?: "") else "",
                        size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    )
                )
            }
        }
        return songs
    }

    /**
     * 查找与某首歌曲同目录、同名的歌词文件（.lrc / .LRC / .txt）。
     * 优先用 MediaStore.Files 查询（适配 Android 10+ 分区存储），
     * 旧版本或查询失败时回退到文件路径。
     * 返回可被 ContentResolver 打开的 Uri；找不到返回 null。
     */
    fun findLrcUri(context: Context, audioData: String): Uri? {
        val fileName = audioData.substringAfterLast('/')
        val baseName = fileName.substringBeforeLast('.')
        if (baseName.isEmpty()) return null

        val parent = audioData.substringBeforeLast('/')
        // 计算相对于外部存储根的相对路径（如 Music/），用于 Files 表精确定位
        val relativePath = parent
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/storage/emulated/")
            .removePrefix("/sdcard/")
            .let { if (it.isEmpty() || it == parent) null else "$it/" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
            val filesUri = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(MediaStore.Files.FileColumns._ID)
            val placeholders = listOf("$baseName.lrc", "$baseName.LRC", "$baseName.txt")
                .joinToString(",") { "?" }
            val sel =
                "(${MediaStore.Files.FileColumns.DISPLAY_NAME} IN ($placeholders)) " +
                        "AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
            val args = arrayOf("$baseName.lrc", "$baseName.LRC", "$baseName.txt", relativePath)
            context.contentResolver.query(filesUri, proj, sel, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                if (c.moveToFirst()) {
                    val id = c.getLong(idCol)
                    return Uri.withAppendedPath(filesUri, id.toString())
                }
            }
        }

        // 回退：直接按文件路径读取
        for (ext in listOf(".lrc", ".LRC", ".txt")) {
            val f = java.io.File(parent, baseName + ext)
            if (f.exists()) return Uri.fromFile(f)
        }
        return null
    }

    /**
     * 读取音频文件内嵌的歌词（目前支持 FLAC 的 Vorbis Comment 标签）。
     * 仅流式读取文件头部的 metadata 块，不会加载整首文件，保持轻量。
     * 命中 LYRICS / UNSYNCEDLYRICS 标签时返回其文本，否则返回 null。
     */
    fun findEmbeddedLyrics(context: Context, song: Song): String? {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
        )
        return try {
            context.contentResolver.openInputStream(uri)?.use { readFlacLyrics(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFlacLyrics(stream: InputStream): String? {
        val dis = DataInputStream(BufferedInputStream(stream))
        val magic = ByteArray(4)
        if (dis.read(magic) != 4 || !magic.contentEquals("fLaC".toByteArray())) return null

        var lastBlock = false
        while (!lastBlock) {
            val header = dis.read()
            if (header < 0) break
            lastBlock = (header and 0x80) != 0
            val type = header and 0x7F
            val len = (dis.read() shl 16) or (dis.read() shl 8) or dis.read()
            if (len < 0) break

            val block = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = dis.read(block, read, len - read)
                if (n < 0) break
                read += n
            }
            if (read < len) break // 块被截断，停止解析

            if (type == 4) { // VORBIS_COMMENT
                val lyrics = parseVorbisLyrics(block)
                if (lyrics != null) return lyrics
            }
        }
        return null
    }

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
}
