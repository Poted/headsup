package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The parts of a "cinematic" grade that work on display colours, after the LUT: a soft highlight
 * shoulder ([ColorGrade.rolloff]), split toning ([ColorGrade.toneAmount]) and calming loud colours
 * ([ColorGrade.tame]). Skin is protected from toning and taming, as a colourist would with a
 * qualifier.
 */
object FilmLook {
    /** Transforms one display-referred colour in place. */
    fun apply(grade: ColorGrade, rgb: FloatArray) {
        if (grade.rolloff > 0f) rolloff(grade.rolloff, rgb)
        if (grade.toneAmount <= 0f && grade.tame <= 0f) return

        val y = luma(rgb)
        var cb = (rgb[2] - y) / 1.8556f
        var cr = (rgb[0] - y) / 1.5748f
        val chroma = sqrt(cb * cb + cr * cr)
        val skin = skinness(cb, cr, chroma)

        if (grade.tame > 0f && chroma > 1e-4f) {
            val angle = angleOf(cb, cr)
            // Greens (around 230°) and deep blues (around 350°) are the usual offenders.
            val loud = maxOf(near(angle, 230f, 50f), near(angle, 350f, 35f) * 0.7f)
            val k = grade.tame * smoothstep(0.04f, 0.22f, chroma) * (0.35f + 0.65f * loud) * (1f - skin)
            val keep = 1f - (0.6f * k).coerceIn(0f, 0.6f)
            cb *= keep
            cr *= keep
        }
        if (grade.toneAmount > 0f) {
            val shadowW = 1f - smoothstep(0.08f, 0.5f, y)
            val highlightW = smoothstep(0.45f, 0.95f, y)
            val strength = grade.toneAmount * TONE_CHROMA * (1f - 0.75f * skin)
            val sh = Math.toRadians(grade.shadowHue.toDouble())
            val hh = Math.toRadians(grade.highlightHue.toDouble())
            cb += strength * (shadowW * cos(sh) + highlightW * cos(hh)).toFloat()
            cr += strength * (shadowW * sin(sh) + highlightW * sin(hh)).toFloat()
        }
        val r = y + 1.5748f * cr
        val b = y + 1.8556f * cb
        val g = (y - 0.2126f * r - 0.0722f * b) / 0.7152f
        rgb[0] = r.coerceIn(0f, 1f)
        rgb[1] = g.coerceIn(0f, 1f)
        rgb[2] = b.coerceIn(0f, 1f)
    }

    /** How much colour toning adds at full strength, in Cb/Cr units. */
    private const val TONE_CHROMA = 0.05f

    /**
     * Highlights above a knee bend smoothly (no kink: the slope stays 1 at the knee) and white
     * settles a little below full, like a print; they also lose some colour, as film does.
     */
    private fun rolloff(amount: Float, rgb: FloatArray) {
        val y = luma(rgb)
        val knee = 1f - 0.5f * amount
        if (y <= knee) return
        val t = (y - knee) / (1f - knee)
        val mapped = knee + (1f - knee) * (t - 0.5f * t * t)
        // At t = 1 this gives knee + (1 - knee) / 2: white at 1 - amount / 4.
        val scale = mapped / y
        val desaturate = 0.35f * amount * t
        for (i in 0..2) {
            val v = rgb[i] * scale
            rgb[i] = (v + (mapped - v) * desaturate).coerceIn(0f, 1f)
        }
    }

    /** 1 for typical skin (near the 123° line, moderately saturated), falling to 0 away from it. */
    internal fun skinness(cb: Float, cr: Float, chroma: Float): Float {
        if (chroma < 0.01f) return 0f
        val d = abs(SkinTone.angleDifference(angleOf(cb, cr).toDouble(), SkinTone.LINE_DEGREES)).toFloat()
        return (1f - smoothstep(15f, 35f, d)) * smoothstep(0.01f, 0.04f, chroma) * (1f - smoothstep(0.2f, 0.3f, chroma))
    }

    internal fun angleOf(cb: Float, cr: Float): Float =
        Math.toDegrees(kotlin.math.atan2(cr.toDouble(), cb.toDouble())).toFloat().let { if (it < 0) it + 360f else it }

    /** 1 at [centre], 0 beyond [width] degrees away. */
    private fun near(angle: Float, centre: Float, width: Float): Float {
        val d = abs(SkinTone.angleDifference(angle.toDouble(), centre.toDouble())).toFloat()
        return 1f - smoothstep(width * 0.4f, width, d)
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
