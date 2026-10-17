package com.camerapremium.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.camerapremium.app.processing.CaptureJob
import com.camerapremium.app.processing.FrameRole
import com.camerapremium.app.processing.JobOptions
import com.camerapremium.app.processing.PhotoProcessor
import com.camerapremium.app.settings.AspectRatio
import com.camerapremium.app.settings.CaptureMode
import com.camerapremium.app.settings.FlashMode
import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.pipeline.FaceRegion
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/** Manual controls for PRO mode. Null fields stay automatic. */
class ManualSettings(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    /** Diopters (1/m); 0 = infinity. */
    val focusDistance: Float? = null,
    val awbMode: Int? = null,
)

/** What the UI asks for when the shutter is pressed. */
class CaptureSpec(
    val deviceOrientation: Int,
    val mirrorFront: Boolean,
    val mode: CaptureMode,
    val options: JobOptions,
    val multiFrame: Boolean,
    val nativeSource: Boolean = true,
    val hybridSource: Boolean = false,
)

/**
 * Camera2 controller. All camera state lives on its own handler thread; public methods are
 * safe to call from the UI thread. Session creation walks a fallback chain of stream
 * combinations so the app works on any device, from LEVEL_3 down to LIMITED.
 */
class CameraController(
    private val ctx: Context,
    val inventory: CameraInventory,
    private val processor: PhotoProcessor,
) {
    interface Listener {
        fun onPreviewSize(lens: LensInfo, size: Size)
        fun onOpened(lens: LensInfo, sessionInfo: String)
        fun onError(message: String)
        fun onActiveLens(label: String)
        fun onFocusLocked(success: Boolean)
        fun onCaptureStarted(frames: Int, estimatedMs: Long)
        fun onFrameProgress(done: Int, total: Int)
        fun onCaptureDone()
        fun onBusy()
        fun onSceneHint(hint: SceneHint, ev100: Float)
    }

    var listener: Listener? = null
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("camera").apply { start() }
    private val cam = Handler(camThread.looper)
    private val imgThread = HandlerThread("camera-images").apply { start() }
    private val img = Handler(imgThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val camExecutor = Executor { cam.post(it) }

    // ---- state (camera thread only)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    var lens: LensInfo? = null
        private set
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var aspect = AspectRatio.R4_3
    private var highRes = false
    private var rawWanted = false
    private var yuvReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var highResSession = false
    private var previewBuilder: CaptureRequest.Builder? = null
    private var zoomRatio = 1f
    private var evIndex = 0
    private var flash = FlashMode.OFF
    private var aeAfLocked = false
    private var meteringRegion: MeteringRectangle? = null
    private var tapFocusAt = 0L
    private var manual: ManualSettings? = null
    private var portrait = false
    @Volatile var lastResult: TotalCaptureResult? = null
        private set
    @Volatile var sceneHint = SceneHint()
        private set
    private var lastPhysical: String? = null
    private var analysisCount = 0
    private var openToken = 0
    private var configuring = false
    /** Camera2 extension (manufacturer multi-frame pipeline) wanted for the next session. */
    private var extensionWanted: Int? = null
    private var extSession: CameraExtensionSession? = null
    private var extKeys: Set<CaptureRequest.Key<*>> = emptySet()
    @Volatile var activeExtension: Int? = null
        private set

    // ---- capture state machine
    private enum class St { IDLE, WAIT_AF, WAIT_PRE, WAIT_NON_PRE, CAPTURING }
    private var st = St.IDLE
    private var stSince = 0L
    private var pendingSpec: CaptureSpec? = null
    private var flashWillFire = false
    @Volatile private var job: CaptureJob? = null
    private var pendingRaw: Image? = null

    // ------------------------------------------------------------------------------ public API

    @SuppressLint("MissingPermission")
    fun open(lensId: String, aspect: AspectRatio, highRes: Boolean, raw: Boolean, extension: Int? = null) = cam.post {
        closeInternal()
        val l = inventory.lenses[lensId] ?: run { post { listener?.onError("Camera $lensId indisponivel") }; return@post }
        lens = l; this.aspect = aspect; this.highRes = highRes; this.rawWanted = raw
        extensionWanted = extension?.takeIf { it in l.extensions }
        // the SurfaceView is resized for the new stream: wait for the UI to hand it back
        previewSurface = null
        zoomRatio = 1f; evIndex = 0; aeAfLocked = false; meteringRegion = null; manual = null
        val ps = extensionWanted?.let { extensionPreviewSize(l, it, aspect) } ?: choosePreviewSize(l, aspect)
        previewSize = ps
        val token = ++openToken
        post { listener?.onPreviewSize(l, ps) }
        try {
            manager.openCamera(lensId, camExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (token != openToken) { d.close(); return }
                    device = d
                    tryCreateSession()
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device === d) device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device === d) device = null
                    post { listener?.onError("Erro da camera ($error)") }
                }
            })
        } catch (e: Exception) {
            post { listener?.onError("Nao foi possivel abrir a camera: ${e.message}") }
        }
    }

    /** Surface of the SurfaceView once it has the preview size. */
    fun setPreviewSurface(holder: SurfaceHolder?) = cam.post {
        previewSurface = holder?.surface
        if (previewSurface != null && device != null && session == null && !configuring) tryCreateSession()
    }

    fun close() = cam.post { closeInternal() }

    fun release() {
        cam.post { closeInternal(); camThread.quitSafely(); imgThread.quitSafely() }
    }

    fun setZoom(ratio: Float) = cam.post {
        val l = lens ?: return@post
        val r = l.zoomRange
        zoomRatio = if (r != null) ratio.coerceIn(r.lower, r.upper) else ratio.coerceIn(1f, l.maxDigitalZoom)
        updatePreview()
    }

    fun currentZoom(): Float = zoomRatio

    fun setExposureEv(ev: Float) = cam.post {
        val l = lens ?: return@post
        val range = l.aeCompRange ?: return@post
        if (l.aeCompStep <= 0f) return@post
        evIndex = (ev / l.aeCompStep).roundToInt().coerceIn(range.lower, range.upper)
        updatePreview()
    }

    fun setFlash(mode: FlashMode) = cam.post { flash = mode; updatePreview() }

    fun setManual(m: ManualSettings?) = cam.post { manual = m; updatePreview() }

    /** Portrait = camera2 extended scene mode BOKEH_STILL_CAPTURE (only when the HAL offers it). */
    fun setPortrait(on: Boolean) = cam.post { portrait = on; updatePreview() }

    /** Tap to focus/meter at a point of the preview (normalized view coordinates). */
    fun focusAt(nx: Float, ny: Float, lock: Boolean) = cam.post {
        val l = lens ?: return@post
        val b = previewBuilder ?: return@post
        val s = session ?: return@post
        if (st != St.IDLE) return@post
        meteringRegion = regionFor(l, nx, ny)
        aeAfLocked = lock
        tapFocusAt = SystemClock.uptimeMillis()
        applyCommon(b, preview = true)
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, cam)
            if (l.hasAutoFocus) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), previewCallback, cam)
            }
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), previewCallback, cam)
        } catch (e: Exception) { Log.w(TAG, "focus", e) }
    }

    /** Back to continuous AF/AE over the whole frame. */
    fun resetFocus() = cam.post {
        meteringRegion = null; aeAfLocked = false
        val b = previewBuilder ?: return@post
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session?.capture(b.build(), null, cam)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        } catch (_: Exception) {}
        updatePreview()
    }

    fun capture(spec: CaptureSpec) = cam.post {
        if (st != St.IDLE || (session == null && extSession == null) || job != null) { post { listener?.onBusy() }; return@post }
        if (!processor.canAcceptCapture()) { post { listener?.onBusy() }; return@post }
        if (extSession != null) { captureWithExtension(spec); return@post }
        pendingSpec = spec
        val l = lens ?: return@post
        val r = lastResult
        val afMode = r?.get(CaptureResult.CONTROL_AF_MODE)
        val afState = r?.get(CaptureResult.CONTROL_AF_STATE)
        val aeState = r?.get(CaptureResult.CONTROL_AE_STATE)
        flashWillFire = l.flashAvailable && !l.front && manual == null &&
            (flash == FlashMode.ON || (flash == FlashMode.AUTO && aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED))
        // Lock AF even when already in focus, so every burst frame shares the same focus position
        // (locking from PASSIVE_FOCUSED is immediate).
        val locked = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        if (l.hasAutoFocus && !aeAfLocked && manual?.focusDistance == null &&
            afMode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE && !locked) {
            trigger(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            setState(St.WAIT_AF)
        } else afterFocus()
    }

    // ------------------------------------------------------------------------------ session

    private fun choosePreviewSize(l: LensInfo, a: AspectRatio): Size {
        val target = if (a == AspectRatio.R1_1) AspectRatio.R4_3.value else a.value
        val sizes = l.streamMap?.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()
        val ok = sizes.filter { abs(it.width.toFloat() / it.height - target) < 0.02f && it.width <= 1920 && it.height <= 1440 }
        return ok.maxByOrNull { it.width * it.height } ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
    }

    private fun bestSize(list: List<Size>, target: Float, maxPixels: Long = Long.MAX_VALUE): Size? =
        list.filter { abs(it.width.toFloat() / it.height - target) < 0.02f && it.width.toLong() * it.height <= maxPixels }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: list.filter { it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width.toLong() * it.height }

    private class Attempt(val name: String, val yuv: Size?, val jpeg: Size?, val analysis: Size?, val raw: Size?, val maxRes: Boolean)

    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0

    private fun tryCreateSession() {
        val l = lens ?: return
        if (previewSurface == null || device == null || configuring) return
        configuring = true
        val ext = extensionWanted
        if (ext != null && Build.VERSION.SDK_INT >= 31) { createExtensionSession(l, ext); return }
        val target = if (aspect == AspectRatio.R1_1) AspectRatio.R4_3.value else aspect.value
        val yuv = bestSize(l.yuvSizes, target, 16_800_000L)
        val jpeg = bestSize(l.jpegSizes, target)
        val analysis = l.yuvSizes.filter { it.width <= 640 && abs(it.width.toFloat() / it.height - target) < 0.05f }.maxByOrNull { it.width * it.height }
            ?: l.yuvSizes.filter { it.width <= 800 }.maxByOrNull { it.width * it.height }
        val raw = if (rawWanted && l.rawCapable) l.rawSizes.first() else null
        val list = ArrayList<Attempt>()
        if (highRes && Build.VERSION.SDK_INT >= 31 && l.ultraHighResolution && l.maxResJpegSizes.isNotEmpty()) {
            list.add(Attempt("alta resolucao (pixel mode maximo)", null, l.maxResJpegSizes.first(), null, null, true))
        }
        if (raw != null) list.add(Attempt("preview+analise+YUV+JPEG+RAW", yuv, jpeg, analysis, raw, false))
        list.add(Attempt("preview+analise+YUV+JPEG", yuv, jpeg, analysis, null, false))
        if (raw != null) list.add(Attempt("preview+analise+YUV+RAW", yuv, null, analysis, raw, false))
        list.add(Attempt("preview+analise+YUV", yuv, null, analysis, null, false))
        list.add(Attempt("preview+YUV", yuv, null, null, null, false))
        list.add(Attempt("preview+JPEG (somente sistema)", null, jpeg, null, null, false))
        attempts = list
        attemptIndex = 0
        createNext()
    }

    private fun createNext() {
        val d = device ?: run { configuring = false; return }
        val surface = previewSurface ?: run { configuring = false; return }
        closeReaders()
        if (attemptIndex >= attempts.size) {
            configuring = false
            post { listener?.onError("Nenhuma combinacao de fluxos foi aceita pela camera") }; return
        }
        val a = attempts[attemptIndex++]
        val outputs = ArrayList<OutputConfiguration>()
        outputs.add(OutputConfiguration(surface))
        if (a.analysis != null) {
            analysisReader = ImageReader.newInstance(a.analysis.width, a.analysis.height, ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ r -> onAnalysisImage(r) }, img)
                outputs.add(OutputConfiguration(it.surface))
            }
        }
        if (a.yuv != null) {
            yuvReader = ImageReader.newInstance(a.yuv.width, a.yuv.height, ImageFormat.YUV_420_888, 6).also {
                it.setOnImageAvailableListener({ r -> onYuvImage(r) }, img)
                outputs.add(OutputConfiguration(it.surface))
            }
        }
        if (a.jpeg != null) {
            jpegReader = ImageReader.newInstance(a.jpeg.width, a.jpeg.height, ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener({ r -> onJpegImage(r) }, img)
                val oc = OutputConfiguration(it.surface)
                if (a.maxRes && Build.VERSION.SDK_INT >= 31) oc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                outputs.add(oc)
            }
        }
        if (a.raw != null) {
            rawReader = ImageReader.newInstance(a.raw.width, a.raw.height, ImageFormat.RAW_SENSOR, 2).also {
                it.setOnImageAvailableListener({ r -> onRawImage(r) }, img)
                outputs.add(OutputConfiguration(it.surface))
            }
        }
        highResSession = a.maxRes
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, camExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                configuring = false
                if (device !== d) { s.close(); return }
                session = s
                startPreview()
                val info = a.name + " | YUV=${a.yuv} JPEG=${a.jpeg} RAW=${a.raw} preview=$previewSize"
                Log.i(TAG, "session: $info")
                val l = lens
                if (l != null) post { listener?.onOpened(l, info) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "session failed: ${a.name}")
                s.close()
                cam.post { createNext() }
            }
        })
        try {
            // Ask first when the platform can answer: avoids a slow failed configuration.
            if (Build.VERSION.SDK_INT >= 29) {
                val supported = try { d.isSessionConfigurationSupported(cfg) } catch (_: Exception) { true }
                if (!supported) { Log.i(TAG, "nao suportado: ${a.name}"); createNext(); return }
            }
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession ${a.name}", e)
            createNext()
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            analysisReader?.let { b.addTarget(it.surface) }
            previewBuilder = b
            applyCommon(b, preview = true)
            s.setRepeatingRequest(b.build(), previewCallback, cam)
        } catch (e: Exception) { post { listener?.onError("Falha na pre-visualizacao: ${e.message}") } }
    }

    private fun updatePreview() {
        if (extSession != null) { startExtensionPreview(); return }
        val b = previewBuilder ?: return
        val s = session ?: return
        if (st != St.IDLE) return
        applyCommon(b, preview = true)
        try { s.setRepeatingRequest(b.build(), previewCallback, cam) } catch (e: Exception) { Log.w(TAG, "update", e) }
    }

    /** Settings shared by preview and still requests. */
    private fun applyCommon(b: CaptureRequest.Builder, preview: Boolean) {
        val l = lens ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val bokeh = portrait && l.supportsBokeh && Build.VERSION.SDK_INT >= 30
        if (Build.VERSION.SDK_INT >= 30 && l.extendedSceneModes.isNotEmpty()) {
            b.set(CaptureRequest.CONTROL_EXTENDED_SCENE_MODE,
                if (bokeh) CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE else CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_DISABLED)
        }
        val zr = if (bokeh) l.bokehZoomRange else null
        if (l.zoomRange != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (zr != null) zoomRatio.coerceIn(zr.lower, zr.upper) else zoomRatio)
        else b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(l.activeArray, zoomRatio))
        val m = manual
        // focus
        if (m?.focusDistance != null && CameraMetadata.CONTROL_AF_MODE_OFF in l.afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDistance)
        } else if (meteringRegion != null && CameraMetadata.CONTROL_AF_MODE_AUTO in l.afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in l.afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        val region = meteringRegion
        if (region != null) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        // exposure
        if (m != null && (m.iso != null || m.exposureNs != null) && l.manualSensor) {
            val r = lastResult
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val t = m.exposureNs ?: r?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_000_000L
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso ?: r?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(t, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, aeModeFor(l))
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked)
            if (flash == FlashMode.OFF || l.front) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        // white balance
        val awb = m?.awbMode
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (awb != null && awb in l.awbModes) awb else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // faces: drive exposure/skin handling
        val face = when {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in l.faceModes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in l.faceModes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        }
        b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, face)
        if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in l.oisModes) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) // EIS crops stills
        if (CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in l.distortionModes) {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        }
        if (preview) {
            if (CameraMetadata.NOISE_REDUCTION_MODE_FAST in l.nrModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            if (CameraMetadata.EDGE_MODE_FAST in l.edgeModes) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        }
    }

    private fun aeModeFor(l: LensInfo): Int = when {
        !l.flashAvailable || l.front -> CameraMetadata.CONTROL_AE_MODE_ON
        flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
        flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        else -> CameraMetadata.CONTROL_AE_MODE_ON
    }

    private fun cropFor(active: Rect, ratio: Float): Rect {
        val w = (active.width() / ratio).toInt(); val h = (active.height() / ratio).toInt()
        val x = active.left + (active.width() - w) / 2; val y = active.top + (active.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    /**
     * View point (portrait, as displayed) -> metering rectangle in sensor coordinates.
     * Accounts for sensor orientation, front mirroring and the preview's aspect crop. With
     * CONTROL_ZOOM_RATIO the region coordinates are relative to the zoomed field of view.
     */
    private fun regionFor(l: LensInfo, nx: Float, ny: Float): MeteringRectangle {
        var dx = nx; val dy = ny
        if (l.front) dx = 1f - dx
        val sx: Float; val sy: Float
        when (l.sensorOrientation) {
            90 -> { sx = dy; sy = 1f - dx }
            180 -> { sx = 1f - dx; sy = 1f - dy }
            270 -> { sx = 1f - dy; sy = dx }
            else -> { sx = dx; sy = dy }
        }
        val a = l.activeArray
        var fx = sx; var fy = sy
        val ps = previewSize
        if (ps != null) {
            val activeAspect = a.width().toFloat() / a.height()
            val streamAspect = ps.width.toFloat() / ps.height
            if (streamAspect > activeAspect + 0.01f) { val s = activeAspect / streamAspect; fy = (1 - s) / 2 + sy * s }
            else if (streamAspect < activeAspect - 0.01f) { val s = streamAspect / activeAspect; fx = (1 - s) / 2 + sx * s }
        }
        val baseW = if (l.zoomRange != null) a.width() else (a.width() / zoomRatio).toInt()
        val baseH = if (l.zoomRange != null) a.height() else (a.height() / zoomRatio).toInt()
        val ox = if (l.zoomRange != null) 0 else (a.width() - baseW) / 2
        val oy = if (l.zoomRange != null) 0 else (a.height() - baseH) / 2
        val cx = (ox + fx * baseW).toInt(); val cy = (oy + fy * baseH).toInt()
        val half = (minOf(a.width(), a.height()) * 0.08f).toInt()
        val left = (cx - half).coerceIn(0, a.width() - 2 * half)
        val top = (cy - half).coerceIn(0, a.height() - 2 * half)
        return MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    // ------------------------------------------------------------------------------ preview results

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            if (Build.VERSION.SDK_INT >= 29) {
                val pid = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                if (pid != null && pid != lastPhysical) {
                    lastPhysical = pid
                    val label = inventory.labelFor(inventory.lenses[pid])
                    post { listener?.onActiveLens(label) }
                }
            }
            // tap-to-focus returns to continuous after a while (unless the user locked it)
            if (meteringRegion != null && !aeAfLocked && st == St.IDLE && SystemClock.uptimeMillis() - tapFocusAt > 8000) {
                meteringRegion = null
                updatePreview()
            }
            stepStateMachine(result)
        }
    }

    private fun setState(s: St) { st = s; stSince = SystemClock.uptimeMillis() }

    private fun stepStateMachine(r: TotalCaptureResult) {
        val elapsed = SystemClock.uptimeMillis() - stSince
        when (st) {
            St.WAIT_AF -> {
                val af = r.get(CaptureResult.CONTROL_AF_STATE)
                if (af == null || af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || elapsed > 1000) {
                    post { listener?.onFocusLocked(af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED) }
                    afterFocus()
                }
            }
            St.WAIT_PRE -> {
                val ae = r.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null || ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE || ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED || elapsed > 1000) setState(St.WAIT_NON_PRE)
            }
            St.WAIT_NON_PRE -> {
                val ae = r.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null || ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE || elapsed > 1500) doCapture()
            }
            else -> {}
        }
    }

    private fun afterFocus() {
        if (flashWillFire) {
            trigger(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            setState(St.WAIT_PRE)
        } else doCapture()
    }

    private fun <T> trigger(key: CaptureRequest.Key<T>, value: T) {
        val b = previewBuilder ?: return
        try {
            b.set(key, value)
            session?.capture(b.build(), previewCallback, cam)
            b.set(key, null)
            if (key == CaptureRequest.CONTROL_AF_TRIGGER) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        } catch (e: Exception) { Log.w(TAG, "trigger", e) }
    }

    // ------------------------------------------------------------------------------ capture

    private fun doCapture() {
        setState(St.CAPTURING)
        val spec = pendingSpec ?: run { setState(St.IDLE); return }
        val d = device ?: run { setState(St.IDLE); return }
        val s = session ?: run { setState(St.IDLE); return }
        val l = lens ?: run { setState(St.IDLE); return }
        val r = lastResult
        val aeT = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val aeIso = r?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val yuv = yuvReader
        val jpeg = jpegReader
        val m = manual

        // ---- plan
        val plan: BurstPlan = when {
            portrait && l.supportsBokeh && jpeg != null -> BurstPlan(0, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, false, "retrato (bokeh do sistema)")
            highResSession || yuv == null -> BurstPlan(0, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, false, "somente JPEG do sistema")
            flashWillFire -> BurstPlan(1, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, false, "flash: quadro unico")
            m != null && (m.iso != null || m.exposureNs != null) -> {
                val t = m.exposureNs ?: aeT
                BurstPlan(if (t > 250_000_000L) 1 else 3, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_FAST, false, "manual")
            }
            else -> CapturePlanner.plan(l, spec.mode, aeT, aeIso, sceneHint, spec.multiFrame, spec.nativeSource, thermalThrottle(), spec.hybridSource)
        }
        val useSrgbCurve = plan.frames > 0 && !flashWillFire && (l.supportsSrgbTonemap || l.supportsContrastCurve) && plan.exposureNs != null

        // ---- geometry: rotation to upright, mirror and aspect crop
        val rotation = if (l.front) (l.sensorOrientation - spec.deviceOrientation + 360) % 360 else (l.sensorOrientation + spec.deviceOrientation) % 360
        val mirror = l.front && spec.mirrorFront
        val yuvCrop = yuv?.let { aspectCrop(it.width, it.height) }
        val jpegCrop = jpeg?.let { aspectCrop(it.width, it.height) }
        val faces = facesFrom(r, l, yuv?.width ?: jpeg?.width ?: 4, yuv?.height ?: jpeg?.height ?: 3, yuvCrop)
        val physical = r?.let { if (Build.VERSION.SDK_INT >= 29) it.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null }
        val label = if (physical != null) inventory.labelFor(inventory.lenses[physical]) else inventory.labelFor(l)
        val flashCombined = flashWillFire && yuv != null && jpeg != null
        val newJob = CaptureJob(
            id = System.currentTimeMillis(), lens = l,
            options = spec.options,
            rotation = rotation, mirror = mirror, crop = yuvCrop, jpegCrop = jpegCrop, plan = plan,
            srgbCurve = useSrgbCurve, faces = faces, mode = spec.mode, originalFromJpeg = jpeg != null,
            expectRaw = rawReader != null, lensLabel = label, flashCombined = flashCombined,
        )
        job = newJob
        processor.begin(newJob)

        try {
            val requests = ArrayList<CaptureRequest>()
            // 1) the original: what the camera system itself produces (also the comparison reference)
            val orig = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyCommon(orig, preview = false)
            orig.set(CaptureRequest.CONTROL_AE_LOCK, true)
            if (jpeg != null) {
                orig.addTarget(jpeg.surface)
                orig.set(CaptureRequest.JPEG_ORIENTATION, 0)
                orig.set(CaptureRequest.JPEG_QUALITY, spec.options.jpegQuality.toByte())
                if (highResSession && Build.VERSION.SDK_INT >= 31) orig.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            } else if (yuv != null) orig.addTarget(yuv.surface)
            rawReader?.let { orig.addTarget(it.surface) }
            setQuality(orig, l, hq = true)
            if (flashCombined) {
                // flash fires once: one request feeds both the original JPEG and the processing YUV
                orig.addTarget(yuv.surface)
                orig.setTag(FrameRole.ORIGINAL)
                requests.add(orig.build())
            } else {
                orig.setTag(FrameRole.ORIGINAL)
                requests.add(orig.build())
                // 2) the burst for our own processing
                for (i in 0 until plan.frames) {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    applyCommon(b, preview = false)
                    b.addTarget(yuv!!.surface)
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    if (plan.exposureNs != null && plan.iso != null) {
                        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                        b.set(CaptureRequest.SENSOR_FRAME_DURATION, plan.frameDurationNs ?: plan.exposureNs)
                        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    } else {
                        b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    if (useSrgbCurve) {
                        if (l.supportsSrgbTonemap) {
                            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                            b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
                        } else {
                            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                            b.set(CaptureRequest.TONEMAP_CURVE, SRGB_CURVE)
                        }
                        if (plan.edgeOff) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                        if (plan.noiseReductionMode in l.nrModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, plan.noiseReductionMode)
                    } else if (plan.hybrid) {
                        // HAL tone/color kept; its spatial NR minimized and sharpening off
                        setQuality(b, l, hq = true)
                        if (plan.noiseReductionMode in l.nrModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, plan.noiseReductionMode)
                        if (plan.edgeOff) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                    } else setQuality(b, l, hq = true)
                    b.setTag(FrameRole.BURST)
                    requests.add(b.build())
                }
            }
            if (flashCombined) newJob.expectedBurst = 1
            val estimated = requests.size * ((plan.exposureNs ?: 33_000_000L).coerceAtLeast(33_000_000L)) / 1_000_000L
            post { listener?.onCaptureStarted(newJob.expectedBurst, estimated) }
            s.captureBurst(requests, stillCallback, cam)
            cam.postDelayed({ watchdog(newJob) }, 5000L + estimated * 2)
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
            processor.abort(newJob, "Falha na captura: ${e.message}")
            job = null
            finishCaptureSequence()
        }
    }

    /** True when the device reports severe heat (Android 10+ thermal API). */
    private fun thermalThrottle(): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
    } catch (_: Exception) { false }

    private fun setQuality(b: CaptureRequest.Builder, l: LensInfo, hq: Boolean) {
        if (!hq) return
        if (CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY in l.nrModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (CameraMetadata.EDGE_MODE_HIGH_QUALITY in l.edgeModes) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        if (CameraMetadata.TONEMAP_MODE_HIGH_QUALITY in l.tonemapModes) b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        private var burstSeen = 0
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val j = job ?: return
            val role = request.tag as? FrameRole ?: return
            j.roles[timestamp] = role
            if (role == FrameRole.ORIGINAL && rawReader != null) j.roles[-timestamp] = FrameRole.RAW
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val j = job ?: return
            when (request.tag as? FrameRole) {
                FrameRole.ORIGINAL -> {
                    j.originalResult = result
                    if (j.refResult == null && j.flashCombined) j.refResult = result
                    pendingRaw?.let { writeDng(j, it, result); pendingRaw = null }
                }
                FrameRole.BURST -> if (j.refResult == null) j.refResult = result
                else -> {}
            }
            checkComplete(j)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val j = job ?: return
            Log.w(TAG, "capture failed reason=${failure.reason} tag=${request.tag}")
            when (request.tag as? FrameRole) {
                FrameRole.BURST -> synchronized(j) { j.expectedBurst = (j.expectedBurst - 1).coerceAtLeast(0) }
                FrameRole.ORIGINAL -> { j.originalReceived = true; j.rawReceived = true }
                else -> {}
            }
            checkComplete(j)
        }

        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            burstSeen = 0
            finishCaptureSequence()
        }

        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) { finishCaptureSequence() }
    }

    private fun finishCaptureSequence() {
        setState(St.IDLE)
        pendingSpec = null
        val b = previewBuilder
        if (b != null && session != null) {
            try {
                if (!aeAfLocked && meteringRegion == null) {
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    session?.capture(b.build(), null, cam)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
                applyCommon(b, preview = true)
                session?.setRepeatingRequest(b.build(), previewCallback, cam)
            } catch (e: Exception) { Log.w(TAG, "restore preview", e) }
        }
        post { listener?.onCaptureDone() }
    }

    // ------------------------------------------------------------------------------ extensions

    private fun extensionChars(l: LensInfo): CameraExtensionCharacteristics? =
        try { manager.getCameraExtensionCharacteristics(l.id) } catch (_: Exception) { null }

    private fun extensionPreviewSize(l: LensInfo, ext: Int, a: AspectRatio): Size? {
        val chars = extensionChars(l) ?: return null
        val sizes = try { chars.getExtensionSupportedSizes(ext, SurfaceTexture::class.java) } catch (_: Exception) { emptyList<Size>() }
        val target = if (a == AspectRatio.R1_1) AspectRatio.R4_3.value else a.value
        return sizes.filter { abs(it.width.toFloat() / it.height - target) < 0.02f && it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
    }

    /**
     * Session that runs the manufacturer's own still pipeline (Camera2 Extensions: Night, Auto,
     * HDR...). This is the only public way for a third-party app to get the multi-frame
     * processing the stock camera uses. Falls back to the regular session if it fails.
     */
    private fun createExtensionSession(l: LensInfo, ext: Int) {
        val d = device ?: run { configuring = false; return }
        val surface = previewSurface ?: run { configuring = false; return }
        val chars = extensionChars(l)
        val target = if (aspect == AspectRatio.R1_1) AspectRatio.R4_3.value else aspect.value
        val jpegSizes = try { chars?.getExtensionSupportedSizes(ext, ImageFormat.JPEG) ?: emptyList() } catch (_: Exception) { emptyList<Size>() }
        val jpeg = bestSize(jpegSizes, target)
        if (chars == null || jpeg == null) { fallbackFromExtension("extensao sem tamanho JPEG"); return }
        closeReaders()
        jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ r -> onJpegImage(r) }, img)
        }
        extKeys = if (Build.VERSION.SDK_INT >= 33) try { chars.getAvailableCaptureRequestKeys(ext).toSet() } catch (_: Exception) { emptySet() } else emptySet()
        val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(jpegReader!!.surface))
        val cfg = ExtensionSessionConfiguration(ext, outputs, camExecutor, object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(s: CameraExtensionSession) {
                configuring = false
                if (device !== d) { s.close(); return }
                extSession = s; activeExtension = ext
                startExtensionPreview()
                val info = "extensao ${CameraInventory.extName(ext)} | JPEG=$jpeg preview=$previewSize"
                Log.i(TAG, "session: $info")
                post { listener?.onOpened(l, info) }
            }
            override fun onConfigureFailed(s: CameraExtensionSession) {
                Log.w(TAG, "extension session failed")
                cam.post { fallbackFromExtension("a extensao do aparelho recusou a sessao") }
            }
        })
        try { d.createExtensionSession(cfg) } catch (e: Exception) { Log.w(TAG, "createExtensionSession", e); fallbackFromExtension(e.message ?: "erro") }
    }

    private fun fallbackFromExtension(reason: String) {
        Log.w(TAG, "extension fallback: $reason")
        extensionWanted = null; extSession = null; activeExtension = null
        configuring = false
        post { listener?.onError("Processamento do aparelho indisponivel aqui; usando o modo normal") }
        tryCreateSession()
    }

    private fun <T> extSet(b: CaptureRequest.Builder, key: CaptureRequest.Key<T>, v: T) {
        // only keys the extension declares (API 33+); older extensions accept none safely
        if (key in extKeys) b.set(key, v)
    }

    private fun startExtensionPreview() {
        val d = device ?: return
        val s = extSession ?: return
        val l = lens ?: return
        val surface = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applyExtensionSettings(b, l)
            s.setRepeatingRequest(b.build(), camExecutor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
        } catch (e: Exception) { Log.w(TAG, "ext preview", e) }
    }

    private fun applyExtensionSettings(b: CaptureRequest.Builder, l: LensInfo) {
        if (l.zoomRange != null) extSet(b, CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        extSet(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        val region = meteringRegion
        if (region != null) {
            extSet(b, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            extSet(b, CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun captureWithExtension(spec: CaptureSpec) {
        val d = device ?: return
        val s = extSession ?: return
        val l = lens ?: return
        val jpeg = jpegReader ?: return
        val ext = activeExtension ?: return
        val rotation = if (l.front) (l.sensorOrientation - spec.deviceOrientation + 360) % 360 else (l.sensorOrientation + spec.deviceOrientation) % 360
        val plan = BurstPlan(0, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, false,
            "processamento do aparelho (extensao ${CameraInventory.extName(ext)})")
        val newJob = CaptureJob(
            id = System.currentTimeMillis(), lens = l, options = spec.options, rotation = rotation,
            mirror = l.front && spec.mirrorFront, crop = null, jpegCrop = aspectCrop(jpeg.width, jpeg.height), plan = plan,
            srgbCurve = false, faces = emptyList(), mode = spec.mode, originalFromJpeg = true, expectRaw = false,
            lensLabel = inventory.labelFor(l) + " · " + CameraInventory.extName(ext),
        )
        job = newJob
        processor.begin(newJob)
        setState(St.CAPTURING)
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jpeg.surface)
            applyExtensionSettings(b, l)
            extSet(b, CaptureRequest.JPEG_ORIENTATION, 0)
            extSet(b, CaptureRequest.JPEG_QUALITY, spec.options.jpegQuality.toByte())
            s.capture(b.build(), camExecutor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
                    post { listener?.onCaptureStarted(1, 1500) }
                }
                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                    setState(St.IDLE)
                    if (job === newJob) { job = null; processor.abort(newJob, "O processamento do aparelho falhou nesta foto") }
                    post { listener?.onCaptureDone() }
                }
                override fun onCaptureSequenceCompleted(session: CameraExtensionSession, sequenceId: Int) {
                    setState(St.IDLE)
                    post { listener?.onCaptureDone() }
                }
                override fun onCaptureSequenceAborted(session: CameraExtensionSession, sequenceId: Int) {
                    setState(St.IDLE)
                    post { listener?.onCaptureDone() }
                }
            })
            cam.postDelayed({ watchdog(newJob) }, 12_000L)
        } catch (e: Exception) {
            Log.e(TAG, "ext capture", e)
            setState(St.IDLE)
            job = null
            processor.abort(newJob, "Falha na captura: ${e.message}")
            post { listener?.onCaptureDone() }
        }
    }

    private fun watchdog(j: CaptureJob) {
        if (job !== j) return
        if (j.originalReceived || j.burstImages > 0) {
            Log.w(TAG, "watchdog: finishing with ${j.burstImages}/${j.expectedBurst} frames")
            synchronized(j) {
                j.expectedBurst = j.burstImages
                j.originalReceived = true; j.rawReceived = true
                if (j.refResult == null) j.refResult = j.originalResult ?: lastResult
            }
            checkComplete(j)
        } else {
            processor.abort(j, "A camera nao entregou a foto a tempo")
            job = null
        }
    }

    private fun checkComplete(j: CaptureJob) {
        if (j.isComplete()) {
            if (job === j) job = null
            processor.complete(j)
        }
    }

    // ------------------------------------------------------------------------------ images

    private fun onAnalysisImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (analysisCount++ % 4 == 0) {
                val h = Frames.hint(image)
                sceneHint = h
                val res = lastResult
                val ev = if (res != null) CapturePlanner.ev100(
                    res.get(CaptureResult.LENS_APERTURE) ?: 1.8f,
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                ) else Float.NaN
                post { listener?.onSceneHint(h, ev) }
            }
        } finally { image.close() }
    }

    private fun onYuvImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        val j = job
        if (j == null) { image.close(); return }
        routeYuv(j, image, 0)
    }

    private fun routeYuv(j: CaptureJob, image: Image, attempt: Int) {
        val role = j.roles[image.timestamp]
        if (role == null && attempt < 40) {
            // onCaptureStarted not delivered yet (different thread): retry shortly
            img.postDelayed({ routeYuv(j, image, attempt + 1) }, 5)
            return
        }
        try {
            when (role ?: FrameRole.BURST) {
                FrameRole.ORIGINAL -> {
                    if (j.flashCombined) {
                        // flash request: YUV of the same exposure goes to processing
                        val f = Frames.copy(image)
                        j.burstImages++
                        processor.addBurstFrame(j, f)
                    } else {
                        j.originalYuv = Frames.copy(image)
                        j.originalReceived = true
                    }
                }
                else -> {
                    val f = Frames.copy(image)
                    synchronized(j) { j.burstImages++ }
                    processor.addBurstFrame(j, f)
                    val done = j.burstImages; val total = j.expectedBurst
                    post { listener?.onFrameProgress(done, total) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame copy", t)
        } finally { image.close() }
        cam.post { checkComplete(j) }
    }

    private fun onJpegImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        val j = job
        try {
            if (j != null) {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                j.originalJpeg = bytes
                j.originalReceived = true
            }
        } finally { image.close() }
        if (j != null) cam.post { checkComplete(j) }
    }

    private fun onRawImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        cam.post {
            val j = job
            if (j == null) { image.close(); return@post }
            val res = j.originalResult
            if (res != null) writeDng(j, image, res) else { pendingRaw?.close(); pendingRaw = image }
        }
    }

    private fun writeDng(j: CaptureJob, image: Image, result: TotalCaptureResult) {
        try {
            val dc = DngCreator(j.lens.characteristics, result)
            dc.setOrientation(com.camerapremium.app.processing.PhotoProcessor.exifOrientation(j.rotation, false))
            val bos = ByteArrayOutputStream()
            dc.writeImage(bos, image)
            dc.close()
            j.dngBytes = bos.toByteArray()
        } catch (t: Throwable) { Log.w(TAG, "dng", t) } finally {
            image.close()
            j.rawReceived = true
            checkComplete(j)
        }
    }

    // ------------------------------------------------------------------------------ helpers

    /** Center crop rectangle (in stream pixels) for the 1:1 aspect when the stream is 4:3. */
    private fun aspectCrop(w: Int, h: Int): Rect? {
        if (aspect != AspectRatio.R1_1) return null
        val s = minOf(w, h) and 1.inv()
        val x = ((w - s) / 2) and 1.inv(); val y = ((h - s) / 2) and 1.inv()
        return Rect(x, y, x + s, y + s)
    }

    /** Faces from the last preview result, as normalized coordinates of the (cropped) still. */
    private fun facesFrom(r: TotalCaptureResult?, l: LensInfo, streamW: Int, streamH: Int, crop: Rect?): List<FaceRegion> {
        val faces = r?.get(CaptureResult.STATISTICS_FACES) ?: return emptyList()
        if (faces.isEmpty()) return emptyList()
        val a = l.activeArray
        val activeAspect = a.width().toFloat() / a.height()
        val streamAspect = streamW.toFloat() / streamH
        val out = ArrayList<FaceRegion>()
        for (f in faces) {
            if (f.score < 30) continue
            val b = f.bounds
            var x0 = (b.left - a.left).toFloat() / a.width(); var x1 = (b.right - a.left).toFloat() / a.width()
            var y0 = (b.top - a.top).toFloat() / a.height(); var y1 = (b.bottom - a.top).toFloat() / a.height()
            if (l.zoomRange == null && zoomRatio > 1f) {
                // crop-region zoom: faces are in full active-array coordinates
                x0 = (x0 - 0.5f) * zoomRatio + 0.5f; x1 = (x1 - 0.5f) * zoomRatio + 0.5f
                y0 = (y0 - 0.5f) * zoomRatio + 0.5f; y1 = (y1 - 0.5f) * zoomRatio + 0.5f
            }
            if (streamAspect > activeAspect + 0.01f) {
                val s = activeAspect / streamAspect; y0 = (y0 - (1 - s) / 2) / s; y1 = (y1 - (1 - s) / 2) / s
            } else if (streamAspect < activeAspect - 0.01f) {
                val s = streamAspect / activeAspect; x0 = (x0 - (1 - s) / 2) / s; x1 = (x1 - (1 - s) / 2) / s
            }
            if (crop != null) {
                x0 = (x0 * streamW - crop.left) / crop.width(); x1 = (x1 * streamW - crop.left) / crop.width()
                y0 = (y0 * streamH - crop.top) / crop.height(); y1 = (y1 * streamH - crop.top) / crop.height()
            }
            if (x1 <= 0f || y1 <= 0f || x0 >= 1f || y0 >= 1f) continue
            out.add(FaceRegion(x0.coerceIn(0f, 1f), y0.coerceIn(0f, 1f), x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), f.score))
        }
        return out
    }

    private fun closeReaders() {
        yuvReader?.close(); yuvReader = null
        jpegReader?.close(); jpegReader = null
        analysisReader?.close(); analysisReader = null
        rawReader?.close(); rawReader = null
    }

    private fun closeInternal() {
        openToken++
        configuring = false
        val j = job
        if (j != null) { processor.abort(j, "Captura interrompida"); job = null }
        setState(St.IDLE)
        try { session?.close() } catch (_: Exception) {}
        try { extSession?.close() } catch (_: Exception) {}
        extSession = null; activeExtension = null
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        previewBuilder = null
        pendingRaw?.close(); pendingRaw = null
        closeReaders()
    }

    private fun post(r: () -> Unit) { main.post { r() } }

    companion object {
        const val TAG = "CameraPremium"

        /** 64-point sRGB transfer curve for devices without the preset (contrast-curve mode). */
        val SRGB_CURVE: TonemapCurve = run {
            val n = 64
            val pts = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = i / (n - 1f)
                pts[i * 2] = x; pts[i * 2 + 1] = ColorMath.linearToSrgb(x)
            }
            TonemapCurve(pts, pts, pts)
        }
    }
}
