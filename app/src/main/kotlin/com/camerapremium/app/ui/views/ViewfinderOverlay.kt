package com.camerapremium.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.camerapremium.app.ui.Theme

/**
 * Everything drawn over the live preview, plus its gestures:
 *  tap = focus/meter at a point; vertical drag after a tap = exposure (sun slider);
 *  long press = AE/AF lock; pinch = zoom.
 */
class ViewfinderOverlay(ctx: Context) : View(ctx) {
    interface Listener {
        fun onTapFocus(nx: Float, ny: Float)
        fun onLongPressLock(nx: Float, ny: Float)
        fun onExposureChanged(ev: Float)
        fun onPinch(scale: Float)
        fun onPinchEnd()
    }

    var listener: Listener? = null
    var grid = false
        set(v) { field = v; invalidate() }
    /** Visible region aspect (h/w in portrait) inside the view; 1f = square mask. 0 = none. */
    var squareMask = false
        set(v) { field = v; invalidate() }
    var countdown = 0
        set(v) { field = v; invalidate() }
    var lockedText: String? = null
        set(v) { field = v; invalidate() }

    private var focusX = -1f; private var focusY = -1f
    private var focusAnim = 0f
    private var focusShownAt = 0L
    private var ev = 0f
    private var evRange = 2f
    private var draggingEv = false
    private var lastY = 0f
    private var blink = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.alpha(Theme.WHITE, 0.27f); strokeWidth = Theme.dp(ctx, 0.8f) }
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.WHITE; strokeWidth = Theme.dp(ctx, 1.4f) }
    private val maskP = Paint().apply { color = Theme.alpha(Theme.BLACK, 0.78f) }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE; textAlign = Paint.Align.CENTER; typeface = Theme.SANS_MEDIUM }
    private val chipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SELECTED }
    private val blinkP = Paint().apply { color = Theme.BLACK }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { listener?.onPinch(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { listener?.onPinchEnd() }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showFocus(e.x, e.y)
            ev = 0f; listener?.onExposureChanged(0f)
            lockedText = null
            listener?.onTapFocus(e.x / width, e.y / height)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            showFocus(e.x, e.y)
            lockedText = "BLOQUEIO AE/AF"
            listener?.onLongPressLock(e.x / width, e.y / height)
        }
    })

    fun showFocus(x: Float, y: Float) {
        focusX = x; focusY = y; focusShownAt = SystemClock.uptimeMillis()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener { focusAnim = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed({ invalidate() }, 3100)
    }

    fun setExposureRange(maxEv: Float) { evRange = maxEv.coerceIn(0.5f, 4f) }

    fun captureBlink() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 220
            addUpdateListener { blink = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun clearFocusRing() { focusX = -1f; lockedText = null; ev = 0f; invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) return true
        // vertical drag while the focus ring is visible adjusts exposure
        val ringVisible = focusX >= 0f && SystemClock.uptimeMillis() - focusShownAt < 3000
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = e.y; draggingEv = false }
            MotionEvent.ACTION_MOVE -> if (ringVisible && e.pointerCount == 1) {
                val dy = e.y - lastY
                if (!draggingEv && kotlin.math.abs(dy) > Theme.dp(context, 12f)) draggingEv = true
                if (draggingEv) {
                    ev = (ev - dy / Theme.dp(context, 110f)).coerceIn(-evRange, evRange)
                    lastY = e.y
                    focusShownAt = SystemClock.uptimeMillis()
                    listener?.onExposureChanged(ev)
                    invalidate()
                    return true
                }
            }
        }
        if (draggingEv && e.actionMasked == MotionEvent.ACTION_UP) { draggingEv = false; return true }
        return gestures.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        var top = 0f; var bottom = h
        if (squareMask) {
            val side = w
            top = (h - side) / 2f; bottom = top + side
            c.drawRect(0f, 0f, w, top, maskP); c.drawRect(0f, bottom, w, h, maskP)
        }
        if (grid) {
            val gh = bottom - top
            for (i in 1..2) {
                c.drawLine(w * i / 3f, top, w * i / 3f, bottom, line)
                c.drawLine(0f, top + gh * i / 3f, w, top + gh * i / 3f, line)
            }
        }
        val age = SystemClock.uptimeMillis() - focusShownAt
        if (focusX >= 0f && (age < 3000 || lockedText != null)) {
            val size = Theme.dp(context, 70f) * (1.35f - 0.35f * focusAnim)
            ringP.alpha = if (age > 2200 && lockedText == null) 120 else 255
            val r = RectF(focusX - size / 2f, focusY - size / 2f, focusX + size / 2f, focusY + size / 2f)
            c.drawRoundRect(r, size * 0.18f, size * 0.18f, ringP)
            // exposure slider: a short track with the sun
            val sx = r.right + Theme.dp(context, 18f)
            val trackH = Theme.dp(context, 120f)
            val t0 = focusY - trackH / 2f; val t1 = focusY + trackH / 2f
            ringP.alpha = 140
            val sy = focusY - (ev / evRange) * trackH / 2f
            if (sy - Theme.dp(context, 14f) > t0) c.drawLine(sx, t0, sx, sy - Theme.dp(context, 14f), ringP)
            if (sy + Theme.dp(context, 14f) < t1) c.drawLine(sx, sy + Theme.dp(context, 14f), sx, t1, ringP)
            Glyphs.draw(c, Glyph.SUN, sx, sy, Theme.dp(context, 22f), Theme.WHITE)
            if (kotlin.math.abs(ev) > 0.05f) {
                textP.textSize = Theme.dp(context, 12f); textP.color = Theme.WHITE
                c.drawText("%+.1f".format(ev).replace(',', '.'), sx, t1 + Theme.dp(context, 16f), textP)
            }
        }
        val lt = lockedText
        if (lt != null) {
            textP.textSize = Theme.dp(context, 11f); textP.color = Theme.ON_SELECTED
            val tw = textP.measureText(lt) + Theme.dp(context, 20f)
            val cy = top + Theme.dp(context, 22f)
            val rr = RectF(w / 2f - tw / 2f, cy - Theme.dp(context, 12f), w / 2f + tw / 2f, cy + Theme.dp(context, 12f))
            c.drawRoundRect(rr, Theme.dp(context, 6f), Theme.dp(context, 6f), chipP)
            c.drawText(lt, w / 2f, cy - (textP.descent() + textP.ascent()) / 2f, textP)
        }
        if (countdown > 0) {
            textP.textSize = Theme.dp(context, 96f); textP.color = Theme.WHITE
            c.drawText(countdown.toString(), w / 2f, h / 2f - (textP.descent() + textP.ascent()) / 2f, textP)
        }
        if (blink > 0f) { blinkP.alpha = (blink * 255).toInt(); c.drawRect(0f, 0f, w, h, blinkP) }
    }
}
