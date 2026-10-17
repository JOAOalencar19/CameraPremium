package com.camerapremium.app.ui

import android.app.Activity
import com.camerapremium.app.ui.views.Sheet
import com.camerapremium.app.settings.CaptureSource
import com.camerapremium.app.settings.Sharpness
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.camerapremium.app.CameraPremiumApp
import com.camerapremium.app.settings.LensSelection
import com.camerapremium.app.settings.ProcessingChoice
import com.camerapremium.imaging.pipeline.ProcessingProfile

/** Secondary settings, grouped; the main screen keeps only essentials. */
class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CameraPremiumApp.of(this)
        val s = app.settings
        val inv = app.ensureInventory()
        val rear = inv.rearLogical
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Theme.dpi(context, 20f), 0, Theme.dpi(context, 20f), Theme.dpi(context, 40f)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BLACK); addView(list) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }

        list.addView(Theme.label(this, "Ajustes", 28f, Theme.WHITE, true).apply { setPadding(0, Theme.dpi(context, 24f), 0, Theme.dpi(context, 8f)) })

        section("Fotografia")
        choice("Perfil", ProcessingProfile.ALL.map { it.displayName }, ProcessingProfile.ALL.indexOfFirst { it.id == s.profileId }.coerceAtLeast(0),
            "Estetica natural: cores fieis, pele agradavel, HDR equilibrado e nitidez contida.") { s.profileId = ProcessingProfile.ALL[it].id }
        choice("Processamento", listOf("Natural Premium", "Somente o sistema"), if (s.processing == ProcessingChoice.NATURAL_PREMIUM) 0 else 1,
            "Natural Premium usa a fusao multiquadro e o pipeline proprio. O original do sistema e sempre preservado para comparacao.") {
            s.processing = if (it == 0) ProcessingChoice.NATURAL_PREMIUM else ProcessingChoice.SYSTEM_ONLY
        }
        choice("Fonte do Natural Premium", listOf("Hibrida (recomendada)", "Nativa do aparelho", "Multiquadro proprio (experimental)"),
            s.captureSource.ordinal,
            "Hibrida mantem a cor e o tom do aparelho, mas troca a reducao de ruido e a nitidez fortes do processador (que geram textura de aquarela e contornos) por fusao de varios quadros. Nativa usa o processamento completo do aparelho em cada quadro.") {
            s.captureSource = CaptureSource.values()[it]
        }
        val anyExt = rear?.extensions ?: emptyList()
        val hasNight = android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT in anyExt
        val hasAuto = android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_AUTOMATIC in anyExt
        toggle("Noite com processamento do aparelho", s.vendorNight && hasNight,
            if (hasNight) "No modo Noite usa o multiquadro do proprio fabricante (Camera2 Extensions). O Natural Premium e aplicado com toque leve por cima." else "Este aparelho nao expoe o modo Noite do fabricante para apps.",
            enabled = hasNight) { s.vendorNight = it }
        toggle("Foto com processamento do aparelho", s.vendorAuto && hasAuto,
            if (hasAuto) "No modo Foto usa o processamento automatico do fabricante. Mais lento por foto." else "Este aparelho nao expoe o modo Auto do fabricante para apps.",
            enabled = hasAuto) { s.vendorAuto = it }
        choice("Nitidez", listOf("Suave", "Natural", "Definida"), s.sharpness.ordinal,
            "Natural evita contornos marcados. Fontes ja nitidas pelo aparelho nao recebem nitidez extra.") {
            s.sharpness = Sharpness.values()[it]
        }
        toggle("Suavizacao sutil da pele", s.skinSmoothing, "Atenua so manchas de media frequencia. Poros, cilios, sobrancelhas e contornos sao preservados.") { s.skinSmoothing = it }
        toggle("Fusao multiquadro (HDR + ruido)", s.multiFrame, "Captura varios quadros curtos e os funde com protecao contra fantasmas.") { s.multiFrame = it }
        if (Build.VERSION.SDK_INT >= 34) toggle("Ultra HDR", s.ultraHdr, "Grava um mapa de ganho: altas luzes brilham em telas HDR, a imagem SDR continua identica.") { s.ultraHdr = it }
        toggle("Cor ampla (Display P3)", s.displayP3, "Arquivo com perfil P3. Desligado = sRGB, compativel com tudo.") { s.displayP3 = it }
        choice("Qualidade JPEG", listOf("Alta (92)", "Muito alta (95)", "Maxima (98)"), when (s.jpegQuality) { 92 -> 0; 98 -> 2; else -> 1 }, null) {
            s.jpegQuality = intArrayOf(92, 95, 98)[it]
        }
        val highResAvail = rear != null && rear.ultraHighResolution && rear.maxResJpegSizes.isNotEmpty()
        toggle("Alta resolucao", s.highResolution, if (highResAvail) "Usa o modo de pixel maximo do sensor (${rear!!.maxResJpegSizes.first()}). Sem fusao multiquadro." else "Este aparelho nao expoe o modo de resolucao maxima para apps.", enabled = highResAvail) { s.highResolution = it }
        val rawAvail = rear?.rawCapable == true
        toggle("RAW (DNG)", s.raw, if (rawAvail) "Salva tambem um DNG do quadro original em DCIM/CameraPremium/RAW." else "RAW nao disponivel neste aparelho.", enabled = rawAvail) { s.raw = it }
        toggle("Salvar original na galeria", s.saveOriginalToGallery, "Desligado: o original fica guardado no app e pode ser recuperado na tela de comparacao.") { s.saveOriginalToGallery = it }

        section("Captura")
        toggle("Linhas de grade", s.grid, null) { s.grid = it }
        choice("Temporizador", listOf("Desligado", "3 s", "10 s"), when (s.timerSeconds) { 3 -> 1; 10 -> 2; else -> 0 }, null) { s.timerSeconds = intArrayOf(0, 3, 10)[it] }
        toggle("Espelhar selfies", s.mirrorFront, "Salva a selfie como aparece na pre-visualizacao.") { s.mirrorFront = it }
        toggle("Flash de tela (frontal)", s.screenFlash, null) { s.screenFlash = it }
        choice("Selecao de lente", listOf("Automatica", "Lente fisica preferencial"), if (s.lensSelection == LensSelection.AUTOMATIC) 0 else 1,
            "Automatica deixa o sistema trocar de lente (melhor em pouca luz). Fisica abre a lente nativa quando o Android a expoe separadamente.") {
            s.lensSelection = if (it == 0) LensSelection.AUTOMATIC else LensSelection.PHYSICAL_PREFERRED
        }
        toggle("Som do obturador", s.shutterSound, null) { s.shutterSound = it }

        section("Avancado")
        toggle("Mostrar tempos de processamento", s.showPerf, null) { s.showPerf = it }
        action("Diagnostico da camera", "Lentes, fatores de zoom, formatos e capacidades reais deste aparelho; exportavel.") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        action("Sobre", "CameraPremium · pipeline Natural Premium processado inteiramente no aparelho.") {}
    }

    private fun section(t: String) {
        list.addView(Theme.label(this, t.uppercase(), 12f, Theme.GRAY_MID, true).apply {
            letterSpacing = 0.12f; setPadding(0, Theme.dpi(context, 26f), 0, Theme.dpi(context, 6f))
        })
    }

    private fun row(title: String, sub: String?, right: View?, onClick: (() -> Unit)?, enabled: Boolean = true): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dpi(context, 12f), 0, Theme.dpi(context, 12f))
            alpha = if (enabled) 1f else 0.45f
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Theme.label(this, title, 16f))
        if (sub != null) texts.addView(Theme.label(this, sub, 12.5f, Theme.TEXT_DIM).apply { setPadding(0, Theme.dpi(context, 3f), Theme.dpi(context, 12f), 0) })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) r.addView(right)
        if (onClick != null && enabled) r.setOnClickListener { onClick() }
        list.addView(r)
        list.addView(View(this).apply { setBackgroundColor(Theme.LINE) }, LinearLayout.LayoutParams(-1, 1))
        return r
    }

    private fun toggle(title: String, value: Boolean, sub: String?, enabled: Boolean = true, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = value && enabled; isEnabled = enabled
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Theme.WHITE, Theme.GRAY_MID))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Theme.alpha(Theme.WHITE, 0.45f), Theme.LINE))
            setOnCheckedChangeListener { _, c -> set(c) }
        }
        row(title, sub, sw, { sw.toggle() }, enabled)
    }

    private fun choice(title: String, options: List<String>, selected: Int, sub: String?, set: (Int) -> Unit) {
        val value = Theme.label(this, options[selected.coerceIn(0, options.size - 1)], 14f, Theme.GRAY_LIGHT, true)
        row(title, sub, value, {
            Sheet.choose(this, title, options, options.indexOf(value.text.toString())) { which ->
                set(which); value.text = options[which]
            }
        })
    }

    private fun action(title: String, sub: String?, onClick: () -> Unit) {
        row(title, sub, Theme.label(this, "›", 22f, Theme.TEXT_DIM) as TextView, onClick)
    }
}
