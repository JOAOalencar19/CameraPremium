package com.camerapremium.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.camerapremium.app.CameraPremiumApp
import com.camerapremium.app.storage.CaptureRecord
import com.camerapremium.app.storage.MediaSaver
import com.camerapremium.app.ui.views.CompareView
import com.camerapremium.app.ui.views.Glyph
import com.camerapremium.app.ui.views.GlyphButton
import java.io.File
import java.util.concurrent.Executors

/**
 * Review of the last capture: the original (camera system output) against the Natural Premium
 * rendition, and the choice of which one the gallery keeps. Nothing is overwritten without an
 * explicit confirmation, and the version that leaves the gallery stays stored in the app.
 */
class ReviewActivity : Activity() {
    private lateinit var app: CameraPremiumApp
    private lateinit var compare: CompareView
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private lateinit var details: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var record: CaptureRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = CameraPremiumApp.of(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        compare = CompareView(this)
        root.addView(compare, FrameLayout.LayoutParams(-1, -1))

        val back = GlyphButton(this, Glyph.BACK).apply { fillColor = Theme.SCRIM; setOnClickListener { finish() } }
        root.addView(back, FrameLayout.LayoutParams(Theme.dpi(this, 44f), Theme.dpi(this, 44f)).also { it.gravity = Gravity.TOP or Gravity.START })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 0, 0, 0))
            val p = Theme.dpi(context, 14f); setPadding(p, p, p, p)
        }
        // segmented mode switch
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val segButtons = ArrayList<TextView>()
        for ((name, m) in listOf("Original" to CompareView.Mode.ORIGINAL, "Comparar" to CompareView.Mode.SPLIT, "Natural Premium" to CompareView.Mode.PROCESSED)) {
            val b = Theme.button(this, name) { v ->
                compare.mode = m
                for (x in segButtons) { x.background = Theme.pill(Theme.PANEL_2, Theme.dp(this, 20f)); x.setTextColor(Theme.WHITE) }
                (v as TextView).background = Theme.pill(Theme.ACCENT, Theme.dp(this, 20f)); v.setTextColor(Color.BLACK)
            }
            segButtons.add(b)
            seg.addView(b, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = Theme.dpi(this, 6f) })
        }
        segButtons[1].background = Theme.pill(Theme.ACCENT, Theme.dp(this, 20f)); segButtons[1].setTextColor(Color.BLACK)
        panel.addView(seg)
        val tip = Theme.label(this, "Arraste a divisoria · segure para ver a outra versao · toque duplo para ampliar", 11f, Theme.WHITE_DIM).apply {
            gravity = Gravity.CENTER; setPadding(0, Theme.dpi(context, 8f), 0, Theme.dpi(context, 8f))
        }
        panel.addView(tip)
        status = Theme.label(this, "", 13f, Theme.WHITE, true).apply { gravity = Gravity.CENTER }
        panel.addView(status)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) },
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Theme.dpi(this, 10f) })
        details = Theme.label(this, "", 10.5f, Theme.WHITE_DIM).apply { visibility = View.GONE; typeface = android.graphics.Typeface.MONOSPACE }
        panel.addView(ScrollView(this).apply { addView(details) }, LinearLayout.LayoutParams(-1, Theme.dpi(this, 140f)).also { it.topMargin = Theme.dpi(this, 8f) })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2).also { it.gravity = Gravity.BOTTOM })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            (back.layoutParams as FrameLayout.LayoutParams).apply { topMargin = b.top + 8; leftMargin = 16 }
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, b.bottom + Theme.dpi(this, 12f))
            details.visibility = View.GONE
            insets
        }
        load(intent.getLongExtra(EXTRA_ID, -1L))
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    private fun load(id: Long) {
        val r = app.store.get(id) ?: run { status.text = "Foto nao encontrada"; return }
        record = r
        refreshActions()
        val target = resources.displayMetrics.widthPixels * 2
        io.execute {
            val orig = r.originalPath?.let { decode(ImageDecoder.createSource(File(it)), target) }
            // processed version: in the gallery when preferred, otherwise in the app's private copy
            val procSource = when {
                r.summary.isEmpty() -> null
                r.preferred == "processed" && r.processedUri != null -> ImageDecoder.createSource(contentResolver, Uri.parse(r.processedUri))
                r.processedPath != null -> ImageDecoder.createSource(File(r.processedPath!!))
                else -> null
            }
            val proc = procSource?.let { decode(it, target) }
            runOnUiThread {
                compare.original = orig
                compare.processed = proc
                if (proc == null) compare.mode = CompareView.Mode.ORIGINAL
            }
        }
    }

    /** Same target size for both, so the comparison is pixel-equivalent on screen. */
    private fun decode(src: ImageDecoder.Source, targetW: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val w = info.size.width
            var sample = 1
            while (w / (sample * 2) >= targetW) sample *= 2
            d.setTargetSampleSize(sample)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) { null }

    private fun refreshActions() {
        val r = record ?: return
        actions.removeAllViews()
        val hasProcessed = r.summary.isNotEmpty() && r.originalPath != null
        status.text = when {
            !hasProcessed -> "Na galeria: original (processamento desativado)"
            r.preferred == "processed" -> "Na galeria: Natural Premium" + (if (r.originalGalleryUri != null) " + original" else " · original guardado no app")
            else -> "Na galeria: original · Natural Premium guardado no app"
        }
        fun add(v: View) { actions.addView(v, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = Theme.dpi(this, 8f) }) }
        if (hasProcessed) {
            if (r.preferred == "processed") add(Theme.button(this, "Usar original na galeria") { confirmSwap(toOriginal = true) })
            else add(Theme.button(this, "Usar Natural Premium", primary = true) { confirmSwap(toOriginal = false) })
            if (r.preferred == "processed" && r.originalGalleryUri == null && r.originalPath != null) add(Theme.button(this, "Salvar as duas") { saveBoth() })
        }
        add(Theme.button(this, "Abrir na Galeria") { openGallery() })
        add(Theme.button(this, "Detalhes") { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        add(Theme.button(this, "Excluir") { confirmDelete() })
        details.text = "Lente: ${r.lensLabel}\n${r.summary.replace(" | ", "\n")}\nTempos (ms): ${r.timings}"
    }

    private fun confirmSwap(toOriginal: Boolean) {
        val msg = if (toOriginal) "A foto da galeria passara a ser a versao original. A versao Natural Premium continua guardada no app e pode voltar a qualquer momento."
        else "A foto da galeria voltara a ser a versao Natural Premium. O original continua guardado no app."
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Substituir na galeria?").setMessage(msg)
            .setPositiveButton("Substituir") { _, _ -> swap(toOriginal) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun swap(toOriginal: Boolean) {
        val r = record ?: return
        val uri = r.processedUri?.let { Uri.parse(it) } ?: return
        io.execute {
            try {
                if (toOriginal) {
                    val processed = MediaSaver.read(this, uri) ?: error("leitura falhou")
                    val keep = File(app.store.originalsDir, "proc_${r.id}.jpg"); keep.writeBytes(processed)
                    MediaSaver.overwrite(this, uri, File(r.originalPath!!).readBytes())
                    r.processedPath = keep.absolutePath; r.preferred = "original"
                } else {
                    MediaSaver.overwrite(this, uri, File(r.processedPath!!).readBytes())
                    r.preferred = "processed"
                }
                app.store.put(r)
                runOnUiThread { refreshActions() }
            } catch (e: Exception) { runOnUiThread { status.text = "Nao foi possivel substituir: ${e.message}" } }
        }
    }

    private fun saveBoth() {
        val r = record ?: return
        io.execute {
            try {
                val bytes = File(r.originalPath!!).readBytes()
                r.originalGalleryUri = MediaSaver.insert(this, bytes, "CP_${r.id}_original.jpg", MediaSaver.ORIGINALS, "image/jpeg", r.timestampMs).toString()
                app.store.put(r)
                runOnUiThread { refreshActions() }
            } catch (e: Exception) { runOnUiThread { status.text = "Falha ao salvar: ${e.message}" } }
        }
    }

    private fun openGallery() {
        val r = record ?: return
        val uri = r.processedUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { status.text = "Nenhum app de galeria encontrado" }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Excluir esta foto?").setMessage("Remove a foto da galeria e as copias guardadas no app.")
            .setPositiveButton("Excluir") { _, _ -> delete() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun delete() {
        val r = record ?: return
        io.execute {
            r.processedUri?.let { MediaSaver.delete(this, Uri.parse(it)) }
            r.originalGalleryUri?.let { MediaSaver.delete(this, Uri.parse(it)) }
            r.originalPath?.let { File(it).delete() }
            r.processedPath?.let { File(it).delete() }
            app.store.remove(r.id)
            runOnUiThread { finish() }
        }
    }

    companion object { const val EXTRA_ID = "record_id" }
}
