package com.camerapremium.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Design tokens. The palette is strictly monochrome: black, white, three grays and their
 * transparencies. Active states use contrast, fill, outline, opacity or weight - never hue.
 * Every color in the UI must come from here (tools/check-palette.py enforces it at build time).
 */
object Theme {
    // ---- palette (exclusive)
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY_DARK = 0xFF1A1A1A.toInt()
    const val GRAY_MID = 0xFF808080.toInt()
    const val GRAY_LIGHT = 0xFFD0D0D0.toInt()

    /** Same color with a different opacity (0..1). */
    fun alpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (color and 0x00FFFFFF)

    // ---- semantic roles
    val TEXT = WHITE
    val TEXT_DIM = GRAY_LIGHT
    val TEXT_FAINT = GRAY_MID
    /** Translucent black over the photo (legibility only where needed). */
    val SCRIM = alpha(BLACK, 0.45f)
    val SCRIM_SOFT = alpha(BLACK, 0.28f)
    val SURFACE = GRAY_DARK
    val SURFACE_GLASS = alpha(GRAY_DARK, 0.92f)
    val LINE = alpha(WHITE, 0.16f)
    val OUTLINE = alpha(WHITE, 0.32f)
    /** Selected/active state: solid light fill with black content. */
    val SELECTED = WHITE
    val ON_SELECTED = BLACK

    // ---- dimensions (dp)
    object Dim {
        const val HEADER = 44f
        const val HEADER_BUTTON = 36f
        const val SEGMENT = 38f
        const val ACTION = 42f
        const val ICON_BUTTON = 42f
        const val RADIUS_SMALL = 10f
        const val GAP = 8f
        const val EDGE = 12f
        const val TOUCH_MIN = 40f
    }

    const val ANIM_FAST = 140L
    const val ANIM = 220L

    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Float): Int = dp(ctx, v).toInt()

    fun pill(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Vertical legibility gradient (top or bottom of the photo). */
    fun fade(topColor: Int, bottomColor: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor))

    fun label(ctx: Context, text: String, sizeSp: Float, color: Int = TEXT, medium: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (medium) SANS_MEDIUM else SANS
        letterSpacing = 0.02f
        includeFontPadding = false
    }

    /** Pill button. Primary = white fill / black text; secondary = translucent with outline. */
    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: (View) -> Unit): TextView =
        label(ctx, text, 14f, if (primary) ON_SELECTED else TEXT, true).apply {
            val padH = dpi(ctx, 16f)
            setPadding(padH, 0, padH, 0)
            minHeight = dpi(ctx, Dim.ACTION)
            background = if (primary) pill(SELECTED, dp(ctx, Dim.ACTION / 2))
            else pill(SCRIM, dp(ctx, Dim.ACTION / 2), dpi(ctx, 1f), OUTLINE)
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            isClickable = true
            setOnClickListener { onClick(it) }
        }
}
