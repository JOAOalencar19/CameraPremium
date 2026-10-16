package com.camerapremium.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Gallery writes through MediaStore (no storage permission needed on Android 10+). */
object MediaSaver {
    const val ALBUM = "DCIM/CameraPremium"
    const val ORIGINALS = "DCIM/CameraPremium/Originais"
    const val RAW = "DCIM/CameraPremium/RAW"

    fun insert(ctx: Context, bytes: ByteArray, name: String, relativePath: String, mime: String, takenMs: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore recusou a insercao")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Replaces the content of an item this app created (user-confirmed swap in the review screen). */
    fun overwrite(ctx: Context, uri: Uri, bytes: ByteArray) {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }

    fun read(ctx: Context, uri: Uri): ByteArray? = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }

    fun saveText(ctx: Context, name: String, text: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CameraPremium")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return uri
    }
}

/** One capture: the processed photo, the preserved original and what was done to it. */
class CaptureRecord(
    val id: Long,
    val timestampMs: Long,
    var processedUri: String?,
    /** Private copy of the original (always kept for comparison). */
    var originalPath: String?,
    var originalGalleryUri: String?,
    /** Private copy of the processed version when the user made the original the gallery photo. */
    var processedPath: String?,
    var dngUri: String?,
    /** "processed" or "original": which version the gallery item currently holds. */
    var preferred: String,
    val summary: String,
    val timings: String,
    val lensLabel: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("t", timestampMs); put("p", processedUri ?: ""); put("o", originalPath ?: "")
        put("og", originalGalleryUri ?: ""); put("pp", processedPath ?: ""); put("dng", dngUri ?: "")
        put("pref", preferred); put("sum", summary); put("tim", timings); put("lens", lensLabel)
    }

    companion object {
        fun fromJson(j: JSONObject) = CaptureRecord(
            j.getLong("id"), j.getLong("t"), j.optString("p").ifEmpty { null }, j.optString("o").ifEmpty { null },
            j.optString("og").ifEmpty { null }, j.optString("pp").ifEmpty { null }, j.optString("dng").ifEmpty { null },
            j.optString("pref", "processed"), j.optString("sum"), j.optString("tim"), j.optString("lens"),
        )
    }
}

/** Small JSON-backed history of recent captures (keeps the last [MAX] originals on device). */
class CaptureStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "captures.json")
    val originalsDir: File = File(ctx.filesDir, "originals").apply { mkdirs() }

    @Synchronized
    fun all(): List<CaptureRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { CaptureRecord.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun put(r: CaptureRecord) {
        val list = all().filter { it.id != r.id }.toMutableList()
        list.add(0, r)
        while (list.size > MAX) {
            val old = list.removeAt(list.size - 1)
            old.originalPath?.let { File(it).delete() }
            old.processedPath?.let { File(it).delete() }
        }
        val arr = JSONArray()
        for (x in list) arr.put(x.toJson())
        file.writeText(arr.toString())
    }

    @Synchronized
    fun remove(id: Long) {
        val list = all().filter { it.id != id }
        val arr = JSONArray(); for (x in list) arr.put(x.toJson())
        file.writeText(arr.toString())
    }

    fun get(id: Long): CaptureRecord? = all().firstOrNull { it.id == id }
    fun latest(): CaptureRecord? = all().firstOrNull()

    companion object { const val MAX = 60 }
}
