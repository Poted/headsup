package app.headsup.core.planner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * The values a camera offers, a third of a stop apart, as its dials print them: shutter times from
 * 1/8000 s to 30 s (and a few long ones for night work), f-numbers from f/1 to f/32, ISO from 50 up.
 * Settings are moved along these, and worked-out values are shown as the nearest of them.
 */
object CameraScale {
    val shutters: List<Double> =
        listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
            .map { 1.0 / it } +
            listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0, 60.0, 120.0, 240.0, 480.0)

    val apertures: List<Double> = listOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1,
        8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0,
    )

    val isos: List<Double> = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400,
    ).map { it.toDouble() }

    /** The place in [scale] nearest to [value] (in stops). */
    fun nearest(scale: List<Double>, value: Double): Int =
        scale.indices.minByOrNull { abs(log2(scale[it] / value)) } ?: 0

    /** [value] moved [steps] places along [scale] from the nearest one (clamped at its ends). */
    fun step(scale: List<Double>, value: Double, steps: Int): Double =
        scale[(nearest(scale, value) + steps).coerceIn(0, scale.size - 1)]

    /** A shutter time as a camera prints it: 1/250s, 1/2s, 0.8s, 1s, 1.3s, 30s; past 30 s the time itself (45s, 2m). */
    fun shutterLabel(seconds: Double): String {
        if (seconds > 31.0) return if (seconds < 100) "${seconds.roundToInt()}s" else "${(seconds / 60).let { if (it < 10) ((it * 10).roundToInt() / 10.0).toString().removeSuffix(".0") else it.roundToInt().toString() }}m"
        val v = shutters[nearest(shutters, seconds)]
        return when {
            v >= 1.0 -> number(v) + "s"
            abs(v - 0.5) < 1e-9 -> "1/2s"
            abs(v - 0.3) < 1e-9 -> "1/3s"
            v >= 0.3 -> number(v) + "s"
            else -> "1/${(1 / v).roundToInt()}s"
        }
    }

    /** An f-number as a lens prints it: f/2.8, f/8, f/11. */
    fun apertureLabel(n: Double): String = "f/" + number(apertures[nearest(apertures, n)])

    /** An ISO as the camera's scale has it. */
    fun isoValue(iso: Double): Int = isos[nearest(isos, iso)].roundToInt()

    private fun number(v: Double): String = if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString() else v.toString()
}
