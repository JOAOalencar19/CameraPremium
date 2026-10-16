package com.camerapremium.imaging.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Color science primitives used by the pipeline.
 *
 * Working space: linear-light sRGB/Rec.709 primaries, D65 white (scene-referred until the
 * tone mapper, display-referred afterwards). Perceptual edits happen in Oklab, which is
 * defined directly on top of linear sRGB and is hue-linear enough for selective adjustments.
 */
object ColorMath {

    // ---------------------------------------------------------------- transfer functions

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(l: Float): Float =
        if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    /** 8-bit sRGB code value -> linear. */
    val SRGB8_TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255f) }

    private const val DECODE_LUT_SIZE = 4096
    private val decodeLut = FloatArray(DECODE_LUT_SIZE + 1) { srgbToLinear(it.toFloat() / DECODE_LUT_SIZE) }

    /** Continuous sRGB decode (input 0..1, values outside are clamped) using an interpolated LUT. */
    fun decode(c: Float): Float {
        if (c <= 0f) return 0f
        if (c >= 1f) return 1f
        val f = c * DECODE_LUT_SIZE
        val i = f.toInt()
        val t = f - i
        return decodeLut[i] + (decodeLut[i + 1] - decodeLut[i]) * t
    }

    // The encode LUT is indexed in sqrt(linear) so that the shadows get enough precision.
    private const val ENCODE_LUT_SIZE = 4096
    private val encodeLut = FloatArray(ENCODE_LUT_SIZE + 1) {
        val s = it.toFloat() / ENCODE_LUT_SIZE
        linearToSrgb(s * s)
    }

    /** Continuous sRGB encode (input linear 0..1, clamped). */
    fun encode(l: Float): Float {
        if (l <= 0f) return 0f
        if (l >= 1f) return 1f
        val f = sqrt(l) * ENCODE_LUT_SIZE
        val i = f.toInt()
        val t = f - i
        return encodeLut[i] + (encodeLut[i + 1] - encodeLut[i]) * t
    }

    // ---------------------------------------------------------------- matrices

    /** Linear sRGB -> CIE XYZ (D65). */
    val SRGB_TO_XYZ = floatArrayOf(
        0.4123908f, 0.3575843f, 0.1804808f,
        0.2126390f, 0.7151687f, 0.0721923f,
        0.0193308f, 0.1191948f, 0.9505322f,
    )
    val XYZ_TO_SRGB = invert3x3(SRGB_TO_XYZ)

    /** CIE XYZ (D65) -> linear Display P3. */
    private val XYZ_TO_P3 = floatArrayOf(
        2.4934969f, -0.9313836f, -0.4027108f,
        -0.8294890f, 1.7626641f, 0.0236247f,
        0.0358458f, -0.0761724f, 0.9568845f,
    )
    val SRGB_TO_P3 = multiply3x3(XYZ_TO_P3, SRGB_TO_XYZ)
    val P3_TO_SRGB = invert3x3(SRGB_TO_P3)

    /** Bradford cone response matrix. */
    private val BRADFORD = floatArrayOf(
        0.8951f, 0.2664f, -0.1614f,
        -0.7502f, 1.7135f, 0.0367f,
        0.0389f, -0.0685f, 1.0296f,
    )
    private val BRADFORD_INV = invert3x3(BRADFORD)

    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    fun luminance(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = s
        }
        return o
    }

    fun invert3x3(m: FloatArray): FloatArray {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-12) { "singular matrix" }
        val inv = doubleArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d,
        )
        return FloatArray(9) { (inv[it] / det).toFloat() }
    }

    fun identity3x3(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun apply3x3(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /** Linear interpolation between two matrices. */
    fun lerp3x3(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(9) { a[it] + (b[it] - a[it]) * t }

    // ---------------------------------------------------------------- chromaticity / CCT

    /** xy chromaticity of a CIE daylight / Planckian white at the given CCT (Kelvin). */
    fun whiteXyForCct(cct: Float): FloatArray {
        val t = cct.coerceIn(1667f, 25000f).toDouble()
        return if (t >= 4000.0) {
            // CIE daylight locus
            val x = if (t <= 7000.0) {
                -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
            } else {
                -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
            }
            val y = -3.000 * x * x + 2.870 * x - 0.275
            floatArrayOf(x.toFloat(), y.toFloat())
        } else {
            // Planckian locus approximation (Kim et al.)
            val x = if (t <= 4000.0) -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
            else -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
            val y = when {
                t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            floatArrayOf(x.toFloat(), y.toFloat())
        }
    }

    /** McCamy's approximation of CCT from xy. Valid ~2000K..12000K. */
    fun cctFromXy(x: Float, y: Float): Float {
        val n = (x - 0.3320f) / (0.1858f - y)
        return (449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f).coerceIn(1500f, 20000f)
    }

    fun xyToXyz(x: Float, y: Float): FloatArray = floatArrayOf(x / y, 1f, (1f - x - y) / y)

    /**
     * Bradford chromatic adaptation matrix operating on linear sRGB, adapting colors seen under
     * [srcWhiteXyz] so they appear as under [dstWhiteXyz].
     */
    fun bradfordSrgb(srcWhiteXyz: FloatArray, dstWhiteXyz: FloatArray): FloatArray {
        val s = apply3x3(BRADFORD, srcWhiteXyz)
        val d = apply3x3(BRADFORD, dstWhiteXyz)
        val diag = floatArrayOf(d[0] / s[0], 0f, 0f, 0f, d[1] / s[1], 0f, 0f, 0f, d[2] / s[2])
        val xyzCat = multiply3x3(BRADFORD_INV, multiply3x3(diag, BRADFORD))
        return multiply3x3(XYZ_TO_SRGB, multiply3x3(xyzCat, SRGB_TO_XYZ))
    }

    val D65_XYZ: FloatArray = floatArrayOf(0.95047f, 1f, 1.08883f)

    // ---------------------------------------------------------------- Oklab

    /** Fast cube root for non-negative and negative floats (two Newton steps, ~1e-6 rel. error). */
    fun cbrt(v: Float): Float {
        if (v == 0f) return 0f
        val a = abs(v)
        var x = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(a) / 3 + 709921077)
        x = x - (x * x * x - a) / (3f * x * x)
        x = x - (x * x * x - a) / (3f * x * x)
        x = x - (x * x * x - a) / (3f * x * x)
        return if (v < 0f) -x else x
    }

    /** Linear sRGB -> Oklab. Writes L, a, b into [out] at [o]. */
    fun linearToOklab(r: Float, g: Float, b: Float, out: FloatArray, o: Int = 0) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[o] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[o + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[o + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Oklab -> linear sRGB. Writes r, g, b into [out] at [o]. */
    fun oklabToLinear(L: Float, a: Float, b: Float, out: FloatArray, o: Int = 0) {
        val l1 = L + 0.3963377774f * a + 0.2158037573f * b
        val m1 = L - 0.1055613458f * a - 0.0638541728f * b
        val s1 = L - 0.0894841775f * a - 1.2914855480f * b
        val l = l1 * l1 * l1
        val m = m1 * m1 * m1
        val s = s1 * s1 * s1
        out[o] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[o + 1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[o + 2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    /** Hue angle of an Oklab color in degrees [0, 360). */
    fun hueDeg(a: Float, b: Float): Float {
        var h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
        if (h < 0f) h += 360f
        return h
    }

    /** Signed smallest difference between two hue angles in degrees, in (-180, 180]. */
    fun hueDelta(h: Float, center: Float): Float {
        var d = h - center
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    /** Raised-cosine window: 1 at the center, 0 beyond +/- halfWidth. Smooth, no discontinuity. */
    fun hueWindow(h: Float, center: Float, halfWidth: Float): Float {
        val d = abs(hueDelta(h, center))
        if (d >= halfWidth) return 0f
        return (0.5f + 0.5f * cos(Math.PI * d / halfWidth)).toFloat()
    }

    fun rotateAb(a: Float, b: Float, deg: Float, out: FloatArray, o: Int) {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        out[o] = a * c - b * s
        out[o + 1] = a * s + b * c
    }

    // ---------------------------------------------------------------- CIEDE2000 (metrics)

    fun linearSrgbToLab(r: Float, g: Float, b: Float): DoubleArray {
        val x = (SRGB_TO_XYZ[0] * r + SRGB_TO_XYZ[1] * g + SRGB_TO_XYZ[2] * b) / D65_XYZ[0]
        val y = (SRGB_TO_XYZ[3] * r + SRGB_TO_XYZ[4] * g + SRGB_TO_XYZ[5] * b) / D65_XYZ[1]
        val z = (SRGB_TO_XYZ[6] * r + SRGB_TO_XYZ[7] * g + SRGB_TO_XYZ[8] * b) / D65_XYZ[2]
        fun f(t: Double) = if (t > 216.0 / 24389.0) Math.cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x.toDouble()); val fy = f(y.toDouble()); val fz = f(z.toDouble())
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun deltaE2000(l1: DoubleArray, l2: DoubleArray): Double {
        val (L1, a1, b1) = Triple(l1[0], l1[1], l1[2])
        val (L2, a2, b2) = Triple(l2[0], l2[1], l2[2])
        val c1 = sqrt(a1 * a1 + b1 * b1); val c2 = sqrt(a2 * a2 + b2 * b2)
        val cm = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cm.pow(7) / (cm.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1; val a2p = (1 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1); val c2p = sqrt(a2p * a2p + b2 * b2)
        fun hp(b: Double, a: Double): Double { if (a == 0.0 && b == 0.0) return 0.0; val h = Math.toDegrees(atan2(b, a)); return if (h < 0) h + 360 else h }
        val h1p = hp(b1, a1p); val h2p = hp(b2, a2p)
        val dLp = L2 - L1; val dCp = c2p - c1p
        var dhp = h2p - h1p
        if (c1p * c2p == 0.0) dhp = 0.0 else if (dhp > 180) dhp -= 360 else if (dhp < -180) dhp += 360
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lpm = (L1 + L2) / 2; val cpm = (c1p + c2p) / 2
        var hpm = h1p + h2p
        if (c1p * c2p != 0.0) {
            hpm = if (abs(h1p - h2p) <= 180) hpm / 2 else if (h1p + h2p < 360) (hpm + 360) / 2 else (hpm - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hpm - 30)) + 0.24 * cos(Math.toRadians(2 * hpm)) +
            0.32 * cos(Math.toRadians(3 * hpm + 6)) - 0.20 * cos(Math.toRadians(4 * hpm - 63))
        val dTheta = 30 * exp(-((hpm - 275) / 25).pow(2))
        val rc = 2 * sqrt(cpm.pow(7) / (cpm.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lpm - 50).pow(2) / sqrt(20 + (lpm - 50).pow(2))
        val sc = 1 + 0.045 * cpm
        val sh = 1 + 0.015 * cpm * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh))
    }
}
