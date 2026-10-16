package com.camerapremium.tools

import com.camerapremium.imaging.burst.BurstMerger
import com.camerapremium.imaging.color.ColorMath
import com.camerapremium.imaging.image.ArgbImage
import com.camerapremium.imaging.image.ArgbSink
import com.camerapremium.imaging.image.ArgbSource
import com.camerapremium.imaging.image.MergedYuvSource
import com.camerapremium.imaging.image.YuvFrameSource
import com.camerapremium.imaging.metrics.ImageMetrics
import com.camerapremium.imaging.pipeline.CaptureInfo
import com.camerapremium.imaging.pipeline.FaceRegion
import com.camerapremium.imaging.pipeline.OutputOptions
import com.camerapremium.imaging.pipeline.PhotoPipeline
import com.camerapremium.imaging.pipeline.ProcessingProfile
import com.camerapremium.imaging.pipeline.SensorCalibration
import com.camerapremium.imaging.pipeline.SourceKind
import com.camerapremium.imaging.pipeline.ToneCurve
import com.camerapremium.imaging.pipeline.WhiteBalance
import com.camerapremium.imaging.util.WorkerPool
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Imaging test suite. Each test encodes a photographic requirement as a measurable property.
 * Run with: imaging-cli test   (writes visual outputs to build/test-output for inspection)
 */
object ImagingTests {
    private var failures = 0
    private var passes = 0
    private val outDir = File(System.getProperty("cp.testOut", "build/test-output"))
    private lateinit var pool: WorkerPool

    private fun check(name: String, ok: Boolean, detail: String) {
        if (ok) passes++ else failures++
        println((if (ok) "  OK   " else "  FALHA") + "  " + name + "  [" + detail + "]")
    }

    fun runAll(): Int {
        outDir.mkdirs()
        pool = WorkerPool()
        val tests = listOf(
            "cor: transferencias e Oklab" to { colorBasics() },
            "cor: CIEDE2000 (dados de Sharma)" to { deltaE() },
            "wb: CCT pela calibracao do sensor" to { cctEstimate() },
            "tom: curva monotona e sem clip" to { toneCurve() },
            "neutros permanecem neutros" to { neutralRamp() },
            "carta de cores: desvios contidos" to { checker() },
            "tons de pele: diversidade preservada" to { skinTones() },
            "hdr: janela em contraluz" to { hdrWindow() },
            "nitidez: sem halos" to { sharpenHalo() },
            "ruido: reduz sem plastificar" to { noise() },
            "cabelo/pelo nao e ruido" to { hairTexture() },
            "fusao: reduz ruido e evita fantasmas" to { burst() },
            "desempenho 12 MP" to { perf() },
        )
        for ((name, body) in tests) {
            println("- $name")
            try { body() } catch (t: Throwable) { failures++; println("  FALHA  excecao: $t"); t.printStackTrace() }
        }
        pool.close()
        println("\nresultado: $passes ok, $failures falha(s). Saidas visuais em ${outDir.absolutePath}")
        return if (failures == 0) 0 else 1
    }

    private fun process(img: ArgbImage, kind: SourceKind, info: CaptureInfo? = null, opt: OutputOptions = OutputOptions(dither = false)): Pair<ArgbImage, PhotoPipeline.Result> {
        val out = ArgbImage(img.width, img.height)
        val res = PhotoPipeline(pool).process(ArgbSource(img), info ?: CaptureInfo(kind), ProcessingProfile.NATURAL_PREMIUM, opt, ArgbSink(out))
        return out to res
    }

    private fun rgbAt(img: ArgbImage, x: Int, y: Int): IntArray {
        val c = img.pixels[y * img.width + x]
        return intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    private fun meanRect(img: ArgbImage, x0: Int, y0: Int, w: Int, h: Int): DoubleArray {
        val s = DoubleArray(3)
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) { val p = rgbAt(img, x, y); for (k in 0..2) s[k] += p[k].toDouble() }
        for (k in 0..2) s[k] /= (w * h).toDouble()
        return s
    }

    private fun labOf(rgb: DoubleArray): DoubleArray = ColorMath.linearSrgbToLab(
        ColorMath.srgbToLinear((rgb[0] / 255).toFloat()), ColorMath.srgbToLinear((rgb[1] / 255).toFloat()), ColorMath.srgbToLinear((rgb[2] / 255).toFloat()))

    // ------------------------------------------------------------------------------ tests

    private fun colorBasics() {
        var maxErr = 0f
        for (i in 0..255) { val v = i / 255f; maxErr = maxOf(maxErr, abs(ColorMath.encode(ColorMath.decode(v)) - v)) }
        check("sRGB ida-e-volta", maxErr < 1e-3f, "erro max %.5f".format(maxErr))
        val lab = FloatArray(3); val rgb = FloatArray(3)
        var okErr = 0f
        for (r in 0..10) for (g in 0..10) for (b in 0..10) {
            ColorMath.linearToOklab(r / 10f, g / 10f, b / 10f, lab)
            ColorMath.oklabToLinear(lab[0], lab[1], lab[2], rgb)
            okErr = maxOf(okErr, abs(rgb[0] - r / 10f), abs(rgb[1] - g / 10f), abs(rgb[2] - b / 10f))
        }
        check("Oklab ida-e-volta", okErr < 1e-4f, "erro max %.6f".format(okErr))
        ColorMath.linearToOklab(0.18f, 0.18f, 0.18f, lab)
        check("cinza tem a=b=0", abs(lab[1]) < 1e-3f && abs(lab[2]) < 1e-3f, "a=%.5f b=%.5f".format(lab[1], lab[2]))
    }

    private fun deltaE() {
        val d = ColorMath.deltaE2000(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485))
        check("par 1", abs(d - 2.0425) < 1e-3, "%.4f (esperado 2.0425)".format(d))
        val d2 = ColorMath.deltaE2000(doubleArrayOf(50.0, -1.0, 2.0), doubleArrayOf(50.0, 0.0, 0.0))
        check("par 9", abs(d2 - 2.3669) < 1e-3, "%.4f (esperado 2.3669)".format(d2))
    }

    private fun cctEstimate() {
        // A camera whose native space is linear sRGB: color matrix = XYZ -> sRGB.
        val cal = SensorCalibration(ColorMath.XYZ_TO_SRGB, ColorMath.XYZ_TO_SRGB, 2856f, 6504f)
        for (t in floatArrayOf(2850f, 4000f, 5500f, 6500f, 8000f)) {
            val xy = ColorMath.whiteXyForCct(t)
            val n = ColorMath.apply3x3(ColorMath.XYZ_TO_SRGB, ColorMath.xyToXyz(xy[0], xy[1]))
            val gains = floatArrayOf(n[1] / n[0], 1f, 1f, n[1] / n[2])
            val est = WhiteBalance.estimateAwbCct(gains, cal)
            check("%.0fK".format(t), est != null && abs(est - t) / t < 0.04f, "estimado ${est?.toInt()}K")
        }
        val p = ProcessingProfile.NATURAL_PREMIUM
        val warm = WhiteBalance.renderCct(2850f, p)
        check("tungstenio mantem calor parcial", warm in 4000f..5800f, "renderizado a ${warm.toInt()}K")
        check("luz do dia nao muda", abs(WhiteBalance.renderCct(6000f, p) - 6504f) < 1f, "${WhiteBalance.renderCct(6000f, p).toInt()}K")
    }

    private fun toneCurve() {
        val c = ToneCurve(1.06f, 1.0f, 3.2f, 0.0018f)
        var prev = -1f; var mono = true
        var l = -14f
        while (l < 6f) { val d = c.display(l); if (d < prev - 1e-6f) mono = false; prev = d; l += 0.01f }
        check("monotona", mono, "")
        val mid = c.display(ToneCurve.LOG2_MID)
        check("cinza 18% ~ medio", mid in 0.15f..0.22f, "cinza18 -> %.3f (sRGB %d)".format(mid, (ColorMath.encode(mid) * 255).toInt()))
        check("highlight +3 stops ainda abaixo do branco", c.display(ToneCurve.LOG2_MID + 2.5f) < 0.97f, "%.3f".format(c.display(ToneCurve.LOG2_MID + 2.5f)))
    }

    private fun neutralRamp() {
        val w = 512; val h = 64
        val img = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) { val v = x * 255 / (w - 1); img.pixels[y * w + x] = Synthetic.rgb(v, v, v) }
        for (kind in SourceKind.values()) {
            val (out, _) = process(img, kind)
            var maxDev = 0
            for (x in 0 until w step 4) { val p = rgbAt(out, x, h / 2); maxDev = maxOf(maxDev, maxOf(p[0], p[1], p[2]) - minOf(p[0], p[1], p[2])) }
            var mono = true; var prev = -1
            for (x in 0 until w) { val v = rgbAt(out, x, h / 2)[1]; if (v < prev - 1) mono = false; prev = v }
            check("rampa $kind", maxDev <= 2 && mono, "desvio de canal max $maxDev, monotona=$mono")
        }
    }

    private fun checker() {
        val img = Synthetic.patches(Synthetic.CHECKER, 6, 120)
        val (out, _) = process(img, SourceKind.SYSTEM_RENDERED)
        DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "checker_antes_depois.png"))
        var sum = 0.0; var worst = 0.0; var worstName = ""
        var hueWorst = 0.0
        for ((k, _) in Synthetic.CHECKER.withIndex()) {
            val x0 = (k % 6) * 120 + 30; val y0 = (k / 6) * 120 + 30
            val a = labOf(meanRect(img, x0, y0, 60, 60)); val b = labOf(meanRect(out, x0, y0, 60, 60))
            val d = ColorMath.deltaE2000(a, b)
            sum += d; if (d > worst) { worst = d; worstName = Synthetic.CHECKER_NAMES[k] }
            val ca = sqrt(a[1] * a[1] + a[2] * a[2])
            if (ca > 15) {
                val ha = Math.toDegrees(Math.atan2(a[2], a[1])); val hb = Math.toDegrees(Math.atan2(b[2], b[1]))
                var dh = abs(ha - hb); if (dh > 180) dh = 360 - dh
                hueWorst = maxOf(hueWorst, dh)
            }
        }
        val mean = sum / 24
        // Rendered input: the look is intentionally gentle. Natural look = small, coherent deltas.
        check("dE2000 medio contido", mean < 4.0, "medio %.2f".format(mean))
        check("nenhum patch muda demais", worst < 8.0, "pior %.2f (%s)".format(worst, worstName))
        check("matizes preservados", hueWorst < 8.0, "maior desvio de matiz %.1f graus".format(hueWorst))
    }

    private fun skinTones() {
        val img = Synthetic.addNoise(Synthetic.patches(Synthetic.SKIN, 5, 140), 2f, 3)
        val faces = listOf(FaceRegion(0f, 0f, 1f, 1f))
        val (out, _) = process(img, SourceKind.SYSTEM_RENDERED, CaptureInfo(SourceKind.SYSTEM_RENDERED, faces = faces))
        DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "pele_antes_depois.png"))
        var maxHue = 0.0; var maxChromaGain = 0.0; var maxDl = 0.0
        val outL = ArrayList<Double>(); val inL = ArrayList<Double>()
        for ((k, _) in Synthetic.SKIN.withIndex()) {
            val x0 = (k % 5) * 140 + 40; val y0 = (k / 5) * 140 + 40
            val a = labOf(meanRect(img, x0, y0, 60, 60)); val b = labOf(meanRect(out, x0, y0, 60, 60))
            outL.add(b[0]); inL.add(a[0])
            val ca = sqrt(a[1] * a[1] + a[2] * a[2]); val cb = sqrt(b[1] * b[1] + b[2] * b[2])
            if (ca > 4) {
                val ha = Math.toDegrees(Math.atan2(a[2], a[1])); val hb = Math.toDegrees(Math.atan2(b[2], b[1]))
                maxHue = maxOf(maxHue, abs(ha - hb))
                maxChromaGain = maxOf(maxChromaGain, cb / ca - 1)
            }
            maxDl = maxOf(maxDl, abs(b[0] - a[0]))
        }
        // every pair keeps its lightness order and stays distinguishable (no homogenization)
        var ordered = true
        for (i in inL.indices) for (j in inL.indices) {
            val di = inL[i] - inL[j]; val dout = outL[i] - outL[j]
            if (abs(di) > 1.0 && (di * dout <= 0 || abs(dout) < abs(di) * 0.6)) ordered = false
        }
        check("matiz da pele estavel", maxHue < 5.0, "desvio max %.1f graus".format(maxHue))
        check("sem saturar a pele", maxChromaGain < 0.08, "ganho de croma max %+.1f%%".format(maxChromaGain * 100))
        check("sem clarear/escurecer a pele", maxDl < 4.0, "dL* max %.1f".format(maxDl))
        check("ordem/diversidade dos tons mantida", ordered, "L* entrada " + inL.joinToString(" ") { "%.0f".format(it) } + " / saida " + outL.joinToString(" ") { "%.0f".format(it) })
    }

    private fun hdrWindow() {
        val img = Synthetic.hdrWindow()
        val info = CaptureInfo(SourceKind.SINGLE_SRGB_CURVE, iso = 400, exposureTimeNs = 20_000_000, aperture = 1.7f, exposureBiasEv = -2f)
        val (out, res) = process(img, SourceKind.SINGLE_SRGB_CURVE, info)
        DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "hdr_janela_antes_depois.png"))
        val w = img.width; val h = img.height
        val sky = ImageMetrics.stats(out, ImageMetrics.Region(0.6f, 0.15f, 0.3f, 0.4f))
        val room = ImageMetrics.stats(out, ImageMetrics.Region(0.05f, 0.1f, 0.15f, 0.5f))
        val roomIn = ImageMetrics.stats(img, ImageMetrics.Region(0.05f, 0.1f, 0.15f, 0.5f))
        val face = labOf(meanRect(out, (0.3f * w).toInt() - 20, (0.45f * h).toInt() - 20, 40, 40))
        check("ceu sem estourar", sky.whiteClipped < 0.02, "branco estourado %.2f%% (canal azul saturado %.0f%%), L* p99 %.0f".format(sky.whiteClipped * 100, sky.clippedHighlights * 100, sky.lPercentiles[6]))
        check("nuvens com detalhe", sky.lPercentiles[6] - sky.lPercentiles[1] > 8, "faixa L* p5..p99 %.1f".format(sky.lPercentiles[6] - sky.lPercentiles[1]))
        check("interior recuperado", room.meanLab[0] > roomIn.meanLab[0] + 8, "L* %.1f -> %.1f".format(roomIn.meanLab[0], room.meanLab[0]))
        check("sem HDR exagerado (sombras com profundidade)", room.meanLab[0] < sky.meanLab[0] - 15, "sala %.1f vs ceu %.1f".format(room.meanLab[0], sky.meanLab[0]))
        check("rosto exposto razoavel", face[0] in 30.0..75.0, "L* rosto %.1f".format(face[0]))
        println("        " + res.stats)
    }

    private fun sharpenHalo() {
        val img = Synthetic.edges()
        val (out, _) = process(img, SourceKind.BURST_MERGED)
        DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "bordas_antes_depois.png"))
        // horizontal profile across the main step edge (x = w/3), on the top half
        val y = img.height / 4; val x = img.width / 3
        val inLo = rgbAt(img, x - 12, y)[1]; val inHi = rgbAt(img, x + 12, y)[1]
        val outLo = rgbAt(out, x - 12, y)[1]; val outHi = rgbAt(out, x + 12, y)[1]
        var minNear = 255; var maxNear = 0
        for (dx in -6..6) { val v = rgbAt(out, x + dx, y)[1]; minNear = minOf(minNear, v); maxNear = maxOf(maxNear, v) }
        val under = outLo - minNear; val over = maxNear - outHi
        check("overshoot pequeno na borda", under <= 6 && over <= 6, "sub=$under sobre=$over (platos $inLo/$inHi -> $outLo/$outHi)")
        val sIn = ImageMetrics.stats(img); val sOut = ImageMetrics.stats(out)
        check("nitidez aumenta moderadamente", sOut.gradientEnergy in sIn.gradientEnergy * 1.0..sIn.gradientEnergy * 1.6,
            "gradiente %.3f -> %.3f".format(sIn.gradientEnergy, sOut.gradientEnergy))
    }

    private fun noise() {
        val flat = Synthetic.addNoise(Synthetic.flat(400, 300, 118), 6f, 11)
        val (out, res) = process(flat, SourceKind.SINGLE_SRGB_CURVE)
        val sIn = ImageMetrics.stats(flat); val sOut = ImageMetrics.stats(out)
        val ratio = sOut.noiseSigma / sIn.noiseSigma
        check("ruido reduzido", ratio < 0.75, "sigma L* %.2f -> %.2f".format(sIn.noiseSigma, sOut.noiseSigma))
        check("grao natural mantido (nao plastico)", ratio > 0.12, "razao %.2f".format(ratio))
        // fine texture must survive
        val tex = ArgbImage(400, 300)
        for (y in 0 until 300) for (x in 0 until 400) { val v = (118 + 30 * Math.sin(x * 1.3) * Math.sin(y * 1.1)).toInt(); tex.pixels[y * 400 + x] = Synthetic.rgb(v, v, v) }
        val texN = Synthetic.addNoise(tex, 3f, 5)
        val (texOut, _) = process(texN, SourceKind.SINGLE_SRGB_CURVE)
        val tIn = ImageMetrics.stats(texN).midBandEnergy; val tOut = ImageMetrics.stats(texOut).midBandEnergy
        check("textura fina preservada", tOut > tIn * 0.7, "energia %.2f -> %.2f".format(tIn, tOut))
        println("        " + res.noise)
        DesktopIO.write(DesktopIO.sideBySide(texN, texOut), File(outDir, "textura_antes_depois.png"))
    }

    private fun hairTexture() {
        // many thin oriented strands, like hair/fur/beard: must keep (almost) all texture energy
        val w = 400; val h = 300
        val v = FloatArray(w * h) { 0.45f }
        val rnd = java.util.Random(21)
        repeat(900) {
            var x = rnd.nextFloat() * w; var y = rnd.nextFloat() * h
            val ang = 1.1f + rnd.nextFloat() * 0.4f
            val dx = Math.cos(ang.toDouble()).toFloat(); val dy = Math.sin(ang.toDouble()).toFloat()
            val bright = if (rnd.nextBoolean()) 0.25f else -0.2f
            repeat(40) {
                val xi = x.toInt(); val yi = y.toInt()
                if (xi in 0 until w && yi in 0 until h) v[yi * w + xi] += bright
                x += dx; y += dy
            }
        }
        val b = Synthetic.gaussianBlur(v, w, h, 0.6f)
        val img = ArgbImage(w, h)
        for (i in b.indices) { val q = (b[i].coerceIn(0f, 1f) * 255).toInt(); img.pixels[i] = Synthetic.rgb(q, (q * 0.85).toInt(), (q * 0.6).toInt()) }
        for (kind in arrayOf(SourceKind.SYSTEM_RENDERED, SourceKind.SINGLE_SRGB_CURVE)) {
            val (out, _) = process(img, kind)
            val eIn = ImageMetrics.stats(img).midBandEnergy; val eOut = ImageMetrics.stats(out).midBandEnergy
            check("textura de fios mantida ($kind)", eOut > eIn * 0.9, "energia %.2f -> %.2f".format(eIn, eOut))
            if (kind == SourceKind.SYSTEM_RENDERED) DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "fios_antes_depois.png"))
        }
    }

    private fun burst() {
        val clean = Synthetic.naturalScene()
        val sigma = 8f
        val merger = BurstMerger(pool)
        val shifts = arrayOf(intArrayOf(0, 0), intArrayOf(3, -2), intArrayOf(-4, 1), intArrayOf(2, 5), intArrayOf(-1, -3), intArrayOf(5, 2))
        for ((i, s) in shifts.withIndex()) {
            // a red square moving across frames: must not leave ghosts
            val sq = intArrayOf(100 + i * 60, 300, 50)
            merger.add(Synthetic.burstFrame(clean, s[0], s[1], sigma, 100L + i, sq))
        }
        val merged = merger.finish()
        val single = Synthetic.burstFrame(clean, 0, 0, sigma, 999L)
        fun lumaNoise(src: com.camerapremium.imaging.image.SourceImage): Double {
            val w = src.width; val h = src.height
            val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
            src.readEncoded(0, 0, w, h, r, g, b)
            val ref = Synthetic.toYuv(clean)
            var s = 0.0; var n = 0
            for (y in 0 until 250) for (x in 0 until w) {
                val yv = (0.299 * r[y * w + x] + 0.587 * g[y * w + x] + 0.114 * b[y * w + x]) * 255
                val d = yv - (ref.y[y * ref.yStride + x].toInt() and 0xFF); s += d * d; n++
            }
            return sqrt(s / n)
        }
        val nMerged = lumaNoise(MergedYuvSource(merged))
        val nSingle = lumaNoise(YuvFrameSource(single))
        check("ruido cai com a fusao", nMerged < nSingle / 1.8, "rms %.2f -> %.2f (%d quadros, %d rejeitados)".format(nSingle, nMerged, merged.framesMerged, merged.framesRejected))
        // ghosting: in the reference the square is at x=100 (frame 0 unless another was sharper);
        // other positions must not show a faded red square.
        val w = merged.width; val h = merged.height
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        MergedYuvSource(merged).readEncoded(0, 0, w, h, r, g, b)
        var ghosts = 0; var squares = 0
        for (i in shifts.indices) {
            val cx = 100 + i * 60 + 25; val cy = 325
            val redness = (r[cy * w + cx] - g[cy * w + cx]) * 255
            if (redness > 120) squares++ else if (redness > 25) ghosts++
        }
        check("sem fantasmas do objeto em movimento", ghosts == 0 && squares == 1, "quadrados nitidos=$squares fantasmas=$ghosts")
        val out = ArgbImage(w, h)
        PhotoPipeline(pool).process(MergedYuvSource(merged), CaptureInfo(SourceKind.BURST_MERGED, framesMerged = merged.framesMerged), ProcessingProfile.NATURAL_PREMIUM, OutputOptions(), ArgbSink(out), merged)
        val singleImg = ArgbImage(w, h)
        val fr = YuvFrameSource(single); val rr = FloatArray(w * h); val gg = FloatArray(w * h); val bb = FloatArray(w * h)
        fr.readEncoded(0, 0, w, h, rr, gg, bb)
        for (i in 0 until w * h) singleImg.pixels[i] = Synthetic.rgb((rr[i] * 255).toInt(), (gg[i] * 255).toInt(), (bb[i] * 255).toInt())
        DesktopIO.write(DesktopIO.sideBySide(singleImg, out), File(outDir, "fusao_quadro_unico_vs_fusao.png"))
    }

    private fun perf() {
        val w = 4000; val h = 3000
        val img = ArgbImage(w, h)
        val rnd = java.util.Random(1)
        for (i in img.pixels.indices) { val v = 60 + (i % w) * 120 / w + rnd.nextInt(8); img.pixels[i] = Synthetic.rgb(v, v - 10, v - 20) }
        val out = ArgbImage(w, h)
        val t0 = System.nanoTime()
        val res = PhotoPipeline(pool).process(ArgbSource(img), CaptureInfo(SourceKind.SINGLE_SRGB_CURVE), ProcessingProfile.NATURAL_PREMIUM, OutputOptions(gainMap = true), ArgbSink(out))
        val ms = (System.nanoTime() - t0) / 1_000_000
        check("pipeline 12 MP neste host (${pool.size} threads)", ms < 20_000, "$ms ms ${res.timingsMs}")
        // burst merge throughput: 4 frames 12 MP
        val frame = Synthetic.toYuv(img)
        val merger = BurstMerger(pool)
        val t1 = System.nanoTime()
        for (i in 0 until 4) merger.add(com.camerapremium.imaging.image.YuvFrame(frame.width, frame.height, frame.y.copyOf(), frame.yStride, frame.u.copyOf(), frame.v.copyOf(), frame.uvStride))
        merger.finish()
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        check("fusao 4x12 MP neste host", ms2 < 30_000, "$ms2 ms")
    }
}
