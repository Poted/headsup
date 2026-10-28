package app.headsup.core.color

import kotlin.math.pow

/**
 * Basic corrections applied after the camera LUT. All values are 0 when neutral;
 * [exposure] is in stops, the others roughly −1..1.
 */
data class ColorGrade(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    /** Darkening towards the frame corners, 0..1. Not a colour operation; drawn as a layer. */
    val vignette: Float = 0f,
    /** Moves the darkest tones (black point) up or down. */
    val blacks: Float = 0f,
    /** Moves the brightest tones (white point) up or down. */
    val whites: Float = 0f,
    /** Saturation that mostly affects muted colours and spares already saturated ones. */
    val vibrance: Float = 0f,
    /** Green (−) to magenta (+) balance, complementing [temperature]. */
    val tint: Float = 0f,
    /** Rotation of skin hues on the vectorscope, degrees; see [SkinTone]. */
    val skinHue: Float = 0f,
    /** Film-like shoulder: highlights bend softly towards white instead of clipping hard, 0..1. */
    val rolloff: Float = 0f,
    /** Split toning strength, 0..1: shadows towards [shadowHue], highlights towards [highlightHue]. */
    val toneAmount: Float = 0f,
    /** Vectorscope angles (as [SkinTone.chroma]); the defaults are teal and orange. */
    val shadowHue: Float = DEFAULT_SHADOW_HUE,
    val highlightHue: Float = DEFAULT_HIGHLIGHT_HUE,
    /** Calms loud colours (strong greens and blues most), sparing skin, 0..1. */
    val tame: Float = 0f,
    /** Colour mixer: hue, saturation and luminance per hue band, −1..1 ([ColourMixer]). */
    val mixer: List<Float> = ColourMixer.NEUTRAL,
    /** A look ([Look] name) under the corrections, and how strongly (0..1.5). */
    val look: String? = null,
    val lookAmount: Float = 1f,
    /** Point tone curves: RGB, red, green, blue, each as x, y pairs ([ToneCurve]); empty is straight. */
    val curves: List<List<Float>> = ToneCurve.NEUTRAL,
    /** Colour grading wheels ([ColourWheels]): shadows, midtones, highlights, balance. */
    val wheels: List<Float> = ColourWheels.NEUTRAL,
    /** Fine detail (−1..1): brings out or smooths small structure; not a colour operation ([Presence]). */
    val texture: Float = 0f,
    /** Midtone local contrast (−1..1); not a colour operation ([Presence]). */
    val clarity: Float = 0f,
    /** Removes (+) or adds (−) atmospheric haze, −1..1; not a colour operation ([Presence]). */
    val dehaze: Float = 0f,
    /** RGB channel mixer ([ChannelMixer]): nine offsets from no change. */
    val channelMix: List<Float> = ChannelMixer.NEUTRAL,
    /** Black and white ([BlackAndWhite]): on, then a brightness per hue band. */
    val bw: List<Float> = BlackAndWhite.NEUTRAL,
    /** Calibration ([Calibration]): hue and saturation of the red, green and blue primaries. */
    val calibration: List<Float> = Calibration.NEUTRAL,
) {
    /** The corrections that look at a pixel's surroundings, drawn by the shader before the colour table. */
    val presence: Presence get() = Presence(texture, clarity, dehaze)

    val isNeutralColour: Boolean
        get() = exposure == 0f && brightness == 0f && contrast == 0f && shadows == 0f &&
            highlights == 0f && saturation == 0f && temperature == 0f &&
            blacks == 0f && whites == 0f && vibrance == 0f && tint == 0f && skinHue == 0f &&
            rolloff == 0f && toneAmount == 0f && tame == 0f && mixer.all { it == 0f } && look == null && curves.all { it.isEmpty() } && wheels.all { it == 0f } &&
            channelMix.all { it == 0f } && bw.all { it == 0f } && calibration.all { it == 0f }

    /** The corrections scaled by [k] (hues stay), e.g. a suggestion at partial strength. */
    operator fun times(k: Float) = ColorGrade(
        exposure * k, brightness * k, contrast * k, shadows * k, highlights * k, saturation * k, temperature * k,
        vignette * k, blacks * k, whites * k, vibrance * k, tint * k, skinHue * k,
        rolloff * k, toneAmount * k, shadowHue, highlightHue, tame * k,
        mixer.map { it * k }, look, lookAmount, curves,
        // Hues stay; strengths scale.
        wheels.mapIndexed { i, v -> if (i % 3 == 0 && i < 9) v else v * k },
        texture * k, clarity * k, dehaze * k,
        channelMix.map { it * k },
        // On stays on; the bands scale.
        bw.mapIndexed { i, v -> if (i == 0) v else v * k },
        calibration.map { it * k },
    )

    /**
     * This grade with [other] on top, e.g. a clip's corrections plus the global ones. The values
     * are offsets, so they add up (kept within the ranges the controls allow).
     */
    operator fun plus(other: ColorGrade) = ColorGrade(
        exposure = (exposure + other.exposure).coerceIn(-4f, 4f),
        brightness = (brightness + other.brightness).coerceIn(-1f, 1f),
        contrast = (contrast + other.contrast).coerceIn(-1f, 1f),
        shadows = (shadows + other.shadows).coerceIn(-1f, 1f),
        highlights = (highlights + other.highlights).coerceIn(-1f, 1f),
        saturation = (saturation + other.saturation).coerceIn(-1f, 1f),
        temperature = (temperature + other.temperature).coerceIn(-1f, 1f),
        vignette = (vignette + other.vignette).coerceIn(0f, 1f),
        blacks = (blacks + other.blacks).coerceIn(-1f, 1f),
        whites = (whites + other.whites).coerceIn(-1f, 1f),
        vibrance = (vibrance + other.vibrance).coerceIn(-1f, 1f),
        tint = (tint + other.tint).coerceIn(-1f, 1f),
        skinHue = (skinHue + other.skinHue).coerceIn(-30f, 30f),
        rolloff = (rolloff + other.rolloff).coerceIn(0f, 1f),
        toneAmount = (toneAmount + other.toneAmount).coerceIn(0f, 1f),
        // Hues cannot be added; the stronger toning decides them.
        shadowHue = if (other.toneAmount > toneAmount) other.shadowHue else shadowHue,
        highlightHue = if (other.toneAmount > toneAmount) other.highlightHue else highlightHue,
        tame = (tame + other.tame).coerceIn(0f, 1f),
        mixer = List(ColourMixer.SIZE) { i -> (mixer.getOrElse(i) { 0f } + other.mixer.getOrElse(i) { 0f }).coerceIn(-1f, 1f) },
        // Looks cannot be added; one set on top wins.
        look = other.look ?: look,
        lookAmount = if (other.look != null) other.lookAmount else lookAmount,
        // Curves cannot be added either; a curve set on top wins, channel by channel.
        curves = List(4) { c -> other.curves.getOrNull(c).orEmpty().ifEmpty { curves.getOrNull(c).orEmpty() } },
        // A range graded on top wins (hues cannot be added).
        wheels = List(ColourWheels.SIZE) { i ->
            val range = i / 3
            if (i < 9 && other.wheels.getOrElse(range * 3 + 1) { 0f } != 0f || i < 9 && other.wheels.getOrElse(range * 3 + 2) { 0f } != 0f) other.wheels.getOrElse(i) { 0f }
            else if (i == 9) (wheels.getOrElse(9) { 0f } + other.wheels.getOrElse(9) { 0f }).coerceIn(-1f, 1f)
            else wheels.getOrElse(i) { 0f }
        },
        texture = (texture + other.texture).coerceIn(-1f, 1f),
        clarity = (clarity + other.clarity).coerceIn(-1f, 1f),
        dehaze = (dehaze + other.dehaze).coerceIn(-1f, 1f),
        channelMix = List(ChannelMixer.SIZE) { i -> (channelMix.getOrElse(i) { 0f } + other.channelMix.getOrElse(i) { 0f }).coerceIn(-2f, 2f) },
        bw = List(BlackAndWhite.SIZE) { i ->
            if (i == 0) (if (BlackAndWhite.on(bw) || BlackAndWhite.on(other.bw)) 1f else 0f)
            else (bw.getOrElse(i) { 0f } + other.bw.getOrElse(i) { 0f }).coerceIn(-1f, 1f)
        },
        calibration = List(Calibration.SIZE) { i -> (calibration.getOrElse(i) { 0f } + other.calibration.getOrElse(i) { 0f }).coerceIn(-1f, 1f) },
    )

    /**
     * This grade with the values that changed from [before] to [after] set as in [after] (the
     * others kept): one edit carried over to other photos, as a sync does.
     */
    fun synced(before: ColorGrade, after: ColorGrade) = ColorGrade(
        exposure = if (after.exposure != before.exposure) after.exposure else exposure,
        brightness = if (after.brightness != before.brightness) after.brightness else brightness,
        contrast = if (after.contrast != before.contrast) after.contrast else contrast,
        shadows = if (after.shadows != before.shadows) after.shadows else shadows,
        highlights = if (after.highlights != before.highlights) after.highlights else highlights,
        saturation = if (after.saturation != before.saturation) after.saturation else saturation,
        temperature = if (after.temperature != before.temperature) after.temperature else temperature,
        vignette = if (after.vignette != before.vignette) after.vignette else vignette,
        blacks = if (after.blacks != before.blacks) after.blacks else blacks,
        whites = if (after.whites != before.whites) after.whites else whites,
        vibrance = if (after.vibrance != before.vibrance) after.vibrance else vibrance,
        tint = if (after.tint != before.tint) after.tint else tint,
        skinHue = if (after.skinHue != before.skinHue) after.skinHue else skinHue,
        rolloff = if (after.rolloff != before.rolloff) after.rolloff else rolloff,
        toneAmount = if (after.toneAmount != before.toneAmount) after.toneAmount else toneAmount,
        shadowHue = if (after.shadowHue != before.shadowHue) after.shadowHue else shadowHue,
        highlightHue = if (after.highlightHue != before.highlightHue) after.highlightHue else highlightHue,
        tame = if (after.tame != before.tame) after.tame else tame,
        mixer = List(ColourMixer.SIZE) { i ->
            val a = after.mixer.getOrElse(i) { 0f }
            if (a != before.mixer.getOrElse(i) { 0f }) a else mixer.getOrElse(i) { 0f }
        },
        look = if (after.look != before.look) after.look else look,
        lookAmount = if (after.lookAmount != before.lookAmount) after.lookAmount else lookAmount,
        curves = List(4) { c ->
            val a = after.curves.getOrNull(c).orEmpty()
            if (a != before.curves.getOrNull(c).orEmpty()) a else curves.getOrNull(c).orEmpty()
        },
        wheels = List(ColourWheels.SIZE) { i ->
            val a = after.wheels.getOrElse(i) { 0f }
            if (a != before.wheels.getOrElse(i) { 0f }) a else wheels.getOrElse(i) { 0f }
        },
        texture = if (after.texture != before.texture) after.texture else texture,
        clarity = if (after.clarity != before.clarity) after.clarity else clarity,
        dehaze = if (after.dehaze != before.dehaze) after.dehaze else dehaze,
        channelMix = List(ChannelMixer.SIZE) { i ->
            val a = after.channelMix.getOrElse(i) { 0f }
            if (a != before.channelMix.getOrElse(i) { 0f }) a else channelMix.getOrElse(i) { 0f }
        },
        bw = List(BlackAndWhite.SIZE) { i ->
            val a = after.bw.getOrElse(i) { 0f }
            if (a != before.bw.getOrElse(i) { 0f }) a else bw.getOrElse(i) { 0f }
        },
        calibration = List(Calibration.SIZE) { i ->
            val a = after.calibration.getOrElse(i) { 0f }
            if (a != before.calibration.getOrElse(i) { 0f }) a else calibration.getOrElse(i) { 0f }
        },
    )

    companion object {
        const val DEFAULT_SHADOW_HUE = 283f
        const val DEFAULT_HIGHLIGHT_HUE = 142f
    }
}

/**
 * Bakes the [ColorGrade] corrections and the camera LUT into one 3D table, so preview and export
 * apply exactly the same transform with a single lookup per pixel.
 *
 * With a LUT the order is the one used in DaVinci Resolve: corrections work on the log-encoded
 * camera values and the LUT is the last step. Highlights that the LUT would clip can therefore
 * still be pulled back by lowering exposure. Without a LUT the footage is treated as already
 * display-ready and the corrections work on display values.
 */
object LutBaker {
    const val DEFAULT_SIZE = 33
    private const val GAMMA = 2.2f

    /** Approximate code-value step per stop in camera log curves such as D-Log M (S-Log3/V-Log ≈ 0.075). */
    internal const val LOG_STOP = 0.075f

    /** Approximate log code value of 18 % grey, the pivot for contrast. */
    private const val LOG_GREY = 0.42f

    /** ARGB colours indexed `[r][g][b]`, the layout Media3's `SingleColorLut.createFromCube` expects. */
    fun bake(lut: CubeLut?, grade: ColorGrade, size: Int = DEFAULT_SIZE, wideGamut: Boolean = false): Array<Array<IntArray>> {
        val rgb = FloatArray(3)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    rgb[0] = r / (size - 1f)
                    rgb[1] = g / (size - 1f)
                    rgb[2] = b / (size - 1f)
                    apply(lut, grade, rgb, wideGamut)
                    argb(rgb)
                }
            }
        }
    }

    /**
     * Transforms one colour in place (0..1 per channel). [wideGamut]: the footage is BT.2020 (e.g.
     * Samsung Log); without a LUT it is first shown in BT.709 as players do, while a LUT gets the
     * camera's own values (log LUTs do their own gamut conversion).
     */
    fun apply(lut: CubeLut?, grade: ColorGrade, rgb: FloatArray, wideGamut: Boolean = false) {
        val look = Look.of(grade.look)
        if (lut == null) {
            if (wideGamut) Gamut.bt2020ToBt709(rgb)
            // The look is the starting rendering; the corrections go on top of it.
            Looks.apply(look, grade.lookAmount, rgb)
            displayCorrections(grade, rgb)
        } else {
            logCorrections(grade, rgb)
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
            lut.sample(rgb[0], rgb[1], rgb[2], rgb)
            Looks.apply(look, grade.lookAmount, rgb)
        }
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        // Calibration and the channel mixer first, as the starting colours the rest works on.
        Calibration.apply(calibrationMatrix(grade.calibration), rgb)
        ChannelMixer.apply(grade.channelMix, rgb)
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        // Film look and the skin line work on display-referred (Rec.709) colours, so they run last.
        FilmLook.apply(grade, rgb)
        ColourMixer.apply(grade.mixer, rgb)
        // Black and white after the colour mixer; curves and grading wheels can still tint it.
        BlackAndWhite.apply(grade.bw, rgb)
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        ToneCurve.apply(grade.curves, rgb)
        ColourWheels.apply(grade.wheels, rgb)
        if (grade.skinHue != 0f) {
            SkinTone.rotate(rgb, grade.skinHue)
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        }
    }

    /** The calibration's matrix, worked out once per table rather than per colour. */
    @Volatile private var calibrationCache: Pair<List<Float>, FloatArray?>? = null

    private fun calibrationMatrix(cal: List<Float>): FloatArray? {
        calibrationCache?.let { (key, m) -> if (key == cal) return m }
        return Calibration.matrix(cal).also { calibrationCache = cal to it }
    }

    /** Looks a colour up in a table produced by [bake] (trilinear), e.g. to analyse the graded preview. */
    fun sampleBaked(cube: Array<Array<IntArray>>, r: Float, g: Float, b: Float, out: FloatArray) {
        val max = cube.size - 1
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = fr.toInt().coerceAtMost(max - 1)
        val g0 = fg.toInt().coerceAtMost(max - 1)
        val b0 = fb.toInt().coerceAtMost(max - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            fun v(ri: Int, gi: Int, bi: Int) = ((cube[ri][gi][bi] shr shift) and 0xFF) / 255f
            val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
            val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
            val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
            out[c] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
        }
    }

    /** Corrections on log-encoded values: exposure and white balance are offsets in log space. */
    private fun logCorrections(grade: ColorGrade, rgb: FloatArray) {
        for (i in 0..2) {
            rgb[i] += grade.exposure * LOG_STOP
            if (grade.brightness != 0f) rgb[i] = rgb[i].coerceAtLeast(0f).pow(2f.pow(-grade.brightness * 0.5f))
            rgb[i] = (rgb[i] - LOG_GREY) * (1 + grade.contrast * 0.5f) + LOG_GREY
        }
        val y = luma(rgb).coerceIn(0f, 1f)
        val shadowWeight = 1 - smoothstep(0.1f, 0.45f, y)
        val highlightWeight = smoothstep(0.45f, 0.85f, y)
        val lift = (grade.shadows * shadowWeight + grade.highlights * highlightWeight) * 2 * LOG_STOP
        for (i in 0..2) rgb[i] += lift

        if (grade.saturation != 0f) {
            val l = luma(rgb)
            for (i in 0..2) rgb[i] = l + (rgb[i] - l) * (1 + grade.saturation)
        }
        rgb[0] += grade.temperature * 0.3f * LOG_STOP
        rgb[2] -= grade.temperature * 0.3f * LOG_STOP
        toneEnds(grade, rgb, 2 * LOG_STOP)
        vibranceAndTint(grade, rgb, 0.3f * LOG_STOP)
    }

    /** Corrections on display (gamma-encoded) values, for footage without a LUT. */
    private fun displayCorrections(grade: ColorGrade, rgb: FloatArray) {
        if (grade.exposure != 0f) {
            val gain = 2f.pow(grade.exposure)
            for (i in 0..2) rgb[i] = (rgb[i].coerceAtLeast(0f).pow(GAMMA) * gain).pow(1 / GAMMA)
        }
        for (i in 0..2) {
            rgb[i] += grade.brightness * 0.25f
            rgb[i] = (rgb[i] - 0.5f) * (1 + grade.contrast * 0.5f) + 0.5f
        }

        val y = luma(rgb).coerceIn(0f, 1f)
        val shadowWeight = 1 - smoothstep(0f, 0.55f, y)
        val highlightWeight = smoothstep(0.45f, 1f, y)
        val lift = grade.shadows * 0.2f * shadowWeight + grade.highlights * 0.2f * highlightWeight
        for (i in 0..2) rgb[i] += lift

        if (grade.saturation != 0f) {
            val l = luma(rgb)
            for (i in 0..2) rgb[i] = l + (rgb[i] - l) * (1 + grade.saturation)
        }
        if (grade.temperature != 0f) {
            rgb[0] *= 1 + grade.temperature * 0.1f
            rgb[2] *= 1 - grade.temperature * 0.1f
        }
        toneEnds(grade, rgb, 0.15f)
        vibranceAndTint(grade, rgb, 0.05f)
    }

    /** Blacks and whites: shifts weighted towards the dark or bright end, equal on all channels. */
    private fun toneEnds(grade: ColorGrade, rgb: FloatArray, amount: Float) {
        if (grade.blacks == 0f && grade.whites == 0f) return
        val y = luma(rgb).coerceIn(0f, 1f)
        val shift = grade.blacks * amount * (1 - smoothstep(0f, 0.4f, y)) + grade.whites * amount * smoothstep(0.55f, 1f, y)
        for (i in 0..2) rgb[i] += shift
    }

    private fun vibranceAndTint(grade: ColorGrade, rgb: FloatArray, tintAmount: Float) {
        if (grade.vibrance != 0f) {
            val chroma = (maxOf(rgb[0], rgb[1], rgb[2]) - minOf(rgb[0], rgb[1], rgb[2])).coerceIn(0f, 1f)
            val factor = 1 + grade.vibrance * (1 - chroma)
            val l = luma(rgb)
            for (i in 0..2) rgb[i] = l + (rgb[i] - l) * factor
        }
        if (grade.tint != 0f) {
            rgb[1] -= grade.tint * tintAmount
            rgb[0] += grade.tint * tintAmount / 2
            rgb[2] += grade.tint * tintAmount / 2
        }
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun argb(c: FloatArray): Int {
        fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }
}

/** Gamut conversion for display-referred colours (BT.1886 gamma). */
object Gamut {
    private const val GAMMA = 2.4f
    private val M = floatArrayOf(
        1.6605f, -0.5876f, -0.0728f,
        -0.1246f, 1.1329f, -0.0083f,
        -0.0182f, -0.1006f, 1.1187f,
    )

    /** BT.2020 colours to BT.709, in place; out-of-gamut colours are clipped. */
    fun bt2020ToBt709(rgb: FloatArray) {
        val r = Math.pow(rgb[0].coerceIn(0f, 1f).toDouble(), GAMMA.toDouble()).toFloat()
        val g = Math.pow(rgb[1].coerceIn(0f, 1f).toDouble(), GAMMA.toDouble()).toFloat()
        val b = Math.pow(rgb[2].coerceIn(0f, 1f).toDouble(), GAMMA.toDouble()).toFloat()
        for (i in 0..2) {
            val v = (M[i * 3] * r + M[i * 3 + 1] * g + M[i * 3 + 2] * b).coerceIn(0f, 1f)
            rgb[i] = Math.pow(v.toDouble(), 1.0 / GAMMA).toFloat()
        }
    }
}
