package app.headsup.core.color

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One colour picked from the picture and changed on its own (Lightroom's Point Color): that colour
 * and its family within [range] (0 just it … 1 much of its family, measured in Oklab as the colour
 * masks are) turned in hue ([hue], −1..1: ±60°), made richer or paler ([saturation]) and lighter
 * or darker ([luminance]). [r], [g], [b] is the picked colour, encoded 0..1.
 */
data class PointColour(
    val r: Float,
    val g: Float,
    val b: Float,
    val range: Float = 0.35f,
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    val isNeutral: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f
}

object PointColours {
    /** Most colours that can be picked and changed at once. */
    const val MAX = 6

    private const val HUE_RANGE_DEG = 60f

    /** How much of [p]'s family the colour is in, 0..1 (as a colour mask's range). */
    fun weight(p: PointColour, r: Float, g: Float, b: Float): Float =
        MaskRange.colourWeight(ColourRange(p.r, p.g, p.b, p.range), r, g, b)

    /**
     * Applies [points] and colour pop ([pop] 0..1: how much the colours outside every picked
     * family lose their colour, leaving only the picked ones) to an encoded colour, in place.
     * Families are told by [source], the colour as it came from the camera (as the picker and the
     * colour masks see it), else by the colour as it is.
     */
    fun apply(points: List<PointColour>, pop: Float, rgb: FloatArray, source: FloatArray? = null) {
        if (points.isEmpty()) return
        val r0 = source?.get(0) ?: rgb[0]
        val g0 = source?.get(1) ?: rgb[1]
        val b0 = source?.get(2) ?: rgb[2]
        var kept = 0f
        // Each picked colour changes its family; the weights are of the colour as it came in.
        for (p in points) {
            val w = weight(p, r0, g0, b0)
            kept = max(kept, w)
            if (w <= 0f || p.isNeutral) continue
            shift(rgb, p.hue * HUE_RANGE_DEG * w, p.saturation * w, p.luminance * w)
        }
        if (pop > 0f) {
            // Everything else towards grey (its own brightness): only the picked colours stay.
            val out = pop.coerceIn(0f, 1f) * (1f - kept)
            if (out > 0f) {
                val y = MaskRange.luma(rgb[0], rgb[1], rgb[2])
                for (i in 0..2) rgb[i] = rgb[i] + (y - rgb[i]) * out
            }
        }
    }

    /** Turns the colour's hue by [degrees] and changes its colourfulness and lightness, in Oklab (LCh). */
    private fun shift(rgb: FloatArray, degrees: Float, sat: Float, lum: Float) {
        val lab = MaskRange.oklab(rgb[0], rgb[1], rgb[2])
        var l = lab[0]
        var c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        var h = atan2(lab[2], lab[1]) + Math.toRadians(degrees.toDouble()).toFloat()
        c *= (1f + sat).coerceAtLeast(0f)
        // Lightness: up to about two thirds of a stop either way, kept off black and white.
        l = if (lum >= 0f) l + (1f - l) * lum * 0.35f else l * (1f + lum * 0.35f)
        val a = c * cos(h)
        val bb = c * sin(h)
        val out = fromOklab(l, a, bb)
        for (i in 0..2) rgb[i] = out[i].coerceIn(0f, 1f)
    }

    /** Oklab back to an encoded (sRGB) colour. */
    fun fromOklab(l: Float, a: Float, b: Float): FloatArray {
        val l1 = l + 0.3963377774f * a + 0.2158037573f * b
        val m1 = l - 0.1055613458f * a - 0.0638541728f * b
        val s1 = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = l1 * l1 * l1
        val mc = m1 * m1 * m1
        val sc = s1 * s1 * s1
        val lr = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        val lg = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        val lb = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
        fun enc(v: Float): Float {
            val x = min(max(v, 0f), 1f)
            return if (x <= 0.0031308f) x * 12.92f else (1.055f * Math.pow(x.toDouble(), 1.0 / 2.4) - 0.055f).toFloat()
        }
        return floatArrayOf(enc(lr), enc(lg), enc(lb))
    }
}
