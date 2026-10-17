package com.camerapremium.imaging.pipeline

import kotlin.math.exp
import kotlin.math.pow

/**
 * Global scene-to-display curve, defined in log2 space around 18% gray:
 *  - linear-in-log midtones with a gentle contrast slope (no crushed shadows, no gray veil),
 *  - an exponential highlight shoulder (C1-continuous) so bright areas roll off instead of clipping,
 *  - normalized so that [whiteStops] above mid-gray reaches display white,
 *  - a tiny black flare subtraction for deep but not crushed blacks.
 * Evaluated through a LUT indexed by log2 luminance.
 */
class ToneCurve(
    val contrast: Float,
    val shoulderStart: Float,
    val whiteStops: Float,
    val flare: Float,
    /** Display-linear gain after the black flare (white-point stretch), with a soft knee so the
     *  stretched highlights roll into white instead of clipping. 1 = none. */
    val gain: Float = 1f,
) {
    private val vMax = (Math.log(1.0 / MID) / Math.log(2.0)).toFloat()
    private val dWhite = rawDisplay(whiteStops)
    private val lut = FloatArray(LUT_SIZE + 1)
    private val hdrLut = FloatArray(LUT_SIZE + 1)

    init {
        for (i in 0..LUT_SIZE) {
            val l2 = MIN_LOG2 + (MAX_LOG2 - MIN_LOG2) * i / LUT_SIZE
            val u = l2 - LOG2_MID
            lut[i] = finish(rawDisplay(u))
            // HDR rendition for the gain map: the same midtones without the shoulder compression
            hdrLut[i] = finish((MID * 2.0.pow((contrast * u).toDouble())).toFloat() / dWhite)
        }
    }

    private fun rawDisplay(u: Float): Float {
        val s = contrast
        val v = if (u <= shoulderStart) s * u else {
            val k = vMax - s * shoulderStart
            if (k <= 0f) s * u else vMax - k * exp((-s * (u - shoulderStart) / k).toDouble()).toFloat()
        }
        return (MID * 2.0.pow(v.toDouble())).toFloat()
    }

    private fun finish(d: Float): Float {
        val n = d / dWhite
        val f = ((n - flare) / (1f - flare)).coerceAtLeast(0f)
        if (gain == 1f) return f
        // the stretch acts on highlights only: midtones (faces) keep the camera's exposure
        val g = f * (1f + (gain - 1f) * smooth(0.1f, 0.7f, f))
        return if (g <= KNEE) g else KNEE + (1f - KNEE) * kotlin.math.tanh((g - KNEE) / (1f - KNEE))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Display-linear value (0..~1, >1 only for out-of-range) for log2 scene luminance. */
    fun display(log2Y: Float): Float = lookup(lut, log2Y)

    fun displayHdr(log2Y: Float): Float = lookup(hdrLut, log2Y)

    private fun lookup(t: FloatArray, log2Y: Float): Float {
        val f = (log2Y - MIN_LOG2) * SCALE
        if (f <= 0f) return t[0]
        if (f >= LUT_SIZE) return t[LUT_SIZE]
        val i = f.toInt()
        val a = f - i
        return t[i] + (t[i + 1] - t[i]) * a
    }

    companion object {
        const val MID = 0.18f
        val LOG2_MID = (Math.log(MID.toDouble()) / Math.log(2.0)).toFloat()
        const val MIN_LOG2 = -16f
        const val MAX_LOG2 = 8f
        const val LUT_SIZE = 4096
        const val SCALE = LUT_SIZE / (MAX_LOG2 - MIN_LOG2)

        private const val KNEE = 0.8f

        /** Near-identity curve for already-rendered (system) images. */
        fun identity(): ToneCurve = ToneCurve(1f, 10f, LOG2_ONE_OVER_MID, 0f)

        /** Identity tones with a black-point (flare) and white-point (gain) correction only. */
        fun levels(flare: Float, gain: Float): ToneCurve = ToneCurve(1f, 10f, LOG2_ONE_OVER_MID, flare, gain)

        private val LOG2_ONE_OVER_MID = (Math.log(1.0 / MID) / Math.log(2.0)).toFloat()
    }
}
