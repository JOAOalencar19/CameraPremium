package com.camerapremium.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.camerapremium.app.CameraPremiumApp
import com.camerapremium.app.storage.CaptureRecord
import com.camerapremium.app.storage.MediaSaver
import com.camerapremium.app.ui.views.CompareView
import com.camerapremium.app.ui.views.Glyph
import com.camerapremium.app.ui.views.GlyphButton
import com.camerapremium.app.ui.views.Sheet
import java.io.File
import java.util.concurrent.Executors

/**
 * Review of a capture: the original (camera system output) against the Natural Premium
 * rendition, and the choice of which one the gallery keeps.
 *
 * Layout: the photo fills the screen; a slim header (back + what is shown), and at the bottom
 * a compact segmented control and one action row, all floating on translucent gradients.
 * Nothing is overwritten without confirmation; the version that leaves the gallery stays in the app.
 */
class ReviewActivity : Activity() {
    private lateinit var app: CameraPremiumApp
    private lateinit var root: FrameLayout
    private lateinit var compare: CompareView
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var primary: TextView
    private val segments = ArrayList<TextView>()
    private val io = Executors.newSingleThreadExecutor()
    private var record: CaptureRecord? = null
    private var insetTop = 0

    private val modes = listOf(CompareView.Mode.ORIGINAL, CompareView.Mode.SPLIT, CompareView.Mode.PROCESSED)
    private val modeNames = listOf("Original", "Comparar", "Natural Premium")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = CameraPremiumApp.of(this)
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BLACK) }
        setContentView(root)

        compare = CompareView(this)
        root.addView(compare, FrameLayout.LayoutParams(-1, -1))
        // legibility gradients instead of opaque panels
        val topFade = View(this).apply { background = Theme.fade(Theme.alpha(Theme.BLACK, 0.55f), Theme.alpha(Theme.BLACK, 0f)) }
        val bottomFade = View(this).apply { background = Theme.fade(Theme.alpha(Theme.BLACK, 0f), Theme.alpha(Theme.BLACK, 0.62f)) }
        root.addView(topFade, FrameLayout.LayoutParams(-1, 0).also { it.gravity = Gravity.TOP })
        root.addView(bottomFade, FrameLayout.LayoutParams(-1, 0).also { it.gravity = Gravity.BOTTOM })

        // ---- slim header: back + what is on screen
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dpi(context, 6f), 0, Theme.dpi(context, Theme.Dim.EDGE), 0)
        }
        val back = GlyphButton(this, Glyph.BACK).apply {
            glyphSize = Theme.dp(context, 20f); contentDescription = "Voltar"; setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(Theme.dpi(this, Theme.Dim.TOUCH_MIN + 4f), Theme.dpi(this, Theme.Dim.TOUCH_MIN + 4f)))
        title = Theme.label(this, "Comparar", 15f, Theme.TEXT, true)
        header.addView(title)
        root.addView(header, FrameLayout.LayoutParams(-1, Theme.dpi(this, Theme.Dim.HEADER)).also { it.gravity = Gravity.TOP })

        hint = Theme.label(this, "", 12f, Theme.TEXT, false).apply {
            setPadding(Theme.dpi(context, 12f), Theme.dpi(context, 6f), Theme.dpi(context, 12f), Theme.dpi(context, 6f))
            background = Theme.pill(Theme.SCRIM, Theme.dp(context, 14f)); alpha = 0f; gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })

        // ---- bottom: compact segmented control + one action row
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = Theme.dpi(context, 3f); setPadding(p, p, p, p)
            background = Theme.pill(Theme.SCRIM, Theme.dp(context, Theme.Dim.SEGMENT / 2), Theme.dpi(context, 1f), Theme.LINE)
        }
        for ((i, name) in modeNames.withIndex()) {
            val t = Theme.label(this, name, 13f, Theme.TEXT_DIM, false).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                setPadding(Theme.dpi(context, 6f), 0, Theme.dpi(context, 6f), 0)
                setOnClickListener { selectMode(i) }
            }
            segments.add(t)
            seg.addView(t, LinearLayout.LayoutParams(0, -1, 1f))
        }
        bottom.addView(seg, LinearLayout.LayoutParams(-1, Theme.dpi(this, Theme.Dim.SEGMENT)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        primary = Theme.button(this, "", primary = true) { onPrimary() }
        actions.addView(primary, LinearLayout.LayoutParams(0, Theme.dpi(this, Theme.Dim.ACTION), 1f))
        val iconSize = Theme.dpi(this, Theme.Dim.ICON_BUTTON)
        val gallery = iconButton(Glyph.GALLERY, "Abrir na galeria") { openGallery() }
        val more = iconButton(Glyph.MORE, "Mais acoes") { showMore() }
        actions.addView(gallery, LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginStart = Theme.dpi(this, Theme.Dim.GAP) })
        actions.addView(more, LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginStart = Theme.dpi(this, Theme.Dim.GAP) })
        bottom.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Theme.dpi(this, Theme.Dim.GAP) })
        // never too wide on large screens; centered
        val maxW = Theme.dpi(this, 440f)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })

        compare.listener = object : CompareView.Listener {
            override fun onShowing(mode: CompareView.Mode) { title.text = modeNames[modes.indexOf(mode)] }
            override fun onInteraction() { hint.animate().alpha(0f).setDuration(Theme.ANIM_FAST).start() }
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = b.top
            (header.layoutParams as FrameLayout.LayoutParams).topMargin = b.top
            (hint.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + Theme.dpi(this, Theme.Dim.HEADER + 6f)
            topFade.layoutParams.height = b.top + Theme.dpi(this, Theme.Dim.HEADER + 24f)
            bottomFade.layoutParams.height = b.bottom + Theme.dpi(this, 140f)
            val edge = Theme.dpi(this, Theme.Dim.EDGE)
            (bottom.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(edge + b.left, 0, edge + b.right, b.bottom + Theme.dpi(this@ReviewActivity, 10f))
                width = minOf(root.width - 2 * edge - b.left - b.right, maxW).takeIf { root.width > 0 } ?: -1
            }
            compare.labelTop = (b.top + Theme.dpi(this, Theme.Dim.HEADER)).toFloat()
            root.requestLayout()
            insets
        }
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) root.requestApplyInsets() }
        renderSegments(1)
        load(intent.getLongExtra(EXTRA_ID, -1L))
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    private fun iconButton(g: Glyph, desc: String, onClick: () -> Unit) = GlyphButton(this, g).apply {
        fillColor = Theme.SCRIM; glyphSize = Theme.dp(context, 20f); contentDescription = desc
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------ view modes

    private fun selectMode(i: Int) {
        if (!segments[i].isEnabled) return
        compare.mode = modes[i]
        renderSegments(i)
    }

    /** Selected = white fill / black text; others transparent. A dot marks the gallery version. */
    private fun renderSegments(selected: Int) {
        val r = record
        val inGallery = when {
            r == null -> -1
            r.summary.isEmpty() || r.preferred == "original" -> 0
            else -> 2
        }
        for ((i, t) in segments.withIndex()) {
            val sel = i == selected
            t.background = if (sel) Theme.pill(Theme.SELECTED, Theme.dp(this, (Theme.Dim.SEGMENT - 6f) / 2)) else null
            t.setTextColor(if (sel) Theme.ON_SELECTED else Theme.TEXT_DIM)
            t.typeface = if (sel) Theme.SANS_MEDIUM else Theme.SANS
            val dot = if (i == inGallery) GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(if (sel) Theme.ON_SELECTED else Theme.TEXT_DIM)
                val d = Theme.dpi(this@ReviewActivity, 5f); setSize(d, d); setBounds(0, 0, d, d)
            } else null
            t.setCompoundDrawablesRelative(null, null, dot, null)
            t.compoundDrawablePadding = Theme.dpi(this, 5f)
            t.contentDescription = modeNames[i] + if (i == inGallery) ", na galeria" else ""
        }
    }

    private fun showHint(text: String, ms: Long = 1800) {
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
        hint.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    // ------------------------------------------------------------------------------ data

    private fun load(id: Long) {
        val r = app.store.get(id) ?: run { showHint("Foto nao encontrada", 2500); return }
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
                if (proc == null) selectMode(0)
                // a one-line tip only for the first few visits
                val shown = app.settings.compareHintsShown
                if (proc != null && shown < 2) {
                    app.settings.compareHintsShown = shown + 1
                    root.postDelayed({ showHint("Arraste para comparar · segure para alternar", 2400) }, 400)
                }
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

    private fun hasProcessed(r: CaptureRecord) = r.summary.isNotEmpty() && r.originalPath != null

    private fun refreshActions() {
        val r = record ?: return
        val has = hasProcessed(r)
        // the primary action is the real one: choose which version the gallery item holds
        primary.visibility = if (has) View.VISIBLE else View.INVISIBLE
        primary.text = if (r.preferred == "processed") "Usar original" else "Usar Natural Premium"
        primary.contentDescription = primary.text.toString() + " na galeria"
        for (i in 1..2) { segments[i].isEnabled = has; segments[i].alpha = if (has) 1f else 0.35f }
        renderSegments(modes.indexOf(compare.mode))
    }

    private fun onPrimary() {
        val r = record ?: return
        if (!hasProcessed(r)) return
        val toOriginal = r.preferred == "processed"
        val msg = if (toOriginal) "A galeria passa a mostrar a versao original. A Natural Premium continua guardada no app."
        else "A galeria volta a mostrar a versao Natural Premium. O original continua guardado no app."
        Sheet.confirm(this, "Substituir na galeria?", msg, "Substituir") { swap(toOriginal) }
    }

    private fun showMore() {
        val r = record ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (hasProcessed(r) && r.preferred == "processed" && r.originalGalleryUri == null) items.add("Salvar as duas na galeria" to { saveBoth() })
        items.add("Detalhes tecnicos" to {
            Sheet.text(this, "Detalhes", "Lente: ${r.lensLabel}\n${r.summary.replace(" | ", "\n")}\nTempos (ms): ${r.timings}")
        })
        items.add("Excluir" to {
            Sheet.confirm(this, "Excluir esta foto?", "Remove a foto da galeria e as copias guardadas no app.", "Excluir") { delete() }
        })
        Sheet.menu(this, items)
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
                runOnUiThread { refreshActions(); showHint(if (toOriginal) "Original na galeria" else "Natural Premium na galeria") }
            } catch (e: Exception) { runOnUiThread { showHint("Nao foi possivel substituir: ${e.message}", 3000) } }
        }
    }

    private fun saveBoth() {
        val r = record ?: return
        io.execute {
            try {
                val bytes = File(r.originalPath!!).readBytes()
                r.originalGalleryUri = MediaSaver.insert(this, bytes, "CP_${r.id}_original.jpg", MediaSaver.ORIGINALS, "image/jpeg", r.timestampMs).toString()
                app.store.put(r)
                runOnUiThread { refreshActions(); showHint("As duas versoes estao na galeria") }
            } catch (e: Exception) { runOnUiThread { showHint("Falha ao salvar: ${e.message}", 3000) } }
        }
    }

    private fun openGallery() {
        val r = record ?: return
        val uri = r.processedUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { showHint("Nenhum app de galeria encontrado") }
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
