package com.puremusicplayer.player

import androidx.lifecycle.MutableLiveData
import com.puremusicplayer.data.Song

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

    /** 可视化数据槽：由 PlayerService 推送 FFT，VisualizerView 注册接收 */
    var fftSink: ((ByteArray) -> Unit)? = null
    /** 可视化数据槽：波形原始数据（供波形样式使用） */
    var waveSink: ((ByteArray) -> Unit)? = null

    fun current(): Song? = if (currentIndex in playlist.indices) playlist[currentIndex] else null

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
}
