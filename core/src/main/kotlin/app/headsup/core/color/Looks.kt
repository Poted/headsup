package app.headsup.core.color

/**
 * Looks (Lightroom's profiles): a starting rendering under the user's own corrections, blended in
 * by an amount (1 = as designed, more exaggerates it). They work on display colours, 0..1.
 */
enum class Look(val label: String) {
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    FILM("Film"),
    VINTAGE("Vintage"),
    MATTE("Matte"),
    TEAL_ORANGE("Teal & orange"),
    MONO("Mono"),
    MONO_CONTRAST("Mono contrast"),
    SEPIA("Sepia"),
    ;

    companion object {
        fun of(name: String?): Look? = entries.firstOrNull { it.name == name }
    }
}

object Looks {
    private val landscapeMix = ColourMixer.NEUTRAL.toMutableList().apply {
        set(ColourMixer.index(ColourMixer.Band.GREEN, ColourMixer.Property.SATURATION), 0.25f)
        set(ColourMixer.index(ColourMixer.Band.AQUA, ColourMixer.Property.SATURATION), 0.2f)
        set(ColourMixer.index(ColourMixer.Band.BLUE, ColourMixer.Property.SATURATION), 0.25f)
        set(ColourMixer.index(ColourMixer.Band.BLUE, ColourMixer.Property.LUMINANCE), -0.2f)
        set(ColourMixer.index(ColourMixer.Band.YELLOW, ColourMixer.Property.HUE), 0.15f)
    }
    private val portraitMix = ColourMixer.NEUTRAL.toMutableList().apply {
        set(ColourMixer.index(ColourMixer.Band.ORANGE, ColourMixer.Property.LUMINANCE), 0.15f)
        set(ColourMixer.index(ColourMixer.Band.ORANGE, ColourMixer.Property.SATURATION), -0.1f)
        set(ColourMixer.index(ColourMixer.Band.RED, ColourMixer.Property.SATURATION), -0.1f)
    }
    private val filmMix = ColourMixer.NEUTRAL.toMutableList().apply {
        set(ColourMixer.index(ColourMixer.Band.GREEN, ColourMixer.Property.HUE), -0.3f)
        set(ColourMixer.index(ColourMixer.Band.GREEN, ColourMixer.Property.SATURATION), -0.2f)
        set(ColourMixer.index(ColourMixer.Band.BLUE, ColourMixer.Property.HUE), -0.2f)
    }

    /** [look] at [amount] on [rgb] (in place); nothing without a look. */
    fun apply(look: Look?, amount: Float, rgb: FloatArray) {
        if (look == null || amount == 0f) return
        val r0 = rgb[0]
        val g0 = rgb[1]
        val b0 = rgb[2]
        transform(look, rgb)
        rgb[0] = (r0 + (rgb[0] - r0) * amount).coerceIn(0f, 1f)
        rgb[1] = (g0 + (rgb[1] - g0) * amount).coerceIn(0f, 1f)
        rgb[2] = (b0 + (rgb[2] - b0) * amount).coerceIn(0f, 1f)
    }

    private fun transform(look: Look, c: FloatArray) {
        when (look) {
            Look.VIVID -> { contrast(c, 0.15f); vibrance(c, 0.35f) }
            Look.WARM -> { scale(c, 1.06f, 1f, 0.9f); toneHighlights(c, 0.04f, 0.02f, -0.02f) }
            Look.COOL -> { scale(c, 0.93f, 1f, 1.07f); toneShadows(c, -0.02f, 0.01f, 0.03f) }
            Look.LANDSCAPE -> { contrast(c, 0.12f); ColourMixer.apply(landscapeMix, c); vibrance(c, 0.1f) }
            Look.PORTRAIT -> { contrast(c, -0.05f); scale(c, 1.03f, 1f, 0.97f); ColourMixer.apply(portraitMix, c) }
            Look.FILM -> {
                fade(c, 0.03f, 0.97f); contrast(c, 0.1f); ColourMixer.apply(filmMix, c); saturation(c, 0.9f)
                toneShadows(c, -0.01f, 0.01f, 0.025f); toneHighlights(c, 0.03f, 0.015f, -0.02f)
            }
            Look.VINTAGE -> {
                saturation(c, 0.72f); contrast(c, 0.06f); fade(c, 0.07f, 0.93f)
                toneShadows(c, -0.02f, 0.02f, 0.01f); toneHighlights(c, 0.05f, 0.03f, -0.03f)
            }
            Look.MATTE -> { contrast(c, -0.08f); fade(c, 0.09f, 0.95f); saturation(c, 0.9f) }
            Look.TEAL_ORANGE -> { toneShadows(c, -0.04f, 0.02f, 0.05f); toneHighlights(c, 0.06f, 0.02f, -0.05f); vibrance(c, 0.1f) }
            Look.MONO -> mono(c, 1f, 1f, 1f)
            Look.MONO_CONTRAST -> { mono(c, 1f, 1f, 1f); contrast(c, 0.3f) }
            Look.SEPIA -> { mono(c, 1.08f, 0.98f, 0.82f); fade(c, 0.03f, 0.98f) }
        }
        for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f)
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    /** S-curve around mid grey (negative flattens). */
    private fun contrast(c: FloatArray, k: Float) {
        for (i in 0..2) {
            val x = c[i].coerceIn(0f, 1f)
            val s = x * x * (3f - 2f * x)
            c[i] = x + (s - x) * k * 2f
        }
    }

    private fun saturation(c: FloatArray, k: Float) {
        val l = luma(c)
        for (i in 0..2) c[i] = l + (c[i] - l) * k
    }

    /** Saturation that lifts muted colours more than strong ones. */
    private fun vibrance(c: FloatArray, k: Float) {
        val chroma = (maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2])).coerceIn(0f, 1f)
        saturation(c, 1f + k * (1f - chroma))
    }

    private fun scale(c: FloatArray, r: Float, g: Float, b: Float) {
        c[0] *= r; c[1] *= g; c[2] *= b
    }

    /** Blacks lifted to [black] and whites lowered to [white]: the faded look of old prints. */
    private fun fade(c: FloatArray, black: Float, white: Float) {
        for (i in 0..2) c[i] = black + c[i].coerceIn(0f, 1f) * (white - black)
    }

    private fun toneShadows(c: FloatArray, r: Float, g: Float, b: Float) {
        val w = (1f - luma(c) * 2f).coerceIn(0f, 1f)
        c[0] += r * w; c[1] += g * w; c[2] += b * w
    }

    private fun toneHighlights(c: FloatArray, r: Float, g: Float, b: Float) {
        val w = ((luma(c) - 0.4f) / 0.6f).coerceIn(0f, 1f)
        c[0] += r * w; c[1] += g * w; c[2] += b * w
    }

    private fun mono(c: FloatArray, r: Float, g: Float, b: Float) {
        // Weighted a little towards red and green, as black-and-white film sees.
        val y = (0.3f * c[0] + 0.6f * c[1] + 0.1f * c[2]).coerceIn(0f, 1f)
        c[0] = y * r; c[1] = y * g; c[2] = y * b
    }
}
