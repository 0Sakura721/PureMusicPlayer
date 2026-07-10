package com.puremusicplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.puremusicplayer.player.PlayerManager
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频可视化视图（随音乐跳动的频谱 / 波形）。
 * 通过注册 PlayerManager 的 FFT 与波形数据接收方，用 Canvas 绘制，零第三方图形库。
 *
 * 支持三种样式（由 Prefs.visualizerStyle 控制）：
 *  - 0 条形：底部向上的频谱柱
 *  - 1 圆形：环绕中心的径向频谱
 *  - 2 波形：居中的音频波形线
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val STYLE_BARS = 0
        const val STYLE_CIRCLE = 1
        const val STYLE_WAVE = 2
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6C5CE7")
    }

    private val barCount = 48
    private var magnitudes: FloatArray? = null   // FFT 幅度
    private var waveform: ByteArray? = null       // 波形原始数据
    private var style = STYLE_BARS
    private var registered = false

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setStyle(s: Int) {
        style = s
        invalidate()
    }

    /** 注册为 FFT / 波形 接收方（进入播放页时调用） */
    fun register() {
        if (registered) return
        registered = true
        PlayerManager.fftSink = { onFft(it) }
        PlayerManager.waveSink = { onWave(it) }
    }

    /** 取消注册（离开播放页时调用） */
    fun unregister() {
        registered = false
        if (PlayerManager.fftSink != null) PlayerManager.fftSink = null
        if (PlayerManager.waveSink != null) PlayerManager.waveSink = null
        magnitudes = null
        waveform = null
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

    private fun onWave(data: ByteArray) {
        waveform = data
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        when (style) {
            STYLE_CIRCLE -> drawCircle(canvas, w, h)
            STYLE_WAVE -> drawWave(canvas, w, h)
            else -> drawBars(canvas, w, h)
        }
    }

    // ---------- 条形 ----------
    private fun drawBars(canvas: Canvas, w: Float, h: Float) {
        val m = magnitudes ?: return
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

    // ---------- 圆形 ----------
    private fun drawCircle(canvas: Canvas, w: Float, h: Float) {
        val m = magnitudes ?: return
        val cx = w / 2f
        val cy = h / 2f
        val base = min(w, h) * 0.22f
        paint.alpha = 200
        for (k in 0 until barCount) {
            val src = if (barCount > 1)
                (k * (m.size - 1) / (barCount - 1)).coerceIn(0, m.size - 1) else 0
            val mag = (m[src] / 128f).coerceIn(0f, 1.4f)
            val len = mag * min(w, h) * 0.30f
            if (len < 1f) continue
            val a = (k * 2 * Math.PI / barCount).toFloat()
            val cosA = cos(a)
            val sinA = sin(a)
            canvas.drawLine(
                cx + base * cosA, cy + base * sinA,
                cx + (base + len) * cosA, cy + (base + len) * sinA,
                paint
            )
        }
    }

    // ---------- 波形 ----------
    private fun drawWave(canvas: Canvas, w: Float, h: Float) {
        val data = waveform ?: return
        val n = data.size
        if (n == 0) return
        val mid = h / 2f
        val amp = h * 0.42f
        val path = Path()
        for (i in 0 until n) {
            val v = (data[i].toInt() and 0xFF) - 128   // -128..127
            val x = i * w / (n - 1).coerceAtLeast(1)
            val y = mid + v * amp / 128f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.alpha = 230
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * resources.displayMetrics.density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
