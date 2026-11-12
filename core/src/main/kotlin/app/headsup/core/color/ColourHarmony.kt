package app.headsup.core.color

import kotlin.math.abs

/**
 * Colour harmony, as colourists build a palette: the picture's colours pulled towards a scheme on
 * the colour wheel around a key hue (degrees, 0 red, 120 green, 240 blue): complementary (the key
 * and its opposite, as teal and orange or yellow and violet), split-complementary, analogous
 * (neighbours) or triadic. Made of ordinary corrections: the colour mixer turns each hue band
 * towards the nearest colour of the scheme and calms the bands far from it; split toning puts the
 * key in the highlights and its partner in the shadows. Skin (the orange band) is turned and calmed
 * least.
 */
object ColourHarmony {
    enum class Scheme(val label: String, val offsets: List<Float>) {
        COMPLEMENTARY("Complementary", listOf(0f, 180f)),
        SPLIT("Split", listOf(0f, 150f, 210f)),
        ANALOGOUS("Analogous", listOf(-30f, 0f, 30f)),
        TRIADIC("Triadic", listOf(0f, 120f, 240f)),
    }

    /** The scheme's hues around [key]. */
    fun hues(scheme: Scheme, key: Float): List<Float> = scheme.offsets.map { wrap(key + it) }

    /** The corrections that pull a picture towards [scheme] around [key], at [amount] (0..1). */
    fun grade(scheme: Scheme, key: Float, amount: Float): ColorGrade {
        val k = amount.coerceIn(0f, 1f)
        val hues = hues(scheme, key)
        val mixer = ColourMixer.NEUTRAL.toMutableList()
        for (band in ColourMixer.Band.entries) {
            val nearest = hues.minBy { abs(diff(it, band.hue)) }
            val d = diff(nearest, band.hue)
            val skin = band == ColourMixer.Band.ORANGE
            // Turned towards it (the mixer turns at most 30°), less for skin.
            val turn = (d / 30f).coerceIn(-1f, 1f) * 0.7f * (if (skin) 0.3f else 1f)
            // Colours on the scheme kept, those far from it calmed.
            val far = ((abs(d) - 20f) / 40f).coerceIn(0f, 1f)
            val sat = (0.12f - far * 0.5f) * (if (skin) 0.3f else 1f)
            mixer[ColourMixer.index(band, ColourMixer.Property.HUE)] = turn * k
            mixer[ColourMixer.index(band, ColourMixer.Property.SATURATION)] = sat * k
        }
        // The partner for the shadows: the scheme's colour farthest from the key (for analogous,
        // the cooler neighbour).
        val partner = if (scheme == Scheme.ANALOGOUS) wrap(key - 30f) else hues.maxBy { abs(diff(it, key)) }
        val highlight = if (scheme == Scheme.ANALOGOUS) wrap(key + 30f) else key
        return ColorGrade(
            mixer = mixer,
            toneAmount = 0.45f * k,
            shadowHue = vectorscope(partner),
            highlightHue = vectorscope(highlight),
        )
    }

    /** A colour-wheel hue as the vectorscope angle split toning uses ([SkinTone.chroma]). */
    fun vectorscope(hue: Float): Float {
        val (r, g, b) = rgbOf(hue)
        return SkinTone.chroma(r, g, b).angleDegrees.toFloat()
    }

    /** Full-saturation colour of [hue]. */
    fun rgbOf(hue: Float): Triple<Float, Float, Float> {
        val hp = wrap(hue) / 60f
        val x = 1f - abs(hp % 2f - 1f)
        return when (hp.toInt()) {
            0 -> Triple(1f, x, 0f)
            1 -> Triple(x, 1f, 0f)
            2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f)
            4 -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
    }

    private fun wrap(h: Float) = ((h % 360f) + 360f) % 360f

    /** a − b in −180..180. */
    private fun diff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
