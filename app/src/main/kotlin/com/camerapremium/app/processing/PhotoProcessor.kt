package com.camerapremium.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.media.ExifInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.camerapremium.app.camera.CameraInventory
import com.camerapremium.app.storage.CaptureRecord
import com.camerapremium.app.storage.CaptureStore
import com.camerapremium.app.storage.MediaSaver
import com.camerapremium.imaging.burst.BurstMerger
import com.camerapremium.imaging.image.MergedYuv
import com.camerapremium.imaging.image.MergedYuvSource
import com.camerapremium.imaging.image.OutputSink
import com.camerapremium.imaging.image.SourceImage
import com.camerapremium.imaging.image.YuvFrame
import com.camerapremium.imaging.image.YuvFrameSource
import com.camerapremium.imaging.pipeline.CaptureInfo
import com.camerapremium.imaging.pipeline.OutputColorSpace
import com.camerapremium.imaging.pipeline.OutputOptions
import com.camerapremium.imaging.pipeline.PhotoPipeline
import com.camerapremium.imaging.pipeline.ProcessingProfile
import com.camerapremium.imaging.pipeline.SourceKind
import com.camerapremium.imaging.pipeline.WhiteMemory
import com.camerapremium.imaging.util.WorkerPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background processing: merge executor (streaming burst merge) + pipeline executor, so the
 * next capture can merge while the previous one renders. Never runs on the main thread.
 */
class PhotoProcessor(private val ctx: Context, private val inventory: CameraInventory, private val store: CaptureStore) {

    interface Listener {
        fun onSaved(record: CaptureRecord, thumbnail: Bitmap)
        fun onFailed(message: String)
        fun onQueueChanged(pending: Int)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val mergeExec = Executors.newSingleThreadExecutor { r -> Thread(r, "merge").apply { priority = Thread.NORM_PRIORITY } }
    private val pipelineExec = Executors.newSingleThreadExecutor { r -> Thread(r, "pipeline") }
    private val pool = WorkerPool()
    private val pipeline = PhotoPipeline(pool)
    private val pending = AtomicInteger()
    private val merging = AtomicInteger()
    @Volatile private var lastWhite: WhiteMemory? = null

    /** True when another capture can start without risking memory (one merge at a time). */
    fun canAcceptCapture(): Boolean = merging.get() == 0 && pending.get() <= 1

    fun begin(job: CaptureJob) {
        pending.incrementAndGet(); merging.incrementAndGet()
        notifyQueue()
        if (job.expectedBurst > 1) job.merger = BurstMerger(pool)
    }

    /** Called from the image thread for each burst frame (already copied out of the Image). */
    fun addBurstFrame(job: CaptureJob, frame: YuvFrame) {
        mergeExec.execute {
            try {
                val m = job.merger
                if (m != null) m.add(frame) else job.singleFrame = frame
            } catch (t: Throwable) { Log.e(TAG, "merge", t) }
        }
    }

    fun complete(job: CaptureJob) {
        synchronized(job) { if (job.completed) return; job.completed = true }
        job.mark("frames_received")
        mergeExec.execute {
            var merged: MergedYuv? = null
            try {
                merged = job.merger?.finish()
                job.merger = null
            } catch (t: Throwable) { Log.e(TAG, "merge finish", t) }
            job.mark("merge_done")
            merging.decrementAndGet()
            notifyQueue()
            val m = merged
            pipelineExec.execute { runPipeline(job, m) }
        }
    }

    fun abort(job: CaptureJob, reason: String) {
        synchronized(job) { if (job.completed) return; job.completed = true }
        job.merger = null
        merging.decrementAndGet(); pending.decrementAndGet(); notifyQueue()
        main.post { listener?.onFailed(reason) }
    }

    private fun notifyQueue() { val n = pending.get(); main.post { listener?.onQueueChanged(n) } }

    // ------------------------------------------------------------------------------------

    private fun runPipeline(job: CaptureJob, merged: MergedYuv?) {
        try {
            val record = process(job, merged)
            val thumb = record.second
            main.post { listener?.onSaved(record.first, thumb) }
        } catch (t: Throwable) {
            Log.e(TAG, "pipeline", t)
            main.post { listener?.onFailed("Falha ao processar: ${t.javaClass.simpleName} ${t.message ?: ""}") }
        } finally {
            pending.decrementAndGet(); notifyQueue()
            pipeline.releaseBuffers()
        }
    }

    private fun process(job: CaptureJob, merged: MergedYuv?): Pair<CaptureRecord, Bitmap> {
        val taken = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(taken))
        val opts = job.options

        // ---- original (always preserved, never overwritten without the user asking)
        val systemJpeg = job.originalJpeg
        val originalBytes: ByteArray? = when {
            job.originalJpeg != null -> prepareOriginalJpeg(job)
            job.originalYuv != null -> encodeYuv(job.originalYuv!!, job.crop, job.rotation, job.mirror, opts.jpegQuality)
            else -> null
        }
        job.originalYuv = null; job.originalJpeg = null
        job.mark("original_ready")
        val origFile = File(store.originalsDir, "orig_$stamp.jpg")
        originalBytes?.let { origFile.writeBytes(it) }

        var processedUri: String? = null
        var summary = ""
        var thumb: Bitmap? = null
        val frame = job.singleFrame
        // High-resolution captures have no YUV burst: process the system JPEG itself when its
        // size is reasonable for the memory budget (two ARGB copies).
        var decoded: Bitmap? = null
        if (opts.process && merged == null && frame == null && systemJpeg != null) {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(systemJpeg, 0, systemJpeg.size, bo)
            if (bo.outWidth.toLong() * bo.outHeight <= MAX_JPEG_PROCESS_PIXELS) {
                decoded = BitmapFactory.decodeByteArray(systemJpeg, 0, systemJpeg.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            }
        }
        val source: SourceImage? = when {
            !opts.process -> null
            merged != null -> MergedYuvSource(merged)
            frame != null -> YuvFrameSource(frame)
            decoded != null -> BitmapSource(decoded)
            else -> null
        }
        if (source != null) {
            val cropRect = if (decoded != null) job.jpegCrop else job.crop
            val src = if (cropRect != null) CroppedSource(source, cropRect) else source
            val kind = when {
                decoded != null -> SourceKind.SYSTEM_RENDERED
                job.plan.hybrid -> SourceKind.HYBRID
                !job.srgbCurve -> SourceKind.SYSTEM_RENDERED
                merged != null && merged.framesMerged > 1 -> SourceKind.BURST_MERGED
                else -> SourceKind.SINGLE_SRGB_CURVE
            }
            val info = captureInfo(job, kind, merged?.framesMerged ?: 1)
            val dims = OrientedBitmapSink.outputSize(src.width, src.height, job.rotation)
            val cs = if (opts.displayP3) ColorSpace.get(ColorSpace.Named.DISPLAY_P3) else ColorSpace.get(ColorSpace.Named.SRGB)
            val out = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888, false, cs)
            val sink = OrientedBitmapSink(src.width, src.height, job.rotation, job.mirror, out)
            val gainMap = opts.ultraHdr && Build.VERSION.SDK_INT >= 34
            val outOpts = OutputOptions(
                colorSpace = if (opts.displayP3) OutputColorSpace.DISPLAY_P3 else OutputColorSpace.SRGB,
                gainMap = gainMap,
            )
            val base = ProcessingProfile.byId(opts.profileId)
            val profile = base.copy(
                sharpenBurst = base.sharpenBurst * opts.sharpnessScale,
                sharpenSingleSrgb = base.sharpenSingleSrgb * opts.sharpnessScale,
                sharpenSystemRendered = base.sharpenSystemRendered * opts.sharpnessScale,
                skinSmoothing = if (opts.skinSmoothing) 0.3f else 0f,
            )
            val res = pipeline.process(src, info, profile, outOpts, sink, merged)
            job.mark("pipeline_done")
            res.whiteBalance.renderCct?.let { lastWhite = WhiteMemory(it, System.currentTimeMillis(), info.lens.key) }
            var hdrAttached = false
            if (gainMap && res.gainMap != null) hdrAttached = sink.attachGainMap(res.gainMap!!, res.gainMapWidth, res.gainMapHeight, outOpts.gainMapHeadroomStops)
            job.singleFrame = null
            decoded?.recycle()
            val bos = ByteArrayOutputStream(dims[0] * dims[1] / 3)
            out.compress(Bitmap.CompressFormat.JPEG, opts.jpegQuality, bos)
            var bytes = bos.toByteArray()
            job.mark("encode_done")
            // EXIF rewriting is skipped for Ultra HDR files so the gain-map container stays intact.
            if (!hdrAttached) bytes = withExif(bytes, job, 1, "Natural Premium")
            processedUri = MediaSaver.insert(ctx, bytes, "CP_$stamp.jpg", MediaSaver.ALBUM, "image/jpeg", taken).toString()
            job.mark("saved")
            thumb = Bitmap.createScaledBitmap(out, 240, (240f * out.height / out.width).toInt().coerceAtLeast(1), true)
            out.recycle()
            summary = "fonte=$kind quadros=${merged?.framesMerged ?: 1} rejeitados=${merged?.framesRejected ?: 0} " +
                "hdr=${if (hdrAttached) "ultra" else "sdr"} plano=[${job.plan}] " + res.summary()
        }

        // ---- original in the gallery: when not processing, or when the user wants both
        var originalGalleryUri: String? = null
        var preferred = "processed"
        if (originalBytes != null && (processedUri == null || opts.saveOriginalToGallery)) {
            val path = if (processedUri == null) MediaSaver.ALBUM else MediaSaver.ORIGINALS
            val uri = MediaSaver.insert(ctx, originalBytes, "CP_${stamp}_original.jpg", path, "image/jpeg", taken).toString()
            if (processedUri == null) { processedUri = uri; preferred = "original" } else originalGalleryUri = uri
        }
        if (thumb == null && originalBytes != null) {
            val o = BitmapFactory.Options().apply { inSampleSize = 16 }
            thumb = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, o)
        }
        val dng = job.dngBytes?.let { MediaSaver.insert(ctx, it, "CP_$stamp.dng", MediaSaver.RAW, "image/x-adobe-dng", taken).toString() }
        job.dngBytes = null
        job.mark("done")
        val record = CaptureRecord(
            id = job.id, timestampMs = taken, processedUri = processedUri,
            originalPath = if (originalBytes != null) origFile.absolutePath else null,
            originalGalleryUri = originalGalleryUri, processedPath = null, dngUri = dng,
            preferred = preferred, summary = summary, timings = job.timings.toString() + memInfo(), lensLabel = job.lensLabel,
        )
        store.put(record)
        Log.i(TAG, "capture ${job.id}: ${job.timings} $summary")
        return record to (thumb ?: Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    }

    private fun memInfo(): String {
        val rt = Runtime.getRuntime()
        return " heap=${(rt.totalMemory() - rt.freeMemory()) / (1 shl 20)}/${rt.maxMemory() / (1 shl 20)}MB"
    }

    private fun captureInfo(job: CaptureJob, kind: SourceKind, frames: Int): CaptureInfo {
        val r = job.refResult ?: job.originalResult
        val physId = if (Build.VERSION.SDK_INT >= 29) r?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
        val activeLens = physId?.let { inventory.lenses[it] } ?: job.lens
        val gains = r?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        return CaptureInfo(
            sourceKind = kind,
            framesMerged = frames,
            iso = r?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureTimeNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            aperture = r?.get(CaptureResult.LENS_APERTURE) ?: activeLens.apertures.firstOrNull() ?: 0f,
            exposureBiasEv = job.plan.evBias,
            awbGains = gains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) },
            calibration = activeLens.calibration ?: job.lens.calibration,
            faces = job.faces,
            lens = activeLens.lensProfile(),
            frontCamera = job.lens.front,
            previousWhite = lastWhite,
        )
    }

    /** HAL JPEG: keep the bytes untouched unless an aspect crop is needed; orientation goes to EXIF. */
    private fun prepareOriginalJpeg(job: CaptureJob): ByteArray {
        val jpeg = job.originalJpeg!!
        val crop = job.jpegCrop
        if (crop == null) return withExif(jpeg, job, exifOrientation(job.rotation, job.mirror), "Original (sistema)")
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val src = CroppedSource(BitmapSource(bmp), crop)
        val dims = OrientedBitmapSink.outputSize(src.width, src.height, job.rotation)
        val out = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
        copyThrough(src, OrientedBitmapSink(src.width, src.height, job.rotation, job.mirror, out))
        bmp.recycle()
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 97, bos)
        out.recycle()
        return withExif(bos.toByteArray(), job, 1, "Original (sistema, recortado)")
    }

    /** YUV original (no JPEG stream in this session): converted exactly, no processing. */
    private fun encodeYuv(frame: YuvFrame, crop: Rect?, rotation: Int, mirror: Boolean, quality: Int): ByteArray {
        val base = YuvFrameSource(frame)
        val src = if (crop != null) CroppedSource(base, crop) else base
        val dims = OrientedBitmapSink.outputSize(src.width, src.height, rotation)
        val out = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
        copyThrough(src, OrientedBitmapSink(src.width, src.height, rotation, mirror, out))
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        out.recycle()
        return bos.toByteArray()
    }

    private fun copyThrough(src: SourceImage, sink: OutputSink) {
        val t = 256
        val r = FloatArray(t * t); val g = FloatArray(t * t); val b = FloatArray(t * t)
        val argb = IntArray(t * t)
        var y = 0
        while (y < src.height) {
            var x = 0
            val h = minOf(t, src.height - y)
            while (x < src.width) {
                val w = minOf(t, src.width - x)
                src.readEncoded(x, y, w, h, r, g, b)
                for (i in 0 until w * h) {
                    argb[i] = (0xFF shl 24) or (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
                }
                sink.writeTile(x, y, w, h, argb)
                x += t
            }
            y += t
        }
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun withExif(bytes: ByteArray, job: CaptureJob, orientation: Int, software: String): ByteArray {
        return try {
            val f = File.createTempFile("exif", ".jpg", ctx.cacheDir)
            f.writeBytes(bytes)
            val e = ExifInterface(f.absolutePath)
            val r = job.refResult ?: job.originalResult
            e.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            e.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            e.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraPremium - $software")
            val now = Date()
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
            e.setAttribute(ExifInterface.TAG_DATETIME, fmt)
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fmt)
            e.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, fmt)
            val off = SimpleDateFormat("XXX", Locale.US).format(now)
            e.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, off)
            if (r != null) {
                r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { e.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
                r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { e.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
                r.get(CaptureResult.LENS_APERTURE)?.let { e.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
            }
            if (job.lens.equivalentFocal > 0f) e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, job.lens.equivalentFocal.toInt().toString())
            e.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
            e.saveAttributes()
            val out = f.readBytes()
            f.delete()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "exif", t); bytes
        }
    }

    companion object {
        const val TAG = "CameraPremium"
        const val MAX_JPEG_PROCESS_PIXELS = 52_000_000L

        /** EXIF orientation for a clockwise rotation, optionally mirrored (selfie as previewed). */
        fun exifOrientation(rotation: Int, mirror: Boolean): Int = if (!mirror) when (rotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        } else when (rotation) {
            90 -> ExifInterface.ORIENTATION_TRANSPOSE
            180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
            270 -> ExifInterface.ORIENTATION_TRANSVERSE
            else -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        }
    }
}
