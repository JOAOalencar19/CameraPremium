package com.camerapremium.imaging.pipeline

import com.camerapremium.imaging.color.ColorMath
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * White balance refinement on top of the camera's AWB.
 *
 * The camera HAL neutralizes the illuminant. A photograph usually looks better when some of the
 * ambience survives (a candle-lit dinner should not look like an office), so we:
 *  1. Estimate the CCT the AWB neutralized, from its gains and the sensor calibration matrices
 *     (DNG-style iteration). This is real data, not a guess from image content.
 *  2. Re-introduce a fraction of the warmth (or coolness) through a Bradford adaptation.
 *  3. Remove part of a residual tint measured on near-neutral pixels (green/magenta casts).
 *  4. Keep consecutive shots coherent by blending with the previous decision when close.
 */
object WhiteBalance {

    class Decision(
        val matrix: FloatArray,
        val estimatedCct: Float?,
        val renderCct: Float?,
        val castAb: FloatArray,
        val castCorrected: Boolean,
    )

    /** Estimates the CCT of the white the AWB neutralized. Returns null without calibration data. */
    fun estimateAwbCct(gains: FloatArray?, cal: SensorCalibration?): Float? {
        if (gains == null || cal == null || gains.size < 4) return null
        val g = (gains[1] + gains[2]) / 2f
        if (gains[0] <= 0f || gains[3] <= 0f || g <= 0f) return null
        // camera-native neutral (AsShotNeutral) = 1 / gains, normalized to green
        val neutral = floatArrayOf(g / gains[0], 1f, g / gains[3])
        var xy = floatArrayOf(0.3457f, 0.3585f) // start at D50
        var cct = 5000f
        repeat(8) {
            cct = ColorMath.cctFromXy(xy[0], xy[1])
            val cm = interpolatedColorMatrix(cal, cct)
            val xyz = ColorMath.apply3x3(ColorMath.invert3x3(cm), neutral)
            val s = xyz[0] + xyz[1] + xyz[2]
            if (s <= 0f) return null
            xy = floatArrayOf(xyz[0] / s, xyz[1] / s)
        }
        cct = ColorMath.cctFromXy(xy[0], xy[1])
        return if (cct.isFinite()) cct else null
    }

    private fun interpolatedColorMatrix(cal: SensorCalibration, cct: Float): FloatArray {
        val m1 = withCalibration(cal.colorMatrix1, cal.calibration1)
        val cm2 = cal.colorMatrix2 ?: return m1
        val m2 = withCalibration(cm2, cal.calibration2)
        val lo = minOf(cal.illuminant1Cct, cal.illuminant2Cct)
        val hi = maxOf(cal.illuminant1Cct, cal.illuminant2Cct)
        if (hi - lo < 1f) return m1
        // DNG interpolates linearly in inverse CCT
        val t = ((1f / cct - 1f / lo) / (1f / hi - 1f / lo)).coerceIn(0f, 1f)
        val mLo = if (cal.illuminant1Cct <= cal.illuminant2Cct) m1 else m2
        val mHi = if (cal.illuminant1Cct <= cal.illuminant2Cct) m2 else m1
        return ColorMath.lerp3x3(mLo, mHi, t)
    }

    private fun withCalibration(cm: FloatArray, cc: FloatArray?): FloatArray =
        if (cc == null) cm else ColorMath.multiply3x3(cc, cm)

    /** CCT that the photo should be rendered at, keeping part of the ambience. */
    fun renderCct(cct: Float, profile: ProcessingProfile): Float {
        val mired = 1e6f / cct
        val m65 = 1e6f / 6504f
        val keep = when {
            cct < 5200f -> profile.warmRetention * (1f - smooth(3000f, 5200f, cct))
            cct > 7000f -> profile.coolRetention * smooth(7000f, 10000f, cct)
            else -> 0f
        }
        return 1e6f / (m65 + keep * (mired - m65))
    }

    /**
     * @param lowLinear low-resolution linear sRGB image (interleaved RGB) used to measure tint.
     */
    fun decide(
        info: CaptureInfo, profile: ProcessingProfile, lowLinear: FloatArray, n: Int,
    ): Decision {
        // ---- residual tint on near-neutral, well-exposed pixels (Oklab a/b mean)
        val lab = FloatArray(3)
        var sa = 0.0; var sb = 0.0; var cnt = 0
        for (i in 0 until n) {
            val r = lowLinear[i * 3]; val g = lowLinear[i * 3 + 1]; val b = lowLinear[i * 3 + 2]
            ColorMath.linearToOklab(r, g, b, lab)
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            if (lab[0] in 0.45f..0.97f && c < 0.035f) { sa += lab[1]; sb += lab[2]; cnt++ }
        }
        val castA = if (cnt > n / 50) (sa / cnt).toFloat() else 0f
        val castB = if (cnt > n / 50) (sb / cnt).toFloat() else 0f
        val castMag = sqrt(castA * castA + castB * castB)

        var m = ColorMath.identity3x3()
        var castCorrected = false
        val strength = when (info.sourceKind) {
            SourceKind.SYSTEM_RENDERED -> profile.castCorrection * 0.5f
            else -> profile.castCorrection
        }
        // Only correct clear tints; a scene can legitimately contain many warm-gray objects, so we
        // treat the blue/yellow axis (b) more gently than green/magenta (a).
        if (castMag > 0.006f && strength > 0f) {
            val ca = castA * strength
            val cb = castB * strength * 0.5f
            val srcWhite = oklabToXyz(0.8f, ca, cb)
            val dstWhite = oklabToXyz(0.8f, 0f, 0f)
            m = ColorMath.bradfordSrgb(srcWhite, dstWhite)
            castCorrected = true
        }

        // ---- ambience retention from the illuminant the AWB removed
        val est = estimateAwbCct(info.awbGains, info.calibration)
        var render: Float? = null
        if (est != null) {
            var rc = renderCct(est, profile)
            val prev = info.previousWhite
            if (prev != null && prev.lensKey == info.lens.key && info.timestampMs - prev.timestampMs < 20_000L) {
                val dm = abs(1e6f / rc - 1e6f / prev.renderCct)
                if (dm < 20f) rc = 1e6f / ((1e6f / rc + 1e6f / prev.renderCct) / 2f)
            }
            render = rc
            if (abs(1e6f / rc - 1e6f / 6504f) > 2f) {
                val wxy = ColorMath.whiteXyForCct(rc)
                // relative to what the daylight locus says for D65 itself (removes locus offset)
                val dxy = ColorMath.whiteXyForCct(6504f)
                val target = ColorMath.xyToXyz(
                    ColorMath.D65_XYZ[0] / (ColorMath.D65_XYZ[0] + 1f + ColorMath.D65_XYZ[2]) + (wxy[0] - dxy[0]),
                    1f / (ColorMath.D65_XYZ[0] + 1f + ColorMath.D65_XYZ[2]) + (wxy[1] - dxy[1]),
                )
                m = ColorMath.multiply3x3(ColorMath.bradfordSrgb(ColorMath.D65_XYZ, target), m)
            }
        }
        return Decision(m, est, render, floatArrayOf(castA, castB), castCorrected)
    }

    private fun oklabToXyz(L: Float, a: Float, b: Float): FloatArray {
        val rgb = FloatArray(3)
        ColorMath.oklabToLinear(L, a, b, rgb)
        val xyz = ColorMath.apply3x3(ColorMath.SRGB_TO_XYZ, rgb)
        return floatArrayOf(xyz[0] / xyz[1], 1f, xyz[2] / xyz[1])
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
