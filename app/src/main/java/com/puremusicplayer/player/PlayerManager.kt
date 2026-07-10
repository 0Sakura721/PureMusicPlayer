package com.puremusicplayer.player

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.puremusicplayer.data.Song
import com.puremusicplayer.util.Prefs
import com.puremusicplayer.util.QueueStorage

/**
 * 播放模式。
 * ORDER=顺序, SHUFFLE=随机, REPEAT_ALL=列表循环, REPEAT_ONE=单曲循环
 */
enum class PlayMode { ORDER, SHUFFLE, REPEAT_ALL, REPEAT_ONE }

/**
 * 播放状态中枢（单例）。
 * Service 与 UI 同进程，借此共享播放列表、当前曲目与播放进度等状态。
 * UI 通过 LiveData 观察；控制指令通过 PlayerControls 发给 PlayerService。
 */
object PlayerManager {
    val playlist = mutableListOf<Song>()
    var currentIndex = -1
    var playMode: PlayMode = PlayMode.REPEAT_ALL

    val currentSong = MutableLiveData<Song?>(null)
    val isPlaying = MutableLiveData(false)
    val progress = MutableLiveData(0)   // 当前进度（毫秒）
    val duration = MutableLiveData(0)   // 总时长（毫秒）
    val lyrics = MutableLiveData<List<LyricLine>>(emptyList())
    val hasLyrics = MutableLiveData(false)

    /** 收藏歌曲主键集合（与 Prefs 同步，供 UI 观察刷新爱心/收藏列表） */
    val favorites = MutableLiveData<Set<String>>(emptySet())

    /**
     * 睡眠定时器剩余秒数：0 表示已关闭；-1 表示「播完当前歌曲后停止」。
     * 由 PlayerService 每秒推送，UI 观察显示倒计时。
     */
    val sleepRemaining = MutableLiveData(0)

    /** 可视化数据槽：由 PlayerService 推送 FFT，VisualizerView 注册接收 */
    var fftSink: ((ByteArray) -> Unit)? = null
    /** 可视化数据槽：波形原始数据（供波形样式使用） */
    var waveSink: ((ByteArray) -> Unit)? = null

    fun current(): Song? = if (currentIndex in playlist.indices) playlist[currentIndex] else null

    /** 从 Prefs 载入收藏集合到 LiveData（应用启动时调用一次） */
    fun syncFavorites() {
        favorites.value = Prefs.favorites.toSet()
    }

    /** 切换某首歌的收藏状态，并同步 Prefs 与 LiveData，返回切换后是否已收藏 */
    fun toggleFav(key: String): Boolean {
        val nowFav = Prefs.toggleFavorite(key)
        favorites.value = Prefs.favorites.toSet()
        return nowFav
    }

    /** 供 PlayerService 把音频 FFT 数据推送给当前注册的视图 */
    fun dispatchFft(data: ByteArray) = fftSink?.invoke(data)

    /** 供 PlayerService 把波形数据推送给当前注册的视图 */
    fun dispatchWave(data: ByteArray) = waveSink?.invoke(data)

    fun reset() {
        playlist.clear()
        currentIndex = -1
        playMode = PlayMode.REPEAT_ALL
        currentSong.value = null
        isPlaying.value = false
        progress.value = 0
        duration.value = 0
        lyrics.value = emptyList()
        hasLyrics.value = false
    }

    /** 保存当前播放状态到持久化存储（播放列表、当前索引、进度） */
    fun saveQueue(context: Context) {
        if (playlist.isNotEmpty() && currentIndex in playlist.indices) {
            QueueStorage.save(context, playlist, currentIndex, progress.value ?: 0)
        } else {
            QueueStorage.clear(context)
        }
    }

    /** 从持久化存储恢复播放状态；成功恢复时返回 true */
    fun restoreQueue(context: Context): Boolean {
        val state = QueueStorage.restore(context) ?: return false
        playlist.clear()
        playlist.addAll(state.playlist)
        currentIndex = state.currentIndex
        progress.value = state.currentPosition
        if (currentIndex in playlist.indices) {
            currentSong.value = playlist[currentIndex]
        }
        return true
    }
}
