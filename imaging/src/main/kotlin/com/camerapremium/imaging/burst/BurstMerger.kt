package com.camerapremium.imaging.burst

import com.camerapremium.imaging.image.MergedYuv
import com.camerapremium.imaging.image.YuvFrame
import com.camerapremium.imaging.util.IndexedTask
import com.camerapremium.imaging.util.WorkerPool
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming multi-frame merge, in the spirit of burst photography pipelines (e.g. HDR+):
 *
 *  1. The first [referenceCandidates] frames are buffered and the sharpest becomes the reference
 *     ("lucky imaging" against hand shake).
 *  2. Every other frame is aligned to the reference with a coarse-to-fine tile search on a luma
 *     pyramid (per-tile integer translation, which also absorbs rolling-shutter wobble locally).
 *  3. Aligned frames are averaged with a per-2x2-block robustness weight derived from the expected
 *     noise: blocks that differ from the reference by more than noise allows (motion, occlusion,
 *     misalignment) fall back to the reference. This is what keeps moving people ghost-free.
 *  4. Frames that are much blurrier than the reference are dropped entirely.
 *
 * Only the reference, the accumulators and the incoming frame live in memory, so long night
 * bursts do not multiply memory use.
 */
class BurstMerger(
    private val pool: WorkerPool,
    private val referenceCandidates: Int = 3,
    private val params: Params = Params(),
) {
    class Params(
        /** Robustness: full weight below lo*sigma, zero above hi*sigma (sigma of 2x2 block mean diff). */
        val robustLo: Float = 2.0f,
        val robustHi: Float = 4.5f,
        /** Noise floor in 8-bit code values (HAL-denoised frames underestimate noise). */
        val noiseFloor: Float = 1.2f,
        /** Frames with sharpness below this fraction of the reference are rejected. */
        val minRelativeSharpness: Float = 0.72f,
        val tileSize: Int = 32,
    )

    private val pending = ArrayList<YuvFrame>()
    private var ref: YuvFrame? = null
    private var refPyramid: LumaPyramid? = null
    private var refSharpness = 0f
    private var noise: NoiseTable? = null

    private var accY = FloatArray(0)
    private var accU = FloatArray(0)
    private var accV = FloatArray(0)
    private var wBlock = FloatArray(0)
    private var merged = 0
    private var rejected = 0
    /** |block-mean difference| histograms per brightness bin (quantized x4) for temporal noise. */
    private val diffHist = Array(MergedYuv.TEMPORAL_BINS) { IntArray(512) }

    val framesMerged get() = merged
    val framesRejected get() = rejected

    /** Adds a frame. The merger takes ownership: do not reuse [frame] buffers afterwards. */
    fun add(frame: YuvFrame) {
        if (ref == null) {
            pending.add(frame)
            if (pending.size >= referenceCandidates) promoteReference()
            return
        }
        mergeFrame(frame)
    }

    fun finish(): MergedYuv {
        if (ref == null) {
            require(pending.isNotEmpty()) { "no frames" }
            promoteReference()
        }
        val r = ref!!
        val w = r.width; val h = r.height
        val bw = (w + 1) / 2
        val outY = accY
        for (y in 0 until h) {
            val brow = (y / 2) * bw
            val row = y * w
            for (x in 0 until w) outY[row + x] = accY[row + x] / wBlock[brow + x / 2]
        }
        val cn = r.chromaWidth * r.chromaHeight
        for (i in 0 until cn) {
            val wb = wBlock[i]
            accU[i] = accU[i] / wb
            accV[i] = accV[i] / wb
        }
        // Effective frame count per 8x8 block (= 4x4 chroma blocks).
        val mw = (w + 7) / 8; val mh = (h + 7) / 8
        val eff = FloatArray(mw * mh)
        val ch = r.chromaHeight
        for (my in 0 until mh) for (mx in 0 until mw) {
            var s = 0f; var c = 0
            for (cy in my * 4 until minOf(ch, my * 4 + 4)) for (cx in mx * 4 until minOf(bw, mx * 4 + 4)) {
                s += wBlock[cy * bw + cx]; c++
            }
            eff[my * mw + mx] = if (c > 0) s / c else 1f
        }
        val result = MergedYuv(w, h, outY, accU, accV, eff, merged, rejected, temporalSigma())
        ref = null; refPyramid = null; pending.clear()
        return result
    }

    // ------------------------------------------------------------------------------------

    /**
     * sigma per bin from the lower quartile of |difference| of 2x2 block means. Handheld subpixel
     * misalignment on texture (skin, fabric) only *adds* to the differences, so a low quantile is
     * far less biased than the median when most of the frame is textured (close-ups).
     */
    private fun temporalSigma(): FloatArray? {
        if (merged < 2) return null
        val out = FloatArray(MergedYuv.TEMPORAL_BINS) { -1f }
        for (b in out.indices) {
            val hst = diffHist[b]
            val total = hst.sum()
            if (total < 200) continue
            // interpolated inside the bucket: at low ISO the quartile falls in the first buckets
            var acc = 0; var q = 0f
            for (i in hst.indices) {
                if ((acc + hst[i]) * 4 >= total) { q = i + (total / 4f - acc) / hst[i].coerceAtLeast(1); break }
                acc += hst[i]
            }
            val q25 = q / 4f
            // block-mean difference sigma = sigma * sqrt(2) / 2 ; 25th percentile of |N(0,s)| = 0.3186 s
            out[b] = q25 / 0.3186f / 0.7071f
        }
        if (out.all { it < 0f }) return null
        NoiseTable.fillMissing(out, 1.5f)
        return out
    }

    private fun promoteReference() {
        var best = 0
        var bestS = -1f
        val pyramids = ArrayList<LumaPyramid>()
        for ((i, f) in pending.withIndex()) {
            val p = LumaPyramid.build(f)
            pyramids.add(p)
            val s = p.sharpness()
            if (s > bestS) { bestS = s; best = i }
        }
        val r = pending[best]
        ref = r
        refPyramid = pyramids[best]
        refSharpness = bestS
        noise = NoiseTable.estimate(r)
        initAccumulators(r)
        val others = ArrayList<YuvFrame>()
        for ((i, f) in pending.withIndex()) if (i != best) others.add(f)
        pending.clear()
        for (f in others) mergeFrame(f)
    }

    private fun initAccumulators(r: YuvFrame) {
        val w = r.width; val h = r.height
        accY = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * r.yStride
            for (x in 0 until w) accY[y * w + x] = (r.y[row + x].toInt() and 0xFF).toFloat()
        }
        val cw = r.chromaWidth; val ch = r.chromaHeight
        accU = FloatArray(cw * ch); accV = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            accU[y * cw + x] = (r.u[y * r.uvStride + x].toInt() and 0xFF) - 128f
            accV[y * cw + x] = (r.v[y * r.uvStride + x].toInt() and 0xFF) - 128f
        }
        wBlock = FloatArray(cw * ch) { 1f }
        merged = 1
    }

    private fun mergeFrame(f: YuvFrame) {
        val r = ref!!
        if (f.width != r.width || f.height != r.height) { rejected++; return }
        val pyr = LumaPyramid.build(f)
        if (pyr.sharpness() < refSharpness * params.minRelativeSharpness) { rejected++; return }
        val field = align(refPyramid!!, pyr, r, f)
        mergeAligned(r, f, field)
        merged++
    }

    // ------------------------------------------------------------------------------ alignment

    /** Per-tile integer offsets (dx, dy) at full resolution such that alt(x+dx, y+dy) ~ ref(x, y). */
    class OffsetField(val tilesX: Int, val tilesY: Int, val tile: Int) {
        val dx = IntArray(tilesX * tilesY)
        val dy = IntArray(tilesX * tilesY)
    }

    private fun align(refP: LumaPyramid, altP: LumaPyramid, ref: YuvFrame, alt: YuvFrame): OffsetField {
        // Coarse-to-fine over pyramid levels 3..1 (each /4), then refine on full resolution.
        var prev: OffsetField? = null
        for (level in LumaPyramid.LEVELS downTo 1) {
            val rl = refP.levels[level - 1]; val al = altP.levels[level - 1]
            val tile = if (level >= 3) 8 else 16
            val tx = (rl.w + tile - 1) / tile; val ty = (rl.h + tile - 1) / tile
            val field = OffsetField(tx, ty, tile)
            val prevF = prev
            val radius = 4
            pool.forEach(ty, IndexedTask { tyi, _ ->
                for (txi in 0 until tx) {
                    val x0 = txi * tile; val y0 = tyi * tile
                    var bestDx = 0; var bestDy = 0; var bestCost = Long.MAX_VALUE
                    // candidate predictions from the coarser level (own tile + 2 nearest neighbours)
                    val cand = predictions(prevF, x0 + tile / 2, y0 + tile / 2, 4)
                    var ci = 0
                    while (ci < cand.size) {
                        val px = cand[ci]; val py = cand[ci + 1]; ci += 2
                        for (sy in -radius..radius) for (sx in -radius..radius) {
                            val c = sadPlane(rl, al, x0, y0, tile, px + sx, py + sy, bestCost)
                            if (c < bestCost) { bestCost = c; bestDx = px + sx; bestDy = py + sy }
                        }
                    }
                    field.dx[tyi * tx + txi] = bestDx
                    field.dy[tyi * tx + txi] = bestDy
                }
            })
            prev = field
        }
        // Full-resolution refinement on bytes, +/-1 px around the upsampled prediction.
        val tile = params.tileSize
        val tx = (ref.width + tile - 1) / tile; val ty = (ref.height + tile - 1) / tile
        val field = OffsetField(tx, ty, tile)
        val prevF = prev!!
        pool.forEach(ty, IndexedTask { tyi, _ ->
            for (txi in 0 until tx) {
                val x0 = txi * tile; val y0 = tyi * tile
                var bestDx = 0; var bestDy = 0; var bestCost = Long.MAX_VALUE
                val cand = predictions(prevF, x0 + tile / 2, y0 + tile / 2, 4)
                var ci = 0
                while (ci < cand.size) {
                    val px = cand[ci]; val py = cand[ci + 1]; ci += 2
                    for (sy in -1..1) for (sx in -1..1) {
                        val c = sadBytes(ref, alt, x0, y0, tile, px + sx, py + sy, bestCost)
                        if (c < bestCost) { bestCost = c; bestDx = px + sx; bestDy = py + sy }
                    }
                }
                field.dx[tyi * tx + txi] = bestDx
                field.dy[tyi * tx + txi] = bestDy
            }
        })
        return field
    }

    /** Candidate offsets (scaled by [scale]) from the coarser field around a point given in coarse coords*scale. */
    private fun predictions(prev: OffsetField?, cx: Int, cy: Int, scale: Int): IntArray {
        if (prev == null) return intArrayOf(0, 0)
        // point (cx, cy) is in the finer level's coordinates; coarse coords = /scale
        val fx = cx.toFloat() / scale / prev.tile - 0.5f
        val fy = cy.toFloat() / scale / prev.tile - 0.5f
        val tx0 = fx.toInt().coerceIn(0, prev.tilesX - 1)
        val ty0 = fy.toInt().coerceIn(0, prev.tilesY - 1)
        val tx1 = (if (fx - tx0 > 0.5f) tx0 + 1 else tx0 - 1).coerceIn(0, prev.tilesX - 1)
        val ty1 = (if (fy - ty0 > 0.5f) ty0 + 1 else ty0 - 1).coerceIn(0, prev.tilesY - 1)
        val i0 = ty0 * prev.tilesX + tx0
        val i1 = ty0 * prev.tilesX + tx1
        val i2 = ty1 * prev.tilesX + tx0
        return intArrayOf(
            prev.dx[i0] * scale, prev.dy[i0] * scale,
            prev.dx[i1] * scale, prev.dy[i1] * scale,
            prev.dx[i2] * scale, prev.dy[i2] * scale,
        )
    }

    private fun sadPlane(r: Plane, a: Plane, x0: Int, y0: Int, tile: Int, dx: Int, dy: Int, bound: Long): Long {
        var s = 0L
        val x1 = minOf(r.w, x0 + tile); val y1 = minOf(r.h, y0 + tile)
        for (y in y0 until y1) {
            val ay = (y + dy).coerceIn(0, a.h - 1) * a.w
            val ry = y * r.w
            for (x in x0 until x1) {
                val ax = (x + dx).coerceIn(0, a.w - 1)
                s += abs(r.data[ry + x] - a.data[ay + ax])
            }
            if (s >= bound) return s
        }
        return s
    }

    private fun sadBytes(r: YuvFrame, a: YuvFrame, x0: Int, y0: Int, tile: Int, dx: Int, dy: Int, bound: Long): Long {
        var s = 0L
        val x1 = minOf(r.width, x0 + tile); val y1 = minOf(r.height, y0 + tile)
        val maxX = a.width - 1; val maxY = a.height - 1
        var y = y0
        while (y < y1) { // 2x subsampled rows/cols: plenty for integer offsets, 4x cheaper
            val ay = (y + dy).coerceIn(0, maxY) * a.yStride
            val ry = y * r.yStride
            var x = x0
            while (x < x1) {
                val ax = (x + dx).coerceIn(0, maxX)
                s += abs((r.y[ry + x].toInt() and 0xFF) - (a.y[ay + ax].toInt() and 0xFF))
                x += 2
            }
            if (s >= bound) return s
            y += 2
        }
        return s
    }

    // ------------------------------------------------------------------------------ merging

    private fun mergeAligned(r: YuvFrame, a: YuvFrame, field: OffsetField) {
        val w = r.width; val h = r.height
        val cw = r.chromaWidth; val ch = r.chromaHeight
        val tile = field.tile
        val nt = noise!!
        val lo = params.robustLo; val hi = params.robustHi
        val floor = params.noiseFloor
        val localHists = Array(pool.size) { Array(MergedYuv.TEMPORAL_BINS) { IntArray(512) } }
        pool.forEach(ch, IndexedTask { by, slot ->
            val hist = localHists[slot]
            val y0 = by * 2
            val tyi = (y0 / tile).coerceAtMost(field.tilesY - 1)
            for (bx in 0 until cw) {
                val x0 = bx * 2
                val ti = tyi * field.tilesX + (x0 / tile).coerceAtMost(field.tilesX - 1)
                val dx = field.dx[ti]; val dy = field.dy[ti]
                // 2x2 block means
                var rs = 0f; var asum = 0f; var n = 0
                for (yy in y0 until minOf(h, y0 + 2)) {
                    val ay = (yy + dy).coerceIn(0, a.height - 1) * a.yStride
                    val ry = yy * r.yStride
                    for (xx in x0 until minOf(w, x0 + 2)) {
                        rs += (r.y[ry + xx].toInt() and 0xFF)
                        asum += (a.y[ay + (xx + dx).coerceIn(0, a.width - 1)].toInt() and 0xFF)
                        n++
                    }
                }
                rs /= n; asum /= n
                if ((bx and 3) == 0) {
                    val q = (abs(rs - asum) * 4f).toInt().coerceAtMost(511)
                    hist[(rs / 256f * MergedYuv.TEMPORAL_BINS).toInt().coerceIn(0, MergedYuv.TEMPORAL_BINS - 1)][q]++
                }
                val sigma = nt.sigma(rs).coerceAtLeast(floor) * 0.7071f // std of a 4-px mean difference
                // chroma: offset halved (rounded toward the nearest chroma sample). Chroma is part of
                // the robustness test, otherwise an object with the same luma as the background
                // (red shirt on green grass) would leave a color ghost.
                val cx = (bx + Math.floorDiv(dx + 1, 2)).coerceIn(0, a.chromaWidth - 1)
                val cy = (by + Math.floorDiv(dy + 1, 2)).coerceIn(0, a.chromaHeight - 1)
                val au = (a.u[cy * a.uvStride + cx].toInt() and 0xFF) - 128f
                val av = (a.v[cy * a.uvStride + cx].toInt() and 0xFF) - 128f
                val ru = (r.u[by * r.uvStride + bx].toInt() and 0xFF) - 128f
                val rv = (r.v[by * r.uvStride + bx].toInt() and 0xFF) - 128f
                val sc = nt.chromaSigma.coerceAtLeast(floor) * 1.4142f
                val d = maxOf(abs(rs - asum) / sigma, maxOf(abs(ru - au), abs(rv - av)) / sc)
                val wgt = if (d <= lo) 1f else if (d >= hi) 0f else (hi - d) / (hi - lo)
                if (wgt <= 0f) continue
                for (yy in y0 until minOf(h, y0 + 2)) {
                    val ay = (yy + dy).coerceIn(0, a.height - 1) * a.yStride
                    val orow = yy * w
                    for (xx in x0 until minOf(w, x0 + 2)) {
                        accY[orow + xx] += wgt * (a.y[ay + (xx + dx).coerceIn(0, a.width - 1)].toInt() and 0xFF)
                    }
                }
                val ci = by * cw + bx
                accU[ci] += wgt * au
                accV[ci] += wgt * av
                wBlock[ci] += wgt
            }
        })
        for (lh in localHists) for (b in lh.indices) { val src = lh[b]; val dst = diffHist[b]; for (k in src.indices) dst[k] += src[k] }
    }

    // ------------------------------------------------------------------------------ helpers

    class Plane(val w: Int, val h: Int, val data: IntArray)

    /** Luma pyramid: levels[0] = /4, levels[1] = /16, levels[2] = /64 (box averages). */
    class LumaPyramid(val levels: Array<Plane>) {
        /** Gradient energy on the /4 level: robust proxy of motion blur. */
        fun sharpness(): Float {
            val p = levels[0]
            var s = 0.0
            for (y in 1 until p.h - 1) for (x in 1 until p.w - 1) {
                val i = y * p.w + x
                val gx = p.data[i + 1] - p.data[i - 1]
                val gy = p.data[i + p.w] - p.data[i - p.w]
                s += gx * gx + gy * gy
            }
            return (s / ((p.w - 2).coerceAtLeast(1) * (p.h - 2).coerceAtLeast(1))).toFloat()
        }

        companion object {
            const val LEVELS = 3

            fun build(f: YuvFrame): LumaPyramid {
                val l1 = downFromBytes(f, 4)
                val l2 = down(l1, 4)
                val l3 = down(l2, 4)
                return LumaPyramid(arrayOf(l1, l2, l3))
            }

            private fun downFromBytes(f: YuvFrame, k: Int): Plane {
                val w = f.width / k; val h = f.height / k
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    var s = 0
                    for (yy in 0 until k) {
                        val row = (y * k + yy) * f.yStride + x * k
                        for (xx in 0 until k) s += f.y[row + xx].toInt() and 0xFF
                    }
                    out[y * w + x] = s // keep the sum (x16): more precision, SAD scale irrelevant
                }
                return Plane(w, h, out)
            }

            private fun down(p: Plane, k: Int): Plane {
                val w = (p.w / k).coerceAtLeast(1); val h = (p.h / k).coerceAtLeast(1)
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    var s = 0
                    for (yy in 0 until k) for (xx in 0 until k) {
                        s += p.data[(y * k + yy).coerceAtMost(p.h - 1) * p.w + (x * k + xx).coerceAtMost(p.w - 1)]
                    }
                    out[y * w + x] = s / (k * k)
                }
                return Plane(w, h, out)
            }
        }
    }

    /**
     * Noise level function of a frame's luma: sigma per brightness bin, estimated from the
     * second-difference residual of flat patches (low percentile rejects texture).
     */
    class NoiseTable(private val sig: FloatArray, val chromaSigma: Float) {
        fun sigma(level: Float): Float {
            val f = (level / 256f * BINS - 0.5f).coerceIn(0f, (BINS - 1).toFloat())
            val i = f.toInt(); val t = f - i
            val j = (i + 1).coerceAtMost(BINS - 1)
            return sig[i] + (sig[j] - sig[i]) * t
        }

        companion object {
            const val BINS = 8

            fun estimate(f: YuvFrame): NoiseTable {
                val bins = Array(BINS) { ArrayList<Float>() }
                val p = 8
                val step = maxOf(p, (f.width * f.height / 6000.0).let { sqrt(it).toInt() })
                var y = 1
                while (y + p + 1 < f.height) {
                    var x = 1
                    while (x + p + 1 < f.width) {
                        var sum = 0f; var sumSq = 0f; var n = 0; var mean = 0f
                        for (yy in y until y + p) for (xx in x until x + p) mean += f.luma(xx, yy)
                        mean /= (p * p)
                        for (yy in y until y + p) for (xx in x until x + p) {
                            val c = f.luma(xx, yy)
                            val dh = 2 * c - f.luma(xx - 1, yy) - f.luma(xx + 1, yy)
                            val dv = 2 * c - f.luma(xx, yy - 1) - f.luma(xx, yy + 1)
                            sum += (dh + dv).toFloat(); sumSq += (dh * dh + dv * dv).toFloat(); n += 2
                        }
                        val v = sumSq / n
                        val bin = (mean / 256f * BINS).toInt().coerceIn(0, BINS - 1)
                        if (mean > 4f && mean < 250f) bins[bin].add(v / 6f)
                        x += step
                    }
                    y += step
                }
                val out = FloatArray(BINS) { -1f }
                for (i in 0 until BINS) {
                    val l = bins[i]
                    if (l.size >= 6) { l.sort(); out[i] = sqrt(l[(l.size * 0.2f).toInt()]) }
                }
                fillMissing(out, 1.5f)
                return NoiseTable(out, chromaNoise(f))
            }

            /** Single chroma sigma (U and V pooled) from second differences on the half-res planes. */
            fun chromaNoise(f: YuvFrame): Float {
                val vals = ArrayList<Float>()
                val p = 8
                val cw = f.chromaWidth; val ch = f.chromaHeight
                val step = maxOf(p, sqrt(cw * ch / 3000.0).toInt())
                var y = 1
                while (y + p + 1 < ch) {
                    var x = 1
                    while (x + p + 1 < cw) {
                        var s = 0f; var n = 0
                        for (yy in y until y + p) for (xx in x until x + p) {
                            for (plane in 0..1) {
                                val t = if (plane == 0) f.u else f.v
                                val c = t[yy * f.uvStride + xx].toInt() and 0xFF
                                val l = t[yy * f.uvStride + xx - 1].toInt() and 0xFF
                                val r = t[yy * f.uvStride + xx + 1].toInt() and 0xFF
                                val d = 2 * c - l - r
                                s += (d * d).toFloat(); n++
                            }
                        }
                        vals.add(s / n / 6f)
                        x += step
                    }
                    y += step
                }
                if (vals.isEmpty()) return 1.5f
                vals.sort()
                return sqrt(vals[(vals.size * 0.2f).toInt()])
            }

            fun fillMissing(a: FloatArray, default: Float) {
                if (a.all { it < 0f }) { a.fill(default); return }
                for (i in a.indices) if (a[i] < 0f) {
                    var l = i - 1; while (l >= 0 && a[l] < 0f) l--
                    var r = i + 1; while (r < a.size && a[r] < 0f) r++
                    a[i] = when {
                        l >= 0 && r < a.size -> (a[l] + a[r]) / 2f
                        l >= 0 -> a[l]
                        else -> a[r]
                    }
                }
            }
        }
    }
}
