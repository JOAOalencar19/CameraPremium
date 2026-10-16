package com.camerapremium.imaging.pipeline

import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.image.SourceImage
import com.camerapremium.imaging.util.ConstantEps
import com.camerapremium.imaging.util.FastMath
import com.camerapremium.imaging.util.Filters
import com.camerapremium.imaging.util.GuidedWorkspace
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything the per-tile renderer needs, decided once on a low-resolution view of the image.
 */
class RenderPlan(
    val width: Int,
    val height: Int,
    /** lens residual * white balance, applied to linear RGB first. */
    val colorMatrix: FloatArray,
    /** Low-res grid geometry: one cell covers [gridScale] full-res pixels. */
    val gridW: Int,
    val gridH: Int,
    val gridScale: Int,
    /** Local tone offset in log2 stops: delta(x) = toneA(x) * log2(Y(x)) + toneB(x) (guided upsampling). */
    val toneA: FloatArray,
    val toneB: FloatArray,
    val curve: ToneCurve,
    /** Prior (0..1) that a pixel belongs to a person's skin, from face detection; per grid cell. */
    val skinPrior: FloatArray,
    val sharpenAmount: Float,
    val microContrast: Float,
    val stats: SceneStats,
    val whiteBalance: WhiteBalance.Decision,
    val sourceKind: SourceKind,
    /** Spatial scale of fine detail relative to a 12 MP frame (sharpening radius follows it). */
    val detailScale: Float = 1f,
)

class SceneStats(
    val sceneEv: Float?,
    val dayFactor: Float,
    val nightFactor: Float,
    val keyLog2: Float,
    val p1Log2: Float,
    val p995Log2: Float,
    val dynamicRangeStops: Float,
    val highlightCompression: Float,
    val shadowCompression: Float,
    val exposureEv: Float,
    val targetKey: Float,
    val clippedFraction: Float,
    val faces: Int,
) {
    override fun toString(): String =
        "ev=${sceneEv?.let { "%.1f".format(it) } ?: "?"} day=%.2f night=%.2f key=%.2f DR=%.1f cHi=%.2f cLo=%.2f E=%+.2f target=%.3f clip=%.3f faces=%d"
            .format(dayFactor, nightFactor, keyLog2, dynamicRangeStops, highlightCompression, shadowCompression, exposureEv, targetKey, clippedFraction, faces)
}

object SceneAnalyzer {
    const val TARGET_LONG_SIDE = 640

    fun analyze(src: SourceImage, info: CaptureInfo, profile: ProcessingProfile): RenderPlan {
        val w = src.width; val h = src.height
        val scale = max(1, ceil(max(w, h) / TARGET_LONG_SIDE.toFloat()).toInt())
        val gw = (w + scale - 1) / scale
        val gh = (h + scale - 1) / scale
        val n = gw * gh

        // ---- 1. low-res linear image (box average in linear light) + clipping stats
        val low = FloatArray(n * 3)
        val r = FloatArray(w * scale); val g = FloatArray(w * scale); val b = FloatArray(w * scale)
        val lens = info.lens.colorMatrix
        var clipped = 0L
        for (gy in 0 until gh) {
            val y0 = gy * scale
            val rows = min(scale, h - y0)
            src.readEncoded(0, y0, w, rows, r, g, b)
            for (gx in 0 until gw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var c = 0
                val x0 = gx * scale; val x1 = min(w, x0 + scale)
                for (yy in 0 until rows) {
                    val row = yy * w
                    for (x in x0 until x1) {
                        val i = row + x
                        if (r[i] > 0.985f || g[i] > 0.985f || b[i] > 0.985f) clipped++
                        sr += ColorMath.decode(r[i]); sg += ColorMath.decode(g[i]); sb += ColorMath.decode(b[i]); c++
                    }
                }
                var lr = sr / c; var lg = sg / c; var lb = sb / c
                if (lens != null) {
                    val nr = lens[0] * lr + lens[1] * lg + lens[2] * lb
                    val ng = lens[3] * lr + lens[4] * lg + lens[5] * lb
                    val nb = lens[6] * lr + lens[7] * lg + lens[8] * lb
                    lr = nr; lg = ng; lb = nb
                }
                val o = (gy * gw + gx) * 3
                low[o] = lr; low[o + 1] = lg; low[o + 2] = lb
            }
        }
        val clippedFraction = clipped.toFloat() / (w.toLong() * h).toFloat()

        // ---- 2. white balance refinement
        val wb = WhiteBalance.decide(info, profile, low, n)
        val m = if (lens != null) ColorMath.multiply3x3(wb.matrix, lens) else wb.matrix
        val wbm = wb.matrix
        val lg2 = FloatArray(n)
        for (i in 0 until n) {
            val o = i * 3
            val lr = low[o]; val lgv = low[o + 1]; val lb = low[o + 2]
            val rr = wbm[0] * lr + wbm[1] * lgv + wbm[2] * lb
            val gg = wbm[3] * lr + wbm[4] * lgv + wbm[5] * lb
            val bb = wbm[6] * lr + wbm[7] * lgv + wbm[8] * lb
            lg2[i] = FastMath.log2(max(ColorMath.luminance(rr, gg, bb), 1e-5f))
        }

        // ---- 3. base layer: edge-preserving smooth of log luminance (large scale illumination)
        val ws = GuidedWorkspace()
        val baseA = FloatArray(n); val baseB = FloatArray(n)
        val radius = max(2, max(gw, gh) / 48)
        Filters.guidedCoefficients(lg2, lg2, gw, gh, radius, ConstantEps(profile.baseFilterEps), baseA, baseB, ws)
        val base = FloatArray(n)
        Filters.applyCoefficients(lg2, baseA, baseB, base, n)

        // ---- 4. weights: center emphasis + faces
        val faces = info.faces
        val weight = FloatArray(n)
        val skinPrior = FloatArray(n)
        var faceArea = 0f
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val nx = (gx + 0.5f) / gw; val ny = (gy + 0.5f) / gh
            val dx = nx - 0.5f; val dy = ny - 0.5f
            val center = exp(-(dx * dx + dy * dy) / (2f * 0.3f * 0.3f).toDouble()).toFloat()
            var face = 0f
            for (f in faces) {
                // ellipse around the face, extended downwards to include neck/upper chest
                val ex = (nx - f.cx) / (f.w * 0.8f)
                val ey = (ny - (f.cy + f.h * 0.25f)) / (f.h * 1.1f)
                val d = ex * ex + ey * ey
                face = max(face, (1f - Filters.smoothstep(0.6f, 1.4f, d)))
            }
            val i = gy * gw + gx
            weight[i] = 0.5f + center
            skinPrior[i] = if (faces.isEmpty()) 0.55f else 0.3f + 0.7f * face
            faceArea += face
        }

        // ---- 5. statistics of the base layer
        val sorted = base.copyOf(); sorted.sort()
        val p1 = sorted[(n * 0.01f).toInt().coerceIn(0, n - 1)]
        val p98 = sorted[(n * 0.98f).toInt().coerceIn(0, n - 1)]
        val p995 = sorted[(n * 0.995f).toInt().coerceIn(0, n - 1)]
        var ks = 0.0; var kw = 0.0; var fs = 0.0; var fw = 0.0
        for (i in 0 until n) {
            if (base[i] > p98) continue
            ks += base[i] * weight[i]; kw += weight[i]
        }
        if (faces.isNotEmpty() && faceArea > 0.5f) {
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val i = gy * gw + gx
                val fwt = (skinPrior[i] - 0.3f) / 0.7f
                if (fwt > 0.5f) { fs += base[i] * fwt; fw += fwt }
            }
        }
        var key = (ks / kw).toFloat()
        if (fw > 0.0) {
            val faceKey = (fs / fw).toFloat()
            key = key * (1f - profile.faceKeyWeight) + faceKey * profile.faceKeyWeight
        }

        // ---- 6. scene classification (smooth factors, never a hard switch between styles)
        val ev = info.captureEv100()
        val day = if (ev == null) 0.6f else Filters.smoothstep(5f, 11f, ev)
        val night = if (ev == null) 0f else 1f - Filters.smoothstep(1f, 5.5f, ev)
        val targetLit = Filters.lerp(profile.targetKeyIndoor, profile.targetKeyDay, day)
        val target = Filters.lerp(targetLit, profile.targetKeyNight, night)

        // ---- 7. dynamic range compression of the base layer
        val rendered = info.sourceKind == SourceKind.SYSTEM_RENDERED
        val minHi = if (rendered) max(0.85f, profile.minHighlightCompression) else profile.minHighlightCompression
        val minLo = if (rendered) max(0.92f, profile.minShadowCompression) else profile.minShadowCompression
        val dh = p995 - key
        val cHi = if (dh > profile.highlightHeadroomStops) (profile.highlightHeadroomStops / dh).coerceIn(minHi, 1f) else 1f
        val ds = key - p1
        var cLo = if (ds > profile.shadowRangeStops) (profile.shadowRangeStops / ds).coerceIn(minLo, 1f) else 1f
        cLo = Filters.lerp(cLo, 1f, night) // night scenes keep their darkness

        val curve = if (rendered) ToneCurve.identity() else
            ToneCurve(profile.contrast, profile.shoulderStartStops, 3.2f, profile.blackFlare)

        // ---- 8. exposure: find E so that the key lands on the target through the curve
        var exposure = 0f
        if (!rendered) {
            var lo = -profile.maxExposureCutEv; var hi = profile.maxExposureLiftEv
            repeat(30) {
                val mid = (lo + hi) / 2f
                if (curve.display(key + mid) < target) lo = mid else hi = mid
            }
            exposure = (lo + hi) / 2f
        } else {
            // a rendered image was already exposed by the camera: nudge only when clearly off
            val cur = curve.display(key)
            val need = FastMath.log2(target / max(cur, 1e-4f))
            exposure = (need * 0.4f).coerceIn(-0.3f, 0.3f)
        }

        // ---- 9. per-cell tone offset and its guided-upsampling coefficients
        val delta = FloatArray(n)
        for (i in 0 until n) {
            val bsh = base[i] - key
            val t = 1f / (1f + exp(-bsh / 0.5f.toDouble()).toFloat())
            val c = cLo + (cHi - cLo) * t
            delta[i] = key + bsh * c - base[i] + exposure
        }
        val toneA = FloatArray(n); val toneB = FloatArray(n)
        Filters.guidedCoefficients(lg2, delta, gw, gh, 2, ConstantEps(0.02f), toneA, toneB, ws)

        val sharpen = profile.sharpenFor(info.sourceKind) * info.lens.sharpenScale
        val micro = if (rendered) 0f else profile.microContrast + 0.05f * (1f - cHi)
        val detailScale = sqrt(w.toDouble() * h / 12e6).toFloat().coerceIn(0.85f, 1.4f)

        val stats = SceneStats(
            sceneEv = ev, dayFactor = day, nightFactor = night, keyLog2 = key, p1Log2 = p1, p995Log2 = p995,
            dynamicRangeStops = p995 - p1, highlightCompression = cHi, shadowCompression = cLo,
            exposureEv = exposure, targetKey = target, clippedFraction = clippedFraction, faces = faces.size,
        )
        return RenderPlan(w, h, m, gw, gh, scale, toneA, toneB, curve, skinPrior, sharpen, micro, stats, wb, info.sourceKind, detailScale)
    }
}
