package com.camerapremium.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.camerapremium.app.CrashReporter

/** Shows the last crash report (deliberately minimal UI, so it works even if the main UI is broken). */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.pending(this) ?: "Nenhum erro registrado."
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BLACK)
            setPadding(32, 120, 32, 60)
        }
        root.addView(TextView(this).apply {
            text = "O CameraPremium fechou na ultima vez.\nCopie ou compartilhe o texto abaixo e envie para o desenvolvimento."
            setTextColor(Theme.WHITE); textSize = 15f; setPadding(0, 0, 0, 24)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(Theme.button(this, "Copiar") {
            run {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("erro", report))
                Toast.makeText(this@CrashActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Theme.button(this, "Compartilhar") {
            run {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Compartilhar erro"))
            }
        })
        buttons.addView(Theme.button(this, "Abrir camera", primary = true) {
            run {
                CrashReporter.clear(this@CrashActivity)
                startActivity(Intent(this@CrashActivity, CameraActivity::class.java))
                finish()
            }
        })
        root.addView(buttons)
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report; setTextColor(Theme.GRAY_LIGHT); typeface = Typeface.MONOSPACE; textSize = 11f
                setTextIsSelectable(true); setPadding(0, 24, 0, 0)
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
