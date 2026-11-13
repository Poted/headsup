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

    /** The sun's ecliptic longitude in degrees. */
    private fun sunLongitude(epochMillis: Long): Double {
        val d = julianDay(epochMillis) - 2_451_545.0
        val l = norm360(280.460 + 0.9856474 * d)
        val g = norm360(357.528 + 0.9856003 * d) * RAD
        return norm360(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
    }

    private fun sunEquatorial(epochMillis: Long): DoubleArray {
        val d = julianDay(epochMillis) - 2_451_545.0
        val lambda = sunLongitude(epochMillis) * RAD
        val e = obliquity(d)
        val ra = norm360(atan2(cos(e) * sin(lambda), cos(lambda)) * DEG)
        val dec = asin(sin(e) * sin(lambda)) * DEG
        return doubleArrayOf(ra, dec)
    }

    /**
     * The moon's ecliptic longitude and latitude (degrees) and distance (km), with the main terms of
     * its motion (evection, variation, the annual term and the larger smaller ones): about 0.1–0.2°.
     */
    private fun moonEcliptic(epochMillis: Long): DoubleArray {
        val d = julianDay(epochMillis) - 2_451_545.0
        val l = norm360(218.316 + 13.176396 * d)
        val m = norm360(134.963 + 13.064993 * d) * RAD // the moon's mean anomaly
        val f = norm360(93.272 + 13.229350 * d) * RAD // its argument of latitude
        val dd = norm360(297.850 + 12.190749 * d) * RAD // mean elongation from the sun
        val ms = norm360(357.529 + 0.98560028 * d) * RAD // the sun's mean anomaly
        val lon = l + 6.289 * sin(m) + 1.274 * sin(2 * dd - m) + 0.658 * sin(2 * dd) - 0.186 * sin(ms) -
            0.059 * sin(2 * m - 2 * dd) - 0.057 * sin(m - 2 * dd + ms) + 0.053 * sin(m + 2 * dd) +
            0.046 * sin(2 * dd - ms) + 0.041 * sin(m - ms) - 0.035 * sin(dd) - 0.031 * sin(m + ms) -
            0.015 * sin(2 * f - 2 * dd) + 0.011 * sin(m - 4 * dd)
        val lat = 5.128 * sin(f) + 0.281 * sin(m + f) + 0.278 * sin(m - f) + 0.173 * sin(2 * dd - f) +
            0.055 * sin(2 * dd - m + f) + 0.046 * sin(2 * dd - m - f) + 0.033 * sin(2 * dd + f) + 0.017 * sin(2 * m + f)
        val distance = 385_001.0 - 20_905.0 * cos(m) - 3_699.0 * cos(2 * dd - m) - 2_956.0 * cos(2 * dd) - 570.0 * cos(2 * m)
        return doubleArrayOf(norm360(lon), lat, distance)
    }

    private fun moonEquatorial(epochMillis: Long): DoubleArray {
        val d = julianDay(epochMillis) - 2_451_545.0
        val ecl = moonEcliptic(epochMillis)
        val lon = ecl[0] * RAD
        val lat = ecl[1] * RAD
        val e = obliquity(d)
        val ra = norm360(atan2(sin(lon) * cos(e) - tan(lat) * sin(e), cos(lon)) * DEG)
        val dec = asin(sin(lat) * cos(e) + cos(lat) * sin(e) * sin(lon)) * DEG
        return doubleArrayOf(ra, dec, ecl[2])
    }

    /** How far the moon has gone round from the sun, degrees: 0 new, 90 first quarter, 180 full, 270 last quarter. */
    fun moonPhaseAngle(epochMillis: Long): Double = norm360(moonEcliptic(epochMillis)[0] - sunLongitude(epochMillis))

    /** A moment the moon reaches a phase. */
    data class MoonPhase(val millis: Long, val name: String)

    /** New moons, quarters and full moons from [fromMillis] for [days] days. */
    fun moonPhases(fromMillis: Long, days: Int): List<MoonPhase> {
        val names = listOf(0.0 to "New moon", 90.0 to "First quarter", 180.0 to "Full moon", 270.0 to "Last quarter")
        val out = ArrayList<MoonPhase>()
        val step = 6 * 3_600_000L
        var t = fromMillis
        val end = fromMillis + days * 86_400_000L
        fun past(target: Double, at: Long) = ((moonPhaseAngle(at) - target + 540.0) % 360.0) - 180.0
        while (t < end) {
            for ((angle, name) in names) {
                val a = past(angle, t)
                val b = past(angle, t + step)
                if (a < 0 && b >= 0 && b - a < 90) {
                    var lo = t; var hi = t + step
                    repeat(24) { val mid = (lo + hi) / 2; if (past(angle, mid) < 0) lo = mid else hi = mid }
                    out += MoonPhase((lo + hi) / 2, name)
                }
            }
            t += step
        }
        return out
    }

    /** Where the sun is. */
    fun sun(place: Place, epochMillis: Long): SkyPoint {
        val s = sunEquatorial(epochMillis)
        return horizontal(s[0], s[1], place, epochMillis)
    }

    /**
     * Where the moon is, and how much of it is lit. Its height is as seen from the ground, not from the
     * Earth's centre: the moon is near enough to stand up to a degree lower near the horizon (parallax).
     */
    fun moon(place: Place, epochMillis: Long): MoonState {
        val m = moonEquatorial(epochMillis)
        val s = sunEquatorial(epochMillis)
        val phi = acos((sin(s[1] * RAD) * sin(m[1] * RAD) + cos(s[1] * RAD) * cos(m[1] * RAD) * cos((s[0] - m[0]) * RAD)).coerceIn(-1.0, 1.0))
        val sunDist = 149_598_000.0
        val moonDist = m[2]
        val inc = atan2(sunDist * sin(phi), moonDist - sunDist * cos(phi))
        val geocentric = horizontal(m[0], m[1], place, epochMillis)
        val parallax = asin(6378.14 / moonDist * cos(geocentric.altitude * RAD)) * DEG
        return MoonState(SkyPoint(geocentric.azimuth, geocentric.altitude - parallax), (1 + cos(inc)) / 2)
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

    /** The moment in [samples] where [altitude] passes [level] going up ([rising]) or down; refined to a fraction of a second. */
    private fun crossing(samples: List<Long>, altitude: (Long) -> Double, level: Double, rising: Boolean): Long? {
        for (i in 0 until samples.size - 1) {
            val a = altitude(samples[i]) - level
            val b = altitude(samples[i + 1]) - level
            val hit = if (rising) a < 0 && b >= 0 else a >= 0 && b < 0
            if (hit) {
                var lo = samples[i]; var hi = samples[i + 1]
                repeat(24) {
                    val mid = (lo + hi) / 2
                    val below = altitude(mid) - level < 0
                    if (below == rising) lo = mid else hi = mid
                }
                return (lo + hi) / 2
            }
        }
        return null
    }

    private fun wholeDay(zone: ZoneId, date: LocalDate): List<Long> =
        daySamples(zone, date, 5) + date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Sunrise, sunset and the twilights of [date] at [place]. Rise and set mean the sun's top edge on
     * the horizon (−0.833°), seen from [aboveGround] metres up (a drone or a roof sees it a little sooner).
     */
    fun sunTimes(place: Place, zone: ZoneId, date: LocalDate, aboveGround: Double = 0.0): SunTimes {
        val samples = wholeDay(zone, date)
        val alt = { t: Long -> sun(place, t).altitude }
        val edge = -0.833 - Terrain.horizonDip(aboveGround)
        return SunTimes(
            crossing(samples, alt, edge, true), crossing(samples, alt, edge, false),
            crossing(samples, alt, -6.0, true), crossing(samples, alt, -6.0, false),
            crossing(samples, alt, -18.0, true), crossing(samples, alt, -18.0, false),
        )
    }

    /** When the moon rises and sets on a local day (null when it does not, which happens about once a month each). */
    data class RiseSet(val rise: Long?, val set: Long?)

    /** Moonrise and moonset of [date] at [place]: the moon's top edge on the horizon, as seen from the ground. */
    fun moonTimes(place: Place, zone: ZoneId, date: LocalDate, aboveGround: Double = 0.0): RiseSet {
        val samples = wholeDay(zone, date)
        val alt = { t: Long -> moon(place, t).position.altitude }
        val edge = -0.833 - Terrain.horizonDip(aboveGround)
        return RiseSet(crossing(samples, alt, edge, true), crossing(samples, alt, edge, false))
    }

    /**
     * Sunrise and sunset over the land: when the sun's top edge clears the [skyline] (hills,
     * mountains) and when it goes behind it, as seen through the air (refraction lifts it a little).
     */
    fun sunOverSkyline(place: Place, zone: ZoneId, date: LocalDate, skyline: HorizonProfile): RiseSet =
        overSkyline(wholeDay(zone, date), skyline, 0.27) { sun(place, it) }

    /** Moonrise and moonset over the land, as [sunOverSkyline]. */
    fun moonOverSkyline(place: Place, zone: ZoneId, date: LocalDate, skyline: HorizonProfile): RiseSet =
        overSkyline(wholeDay(zone, date), skyline, 0.26) { moon(place, it).position }

    private fun overSkyline(samples: List<Long>, skyline: HorizonProfile, semiDiameter: Double, at: (Long) -> SkyPoint): RiseSet {
        val clear = { t: Long -> at(t).let { p -> p.altitude + Geo.refraction(p.altitude) + semiDiameter - skyline.at(p.azimuth) } }
        return RiseSet(crossing(samples, clear, 0.0, true), crossing(samples, clear, 0.0, false))
    }

    /** A stretch of time, in milliseconds since 1970. */
    data class Interval(val start: Long, val end: Long)

    /** The stretches of [samples] (5 minutes apart) where [inside] holds. */
    private fun stretches(samples: List<Long>, inside: (Long) -> Boolean): List<Interval> {
        val out = ArrayList<Interval>()
        var start: Long? = null
        for (t in samples) {
            val now = inside(t)
            if (now && start == null) start = t
            if (!now && start != null) { out += Interval(start, t); start = null }
        }
        if (start != null) out += Interval(start, samples.last())
        return out
    }

    /** The golden hours of a local day: the sun between 4° below and 6° above the horizon (warm, low light), morning and evening. */
    fun goldenHours(place: Place, zone: ZoneId, date: LocalDate): List<Interval> =
        stretches(wholeDay(zone, date)) { t -> sun(place, t).altitude.let { it > -4.0 && it < 6.0 } }

    /** The blue hours of a local day: the sun between 4° and 8° below the horizon. */
    fun blueHours(place: Place, zone: ZoneId, date: LocalDate): List<Interval> =
        stretches(wholeDay(zone, date)) { t -> sun(place, t).altitude.let { it > -8.0 && it <= -4.0 } }

    /**
     * When the centre of the Milky Way can be photographed in the night after [date] (noon to noon):
     * [dark] where it is at least [minAltitude] up and the sky is fully dark (sun 18° down); [moonless]
     * the part of that where the moon is also down or a thin crescent (under a quarter lit).
     */
    data class MilkyWayNight(val dark: List<Interval>, val moonless: List<Interval>)

    fun milkyWayNight(place: Place, zone: ZoneId, date: LocalDate, minAltitude: Double = 5.0): MilkyWayNight {
        val noon = date.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L
        val samples = (0..288).map { noon + it * 5 * 60_000L }
        fun dark(t: Long) = sun(place, t).altitude <= -18.0 && galacticCentre(place, t).altitude >= minAltitude
        fun moonless(t: Long) = dark(t) && moon(place, t).let { it.position.altitude < 0.0 || it.lit < 0.25 }
        return MilkyWayNight(stretches(samples, ::dark), stretches(samples, ::moonless))
    }
    /** One night's chance for the Milky Way: how long its centre is up in a dark, moonless sky, and how high it climbs then. */
    data class MilkyWayScore(val date: LocalDate, val moonless: List<Interval>, val moonlessMinutes: Int, val darkMinutes: Int, val bestAltitude: Double)

    /** The nights from [from] for [days] days, best first: the longest moonless window with the centre up. */
    fun milkyWaySeason(place: Place, zone: ZoneId, from: LocalDate, days: Int, minAltitude: Double = 5.0): List<MilkyWayScore> =
        (0 until days).map { i ->
            val date = from.plusDays(i.toLong())
            val n = milkyWayNight(place, zone, date, minAltitude)
            val minutes = { list: List<Interval> -> list.sumOf { ((it.end - it.start) / 60_000L).toInt() } }
            val best = n.moonless.maxOfOrNull { iv ->
                var top = -90.0
                var t = iv.start
                while (t <= iv.end) { top = maxOf(top, galacticCentre(place, t).altitude); t += 15 * 60_000L }
                top
            } ?: 0.0
            MilkyWayScore(date, n.moonless, minutes(n.moonless), minutes(n.dark), best)
        }.filter { it.moonlessMinutes > 0 }.sortedWith(compareByDescending<MilkyWayScore> { it.moonlessMinutes }.thenByDescending { it.bestAltitude })


    /** The instant [epochMillis] as a local date in [zone]. */
    fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
