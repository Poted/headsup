package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkyTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun sunAtSolarNoonOnEquinoxStandsHighOverTheEquator() {
        // 2026-03-20, noon UTC at longitude 0: the sun is within a degree or two of the zenith at the equator.
        val p = Sky.sun(Place(0.0, 0.0), ms(2026, 3, 20, 12, 0, "UTC"))
        assertTrue(p.altitude > 85.0, "altitude ${p.altitude}")
    }

    @Test fun sunInWarsawAtMidsummerNoonIsAbout61Degrees() {
        // 2026-06-21, around 10:34 UTC the sun crosses the meridian at 21°E: 90 − 52.2 + 23.44 ≈ 61.2°, due south.
        val p = Sky.sun(Place(52.23, 21.0), ms(2026, 6, 21, 10, 34, "UTC"))
        assertTrue(abs(p.altitude - 61.2) < 0.8, "altitude ${p.altitude}")
        assertTrue(abs(p.azimuth - 180.0) < 6.0, "azimuth ${p.azimuth}")
    }

    @Test fun sunRisesInTheEastAndSetsInTheWest() {
        val zone = ZoneId.of("Europe/Warsaw")
        val place = Place(52.23, 21.01)
        val t = Sky.sunTimes(place, zone, LocalDate.of(2026, 3, 20))
        val rise = assertNotNull(t.sunrise); val set = assertNotNull(t.sunset)
        assertTrue(Sky.sun(place, rise + 120_000).azimuth in 80.0..100.0)
        assertTrue(Sky.sun(place, set - 120_000).azimuth in 260.0..280.0)
        // Equinox day in Warsaw is a little over 12 hours long.
        val hours = (set - rise) / 3_600_000.0
        assertTrue(hours in 12.0..12.3, "day $hours h")
        // About 06:00 and 18:15 local: rise is before 7.
        val localRise = java.time.Instant.ofEpochMilli(rise).atZone(zone).hour
        assertTrue(localRise in 5..6, "rise hour $localRise")
    }

    @Test fun midnightSunAboveTheArcticCircleHasNoSunset() {
        val t = Sky.sunTimes(Place(70.0, 25.0), ZoneId.of("Europe/Oslo"), LocalDate.of(2026, 6, 21))
        assertEquals(null, t.sunset)
        assertEquals(null, t.sunrise)
    }

    @Test fun galacticPlanePassesThroughTheGalacticCentre() {
        val p = Sky.galacticPlane(0.0)
        assertTrue(abs(p[0] - 266.4) < 0.5, "ra ${p[0]}")
        assertTrue(abs(p[1] - -28.94) < 0.5, "dec ${p[1]}")
    }

    @Test fun galacticPlaneIsAGreatCircleTiltedBySixtyDegrees() {
        // The plane's highest declination is 90 − 62.87 = 27.13 (the pole's complement), its lowest the negative of it.
        val decs = (0 until 360).map { Sky.galacticPlane(it.toDouble())[1] }
        assertTrue(abs(decs.max() - 62.87) < 0.3, "max ${decs.max()}")
        assertTrue(abs(decs.min() + 62.87) < 0.3, "min ${decs.min()}")
    }

    @Test fun galacticCentreIsHighInSummerNightsOfTheNorthAndLowInWinter() {
        val place = Place(50.0, 20.0)
        val summer = (0 until 24).maxOf { Sky.galacticCentre(place, ms(2026, 7, 1, it, 0, "UTC")).altitude }
        val winter = (0 until 24).maxOf { Sky.galacticCentre(place, ms(2026, 1, 1, it, 0, "UTC")).altitude }
        // Its declination is −29°, so at 50°N it never climbs past about 11°.
        assertTrue(summer in 9.0..12.5, "summer $summer")
        assertTrue(winter in 9.0..12.5, "winter $winter")
    }

    @Test fun moonIsFullOnAKnownFullMoonAndNewOnANewMoon() {
        // Full moon 2026-05-31 08:45 UTC; new moon 2026-05-16 20:01 UTC.
        val place = Place(0.0, 0.0)
        assertTrue(Sky.moon(place, ms(2026, 5, 31, 8, 45, "UTC")).lit > 0.97)
        assertTrue(Sky.moon(place, ms(2026, 5, 16, 20, 1, "UTC")).lit < 0.03)
    }

    @Test fun moonStaysWithinItsOwnRangeOfLitness() {
        val place = Place(10.0, 10.0)
        for (d in 0 until 40) {
            val l = Sky.moon(place, ms(2026, 6, 1, 0, 0, "UTC") + d * 86_400_000L).lit
            assertTrue(l in 0.0..1.0)
        }
    }
}
