package com.puremusicplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.puremusicplayer.player.PlayerManager
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 音频可视化视图（随音乐跳动的频谱柱）。
 * 通过注册 PlayerManager.fftSink 接收 FFT 数据，使用 Canvas 绘制，
 * 无第三方图形库，自动限制柱数以降低低端设备开销。
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6C5CE7")
    }

    private val barCount = 48
    private var magnitudes: FloatArray? = null
    private var registered = false

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    /** 注册为 FFT 接收方（进入播放页时调用） */
    fun register() {
        if (registered) return
        registered = true
        PlayerManager.fftSink = { onFft(it) }
    }

    /** 取消注册（离开播放页时调用） */
    fun unregister() {
        registered = false
        if (PlayerManager.fftSink != null) PlayerManager.fftSink = null
    }

    private fun onFft(data: ByteArray) {
        val bins = data.size / 2
        if (bins <= 0) return
        if (magnitudes == null || magnitudes!!.size != bins) magnitudes = FloatArray(bins)
        val m = magnitudes!!
        var j = 0
        var i = 0
        while (i < data.size - 1 && j < bins) {
            val re = (data[i].toInt() and 0xFF) - 128
            val im = (data[i + 1].toInt() and 0xFF) - 128
            m[j++] = sqrt((re * re + im * im).toDouble()).toFloat()
            i += 2
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = magnitudes ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val step = w / barCount
        for (k in 0 until barCount) {
            val src = if (barCount > 1)
                (k * (m.size - 1) / (barCount - 1)).coerceIn(0, m.size - 1) else 0
            val mag = (m[src] / 128f).coerceIn(0f, 1.4f)
            val barH = mag * h * 0.92f
            if (barH < 2f) continue
            val x = k * step
            paint.alpha = 210
            canvas.drawRoundRect(
                x + step * 0.15f, h - barH,
                x + step * 0.85f, h,
                step * 0.4f, step * 0.4f,
                paint
            )
        }
    }
}
