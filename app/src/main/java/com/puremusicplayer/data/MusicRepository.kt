package com.puremusicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 本地曲库数据源。
 * 默认通过 MediaStore 扫描设备上的音乐文件；若用户指定了音乐目录，
 * 则通过 SAF 文档树遍历该目录（含子目录）收集音频，并用 MediaMetadataRetriever 提取元数据。
 * 同时支持按“同名 .lrc”匹配歌词文件（路径模式与文档树模式均适配）。
 *
 * 设计取向：只读取本地媒体库，不做任何上传/网络请求。
 */
object MusicRepository {

    private val AUDIO_EXT = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus", "ape", "wv")

    /**
     * 扫描音乐。
     * @param treeUri 若非空，则只扫描该文档树目录；否则扫描整个 MediaStore。
     */
    fun loadSongs(context: Context, treeUri: Uri? = null): List<Song> {
        return if (treeUri != null) loadFromTree(context, treeUri)
        else loadFromMediaStore(context)
    }

    // ---------- MediaStore 全量扫描 ----------
    private fun loadFromMediaStore(context: Context): List<Song> {
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

    // ---------- SAF 文档树目录扫描 ----------
    private fun loadFromTree(context: Context, treeUri: Uri): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return songs
            collectAudio(context, root, songs)
        } catch (_: Exception) {
            // 目录不可读时安全降级为空
        }
        songs.sortBy { it.title.lowercase() }
        return songs
    }

    private fun collectAudio(context: Context, dir: DocumentFile, out: MutableList<Song>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) collectAudio(context, f, out)
            else if (f.isFile) {
                val type = f.type ?: ""
                val name = f.name ?: ""
                if (isAudio(type, name)) buildSongFromDoc(context, f)?.let { out.add(it) }
            }
        }
    }

    private fun isAudio(type: String, name: String): Boolean {
        if (type.startsWith("audio/", ignoreCase = true)) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXT
    }

    private fun buildSongFromDoc(context: Context, file: DocumentFile): Song? {
        val name = file.name ?: return null
        val baseName = name.substringBeforeLast('.')
        val retr = MediaMetadataRetriever()
        return try {
            retr.setDataSource(context, file.uri)
            val title = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: baseName.ifEmpty { name }
            val artist = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "未知艺术家"
            val album = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "未知专辑"
            val durStr = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durStr?.toLongOrNull() ?: 0L
            val pic = retr.embeddedPicture
            val albumArt = if (pic != null) saveArtToCache(context, file.uri, pic) else null
            Song(
                id = 0,
                title = title,
                artist = artist,
                album = album,
                albumId = 0,
                duration = duration,
                data = "",
                size = file.length(),
                uri = file.uri,
                albumArt = albumArt
            )
        } catch (_: Exception) {
            null
        } finally {
            try { retr.release() } catch (_: Exception) {}
        }
    }

    /** 把内嵌封面写入应用私有缓存，返回文件 Uri（避免重复解码，跨刷新复用） */
    private fun saveArtToCache(context: Context, keyUri: Uri, bytes: ByteArray): Uri? {
        return try {
            val dir = File(context.cacheDir, "art")
            if (!dir.exists()) dir.mkdirs()
            val h = keyUri.toString().hashCode()
            val name = "art_${if (h < 0) -h else h}.jpg"
            val file = File(dir, name)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 查找与某首歌曲同名的歌词文件（.lrc / .LRC / .txt）。
     * 优先：文档树模式下在同级目录查找；MediaStore 模式下用 Files 表/文件路径查找。
     * 返回可被 ContentResolver 打开的 Uri；找不到返回 null。
     */
    fun findLrcUri(context: Context, song: Song): Uri? {
        // 文档树模式：在同目录查找同名 .lrc
        if (song.uri != null) {
            try {
                val doc = DocumentFile.fromSingleUri(context, song.uri) ?: return null
                val parent = doc.parentFile ?: return null
                val base = (doc.name ?: "").substringBeforeLast('.')
                if (base.isNotEmpty()) {
                    val target = "$base.lrc"
                    parent.listFiles()?.forEach {
                        if (it.name.equals(target, ignoreCase = true)) return it.uri
                    }
                }
            } catch (_: Exception) {
                // 忽略，回退到路径模式
            }
        }

        val audioData = song.data
        if (audioData.isEmpty()) return null
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
            val f = File(parent, baseName + ext)
            if (f.exists()) return Uri.fromFile(f)
        }
        return null
    }
}
