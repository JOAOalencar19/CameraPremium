package com.camerapremium.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.camerapremium.app.ui.Theme

/** The app's own icon set, drawn on a 24x24 grid with round strokes (no third-party assets). */
enum class Glyph { FLASH_AUTO, FLASH_ON, FLASH_OFF, TIMER, SETTINGS, FLIP, SUN, MOON, LOCK, CLOSE, BACK }

object Glyphs {
    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, badge: String? = null) {
        val s = size / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f * s
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(cx - 12f * s, cy - 12f * s)
        when (g) {
            Glyph.FLASH_AUTO, Glyph.FLASH_ON, Glyph.FLASH_OFF -> {
                val bolt = Path().apply {
                    moveTo(13.5f * s, 2.5f * s); lineTo(5.5f * s, 13.5f * s); lineTo(11.5f * s, 13.5f * s)
                    lineTo(10.5f * s, 21.5f * s); lineTo(18.5f * s, 10.5f * s); lineTo(12.5f * s, 10.5f * s); close()
                }
                if (g == Glyph.FLASH_ON) { p.style = Paint.Style.FILL_AND_STROKE }
                c.drawPath(bolt, p)
                p.style = Paint.Style.STROKE
                if (g == Glyph.FLASH_OFF) c.drawLine(4f * s, 4f * s, 20f * s, 20f * s, p)
                if (g == Glyph.FLASH_AUTO) {
                    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 8.5f * s; typeface = Theme.SANS_MEDIUM }
                    c.drawText("A", 17.5f * s, 22.5f * s, t)
                }
            }
            Glyph.TIMER -> {
                c.drawCircle(12f * s, 13.5f * s, 8f * s, p)
                c.drawLine(12f * s, 13.5f * s, 12f * s, 9f * s, p)
                c.drawLine(9.5f * s, 2.5f * s, 14.5f * s, 2.5f * s, p)
                c.drawLine(18.5f * s, 6.5f * s, 19.8f * s, 5.2f * s, p)
            }
            Glyph.SETTINGS -> {
                // three sliders: our settings mark
                for ((i, y) in floatArrayOf(6f, 12f, 18f).withIndex()) {
                    c.drawLine(4f * s, y * s, 20f * s, y * s, p)
                    val kx = floatArrayOf(15f, 8f, 13f)[i]
                    val fill = Paint(p).apply { style = Paint.Style.FILL; this.color = 0xFF000000.toInt() }
                    c.drawCircle(kx * s, y * s, 2.2f * s, fill)
                    c.drawCircle(kx * s, y * s, 2.2f * s, p)
                }
            }
            Glyph.FLIP -> {
                val r = RectF(4.5f * s, 4.5f * s, 19.5f * s, 19.5f * s)
                c.drawArc(r, 200f, 140f, false, p)
                c.drawArc(r, 20f, 140f, false, p)
                c.drawLine(19.2f * s, 9.2f * s, 19.6f * s, 5.4f * s, p)
                c.drawLine(19.2f * s, 9.2f * s, 15.6f * s, 8.4f * s, p)
                c.drawLine(4.8f * s, 14.8f * s, 4.4f * s, 18.6f * s, p)
                c.drawLine(4.8f * s, 14.8f * s, 8.4f * s, 15.6f * s, p)
            }
            Glyph.SUN -> {
                c.drawCircle(12f * s, 12f * s, 3.6f * s, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val x0 = 12f + 6.3f * Math.cos(a).toFloat(); val y0 = 12f + 6.3f * Math.sin(a).toFloat()
                    val x1 = 12f + 8.6f * Math.cos(a).toFloat(); val y1 = 12f + 8.6f * Math.sin(a).toFloat()
                    c.drawLine(x0 * s, y0 * s, x1 * s, y1 * s, p)
                }
            }
            Glyph.MOON -> {
                val path = Path().apply {
                    addCircle(12f * s, 12f * s, 8f * s, Path.Direction.CW)
                    val cut = Path().apply { addCircle(16f * s, 9f * s, 7f * s, Path.Direction.CW) }
                    op(cut, Path.Op.DIFFERENCE)
                }
                p.style = Paint.Style.FILL
                c.drawPath(path, p)
            }
            Glyph.LOCK -> {
                c.drawRoundRect(RectF(5f * s, 11f * s, 19f * s, 21f * s), 2.5f * s, 2.5f * s, p)
                c.drawArc(RectF(8f * s, 4f * s, 16f * s, 14f * s), 180f, 180f, false, p)
                c.drawLine(8f * s, 9f * s, 8f * s, 11f * s, p); c.drawLine(16f * s, 9f * s, 16f * s, 11f * s, p)
            }
            Glyph.CLOSE -> { c.drawLine(6f * s, 6f * s, 18f * s, 18f * s, p); c.drawLine(18f * s, 6f * s, 6f * s, 18f * s, p) }
            Glyph.BACK -> { c.drawLine(15f * s, 5f * s, 8f * s, 12f * s, p); c.drawLine(8f * s, 12f * s, 15f * s, 19f * s, p) }
        }
        c.restore()
        if (badge != null) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Theme.ACCENT; textSize = 9f * s; typeface = Theme.SANS_MEDIUM; textAlign = Paint.Align.CENTER }
            c.drawText(badge, cx + 9f * s, cy + 12f * s, t)
        }
    }
}

/** Round icon button that rotates its glyph with the device (UI stays portrait). */
class GlyphButton(ctx: Context, var glyph: Glyph) : View(ctx) {
    var color = Theme.WHITE
        set(v) { field = v; invalidate() }
    var badge: String? = null
        set(v) { field = v; invalidate() }
    var glyphSize = Theme.dp(ctx, 24f)
    var fillColor: Int = 0
        set(v) { field = v; invalidate() }
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true; isFocusable = true }

    fun rotateTo(deg: Float) {
        var target = deg
        while (target - angle > 180f) target -= 360f
        while (target - angle < -180f) target += 360f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(angle, target).apply {
            duration = 280; interpolator = DecelerateInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (fillColor != 0) { bg.color = fillColor; c.drawCircle(cx, cy, minOf(width, height) / 2f, bg) }
        val alpha = if (isPressed) 0.6f else 1f
        val col = (color and 0x00FFFFFF) or ((((color ushr 24) and 0xFF) * alpha).toInt() shl 24)
        c.save(); c.rotate(angle, cx, cy)
        Glyphs.draw(c, glyph, cx, cy, glyphSize, col, badge)
        c.restore()
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}
