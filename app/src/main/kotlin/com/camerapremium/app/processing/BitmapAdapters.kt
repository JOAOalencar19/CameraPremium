package com.camerapremium.app.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Rect
import android.os.Build
import com.camerapremium.imaging.image.OutputSink
import com.camerapremium.imaging.image.SourceImage
import java.nio.ByteBuffer
import kotlin.math.pow

/** Source over an ARGB_8888 Bitmap (decoded system JPEG). Reads tiles with getPixels. */
class BitmapSource(private val bmp: Bitmap) : SourceImage {
    override val width = bmp.width
    override val height = bmp.height
    private val tl = object : ThreadLocal<IntArray>() {}

    override fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        // clamp the rect, read the valid part, then replicate borders
        val cx0 = x0.coerceIn(0, width - 1); val cy0 = y0.coerceIn(0, height - 1)
        val cx1 = (x0 + w).coerceIn(cx0 + 1, width); val cy1 = (y0 + h).coerceIn(cy0 + 1, height)
        val cw = cx1 - cx0; val ch = cy1 - cy0
        var buf = tl.get()
        if (buf == null || buf.size < cw * ch) { buf = IntArray(cw * ch); tl.set(buf) }
        bmp.getPixels(buf, 0, cw, cx0, cy0, cw, ch)
        var o = 0
        for (yy in 0 until h) {
            val sy = ((y0 + yy).coerceIn(cy0, cy1 - 1) - cy0) * cw
            for (xx in 0 until w) {
                val c = buf[sy + (x0 + xx).coerceIn(cx0, cx1 - 1) - cx0]
                r[o] = ((c ushr 16) and 0xFF) * INV; g[o] = ((c ushr 8) and 0xFF) * INV; b[o] = (c and 0xFF) * INV
                o++
            }
        }
    }

    companion object { const val INV = 1f / 255f }
}

/** Restricts a source to a crop rectangle (aspect-ratio crops are processed, not discarded). */
class CroppedSource(private val src: SourceImage, private val crop: Rect) : SourceImage {
    override val width = crop.width()
    override val height = crop.height()
    override fun readEncoded(x0: Int, y0: Int, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        // clamp to the crop so halos replicate the crop border, like a real image edge
        if (x0 >= 0 && y0 >= 0 && x0 + w <= width && y0 + h <= height) {
            src.readEncoded(crop.left + x0, crop.top + y0, w, h, r, g, b)
            return
        }
        // read the valid intersection once, then replicate its border
        val cx0 = x0.coerceIn(0, width - 1); val cy0 = y0.coerceIn(0, height - 1)
        val cx1 = (x0 + w).coerceIn(cx0 + 1, width); val cy1 = (y0 + h).coerceIn(cy0 + 1, height)
        val cw = cx1 - cx0; val ch = cy1 - cy0
        val n = cw * ch
        val tr = FloatArray(n); val tg = FloatArray(n); val tb = FloatArray(n)
        src.readEncoded(crop.left + cx0, crop.top + cy0, cw, ch, tr, tg, tb)
        var o = 0
        for (yy in 0 until h) {
            val sy = ((y0 + yy).coerceIn(cy0, cy1 - 1) - cy0) * cw
            for (xx in 0 until w) {
                val i = sy + (x0 + xx).coerceIn(cx0, cx1 - 1) - cx0
                r[o] = tr[i]; g[o] = tg[i]; b[o] = tb[i]; o++
            }
        }
    }
}

/**
 * Writes source-oriented tiles into a bitmap that is already upright (rotation clockwise in
 * degrees, then optional horizontal mirror). Saves a full-size rotated copy at the end.
 */
class OrientedBitmapSink(val srcW: Int, val srcH: Int, val rotation: Int, val mirror: Boolean, val bitmap: Bitmap) : OutputSink {
    private val lock = Any()

    companion object {
        fun outputSize(w: Int, h: Int, rotation: Int): IntArray = if (rotation % 180 == 0) intArrayOf(w, h) else intArrayOf(h, w)
    }

    /** Maps a source pixel to destination coordinates. */
    fun map(x: Int, y: Int, out: IntArray) {
        var dx: Int; var dy: Int
        when (rotation) {
            90 -> { dx = srcH - 1 - y; dy = x }
            180 -> { dx = srcW - 1 - x; dy = srcH - 1 - y }
            270 -> { dx = y; dy = srcW - 1 - x }
            else -> { dx = x; dy = y }
        }
        if (mirror) dx = bitmap.width - 1 - dx
        out[0] = dx; out[1] = dy
    }

    override fun writeTile(x0: Int, y0: Int, w: Int, h: Int, argb: IntArray) {
        if (rotation == 0 && !mirror) {
            synchronized(lock) { bitmap.setPixels(argb, 0, w, x0, y0, w, h) }
            return
        }
        val a = IntArray(2); val b = IntArray(2)
        map(x0, y0, a); map(x0 + w - 1, y0 + h - 1, b)
        val left = minOf(a[0], b[0]); val top = minOf(a[1], b[1])
        val dw = if (rotation % 180 == 0) w else h
        val dh = if (rotation % 180 == 0) h else w
        val out = IntArray(dw * dh)
        val p = IntArray(2)
        for (yy in 0 until h) for (xx in 0 until w) {
            map(x0 + xx, y0 + yy, p)
            out[(p[1] - top) * dw + (p[0] - left)] = argb[yy * w + xx]
        }
        synchronized(lock) { bitmap.setPixels(out, 0, dw, left, top, dw, dh) }
    }

    /** Rotates a quarter-res gain map the same way and attaches it (Android 14+ Ultra HDR). */
    fun attachGainMap(gain: FloatArray, gw: Int, gh: Int, headroomStops: Float): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val dims = outputSize(gw, gh, rotation)
        val ow = dims[0]; val oh = dims[1]
        val gm = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
        val rowBytes = gm.rowBytes
        val bytes = ByteArray(rowBytes * oh)
        val tmpSink = this
        for (y in 0 until gh) for (x in 0 until gw) {
            var dx: Int; var dy: Int
            when (rotation) {
                90 -> { dx = gh - 1 - y; dy = x }
                180 -> { dx = gw - 1 - x; dy = gh - 1 - y }
                270 -> { dx = y; dy = gw - 1 - x }
                else -> { dx = x; dy = y }
            }
            if (tmpSink.mirror) dx = ow - 1 - dx
            val v = (gain[y * gw + x] / headroomStops).coerceIn(0f, 1f)
            bytes[dy * rowBytes + dx] = (v * 255f + 0.5f).toInt().toByte()
        }
        gm.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        val maxRatio = 2.0.pow(headroomStops.toDouble()).toFloat()
        val g = Gainmap(gm)
        g.setRatioMin(1f, 1f, 1f)
        g.setRatioMax(maxRatio, maxRatio, maxRatio)
        g.setGamma(1f, 1f, 1f)
        g.setEpsilonSdr(1f / 64f, 1f / 64f, 1f / 64f)
        g.setEpsilonHdr(1f / 64f, 1f / 64f, 1f / 64f)
        g.setDisplayRatioForFullHdr(maxRatio)
        g.setMinDisplayRatioForHdrTransition(1f)
        bitmap.gainmap = g
        return true
    }
}
