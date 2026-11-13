package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoHorizonTest {
    private val krakow = 50.0614 to 19.9366
    private val warsaw = 52.2297 to 21.0122

    @Test fun distanceAndDirectionBetweenCities() {
        val d = Geo.distance(krakow.first, krakow.second, warsaw.first, warsaw.second)
        assertTrue(abs(d - 252_000) < 3_000, "distance $d")
        val b = Geo.bearing(krakow.first, krakow.second, warsaw.first, warsaw.second)
        assertTrue(b in 14.0..20.0, "bearing $b")
    }

    @Test fun goingSomeWayAndBackLandsWhereItStarted() {
        val (la, lo) = Geo.destination(krakow.first, krakow.second, 123.0, 45_000.0)
        assertEquals(45_000.0, Geo.distance(krakow.first, krakow.second, la, lo), 1.0)
        assertEquals(123.0, Geo.bearing(krakow.first, krakow.second, la, lo), 0.01)
    }

    @Test fun aFarPointLooksLowerThanLevelBecauseOfTheCurve() {
        // 1000 m higher at 10 km: almost atan(0.1) = 5.71°; a level point 50 km away sits below level.
        assertEquals(Math.toDegrees(atan(0.1)), Geo.elevationAngle(10_000.0, 1000.0), 0.05)
        assertTrue(Geo.elevationAngle(50_000.0, 0.0) < -0.1)
    }

    @Test fun refractionLiftsMostAtTheHorizon() {
        // For the true height the sun's top edge has at sunrise (−0.57°) it is about 0.57°; at 0° true height 0.48°.
        assertEquals(0.57, Geo.refraction(-0.567), 0.02)
        assertEquals(0.48, Geo.refraction(0.0), 0.02)
        assertTrue(Geo.refraction(30.0) < 0.05)
    }

    @Test fun theSkylineShowsAMountainWhereItStands() {
        // Flat land at 0 m with one mountain 1 km high, 1.5 km wide, 10 km east of the eye.
        val (mLat, mLon) = Geo.destination(krakow.first, krakow.second, 90.0, 10_000.0)
        val terrain = { la: Double, lo: Double, _: Int -> if (Geo.distance(la, lo, mLat, mLon) < 750.0) 1000.0 else 0.0 }
        val sky = Horizon.profile(krakow.first, krakow.second, 2.0, terrain, steps = 72)
        // Its near edge is 9.25 km away: atan(998 / 9250) = 6.16°, lowered a little by the Earth's curve.
        assertTrue(abs(sky.at(90.0) - 6.1) < 0.2, "east ${sky.at(90.0)}")
        assertTrue(sky.at(270.0) < 0.05, "west ${sky.at(270.0)}")
    }

    @Test fun theSunRisesLaterOverAHillThanOverAFlatHorizon() {
        val place = Place(krakow.first, krakow.second)
        val zone = ZoneId.of("Europe/Warsaw")
        val day = LocalDate.of(2026, 3, 20)
        val flat = Sky.sunOverSkyline(place, zone, day, HorizonProfile.flat())
        val plain = Sky.sunTimes(place, zone, day)
        // Over a flat skyline it agrees with the usual sunrise to about a minute.
        assertTrue(abs(flat.rise!! - plain.sunrise!!) < 90_000L, "${(flat.rise!! - plain.sunrise!!) / 1000} s")
        val hills = Sky.sunOverSkyline(place, zone, day, HorizonProfile(DoubleArray(360) { 5.0 }))
        val minutes = (hills.rise!! - flat.rise!!) / 60_000.0
        // 5° at the equinox at 50°N: the sun climbs about 0.64° a minute at first, so roughly half an hour later.
        assertTrue(minutes in 25.0..45.0, "later by $minutes min")
        assertTrue(hills.set!! < flat.set!!)
    }

    @Test fun findsTheSunInTheEastAtTheHorizonAroundTheEquinoxes() {
        val place = Place(krakow.first, krakow.second)
        val found = Alignment.find(place, ZoneOffset.ofHours(1), LocalDate.of(2026, 1, 1), 365, Body.Sun, 90.0, 0.0, 1.0)
        assertTrue(found.isNotEmpty())
        for (m in found) {
            assertTrue(abs(Sky.sun(place, m.millis).azimuth - 90.0) < 0.05)
            assertTrue(abs(m.altitude) <= 1.0)
        }
        val months = found.map { java.time.Instant.ofEpochMilli(it.millis).atZone(ZoneOffset.UTC).monthValue }.toSet()
        // The sun rises due east only around the equinoxes.
        assertTrue(months.all { it in setOf(3, 4, 9, 10) }, "months $months")
    }

    @Test fun theMilkyWaysCentreIsFoundOnlyInTheDark() {
        // At 50°N around midsummer the sky never gets fully dark; at 30°N it does, with the centre due south late in the evening.
        val dark = Alignment.find(Place(30.0, -100.0), ZoneOffset.ofHours(-6), LocalDate.of(2026, 7, 1), 60, Body.GalacticCentre, 180.0, null)
        assertTrue(dark.isNotEmpty())
        assertTrue(dark.all { it.sunAltitude <= -18.0 })
        val place = Place(krakow.first, krakow.second)
        val found = Alignment.find(place, ZoneOffset.ofHours(2), LocalDate.of(2026, 6, 10), 20, Body.GalacticCentre, 180.0, null)
        assertTrue(found.isEmpty(), "found ${found.size} in a sky that is never fully dark")
    }
}
