package com.puremusicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

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
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE LOCALIZED ASC"

        resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                songs.add(
                    Song(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "未知标题",
                        artist = cursor.getString(artistCol) ?: "未知艺术家",
                        album = cursor.getString(albumCol) ?: "未知专辑",
                        albumId = cursor.getLong(albumIdCol),
                        duration = cursor.getLong(durCol),
                        data = cursor.getString(dataCol) ?: "",
                        size = cursor.getLong(sizeCol)
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
}
