package app.headsup.core.planner

import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** What the light of a place depends on besides the sun and moon: [bortle] (1 darkest sky … 9 city centre) and [cloud] cover (0 clear … 1 overcast). */
data class SkyConditions(val bortle: Int = 3, val cloud: Double = 0.0)

/**
 * The exposure value (at ISO 100) a scene needs for a place and a moment, estimated from where
 * the sun and moon stand, the height above the sea, the sky's brightness at night and the cloud.
 * It is the exposure for a mid-tone subject under that light, good to about a stop and a half;
 * where the phone's camera can measure the light, the measured value takes over and only the
 * change over time is taken from here ([relative]).
 */
object SceneLight {
    // Light on a mid-tone subject, as exposure value at ISO 100 (an incident reading: the sun high and clear is about 15).
    // Sun altitude in degrees → EV, interpolated; below −18° the sun adds nothing.
    private val sunTable = listOf(
        -18.0 to -11.0, -12.0 to -3.0, -6.0 to 2.0, 0.0 to 7.3, 5.0 to 11.0,
        10.0 to 12.1, 20.0 to 13.3, 30.0 to 13.8, 60.0 to 14.8, 90.0 to 15.2,
    )

    // A night with no moon, as the EV of a correctly exposed Milky Way or landscape shot, by Bortle class 1…9.
    private val nightTable = doubleArrayOf(-6.5, -6.2, -5.8, -5.2, -4.5, -3.5, -2.5, -1.0, 1.0)

    private fun interpolate(x: Double, table: List<Pair<Double, Double>>): Double {
        if (x <= table.first().first) return table.first().second
        if (x >= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (x0, y0) = table[i]
            val (x1, y1) = table[i + 1]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return table.last().second
    }

    /** The light the sun alone gives: 2^EV, so that lights add. Zero deep below the horizon. */
    private fun sunLight(altitude: Double, elevation: Double, cloud: Double): Double {
        if (altitude <= -18.0) return 0.0
        val atmosphere = exp(0.00006 * elevation.coerceIn(0.0, 6000.0) * (if (altitude > 0) 1.0 else 0.0))
        val clouds = 1.0 - 0.75 * cloud.coerceIn(0.0, 1.0).pow(3.4)
        return 2.0.pow(interpolate(altitude, sunTable)) * atmosphere * clouds
    }

    /** The light the moon gives: about EV −2.5 for a full moon at the zenith, much less when thin or low. */
    private fun moonLight(moon: MoonState, cloud: Double): Double {
        val up = sin(Math.toRadians(moon.position.altitude))
        if (up <= 0.0) return 0.0
        val phase = moon.lit * moon.lit
        return 2.0.pow(-2.5) * phase * up.pow(0.8) * (1.0 - 0.7 * cloud.coerceIn(0.0, 1.0).pow(2))
    }

    /** The exposure value (ISO 100) a scene needs at [place] and [epochMillis]. */
    fun ev(place: Place, epochMillis: Long, conditions: SkyConditions = SkyConditions()): Double {
        val sun = Sky.sun(place, epochMillis)
        val moon = Sky.moon(place, epochMillis)
        val night = 2.0.pow(nightTable[(conditions.bortle - 1).coerceIn(0, 8)])
        val total = sunLight(sun.altitude, place.elevation, conditions.cloud) + moonLight(moon, conditions.cloud) + night
        return log2(max(total, 1e-9))
    }

    /** The light at [epochMillis] when the light at [baseMillis] was measured as [measuredEv]: the measure moved by the model's change. */
    fun relative(place: Place, baseMillis: Long, measuredEv: Double, epochMillis: Long, conditions: SkyConditions = SkyConditions()): Double =
        measuredEv + ev(place, epochMillis, conditions) - ev(place, baseMillis, conditions)

    /** A plain name for the light: Day, Golden hour, Blue hour, Twilight, Night. */
    fun phase(sunAltitude: Double): String = when {
        sunAltitude > 6.0 -> "Day"
        sunAltitude > -0.833 -> "Golden hour"
        sunAltitude > -6.0 -> "Civil twilight"
        sunAltitude > -12.0 -> "Blue hour"
        sunAltitude > -18.0 -> "Twilight"
        else -> "Night"
    }
}
