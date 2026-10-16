package com.camerapremium.imaging.util

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, allocation-conscious image filters on row-major float planes.
 * All filters clamp at borders (edge replication), which is what the tile halos assume.
 */
object Filters {

    /**
     * Box mean with radius r ((2r+1)^2 window) in O(1) per pixel via running sums.
     * [tmp] must hold at least w*h floats. src and dst may not alias tmp; src and dst may alias.
     */
    fun boxMean(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        if (r <= 0) {
            if (src !== dst) System.arraycopy(src, 0, dst, 0, w * h)
            return
        }
        val norm = 1f / (2 * r + 1)
        // horizontal pass -> tmp
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * norm
                val add = (x + r + 1).coerceAtMost(w - 1)
                val sub = (x - r).coerceAtLeast(0)
                sum += src[row + add] - src[row + sub]
            }
        }
        // vertical pass -> dst, column-wise with a running sum per column (cache friendly by rows)
        val colSum = FloatArray(w)
        for (k in -r..r) {
            val row = k.coerceIn(0, h - 1) * w
            for (x in 0 until w) colSum[x] += tmp[row + x]
        }
        for (y in 0 until h) {
            val row = y * w
            val addRow = (y + r + 1).coerceAtMost(h - 1) * w
            val subRow = (y - r).coerceAtLeast(0) * w
            for (x in 0 until w) {
                dst[row + x] = colSum[x] * norm
                colSum[x] += tmp[addRow + x] - tmp[subRow + x]
            }
        }
    }

    /**
     * Guided filter (He et al.) of [p] guided by [guide]. When guide === p it is the classic
     * edge-preserving self-guided smoother. [epsOf] returns the regularization for a local
     * guide mean (lets the caller plug in a signal-dependent noise model).
     * Outputs the linear coefficients (a, b) averaged over the window into [outA]/[outB] so the
     * caller can evaluate q = a*I + b, possibly at another resolution (fast guided upsampling).
     */
    fun guidedCoefficients(
        guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int,
        eps: EpsModel, outA: FloatArray, outB: FloatArray, ws: GuidedWorkspace,
    ) {
        val n = w * h
        ws.ensure(n)
        val meanI = ws.t1; val meanP = ws.t2; val corrIp = ws.t3; val corrII = ws.t4; val tmp = ws.t5
        boxMean(guide, meanI, w, h, r, tmp)
        val same = guide === p
        if (!same) boxMean(p, meanP, w, h, r, tmp)
        for (i in 0 until n) corrII[i] = guide[i] * guide[i]
        boxMean(corrII, corrII, w, h, r, tmp)
        if (!same) {
            for (i in 0 until n) corrIp[i] = guide[i] * p[i]
            boxMean(corrIp, corrIp, w, h, r, tmp)
        }
        for (i in 0 until n) {
            val mi = meanI[i]
            val varI = (corrII[i] - mi * mi).coerceAtLeast(0f)
            val mp = if (same) mi else meanP[i]
            val cov = if (same) varI else corrIp[i] - mi * mp
            val a = cov / (varI + eps.eps(mi, i))
            outA[i] = a
            outB[i] = mp - a * mi
        }
        boxMean(outA, outA, w, h, r, tmp)
        boxMean(outB, outB, w, h, r, tmp)
    }

    /** Applies q = a*I + b in place into [out]. */
    fun applyCoefficients(guide: FloatArray, a: FloatArray, b: FloatArray, out: FloatArray, n: Int) {
        for (i in 0 until n) out[i] = a[i] * guide[i] + b[i]
    }

    /** Separable 5-tap binomial-ish gaussian (sigma ~0.9-1.0). */
    fun gauss5(src: FloatArray, dst: FloatArray, w: Int, h: Int, tmp: FloatArray) {
        val k0 = 0.0625f; val k1 = 0.25f; val k2 = 0.375f
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val xm2 = (x - 2).coerceAtLeast(0); val xm1 = (x - 1).coerceAtLeast(0)
                val xp1 = (x + 1).coerceAtMost(w - 1); val xp2 = (x + 2).coerceAtMost(w - 1)
                tmp[row + x] = k0 * (src[row + xm2] + src[row + xp2]) + k1 * (src[row + xm1] + src[row + xp1]) + k2 * src[row + x]
            }
        }
        for (y in 0 until h) {
            val rm2 = (y - 2).coerceAtLeast(0) * w; val rm1 = (y - 1).coerceAtLeast(0) * w
            val rp1 = (y + 1).coerceAtMost(h - 1) * w; val rp2 = (y + 2).coerceAtMost(h - 1) * w
            val row = y * w
            for (x in 0 until w) {
                dst[row + x] = k0 * (tmp[rm2 + x] + tmp[rp2 + x]) + k1 * (tmp[rm1 + x] + tmp[rp1 + x]) + k2 * tmp[row + x]
            }
        }
    }

    /** Separable gaussian with arbitrary sigma (radius ceil(2.5 sigma), max 3). */
    fun gauss(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigma: Float, tmp: FloatArray) {
        val r = kotlin.math.ceil(2.5f * sigma).toInt().coerceIn(1, 3)
        val k = FloatArray(2 * r + 1)
        var sum = 0f
        for (i in -r..r) { k[i + r] = kotlin.math.exp(-(i * i) / (2f * sigma * sigma)); sum += k[i + r] }
        for (i in k.indices) k[i] /= sum
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0f
                for (j in -r..r) a += k[j + r] * src[row + (x + j).coerceIn(0, w - 1)]
                tmp[row + x] = a
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0f
                for (j in -r..r) a += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]
                dst[row + x] = a
            }
        }
    }

    /** Integer-factor box downsample (averaging). Output size ceil(w/f) x ceil(h/f). */
    fun downsample(src: FloatArray, w: Int, h: Int, f: Int, dst: FloatArray) {
        val ow = (w + f - 1) / f
        val oh = (h + f - 1) / f
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var s = 0f; var c = 0
            val y1 = minOf(h, (oy + 1) * f); val x1 = minOf(w, (ox + 1) * f)
            for (y in oy * f until y1) { val row = y * w; for (x in ox * f until x1) { s += src[row + x]; c++ } }
            dst[oy * ow + ox] = s / c
        }
    }

    /** Bilinear sample of a low-res grid whose cell centers sit at ((i+0.5)*scale) in full-res. */
    fun sampleBilinear(grid: FloatArray, gw: Int, gh: Int, gx: Float, gy: Float): Float {
        val fx = gx.coerceIn(0f, (gw - 1).toFloat())
        val fy = gy.coerceIn(0f, (gh - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(gw - 1); val y1 = (y0 + 1).coerceAtMost(gh - 1)
        val tx = fx - x0; val ty = fy - y0
        val a = grid[y0 * gw + x0]; val b = grid[y0 * gw + x1]
        val c = grid[y1 * gw + x0]; val d = grid[y1 * gw + x1]
        val top = a + (b - a) * tx
        return top + (c + (d - c) * tx - top) * ty
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/** Regularization model for the guided filter. */
fun interface EpsModel {
    fun eps(meanGuide: Float, index: Int): Float
}

class ConstantEps(private val e: Float) : EpsModel {
    override fun eps(meanGuide: Float, index: Int): Float = e
}

class GuidedWorkspace {
    var t1 = FloatArray(0); var t2 = FloatArray(0); var t3 = FloatArray(0)
    var t4 = FloatArray(0); var t5 = FloatArray(0)

    fun ensure(n: Int) {
        if (t1.size < n) {
            t1 = FloatArray(n); t2 = FloatArray(n); t3 = FloatArray(n); t4 = FloatArray(n); t5 = FloatArray(n)
        }
    }
}

/** Minimal fixed-size worker pool used by the pipeline (no coroutine dependency). */
class WorkerPool(threads: Int = defaultThreads()) : AutoCloseable {
    val size: Int = threads.coerceAtLeast(1)
    private val counter = AtomicInteger()
    private val executor: ExecutorService = Executors.newFixedThreadPool(size, object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "imaging-" + counter.incrementAndGet())
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY - 1
            return t
        }
    })

    /** Runs [body] for each index in [0, n) distributed dynamically; [body] gets (index, workerSlot). */
    fun forEach(n: Int, body: IndexedTask) {
        if (n <= 0) return
        val next = AtomicInteger()
        val workers = minOf(size, n)
        val tasks = ArrayList<Callable<Unit>>(workers)
        for (slot in 0 until workers) {
            tasks.add(object : Callable<Unit> {
                override fun call() {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= n) break
                        body.run(i, slot)
                    }
                }
            })
        }
        val futures = executor.invokeAll(tasks)
        for (f in futures) f.get() // propagate exceptions
    }

    override fun close() { executor.shutdown() }

    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    }
}

fun interface IndexedTask {
    fun run(index: Int, slot: Int)
}
