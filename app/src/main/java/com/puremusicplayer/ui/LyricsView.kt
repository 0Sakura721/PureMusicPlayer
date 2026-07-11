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
 * 全屏歌词视图（当前行高亮 + 平滑滚动）。
 * 纯 Canvas 绘制，零依赖、轻量。
 *
 * 能力：
 *  - 长歌词按视图宽度自动换行（breakText），不再溢出屏幕两侧。
 *  - 双语歌词：原文下方渲染译文行（来自 LyricsParser 的 translation 字段）。
 *  - 当前行高亮 + 平滑滚动；setAnimate(false) 可关闭动画（设置项）。
 *  - setAccent(color) 设置高亮强调色。
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
    /** 是否绘制译文行（由设置控制） */
    var showTranslation = true

    /** 每行展开后的绘制行（含译文行） */
    private data class Row(val text: String, val translation: Boolean)
    private var rows = emptyList<Row>()
    private var lineOfRow = IntArray(0)   // 绘制行 -> 所属歌词行索引
    private var rowsBefore = IntArray(0)  // 每行之前累计的绘制行数（长度 lines.size+1）

    private val density = resources.displayMetrics.density
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * density
        color = Color.parseColor("#9A9AB0")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
        isFakeBoldText = true
        color = Color.WHITE
    }
    private val transPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        color = Color.parseColor("#8A8AA0")
    }
    private var lineHeight = activePaint.textSize * 1.7f
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
        rebuildRows()
        targetOffset = computeTarget(-1)
        currentOffset = if (animate) currentOffset else targetOffset
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

    /** 按当前宽度把每行（原文 + 译文）展开为若干绘制行，并记录行归属与累计 */
    private fun rebuildRows() {
        if (width <= 0) return
        val maxW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val rowList = mutableListOf<Row>()
        val lof = mutableListOf<Int>()
        val rb = IntArray(lines.size + 1)
        for (i in lines.indices) {
            rb[i] = rowList.size
            val ln = lines[i]
            val orig = if (ln.text.isEmpty()) "♪" else ln.text
            for (r in wrap(orig, maxW, activePaint)) {
                rowList.add(Row(r, false))
                lof.add(i)
            }
            if (!showTranslation || ln.translation.isNullOrEmpty()) {
                // 跳过译文
            } else {
                for (r in wrap(ln.translation!!, maxW, transPaint)) {
                    rowList.add(Row(r, true))
                    lof.add(i)
                }
            }
        }
        rb[lines.size] = rowList.size
        rows = rowList
        lineOfRow = lof.toIntArray()
        rowsBefore = rb
    }

    /** 按可用宽度断行；优先以活动行字号换行，保证当前行也放得下 */
    private fun wrap(text: String, maxW: Float, paint: Paint): List<String> {
        if (text.isEmpty()) return listOf("♪")
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxW, null)
            if (count <= 0) {
                result.add(text.substring(start))
                break
            }
            var piece = text.substring(start, start + count).trim()
            if (piece.isEmpty()) piece = text.substring(start, start + count)
            result.add(piece)
            start += count
            if (result.size > 8) break   // 安全阀，避免极端长行产生过多行
        }
        return result
    }

    private fun computeTarget(idx: Int): Float {
        if (lines.isEmpty() || height == 0) return 0f
        val before = if (idx < 0) 0 else rowsBefore.getOrElse(idx) { 0 }
        return height / 2f - lineHeight / 2f - before * lineHeight
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildRows()
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

        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        for (r in rows.indices) {
            val y = currentOffset + r * lineHeight + lineHeight / 2f
            if (y < -lineHeight || y > height + lineHeight) continue
            val row = rows[r]
            val lineIdx = lineOfRow[r]
            val paint = when {
                row.translation -> transPaint
                lineIdx == currentIndex -> activePaint
                else -> normalPaint
            }
            paint.alpha = when {
                row.translation -> 200
                lineIdx == currentIndex -> 255
                else -> max(45, 200 - abs(lineIdx - currentIndex) * 45)
            }
            canvas.drawText(row.text, cx, y, paint)
        }
    }
}
