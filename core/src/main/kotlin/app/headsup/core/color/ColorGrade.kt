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
) {
    val isNeutralColour: Boolean
        get() = exposure == 0f && brightness == 0f && contrast == 0f && shadows == 0f &&
            highlights == 0f && saturation == 0f && temperature == 0f &&
            blacks == 0f && whites == 0f && vibrance == 0f && tint == 0f && skinHue == 0f &&
            rolloff == 0f && toneAmount == 0f && tame == 0f

    /** The corrections scaled by [k] (hues stay), e.g. a suggestion at partial strength. */
    operator fun times(k: Float) = ColorGrade(
        exposure * k, brightness * k, contrast * k, shadows * k, highlights * k, saturation * k, temperature * k,
        vignette * k, blacks * k, whites * k, vibrance * k, tint * k, skinHue * k,
        rolloff * k, toneAmount * k, shadowHue, highlightHue, tame * k,
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
    fun bake(lut: CubeLut?, grade: ColorGrade, size: Int = DEFAULT_SIZE): Array<Array<IntArray>> {
        val rgb = FloatArray(3)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    rgb[0] = r / (size - 1f)
                    rgb[1] = g / (size - 1f)
                    rgb[2] = b / (size - 1f)
                    apply(lut, grade, rgb)
                    argb(rgb)
                }
            }
        }
    }

    /** Transforms one colour in place (0..1 per channel). */
    fun apply(lut: CubeLut?, grade: ColorGrade, rgb: FloatArray) {
        if (lut == null) {
            displayCorrections(grade, rgb)
        } else {
            logCorrections(grade, rgb)
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
            lut.sample(rgb[0], rgb[1], rgb[2], rgb)
        }
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        // Film look and the skin line work on display-referred (Rec.709) colours, so they run last.
        FilmLook.apply(grade, rgb)
        if (grade.skinHue != 0f) {
            SkinTone.rotate(rgb, grade.skinHue)
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        }
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
