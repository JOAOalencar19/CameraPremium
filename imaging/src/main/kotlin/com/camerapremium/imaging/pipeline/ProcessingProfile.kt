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
class ProcessingProfile(
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

    // ---- white balance ----
    /** Fraction of a warm (tungsten) illuminant that is kept, iPhone-like ambience. */
    val warmRetention: Float = 0.26f,
    /** Fraction of a very cool (shade/overcast) illuminant that is kept. */
    val coolRetention: Float = 0.10f,
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
    val skinSharpenScale: Float = 0.6f,

    // ---- noise ----
    val lumaNrStrength: Float = 2.0f,
    /** Fraction of the removed luminance noise added back as fine grain (prevents the plastic look). */
    val grainRetention: Float = 0.3f,
    val chromaNrStrength: Float = 2.5f,

    // ---- sharpening ----
    val sharpenBurst: Float = 0.6f,
    val sharpenSingleSrgb: Float = 0.35f,
    val sharpenSystemRendered: Float = 0.10f,
    val sharpenOvershoot: Float = 0.012f,
    val coringSigmas: Float = 2.0f,
    val microContrast: Float = 0.04f,
) {
    fun sharpenFor(kind: SourceKind) = when (kind) {
        SourceKind.BURST_MERGED -> sharpenBurst
        SourceKind.SINGLE_SRGB_CURVE -> sharpenSingleSrgb
        SourceKind.SYSTEM_RENDERED -> sharpenSystemRendered
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
