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
            "nitidez: sem duplicar a do sistema" to { noDoubleSharpen() },
            "nitidez: nao amplifica ruido em areas lisas" to { flatNoiseNotSharpened() },
            "pele: brilho especular contido" to { skinSpecular() },
            "pele: suavizacao opcional preserva poros" to { skinSmoothingOptional() },
            "fusao: reduz ruido e evita fantasmas" to { burst() },
            "hibrido vs quadro unico com NR/nitidez fortes do ISP" to { hybridVsIsp() },
            "hibrido: cor falsa (arco-iris) em textura fina" to { falseColor() },
            "hibrido: veu cinza (pretos levantados, brancos baixos)" to { hybridLevels() },
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

    private fun process(img: ArgbImage, kind: SourceKind, info: CaptureInfo? = null, opt: OutputOptions = OutputOptions(dither = false),
                        profile: ProcessingProfile = ProcessingProfile.NATURAL_PREMIUM): Pair<ArgbImage, PhotoPipeline.Result> {
        val out = ArgbImage(img.width, img.height)
        val res = PhotoPipeline(pool).process(ArgbSource(img), info ?: CaptureInfo(kind), profile, opt, ArgbSink(out))
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

    private fun noDoubleSharpen() {
        val img = Synthetic.edges()
        val (out, _) = process(img, SourceKind.SYSTEM_RENDERED)
        val gi = ImageMetrics.stats(img).gradientEnergy; val go = ImageMetrics.stats(out).gradientEnergy
        check("fonte ja nitida pelo ISP nao recebe outra mascara", go < gi * 1.06, "gradiente %.3f -> %.3f".format(gi, go))
        val oi = ImageMetrics.stats(img).overshootIndex; val oo = ImageMetrics.stats(out).overshootIndex
        check("sem novos halos", oo <= oi + 0.002, "overshoot %.4f -> %.4f".format(oi, oo))
    }

    private fun flatNoiseNotSharpened() {
        val flat = Synthetic.addNoise(Synthetic.flat(400, 300, 140), 3f, 31)
        val (out, _) = process(flat, SourceKind.BURST_MERGED)
        val ni = ImageMetrics.stats(flat).noiseSigma; val no = ImageMetrics.stats(out).noiseSigma
        check("ruido nao e realcado pela nitidez", no < ni, "sigma L* %.2f -> %.2f".format(ni, no))
    }

    /** Skin-colored face with fine pores, gentle blotches and a specular spot. */
    private fun skinFace(): ArgbImage {
        val w = 360; val h = 360
        val img = ArgbImage(w, h)
        val rnd = java.util.Random(77)
        val pores = FloatArray(w * h) { (rnd.nextGaussian() * 0.035).toFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            val blotch = 0.03f * Math.sin(x * 0.08).toFloat() * Math.sin(y * 0.07).toFloat()
            val dx = (x - 250f) / 22f; val dy = (y - 120f) / 16f
            val spec = 0.32f * Math.exp(-(dx * dx + dy * dy).toDouble()).toFloat()
            val k = 1f + pores[y * w + x] + blotch + spec
            img.pixels[y * w + x] = Synthetic.rgb((194 * k).toInt(), (150 * k).toInt(), (130 * k).toInt())
        }
        return img
    }

    private fun skinSpecular() {
        val img = skinFace()
        val info = CaptureInfo(SourceKind.SINGLE_SRGB_CURVE, faces = listOf(FaceRegion(0f, 0f, 1f, 1f)))
        val (out, _) = process(img, SourceKind.SINGLE_SRGB_CURVE, info)
        DesktopIO.write(DesktopIO.sideBySide(img, out), File(outDir, "pele_brilho_antes_depois.png"))
        val specIn = labOf(meanRect(img, 240, 112, 20, 16))[0] - labOf(meanRect(img, 60, 220, 60, 60))[0]
        val specOut = labOf(meanRect(out, 240, 112, 20, 16))[0] - labOf(meanRect(out, 60, 220, 60, 60))[0]
        check("excesso do brilho reduzido", specOut < specIn - 0.5, "dL* brilho-pele %.1f -> %.1f".format(specIn, specOut))
        check("brilho nao some (pele continua com volume)", specOut > specIn * 0.5, "%.1f".format(specOut))
    }

    private fun skinSmoothingOptional() {
        val img = skinFace()
        val info = CaptureInfo(SourceKind.SINGLE_SRGB_CURVE, faces = listOf(FaceRegion(0f, 0f, 1f, 1f)))
        val (off, _) = process(img, SourceKind.SINGLE_SRGB_CURVE, info)
        val (on, _) = process(img, SourceKind.SINGLE_SRGB_CURVE, info, profile = ProcessingProfile.NATURAL_PREMIUM.copy(skinSmoothing = 0.35f))
        DesktopIO.write(DesktopIO.sideBySide(off, on), File(outDir, "pele_suavizacao_desligada_ligada.png"))
        val region = ImageMetrics.Region(0.05f, 0.5f, 0.5f, 0.45f)
        val gOff = ImageMetrics.stats(off, region).gradientEnergy; val gOn = ImageMetrics.stats(on, region).gradientEnergy
        check("poros preservados com suavizacao ligada", gOn > gOff * 0.9, "gradiente %.3f -> %.3f".format(gOff, gOn))
        val pOff = ImageMetrics.stats(off, region).lPercentiles; val pOn = ImageMetrics.stats(on, region).lPercentiles
        val spreadOff = pOff[5] - pOff[1]; val spreadOn = pOn[5] - pOn[1]
        check("manchas de media frequencia atenuadas, sutilmente", spreadOn < spreadOff && spreadOn > spreadOff * 0.75,
            "faixa L* p5..p95 %.2f -> %.2f".format(spreadOff, spreadOn))
    }

    /** Heathered fabric texture (like the t-shirt in a real night-indoor test photo). */
    private fun fabric(w: Int, h: Int): ArgbImage {
        val rnd = java.util.Random(5)
        val base = FloatArray(w * h) { 0.45f + 0.09f * rnd.nextGaussian().toFloat() }
        val b = Synthetic.gaussianBlur(base, w, h, 0.7f)
        val img = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val stripe = 0.03f * Math.sin(y * 0.9).toFloat()
            val v = ((b[y * w + x] + stripe) * 255).toInt()
            img.pixels[y * w + x] = Synthetic.rgb(v, (v * 0.98).toInt(), (v * 0.9).toInt())
        }
        return img
    }

    /** Crude model of a third-party ISP HQ path: strong spatial NR (watercolor) + strong USM (halos). */
    private fun ispSingle(clean: ArgbImage, sigma: Float): ArgbImage {
        val w = clean.width; val h = clean.height
        val noisy = Synthetic.addNoise(clean, sigma, 909)
        val out = ArgbImage(w, h)
        for (ch in 0..2) {
            val shift = 16 - ch * 8
            val v = FloatArray(w * h) { ((noisy.pixels[it] shr shift) and 0xFF) / 255f }
            val a = FloatArray(w * h); val bb = FloatArray(w * h)
            com.camerapremium.imaging.util.Filters.guidedCoefficients(v, v, w, h, 3, com.camerapremium.imaging.util.ConstantEps(0.004f), a, bb, com.camerapremium.imaging.util.GuidedWorkspace())
            val nr = FloatArray(w * h) { a[it] * v[it] + bb[it] }
            val blur = Synthetic.gaussianBlur(nr, w, h, 1.3f)
            for (i in 0 until w * h) {
                val sh = (nr[i] + 1.4f * (nr[i] - blur[i])).coerceIn(0f, 1f)
                val q = (sh * 255 + 0.5f).toInt()
                out.pixels[i] = (out.pixels[i] or (0xFF shl 24)) or (q shl shift)
            }
        }
        return out
    }

    private fun lumaRmse(a: ArgbImage, b: ArgbImage): Double {
        var s = 0.0
        for (i in a.pixels.indices) {
            val la = ColorMath.linearSrgbToLab(ColorMath.SRGB8_TO_LINEAR[(a.pixels[i] shr 16) and 255], ColorMath.SRGB8_TO_LINEAR[(a.pixels[i] shr 8) and 255], ColorMath.SRGB8_TO_LINEAR[a.pixels[i] and 255])[0]
            val lb = ColorMath.linearSrgbToLab(ColorMath.SRGB8_TO_LINEAR[(b.pixels[i] shr 16) and 255], ColorMath.SRGB8_TO_LINEAR[(b.pixels[i] shr 8) and 255], ColorMath.SRGB8_TO_LINEAR[b.pixels[i] and 255])[0]
            s += (la - lb) * (la - lb)
        }
        return Math.sqrt(s / a.pixels.size)
    }

    private fun hybridVsIsp() {
        val w = 360; val h = 300; val sigma = 7f
        val clean = fabric(w, h)
        val isp = ispSingle(clean, sigma)
        val merger = BurstMerger(pool)
        for (i in 0 until 7) merger.add(Synthetic.burstFrame(clean, 0, 0, sigma, 300L + i))
        val merged = merger.finish()
        val hyb = ArgbImage(w, h)
        PhotoPipeline(pool).process(MergedYuvSource(merged), CaptureInfo(SourceKind.HYBRID, framesMerged = merged.framesMerged),
            ProcessingProfile.NATURAL_PREMIUM, OutputOptions(dither = false), ArgbSink(hyb), merged)
        DesktopIO.write(DesktopIO.sideBySide(DesktopIO.sideBySide(clean, isp), hyb), File(outDir, "tecido_real_isp_hibrido.png"))
        val eIsp = lumaRmse(isp, clean); val eHyb = lumaRmse(hyb, clean)
        check("hibrido mais fiel a textura real", eHyb < eIsp, "erro L* vs verdade: ISP %.2f, hibrido %.2f".format(eIsp, eHyb))
        val oIsp = ImageMetrics.stats(isp).overshootIndex; val oHyb = ImageMetrics.stats(hyb).overshootIndex
        val tC = ImageMetrics.stats(clean).midBandEnergy; val tIsp = ImageMetrics.stats(isp).midBandEnergy; val tHyb = ImageMetrics.stats(hyb).midBandEnergy
        check("textura real do tecido preservada (>= 90%)", tHyb >= tC * 0.9, "energia: real %.2f, ISP %.2f (inclui ruido/halos), hibrido %.2f".format(tC, tIsp, tHyb))
        println("        overshoot ISP %.4f hibrido %.4f".format(oIsp, oHyb))
        val raw = ArgbImage(w, h)
        val rr = FloatArray(w * h); val rg = FloatArray(w * h); val rb = FloatArray(w * h)
        MergedYuvSource(merged).readEncoded(0, 0, w, h, rr, rg, rb)
        for (i in 0 until w * h) raw.pixels[i] = Synthetic.rgb((rr[i] * 255).toInt(), (rg[i] * 255).toInt(), (rb[i] * 255).toInt())
        val cy = Synthetic.toYuv(clean); val cyImg = ArgbImage(w, h)
        val c1 = FloatArray(w * h); val c2 = FloatArray(w * h); val c3 = FloatArray(w * h)
        com.camerapremium.imaging.image.YuvFrameSource(cy).readEncoded(0, 0, w, h, c1, c2, c3)
        for (i in 0 until w * h) cyImg.pixels[i] = Synthetic.rgb((c1[i] * 255).toInt(), (c2[i] * 255).toInt(), (c3[i] * 255).toInt())
        println("        diag: energia verdade-via-YUV %.2f, fusao crua %.2f (erro %.2f), noise %s".format(
            ImageMetrics.stats(cyImg).midBandEnergy, ImageMetrics.stats(raw).midBandEnergy, lumaRmse(raw, clean), "-"))
        val base = ProcessingProfile.NATURAL_PREMIUM
        for ((name, prof) in listOf(
            "sem NR luma" to base.copy(lumaNrStrength = 0f),
            "sem NR croma" to base.copy(chromaNrStrength = 0f),
            "sem nitidez" to base.copy(sharpenHybrid = 0f),
            "sem cor" to base.copy(chroma = 1f, vibrance = 0f, redChroma = 1f, greenChroma = 1f, skyChroma = 1f, greenHueShiftDeg = 0f, castCorrection = 0f),
            "sem nada" to base.copy(lumaNrStrength = 0f, chromaNrStrength = 0f, sharpenHybrid = 0f, chroma = 1f, vibrance = 0f, castCorrection = 0f, microContrast = 0f))) {
            val o = ArgbImage(w, h)
            val res = PhotoPipeline(pool).process(MergedYuvSource(merged), CaptureInfo(SourceKind.HYBRID, framesMerged = merged.framesMerged), prof, OutputOptions(dither = false), ArgbSink(o), merged)
            println("        diag %-14s erro %.2f energia %.2f  %s".format(name, lumaRmse(o, clean), ImageMetrics.stats(o).midBandEnergy, if (name == "sem nada") res.stats.toString() else ""))
        }
    }

    /** Mean Oklab chroma deviation from the image's mean color, and the mean chroma itself. */
    private fun chromaStats(img: ArgbImage): DoubleArray {
        val n = img.pixels.size
        val a = DoubleArray(n); val b = DoubleArray(n); val lab = FloatArray(3)
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) {
            val c = img.pixels[i]
            ColorMath.linearToOklab(ColorMath.SRGB8_TO_LINEAR[(c shr 16) and 255], ColorMath.SRGB8_TO_LINEAR[(c shr 8) and 255], ColorMath.SRGB8_TO_LINEAR[c and 255], lab, 0)
            a[i] = lab[1].toDouble(); b[i] = lab[2].toDouble(); ma += a[i]; mb += b[i]
        }
        ma /= n; mb /= n
        var dev = 0.0; var chroma = 0.0
        for (i in 0 until n) { dev += Math.hypot(a[i] - ma, b[i] - mb); chroma += Math.hypot(a[i], b[i]) }
        return doubleArrayOf(dev / n, chroma / n)
    }

    private fun falseColor() {
        val w = 320; val h = 240
        // fine bright texture (brushed metal / chain) with demosaic-like random rainbow speckle
        val clean = fabric(w, h)
        val speckled = ArgbImage(w, h)
        val rnd = java.util.Random(77)
        for (i in 0 until w * h) {
            val c = clean.pixels[i]
            val c1 = rnd.nextGaussian() * 14; val c2 = rnd.nextGaussian() * 14
            speckled.pixels[i] = Synthetic.rgb((((c shr 16) and 255) + c1).toInt().coerceIn(0, 255),
                (((c shr 8) and 255) - (c1 + c2) / 2).toInt().coerceIn(0, 255), ((c and 255) + c2).toInt().coerceIn(0, 255))
        }
        val (out, _) = process(speckled, SourceKind.HYBRID)
        val dIn = chromaStats(speckled)[0]; val dClean = chromaStats(clean)[0]; val dOut = chromaStats(out)[0]
        check("arco-iris removido da textura", dOut < dClean + 0.35 * (dIn - dClean), "desvio de croma: limpo %.4f, com cor falsa %.4f, saida %.4f".format(dClean, dIn, dOut))
        // the suppression works on chroma only: luma texture must match the pipeline without it
        val (ref, _) = process(speckled, SourceKind.HYBRID, profile = ProcessingProfile.NATURAL_PREMIUM.copy(falseColorSuppression = 0f))
        val tRef = ImageMetrics.stats(ref).midBandEnergy; val tOut = ImageMetrics.stats(out).midBandEnergy
        check("textura de luma intocada pela supressao", tOut > tRef * 0.98, "energia sem supressao %.2f, com %.2f".format(tRef, tOut))
        // dark mesh with colored glints (bar ceiling): shadows hide the luma texture confidence
        val dark = ArgbImage(w, h)
        val rd = java.util.Random(78)
        for (i in 0 until w * h) {
            val v = ((clean.pixels[i] shr 8) and 255) / 6
            val g = if (rd.nextInt(40) == 0) 40 + rd.nextInt(30) else 0
            dark.pixels[i] = Synthetic.rgb(v + g / 3, v + g, v)
        }
        val (darkOut, _) = process(dark, SourceKind.HYBRID)
        val ddIn = chromaStats(dark)[0]; val ddOut = chromaStats(darkOut)[0]
        check("pontos coloridos em sombra contidos", ddOut < ddIn * 0.6, "desvio de croma %.4f -> %.4f".format(ddIn, ddOut))
        // knurled metal: periodic neutral ridges whose demosaic aliasing tints alternate ridges
        // (yellow/violet). Even the half-resolution chroma carries this, so it needs the
        // low-frequency neutral reference.
        val knurl = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val ph = (x + y / 3) % 6
            val v = if (ph < 3) 200 else 90
            val t = if (((x + y / 3) / 6) % 2 == 0) 22 else -22
            knurl.pixels[y * w + x] = Synthetic.rgb((v + t).coerceIn(0, 255), (v + t).coerceIn(0, 255), (v - t).coerceIn(0, 255))
        }
        val (kOut, _) = process(knurl, SourceKind.HYBRID)
        val kIn = chromaStats(knurl)[0]; val kO = chromaStats(kOut)[0]
        check("arco-iris em metal serrilhado (periodico) contido", kO < kIn * 0.5, "desvio de croma %.4f -> %.4f".format(kIn, kO))
        DesktopIO.write(DesktopIO.sideBySide(knurl, kOut), File(outDir, "teste_cor_falsa_serrilhado.png"))
        // real fine color detail (red/green stripes, 4 px) must keep its color
        val stripes = ArgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) stripes.pixels[y * w + x] = if ((x / 4) % 2 == 0) Synthetic.rgb(190, 60, 50) else Synthetic.rgb(60, 150, 70)
        val (so, _) = process(stripes, SourceKind.HYBRID)
        val cIn = chromaStats(stripes)[1]; val cOut = chromaStats(so)[1]
        check("cor real em detalhe fino preservada", cOut > cIn * 0.85, "croma medio %.4f -> %.4f".format(cIn, cOut))
        DesktopIO.write(DesktopIO.sideBySide(speckled, out), File(outDir, "teste_cor_falsa.png"))
    }

    private fun hybridLevels() {
        // flat stream: a full tonal ramp (with some color) squeezed into sRGB 58..205
        val src = Synthetic.naturalScene()
        val flat = ArgbImage(src.width, src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val t = x.toFloat() / (src.width - 1)
            val c = src.pixels[y * src.width + x]
            fun f(v: Int) = (58 + (0.8f * t * 255 + 0.2f * v) * (205 - 58) / 255).toInt()
            flat.pixels[y * src.width + x] = Synthetic.rgb(f((c shr 16) and 255), f((c shr 8) and 255), f(c and 255))
        }
        val info = CaptureInfo(SourceKind.HYBRID, iso = 50, exposureTimeNs = 2_000_000L, aperture = 1.8f)
        val (out, _) = process(flat, SourceKind.HYBRID, info)
        val si = ImageMetrics.stats(flat); val so = ImageMetrics.stats(out)
        check("pretos aprofundados", so.lPercentiles[0] < si.lPercentiles[0] - 3, "L* p1 %.1f -> %.1f".format(si.lPercentiles[0], so.lPercentiles[0]))
        check("brancos mais limpos, sem estourar", so.lPercentiles[6] > si.lPercentiles[6] + 1.5 && so.whiteClipped < 0.005,
            "L* p99 %.1f -> %.1f, estourado %.2f%%".format(si.lPercentiles[6], so.lPercentiles[6], so.whiteClipped * 100))
        // a normally rendered night scene keeps its darkness
        val nightInfo = CaptureInfo(SourceKind.HYBRID, iso = 3200, exposureTimeNs = 100_000_000L, aperture = 1.8f)
        val (n2, _) = process(src, SourceKind.HYBRID, nightInfo)
        val ns = ImageMetrics.stats(src); val no = ImageMetrics.stats(n2)
        check("cena noturna ja contrastada quase inalterada", Math.abs(no.meanLab[0] - ns.meanLab[0]) < 4, "L* medio %.1f -> %.1f".format(ns.meanLab[0], no.meanLab[0]))
        DesktopIO.write(DesktopIO.sideBySide(flat, out), File(outDir, "teste_veu_hibrido.png"))
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
