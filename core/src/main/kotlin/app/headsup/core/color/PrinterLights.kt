package app.headsup.core.color

import kotlin.math.pow

/**
 * Printer lights, as a film lab timed a print: the red, green and blue light that exposed the print
 * stock, each in printer points. A point is 0.025 log exposure, about 1/12 stop, of that colour's
 * light, so the colour of the whole picture moves evenly from the shadows to the highlights (an
 * offset in log, not a tint that bends the ends). Adding a colour's light adds that colour; adding
 * all three brightens the print. Applied first, on the footage as it comes in.
 */
object PrinterLights {
    val NEUTRAL: List<Float> = listOf(0f, 0f, 0f)

    /** Points per stop. */
    const val PER_STOP = 12f

    /** Most points either way. */
    const val MAX = 24f

    /** On log footage: an offset of each channel by its points, [logStop] per stop. */
    fun applyLog(points: List<Float>, rgb: FloatArray, logStop: Float) {
        if (points.all { it == 0f }) return
        for (i in 0..2) rgb[i] += points.getOrElse(i) { 0f } / PER_STOP * logStop
    }

    /** On display (gamma 2.2) colours: each channel's light multiplied by 2^(points / 12). */
    fun applyDisplay(points: List<Float>, rgb: FloatArray) {
        if (points.all { it == 0f }) return
        for (i in 0..2) {
            val p = points.getOrElse(i) { 0f }
            if (p == 0f) continue
            rgb[i] = (rgb[i].coerceAtLeast(0f).pow(2.2f) * 2f.pow(p / PER_STOP)).pow(1f / 2.2f)
        }
    }

    /**
     * The three channels after pressing a key: red, green or blue ([channel] 0..2) or all of them
     * ([channel] 3, the master: brightness), by [step] points; cyan, magenta and yellow are the
     * same keys taken away.
     */
    fun pressed(points: List<Float>, channel: Int, step: Float): List<Float> =
        List(3) { i -> (points.getOrElse(i) { 0f } + if (channel == 3 || channel == i) step else 0f).coerceIn(-MAX, MAX) }
}
