package app.headsup.core.planner

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where something is in the sky of a place: [azimuth] in degrees from north through east, [altitude] above the flat horizon. */
data class SkyPoint(val azimuth: Double, val altitude: Double)

/** The moon at a moment: where it is and how much of its face is lit (0 new … 1 full). */
data class MoonState(val position: SkyPoint, val lit: Double)

/** A place on Earth: [lat] and [lon] in degrees (east positive), [elevation] in metres above the sea. */
data class Place(val lat: Double, val lon: Double, val elevation: Double = 0.0)

/**
 * Where the sun, the moon and the Milky Way are for any place and time, worked out on the phone
 * with no data. The sun is good to about 0.02°, the moon to about 0.3° (enough to frame a shot),
 * the Milky Way is drawn for the year 2000's sky (its drift since is under half a degree).
 */
object Sky {
    private const val RAD = PI / 180.0
    private const val DEG = 180.0 / PI

    /** The galactic centre (Sagittarius A*), equatorial J2000: right ascension and declination in degrees. */
    private const val GC_RA = 266.405
    private const val GC_DEC = -28.936

    // The north galactic pole and the galactic longitude of the celestial pole, J2000.
    private const val NGP_RA = 192.85948
    private const val NGP_DEC = 27.12825
    private const val NCP_L = 122.93192

    /** Julian day of an instant in milliseconds since 1970. */
    fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    private fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    /** Turns a point given by right ascension and declination (degrees) into where it stands at [place] and [epochMillis]. */
    fun horizontal(raDeg: Double, decDeg: Double, place: Place, epochMillis: Long): SkyPoint {
        val d = julianDay(epochMillis) - 2_451_545.0
        val lst = norm360(280.46061837 + 360.98564736629 * d + place.lon)
        val ha = (lst - raDeg) * RAD
        val dec = decDeg * RAD
        val lat = place.lat * RAD
        val alt = asin(sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha))
        val az = atan2(sin(ha), cos(ha) * sin(lat) - tan(dec) * cos(lat)) * DEG + 180.0
        return SkyPoint(norm360(az), alt * DEG)
    }

    private fun obliquity(d: Double) = (23.4393 - 3.563e-7 * d) * RAD

    private fun sunEquatorial(epochMillis: Long): DoubleArray {
        val d = julianDay(epochMillis) - 2_451_545.0
        val l = norm360(280.460 + 0.9856474 * d)
        val g = norm360(357.528 + 0.9856003 * d) * RAD
        val lambda = (l + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val e = obliquity(d)
        val ra = norm360(atan2(cos(e) * sin(lambda), cos(lambda)) * DEG)
        val dec = asin(sin(e) * sin(lambda)) * DEG
        return doubleArrayOf(ra, dec)
    }

    private fun moonEquatorial(epochMillis: Long): DoubleArray {
        val d = julianDay(epochMillis) - 2_451_545.0
        val l = norm360(218.316 + 13.176396 * d) * RAD
        val m = norm360(134.963 + 13.064993 * d) * RAD
        val f = norm360(93.272 + 13.229350 * d) * RAD
        val lon = l + 6.289 * RAD * sin(m)
        val lat = 5.128 * RAD * sin(f)
        val e = obliquity(d)
        val ra = norm360(atan2(sin(lon) * cos(e) - tan(lat) * sin(e), cos(lon)) * DEG)
        val dec = asin(sin(lat) * cos(e) + cos(lat) * sin(e) * sin(lon)) * DEG
        return doubleArrayOf(ra, dec)
    }

    /** Where the sun is. */
    fun sun(place: Place, epochMillis: Long): SkyPoint {
        val s = sunEquatorial(epochMillis)
        return horizontal(s[0], s[1], place, epochMillis)
    }

    /** Where the moon is, and how much of it is lit. */
    fun moon(place: Place, epochMillis: Long): MoonState {
        val m = moonEquatorial(epochMillis)
        val s = sunEquatorial(epochMillis)
        val phi = acos((sin(s[1] * RAD) * sin(m[1] * RAD) + cos(s[1] * RAD) * cos(m[1] * RAD) * cos((s[0] - m[0]) * RAD)).coerceIn(-1.0, 1.0))
        val sunDist = 149_598_000.0
        val moonDist = 385_001.0
        val inc = atan2(sunDist * sin(phi), moonDist - sunDist * cos(phi))
        return MoonState(horizontal(m[0], m[1], place, epochMillis), (1 + cos(inc)) / 2)
    }

    /** Where the centre of the Milky Way (the bright bulge in Sagittarius) is. */
    fun galacticCentre(place: Place, epochMillis: Long): SkyPoint = horizontal(GC_RA, GC_DEC, place, epochMillis)

    /** The right ascension and declination (degrees) of the galactic plane at galactic longitude [l] (degrees). */
    fun galacticPlane(l: Double): DoubleArray {
        val dl = (NCP_L - l) * RAD
        // On the galactic equator (latitude 0) the general conversion shortens to this.
        val dec = asin(cos(NGP_DEC * RAD) * cos(dl))
        val ra = NGP_RA * RAD + atan2(sin(dl), -sin(NGP_DEC * RAD) * cos(dl))
        return doubleArrayOf(norm360(ra * DEG), dec * DEG)
    }

    /** The band of the Milky Way as points along the galactic equator, every [stepDeg] of galactic longitude, as they stand at [epochMillis]. */
    fun milkyWayArc(place: Place, epochMillis: Long, stepDeg: Int = 10): List<SkyPoint> =
        (0 until 360 step stepDeg).map { l ->
            val p = galacticPlane(l.toDouble())
            horizontal(p[0], p[1], place, epochMillis)
        }

    /** A body's path over one local day: a point every [stepMinutes], from midnight. */
    fun daySamples(zone: ZoneId, date: LocalDate, stepMinutes: Int = 10): List<Long> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<Long>()
        var t = start
        while (t < end) { out += t; t += stepMinutes * 60_000L }
        return out
    }

    /** The sun's times on a local day, as moments (null where it does not rise or set that day, as in polar summer). */
    data class SunTimes(
        val sunrise: Long?, val sunset: Long?,
        val civilDawn: Long?, val civilDusk: Long?,
        val astroDawn: Long?, val astroDusk: Long?,
    )

    /** Sunrise, sunset and the twilights of [date] at [place]. Rise and set mean the sun's top edge on the horizon (−0.833°). */
    fun sunTimes(place: Place, zone: ZoneId, date: LocalDate): SunTimes {
        val samples = daySamples(zone, date, 5) + date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun crossing(level: Double, rising: Boolean): Long? {
            for (i in 0 until samples.size - 1) {
                val a = sun(place, samples[i]).altitude - level
                val b = sun(place, samples[i + 1]).altitude - level
                val hit = if (rising) a < 0 && b >= 0 else a >= 0 && b < 0
                if (hit) {
                    var lo = samples[i]; var hi = samples[i + 1]
                    repeat(24) {
                        val mid = (lo + hi) / 2
                        val v = sun(place, mid).altitude - level
                        val below = v < 0
                        if (below == rising) lo = mid else hi = mid
                    }
                    return (lo + hi) / 2
                }
            }
            return null
        }
        return SunTimes(
            crossing(-0.833, true), crossing(-0.833, false),
            crossing(-6.0, true), crossing(-6.0, false),
            crossing(-18.0, true), crossing(-18.0, false),
        )
    }

    /** The instant [epochMillis] as a local date in [zone]. */
    fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
