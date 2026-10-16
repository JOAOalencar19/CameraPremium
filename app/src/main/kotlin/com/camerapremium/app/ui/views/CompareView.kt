package com.camerapremium.app.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * Side-by-side comparison of two renditions of the *same* frame (same geometry, orientation and
 * scaling), so differences are only the processing:
 *  - SPLIT: draggable divider (original on the left, processed on the right),
 *  - press and hold anywhere: shows the other version full-frame,
 *  - pinch/double-tap zoom and pan apply to both images identically.
 */
class CompareView(ctx: Context) : View(ctx) {
    enum class Mode { ORIGINAL, SPLIT, PROCESSED }

    var original: Bitmap? = null
        set(v) { field = v; resetView(); invalidate() }
    var processed: Bitmap? = null
        set(v) { field = v; resetView(); invalidate() }
    var mode = Mode.SPLIT
        set(v) { field = v; invalidate() }
    var labelOriginal = "ORIGINAL"
    var labelProcessed = "NATURAL PREMIUM"

    private var split = 0.5f
    private var holding = false
    private var draggingSplit = false
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE; strokeWidth = Theme.dp(ctx, 1.5f) }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE }
    private val knobArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = Theme.dp(ctx, 1.6f); strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE; textSize = Theme.dp(ctx, 11f); typeface = Theme.SANS_MEDIUM; letterSpacing = 0.1f }
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
        override fun onLongPress(e: MotionEvent) { holding = true; invalidate() }
    })

    private fun resetView() { scale = 1f; tx = 0f; ty = 0f }

    private fun clampPan() {
        val w = width.toFloat(); val h = height.toFloat()
        tx = tx.coerceIn(w - w * scale, 0f); ty = ty.coerceIn(h - h * scale, 0f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (mode == Mode.SPLIT && e.pointerCount == 1) {
            val sx = width * split
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> draggingSplit = abs(e.x - sx) < Theme.dp(context, 28f)
                MotionEvent.ACTION_MOVE -> if (draggingSplit) { split = (e.x / width).coerceIn(0.02f, 0.98f); invalidate(); return true }
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            draggingSplit = false
            if (holding) { holding = false; invalidate() }
        }
        return gestures.onTouchEvent(e) || true
    }

    /** Fit-center transform of a bitmap into the view, then the shared zoom/pan. */
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
        c.drawColor(Color.BLACK)
        val o = original; val p = processed
        val showOriginalOnly = mode == Mode.ORIGINAL || (holding && mode == Mode.PROCESSED)
        val showProcessedOnly = mode == Mode.PROCESSED && !holding || (holding && mode == Mode.ORIGINAL)
        when {
            p == null && o != null -> { c.drawBitmap(o, matrixFor(o), paint); tag(c, labelOriginal, true) }
            o == null && p != null -> { c.drawBitmap(p, matrixFor(p), paint); tag(c, labelProcessed, false) }
            o == null || p == null -> {}
            showOriginalOnly -> { c.drawBitmap(o, matrixFor(o), paint); tag(c, labelOriginal, true) }
            showProcessedOnly -> { c.drawBitmap(p, matrixFor(p), paint); tag(c, labelProcessed, false) }
            holding -> { c.drawBitmap(o, matrixFor(o), paint); tag(c, labelOriginal, true) }
            else -> {
                val sx = width * split
                c.save(); c.clipRect(0f, 0f, sx, height.toFloat()); c.drawBitmap(o, matrixFor(o), paint); c.restore()
                c.save(); c.clipRect(sx, 0f, width.toFloat(), height.toFloat()); c.drawBitmap(p, matrixFor(p), paint); c.restore()
                c.drawLine(sx, 0f, sx, height.toFloat(), linePaint)
                val ky = height / 2f; val kr = Theme.dp(context, 15f)
                c.drawCircle(sx, ky, kr, knob)
                val a = kr * 0.4f
                c.drawLine(sx - a * 1.6f, ky, sx - a * 0.6f, ky - a * 0.8f, knobArrow); c.drawLine(sx - a * 1.6f, ky, sx - a * 0.6f, ky + a * 0.8f, knobArrow)
                c.drawLine(sx + a * 1.6f, ky, sx + a * 0.6f, ky - a * 0.8f, knobArrow); c.drawLine(sx + a * 1.6f, ky, sx + a * 0.6f, ky + a * 0.8f, knobArrow)
                tag(c, labelOriginal, true); tag(c, labelProcessed, false)
            }
        }
    }

    private fun tag(c: Canvas, s: String, left: Boolean) {
        val pad = Theme.dp(context, 10f)
        val tw = text.measureText(s)
        val h = Theme.dp(context, 24f)
        val x = if (left) pad else width - pad - tw - pad * 1.6f
        val y = Theme.dp(context, 12f)
        c.drawRoundRect(RectF(x, y, x + tw + pad * 1.6f, y + h), h / 2f, h / 2f, chip)
        c.drawText(s, x + pad * 0.8f, y + h / 2f - (text.descent() + text.ascent()) / 2f, text)
    }
}
