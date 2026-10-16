package com.camerapremium.tools

import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.image.ArgbImage
import com.camerapremium.imaging.image.YuvFrame
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthetic test scenes with known ground truth. */
object Synthetic {

    /** sRGB values of the 24 ColorChecker Classic patches (D65 rendering, common reference). */
    val CHECKER = arrayOf(
        intArrayOf(115, 82, 68), intArrayOf(194, 150, 130), intArrayOf(98, 122, 157), intArrayOf(87, 108, 67),
        intArrayOf(133, 128, 177), intArrayOf(103, 189, 170), intArrayOf(214, 126, 44), intArrayOf(80, 91, 166),
        intArrayOf(193, 90, 99), intArrayOf(94, 60, 108), intArrayOf(157, 188, 64), intArrayOf(224, 163, 46),
        intArrayOf(56, 61, 150), intArrayOf(70, 148, 73), intArrayOf(175, 54, 60), intArrayOf(231, 199, 31),
        intArrayOf(187, 86, 149), intArrayOf(8, 133, 161), intArrayOf(243, 243, 242), intArrayOf(200, 200, 200),
        intArrayOf(160, 160, 160), intArrayOf(122, 122, 121), intArrayOf(85, 85, 85), intArrayOf(52, 52, 52),
    )
    val CHECKER_NAMES = arrayOf(
        "pele escura", "pele clara", "ceu azul", "folhagem", "flor azul", "verde azulado", "laranja", "azul arroxeado",
        "vermelho moderado", "roxo", "verde amarelado", "amarelo alaranjado", "azul", "verde", "vermelho", "amarelo",
        "magenta", "ciano", "branco", "neutro 8", "neutro 6.5", "neutro 5", "neutro 3.5", "preto",
    )

    /** Monk Skin Tone scale (10 orbs), approximate sRGB. */
    val SKIN = arrayOf(
        intArrayOf(246, 237, 228), intArrayOf(243, 231, 219), intArrayOf(247, 234, 208), intArrayOf(234, 218, 186),
        intArrayOf(215, 189, 150), intArrayOf(160, 126, 86), intArrayOf(130, 92, 67), intArrayOf(96, 65, 52),
        intArrayOf(58, 49, 42), intArrayOf(41, 36, 32),
    )

    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun patches(colors: Array<IntArray>, cols: Int, cell: Int, border: Int = 8, bg: Int = 30): ArgbImage {
        val rows = (colors.size + cols - 1) / cols
        val w = cols * cell; val h = rows * cell
        val img = ArgbImage(w, h)
        img.pixels.fill(rgb(bg, bg, bg))
        for ((k, c) in colors.withIndex()) {
            val cx = (k % cols) * cell; val cy = (k / cols) * cell
            for (y in cy + border until cy + cell - border) for (x in cx + border until cx + cell - border) {
                img.pixels[y * w + x] = rgb(c[0], c[1], c[2])
            }
        }
        return img
    }

    fun addNoise(img: ArgbImage, sigma: Float, seed: Long = 1L): ArgbImage {
        val rnd = Random(seed)
        val out = ArgbImage(img.width, img.height)
        for (i in img.pixels.indices) {
            val c = img.pixels[i]
            val n = rnd.nextGaussian() * sigma // luma-correlated noise like a real sensor
            val r = ((c shr 16) and 0xFF) + n + rnd.nextGaussian() * sigma * 0.5
            val g = ((c shr 8) and 0xFF) + n + rnd.nextGaussian() * sigma * 0.5
            val b = (c and 0xFF) + n + rnd.nextGaussian() * sigma * 0.5
            out.pixels[i] = rgb(r.toInt(), g.toInt(), b.toInt())
        }
        return out
    }

    /**
     * Backlit interior: dim room with textured wall and a person-like skin patch, and a window
     * showing sky + clouds ~6 stops brighter. Encoded as an sRGB-curve frame underexposed so the
     * window is not clipped (what our HDR burst does).
     */
    fun hdrWindow(w: Int = 1200, h: Int = 900, underExposureEv: Float = 2f): ArgbImage {
        val img = ArgbImage(w, h)
        val k = Math.pow(2.0, -underExposureEv.toDouble()).toFloat()
        val rnd = Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = x.toFloat() / w; val ny = y.toFloat() / h
            var r: Float; var g: Float; var b: Float
            val inWindow = nx in 0.55f..0.92f && ny in 0.12f..0.6f
            if (inWindow) {
                // sky: blue gradient with white clouds, radiance ~ 6 stops above the room
                val cloud = (0.5f + 0.5f * sin(nx * 23f + sin(ny * 17f) * 2f) * sin(ny * 19f)).coerceIn(0f, 1f)
                val sky = floatArrayOf(0.25f, 0.45f, 0.95f)
                val cl = cloud * cloud
                r = (sky[0] * (1 - cl) + 0.95f * cl) * 3.2f
                g = (sky[1] * (1 - cl) + 0.95f * cl) * 3.2f
                b = (sky[2] * (1 - cl) + 0.97f * cl) * 3.2f
            } else {
                // room: warm wall with fine texture, a darker sofa band, a face-like ellipse
                val tex = 1f + 0.08f * sin(x * 0.9f) * sin(y * 0.7f) + 0.03f * rnd.nextGaussian().toFloat()
                r = 0.07f * tex; g = 0.06f * tex; b = 0.05f * tex
                if (ny > 0.7f) { r = 0.025f * tex; g = 0.02f * tex; b = 0.03f * tex }
                val fx = (nx - 0.3f) / 0.09f; val fy = (ny - 0.45f) / 0.13f
                if (fx * fx + fy * fy < 1f) {
                    val skin = floatArrayOf(ColorMath.srgbToLinear(194 / 255f), ColorMath.srgbToLinear(150 / 255f), ColorMath.srgbToLinear(130 / 255f))
                    val shade = 0.25f * tex
                    r = skin[0] * shade; g = skin[1] * shade; b = skin[2] * shade
                }
            }
            val e = 1.2f * k // exposure chosen so the brightest cloud stays just below clipping
            img.pixels[y * w + x] = rgb(
                (ColorMath.linearToSrgb((r * e).coerceIn(0f, 1f)) * 255 + 0.5f).toInt(),
                (ColorMath.linearToSrgb((g * e).coerceIn(0f, 1f)) * 255 + 0.5f).toInt(),
                (ColorMath.linearToSrgb((b * e).coerceIn(0f, 1f)) * 255 + 0.5f).toInt(),
            )
        }
        return img
    }

    /** Step edges, thin lines and a fine grating, slightly blurred like optics would. */
    fun edges(w: Int = 600, h: Int = 400): ArgbImage {
        val v = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var c = if (x < w / 3) 0.2f else 0.75f
            if (y > h / 2) c = if ((x / 40) % 2 == 0) 0.3f else 0.6f
            if (x in w * 2 / 3 until w * 2 / 3 + 2 && y < h / 2) c = 0.1f
            if (x > w * 5 / 6 && y < h / 2) c = 0.45f + 0.15f * sin(x * 2 * PI.toFloat() / 4f)
            v[y * w + x] = c
        }
        val b = gaussianBlur(v, w, h, 1.0f)
        val img = ArgbImage(w, h)
        for (i in b.indices) { val q = (b[i] * 255 + 0.5f).toInt(); img.pixels[i] = rgb(q, q, q) }
        return img
    }

    fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f; for (j in -r..r) a += k[j + r] * src[y * w + (x + j).coerceIn(0, w - 1)]; tmp[y * w + x] = a
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f; for (j in -r..r) a += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]; out[y * w + x] = a
        }
        return out
    }

    fun flat(w: Int, h: Int, v: Int): ArgbImage = ArgbImage(w, h).also { it.pixels.fill(rgb(v, v, v)) }

    // ------------------------------------------------------------------ burst simulation

    fun toYuv(img: ArgbImage): YuvFrame {
        val f = YuvFrame.allocate(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.pixels[y * img.width + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            f.y[y * f.yStride + x] = (0.299 * r + 0.587 * g + 0.114 * b + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        for (y in 0 until f.chromaHeight) for (x in 0 until f.chromaWidth) {
            var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
            for (yy in y * 2 until minOf(img.height, y * 2 + 2)) for (xx in x * 2 until minOf(img.width, x * 2 + 2)) {
                val c = img.pixels[yy * img.width + xx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            r /= n; g /= n; b /= n
            f.u[y * f.uvStride + x] = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128.5).toInt().coerceIn(0, 255).toByte()
            f.v[y * f.uvStride + x] = (0.5 * r - 0.418688 * g - 0.081312 * b + 128.5).toInt().coerceIn(0, 255).toByte()
        }
        return f
    }

    /** Shifted (integer px) noisy copy of [img]; optional moving square to test ghosting. */
    fun burstFrame(img: ArgbImage, dx: Int, dy: Int, sigma: Float, seed: Long, square: IntArray? = null): YuvFrame {
        val w = img.width; val h = img.height
        val shifted = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x + dx).coerceIn(0, w - 1); val sy = (y + dy).coerceIn(0, h - 1)
            shifted.pixels[y * w + x] = img.pixels[sy * w + sx]
        }
        if (square != null) {
            for (y in square[1] until square[1] + square[2]) for (x in square[0] until square[0] + square[2]) {
                if (x in 0 until w && y in 0 until h) shifted.pixels[y * w + x] = rgb(220, 40, 40)
            }
        }
        return toYuv(addNoise(shifted, sigma, seed))
    }

    fun naturalScene(w: Int = 800, h: Int = 600): ArgbImage {
        // smooth gradients + textures: a stand-in for a real photo in burst tests
        val img = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = 0.5f + 0.25f * sin(x * 0.05f) * sin(y * 0.04f) + 0.15f * sin(x * 0.6f + y * 0.3f)
            val r = (t * 200).toInt(); val g = ((1 - t) * 120 + 60).toInt(); val b = (100 + 80 * sin(y * 0.02f)).toInt()
            img.pixels[y * w + x] = rgb(r, g, b)
        }
        return img
    }
}
