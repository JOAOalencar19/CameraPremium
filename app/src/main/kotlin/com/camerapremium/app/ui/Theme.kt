package com.camerapremium.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/** Visual identity: black canvas, warm-white controls, a single champagne accent. */
object Theme {
    val ACCENT = Color.rgb(242, 196, 109)
    val ACCENT_SOFT = Color.argb(60, 242, 196, 109)
    val WHITE = Color.rgb(246, 244, 239)
    val WHITE_DIM = Color.argb(170, 246, 244, 239)
    val SCRIM = Color.argb(110, 0, 0, 0)
    val PANEL = Color.rgb(22, 22, 24)
    val PANEL_2 = Color.rgb(34, 34, 37)
    val LINE = Color.argb(40, 255, 255, 255)
    val DANGER = Color.rgb(255, 120, 110)

    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Float): Int = dp(ctx, v).toInt()

    fun pill(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun label(ctx: Context, text: String, sizeSp: Float, color: Int = WHITE, medium: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (medium) SANS_MEDIUM else SANS
        letterSpacing = 0.02f
    }

    /** Text button with the app's pill style. */
    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: (View) -> Unit): TextView = label(ctx, text, 14f, if (primary) Color.BLACK else WHITE, true).apply {
        val padH = dpi(ctx, 16f); val padV = dpi(ctx, 10f)
        setPadding(padH, padV, padH, padV)
        background = pill(if (primary) ACCENT else PANEL_2, dp(ctx, 20f))
        gravity = android.view.Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick(it) }
    }
}
