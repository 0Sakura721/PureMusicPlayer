package com.puremusicplayer.data

import android.net.Uri

/**
 * 一首本地歌曲的轻量模型。
 * 仅保存播放与展示所需的最小字段，保持内存精简。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,   // 毫秒
    val data: String,     // 文件系统路径
    val size: Long
) {
    /** 专辑封面 Uri（MediaStore 提供，无需文件权限即可读取） */
    val albumArtUri: Uri?
        get() = if (albumId > 0) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else null
}
