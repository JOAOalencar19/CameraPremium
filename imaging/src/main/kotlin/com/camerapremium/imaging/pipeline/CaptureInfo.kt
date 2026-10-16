package com.camerapremium.imaging.pipeline

/** How the source pixels were produced. Drives how much the pipeline is allowed to change. */
enum class SourceKind {
    /** Our own multi-frame merge of YUV frames captured with the sRGB preset tonemap and edge enhancement off. */
    BURST_MERGED,
    /** A single YUV frame captured with the sRGB preset tonemap (our tonal rendering applies fully). */
    SINGLE_SRGB_CURVE,
    /** Already tone-mapped and sharpened by the camera HAL (system JPEG / default YUV). Light touch only. */
    SYSTEM_RENDERED,
}

/** Face rectangle in normalized source coordinates (0..1), before any rotation. */
class FaceRegion(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Int = 100) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = right - left
    val h get() = bottom - top
}

/**
 * Sensor color calibration as reported by camera2 (same semantics as DNG tags):
 * colorMatrix = XYZ -> reference camera space; calibration = reference -> this unit.
 */
class SensorCalibration(
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray?,
    val illuminant1Cct: Float,
    val illuminant2Cct: Float,
    val calibration1: FloatArray? = null,
    val calibration2: FloatArray? = null,
)

/** White balance decision of the previous shot, used to keep consecutive photos coherent. */
class WhiteMemory(val renderCct: Float, val timestampMs: Long, val lensKey: String)

/** Per-lens adjustments to bring different sensors/lenses closer together. */
class LensProfile(
    val key: String,
    /** Residual 3x3 color matrix in linear sRGB (identity = none). Measured per lens when calibrated. */
    val colorMatrix: FloatArray? = null,
    val sharpenScale: Float = 1f,
    val noiseScale: Float = 1f,
    val saturationScale: Float = 1f,
) {
    companion object {
        val DEFAULT = LensProfile("default")

        /** Conservative priors by focal class until real per-unit calibration data exists. */
        fun forFocalClass(key: String, equivalentFocalMm: Float, front: Boolean): LensProfile = when {
            front -> LensProfile(key, sharpenScale = 0.85f, noiseScale = 1.1f)
            equivalentFocalMm < 18f -> LensProfile(key, sharpenScale = 0.9f, noiseScale = 1.1f) // ultra-wide: softer corners
            equivalentFocalMm > 90f -> LensProfile(key, sharpenScale = 0.9f, noiseScale = 1.15f) // long tele: small pixels, more noise
            equivalentFocalMm > 50f -> LensProfile(key, sharpenScale = 0.95f, noiseScale = 1.05f)
            else -> LensProfile(key)
        }
    }
}

class CaptureInfo(
    val sourceKind: SourceKind,
    val framesMerged: Int = 1,
    val iso: Int = 0,
    val exposureTimeNs: Long = 0L,
    val aperture: Float = 0f,
    /** Exposure applied relative to the auto-exposure decision, in EV (negative = underexposed burst). */
    val exposureBiasEv: Float = 0f,
    /** camera2 COLOR_CORRECTION_GAINS [R, Geven, Godd, B] used for the capture, if known. */
    val awbGains: FloatArray? = null,
    val calibration: SensorCalibration? = null,
    val faces: List<FaceRegion> = emptyList(),
    val lens: LensProfile = LensProfile.DEFAULT,
    val frontCamera: Boolean = false,
    val previousWhite: WhiteMemory? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** EV100 of the capture exposure (scene brightness proxy), or null when metadata is missing. */
    fun captureEv100(): Float? {
        if (iso <= 0 || exposureTimeNs <= 0L || aperture <= 0f) return null
        val t = exposureTimeNs / 1e9
        val ev = Math.log(aperture.toDouble() * aperture / t) / Math.log(2.0) - Math.log(iso / 100.0) / Math.log(2.0)
        // An underexposed burst was taken at a shorter exposure than AE wanted: add the bias back.
        return (ev + exposureBiasEv).toFloat()
    }
}
