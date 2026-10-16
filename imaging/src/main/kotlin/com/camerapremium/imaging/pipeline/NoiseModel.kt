package com.camerapremium.imaging.pipeline

import kotlin.math.sqrt

/**
 * Noise level function measured on the image itself, in the rendered (Oklab) domain, per
 * lightness bin. Measuring after tone mapping means the model already includes the shadow lift
 * and whatever denoising the camera did, so the same code adapts to a clean system JPEG (little
 * NR applied) and to a noisy single frame (more NR).
 */
class NoiseModel(val sigmaL: FloatArray, val sigmaC: FloatArray) {

    fun lumaSigma(l: Float): Float = interp(sigmaL, l)
    fun chromaSigma(l: Float): Float = interp(sigmaC, l)

    private fun interp(t: FloatArray, l: Float): Float {
        val f = (l * BINS - 0.5f).coerceIn(0f, (BINS - 1).toFloat())
        val i = f.toInt(); val j = (i + 1).coerceAtMost(BINS - 1)
        return t[i] + (t[j] - t[i]) * (f - i)
    }

    override fun toString(): String =
        "sigmaL=" + sigmaL.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) } +
            " sigmaC=" + sigmaC.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }

    /** Accumulates patch measurements and turns them into a model. */
    class Builder {
        private val l = Array(BINS) { ArrayList<Float>() }
        private val c = Array(BINS) { ArrayList<Float>() }

        /** Patch of size p x p (row-major) of L/A/B values. */
        @Synchronized
        fun addPatch(L: FloatArray, A: FloatArray, B: FloatArray, p: Int) {
            var mean = 0f
            for (i in 0 until p * p) mean += L[i]
            mean /= (p * p)
            if (mean < 0.02f || mean > 0.98f) return
            var slh = 0f; var slv = 0f; var sc = 0f; var n = 0
            for (y in 1 until p - 1) for (x in 1 until p - 1) {
                val i = y * p + x
                val dh = 2 * L[i] - L[i - 1] - L[i + 1]
                val dv = 2 * L[i] - L[i - p] - L[i + p]
                slh += dh * dh; slv += dv * dv
                val ah = 2 * A[i] - A[i - 1] - A[i + 1]
                val bh = 2 * B[i] - B[i - 1] - B[i + 1]
                val av = 2 * A[i] - A[i - p] - A[i + p]
                val bv = 2 * B[i] - B[i - p] - B[i + p]
                sc += ah * ah + bh * bh + av * av + bv * bv
                n++
            }
            // Noise is isotropic; hair, fur, fabric and foliage usually are not. Strongly
            // oriented patches are texture and must not be mistaken for noise.
            val aniso = kotlin.math.abs(slh - slv) / (slh + slv + 1e-12f)
            if (aniso > 0.3f) return
            val sl = slh + slv
            // second-difference of white noise has variance 6 sigma^2
            val varL = sl / (2 * n) / 6f
            val varC = sc / (4 * n) / 6f
            // Chroma noise after demosaicing/HAL NR is spatially correlated (blotchy): measure it
            // also on 2x2-binned values and keep the larger equivalent full-res sigma.
            val q = p / 2
            var sb = 0f; var nb = 0
            for (y in 1 until q - 1) for (x in 1 until q - 1) {
                val ca = bin(A, p, x, y); val cb = bin(B, p, x, y)
                val ah = 2 * ca - bin(A, p, x - 1, y) - bin(A, p, x + 1, y)
                val av = 2 * ca - bin(A, p, x, y - 1) - bin(A, p, x, y + 1)
                val bh = 2 * cb - bin(B, p, x - 1, y) - bin(B, p, x + 1, y)
                val bv = 2 * cb - bin(B, p, x, y - 1) - bin(B, p, x, y + 1)
                sb += ah * ah + av * av + bh * bh + bv * bv
                nb++
            }
            val varCb = if (nb > 0) sb / (4 * nb) / 6f * 4f else 0f
            val bin = (mean * BINS).toInt().coerceIn(0, BINS - 1)
            l[bin].add(varL)
            c[bin].add(maxOf(varC, varCb))
        }

        private fun bin(t: FloatArray, p: Int, x: Int, y: Int): Float {
            val i = (y * 2) * p + x * 2
            return (t[i] + t[i + 1] + t[i + p] + t[i + p + 1]) * 0.25f
        }

        fun build(): NoiseModel = NoiseModel(reduce(l), reduce(c))

        private fun reduce(bins: Array<ArrayList<Float>>): FloatArray {
            val out = FloatArray(BINS) { -1f }
            for (i in 0 until BINS) {
                val v = bins[i]
                if (v.size >= 8) {
                    v.sort()
                    // lower tail: flattest patches. For pure noise the 10th percentile of these
                    // variance estimates sits at ~0.8x the true variance (chi-square, ~100 dof).
                    out[i] = sqrt(v[(v.size * 0.1f).toInt()] / 0.8f)
                }
            }
            if (out.all { it < 0f }) { out.fill(0f); return out }
            for (i in 0 until BINS) if (out[i] < 0f) {
                var lo = i - 1; while (lo >= 0 && out[lo] < 0f) lo--
                var hi = i + 1; while (hi < BINS && out[hi] < 0f) hi++
                out[i] = when {
                    lo >= 0 && hi < BINS -> (out[lo] + out[hi]) / 2f
                    lo >= 0 -> out[lo]
                    else -> out[hi]
                }
            }
            // light smoothing across bins
            val s = out.copyOf()
            for (i in 0 until BINS) {
                val a = out[(i - 1).coerceAtLeast(0)]; val b = out[(i + 1).coerceAtMost(BINS - 1)]
                s[i] = 0.25f * a + 0.5f * out[i] + 0.25f * b
            }
            return s
        }
    }

    companion object {
        const val BINS = 10
    }
}
