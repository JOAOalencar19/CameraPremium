package com.camerapremium.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.camerapremium.app.CameraPremiumApp
import com.camerapremium.app.camera.CameraController
import com.camerapremium.app.camera.CameraInventory
import com.camerapremium.app.camera.CaptureSpec
import com.camerapremium.app.camera.LensInfo
import com.camerapremium.app.camera.SceneHint
import com.camerapremium.app.camera.ZoomStop
import com.camerapremium.app.processing.JobOptions
import com.camerapremium.app.processing.PhotoProcessor
import com.camerapremium.app.settings.AppSettings
import com.camerapremium.app.settings.AspectRatio
import com.camerapremium.app.settings.CaptureMode
import com.camerapremium.app.settings.FlashMode
import com.camerapremium.app.settings.LensSelection
import com.camerapremium.app.settings.ProcessingChoice
import com.camerapremium.app.storage.CaptureRecord
import com.camerapremium.app.ui.views.Glyph
import com.camerapremium.app.ui.views.GlyphButton
import com.camerapremium.app.ui.views.ManualPanel
import com.camerapremium.app.ui.views.ModeSelector
import com.camerapremium.app.ui.views.ShutterButton
import com.camerapremium.app.ui.views.ThumbnailView
import com.camerapremium.app.ui.views.ViewfinderOverlay
import com.camerapremium.app.ui.views.ZoomBar
import kotlin.math.abs

/** Main screen: live preview and the few controls a photographer needs. */
class CameraActivity : Activity(), CameraController.Listener, PhotoProcessor.Listener {
    private lateinit var app: CameraPremiumApp
    private lateinit var settings: AppSettings
    private lateinit var inventory: CameraInventory
    private lateinit var controller: CameraController
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var overlay: ViewfinderOverlay
    private lateinit var cover: ImageView
    private lateinit var flashBtn: GlyphButton
    private lateinit var timerBtn: GlyphButton
    private lateinit var settingsBtn: GlyphButton
    private lateinit var aspectChip: TextView
    private lateinit var sceneChip: TextView
    private lateinit var lensLabel: TextView
    private lateinit var hint: TextView
    private lateinit var zoomBar: ZoomBar
    private lateinit var modeSelector: ModeSelector
    private lateinit var shutter: ShutterButton
    private lateinit var thumb: ThumbnailView
    private lateinit var flipBtn: GlyphButton
    private lateinit var screenFlash: View
    private lateinit var permissionView: LinearLayout
    private var manualPanel: ManualPanel? = null

    private var lens: LensInfo? = null
    private var previewSize: Size? = null
    private var surfaceReady = false
    private var factor = 1f
    private var zoomAnim: ValueAnimator? = null
    private var deviceOrientation = 0
    private var lastSwitchAt = 0L
    private var lastRecordId: Long? = null
    private var countdownRunning = false
    private var insetTop = 0
    private var insetBottom = 0
    private var modes = listOf(CaptureMode.PHOTO, CaptureMode.NIGHT, CaptureMode.PRO)
    private var mode = CaptureMode.PHOTO
    private val sound = MediaActionSound()
    private lateinit var orientationListener: OrientationEventListener
    private var crashedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A previous run crashed: show the report first (it can be copied/shared from the phone).
        if (com.camerapremium.app.CrashReporter.pending(this) != null) {
            startActivity(Intent(this, CrashActivity::class.java))
            crashedBefore = true
            finish()
            return
        }
        app = CameraPremiumApp.of(this)
        settings = app.settings
        // created up-front (no permission needed) so UI callbacks can always reach it
        inventory = app.inventory
        controller = CameraController(this, inventory, app.processor)
        controller.listener = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.TRANSPARENT
        buildUi()
        sound.load(MediaActionSound.SHUTTER_CLICK)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = ((o + 45) / 90 * 90) % 360
                if (r != deviceOrientation) { deviceOrientation = r; rotateIcons(-r.toFloat()) }
            }
        }
        mode = settings.lastMode.takeIf { it in modes } ?: CaptureMode.PHOTO
    }

    // ------------------------------------------------------------------------------ UI

    private fun buildUi() {
        val ctx = this
        root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        previewFrame = FrameLayout(ctx).apply { clipChildren = true }
        surface = SurfaceView(ctx)
        overlay = ViewfinderOverlay(ctx)
        cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
        previewFrame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(cover, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, FrameLayout.LayoutParams(-1, -1))

        // top bar
        flashBtn = GlyphButton(ctx, Glyph.FLASH_OFF).apply { setOnClickListener { cycleFlash() }; contentDescription = "Flash" }
        timerBtn = GlyphButton(ctx, Glyph.TIMER).apply { setOnClickListener { cycleTimer() }; contentDescription = "Temporizador" }
        settingsBtn = GlyphButton(ctx, Glyph.SETTINGS).apply {
            setOnClickListener { startActivity(Intent(ctx, SettingsActivity::class.java)) }; contentDescription = "Configuracoes"
        }
        aspectChip = Theme.label(ctx, settings.aspect.label, 12f, Theme.WHITE, true).apply {
            setPadding(Theme.dpi(ctx, 10f), Theme.dpi(ctx, 4f), Theme.dpi(ctx, 10f), Theme.dpi(ctx, 4f))
            background = Theme.pill(0, Theme.dp(ctx, 8f), Theme.dpi(ctx, 1.2f), Theme.WHITE_DIM)
            setOnClickListener { cycleAspect() }
        }
        sceneChip = Theme.label(ctx, "", 11f, Color.BLACK, true).apply {
            setPadding(Theme.dpi(ctx, 8f), Theme.dpi(ctx, 3f), Theme.dpi(ctx, 8f), Theme.dpi(ctx, 3f))
            background = Theme.pill(Theme.ACCENT, Theme.dp(ctx, 6f)); visibility = View.GONE
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val s = Theme.dpi(ctx, 44f)
            addView(flashBtn, LinearLayout.LayoutParams(s, s))
            addView(timerBtn, LinearLayout.LayoutParams(s, s))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sceneChip)
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(aspectChip)
            addView(View(ctx), LinearLayout.LayoutParams(Theme.dpi(ctx, 8f), 1))
            addView(settingsBtn, LinearLayout.LayoutParams(s, s))
            setPadding(Theme.dpi(ctx, 10f), 0, Theme.dpi(ctx, 10f), 0)
        }
        root.addView(top, FrameLayout.LayoutParams(-1, Theme.dpi(ctx, 52f)).also { it.gravity = Gravity.TOP })

        lensLabel = Theme.label(ctx, "", 12f, Theme.WHITE, true).apply {
            setPadding(Theme.dpi(ctx, 10f), Theme.dpi(ctx, 3f), Theme.dpi(ctx, 10f), Theme.dpi(ctx, 3f))
            background = Theme.pill(Theme.SCRIM, Theme.dp(ctx, 10f)); alpha = 0f
        }
        root.addView(lensLabel, FrameLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP })
        zoomBar = ZoomBar(ctx)
        root.addView(zoomBar, FrameLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP })
        modeSelector = ModeSelector(ctx)
        root.addView(modeSelector, FrameLayout.LayoutParams(-1, Theme.dpi(ctx, 36f)).also { it.gravity = Gravity.TOP })

        shutter = ShutterButton(ctx).apply { setOnClickListener { onShutter() } }
        thumb = ThumbnailView(ctx).apply { setOnClickListener { openReview() } }
        flipBtn = GlyphButton(ctx, Glyph.FLIP).apply {
            fillColor = Theme.PANEL_2; glyphSize = Theme.dp(ctx, 26f)
            setOnClickListener { flip() }; contentDescription = "Alternar camera"
        }
        val bottom = FrameLayout(ctx)
        bottom.addView(thumb, FrameLayout.LayoutParams(Theme.dpi(ctx, 52f), Theme.dpi(ctx, 52f)).also { it.gravity = Gravity.CENTER_VERTICAL or Gravity.START; it.leftMargin = Theme.dpi(ctx, 34f) })
        bottom.addView(shutter, FrameLayout.LayoutParams(Theme.dpi(ctx, 78f), Theme.dpi(ctx, 78f)).also { it.gravity = Gravity.CENTER })
        bottom.addView(flipBtn, FrameLayout.LayoutParams(Theme.dpi(ctx, 52f), Theme.dpi(ctx, 52f)).also { it.gravity = Gravity.CENTER_VERTICAL or Gravity.END; it.rightMargin = Theme.dpi(ctx, 34f) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, Theme.dpi(ctx, 110f)).also { it.gravity = Gravity.BOTTOM })

        hint = Theme.label(ctx, "", 13f, Theme.WHITE, true).apply {
            setPadding(Theme.dpi(ctx, 14f), Theme.dpi(ctx, 8f), Theme.dpi(ctx, 14f), Theme.dpi(ctx, 8f))
            background = Theme.pill(Theme.SCRIM, Theme.dp(ctx, 14f)); alpha = 0f; gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER })

        screenFlash = View(ctx).apply { setBackgroundColor(Color.rgb(255, 250, 240)); visibility = View.GONE }
        root.addView(screenFlash, FrameLayout.LayoutParams(-1, -1))

        permissionView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            addView(Theme.label(ctx, "O CameraPremium precisa da camera para fotografar.", 16f).apply { gravity = Gravity.CENTER; setPadding(60, 0, 60, 40) })
            addView(Theme.button(ctx, "Permitir camera", primary = true) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) })
        }
        root.addView(permissionView, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = bars.top; insetBottom = bars.bottom
            root.post { layoutViews() }
            insets
        }

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                val ps = previewSize ?: return
                if (w == ps.width && hgt == ps.height) controller.setPreviewSurface(h)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; controller.setPreviewSurface(null) }
        })

        overlay.listener = object : ViewfinderOverlay.Listener {
            override fun onTapFocus(nx: Float, ny: Float) { controller.focusAt(nx, ny, lock = false); controller.setExposureEv(0f) }
            override fun onLongPressLock(nx: Float, ny: Float) { controller.focusAt(nx, ny, lock = true) }
            override fun onExposureChanged(ev: Float) { controller.setExposureEv(ev) }
            override fun onPinch(scale: Float) { pinch(scale) }
            override fun onPinchEnd() {}
        }
        zoomBar.listener = object : ZoomBar.Listener {
            override fun onStopSelected(stop: ZoomStop) { goToFactor(stop.factor, animate = true, hintStop = stop) }
            override fun onZoomDrag(factor: Float) { goToFactor(factor, animate = false, hintStop = null) }
        }
        modeSelector.onSelect = { i -> setMode(modes[i]) }
    }

    private fun layoutViews() {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val topBarH = Theme.dpi(this, 52f)
        val aspect = settings.aspect
        val ps = previewSize
        // preview in portrait: height/width = stream width/height
        val ratio = if (ps != null) ps.width.toFloat() / ps.height else if (aspect == AspectRatio.R16_9) 16f / 9f else 4f / 3f
        val ph = (w * ratio).toInt()
        val pTop = if (aspect == AspectRatio.R16_9) maxOf(insetTop, (h - insetBottom - ph) / 2).coerceAtMost(maxOf(0, h - ph)) else insetTop + topBarH
        (previewFrame.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = ph; topMargin = pTop; gravity = Gravity.TOP }
        previewFrame.requestLayout()
        (root.getChildAt(1).layoutParams as FrameLayout.LayoutParams).topMargin = insetTop
        val bottomBar = Theme.dpi(this, 110f)
        val bottomTop = h - insetBottom - bottomBar
        (root.getChildAt(root.indexOfChild(shutter.parent as View)).layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom
        val modeTop = bottomTop - Theme.dpi(this, 40f)
        (modeSelector.layoutParams as FrameLayout.LayoutParams).topMargin = modeTop
        val previewBottom = pTop + ph
        val zoomTop = minOf(previewBottom, modeTop) - Theme.dpi(this, 58f)
        (zoomBar.layoutParams as FrameLayout.LayoutParams).topMargin = zoomTop
        (lensLabel.layoutParams as FrameLayout.LayoutParams).topMargin = zoomTop - Theme.dpi(this, 30f)
        manualPanel?.let { (it.layoutParams as FrameLayout.LayoutParams).topMargin = zoomTop - Theme.dpi(this, 180f) }
        overlay.squareMask = aspect == AspectRatio.R1_1
        root.requestLayout()
    }

    private fun rotateIcons(deg: Float) {
        for (b in listOf(flashBtn, timerBtn, settingsBtn, flipBtn)) b.rotateTo(deg)
        aspectChip.animate().rotation(deg).setDuration(280).start()
        thumb.animate().rotation(deg).setDuration(280).start()
        zoomBar.textAngle = deg
    }

    private fun showHint(text: String, ms: Long = 1800) {
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
        hint.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    // ------------------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        if (crashedBefore) return
        orientationListener.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionView.visibility = View.VISIBLE
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        permissionView.visibility = View.GONE
        startCamera()
        app.store.latest()?.let { r -> lastRecordId = r.id }
        refreshTopBar()
    }

    override fun onPause() {
        super.onPause()
        if (crashedBefore) return
        orientationListener.disable()
        controller.close()
        if (countdownRunning) countdownRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (crashedBefore) return
        controller.release()
        sound.release()
        if (app.processor.listener === this) app.processor.listener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionView.visibility = View.GONE
            startCamera()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCamera() {
        app.ensureInventory()
        app.processor.listener = this
        val target = if (settings.useFront) inventory.front ?: inventory.rearLogical else inventory.rearLogical ?: inventory.front
        if (target == null) { showHint("Nenhuma camera disponivel", 4000); return }
        val stops = stopsFor(target)
        zoomBar.stops = stops
        zoomBar.minFactor = stops.firstOrNull()?.factor ?: 1f
        zoomBar.maxFactor = if (target.front) (target.zoomRange?.upper ?: 1f) else inventory.rearMaxFactor()
        factor = 1f; zoomBar.factor = 1f
        refreshModes(target)
        open(target, ratio = stops.firstOrNull { abs(it.factor - 1f) < 0.01f }?.ratioOnCamera ?: 1f)
        setMode(mode)
    }

    /** Portrait appears only when this camera offers the public camera2 bokeh mode. */
    private fun refreshModes(l: LensInfo) {
        modes = if (l.supportsBokeh) listOf(CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.NIGHT, CaptureMode.PRO)
        else listOf(CaptureMode.PHOTO, CaptureMode.NIGHT, CaptureMode.PRO)
        if (mode !in modes) mode = CaptureMode.PHOTO
        modeSelector.modes = modes.map { it.label }
        modeSelector.selected = modes.indexOf(mode).coerceAtLeast(0)
    }

    private fun stopsFor(l: LensInfo): List<ZoomStop> = if (l.front) inventory.frontStops else inventory.rearStops

    private var pendingRatio = 1f

    private fun open(l: LensInfo, ratio: Float) {
        lens = l
        pendingRatio = ratio
        controller.open(l.id, settings.aspect, settings.highResolution, settings.raw)
        controller.setFlash(settings.flash)
    }

    // ------------------------------------------------------------------------------ zoom

    private class Route(val cameraId: String, val ratio: Float)

    private fun route(f: Float, hintStop: ZoomStop?): Route {
        val l = lens!!
        val stops = stopsFor(l)
        if (settings.lensSelection == LensSelection.PHYSICAL_PREFERRED && hintStop != null && hintStop.native && !l.front) {
            val phys = inventory.lenses.values.firstOrNull {
                it.parentLogicalId == null && !it.front && it.equivalentFocal > 0f &&
                    abs(it.equivalentFocal / inventory.mainEquivalentFocal - f) / f < 0.08f
            }
            if (phys != null) return Route(phys.id, 1f)
        }
        val s = stops.lastOrNull { it.factor <= f * 1.001f } ?: stops.firstOrNull() ?: return Route(l.id, f)
        val lensFactor = s.factor / s.ratioOnCamera
        return Route(s.cameraId, f / lensFactor)
    }

    private fun goToFactor(f: Float, animate: Boolean, hintStop: ZoomStop?) {
        val l = lens ?: return
        val target = f.coerceIn(zoomBar.minFactor, zoomBar.maxFactor)
        val r = route(target, hintStop)
        if (r.cameraId != l.id) {
            val next = inventory.lenses[r.cameraId] ?: return
            factor = target; zoomBar.factor = target
            switchTo(next, r.ratio)
            return
        }
        zoomAnim?.cancel()
        if (!animate) {
            factor = target; zoomBar.factor = target
            controller.setZoom(r.ratio)
            return
        }
        val from = controller.currentZoom()
        val fromFactor = factor
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                controller.setZoom(ZoomBar.lerpLog(from.coerceAtLeast(0.1f), r.ratio, t))
                factor = ZoomBar.lerpLog(fromFactor.coerceAtLeast(0.1f), target, t)
                zoomBar.factor = factor
            }
            start()
        }
    }

    private fun pinch(scale: Float) {
        if (lens == null) return
        val now = System.currentTimeMillis()
        val f = (factor * scale).coerceIn(zoomBar.minFactor, zoomBar.maxFactor)
        val r = route(f, null)
        if (r.cameraId != lens?.id && now - lastSwitchAt < 600) return
        goToFactor(f, animate = false, hintStop = null)
    }

    /** Lens or camera switch with a frozen, blurred frame to hide the reconfiguration. */
    private fun switchTo(next: LensInfo, ratio: Float) {
        lastSwitchAt = System.currentTimeMillis()
        freezeFrame()
        open(next, ratio)
    }

    private fun freezeFrame() {
        if (previewSize == null || !surfaceReady || surface.width == 0) return
        val bmp = Bitmap.createBitmap(surface.width / 2, surface.height / 2, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    cover.setImageBitmap(bmp)
                    cover.setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP))
                    cover.alpha = 1f
                    cover.visibility = View.VISIBLE
                }
            }, main)
        } catch (_: Exception) {}
    }

    private fun unfreeze() {
        if (cover.visibility != View.VISIBLE) return
        cover.animate().alpha(0f).setStartDelay(220).setDuration(260).withEndAction { cover.visibility = View.GONE }.start()
    }

    private fun flip() {
        val cur = lens ?: return
        val next = if (cur.front) inventory.rearLogical else inventory.front
        if (next == null) return
        settings.useFront = next.front
        flipBtn.animate().rotationBy(180f).setDuration(300).start()
        val stops = stopsFor(next)
        zoomBar.stops = stops
        zoomBar.minFactor = stops.firstOrNull()?.factor ?: 1f
        zoomBar.maxFactor = if (next.front) (next.zoomRange?.upper ?: 1f) else inventory.rearMaxFactor()
        factor = 1f; zoomBar.factor = 1f
        overlay.clearFocusRing()
        switchTo(next, stops.firstOrNull { abs(it.factor - 1f) < 0.01f }?.ratioOnCamera ?: 1f)
        refreshModes(next)
        setMode(mode)
        refreshTopBar()
    }

    // ------------------------------------------------------------------------------ top bar actions

    private fun refreshTopBar() {
        val front = lens?.front == true
        flashBtn.glyph = when (settings.flash) { FlashMode.AUTO -> Glyph.FLASH_AUTO; FlashMode.ON -> Glyph.FLASH_ON; FlashMode.OFF -> Glyph.FLASH_OFF }
        flashBtn.color = if (settings.flash != FlashMode.OFF) Theme.ACCENT else Theme.WHITE
        flashBtn.visibility = if (front && !settings.screenFlash) View.INVISIBLE else View.VISIBLE
        timerBtn.badge = if (settings.timerSeconds > 0) settings.timerSeconds.toString() else null
        timerBtn.color = if (settings.timerSeconds > 0) Theme.ACCENT else Theme.WHITE
        aspectChip.text = settings.aspect.label
        overlay.grid = settings.grid
        flashBtn.invalidate()
    }

    private fun cycleFlash() {
        val front = lens?.front == true
        settings.flash = if (front) (if (settings.flash == FlashMode.OFF) FlashMode.ON else FlashMode.OFF) else when (settings.flash) {
            FlashMode.AUTO -> FlashMode.ON; FlashMode.ON -> FlashMode.OFF; FlashMode.OFF -> FlashMode.AUTO
        }
        controller.setFlash(settings.flash)
        showHint(when (settings.flash) { FlashMode.AUTO -> "Flash automatico"; FlashMode.ON -> if (front) "Flash de tela" else "Flash ligado"; FlashMode.OFF -> "Flash desligado" }, 900)
        refreshTopBar()
    }

    private fun cycleTimer() {
        settings.timerSeconds = when (settings.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
        showHint(if (settings.timerSeconds == 0) "Temporizador desligado" else "Temporizador ${settings.timerSeconds}s", 900)
        refreshTopBar()
    }

    private fun cycleAspect() {
        settings.aspect = when (settings.aspect) { AspectRatio.R4_3 -> AspectRatio.R16_9; AspectRatio.R16_9 -> AspectRatio.R1_1; AspectRatio.R1_1 -> AspectRatio.R4_3 }
        refreshTopBar()
        val l = lens ?: return
        freezeFrame()
        open(l, controller.currentZoom())
    }

    private fun setMode(m: CaptureMode) {
        mode = m
        settings.lastMode = m
        shutter.night = m == CaptureMode.NIGHT
        manualPanel?.let { root.removeView(it) }
        manualPanel = null
        controller.setManual(null)
        controller.setPortrait(m == CaptureMode.PORTRAIT)
        val l = lens
        if (m == CaptureMode.PRO && l != null) {
            val panel = ManualPanel(this, l) { s -> controller.setManual(s) }
            manualPanel = panel
            root.addView(panel, FrameLayout.LayoutParams(-1, -2).also {
                it.gravity = Gravity.TOP; it.leftMargin = Theme.dpi(this, 12f); it.rightMargin = Theme.dpi(this, 12f)
            })
            root.post { layoutViews() }
        }
        if (m == CaptureMode.NIGHT) showHint("Noite: varios quadros sao fundidos. Mantenha firme.", 2200)
    }

    // ------------------------------------------------------------------------------ capture

    private fun onShutter() {
        if (countdownRunning) { countdownRunning = false; overlay.countdown = 0; return }
        val t = settings.timerSeconds
        if (t > 0) { countdown(t); return }
        captureNow()
    }

    private fun countdown(sec: Int) {
        countdownRunning = true
        var left = sec
        val tick = object : Runnable {
            override fun run() {
                if (!countdownRunning) { overlay.countdown = 0; return }
                if (left == 0) { overlay.countdown = 0; countdownRunning = false; captureNow(); return }
                overlay.countdown = left
                left--
                main.postDelayed(this, 1000)
            }
        }
        main.post(tick)
    }

    private fun captureNow() {
        val l = lens ?: return
        val spec = CaptureSpec(
            deviceOrientation = deviceOrientation,
            mirrorFront = settings.mirrorFront,
            mode = mode,
            options = JobOptions(
                process = settings.processing == ProcessingChoice.NATURAL_PREMIUM,
                profileId = settings.profileId,
                ultraHdr = settings.ultraHdr,
                displayP3 = settings.displayP3,
                saveOriginalToGallery = settings.saveOriginalToGallery,
                jpegQuality = settings.jpegQuality,
                raw = settings.raw,
            ),
            multiFrame = settings.multiFrame,
        )
        if (l.front && settings.flash != FlashMode.OFF && settings.screenFlash) {
            screenFlash.alpha = 1f; screenFlash.visibility = View.VISIBLE
            val lp = window.attributes; lp.screenBrightness = 1f; window.attributes = lp
            main.postDelayed({ controller.capture(spec) }, 420)
        } else controller.capture(spec)
    }

    private fun endScreenFlash() {
        if (screenFlash.visibility != View.VISIBLE) return
        screenFlash.animate().alpha(0f).setDuration(200).withEndAction { screenFlash.visibility = View.GONE }.start()
        val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp
    }

    private fun openReview() {
        val id = lastRecordId ?: return
        startActivity(Intent(this, ReviewActivity::class.java).putExtra(ReviewActivity.EXTRA_ID, id))
    }

    // ------------------------------------------------------------------------------ controller callbacks

    override fun onPreviewSize(lens: LensInfo, size: Size) {
        previewSize = size
        surface.holder.setFixedSize(size.width, size.height)
        layoutViews()
        val frame = surface.holder.surfaceFrame
        if (surfaceReady && frame.width() == size.width && frame.height() == size.height) controller.setPreviewSurface(surface.holder)
        overlay.setExposureRange(lens.aeCompRange?.let { it.upper * lens.aeCompStep } ?: 2f)
    }

    override fun onOpened(lens: LensInfo, sessionInfo: String) {
        controller.setZoom(pendingRatio)
        controller.setFlash(settings.flash)
        unfreeze()
        flashBtn.visibility = if (lens.flashAvailable || (lens.front && settings.screenFlash)) View.VISIBLE else View.INVISIBLE
        showLensLabel(inventory.labelFor(lens))
        if (settings.showPerf) showHint(sessionInfo, 3000)
    }

    override fun onError(message: String) { unfreeze(); showHint(message, 3500) }

    override fun onActiveLens(label: String) { showLensLabel(label) }

    private fun showLensLabel(label: String) {
        if (label.isEmpty()) return
        lensLabel.text = label
        lensLabel.animate().cancel()
        lensLabel.alpha = 1f
        lensLabel.animate().alpha(0f).setStartDelay(1400).setDuration(400).start()
    }

    override fun onFocusLocked(success: Boolean) {}

    override fun onCaptureStarted(frames: Int, estimatedMs: Long) {
        if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        overlay.captureBlink()
        shutter.progress = if (frames > 1) 0f else -1f
        if (estimatedMs > 700) showHint("Mantenha firme…", estimatedMs)
    }

    override fun onFrameProgress(done: Int, total: Int) { if (total > 1) shutter.progress = done.toFloat() / total }

    override fun onCaptureDone() {
        shutter.progress = -1f
        endScreenFlash()
        shutter.busy = !app.processor.canAcceptCapture()
    }

    override fun onBusy() { showHint("Processando a foto anterior…", 1000) }

    override fun onSceneHint(hint: SceneHint, ev100: Float) {
        val text = when {
            mode == CaptureMode.PRO -> null
            !ev100.isNaN() && ev100 < 3f -> "NOITE"
            hint.clippedFraction > 0.002f || hint.p995 >= 250 -> "HDR"
            else -> null
        }
        if (text == null) sceneChip.visibility = View.GONE else { sceneChip.text = text; sceneChip.visibility = View.VISIBLE }
    }

    // ------------------------------------------------------------------------------ processor callbacks

    override fun onSaved(record: CaptureRecord, thumbnail: Bitmap) {
        lastRecordId = record.id
        thumb.setBitmap(thumbnail)
        if (settings.showPerf) showHint(record.timings, 4000)
    }

    override fun onFailed(message: String) { showHint(message, 3500) }

    override fun onQueueChanged(pending: Int) {
        thumb.processing = pending
        shutter.busy = !app.processor.canAcceptCapture()
    }

    companion object {
        const val REQ_CAMERA = 10
    }
}
