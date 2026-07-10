package com.puremusicplayer.util

import android.content.Context
import android.net.Uri
import com.puremusicplayer.data.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放队列持久化：保存/恢复当前播放列表、歌曲索引与进度。
 * 用 org.json（Android 内置）序列化，零额外依赖，保持精简。
 */
object QueueStorage {

    private const val PREFS_NAME = "queue_storage"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"

    data class RestoredState(
        val playlist: List<Song>,
        val currentIndex: Int,
        val currentPosition: Int
    )

    fun save(context: Context, playlist: List<Song>, index: Int, position: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (song in playlist) {
            val obj = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("albumId", song.albumId)
                put("duration", song.duration)
                put("data", song.data)
                put("size", song.size)
                song.uri?.toString()?.let { put("uri", it) }
                song.albumArt?.toString()?.let { put("albumArt", it) }
            }
            arr.put(obj)
        }
        prefs.edit()
            .putString(KEY_QUEUE, arr.toString())
            .putInt(KEY_INDEX, index)
            .putInt(KEY_POSITION, position)
            .apply()
    }

    fun restore(context: Context): RestoredState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, null) ?: return null
        val index = prefs.getInt(KEY_INDEX, -1)
        val position = prefs.getInt(KEY_POSITION, 0)

        return try {
            val arr = JSONArray(json)
            val songs = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val song = Song(
                    id = obj.optLong("id"),
                    title = obj.optString("title", "未知标题"),
                    artist = obj.optString("artist", "未知艺术家"),
                    album = obj.optString("album", "未知专辑"),
                    albumId = obj.optLong("albumId"),
                    duration = obj.optLong("duration"),
                    data = obj.optString("data"),
                    size = obj.optLong("size"),
                    uri = obj.optString("uri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                    albumArt = obj.optString("albumArt").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                )
                songs.add(song)
            }
            RestoredState(songs, index.coerceIn(-1, songs.size - 1), position)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
