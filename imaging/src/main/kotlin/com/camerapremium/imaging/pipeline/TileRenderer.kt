package com.camerapremium.imaging.pipeline

import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.image.SourceImage
import com.camerapremium.imaging.util.EpsModel
import com.camerapremium.imaging.util.FastMath
import com.camerapremium.imaging.util.Filters
import com.camerapremium.imaging.util.GuidedWorkspace
import kotlin.math.sqrt

/**
 * Renders one tile (plus a halo for the spatial filters) through the full per-pixel pipeline:
 *
 *   decode -> lens/WB matrix -> local+global tone mapping -> Oklab -> color/skin look
 *   -> luma NR (guided, noise-model driven, grain retained) -> chroma NR (luma-guided, half-res)
 *   -> adaptive sharpening with coring and overshoot clamp -> micro-contrast
 *   -> gamut mapping -> output encoding (+ dither, + gain map)
 *
 * Every stage reads the noise model, so its strength follows the real noise of *this* image.
 */
class TileRenderer(
    private val src: SourceImage,
    private val plan: RenderPlan,
    private val profile: ProcessingProfile,
    private val output: OutputOptions,
    private val look: ColorLook,
    /** Optional per-8x8-block noise multiplier (burst regions that fell back to fewer frames). */
    private val noiseFactor: FloatArray?,
    private val noiseFactorW: Int,
    private val lensNoiseScale: Float = 1f,
) {
    var noise: NoiseModel = NoiseModel(FloatArray(NoiseModel.BINS), FloatArray(NoiseModel.BINS))

    class Workspace(capacity: Int) {
        val r = FloatArray(capacity); val g = FloatArray(capacity); val b = FloatArray(capacity)
        val okL = FloatArray(capacity); val okA = FloatArray(capacity); val okB = FloatArray(capacity)
        val skin = FloatArray(capacity); val gain = FloatArray(capacity); val nf = FloatArray(capacity)
        val ca = FloatArray(capacity); val cb = FloatArray(capacity)
        val ld = FloatArray(capacity); val t1 = FloatArray(capacity); val t2 = FloatArray(capacity)
        val half = capacity / 4 + 1024
        val hL = FloatArray(half); val hA = FloatArray(half); val hB = FloatArray(half)
        val hA2 = FloatArray(half); val hB2 = FloatArray(half); val hc1 = FloatArray(half); val hc2 = FloatArray(half)
        val gws = GuidedWorkspace()
        val gwsHalf = GuidedWorkspace()
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        var argb = IntArray(0)
    }

    // ----------------------------------------------------------------------------- stage 1

    /** Per-pixel stages for the rect (clamped reads). Fills ws.okL/A/B, ws.skin, ws.gain. */
    fun renderBase(x0: Int, y0: Int, w: Int, h: Int, ws: Workspace) {
        src.readEncoded(x0, y0, w, h, ws.r, ws.g, ws.b)
        val m = plan.colorMatrix
        val curve = plan.curve
        val scale = plan.gridScale.toFloat()
        val gw = plan.gridW; val gh = plan.gridH
        val lab = ws.lab
        val maxX = plan.width - 1; val maxY = plan.height - 1
        var i = 0
        for (yy in 0 until h) {
            val py = (y0 + yy).coerceIn(0, maxY)
            val gy = (py + 0.5f) / scale - 0.5f
            for (xx in 0 until w) {
                val px = (x0 + xx).coerceIn(0, maxX)
                val gx = (px + 0.5f) / scale - 0.5f
                val lr = ColorMath.decode(ws.r[i]); val lg = ColorMath.decode(ws.g[i]); val lb = ColorMath.decode(ws.b[i])
                var r = m[0] * lr + m[1] * lg + m[2] * lb
                var g = m[3] * lr + m[4] * lg + m[5] * lb
                var b = m[6] * lr + m[7] * lg + m[8] * lb
                val y = ColorMath.luminance(r, g, b).coerceAtLeast(1e-6f)
                val l2 = FastMath.log2(y)
                val ta = Filters.sampleBilinear(plan.toneA, gw, gh, gx, gy)
                val tb = Filters.sampleBilinear(plan.toneB, gw, gh, gx, gy)
                val mapped = l2 + ta * l2 + tb
                val d = curve.display(mapped)
                val ratio = d / y
                r *= ratio; g *= ratio; b *= ratio
                ws.gain[i] = if (output.gainMap) (curve.displayHdr(mapped) / d.coerceAtLeast(1e-4f)) else 1f
                ColorMath.linearToOklab(r.coerceAtLeast(0f), g.coerceAtLeast(0f), b.coerceAtLeast(0f), lab, 0)
                val prior = Filters.sampleBilinear(plan.skinPrior, gw, gh, gx, gy)
                ws.skin[i] = look.apply(lab, 0, prior)
                ws.okL[i] = lab[0]; ws.okA[i] = lab[1]; ws.okB[i] = lab[2]
                if (noiseFactor != null) {
                    ws.nf[i] = noiseFactor[(py / 8) * noiseFactorW + (px / 8)]
                } else ws.nf[i] = 1f
                i++
            }
        }
    }

    // ----------------------------------------------------------------------------- full tile

    /**
     * Renders the core rect [x0, x0+w) x [y0, y0+h) and returns packed ARGB in ws.argb (w*h).
     * If [gainOut] is given, accumulates log2 gain into the quarter-res map.
     */
    fun renderTile(x0: Int, y0: Int, w: Int, h: Int, ws: Workspace, gainOut: FloatArray?, gainW: Int) {
        val H = HALO
        val pw = w + 2 * H; val ph = h + 2 * H
        val n = pw * ph
        renderBase(x0 - H, y0 - H, pw, ph, ws)
        val nm = noise

        // ---- luma NR: self-guided filter with signal-dependent regularization
        // A system-rendered source was already denoised by the camera: only a light touch here.
        val rendered = plan.sourceKind.ispDenoised
        val wellLit = Filters.lerp(1f, profile.wellLitNrScale, plan.stats.dayFactor)
        val k = (if (nm.temporal) profile.lumaNrStrengthTemporal else profile.lumaNrStrength) * lensNoiseScale * wellLit * (if (rendered) profile.renderedLumaNrScale else 1f)
        val eps = object : EpsModel {
            override fun eps(meanGuide: Float, index: Int): Float {
                val s = nm.lumaSigma(meanGuide) * ws.nf[index] * k
                return s * s + 1e-7f
            }
        }
        Filters.guidedCoefficients(ws.okL, ws.okL, pw, ph, 2, eps, ws.ca, ws.cb, ws.gws)
        val rho = if (rendered) profile.renderedGrainRetention else profile.grainRetention
        for (i in 0 until n) {
            val q = ws.ca[i] * ws.okL[i] + ws.cb[i]
            ws.ld[i] = q + rho * (ws.okL[i] - q)
        }

        // ---- chroma NR at half resolution, guided by luma
        val hw = (pw + 1) / 2; val hh = (ph + 1) / 2
        val hn = hw * hh
        Filters.downsample(ws.ld, pw, ph, 2, ws.hL)
        Filters.downsample(ws.okA, pw, ph, 2, ws.hA)
        Filters.downsample(ws.okB, pw, ph, 2, ws.hB)
        val epsL = object : EpsModel {
            override fun eps(meanGuide: Float, index: Int): Float {
                val s = 2f * nm.lumaSigma(meanGuide)
                return s * s + 4e-5f
            }
        }
        Filters.guidedCoefficients(ws.hL, ws.hA, hw, hh, 4, epsL, ws.hc1, ws.hc2, ws.gwsHalf)
        Filters.applyCoefficients(ws.hL, ws.hc1, ws.hc2, ws.hA2, hn)
        Filters.guidedCoefficients(ws.hL, ws.hB, hw, hh, 4, epsL, ws.hc1, ws.hc2, ws.gwsHalf)
        Filters.applyCoefficients(ws.hL, ws.hc1, ws.hc2, ws.hB2, hn)
        val cs = profile.chromaNrStrength * (if (rendered) 0.6f else 1f)
        val fcs = if (rendered) 0f else profile.falseColorSuppression
        for (yy in 0 until ph) {
            val fy = (yy - 0.5f) * 0.5f
            for (xx in 0 until pw) {
                val i = yy * pw + xx
                val fx = (xx - 0.5f) * 0.5f
                val af = Filters.sampleBilinear(ws.hA2, hw, hh, fx, fy)
                val bf = Filters.sampleBilinear(ws.hB2, hw, hh, fx, fy)
                val skinBoost = 1f + (profile.skinChromaNrBoost - 1f) * ws.skin[i]
                val thr = cs * skinBoost * nm.chromaSigma(ws.ld[i]) * ws.nf[i] + 1e-5f
                val da = ws.okA[i] - af; val db = ws.okB[i] - bf
                val mag = sqrt(da * da + db * db)
                // texture confidence from the luma filter: false color lives on fine luma texture
                // on near-neutral surfaces (metal, text, chains), where a rainbow is most visible.
                // Saturated fine detail (colored fabric) keeps its full-resolution chroma.
                val neutral = 1f - Filters.smoothstep(0.03f, 0.08f, sqrt(af * af + bf * bf))
                // In shadows the luma texture is often below the noise-based confidence, yet glints
                // in a dark mesh show the same speckle: shadows count as texture for this purpose.
                val dark = 1f - Filters.smoothstep(0.25f, 0.45f, ws.ld[i])
                val fcw = maxOf(ws.ca[i].coerceIn(0f, 1f), dark)
                val keep = Filters.smoothstep(thr, 2f * thr, mag) * (1f - fcs * neutral * fcw)
                ws.okA[i] = af + keep * da
                ws.okB[i] = bf + keep * db
            }
        }

        // ---- sharpening (detail band) + micro-contrast (mid band) on denoised luma
        // radius follows resolution: the same physical detail spans more pixels at 50 MP
        Filters.gauss(ws.ld, ws.t1, pw, ph, profile.sharpenRadius * plan.detailScale, ws.t2) // t1 = blur
        // mid band: three passes of a radius-3 box ~ gaussian sigma 3.5
        Filters.boxMean(ws.ld, ws.okL, pw, ph, 3, ws.t2)
        Filters.boxMean(ws.okL, ws.okL, pw, ph, 3, ws.t2)
        Filters.boxMean(ws.okL, ws.okL, pw, ph, 3, ws.t2) // ws.okL now holds the mid-band blur
        val amount = plan.sharpenAmount
        val micro = plan.microContrast
        val coring = profile.coringSigmas
        val ov = profile.sharpenOvershoot
        val skinScale = profile.skinSharpenScale
        val residual = sqrt(rho * rho + 0.05f)
        val thr0 = profile.sharpenThreshold
        val noiseRef = profile.sharpenNoiseRef
        val flatFloor = profile.sharpenFlatFloor
        val smoothing = profile.skinSmoothing
        val specMargin = profile.skinHighlightMargin
        val specKeep = profile.skinHighlightSlope

        // ---- output
        if (ws.argb.size < w * h) ws.argb = IntArray(w * h)
        val out = ws.argb
        val p3 = output.colorSpace == OutputColorSpace.DISPLAY_P3
        val mp = ColorMath.SRGB_TO_P3
        val rgb = ws.rgb
        for (yy in 0 until h) {
            val py = yy + H
            for (xx in 0 until w) {
                val px = xx + H
                val i = py * pw + px
                val ld = ws.ld[i]
                // detail band with noise-aware coring
                val hd = ld - ws.t1[i]
                val sig = nm.lumaSigma(ld) * ws.nf[i]
                val t = coring * sig * residual + thr0
                val hc = hd * (hd * hd / (hd * hd + t * t))
                val skin = ws.skin[i]
                val shadow = 0.5f + 0.5f * Filters.smoothstep(0.08f, 0.25f, ld)
                // texture confidence from the luma guided filter: ~0 on flat areas, ~1 on real
                // edges/texture (lashes, hair, fabric). Flat areas keep only a fraction.
                val conf = ws.ca[i].coerceIn(0f, 1f)
                val texture = flatFloor + (1f - flatFloor) * conf
                val sn = sig / noiseRef
                val noiseFall = 1f / (1f + sn * sn)
                val a = amount * (1f - skin * (1f - skinScale)) * shadow * texture * noiseFall
                var s = ld + a * hc
                // optional subtle skin smoothing: attenuates only the mid band (blotches), never
                // the finest band (pores, hairs); contours with high confidence are protected
                if (smoothing > 0f && skin > 0f) {
                    val prot = (1f - conf) * (1f - conf)
                    s -= smoothing * skin * prot * (ws.t1[i] - ws.okL[i])
                }
                // specular roll-off on skin: a hot spot is a local excess over the surrounding
                // skin (mid-band blur), so uniformly light skin is untouched
                if (skin > 0f && ld > 0.7f) {
                    val excess = ld - ws.okL[i] - specMargin
                    if (excess > 0f) s -= skin * excess * (1f - specKeep)
                }
                // overshoot clamp against the 3x3 neighbourhood -> no halos
                var mn = ld; var mx = ld
                for (dy in -1..1) {
                    val row = (py + dy) * pw + px
                    for (dx in -1..1) {
                        val v = ws.ld[row + dx]
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                    }
                }
                if (s < mn - ov) s = mn - ov
                if (s > mx + ov) s = mx + ov
                // micro-contrast, midtones only, limited amplitude
                if (micro > 0f) {
                    val mid = Filters.smoothstep(0.1f, 0.35f, ld) * (1f - Filters.smoothstep(0.75f, 0.95f, ld))
                    val mc = ((ld - ws.okL[i]) * micro * mid * (1f - 0.6f * skin)).coerceIn(-0.02f, 0.02f)
                    s += mc
                }
                ColorMath.oklabToLinear(s.coerceAtLeast(0f), ws.okA[i], ws.okB[i], rgb, 0)
                var r = rgb[0]; var g = rgb[1]; var b = rgb[2]
                if (p3) {
                    val nr = mp[0] * r + mp[1] * g + mp[2] * b
                    val ng = mp[3] * r + mp[4] * g + mp[5] * b
                    val nb = mp[6] * r + mp[7] * g + mp[8] * b
                    r = nr; g = ng; b = nb
                }
                // gamut mapping. Pure desaturation toward gray turns bright skies white; pure
                // darkening keeps saturation but loses brightness. Blend both (each result is in
                // gamut, so their mix is too): hue is kept, a sky stays blue and a bit deeper.
                val sc = s.coerceAtLeast(0f)
                val gray = (sc * sc * sc).coerceAtMost(1f)
                var tg = 1f
                tg = limit(r, gray, tg); tg = limit(g, gray, tg); tg = limit(b, gray, tg)
                if (tg < 1f) {
                    val dr = gray + (r - gray) * tg; val dg = gray + (g - gray) * tg; val dbb = gray + (b - gray) * tg
                    val mxc = maxOf(r, g, b)
                    if (mxc > 1f && minOf(r, g, b) >= 0f) {
                        val k = 1f / mxc
                        r = 0.5f * (dr + r * k); g = 0.5f * (dg + g * k); b = 0.5f * (dbb + b * k)
                    } else { r = dr; g = dg; b = dbb }
                }
                val ax = x0 + xx; val ay = y0 + yy
                out[yy * w + xx] = pack(r, g, b, ax, ay)
                if (gainOut != null) {
                    val gi = (ay / 4) * gainW + (ax / 4)
                    gainOut[gi] += FastMath.log2(ws.gain[i].coerceAtLeast(1f)) * (1f / 16f)
                }
            }
        }
    }

    private fun limit(v: Float, gray: Float, t: Float): Float {
        if (v < 0f) {
            val d = gray - v
            if (d > 1e-6f) return minOf(t, gray / d)
        } else if (v > 1f) {
            val d = v - gray
            if (d > 1e-6f) return minOf(t, ((1f - gray) / d).coerceAtLeast(0f))
        }
        return t
    }

    private fun pack(r: Float, g: Float, b: Float, x: Int, y: Int): Int {
        val dither = output.dither
        val dr = if (dither) ditherAt(x, y) else 0f
        val dg = if (dither) ditherAt(y + 11, x) else 0f
        val db = if (dither) ditherAt(x + 7, y + 3) else 0f
        val ri = (ColorMath.encode(r) * 255f + 0.5f + dr).toInt().coerceIn(0, 255)
        val gi = (ColorMath.encode(g) * 255f + 0.5f + dg).toInt().coerceIn(0, 255)
        val bi = (ColorMath.encode(b) * 255f + 0.5f + db).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Triangular-ish dither in (-0.5, 0.5) code values from a cheap integer hash: no banding in skies. */
    private fun ditherAt(x: Int, y: Int): Float {
        var hsh = x * 374761393 + y * 668265263
        hsh = (hsh xor (hsh ushr 13)) * 1274126177
        hsh = hsh xor (hsh ushr 16)
        val u1 = (hsh and 0xFFFF) / 65536f
        val u2 = ((hsh ushr 16) and 0xFFFF) / 65536f
        return (u1 + u2 - 1f) * 0.5f
    }

    companion object {
        const val HALO = 20
        const val TILE = 288
    }
}
