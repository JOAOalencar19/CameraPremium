package com.camerapremium.tools

import com.camerapremium.imaging.burst.BurstMerger
import com.camerapremium.imaging.image.ArgbImage
import com.camerapremium.imaging.image.ArgbSink
import com.camerapremium.imaging.image.ArgbSource
import com.camerapremium.imaging.image.MergedYuvSource
import com.camerapremium.imaging.metrics.ImageMetrics
import com.camerapremium.imaging.pipeline.CaptureInfo
import com.camerapremium.imaging.pipeline.OutputColorSpace
import com.camerapremium.imaging.pipeline.OutputOptions
import com.camerapremium.imaging.pipeline.PhotoPipeline
import com.camerapremium.imaging.pipeline.ProcessingProfile
import com.camerapremium.imaging.pipeline.SourceKind
import com.camerapremium.imaging.util.WorkerPool
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop tool for developing and validating the pipeline without a phone.
 *
 *   process <in> <out> [--kind system|single|burst] [--iso N --exp-ms T --f N] [--p3] [--side-by-side file]
 *   burst <out> <frame1> <frame2> ...   merge real JPEG/PNG frames of a handheld burst, then process
 *   compare <a> <b> [--roiA x,y,w,h] [--roiB x,y,w,h]   objective comparison (e.g. Galaxy vs iPhone)
 *   synth <dir>                          write the synthetic validation scenes
 *   test                                 run the imaging test suite
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(1) }
    val opts = Options(args.drop(1))
    when (args[0]) {
        "process" -> cmdProcess(opts)
        "burst" -> cmdBurst(opts)
        "compare" -> cmdCompare(opts)
        "synth" -> cmdSynth(opts)
        "test" -> exitProcess(ImagingTests.runAll())
        else -> { usage(); exitProcess(1) }
    }
}

private fun usage() {
    println(
        """
        uso:
          process <entrada> <saida> [--kind system|single|burst] [--iso N] [--exp-ms T] [--f N] [--p3] [--side-by-side arquivo]
          burst <saida> <quadro1> <quadro2> ...
          compare <a> <b> [--roiA x,y,w,h] [--roiB x,y,w,h]
          synth <pasta>
          test
        """.trimIndent()
    )
}

class Options(args: List<String>) {
    val positional = ArrayList<String>()
    val named = HashMap<String, String>()
    val flags = HashSet<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                if (i + 1 < args.size && !args[i + 1].startsWith("--") && key !in BOOLEAN) { named[key] = args[i + 1]; i += 2; continue }
                flags.add(key)
            } else positional.add(a)
            i++
        }
    }

    companion object { val BOOLEAN = setOf("p3") }
}

private fun infoFrom(o: Options, kind: SourceKind, frames: Int = 1) = CaptureInfo(
    sourceKind = kind,
    framesMerged = frames,
    iso = o.named["iso"]?.toInt() ?: 0,
    exposureTimeNs = ((o.named["exp-ms"]?.toDouble() ?: 0.0) * 1e6).toLong(),
    aperture = o.named["f"]?.toFloat() ?: 0f,
)

private fun kindOf(s: String?) = when (s) {
    "single" -> SourceKind.SINGLE_SRGB_CURVE
    "burst" -> SourceKind.BURST_MERGED
    else -> SourceKind.SYSTEM_RENDERED
}

private fun cmdProcess(o: Options) {
    val input = File(o.positional[0]); val output = File(o.positional[1])
    val img = DesktopIO.read(input)
    val out = ArgbImage(img.width, img.height)
    val opt = OutputOptions(colorSpace = if ("p3" in o.flags) OutputColorSpace.DISPLAY_P3 else OutputColorSpace.SRGB)
    WorkerPool().use { pool ->
        val t0 = System.nanoTime()
        val res = PhotoPipeline(pool).process(ArgbSource(img), infoFrom(o, kindOf(o.named["kind"])), ProcessingProfile.NATURAL_PREMIUM, opt, ArgbSink(out))
        println("processado em %d ms".format((System.nanoTime() - t0) / 1_000_000))
        println(res.summary())
    }
    DesktopIO.write(out, output)
    o.named["side-by-side"]?.let { DesktopIO.write(DesktopIO.sideBySide(DesktopIO.fit(img, 1400), DesktopIO.fit(out, 1400)), File(it)) }
}

private fun cmdBurst(o: Options) {
    val output = File(o.positional[0])
    val frames = o.positional.drop(1).map { DesktopIO.read(File(it)) }
    require(frames.size >= 2) { "precisa de pelo menos 2 quadros" }
    WorkerPool().use { pool ->
        val t0 = System.nanoTime()
        val merger = BurstMerger(pool)
        for (f in frames) merger.add(Synthetic.toYuv(f))
        val merged = merger.finish()
        println("fusao: %d quadros usados, %d rejeitados, %d ms".format(merged.framesMerged, merged.framesRejected, (System.nanoTime() - t0) / 1_000_000))
        val out = ArgbImage(merged.width, merged.height)
        val res = PhotoPipeline(pool).process(
            MergedYuvSource(merged), infoFrom(o, SourceKind.SYSTEM_RENDERED, merged.framesMerged),
            ProcessingProfile.NATURAL_PREMIUM, OutputOptions(), ArgbSink(out), merged,
        )
        println(res.summary())
        DesktopIO.write(out, output)
    }
}

private fun cmdCompare(o: Options) {
    val a = DesktopIO.fit(DesktopIO.read(File(o.positional[0])), 2000)
    val b = DesktopIO.fit(DesktopIO.read(File(o.positional[1])), 2000)
    val ra = o.named["roiA"]?.let { ImageMetrics.Region.parse(it) } ?: ImageMetrics.Region.FULL
    val rb = o.named["roiB"]?.let { ImageMetrics.Region.parse(it) } ?: ra
    val sa = ImageMetrics.stats(a, ra); val sb = ImageMetrics.stats(b, rb)
    println("== A: ${o.positional[0]}"); print(sa.format())
    println("== B: ${o.positional[1]}"); print(sb.format())
    println("== Diferencas (B - A)")
    println("dL* medio: %+.2f   dC medio: %+.2f   dE2000 das medias: %.2f".format(
        sb.meanLab[0] - sa.meanLab[0], sb.meanChroma - sa.meanChroma, ImageMetrics.regionDeltaE(a, ra, b, rb)))
    println("dRuido: %+.3f   dTextura: %+.3f   dOvershoot: %+.4f   dBrancoEstourado: %+.2f%%".format(
        sb.noiseSigma - sa.noiseSigma, sb.midBandEnergy - sa.midBandEnergy, sb.overshootIndex - sa.overshootIndex,
        (sb.whiteClipped - sa.whiteClipped) * 100))
    println("Lembrete: nitidez/saturacao maiores NAO significam imagem melhor; use junto da avaliacao visual.")
}

private fun cmdSynth(o: Options) {
    val dir = File(o.positional.getOrElse(0) { "synthetic" })
    dir.mkdirs()
    DesktopIO.write(Synthetic.patches(Synthetic.CHECKER, 6, 120), File(dir, "checker.png"))
    DesktopIO.write(Synthetic.patches(Synthetic.SKIN, 5, 140), File(dir, "skin_tones.png"))
    DesktopIO.write(Synthetic.hdrWindow(), File(dir, "hdr_window.png"))
    DesktopIO.write(Synthetic.edges(), File(dir, "edges.png"))
    DesktopIO.write(Synthetic.addNoise(Synthetic.patches(Synthetic.CHECKER, 6, 120), 6f), File(dir, "checker_noisy.png"))
    println("cenas escritas em ${dir.absolutePath}")
}
