package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colour mixer (Lightroom's HSL panel): for each of eight hue bands a hue shift, a saturation and
 * a luminance change, −1..1. A colour between two band centres gets a blend of both bands, and
 * greys (no hue) are left alone.
 */
object ColourMixer {
    enum class Band(val label: String, val hue: Float, val swatch: Int) {
        RED("Red", 0f, 0xFFE0453A.toInt()),
        ORANGE("Orange", 30f, 0xFFF08A2C.toInt()),
        YELLOW("Yellow", 60f, 0xFFF2D43A.toInt()),
        GREEN("Green", 120f, 0xFF4CB848.toInt()),
        AQUA("Aqua", 180f, 0xFF3CC8C8.toInt()),
        BLUE("Blue", 230f, 0xFF3A6FE0.toInt()),
        PURPLE("Purple", 275f, 0xFF8A4FD8.toInt()),
        MAGENTA("Magenta", 320f, 0xFFD848B4.toInt()),
    }

    enum class Property(val label: String) { HUE("Hue"), SATURATION("Saturation"), LUMINANCE("Luminance") }

    const val SIZE = 24

    val NEUTRAL: List<Float> = List(SIZE) { 0f }

    fun index(band: Band, property: Property) = band.ordinal * 3 + property.ordinal

    /** Largest hue shift (at ±1), degrees. */
    private const val HUE_RANGE = 30f

    /** Applies [mixer] to a display colour (0..1 per channel) in place. */
    fun apply(mixer: List<Float>, rgb: FloatArray) {
        if (mixer.size < SIZE || mixer.all { it == 0f }) return
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val v = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val chroma = v - mn
        if (v <= 0f || chroma <= 1e-5f) return
        var h = when (v) {
            r -> 60f * ((g - b) / chroma)
            g -> 60f * (2f + (b - r) / chroma)
            else -> 60f * (4f + (r - g) / chroma)
        }
        if (h < 0f) h += 360f
        var s = chroma / v

        // The two bands around the hue and how much of each.
        val bands = Band.entries
        var hueShift = 0f
        var satChange = 0f
        var lumChange = 0f
        for (i in bands.indices) {
            val c = bands[i].hue
            var next = bands[(i + 1) % bands.size].hue
            if (next <= c) next += 360f
            var prev = bands[(i + bands.size - 1) % bands.size].hue
            if (prev >= c) prev -= 360f
            var d = h - c
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            val w = if (d >= 0f) (1f - d / (next - c)).coerceIn(0f, 1f) else (1f - d / (prev - c)).coerceIn(0f, 1f)
            if (w == 0f) continue
            hueShift += w * mixer[i * 3]
            satChange += w * mixer[i * 3 + 1]
            lumChange += w * mixer[i * 3 + 2]
        }
        // Muted colours change less (a near-grey has no clear hue).
        val strength = (s / 0.2f).coerceIn(0f, 1f)
        h = (h + hueShift * HUE_RANGE * strength + 360f) % 360f
        s = (s * (1f + satChange * strength)).coerceIn(0f, 1f)
        val v2 = (v * (1f + lumChange * 0.5f * strength)).coerceIn(0f, 1.5f)

        // Back from hue, saturation and value.
        val c2 = v2 * s
        val hp = h / 60f
        val x = c2 * (1f - abs(hp % 2f - 1f))
        val m = v2 - c2
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c2, x, 0f)
            1 -> Triple(x, c2, 0f)
            2 -> Triple(0f, c2, x)
            3 -> Triple(0f, x, c2)
            4 -> Triple(x, 0f, c2)
            else -> Triple(c2, 0f, x)
        }
        rgb[0] = r1 + m
        rgb[1] = g1 + m
        rgb[2] = b1 + m
    }
}
