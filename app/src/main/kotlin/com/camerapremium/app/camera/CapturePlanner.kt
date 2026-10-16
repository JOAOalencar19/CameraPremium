package com.camerapremium.app.camera

import android.hardware.camera2.CameraMetadata
import android.media.Image
import com.camerapremium.app.settings.CaptureMode
import com.camerapremium.imaging.image.YuvFrame
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Live scene statistics from the small analysis stream. */
class SceneHint(
    val meanLuma: Float = 0.45f,
    /** Fraction of pixels at the top of the range (Y >= 250). */
    val clippedFraction: Float = 0f,
    /** 99.5th percentile of luma, 0..255. */
    val p995: Int = 200,
    /** Fraction of very dark pixels (Y <= 16). */
    val darkFraction: Float = 0f,
)

/** What the capture should do. */
class BurstPlan(
    val frames: Int,
    /** Manual exposure for the burst frames, or null to keep AE (locked) with [evBias] compensation. */
    val exposureNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    /** Underexposure applied to protect highlights (EV, <= 0). */
    val evBias: Float,
    val noiseReductionMode: Int,
    val edgeOff: Boolean,
    val reason: String,
) {
    override fun toString() = "frames=$frames exp=${exposureNs?.let { "%.1fms".format(it / 1e6) }} iso=$iso ev=%.2f nr=$noiseReductionMode edgeOff=$edgeOff ($reason)".format(evBias)
}

/**
 * Burst planning, following the logic of burst photography: several identical, slightly
 * underexposed short frames are better than one long one — highlights are protected by the
 * underexposure, motion blur by the short exposure, and noise is recovered by merging.
 */
object CapturePlanner {

    fun plan(
        lens: LensInfo,
        mode: CaptureMode,
        aeExposureNs: Long,
        aeIso: Int,
        hint: SceneHint,
        multiFrame: Boolean,
    ): BurstPlan {
        val nrMinimal = if (CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in lens.nrModes) CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        else CameraMetadata.NOISE_REDUCTION_MODE_FAST
        val canEdgeOff = CameraMetadata.EDGE_MODE_OFF in lens.edgeModes
        if (!multiFrame || !lens.manualSensor || aeExposureNs <= 0 || aeIso <= 0) {
            val n = if (multiFrame && lens.burstCapable) 4 else 1
            return BurstPlan(n, null, null, null, 0f, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, false,
                if (!lens.manualSensor) "sem MANUAL_SENSOR: AE travado" else "fusao desativada")
        }

        // --- highlight protection: underexpose when the live view shows clipping
        var ev = 0f
        if (hint.p995 >= 250 || hint.clippedFraction > 0.002f) ev = -1f
        if (hint.clippedFraction > 0.02f) ev = -1.7f
        if (hint.clippedFraction > 0.08f) ev = -2.3f
        if (mode == CaptureMode.NIGHT) ev = min(ev, -0.3f)

        // --- per-frame exposure limit (hand-held, OIS-assisted)
        val eq = if (lens.equivalentFocal > 0f) lens.equivalentFocal else 24f
        val hasOis = lens.oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        var tMax = (1e9 / (2.0 * eq) * (if (hasOis) 3.0 else 1.0)).toLong()
        tMax = if (mode == CaptureMode.NIGHT) min(tMax * 2, 125_000_000L) else min(tMax, 66_000_000L)
        val exposureRange = lens.exposureRange
        val isoRange = lens.isoRange
        val total = aeExposureNs.toDouble() * aeIso * 2.0.pow(ev.toDouble())
        var t: Long; var iso: Int
        val tWanted = (aeExposureNs * 2.0.pow(ev.toDouble())).toLong()
        if (tWanted <= tMax) { t = tWanted; iso = aeIso } else { t = tMax; iso = (total / t).roundToInt() }
        if (isoRange != null) iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        if (exposureRange != null) t = t.coerceIn(exposureRange.lower, exposureRange.upper)

        // --- frame count from the resulting noise level; long bursts only when they pay off
        var n = when {
            iso <= 80 -> 4
            iso <= 200 -> 5
            iso <= 800 -> 7
            iso <= 1600 -> 8
            else -> 10
        }
        if (ev < -0.5f) n += 2
        if (mode == CaptureMode.NIGHT) n = max(n, 12)
        val budgetNs = if (mode == CaptureMode.NIGHT) 3_000_000_000L else 1_100_000_000L
        val perFrame = max(t, 33_000_000L)
        n = n.coerceAtMost((budgetNs / perFrame).toInt()).coerceIn(2, 15)

        val nr = if (iso >= 1600) CameraMetadata.NOISE_REDUCTION_MODE_FAST else nrMinimal
        val reason = "ISO $aeIso->${iso} t ${"%.1f".format(aeExposureNs / 1e6)}->${"%.1f".format(t / 1e6)}ms, clip ${"%.3f".format(hint.clippedFraction)}"
        return BurstPlan(n, t, iso, max(t, 33_333_333L), ev, nr, canEdgeOff, reason)
    }

    /** EV100 estimate of the scene from AE values (for logging/UI). */
    fun ev100(aperture: Float, exposureNs: Long, iso: Int): Float =
        if (aperture <= 0f || exposureNs <= 0 || iso <= 0) Float.NaN
        else (ln(aperture.toDouble() * aperture / (exposureNs / 1e9)) / ln(2.0) - ln(iso / 100.0) / ln(2.0)).toFloat()
}

object Frames {
    /** Copies a YUV_420_888 Image into a planar [YuvFrame] (handles any pixel/row stride). */
    fun copy(image: Image, crop: android.graphics.Rect? = null): YuvFrame {
        val r = crop ?: android.graphics.Rect(0, 0, image.width, image.height)
        // keep even offsets so chroma stays aligned
        val x0 = r.left and 1.inv(); val y0 = r.top and 1.inv()
        val w = (r.width()) and 1.inv(); val h = (r.height()) and 1.inv()
        val f = YuvFrame.allocate(w, h)
        val planes = image.planes
        val yp = planes[0]
        val yb = yp.buffer
        val yRow = yp.rowStride
        val yPix = yp.pixelStride
        if (yPix == 1) {
            for (y in 0 until h) {
                yb.position((y + y0) * yRow + x0)
                yb.get(f.y, y * w, w)
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) f.y[y * w + x] = yb.get((y + y0) * yRow + (x + x0) * yPix)
        }
        val up = planes[1]; val vp = planes[2]
        val ub = up.buffer; val vb = vp.buffer
        val cw = f.chromaWidth; val ch = f.chromaHeight
        val cx0 = x0 / 2; val cy0 = y0 / 2
        val uRow = up.rowStride; val uPix = up.pixelStride
        val vRow = vp.rowStride; val vPix = vp.pixelStride
        if (uPix == 1 && vPix == 1) {
            for (y in 0 until ch) {
                ub.position((y + cy0) * uRow + cx0); ub.get(f.u, y * cw, cw)
                vb.position((y + cy0) * vRow + cx0); vb.get(f.v, y * cw, cw)
            }
        } else {
            val rowU = ByteArray(uRow); val rowV = ByteArray(vRow)
            for (y in 0 until ch) {
                val ou = (y + cy0) * uRow; val ov = (y + cy0) * vRow
                val lenU = min(uRow, ub.limit() - ou); val lenV = min(vRow, vb.limit() - ov)
                ub.position(ou); ub.get(rowU, 0, lenU)
                vb.position(ov); vb.get(rowV, 0, lenV)
                for (x in 0 until cw) {
                    val iu = (x + cx0) * uPix; val iv = (x + cx0) * vPix
                    f.u[y * cw + x] = if (iu < lenU) rowU[iu] else 128.toByte()
                    f.v[y * cw + x] = if (iv < lenV) rowV[iv] else 128.toByte()
                }
            }
        }
        return YuvFrame(w, h, f.y, w, f.u, f.v, cw, image.timestamp)
    }

    /** Luma statistics on a small analysis frame. */
    fun hint(image: Image): SceneHint {
        val p = image.planes[0]
        val b = p.buffer
        val hist = IntArray(256)
        val w = image.width; val h = image.height
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) { hist[b.get(y * p.rowStride + x * p.pixelStride).toInt() and 0xFF]++; n++; x += 4 }
            y += 4
        }
        var sum = 0L; var clip = 0; var dark = 0
        for (i in 0..255) { sum += i.toLong() * hist[i]; if (i >= 250) clip += hist[i]; if (i <= 16) dark += hist[i] }
        var acc = 0; var p995 = 255
        val lim = (n * 0.995).toInt()
        for (i in 0..255) { acc += hist[i]; if (acc >= lim) { p995 = i; break } }
        return SceneHint(sum.toFloat() / n / 255f, clip.toFloat() / n, p995, dark.toFloat() / n)
    }
}
