package com.camerapremium.app.processing

import android.graphics.Rect
import android.hardware.camera2.TotalCaptureResult
import com.camerapremium.app.camera.BurstPlan
import com.camerapremium.app.camera.LensInfo
import com.camerapremium.app.settings.CaptureMode
import com.camerapremium.imaging.burst.BurstMerger
import com.camerapremium.imaging.image.YuvFrame
import com.camerapremium.imaging.pipeline.FaceRegion
import java.util.concurrent.ConcurrentHashMap

/** Snapshot of the settings that affect one capture (settings may change while it processes). */
class JobOptions(
    val process: Boolean,
    val profileId: String,
    val ultraHdr: Boolean,
    val displayP3: Boolean,
    val saveOriginalToGallery: Boolean,
    val jpegQuality: Int,
    val raw: Boolean,
)

enum class FrameRole { ORIGINAL, BURST, RAW }

/**
 * State of one capture while its frames arrive. Frames are merged as they come (streaming), so
 * a 12-frame night burst never holds 12 full frames in memory.
 */
class CaptureJob(
    val id: Long,
    val lens: LensInfo,
    val options: JobOptions,
    /** Clockwise rotation (degrees) to make the image upright. */
    val rotation: Int,
    val mirror: Boolean,
    /** Aspect-ratio crop in stream coordinates (null = full frame). */
    val crop: Rect?,
    /** Same crop for the JPEG stream, which may have a different size than the YUV stream. */
    val jpegCrop: Rect?,
    val plan: BurstPlan,
    /** Burst frames were captured with the sRGB preset curve (our tone mapping applies fully). */
    val srgbCurve: Boolean,
    val faces: List<FaceRegion>,
    val mode: CaptureMode,
    val originalFromJpeg: Boolean,
    val expectRaw: Boolean,
    val lensLabel: String,
    /** Flash capture: one request feeds both the original JPEG and the processing YUV. */
    val flashCombined: Boolean = false,
) {
    @Volatile var expectedBurst = plan.frames
    @Volatile var burstImages = 0
    @Volatile var originalReceived = false
    @Volatile var rawReceived = !expectRaw
    @Volatile var originalJpeg: ByteArray? = null
    @Volatile var originalYuv: YuvFrame? = null
    @Volatile var singleFrame: YuvFrame? = null
    @Volatile var dngBytes: ByteArray? = null
    @Volatile var refResult: TotalCaptureResult? = null
    @Volatile var originalResult: TotalCaptureResult? = null
    @Volatile var completed = false
    var merger: BurstMerger? = null
    val roles = ConcurrentHashMap<Long, FrameRole>()
    val startedNs = System.nanoTime()
    val timings = LinkedHashMap<String, Long>()

    fun mark(name: String) { timings[name] = (System.nanoTime() - startedNs) / 1_000_000 }

    @Synchronized
    fun isComplete(): Boolean =
        !completed && originalReceived && rawReceived && burstImages >= expectedBurst &&
            (expectedBurst == 0 || refResult != null)
}
