package com.camerapremium.app.ui.views

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.camerapremium.app.ui.Theme

/**
 * Monochrome bottom sheet used instead of system dialogs (whose buttons take the system accent
 * color). Confirmations, single choice lists, compact action menus and text panels.
 */
object Sheet {

    /** Shows a sheet over the whole window; returns a function that dismisses it. */
    fun show(activity: Activity, build: (LinearLayout, () -> Unit) -> Unit): () -> Unit {
        val decor = activity.window.decorView as ViewGroup
        val ctx = activity
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Theme.alpha(Theme.BLACK, 0.5f))
            alpha = 0f
            isClickable = true
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dpi(ctx, 16f)
            setPadding(p, p, p, p)
            background = Theme.pill(Theme.SURFACE_GLASS, Theme.dp(ctx, 18f), Theme.dpi(ctx, 1f), Theme.LINE)
            isClickable = true // swallow taps
        }
        val bottomInset = decor.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        overlay.addView(card, FrameLayout.LayoutParams(-1, -2).also {
            it.gravity = Gravity.BOTTOM
            val m = Theme.dpi(ctx, Theme.Dim.EDGE)
            it.setMargins(m, m, m, m + bottomInset)
        })
        var dismissed = false
        val dismiss = {
            if (!dismissed) {
                dismissed = true
                overlay.animate().alpha(0f).setDuration(Theme.ANIM_FAST).withEndAction { decor.removeView(overlay) }.start()
            }
        }
        overlay.setOnClickListener { dismiss() }
        build(card, dismiss)
        decor.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        card.translationY = Theme.dp(ctx, 24f)
        overlay.animate().alpha(1f).setDuration(Theme.ANIM_FAST).start()
        card.animate().translationY(0f).setDuration(Theme.ANIM).start()
        return dismiss
    }

    fun confirm(activity: Activity, title: String, message: String, confirmText: String, onConfirm: () -> Unit) {
        show(activity) { card, dismiss ->
            card.addView(Theme.label(activity, title, 17f, Theme.TEXT, true))
            card.addView(Theme.label(activity, message, 13.5f, Theme.TEXT_DIM).apply {
                setPadding(0, Theme.dpi(activity, 8f), 0, Theme.dpi(activity, 16f)); setLineSpacing(0f, 1.15f)
            })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Theme.button(activity, "Cancelar") { dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(View(activity), LinearLayout.LayoutParams(Theme.dpi(activity, Theme.Dim.GAP), 1))
            row.addView(Theme.button(activity, confirmText, primary = true) { dismiss(); onConfirm() }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }
    }

    fun choose(activity: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        show(activity) { card, dismiss ->
            card.addView(Theme.label(activity, title, 15f, Theme.TEXT_DIM, true).apply { setPadding(0, 0, 0, Theme.dpi(activity, 8f)) })
            for ((i, o) in options.withIndex()) {
                val sel = i == selected
                card.addView(Theme.label(activity, o, 15f, if (sel) Theme.ON_SELECTED else Theme.TEXT, sel).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = Theme.dpi(activity, 44f)
                    setPadding(Theme.dpi(activity, 14f), 0, Theme.dpi(activity, 14f), 0)
                    if (sel) background = Theme.pill(Theme.SELECTED, Theme.dp(activity, 12f))
                    setOnClickListener { dismiss(); onPick(i) }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Theme.dpi(activity, 2f) })
            }
        }
    }

    /** Compact action list (the "more" menu). */
    fun menu(activity: Activity, items: List<Pair<String, () -> Unit>>) {
        show(activity) { card, dismiss ->
            for ((name, action) in items) {
                card.addView(Theme.label(activity, name, 15f, Theme.TEXT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = Theme.dpi(activity, 44f)
                    setPadding(Theme.dpi(activity, 8f), 0, Theme.dpi(activity, 8f), 0)
                    setOnClickListener { dismiss(); action() }
                })
            }
        }
    }

    fun text(activity: Activity, title: String, body: String) {
        show(activity) { card, dismiss ->
            card.addView(Theme.label(activity, title, 15f, Theme.TEXT, true).apply { setPadding(0, 0, 0, Theme.dpi(activity, 8f)) })
            val tv = TextView(activity).apply {
                text = body; setTextColor(Theme.TEXT_DIM); typeface = Typeface.MONOSPACE; textSize = 10.5f
                setTextIsSelectable(true)
            }
            card.addView(ScrollView(activity).apply { addView(tv) }, LinearLayout.LayoutParams(-1, Theme.dpi(activity, 260f)))
            card.addView(Theme.button(activity, "Fechar") { dismiss() }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Theme.dpi(activity, 10f) })
        }
    }
}
