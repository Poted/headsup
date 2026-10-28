package app.headsup.core.color

import kotlin.math.cos

/**
 * Colour grading wheels (Lightroom's Color Grading): shadows, midtones and highlights each pushed
 * towards a hue (by a saturation, 0..1) and made brighter or darker; the balance moves the line
 * between shadows and highlights.
 */
object ColourWheels {
    enum class Range(val label: String) { SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights") }

    /** Hue (degrees), saturation and luminance per range, then the balance (−1..1): 10 values. */
    const val SIZE = 10
    val NEUTRAL: List<Float> = List(SIZE) { 0f }

    fun hue(w: List<Float>, r: Range) = w.getOrElse(r.ordinal * 3) { 0f }
    fun saturation(w: List<Float>, r: Range) = w.getOrElse(r.ordinal * 3 + 1) { 0f }
    fun luminance(w: List<Float>, r: Range) = w.getOrElse(r.ordinal * 3 + 2) { 0f }
    fun balance(w: List<Float>) = w.getOrElse(9) { 0f }

    fun with(w: List<Float>, index: Int, value: Float): List<Float> =
        List(SIZE) { i -> if (i == index) value else w.getOrElse(i) { 0f } }

    fun apply(w: List<Float>, rgb: FloatArray) {
        if (w.size < SIZE || w.all { it == 0f }) return
        val y = (0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]).coerceIn(0f, 1f)
        // Where shadows end and highlights begin, moved by the balance.
        val pivot = (0.5f + balance(w) * 0.25f).coerceIn(0.2f, 0.8f)
        val shadows = (1f - y / pivot).coerceIn(0f, 1f).let { it * it }
        val highs = ((y - pivot) / (1f - pivot)).coerceIn(0f, 1f).let { it * it }
        val mids = (1f - shadows - highs).coerceIn(0f, 1f)
        for ((range, weight) in listOf(Range.SHADOWS to shadows, Range.MIDTONES to mids, Range.HIGHLIGHTS to highs)) {
            if (weight == 0f) continue
            val s = saturation(w, range)
            val l = luminance(w, range)
            if (s != 0f) {
                // A tint of the hue, equal in brightness (it shifts colour, not light).
                val a = Math.toRadians(hue(w, range).toDouble())
                val tr = cos(a).toFloat()
                val tg = cos(a - 2.0944).toFloat()
                val tb = cos(a + 2.0944).toFloat()
                val amount = s * 0.18f * weight
                rgb[0] += tr * amount
                rgb[1] += tg * amount
                rgb[2] += tb * amount
            }
            if (l != 0f) for (i in 0..2) rgb[i] += l * 0.2f * weight
        }
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
    }

    /** The hue's colour at full saturation, for drawing the wheel. */
    fun swatch(hue: Float): Triple<Float, Float, Float> {
        val a = Math.toRadians(hue.toDouble())
        return Triple(0.5f + 0.5f * cos(a).toFloat(), 0.5f + 0.5f * cos(a - 2.0944).toFloat(), 0.5f + 0.5f * cos(a + 2.0944).toFloat())
    }
}
