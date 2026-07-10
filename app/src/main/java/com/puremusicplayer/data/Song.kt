package com.puremusicplayer.data

import android.net.Uri
import android.provider.MediaStore

/**
 * 一首本地歌曲的轻量模型。
 * 仅保存播放与展示所需的最小字段，保持内存精简。
 *
 * @param uri 直接内容 Uri（目录模式下使用）；为 null 时回退到 MediaStore id
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,   // 毫秒
    val data: String,     // 文件系统路径（目录模式下可能为空）
    val size: Long,
    val uri: Uri? = null,
    val albumArt: Uri? = null
) {
    /** 文件信息缓存（格式/位深/采样率），按需探测后写入 */
    var audioInfo: AudioFileInfo? = null

    /** 专辑封面 Uri：目录模式用内嵌封面缓存，否则回退 MediaStore */
    val albumArtUri: Uri?
        get() = albumArt ?: if (albumId > 0) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else null

    /** 实际可播放/读取的内容 Uri：优先用直接 Uri，否则按 MediaStore id 拼接 */
    fun contentUri(): Uri = uri
        ?: Uri.parse("${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id")
}
