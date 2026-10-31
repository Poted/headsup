package app.headsup.core.color

/**
 * Colour density, as in DaVinci Resolve's Color Slice: saturation the way film and paint have it,
 * subtractive. More density makes a saturated colour deeper (darker, a little richer) instead of
 * louder; less makes it lighter and paler. Greys are left as they are; how much a colour changes
 * goes with how saturated it is. Works on display (encoded) values, in place.
 */
object ColourDensity {
    fun apply(amount: Float, rgb: FloatArray) {
        if (amount == 0f) return
        val s = (maxOf(rgb[0], rgb[1], rgb[2]) - minOf(rgb[0], rgb[1], rgb[2])).coerceIn(0f, 1f)
        if (s <= 0f) return
        val k = amount.coerceIn(-1f, 1f)
        if (k > 0f) {
            // Darker by its saturation (the ratios kept, so the hue stays), its colour a little richer.
            val dark = 1f - 0.45f * k * s
            for (i in 0..2) rgb[i] *= dark
            val l = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            val rich = 1f + 0.2f * k * s
            for (i in 0..2) rgb[i] = (l + (rgb[i] - l) * rich).coerceIn(0f, 1f)
        } else {
            // Lighter towards white by its saturation: pastel.
            val light = -0.35f * k * s
            for (i in 0..2) rgb[i] = (rgb[i] + (1f - rgb[i]) * light).coerceIn(0f, 1f)
        }
    }
}
