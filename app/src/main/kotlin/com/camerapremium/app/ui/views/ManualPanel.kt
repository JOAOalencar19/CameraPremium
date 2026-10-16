package com.camerapremium.app.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.hardware.camera2.CameraMetadata
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.camerapremium.app.camera.LensInfo
import com.camerapremium.app.camera.ManualSettings
import com.camerapremium.app.ui.Theme
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** PRO controls: ISO, shutter, focus and white balance presets. Each can go back to automatic. */
class ManualPanel(ctx: Context, private val lens: LensInfo, private val onChange: (ManualSettings?) -> Unit) : LinearLayout(ctx) {
    private var iso: Int? = null
    private var exposureNs: Long? = null
    private var focus: Float? = null
    private var awb: Int? = null

    init {
        orientation = VERTICAL
        val pad = Theme.dpi(ctx, 14f)
        setPadding(pad, pad / 2, pad, pad / 2)
        background = Theme.pill(Theme.SCRIM, Theme.dp(ctx, 18f))
        val isoR = lens.isoRange
        if (isoR != null && lens.manualSensor) addSlider("ISO", { v -> iso = logMap(v, isoR.lower.toFloat(), isoR.upper.toFloat()).roundToInt(); "$iso" }, { iso = null })
        val expR = lens.exposureRange
        if (expR != null && lens.manualSensor) {
            val lo = maxOf(expR.lower, 125_000L).toFloat(); val hi = minOf(expR.upper, 2_000_000_000L).toFloat()
            addSlider("Obturador", { v ->
                exposureNs = logMap(v, lo, hi).toLong()
                val s = exposureNs!! / 1e9
                if (s >= 0.5) "%.1fs".format(s) else "1/${(1 / s).roundToInt()}"
            }, { exposureNs = null })
        }
        if (lens.hasAutoFocus && CameraMetadata.CONTROL_AF_MODE_OFF in lens.afModes) {
            addSlider("Foco", { v ->
                focus = v * lens.minFocusDistance
                if (focus!! < 0.05f) "∞" else "%.2fm".format(1f / focus!!)
            }, { focus = null })
        }
        val presets = listOf(
            "Auto" to null, "Sol" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Nublado" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "Sombra" to CameraMetadata.CONTROL_AWB_MODE_SHADE, "Tungst." to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            "Fluor." to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        ).filter { p -> val m = p.second; m == null || m in lens.awbModes }
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val chips = ArrayList<TextView>()
        for ((name, mode) in presets) {
            val chip = Theme.label(ctx, name, 12f, Theme.WHITE, true).apply {
                setPadding(Theme.dpi(ctx, 9f), Theme.dpi(ctx, 6f), Theme.dpi(ctx, 9f), Theme.dpi(ctx, 6f))
            }
            chip.setOnClickListener {
                awb = mode
                for (c in chips) c.setTextColor(Theme.WHITE)
                chip.setTextColor(Theme.ACCENT)
                emit()
            }
            if (mode == null) chip.setTextColor(Theme.ACCENT)
            chips.add(chip)
            row.addView(chip)
        }
        addView(row)
    }

    private fun addSlider(name: String, format: (Float) -> String, reset: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = Theme.label(context, name, 12f, Theme.WHITE_DIM, true).apply { minWidth = Theme.dpi(context, 72f) }
        val value = Theme.label(context, "A", 12f, Theme.ACCENT, true).apply { minWidth = Theme.dpi(context, 52f); gravity = Gravity.END }
        val bar = SeekBar(context).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Theme.ACCENT)
            thumbTintList = ColorStateList.valueOf(Theme.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Theme.LINE)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { value.text = format(p / 1000f); emit() } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        value.setOnClickListener { reset(); value.text = "A"; emit() } // tap the value = back to auto
        row.addView(label)
        row.addView(bar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        addView(row)
    }

    private fun emit() {
        val m = if (iso == null && exposureNs == null && focus == null && awb == null) null else ManualSettings(iso, exposureNs, focus, awb)
        onChange(m)
    }

    private fun logMap(t: Float, lo: Float, hi: Float): Float = exp(ln(lo) + (ln(hi) - ln(lo)) * t)
}
