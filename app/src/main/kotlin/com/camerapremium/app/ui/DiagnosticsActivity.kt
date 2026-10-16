package com.camerapremium.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.camerapremium.app.CameraPremiumApp
import com.camerapremium.app.storage.MediaSaver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real capabilities of this device, as reported by Camera2, plus the last captures' timings.
 * Exported as a text file so it can be shared for tuning the pipeline per device.
 */
class DiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CameraPremiumApp.of(this)
        val report = buildString {
            append(app.ensureInventory().report())
            append("\n== Ultimas capturas ==\n")
            for (r in app.store.all().take(15)) {
                append(SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(r.timestampMs)))
                append(" [${r.lensLabel}] ${r.timings}\n  ${r.summary}\n")
            }
            val rt = Runtime.getRuntime()
            append("\nMemoria Java maxima: ${rt.maxMemory() / (1 shl 20)} MB, nucleos: ${rt.availableProcessors()}\n")
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dpi(context, 16f), Theme.dpi(context, 8f), Theme.dpi(context, 16f), Theme.dpi(context, 8f))
            addView(Theme.label(context, "Diagnostico", 20f, Theme.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Theme.button(context, "Exportar") {
                val name = "camerapremium_diagnostico_${System.currentTimeMillis()}.txt"
                val uri = MediaSaver.saveText(this@DiagnosticsActivity, name, report)
                if (uri != null) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, report)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar diagnostico"))
                }
            })
        }
        root.addView(bar)
        val text = TextView(this).apply {
            this.text = report; setTextColor(Theme.WHITE_DIM); typeface = Typeface.MONOSPACE; textSize = 10.5f
            setTextIsSelectable(true)
            setPadding(Theme.dpi(context, 14f), 0, Theme.dpi(context, 14f), Theme.dpi(context, 30f))
        }
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
    }
}
