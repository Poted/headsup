package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** What the planner can look for in the sky. */
enum class Body(val label: String) { Sun("Sun"), Moon("Moon"), GalacticCentre("Milky Way centre") }

/** A moment a body stands in the direction looked for: when, where exactly, and the light around it. */
data class AlignMatch(
    val millis: Long,
    val azimuth: Double,
    val altitude: Double,
    /** How much of the moon is lit then (0 … 1). */
    val moonLit: Double,
    /** How high the sun is then (for the Milky Way: below −18° is fully dark). */
    val sunAltitude: Double,
)

/**
 * Finding when a body stands where the photographer wants it: in a direction (towards a peak, a
 * tower, a gap between trees) and, if given, at a height (just above the peak). Day by day, the
 * moment the body crosses that direction is found, and kept when its height is near the one asked for.
 */
object Alignment {
    fun position(body: Body, place: Place, t: Long): SkyPoint = when (body) {
        Body.Sun -> Sky.sun(place, t)
        Body.Moon -> Sky.moon(place, t).position
        Body.GalacticCentre -> Sky.galacticCentre(place, t)
    }

    private fun azDiff(a: Double, target: Double): Double = ((a - target + 540.0) % 360.0) - 180.0

    /**
     * The moments within [days] days from [from] when [body] stands at [azimuth] (degrees from true
     * north) and, when [altitude] is given, within [altTolerance] degrees of it. The Milky Way's
     * centre counts only in a fully dark sky; the sun and the moon only when not far below the horizon.
     */
    fun find(
        place: Place, zone: ZoneId, from: LocalDate, days: Int, body: Body,
        azimuth: Double, altitude: Double?, altTolerance: Double = 1.0,
    ): List<AlignMatch> {
        val out = ArrayList<AlignMatch>()
        val step = 4 * 60_000L
        for (day in 0 until days) {
            val start = from.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            var prevT = start
            var prev = azDiff(position(body, place, prevT).azimuth, azimuth)
            var t = start + step
            val end = start + 24 * 3_600_000L
            while (t <= end) {
                val d = azDiff(position(body, place, t).azimuth, azimuth)
                // A crossing: the sign turns while both sides are near the direction (not across the opposite one).
                if ((prev < 0) != (d < 0) && abs(prev) < 90 && abs(d) < 90) {
                    var lo = prevT; var hi = t; val loNeg = prev < 0
                    repeat(20) {
                        val mid = (lo + hi) / 2
                        val m = azDiff(position(body, place, mid).azimuth, azimuth)
                        if ((m < 0) == loNeg) lo = mid else hi = mid
                    }
                    val when_ = (lo + hi) / 2
                    val p = position(body, place, when_)
                    val sunAlt = Sky.sun(place, when_).altitude
                    val visible = when (body) {
                        Body.GalacticCentre -> sunAlt <= -18.0 && p.altitude > -1.0
                        else -> p.altitude > -2.0
                    }
                    val heightOk = altitude == null || abs(p.altitude - altitude) <= altTolerance
                    if (visible && heightOk) out += AlignMatch(when_, p.azimuth, p.altitude, Sky.moon(place, when_).lit, sunAlt)
                }
                prevT = t; prev = d; t += step
            }
        }
        return out
    }
}
