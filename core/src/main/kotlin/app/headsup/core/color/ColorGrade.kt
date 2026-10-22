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
) {
    val isNeutralColour: Boolean
        get() = exposure == 0f && brightness == 0f && contrast == 0f && shadows == 0f &&
            highlights == 0f && saturation == 0f && temperature == 0f
}

/**
 * Bakes the camera LUT and the [ColorGrade] colour operations into one 3D table, so preview and
 * export apply exactly the same transform with a single lookup per pixel.
 */
object LutBaker {
    const val DEFAULT_SIZE = 33
    private const val GAMMA = 2.2f

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
        lut?.sample(rgb[0], rgb[1], rgb[2], rgb)

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
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
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
