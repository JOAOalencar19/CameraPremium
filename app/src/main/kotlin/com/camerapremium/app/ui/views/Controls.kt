package com.camerapremium.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.camerapremium.app.camera.ZoomStop
import com.camerapremium.app.ui.Theme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Shutter: white ring + disc; the ring turns into a progress arc while a burst is captured. */
class ShutterButton(ctx: Context) : View(ctx) {
    var progress = -1f // <0: idle
        set(v) { field = v; invalidate() }
    var night = false
        set(v) { field = v; invalidate() }
    var busy = false
        set(v) { field = v; invalidate() }
    private var press = 1f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.WHITE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.WHITE; strokeCap = Paint.Cap.ROUND }

    init { isClickable = true; contentDescription = "Capturar" }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.86f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(e)
    }

    private fun animatePress(to: Float) {
        ValueAnimator.ofFloat(press, to).apply {
            duration = 140; interpolator = if (to == 1f) OvershootInterpolator(2f) else DecelerateInterpolator()
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f
        val stroke = r * 0.075f
        ring.strokeWidth = stroke
        ring.alpha = if (busy && progress < 0f) 120 else 255
        c.drawCircle(cx, cy, r - stroke / 2f, ring)
        disc.color = when {
            busy -> Theme.alpha(Theme.WHITE, 0.55f)
            night -> Theme.GRAY_LIGHT // night is shown by tone, not hue
            else -> Theme.WHITE
        }
        c.drawCircle(cx, cy, (r - stroke * 2.3f) * press, disc)
        if (progress >= 0f) {
            arc.strokeWidth = stroke
            val o = RectF(cx - r + stroke / 2f, cy - r + stroke / 2f, cx + r - stroke / 2f, cy + r - stroke / 2f)
            c.drawArc(o, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }
    }
}

/**
 * Zoom pills (factors detected from the hardware). Tap = jump to a stop; horizontal drag on
 * the bar = continuous zoom (logarithmic, like a dial).
 */
class ZoomBar(ctx: Context) : View(ctx) {
    interface Listener {
        fun onStopSelected(stop: ZoomStop)
        fun onZoomDrag(factor: Float)
    }

    var listener: Listener? = null
    var stops: List<ZoomStop> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var factor = 1f
        set(v) { field = v; invalidate() }
    var minFactor = 0.5f
    var maxFactor = 10f
    var textAngle = 0f
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SCRIM }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.alpha(Theme.GRAY_DARK, 0.6f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Theme.SANS_MEDIUM }
    private val pillD = Theme.dp(ctx, 34f)
    private val gap = Theme.dp(ctx, 8f)
    private var downX = 0f
    private var downFactor = 1f
    private var dragging = false

    fun selectedIndex(): Int {
        var best = 0
        for ((i, s) in stops.withIndex()) if (factor >= s.factor * 0.97f) best = i
        return best
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (stops.size * pillD + (stops.size + 1) * gap).toInt().coerceAtLeast(pillD.toInt())
        setMeasuredDimension(w, (pillD + gap * 1.2f).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (stops.isEmpty()) return
        val h = height.toFloat()
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2f, h / 2f, bg)
        val sel = selectedIndex()
        for ((i, s) in stops.withIndex()) {
            val cx = gap + pillD / 2f + i * (pillD + gap)
            val cy = h / 2f
            val selected = i == sel
            val radius = if (selected) pillD / 2f else pillD / 2f * 0.78f
            pill.color = if (selected) Theme.SELECTED else Theme.alpha(Theme.GRAY_DARK, 0.6f)
            c.drawCircle(cx, cy, radius, pill)
            text.color = if (selected) Theme.ON_SELECTED else Theme.WHITE
            text.textSize = if (selected) pillD * 0.36f else pillD * 0.3f
            val label = if (selected && abs(factor - s.factor) > 0.05f) ZoomStop.formatFactor(factor) + "×"
            else if (selected) s.label + "×" else s.label
            c.save(); c.rotate(textAngle, cx, cy)
            c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2f, text)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downFactor = factor; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragging && abs(dx) > Theme.dp(context, 10f)) dragging = true
                if (dragging) {
                    val f = (downFactor * exp(dx / Theme.dp(context, 160f))).coerceIn(minFactor, maxFactor)
                    factor = f
                    listener?.onZoomDrag(f)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val i = ((e.x - gap / 2f) / (pillD + gap)).toInt().coerceIn(0, stops.size - 1)
                    if (stops.isNotEmpty()) listener?.onStopSelected(stops[i])
                }
            }
        }
        return true
    }

    companion object {
        fun lerpLog(a: Float, b: Float, t: Float): Float = exp(ln(a) + (ln(b) - ln(a)) * t)
    }
}

/** Horizontal mode selector (FOTO / NOITE / PRO / RETRATO when available). */
class ModeSelector(ctx: Context) : View(ctx) {
    var modes: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Theme.SANS_MEDIUM; letterSpacing = 0.12f }
    private val spacing = Theme.dp(ctx, 84f)
    private var downX = 0f

    override fun onDraw(c: Canvas) {
        p.textSize = Theme.dp(context, 13f)
        val cx = width / 2f
        val y = height / 2f - (p.descent() + p.ascent()) / 2f
        for ((i, m) in modes.withIndex()) {
            p.color = if (i == selected) Theme.WHITE else Theme.GRAY_MID
            p.typeface = if (i == selected) Theme.SANS_MEDIUM else Theme.SANS
            c.drawText(m, cx + (i - selected) * spacing, y, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val target = if (abs(dx) > Theme.dp(context, 30f)) selected + (if (dx < 0) 1 else -1)
                else selected + Math.round((e.x - width / 2f) / spacing)
                val t = target.coerceIn(0, modes.size - 1)
                if (t != selected) { selected = t; onSelect?.invoke(t) }
            }
        }
        return true
    }
}

/** Round thumbnail of the last photo, with a ring while photos are still processing. */
class ThumbnailView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.alpha(Theme.WHITE, 0.6f) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.WHITE; strokeCap = Paint.Cap.ROUND }
    var processing = 0
        set(v) { field = v; if (v > 0) spin() ; invalidate() }
    private var spinAngle = 0f
    private var spinner: ValueAnimator? = null
    var angle = 0f
        set(v) { field = v; invalidate() }

    init { isClickable = true; contentDescription = "Ultima foto" }

    fun setBitmap(b: Bitmap?) { bmp = b; invalidate() }

    private fun spin() {
        if (spinner?.isRunning == true) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { spinAngle = it.animatedValue as Float; if (processing <= 0) cancel(); invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - Theme.dp(context, 2f)
        val b = bmp
        if (b != null) {
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(2 * r / b.width, 2 * r / b.height)
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(cx - b.width * scale / 2f, cy - b.height * scale / 2f)
            m.postRotate(angle, cx, cy)
            shader.setLocalMatrix(m)
            paint.shader = shader
            c.drawCircle(cx, cy, r, paint)
        } else {
            paint.shader = null; paint.color = Theme.SURFACE
            c.drawCircle(cx, cy, r, paint)
        }
        ring.strokeWidth = Theme.dp(context, 1.5f)
        c.drawCircle(cx, cy, r, ring)
        if (processing > 0) {
            arc.strokeWidth = Theme.dp(context, 2.5f)
            val o = RectF(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(o, spinAngle, 90f, false, arc)
        }
    }
}
