package com.camerapremium.imaging.metrics

import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.image.ArgbImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Objective, alignment-insensitive image statistics. Used by the tests and by the comparison
 * protocol (Galaxy vs iPhone photos of the same scene are never pixel-aligned, so we compare
 * distributions and user-selected regions instead of per-pixel differences).
 */
object ImageMetrics {

    class Region(val x: Float, val y: Float, val w: Float, val h: Float) {
        companion object {
            val FULL = Region(0f, 0f, 1f, 1f)
            fun parse(s: String): Region {
                val p = s.split(",").map { it.trim().toFloat() }
                require(p.size == 4) { "region must be x,y,w,h (normalized 0..1)" }
                return Region(p[0], p[1], p[2], p[3])
            }
        }
    }

    class Stats(
        val meanLab: DoubleArray,
        val lPercentiles: DoubleArray, // 1, 5, 25, 50, 75, 95, 99 (L*)
        /** Any channel at the top code value (includes legitimately saturated colors, e.g. blue sky). */
        val clippedHighlights: Double,
        /** All channels near white: highlight detail actually lost. */
        val whiteClipped: Double,
        val crushedShadows: Double,
        val meanChroma: Double,
        val chromaByHue: DoubleArray, // 8 sectors of 45 degrees (CIELAB hue)
        val neutralCast: DoubleArray, // mean a*, b* of near-neutral pixels
        val gradientEnergy: Double,   // mean |grad L*| (sharpness proxy)
        val midBandEnergy: Double,    // texture at ~3-8 px scale
        val noiseSigma: Double,       // L* noise from flat patches
        val overshootIndex: Double,   // ringing next to strong edges (halos)
    ) {
        fun format(): String = buildString {
            append("L*a*b* medio: %.1f %.1f %.1f\n".format(meanLab[0], meanLab[1], meanLab[2]))
            append("L* p1/p5/p25/p50/p75/p95/p99: ").append(lPercentiles.joinToString(" ") { "%.1f".format(it) }).append('\n')
            append("branco estourado: %.2f%%  canal saturado: %.2f%%  sombras esmagadas: %.2f%%\n".format(whiteClipped * 100, clippedHighlights * 100, crushedShadows * 100))
            append("croma medio: %.2f  por matiz(0..315): ".format(meanChroma))
            append(chromaByHue.joinToString(" ") { "%.1f".format(it) }).append('\n')
            append("dominante em neutros a*/b*: %.2f / %.2f\n".format(neutralCast[0], neutralCast[1]))
            append("energia de gradiente: %.3f  textura media: %.3f  ruido L*: %.3f  overshoot: %.4f\n"
                .format(gradientEnergy, midBandEnergy, noiseSigma, overshootIndex))
        }
    }

    fun labImage(img: ArgbImage, r: Region = Region.FULL): Triple<Array<DoubleArray>, Int, Int> {
        val x0 = (r.x * img.width).toInt().coerceIn(0, img.width - 1)
        val y0 = (r.y * img.height).toInt().coerceIn(0, img.height - 1)
        val w = (r.w * img.width).toInt().coerceIn(1, img.width - x0)
        val h = (r.h * img.height).toInt().coerceIn(1, img.height - y0)
        val out = Array(w * h) { DoubleArray(3) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = img.pixels[(y0 + y) * img.width + x0 + x]
            val lr = ColorMath.SRGB8_TO_LINEAR[(c shr 16) and 0xFF]
            val lg = ColorMath.SRGB8_TO_LINEAR[(c shr 8) and 0xFF]
            val lb = ColorMath.SRGB8_TO_LINEAR[c and 0xFF]
            out[y * w + x] = ColorMath.linearSrgbToLab(lr, lg, lb)
        }
        return Triple(out, w, h)
    }

    fun stats(img: ArgbImage, region: Region = Region.FULL): Stats {
        val (lab, w, h) = labImage(img, region)
        val n = w * h
        val mean = DoubleArray(3)
        val ls = DoubleArray(n)
        var clipped = 0; var white = 0; var crushed = 0
        var chroma = 0.0
        val hueSum = DoubleArray(8); val hueCnt = IntArray(8)
        var na = 0.0; var nb = 0.0; var nn = 0
        val x0 = (region.x * img.width).toInt(); val y0 = (region.y * img.height).toInt()
        for (i in 0 until n) {
            val p = lab[i]
            mean[0] += p[0]; mean[1] += p[1]; mean[2] += p[2]
            ls[i] = p[0]
            val c = img.pixels[(y0 + i / w) * img.width + x0 + i % w]
            val mx = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            val mn = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            if (mx >= 254) clipped++
            if (mn >= 252) white++
            if (mx <= 2 && mn <= 2) crushed++
            val ch = sqrt(p[1] * p[1] + p[2] * p[2])
            chroma += ch
            if (ch > 5) {
                var hd = Math.toDegrees(atan2(p[2], p[1])); if (hd < 0) hd += 360.0
                val s = (hd / 45).toInt().coerceIn(0, 7)
                hueSum[s] += ch; hueCnt[s]++
            } else if (p[0] > 30 && p[0] < 95) { na += p[1]; nb += p[2]; nn++ }
        }
        for (k in 0 until 3) mean[k] /= n
        val sorted = ls.copyOf(); sorted.sort()
        val pct = doubleArrayOf(0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99).map { sorted[(it * (n - 1)).toInt()] }.toDoubleArray()
        val hue = DoubleArray(8) { if (hueCnt[it] > 0) hueSum[it] / hueCnt[it] else 0.0 }

        // gradient energy, mid-band energy, overshoot
        var grad = 0.0; var gcount = 0
        var mid = 0.0
        var over = 0.0; var edgeCount = 0
        for (y in 4 until h - 4) for (x in 4 until w - 4) {
            val i = y * w + x
            val gx = ls[i + 1] - ls[i - 1]; val gy = ls[i + w] - ls[i - w]
            val g = sqrt(gx * gx + gy * gy)
            grad += g; gcount++
            var box = 0.0
            for (dy in -3..3) for (dx in -3..3) box += ls[i + dy * w + dx]
            box /= 49.0
            mid += abs(ls[i] - box)
            if (g > 20) {
                // strong edge: look 3 px to each side along the dominant axis for overshoot past the plateau
                edgeCount++
                val horizontal = abs(gx) > abs(gy)
                val step = if (horizontal) 1 else w
                val a1 = ls[i - step]; val a3 = ls[i - 3 * step]; val a4 = ls[i - 4 * step]
                val b1 = ls[i + step]; val b3 = ls[i + 3 * step]; val b4 = ls[i + 4 * step]
                // overshoot = how much the near-edge sample exceeds the far plateau on the same side
                val lowSide = if (a4 < b4) Triple(a1, a3, a4) else Triple(b1, b3, b4)
                val highSide = if (a4 < b4) Triple(b1, b3, b4) else Triple(a1, a3, a4)
                over += maxOf(0.0, lowSide.third - minOf(lowSide.first, lowSide.second)) +
                    maxOf(0.0, maxOf(highSide.first, highSide.second) - highSide.third)
            }
        }
        val noise = flatNoise(ls, w, h)
        return Stats(
            mean, pct, clipped.toDouble() / n, white.toDouble() / n, crushed.toDouble() / n, chroma / n, hue,
            doubleArrayOf(if (nn > 0) na / nn else 0.0, if (nn > 0) nb / nn else 0.0),
            if (gcount > 0) grad / gcount else 0.0, if (gcount > 0) mid / gcount else 0.0, noise,
            if (edgeCount > 0) over / edgeCount / 100.0 else 0.0,
        )
    }

    /** Noise sigma (L* units) from the 20th percentile of second-difference variance in 8x8 patches. */
    fun flatNoise(ls: DoubleArray, w: Int, h: Int): Double {
        val vars = ArrayList<Double>()
        var y = 1
        while (y + 9 < h) {
            var x = 1
            while (x + 9 < w) {
                var s = 0.0; var c = 0
                for (yy in y until y + 8) for (xx in x until x + 8) {
                    val i = yy * w + xx
                    val d = 2 * ls[i] - ls[i - 1] - ls[i + 1]
                    s += d * d; c++
                }
                vars.add(s / c / 6.0)
                x += 8
            }
            y += 8
        }
        if (vars.isEmpty()) return 0.0
        vars.sort()
        return sqrt(vars[(vars.size * 0.2).toInt()])
    }

    /** Mean CIEDE2000 between region means (alignment-insensitive) and per-pixel if same size. */
    fun regionDeltaE(a: ArgbImage, ra: Region, b: ArgbImage, rb: Region): Double {
        val sa = stats(a, ra); val sb = stats(b, rb)
        return ColorMath.deltaE2000(sa.meanLab, sb.meanLab)
    }

    fun pixelDeltaE(a: ArgbImage, b: ArgbImage): Double {
        require(a.width == b.width && a.height == b.height)
        var s = 0.0
        val n = a.width * a.height
        val la = labImage(a).first; val lb = labImage(b).first
        for (i in 0 until n) s += ColorMath.deltaE2000(la[i], lb[i])
        return s / n
    }
}
