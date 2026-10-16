package com.camerapremium.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.camerapremium.app.ui.Theme
import kotlin.math.abs

/**
 * Comparison of two renditions of the *same* frame (same geometry, orientation and scale), so
 * the only differences are the processing. The photo fills the view (fit-center, never cropped):
 *  - SPLIT: draggable hairline divider with a small handle (original left, processed right),
 *  - press and hold: shows the other version,
 *  - pinch / double-tap zoom and pan apply to both images identically.
 */
class CompareView(ctx: Context) : View(ctx) {
    enum class Mode { ORIGINAL, SPLIT, PROCESSED }

    interface Listener {
        /** What is visible right now (changes while holding). */
        fun onShowing(mode: Mode)
        fun onInteraction()
    }

    var listener: Listener? = null
    var original: Bitmap? = null
        set(v) { field = v; resetView(); invalidate() }
    var processed: Bitmap? = null
        set(v) { field = v; resetView(); invalidate() }
    var mode = Mode.SPLIT
        set(v) { field = v; showLabels(); invalidate(); listener?.onShowing(showing()) }
    var labelOriginal = "Original"
    var labelProcessed = "Natural Premium"
    /** Vertical band (px from the top) kept clear for the header when drawing labels. */
    var labelTop = 0f

    private var split = 0.5f
    private var holding = false
    private var draggingSplit = false
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var labelAlpha = 1f
    private var labelAnim: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.alpha(Theme.WHITE, 0.85f); strokeWidth = Theme.dp(ctx, 1f) }
    private val lineShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.alpha(Theme.BLACK, 0.25f); strokeWidth = Theme.dp(ctx, 2.5f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.alpha(Theme.BLACK, 0.3f); style = Paint.Style.STROKE; strokeWidth = Theme.dp(ctx, 1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE; textSize = Theme.dp(ctx, 11f); typeface = Theme.SANS_MEDIUM }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SCRIM }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 8f)
            val k = ns / scale
            tx = d.focusX - (d.focusX - tx) * k; ty = d.focusY - (d.focusY - ty) * k
            scale = ns; clampPan(); invalidate(); return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) resetView() else {
                val ns = 3f
                tx = e.x - (e.x - tx) * ns; ty = e.y - (e.y - ty) * ns; scale = ns; clampPan()
            }
            invalidate(); return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (draggingSplit) return false
            if (scale > 1.01f) { tx -= dx; ty -= dy; clampPan(); invalidate() }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (draggingSplit) return
            holding = true; showLabels(); invalidate(); listener?.onShowing(showing())
        }
    })

    /** What is on screen (SPLIT shows both). */
    fun showing(): Mode = when {
        !holding -> mode
        mode == Mode.ORIGINAL -> Mode.PROCESSED
        else -> Mode.ORIGINAL // holding in SPLIT or PROCESSED shows the original (as before)
    }

    private fun resetView() { scale = 1f; tx = 0f; ty = 0f }

    private fun clampPan() {
        val w = width.toFloat(); val h = height.toFloat()
        tx = tx.coerceIn(w - w * scale, 0f); ty = ty.coerceIn(h - h * scale, 0f)
    }

    /** Labels appear on interaction and fade after a short idle time. */
    private fun showLabels() {
        labelAnim?.cancel()
        labelAlpha = 1f
        labelAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 1600; duration = 400
            addUpdateListener { labelAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) listener?.onInteraction()
        if (mode == Mode.SPLIT && e.pointerCount == 1 && !holding) {
            val sx = width * split
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> draggingSplit = abs(e.x - sx) < Theme.dp(context, 32f)
                MotionEvent.ACTION_MOVE -> if (draggingSplit) {
                    split = (e.x / width).coerceIn(0.02f, 0.98f); showLabels(); invalidate(); return true
                }
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            draggingSplit = false
            if (holding) { holding = false; invalidate(); listener?.onShowing(showing()) }
        }
        return gestures.onTouchEvent(e) || true
    }

    /** Fit-center transform into the whole view, then the shared zoom/pan. */
    private fun matrixFor(b: Bitmap): Matrix {
        val vw = width.toFloat(); val vh = height.toFloat()
        val s = minOf(vw / b.width, vh / b.height)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((vw - b.width * s) / 2f, (vh - b.height * s) / 2f)
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        return m
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.BLACK)
        val o = original; val p = processed
        val show = showing()
        when {
            p == null && o != null -> c.drawBitmap(o, matrixFor(o), paint)
            o == null && p != null -> c.drawBitmap(p, matrixFor(p), paint)
            o == null || p == null -> {}
            show == Mode.ORIGINAL -> { c.drawBitmap(o, matrixFor(o), paint); tag(c, labelOriginal, true) }
            show == Mode.PROCESSED -> { c.drawBitmap(p, matrixFor(p), paint); tag(c, labelProcessed, false) }
            else -> {
                val sx = width * split
                c.save(); c.clipRect(0f, 0f, sx, height.toFloat()); c.drawBitmap(o, matrixFor(o), paint); c.restore()
                c.save(); c.clipRect(sx, 0f, width.toFloat(), height.toFloat()); c.drawBitmap(p, matrixFor(p), paint); c.restore()
                // hairline divider with a faint shadow for contrast on bright photos
                c.drawLine(sx, 0f, sx, height.toFloat(), lineShadow)
                c.drawLine(sx, 0f, sx, height.toFloat(), line)
                // small vertical handle (does not hide detail)
                val hw = Theme.dp(context, 3f); val hh = Theme.dp(context, 14f); val cy = height / 2f
                val r = RectF(sx - hw, cy - hh, sx + hw, cy + hh)
                c.drawRoundRect(r, hw, hw, handle)
                c.drawRoundRect(r, hw, hw, handleEdge)
                tag(c, labelOriginal, true); tag(c, labelProcessed, false)
            }
        }
    }

    private fun tag(c: Canvas, s: String, left: Boolean) {
        if (labelAlpha <= 0.01f) return
        val padH = Theme.dp(context, 8f)
        val tw = text.measureText(s)
        val h = Theme.dp(context, 22f)
        val x = if (left) Theme.dp(context, Theme.Dim.EDGE) else width - Theme.dp(context, Theme.Dim.EDGE) - tw - 2 * padH
        val y = labelTop + Theme.dp(context, 6f)
        chip.alpha = (115 * labelAlpha).toInt()
        text.alpha = (255 * labelAlpha).toInt()
        c.drawRoundRect(RectF(x, y, x + tw + 2 * padH, y + h), h / 2f, h / 2f, chip)
        c.drawText(s, x + padH, y + h / 2f - (text.descent() + text.ascent()) / 2f, text)
    }
}
