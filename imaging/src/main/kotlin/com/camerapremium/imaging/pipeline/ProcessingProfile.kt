package com.camerapremium.imaging.pipeline

/**
 * All tunables of a photographic look. Natural Premium is the only profile for now; new looks
 * are new instances, not new code paths. Values are deliberately conservative: every stage is
 * designed so that "no adjustment" is reachable by setting its strength to 0 / 1.
 *
 * NOTE: these defaults were chosen from color-science reasoning and synthetic/sample tests.
 * They still need tuning against real Galaxy S26 Ultra captures and iPhone references
 * (see docs/PROTOCOLO_COMPARACAO_IPHONE.md).
 */
data class ProcessingProfile(
    val id: String,
    val displayName: String,

    // ---- exposure (display-linear luminance targets for the scene key) ----
    val targetKeyDay: Float = 0.195f,
    val targetKeyIndoor: Float = 0.18f,
    val targetKeyNight: Float = 0.105f,
    /** Weight of detected faces in the exposure key (0..1 of total). */
    val faceKeyWeight: Float = 0.35f,
    val maxExposureLiftEv: Float = 2.5f,
    val maxExposureCutEv: Float = 1.5f,

    // ---- local tone mapping / HDR ----
    /** Highlights of the base layer are compressed so they land this many stops above mid-gray. */
    val highlightHeadroomStops: Float = 2.4f,
    val minHighlightCompression: Float = 0.5f,
    /** Shadow range (stops below key) kept before lifting; smaller = more shadow lift. */
    val shadowRangeStops: Float = 6.0f,
    val minShadowCompression: Float = 0.78f,
    val baseFilterEps: Float = 0.04f,

    // ---- global tone curve ----
    val contrast: Float = 1.06f,
    /** Where the highlight shoulder starts, in stops above mid-gray. */
    val shoulderStartStops: Float = 1.0f,
    val blackFlare: Float = 0.0018f,
    /** HYBRID (HAL tone) only: fraction of the black-point lift removed, linear display units. */
    val hybridBlackPull: Float = 0.5f,
    /** HYBRID only: the 99.5th percentile is stretched toward this display-linear level... */
    val hybridWhiteTarget: Float = 0.85f,
    /** ...with at most this gain (soft knee above 0.8, never a hard clip). */
    val hybridMaxWhiteGain: Float = 1.2f,

    // ---- white balance ----
    /** Fraction of a warm (tungsten) illuminant that is kept, iPhone-like ambience. */
    val warmRetention: Float = 0.26f,
    /** Fraction of a very cool (shade/overcast) illuminant that is kept. */
    val coolRetention: Float = 0.10f,
    /** Scale of the ambience retention when the HAL's color is kept (system/hybrid): its own AWB
     *  already keeps some warmth, and adding ours on top made skin orange in real S26 shots. */
    val halToneAmbienceScale: Float = 0.4f,
    /** Fraction of a residual green/magenta or color cast that is removed. */
    val castCorrection: Float = 0.5f,

    // ---- color (Oklab) ----
    val chroma: Float = 1.03f,
    val vibrance: Float = 0.08f,
    val redChroma: Float = 0.96f,
    val greenChroma: Float = 0.95f,
    val greenHueShiftDeg: Float = -3f,
    val skyChroma: Float = 1.04f,
    val skyDeepen: Float = 0.04f,
    val highlightDesat: Float = 0.30f,

    // ---- skin ----
    val skinChromaKnee: Float = 0.115f,
    val skinChromaSlope: Float = 0.6f,
    val skinHueCenterDeg: Float = 58f,
    val skinHueTolerance: Float = 12f,
    val skinHuePull: Float = 0.25f,
    val skinSharpenScale: Float = 0.45f,
    /** Chroma NR multiplier on skin: removes color blotches/redness noise, never luma texture. */
    val skinChromaNrBoost: Float = 1.6f,
    /** Specular roll-off on skin (forehead, nose): only the *local* excess over the surrounding
     *  skin above this margin is compressed, so naturally light skin is never darkened. */
    val skinHighlightMargin: Float = 0.03f,
    val skinHighlightSlope: Float = 0.6f,
    /** Optional subtle smoothing of mid-frequency blotches on skin (0 = off, default). Pores,
     *  lashes and contours are protected by the edge/texture confidence mask. */
    val skinSmoothing: Float = 0f,

    // ---- noise ----
    val lumaNrStrength: Float = 2.0f,
    /** Luma NR strength when the noise level was measured temporally (reliable, no margin). */
    val lumaNrStrengthTemporal: Float = 1.1f,
    /** Fraction of the removed luminance noise added back as fine grain (prevents the plastic look). */
    val grainRetention: Float = 0.3f,
    val chromaNrStrength: Float = 2.5f,
    /** Sources without the ISP's chroma cleanup (NR off/minimal) show demosaic false color: random
     *  rainbow speckle on fine bright texture (metal, chains, text). On textured pixels the
     *  full-resolution chroma residual is dropped by this fraction in favour of the luma-guided
     *  half-resolution chroma (the 4:2:0 resolution every JPEG already has). */
    val falseColorSuppression: Float = 0.9f,
    /** Luma NR multiplier in good light (blends in with the scene's day factor). */
    val wellLitNrScale: Float = 0.7f,
    /** The ISP already denoised a system-rendered source: luma NR is only a light residual pass. */
    val renderedLumaNrScale: Float = 0.2f,
    val renderedGrainRetention: Float = 0.5f,

    // ---- sharpening ----
    // Audit: a SYSTEM_RENDERED source was already sharpened by the ISP (EDGE_MODE HQ), so it
    // gets no second unsharp mask. Our own sharpening runs only where the ISP's was turned off.
    val sharpenBurst: Float = 0.38f,
    val sharpenSingleSrgb: Float = 0.24f,
    val sharpenSystemRendered: Float = 0f,
    val sharpenHybrid: Float = 0.50f,
    /** Gaussian sigma (px at 12 MP) of the unsharp mask; scaled with resolution. */
    val sharpenRadius: Float = 0.9f,
    /** Absolute coring threshold (Oklab L) added to the noise-based one. */
    val sharpenThreshold: Float = 0.0025f,
    /** Max overshoot beyond the 3x3 neighbourhood (Oklab L): the halo limiter. */
    val sharpenOvershoot: Float = 0.006f,
    val coringSigmas: Float = 2.5f,
    /** Noise level at which sharpening is halved. */
    val sharpenNoiseRef: Float = 0.012f,
    /** Fraction of sharpening kept on flat/smooth areas (protected by texture confidence). */
    val sharpenFlatFloor: Float = 0.35f,
    val microContrast: Float = 0.025f,
) {
    fun sharpenFor(kind: SourceKind) = when (kind) {
        SourceKind.BURST_MERGED -> sharpenBurst
        SourceKind.SINGLE_SRGB_CURVE -> sharpenSingleSrgb
        SourceKind.SYSTEM_RENDERED -> sharpenSystemRendered
        SourceKind.HYBRID -> sharpenHybrid
    }

    companion object {
        val NATURAL_PREMIUM = ProcessingProfile(id = "natural_premium", displayName = "Natural Premium")
        val ALL = listOf(NATURAL_PREMIUM)
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: NATURAL_PREMIUM
    }
}

enum class OutputColorSpace { SRGB, DISPLAY_P3 }

class OutputOptions(
    val colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
    /** Produce an Ultra HDR gain map (quarter resolution) alongside the SDR rendition. */
    val gainMap: Boolean = false,
    /** Max HDR boost encoded in the gain map, in stops. */
    val gainMapHeadroomStops: Float = 2f,
    val dither: Boolean = true,
)
