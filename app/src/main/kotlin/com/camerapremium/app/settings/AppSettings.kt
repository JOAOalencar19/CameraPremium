package com.camerapremium.app.settings

import android.content.Context
import android.content.SharedPreferences

enum class FlashMode { AUTO, ON, OFF }
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    R4_3("4:3", 4, 3), R16_9("16:9", 16, 9), R1_1("1:1", 1, 1);

    val value: Float get() = w.toFloat() / h
}
enum class CaptureMode(val label: String) { PHOTO("FOTO"), NIGHT("NOITE"), PORTRAIT("RETRATO"), PRO("PRO") }
enum class ProcessingChoice { NATURAL_PREMIUM, SYSTEM_ONLY }
enum class LensSelection { AUTOMATIC, PHYSICAL_PREFERRED }
/** NATIVE keeps the ISP's own noise reduction, sharpening and tone curve on every burst frame. */
/** HYBRID (default): HAL tone/color, ISP spatial NR minimized and sharpening off, multi-frame merge.
 *  NATIVE: every frame with the ISP's HQ NR/sharpening. CUSTOM_BURST: sRGB curve, own HDR. */
enum class CaptureSource { HYBRID, NATIVE, CUSTOM_BURST }
enum class Sharpness(val scale: Float) { SOFT(0.7f), NATURAL(1f), CRISP(1.25f) }

/** Thin typed wrapper over SharedPreferences. */
class AppSettings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("camera_premium", Context.MODE_PRIVATE)

    var flash: FlashMode
        get() = enumOr(p.getString("flash", null), FlashMode.OFF)
        set(v) = p.edit().putString("flash", v.name).apply()
    var aspect: AspectRatio
        get() = enumOr(p.getString("aspect", null), AspectRatio.R4_3)
        set(v) = p.edit().putString("aspect", v.name).apply()
    var timerSeconds: Int
        get() = p.getInt("timer", 0)
        set(v) = p.edit().putInt("timer", v).apply()
    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.edit().putBoolean("grid", v).apply()
    var processing: ProcessingChoice
        get() = enumOr(p.getString("processing", null), ProcessingChoice.NATURAL_PREMIUM)
        set(v) = p.edit().putString("processing", v.name).apply()
    var profileId: String
        get() = p.getString("profile", "natural_premium") ?: "natural_premium"
        set(v) = p.edit().putString("profile", v).apply()
    var multiFrame: Boolean
        get() = p.getBoolean("multiframe", true)
        set(v) = p.edit().putBoolean("multiframe", v).apply()
    var ultraHdr: Boolean
        get() = p.getBoolean("ultrahdr", false)
        set(v) = p.edit().putBoolean("ultrahdr", v).apply()
    var displayP3: Boolean
        get() = p.getBoolean("p3", false)
        set(v) = p.edit().putBoolean("p3", v).apply()
    var highResolution: Boolean
        get() = p.getBoolean("highres", false)
        set(v) = p.edit().putBoolean("highres", v).apply()
    var raw: Boolean
        get() = p.getBoolean("raw", false)
        set(v) = p.edit().putBoolean("raw", v).apply()
    var saveOriginalToGallery: Boolean
        get() = p.getBoolean("save_original", false)
        set(v) = p.edit().putBoolean("save_original", v).apply()
    var mirrorFront: Boolean
        get() = p.getBoolean("mirror_front", false)
        set(v) = p.edit().putBoolean("mirror_front", v).apply()
    var screenFlash: Boolean
        get() = p.getBoolean("screen_flash", true)
        set(v) = p.edit().putBoolean("screen_flash", v).apply()
    var lensSelection: LensSelection
        get() = enumOr(p.getString("lens_sel", null), LensSelection.AUTOMATIC)
        set(v) = p.edit().putString("lens_sel", v.name).apply()
    var shutterSound: Boolean
        get() = p.getBoolean("shutter_sound", true)
        set(v) = p.edit().putBoolean("shutter_sound", v).apply()
    var jpegQuality: Int
        get() = p.getInt("jpeg_q", 95)
        set(v) = p.edit().putInt("jpeg_q", v).apply()
    var captureSource: CaptureSource
        get() = enumOr(p.getString("capture_source_v2", null), CaptureSource.HYBRID)
        set(v) = p.edit().putString("capture_source_v2", v.name).apply()
    var sharpness: Sharpness
        get() = enumOr(p.getString("sharpness", null), Sharpness.NATURAL)
        set(v) = p.edit().putString("sharpness", v.name).apply()
    var skinSmoothing: Boolean
        get() = p.getBoolean("skin_smoothing", false)
        set(v) = p.edit().putBoolean("skin_smoothing", v).apply()
    /** Use the manufacturer's Night extension in NIGHT mode when the device exposes it. */
    var vendorNight: Boolean
        get() = p.getBoolean("vendor_night", true)
        set(v) = p.edit().putBoolean("vendor_night", v).apply()
    /** Use the manufacturer's Auto extension in PHOTO mode when available. */
    var vendorAuto: Boolean
        get() = p.getBoolean("vendor_auto", false)
        set(v) = p.edit().putBoolean("vendor_auto", v).apply()
    var compareHintsShown: Int
        get() = p.getInt("compare_hints", 0)
        set(v) = p.edit().putInt("compare_hints", v).apply()
    var showPerf: Boolean
        get() = p.getBoolean("show_perf", false)
        set(v) = p.edit().putBoolean("show_perf", v).apply()
    var lastMode: CaptureMode
        get() = enumOr(p.getString("mode", null), CaptureMode.PHOTO)
        set(v) = p.edit().putString("mode", v.name).apply()
    var useFront: Boolean
        get() = p.getBoolean("use_front", false)
        set(v) = p.edit().putBoolean("use_front", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(s: String?, d: T): T =
        if (s == null) d else try { java.lang.Enum.valueOf(T::class.java, s) } catch (_: Exception) { d }
}
