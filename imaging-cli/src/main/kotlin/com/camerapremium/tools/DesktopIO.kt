package com.camerapremium.tools

import com.camerapremium.imaging.image.ArgbImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object DesktopIO {
    fun read(f: File): ArgbImage {
        val bi = ImageIO.read(f) ?: error("formato nao suportado: $f (use JPEG/PNG; HEIC deve ser exportado como JPEG)")
        val w = bi.width; val h = bi.height
        val px = IntArray(w * h)
        bi.getRGB(0, 0, w, h, px, 0, w)
        return ArgbImage(w, h, px)
    }

    fun write(img: ArgbImage, f: File, quality: Float = 0.95f) {
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
        f.absoluteFile.parentFile?.mkdirs()
        if (f.name.lowercase().endsWith(".png")) { ImageIO.write(bi, "png", f); return }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
        ImageIO.createImageOutputStream(f).use { out ->
            writer.output = out
            writer.write(null, IIOImage(bi, null, null), param)
        }
        writer.dispose()
    }

    /** Box downscale so the long side is <= maxSide (metrics on huge images are slow). */
    fun fit(img: ArgbImage, maxSide: Int): ArgbImage {
        val f = maxOf(1, (maxOf(img.width, img.height) + maxSide - 1) / maxSide)
        if (f == 1) return img
        val w = img.width / f; val h = img.height / f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (yy in 0 until f) for (xx in 0 until f) {
                val c = img.pixels[(y * f + yy) * img.width + x * f + xx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val n = f * f
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return ArgbImage(w, h, out)
    }

    /** Side-by-side image (for visual review). */
    fun sideBySide(a: ArgbImage, b: ArgbImage): ArgbImage {
        val h = minOf(a.height, b.height)
        val out = ArgbImage(a.width + b.width + 8, h)
        out.pixels.fill(0xFF000000.toInt())
        for (y in 0 until h) {
            System.arraycopy(a.pixels, y * a.width, out.pixels, y * out.width, a.width)
            System.arraycopy(b.pixels, y * b.width, out.pixels, y * out.width + a.width + 8, b.width)
        }
        return out
    }
}
