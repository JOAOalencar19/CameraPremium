package com.camerapremium.imaging.pipeline

import com.camerapremium.imaging.util.Filters
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Perceptual color rendering in Oklab (display-referred, after tone mapping).
 *
 * Design rules:
 *  - Hue-selective changes use wide raised-cosine windows: no hue band boundaries, no posterization.
 *  - Chroma changes are multiplicative and small; vibrance acts more on muted colors than on
 *    already-saturated ones, so nothing turns neon.
 *  - Skin is detected softly (color likelihood x face prior) and only *outliers* are corrected:
 *    over-saturated orange/red skin is compressed (never boosted) and extreme hues are pulled a little toward the
 *    center of the natural skin range. Lightness is never changed (no automatic skin brightening),
 *    and texture is untouched here (sharpening/NR are only softened on skin elsewhere).
 */
class ColorLook(private val p: ProcessingProfile, private val saturationScale: Float = 1f) {

    /**
     * Applies the look in place on lab[o..o+2] (L, a, b). Returns the skin weight (0..1).
     */
    fun apply(lab: FloatArray, o: Int, skinPrior: Float): Float {
        val L = lab[o]
        var a = lab[o + 1]
        var b = lab[o + 2]
        val c = sqrt(a * a + b * b)
        if (c < 1e-5f) return 0f
        val h = atan2Deg(b, a)

        // ---- skin likelihood
        val hueW = window(h, 57f, 38f)
        val chromaW = Filters.smoothstep(0.004f, 0.016f, c) * (1f - Filters.smoothstep(0.15f, 0.2f, c))
        val lightW = Filters.smoothstep(0.18f, 0.3f, L) * (1f - Filters.smoothstep(0.9f, 0.97f, L))
        val skin = (hueW * chromaW * lightW * skinPrior * 1.6f).coerceIn(0f, 1f)

        // ---- global look (non-skin)
        var gain = p.chroma * (1f + p.vibrance * (1f - Filters.smoothstep(0f, 0.2f, c)))
        val wRed = window(h, 25f, 30f)
        val wGreen = window(h, 145f, 40f)
        val wCyan = window(h, 200f, 30f)
        val wSky = window(h, 250f, 35f)
        val wPurple = window(h, 320f, 30f)
        gain *= 1f + (p.redChroma - 1f) * wRed
        gain *= 1f + (p.greenChroma - 1f) * wGreen
        gain *= 1f + (0.98f - 1f) * wCyan
        gain *= 1f + (p.skyChroma - 1f) * wSky
        gain *= 1f + (0.97f - 1f) * wPurple
        gain *= saturationScale
        val hueShift = p.greenHueShiftDeg * wGreen
        var lOut = L - p.skyDeepen * wSky * minOf(c, 0.15f) / 0.15f * 0.25f

        // ---- skin rendering: replaces the global chroma gain where skin is likely
        var skinGain = 1f
        var skinShift = 0f
        if (skin > 0f) {
            // Only over-saturated skin is compressed. Light skin is naturally low in chroma, so
            // nothing is ever *added* to skin chroma (that would homogenize different skin tones).
            if (c > p.skinChromaKnee) skinGain = (p.skinChromaKnee + (c - p.skinChromaKnee) * p.skinChromaSlope) / c
            val d = deltaDeg(h, p.skinHueCenterDeg)
            if (abs(d) > p.skinHueTolerance) skinShift = -(abs(d) - p.skinHueTolerance) * p.skinHuePull * sign(d)
        }
        val g = gain + (skinGain - gain) * skin
        val shiftDeg = hueShift * (1f - skin) + skinShift * skin

        // ---- tonal chroma limits: highlights roll off to white, deep shadows lose chroma noise
        // only near-white highlights (speculars, overcast sky) roll off; bright blue sky is kept
        var tonal = 1f - p.highlightDesat * Filters.smoothstep(0.93f, 1.0f, L) * (1f - Filters.smoothstep(0.04f, 0.1f, c))
        tonal *= 0.82f + 0.18f * Filters.smoothstep(0.03f, 0.14f, L)

        a *= g * tonal
        b *= g * tonal
        if (shiftDeg != 0f) {
            val th = Math.toRadians(shiftDeg.toDouble()).toFloat()
            val na = a - b * th
            val nb = b + a * th
            val k = sqrt(a * a + b * b) / maxOf(1e-6f, sqrt(na * na + nb * nb))
            a = na * k; b = nb * k
        }
        if (lOut < 0f) lOut = 0f
        lab[o] = lOut
        lab[o + 1] = a
        lab[o + 2] = b
        return skin
    }

    companion object {
        fun sign(x: Float) = if (x < 0f) -1f else 1f

        fun deltaDeg(h: Float, center: Float): Float {
            var d = h - center
            if (d > 180f) d -= 360f else if (d <= -180f) d += 360f
            return d
        }

        /** Raised-cosine window; cos evaluated by a short polynomial (smooth, monotone on [0, pi]). */
        fun window(h: Float, center: Float, halfWidth: Float): Float {
            val d = abs(deltaDeg(h, center))
            if (d >= halfWidth) return 0f
            val t = d / halfWidth // 0..1
            // 0.5 + 0.5 cos(pi t) ~ smooth Hermite: 1 - (3t^2 - 2t^3)
            return 1f - t * t * (3f - 2f * t)
        }

        /** Fast atan2 in degrees [0, 360). Max error ~0.1 degrees. */
        fun atan2Deg(y: Float, x: Float): Float {
            val ax = abs(x); val ay = abs(y)
            val mx = maxOf(ax, ay)
            if (mx == 0f) return 0f
            val t = minOf(ax, ay) / mx
            val s = t * t
            var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * t + t
            if (ay > ax) r = 1.57079637f - r
            if (x < 0) r = 3.14159274f - r
            if (y < 0) r = -r
            var deg = r * 57.2957795f
            if (deg < 0f) deg += 360f
            return deg
        }
    }
}
