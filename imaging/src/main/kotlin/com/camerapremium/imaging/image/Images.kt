package com.camerapremium.imaging.image

/**
 * Source of display-encoded RGB (0..1, sRGB transfer, Rec.709 primaries) addressed by rectangles.
 * Reads outside the image are clamped to the border (needed for filter halos).
 *
 * The pipeline never asks for the whole image at once, so implementations can wrap a native
 * Bitmap (Android), an IntArray (desktop) or a merged burst without extra full-size copies.
 */
interface SourceImage {
    val width: Int
    val height: Int

    /** Fills r/g/b (size >= w*h, row-major, stride w) with the encoded values of the clamped rect. */
    fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray)
}

/** Receives finished tiles as packed ARGB_8888. Calls may come from several threads. */
interface OutputSink {
    fun writeTile(x0: Int, y0: Int, w: Int, h: Int, argb: IntArray)
}

/** In-memory ARGB image (desktop tools, tests and the Android single-frame path). */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init { require(pixels.size >= width * height) }
}

class ArgbSource(private val image: ArgbImage) : SourceImage {
    override val width get() = image.width
    override val height get() = image.height

    override fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val px = image.pixels
        val iw = image.width
        val maxX = iw - 1
        val maxY = image.height - 1
        var o = 0
        for (yy in 0 until h) {
            val sy = (y0 + yy).coerceIn(0, maxY) * iw
            for (xx in 0 until w) {
                val c = px[sy + (x0 + xx).coerceIn(0, maxX)]
                r[o] = ((c ushr 16) and 0xFF) * INV255
                g[o] = ((c ushr 8) and 0xFF) * INV255
                b[o] = (c and 0xFF) * INV255
                o++
            }
        }
    }

    companion object { const val INV255 = 1f / 255f }
}

class ArgbSink(val image: ArgbImage) : OutputSink {
    override fun writeTile(x0: Int, y0: Int, w: Int, h: Int, argb: IntArray) {
        for (yy in 0 until h) {
            System.arraycopy(argb, yy * w, image.pixels, (y0 + yy) * image.width + x0, w)
        }
    }
}

/**
 * One YUV 4:2:0 frame in planar layout (chroma planes at half resolution, pixel stride 1).
 * Values follow the camera2 YUV_420_888 convention: JFIF / BT.601 full range.
 * The Android layer repacks Image planes into this so the imaging code stays platform-free.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvStride: Int,
    val timestampNs: Long = 0L,
) {
    val chromaWidth get() = (width + 1) / 2
    val chromaHeight get() = (height + 1) / 2

    fun luma(x: Int, y: Int): Int = this.y[y * yStride + x].toInt() and 0xFF

    companion object {
        fun allocate(width: Int, height: Int): YuvFrame {
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            return YuvFrame(width, height, ByteArray(width * height), width, ByteArray(cw * ch), ByteArray(cw * ch), cw)
        }
    }
}

/**
 * Result of a multi-frame merge. Luma at full resolution and chroma at half resolution, both in
 * float to keep the extra precision gained by averaging (an 8-frame merge is worth ~1.5 bits).
 * Y is 0..255, U/V are centered (-128..127).
 */
class MergedYuv(
    val width: Int,
    val height: Int,
    val y: FloatArray,
    val u: FloatArray,
    val v: FloatArray,
    /** Effective number of merged frames per 8x8 block (1 = reference only). */
    val effectiveFrames: FloatArray,
    val framesMerged: Int,
    val framesRejected: Int,
) {
    val chromaWidth get() = (width + 1) / 2
    val chromaHeight get() = (height + 1) / 2
    val mapWidth get() = (width + 7) / 8
    val mapHeight get() = (height + 7) / 8

    fun effectiveFramesAt(x: Int, y: Int): Float {
        val mx = (x / 8).coerceIn(0, mapWidth - 1)
        val my = (y / 8).coerceIn(0, mapHeight - 1)
        return effectiveFrames[my * mapWidth + mx]
    }
}

/** Converts YUV (JFIF full-range BT.601) to encoded RGB. */
class MergedYuvSource(private val m: MergedYuv) : SourceImage {
    override val width get() = m.width
    override val height get() = m.height

    override fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val maxX = m.width - 1
        val maxY = m.height - 1
        val cw = m.chromaWidth
        val cMaxX = cw - 1
        val cMaxY = m.chromaHeight - 1
        var o = 0
        for (yy in 0 until h) {
            val sy = (y0 + yy).coerceIn(0, maxY)
            // chroma sample positions are centered between luma pairs
            val cyF = (sy - 0.5f) * 0.5f
            val cy0 = kotlin.math.floor(cyF).toInt()
            val ty = cyF - cy0
            val r0 = cy0.coerceIn(0, cMaxY) * cw
            val r1 = (cy0 + 1).coerceIn(0, cMaxY) * cw
            for (xx in 0 until w) {
                val sx = (x0 + xx).coerceIn(0, maxX)
                val cxF = (sx - 0.5f) * 0.5f
                val cx0 = kotlin.math.floor(cxF).toInt()
                val tx = cxF - cx0
                val a0 = cx0.coerceIn(0, cMaxX)
                val a1 = (cx0 + 1).coerceIn(0, cMaxX)
                val u = bilerp(m.u[r0 + a0], m.u[r0 + a1], m.u[r1 + a0], m.u[r1 + a1], tx, ty)
                val v = bilerp(m.v[r0 + a0], m.v[r0 + a1], m.v[r1 + a0], m.v[r1 + a1], tx, ty)
                val yv = m.y[sy * m.width + sx]
                r[o] = (yv + 1.402f * v) * INV255
                g[o] = (yv - 0.344136f * u - 0.714136f * v) * INV255
                b[o] = (yv + 1.772f * u) * INV255
                o++
            }
        }
    }

    private fun bilerp(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Float {
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }

    companion object { const val INV255 = 1f / 255f }
}

/** Single YUV frame exposed as a source (no merge). */
class YuvFrameSource(private val f: YuvFrame) : SourceImage {
    override val width get() = f.width
    override val height get() = f.height

    override fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val maxX = f.width - 1
        val maxY = f.height - 1
        val cMaxX = f.chromaWidth - 1
        val cMaxY = f.chromaHeight - 1
        var o = 0
        for (yy in 0 until h) {
            val sy = (y0 + yy).coerceIn(0, maxY)
            val cy = (sy / 2).coerceIn(0, cMaxY) * f.uvStride
            for (xx in 0 until w) {
                val sx = (x0 + xx).coerceIn(0, maxX)
                val cx = (sx / 2).coerceIn(0, cMaxX)
                val yv = (f.y[sy * f.yStride + sx].toInt() and 0xFF).toFloat()
                val u = (f.u[cy + cx].toInt() and 0xFF) - 128f
                val v = (f.v[cy + cx].toInt() and 0xFF) - 128f
                r[o] = (yv + 1.402f * v) * INV255
                g[o] = (yv - 0.344136f * u - 0.714136f * v) * INV255
                b[o] = (yv + 1.772f * u) * INV255
                o++
            }
        }
    }

    companion object { const val INV255 = 1f / 255f }
}
