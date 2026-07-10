package com.puremusicplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.puremusicplayer.player.LyricLine
import kotlin.math.abs
import kotlin.math.max

/**
 * 全屏歌词视图（folia 风格：当前行高亮 + 平滑滚动）。
 * 纯 Canvas 绘制，零依赖、轻量。
 *
 * 用法：
 *  - setLyrics(list) 设置解析后的歌词
 *  - update(progressMs) 随播放进度调用，内部自动定位当前行并缓动滚动
 *  - setAnimate(false) 关闭动画（设置项）
 *  - setAccent(color) 设置高亮强调色
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var lines = emptyList<LyricLine>()
    private var currentIndex = -1
    private var currentOffset = 0f
    private var targetOffset = 0f
    private var animate = true

    private val density = resources.displayMetrics.density
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f * density
        color = Color.parseColor("#9A9AB0")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f * density
        isFakeBoldText = true
        color = Color.WHITE
    }
    private var lineHeight = activePaint.textSize * 1.9f
    private var accent = Color.parseColor("#6C5CE7")

    fun setAccent(color: Int) {
        accent = color
        activePaint.color = color
        invalidate()
    }

    fun setAnimate(on: Boolean) {
        animate = on
        if (!on) currentOffset = targetOffset
    }

    fun setLyrics(list: List<LyricLine>) {
        lines = list
        currentIndex = -1
        targetOffset = computeTarget(-1)
        currentOffset = if (animate) currentOffset else targetOffset
        if (!animate) currentOffset = targetOffset
        invalidate()
    }

    fun update(progressMs: Long) {
        if (lines.isEmpty()) return
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= progressMs) idx = i else break
        }
        if (idx != currentIndex) {
            currentIndex = idx
            targetOffset = computeTarget(idx)
            if (!animate) currentOffset = targetOffset
        }
        if (!animate) invalidate()
    }

    private fun computeTarget(idx: Int): Float {
        if (lines.isEmpty() || height == 0) return 0f
        return height / 2f - lineHeight / 2f - idx * lineHeight
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        targetOffset = computeTarget(currentIndex)
        if (!animate) currentOffset = targetOffset
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        if (animate) {
            currentOffset += (targetOffset - currentOffset) * 0.18f
            if (abs(targetOffset - currentOffset) > 0.5f) postInvalidateOnAnimation()
        }

        val centerX = width / 2f
        for (i in lines.indices) {
            val y = currentOffset + i * lineHeight + lineHeight / 2f
            if (y < -lineHeight || y > height + lineHeight) continue
            val paint = if (i == currentIndex) activePaint else normalPaint
            val dist = abs(i - currentIndex)
            paint.alpha = if (i == currentIndex) 255 else max(40, 210 - dist * 50)
            val text = if (lines[i].text.isEmpty()) "♪" else lines[i].text
            canvas.drawText(text, centerX, y, paint)
        }
    }
}
