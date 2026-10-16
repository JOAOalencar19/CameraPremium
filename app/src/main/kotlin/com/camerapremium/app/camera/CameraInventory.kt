package com.camerapremium.app.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import android.util.SizeF
import com.camerapremium.imaging.pipeline.LensProfile
import com.camerapremium.imaging.pipeline.SensorCalibration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything the app needs to know about one camera (logical, standalone or hidden physical). */
class LensInfo(
    val id: String,
    /** For a hidden physical sub-camera: the logical camera it belongs to. */
    val parentLogicalId: String?,
    val facing: Int,
    val characteristics: CameraCharacteristics,
) {
    private val c = characteristics
    val capabilities: Set<Int> = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
    val hardwareLevel: Int = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
    val isLogical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
    val physicalIds: Set<String> = if (isLogical) c.physicalCameraIds else emptySet()
    val sensorOrientation: Int = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val focalLengths: FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
    val apertures: FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
    val sensorSize: SizeF? = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val pixelArray: Size? = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val activeArray: Rect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    val zoomRange: android.util.Range<Float>? = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    val maxDigitalZoom: Float = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val minFocusDistance: Float = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val afModes: IntArray = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
    val aeCompRange: android.util.Range<Int>? = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val aeCompStep: Float = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    val isoRange: android.util.Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureRange: android.util.Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val oisModes: IntArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
    val nrModes: IntArray = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
    val edgeModes: IntArray = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
    val tonemapModes: IntArray = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
    val maxTonemapPoints: Int = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
    val awbModes: IntArray = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
    val flashAvailable: Boolean = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    val faceModes: IntArray = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
    val distortionModes: IntArray = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: intArrayOf()
    val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maxResStreamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)

    val yuvSizes: List<Size> = sizes(ImageFormat.YUV_420_888)
    val jpegSizes: List<Size> = sizes(ImageFormat.JPEG)
    val rawSizes: List<Size> = sizes(ImageFormat.RAW_SENSOR)
    val maxResJpegSizes: List<Size> = maxResStreamMap?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width.toLong() * it.height }?.toList() ?: emptyList()
    val ultraHighResolution = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in capabilities
    val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val manualPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
    val rawCapable = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities && rawSizes.isNotEmpty()
    val burstCapable = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities
    val hasAutoFocus = afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF } && minFocusDistance > 0f
    val supportsSrgbTonemap = CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tonemapModes
    val supportsContrastCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes && maxTonemapPoints >= 32

    /** 35 mm-equivalent focal length (diagonal-based). */
    val equivalentFocal: Float = run {
        val f = focalLengths.firstOrNull() ?: return@run 0f
        val s = sensorSize ?: return@run 0f
        // the active array may be a crop of the pixel array: scale the physical size accordingly
        val pa = pixelArray
        val cropW = if (pa != null && pa.width > 0) s.width * activeArray.width() / pa.width else s.width
        val cropH = if (pa != null && pa.height > 0) s.height * activeArray.height() / pa.height else s.height
        val diag = sqrt(cropW * cropW + cropH * cropH)
        if (diag <= 0f) 0f else f * 43.27f / diag
    }

    val calibration: SensorCalibration? = run {
        val cm1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return@run null
        val cm2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val i1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: return@run null
        val i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        SensorCalibration(
            colorMatrix1 = toArray(cm1),
            colorMatrix2 = cm2?.let { toArray(it) },
            illuminant1Cct = illuminantCct(i1),
            illuminant2Cct = if (i2 != null) illuminantCct(i2) else illuminantCct(i1),
            calibration1 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let { toArray(it) },
            calibration2 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { toArray(it) },
        )
    }

    /** Camera2 extensions (Samsung exposes proprietary modes here, if any). */
    var extensions: List<Int> = emptyList()

    /** Bokeh via extended scene mode (API 30+), if present. */
    val extendedSceneModes: List<Int> = if (Build.VERSION.SDK_INT >= 30) {
        c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.map { it.mode } ?: emptyList()
    } else emptyList()

    val front get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    /** Public camera2 bokeh (portrait) for stills, if the HAL offers it. */
    val bokehZoomRange: android.util.Range<Float>? = if (Build.VERSION.SDK_INT >= 30) {
        c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)
            ?.firstOrNull { it.mode == CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE }?.zoomRatioRange
    } else null
    val supportsBokeh get() = bokehZoomRange != null

    fun lensProfile(): LensProfile = LensProfile.forFocalClass(id, equivalentFocal, front)

    private fun sizes(format: Int): List<Size> =
        streamMap?.getOutputSizes(format)?.sortedByDescending { it.width.toLong() * it.height }?.toList() ?: emptyList()

    fun describe(): String = buildString {
        append(if (front) "frontal" else "traseira")
        append(if (isLogical) " (logica: ${physicalIds.joinToString(",")})" else "")
        if (parentLogicalId != null) append(" [fisica de $parentLogicalId]")
        append(", %.1f mm (eq. %.0f mm)".format(focalLengths.firstOrNull() ?: 0f, equivalentFocal))
        apertures.firstOrNull()?.let { append(", f/%.1f".format(it)) }
    }

    companion object {
        fun toArray(t: android.hardware.camera2.params.ColorSpaceTransform): FloatArray =
            FloatArray(9) { val r = it / 3; val col = it % 3; t.getElement(col, r).toFloat() }

        /** DNG/camera2 reference illuminant enum -> CCT (K). */
        fun illuminantCct(i: Int): Float = when (i) {
            1, 4, 9 -> 5500f      // daylight, flash, fine weather
            2, 14 -> 4150f        // fluorescent / cool white fluorescent
            3 -> 2850f            // tungsten
            10 -> 6500f           // cloudy
            11 -> 7500f           // shade
            12 -> 6430f           // daylight fluorescent
            13 -> 5000f           // day white fluorescent
            15 -> 3450f           // white fluorescent
            17 -> 2856f           // standard A
            18 -> 4874f           // standard B
            19 -> 6774f           // standard C
            20 -> 5503f           // D55
            21 -> 6504f           // D65
            22 -> 7504f           // D75
            23 -> 5003f           // D50
            24 -> 3200f           // ISO studio tungsten
            else -> 5000f
        }

        fun hardwareLevelName(l: Int) = when (l) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?($l)"
        }
    }
}

/** A user-facing zoom factor and how to reach it with the best available hardware. */
class ZoomStop(
    /** Factor relative to the main rear camera (1x). */
    val factor: Float,
    val cameraId: String,
    /** Value for CONTROL_ZOOM_RATIO on [cameraId]. */
    val ratioOnCamera: Float,
    /** True when a physical lens natively provides this framing (no crop). */
    val native: Boolean,
    val lensLabel: String,
) {
    val label: String get() = formatFactor(factor)

    companion object {
        fun formatFactor(f: Float): String {
            val r = (f * 10f).roundToInt() / 10f
            return if (abs(r - r.roundToInt()) < 0.05f) "${r.roundToInt()}" else "%.1f".format(r).replace(',', '.')
        }
    }
}

/**
 * Enumerates all cameras the platform exposes (including physical sub-cameras of logical
 * multi-cameras) and derives the zoom stops dynamically. Nothing is hard-coded for the S26 Ultra:
 * factors come from real focal lengths and sensor sizes.
 */
class CameraInventory(private val manager: CameraManager) {
    val lenses = LinkedHashMap<String, LensInfo>()
    var rearLogical: LensInfo? = null
        private set
    var front: LensInfo? = null
        private set
    var mainEquivalentFocal = 24f
        private set
    val rearStops = ArrayList<ZoomStop>()
    val frontStops = ArrayList<ZoomStop>()
    val warnings = ArrayList<String>()

    fun load() {
        lenses.clear(); rearStops.clear(); frontStops.clear(); warnings.clear()
        for (id in manager.cameraIdList) {
            try {
                val ch = manager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
                val info = LensInfo(id, null, facing, ch)
                try { info.extensions = manager.getCameraExtensionCharacteristics(id).supportedExtensions } catch (_: Exception) {}
                lenses[id] = info
            } catch (e: Exception) { warnings.add("camera $id: ${e.message}") }
        }
        // hidden physical cameras behind logical ones
        for (l in lenses.values.toList()) for (pid in l.physicalIds) {
            if (lenses.containsKey(pid)) continue
            try {
                val ch = manager.getCameraCharacteristics(pid)
                lenses[pid] = LensInfo(pid, l.id, ch.get(CameraCharacteristics.LENS_FACING) ?: l.facing, ch)
            } catch (e: Exception) { warnings.add("fisica $pid: ${e.message}") }
        }
        val standaloneRear = lenses.values.filter { it.parentLogicalId == null && it.facing == CameraCharacteristics.LENS_FACING_BACK }
        // Prefer the logical camera with the widest zoom range (it switches lenses itself, with
        // the manufacturer's color/exposure matching). Otherwise the first rear camera.
        rearLogical = standaloneRear.maxByOrNull { l ->
            val r = l.zoomRange
            (if (l.isLogical) 1000f else 0f) + (if (r != null) r.upper / r.lower else 1f) - l.id.length * 0.01f
        } ?: standaloneRear.firstOrNull()
        front = lenses.values.filter { it.parentLogicalId == null && it.front }.minByOrNull { it.id.length * 100 + (it.id.toIntOrNull() ?: 99) }
        buildRearStops()
        buildFrontStops()
    }

    private fun rearPhysicals(): List<LensInfo> {
        val logical = rearLogical ?: return emptyList()
        val list = ArrayList<LensInfo>()
        for (pid in logical.physicalIds) lenses[pid]?.let { list.add(it) }
        for (l in lenses.values) if (l.parentLogicalId == null && l.facing == CameraCharacteristics.LENS_FACING_BACK && l.id != logical.id && l !in list) list.add(l)
        return list.filter { it.equivalentFocal > 0f }
    }

    fun labelFor(lens: LensInfo?): String {
        if (lens == null) return ""
        if (lens.front) return "Frontal"
        val f = lens.equivalentFocal / mainEquivalentFocal
        return when {
            f < 0.8f -> "Ultra-wide"
            f < 1.3f -> "Principal"
            else -> "Tele ${ZoomStop.formatFactor(f)}×"
        }
    }

    private fun buildRearStops() {
        val logical = rearLogical ?: return
        val physicals = rearPhysicals()
        // the "main" lens = the one closest to the logical camera's own focal length at 1x
        mainEquivalentFocal = if (logical.equivalentFocal > 0f) logical.equivalentFocal
        else physicals.minByOrNull { abs(it.equivalentFocal - 24f) }?.equivalentFocal ?: 24f
        val zr = logical.zoomRange
        val minRatio = zr?.lower ?: 1f
        val maxRatio = zr?.upper ?: logical.maxDigitalZoom
        val nativeFactors = physicals.map { it.equivalentFocal / mainEquivalentFocal }.sorted()

        val candidates = sortedSetOf<Float>()
        if (minRatio < 0.95f) candidates.add(minRatio)
        nativeFactors.filter { it < 0.95f }.forEach { candidates.add(roundNice(it)) }
        candidates.add(1f)
        candidates.add(2f)
        nativeFactors.filter { it > 1.3f }.forEach { candidates.add(roundNice(it)) }
        candidates.add(10f)

        for (f in candidates) {
            // route 1: the logical camera covers it (seamless switching by the HAL)
            if (zr != null && f >= minRatio - 1e-3f && f <= maxRatio + 1e-3f) {
                val nativeLens = physicals.firstOrNull { abs(it.equivalentFocal / mainEquivalentFocal - f) / f < 0.08f }
                rearStops.add(ZoomStop(f, logical.id, f.coerceIn(minRatio, maxRatio), nativeLens != null || abs(f - 1f) < 0.01f, labelFor(nativeLens ?: logical)))
                continue
            }
            // route 2: a standalone camera with this or a wider lens, plus digital crop
            val lens = physicals.filter { it.parentLogicalId == null }
                .filter { it.equivalentFocal / mainEquivalentFocal <= f * 1.02f }
                .maxByOrNull { it.equivalentFocal }
            if (lens != null) {
                val lf = lens.equivalentFocal / mainEquivalentFocal
                val onCam = f / lf
                val maxOnCam = lens.zoomRange?.upper ?: lens.maxDigitalZoom
                if (onCam <= maxOnCam + 1e-3f) rearStops.add(ZoomStop(f, lens.id, onCam, abs(onCam - 1f) < 0.08f, labelFor(lens)))
            } else if (f >= 1f && f <= logical.maxDigitalZoom) {
                rearStops.add(ZoomStop(f, logical.id, f, abs(f - 1f) < 0.01f, labelFor(logical)))
            }
        }
        // de-duplicate near-identical stops (e.g. 2x and a 2.0x native lens)
        val dedup = ArrayList<ZoomStop>()
        for (s in rearStops.sortedBy { it.factor }) {
            val last = dedup.lastOrNull()
            if (last != null && abs(last.factor - s.factor) / s.factor < 0.1f) {
                if (s.native && !last.native) dedup[dedup.size - 1] = s
            } else dedup.add(s)
        }
        rearStops.clear(); rearStops.addAll(dedup)
        if (rearStops.isEmpty()) rearStops.add(ZoomStop(1f, logical.id, 1f, true, "Principal"))
    }

    private fun buildFrontStops() {
        val f = front ?: return
        val zr = f.zoomRange
        if (zr != null && zr.lower < 0.95f) frontStops.add(ZoomStop(zr.lower, f.id, zr.lower, true, "Frontal"))
        frontStops.add(ZoomStop(1f, f.id, 1f, true, "Frontal"))
    }

    /** Max factor reachable by pinch on the rear side. */
    fun rearMaxFactor(): Float {
        val l = rearLogical ?: return 1f
        return l.zoomRange?.upper ?: l.maxDigitalZoom
    }

    fun report(): String = buildString {
        append("== Inventario de cameras (Camera2) ==\n")
        append("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        append("IDs publicos: ${manager.cameraIdList.joinToString()}\n")
        append("Traseira logica escolhida: ${rearLogical?.id}  frontal: ${front?.id}  principal eq.: %.1f mm\n\n".format(mainEquivalentFocal))
        for (l in lenses.values) {
            append("[${l.id}] ${l.describe()} — ${labelFor(l)}\n")
            append("  nivel=${LensInfo.hardwareLevelName(l.hardwareLevel)} orientacao=${l.sensorOrientation} capacidades=${capNames(l.capabilities)}\n")
            append("  sensor=${l.sensorSize} pixels=${l.pixelArray} ativo=${l.activeArray.width()}x${l.activeArray.height()}\n")
            append("  zoom=${l.zoomRange} maxDigital=${l.maxDigitalZoom} focoMin=${l.minFocusDistance} AF=${l.afModes.joinToString()}\n")
            append("  ISO=${l.isoRange} exposicao(ns)=${l.exposureRange} EV=${l.aeCompRange} passo=${l.aeCompStep}\n")
            append("  YUV max=${l.yuvSizes.firstOrNull()} JPEG max=${l.jpegSizes.firstOrNull()} RAW=${l.rawSizes.firstOrNull()} maxRes JPEG=${l.maxResJpegSizes.firstOrNull()}\n")
            append("  OIS=${l.oisModes.joinToString()} NR=${l.nrModes.joinToString()} EDGE=${l.edgeModes.joinToString()} TONEMAP=${l.tonemapModes.joinToString()} (pts ${l.maxTonemapPoints})\n")
            append("  AWB=${l.awbModes.joinToString()} flash=${l.flashAvailable} faces=${l.faceModes.joinToString()} distorcao=${l.distortionModes.joinToString()}\n")
            append("  calibracao de cor=${if (l.calibration != null) "sim" else "nao"} extensoes=${l.extensions.joinToString { extName(it) }} cenaEstendida=${l.extendedSceneModes.joinToString()}\n")
            if (l.jpegSizes.isNotEmpty() && Build.VERSION.SDK_INT >= 34) {
                val jr = l.streamMap?.getOutputSizes(ImageFormat.JPEG_R)?.firstOrNull()
                append("  JPEG_R (Ultra HDR do HAL)=${jr ?: "nao"}\n")
            }
        }
        append("\nParadas de zoom traseiras:\n")
        for (s in rearStops) append("  ${s.label}x -> camera ${s.cameraId} ratio %.2f ${if (s.native) "(lente nativa)" else "(recorte)"} ${s.lensLabel}\n".format(s.ratioOnCamera))
        append("Zoom maximo por gesto: %.1fx\n".format(rearMaxFactor()))
        append("Paradas frontais: ${frontStops.joinToString { it.label + "x" }}\n")
        if (warnings.isNotEmpty()) append("Avisos: ${warnings.joinToString("; ")}\n")
    }

    private fun capNames(caps: Set<Int>) = caps.sorted().joinToString(",") {
        when (it) {
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BC"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RES"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROC"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIV_REPROC"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH"
            else -> it.toString()
        }
    }

    companion object {
        fun extName(e: Int) = when (e) {
            CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTO"
            CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
            CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
            CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
            CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
            else -> e.toString()
        }

        private fun roundNice(f: Float): Float = if (f < 1f) (f * 10f).roundToInt() / 10f else (f * 2f).roundToInt() / 2f
    }
}
