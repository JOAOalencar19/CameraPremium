package com.camerapremium.imaging.pipeline

import com.camerapremium.imaging.image.MergedYuv
import com.camerapremium.imaging.image.OutputSink
import com.camerapremium.imaging.image.SourceImage
import com.camerapremium.imaging.util.IndexedTask
import com.camerapremium.imaging.util.WorkerPool
import kotlin.math.sqrt

/**
 * Entry point of the photographic pipeline. Platform-free: the Android app and the desktop
 * tools both call [process] with their own source/sink adapters.
 */
class PhotoPipeline(private val pool: WorkerPool) {

    class Result(
        val width: Int,
        val height: Int,
        /** log2 HDR gain per 4x4 block (>= 0), or null when not requested. */
        val gainMap: FloatArray?,
        val gainMapWidth: Int,
        val gainMapHeight: Int,
        val stats: SceneStats,
        val noise: NoiseModel,
        val whiteBalance: WhiteBalance.Decision,
        val timingsMs: LinkedHashMap<String, Long>,
    ) {
        fun summary(): String = buildString {
            append(stats.toString())
            append(" | wb cct=").append(whiteBalance.estimatedCct?.toInt() ?: "?")
            append(" render=").append(whiteBalance.renderCct?.toInt() ?: "-")
            append(" cast=").append("%.4f,%.4f".format(whiteBalance.castAb[0], whiteBalance.castAb[1]))
            append(" | ").append(noise.toString())
            append(" | ms=").append(timingsMs.toString())
        }
    }

    private val workspaces = arrayOfNulls<TileRenderer.Workspace>(pool.size)

    private fun workspace(slot: Int): TileRenderer.Workspace {
        var ws = workspaces[slot]
        if (ws == null) {
            val side = TileRenderer.TILE + 2 * TileRenderer.HALO
            ws = TileRenderer.Workspace(side * side)
            workspaces[slot] = ws
        }
        return ws
    }

    /** Frees the per-thread scratch buffers (~7 MB per worker). */
    fun releaseBuffers() { for (i in workspaces.indices) workspaces[i] = null }

    fun process(
        src: SourceImage,
        info: CaptureInfo,
        profile: ProcessingProfile,
        output: OutputOptions,
        sink: OutputSink,
        merged: MergedYuv? = null,
    ): Result {
        val timings = LinkedHashMap<String, Long>()
        var t = System.nanoTime()
        fun lap(name: String) { val now = System.nanoTime(); timings[name] = (now - t) / 1_000_000; t = now }

        val plan = SceneAnalyzer.analyze(src, info, profile)
        lap("analysis")

        // Regions where the burst merge rejected frames are noisier: tell the denoiser.
        var nf: FloatArray? = null
        var nfW = 0
        if (merged != null && merged.framesMerged > 1) {
            val eff = merged.effectiveFrames
            val sorted = eff.copyOf(); sorted.sort()
            val median = sorted[sorted.size / 2].coerceAtLeast(1f)
            nf = FloatArray(eff.size) { sqrt(median / eff[it].coerceAtLeast(1f)).coerceIn(1f, 2.5f) }
            nfW = merged.mapWidth
        }
        val look = ColorLook(profile, info.lens.saturationScale)
        val renderer = TileRenderer(src, plan, profile, output, look, nf, nfW, info.lens.noiseScale)

        // ---- noise model from sampled patches of the rendered (pre-NR) image
        val builder = NoiseModel.Builder()
        val p = 12
        val target = 2400
        val step = maxOf(p * 2, sqrt(src.width.toDouble() * src.height / target).toInt())
        val cols = (src.width - p) / step
        val rows = (src.height - p) / step
        pool.forEach(rows.coerceAtLeast(0), IndexedTask { ry, slot ->
            val ws = workspace(slot)
            val pl = FloatArray(p * p); val pa = FloatArray(p * p); val pb = FloatArray(p * p)
            for (cx in 0 until cols) {
                val x = cx * step + (ry * 7 % step) / 2
                val y = ry * step + (cx * 13 % step) / 2
                renderer.renderBase(x, y, p, p, ws)
                System.arraycopy(ws.okL, 0, pl, 0, p * p)
                System.arraycopy(ws.okA, 0, pa, 0, p * p)
                System.arraycopy(ws.okB, 0, pb, 0, p * p)
                builder.addPatch(pl, pa, pb, p)
            }
        })
        val noise = builder.build()
        renderer.noise = noise
        lap("noise_model")

        // ---- tiles
        val tile = TileRenderer.TILE
        val tx = (src.width + tile - 1) / tile
        val ty = (src.height + tile - 1) / tile
        val gw = (src.width + 3) / 4; val gh = (src.height + 3) / 4
        val gain = if (output.gainMap) FloatArray(gw * gh) else null
        pool.forEach(tx * ty, IndexedTask { idx, slot ->
            val ws = workspace(slot)
            val x0 = (idx % tx) * tile; val y0 = (idx / tx) * tile
            val w = minOf(tile, src.width - x0); val h = minOf(tile, src.height - y0)
            renderer.renderTile(x0, y0, w, h, ws, gain, gw)
            sink.writeTile(x0, y0, w, h, ws.argb)
        })
        lap("render")
        if (gain != null) {
            val maxStops = output.gainMapHeadroomStops
            for (i in gain.indices) gain[i] = gain[i].coerceIn(0f, maxStops)
        }
        return Result(src.width, src.height, gain, gw, gh, plan.stats, noise, plan.whiteBalance, timings)
    }
}
