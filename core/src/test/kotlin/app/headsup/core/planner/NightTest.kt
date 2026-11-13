package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NightTest {
    private val krakow = Place(50.06, 19.94)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test fun theMoonRisesInTheEastAndSetsInTheWestOnMostDays() {
        var rises = 0
        for (d in 0 until 30) {
            val date = LocalDate.of(2026, 6, 1).plusDays(d.toLong())
            val t = Sky.moonTimes(krakow, zone, date)
            t.rise?.let { r ->
                rises++
                val p = Sky.moon(krakow, r).position
                assertTrue(abs(p.altitude + 0.833) < 0.05, "altitude at rise ${p.altitude}")
                assertTrue(p.azimuth < 180.0, "azimuth at rise ${p.azimuth}")
            }
            t.set?.let { s -> assertTrue(Sky.moon(krakow, s).position.azimuth > 180.0) }
        }
        // About one day a month it does not rise within the local day.
        assertTrue(rises >= 27, "rises $rises")
    }

    @Test fun theMoonStandsLowerFromTheGroundThanFromTheEarthsCentreNearTheHorizon() {
        // Parallax: at the horizon the moon is about 0.9–1° lower than the centre of the Earth would see it.
        val date = LocalDate.of(2026, 6, 10)
        val rise = Sky.moonTimes(krakow, zone, date).rise ?: return
        val seen = Sky.moon(krakow, rise).position.altitude
        assertTrue(seen < 0.0)
    }

    @Test fun goldenHoursComeAroundSunriseAndSunset() {
        val date = LocalDate.of(2026, 3, 20)
        val g = Sky.goldenHours(krakow, zone, date)
        assertEquals(2, g.size)
        val t = Sky.sunTimes(krakow, zone, date)
        assertTrue(g[0].start < t.sunrise!! && t.sunrise!! < g[0].end)
        assertTrue(g[1].start < t.sunset!! && t.sunset!! < g[1].end)
        val b = Sky.blueHours(krakow, zone, date)
        assertEquals(2, b.size)
        assertTrue(b[0].end <= g[0].start + 5 * 60_000L)
    }

    @Test fun theMilkyWaysCentreIsUpOnSummerNightsAndNotInWinter() {
        // 2026-06-15 is a new moon: the dark window is also moonless.
        val summer = Sky.milkyWayNight(krakow, zone, LocalDate.of(2026, 6, 15), minAltitude = 5.0)
        // At 50°N in June the sky is barely fully dark; in August it is.
        val august = Sky.milkyWayNight(krakow, zone, LocalDate.of(2026, 8, 12), minAltitude = 5.0)
        assertTrue(august.dark.isNotEmpty(), "august ${august.dark}")
        assertTrue(august.moonless.isNotEmpty(), "august moonless ${august.moonless}")
        for (iv in august.dark) {
            val mid = (iv.start + iv.end) / 2
            assertTrue(Sky.galacticCentre(krakow, mid).altitude >= 5.0)
            assertTrue(Sky.sun(krakow, mid).altitude <= -18.0)
        }
        val winter = Sky.milkyWayNight(krakow, zone, LocalDate.of(2026, 12, 15), minAltitude = 5.0)
        assertTrue(winter.dark.isEmpty(), "winter ${winter.dark}")
        assertTrue(summer.moonless.size <= summer.dark.size)
    }
}

class MoonPhaseAndSeasonTest {
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun phasesFallOnTheirKnownTimes() {
        // 2026: new moon 16 May 20:01 UTC, full moon 31 May 08:45 UTC, first quarter 23 May 11:11 UTC.
        val p = Sky.moonPhases(utc(2026, 5, 10, 0, 0), 25)
        val new = p.first { it.name == "New moon" }
        val full = p.first { it.name == "Full moon" }
        val first = p.first { it.name == "First quarter" }
        assertTrue(abs(new.millis - utc(2026, 5, 16, 20, 1)) < 45 * 60_000L, "new off by ${(new.millis - utc(2026, 5, 16, 20, 1)) / 60_000} min")
        assertTrue(abs(full.millis - utc(2026, 5, 31, 8, 45)) < 45 * 60_000L, "full off by ${(full.millis - utc(2026, 5, 31, 8, 45)) / 60_000} min")
        assertTrue(abs(first.millis - utc(2026, 5, 23, 11, 11)) < 45 * 60_000L, "quarter off by ${(first.millis - utc(2026, 5, 23, 11, 11)) / 60_000} min")
    }

    @Test fun theBestMilkyWayNightsOfASummerAreAroundTheNewMoon() {
        val season = Sky.milkyWaySeason(Place(50.06, 19.94), ZoneId.of("Europe/Warsaw"), LocalDate.of(2026, 8, 1), 30)
        assertTrue(season.isNotEmpty())
        val best = season.first()
        // Its window is truly moonless and dark with the centre up, and it is far from the full moon (28 August).
        val place = Place(50.06, 19.94)
        for (iv in best.moonless) {
            val mid = (iv.start + iv.end) / 2
            assertTrue(Sky.galacticCentre(place, mid).altitude >= 5.0)
            assertTrue(Sky.sun(place, mid).altitude <= -18.0)
            Sky.moon(place, mid).let { assertTrue(it.position.altitude < 0.0 || it.lit < 0.25) }
        }
        assertTrue(abs(best.date.toEpochDay() - LocalDate.of(2026, 8, 28).toEpochDay()) > 5, "best ${best.date}")
        assertTrue(best.moonlessMinutes >= 30 && best.bestAltitude >= 5.0, "best $best")
        // Best first.
        for (i in 0 until season.size - 1) assertTrue(season[i].moonlessMinutes >= season[i + 1].moonlessMinutes)
    }

    @Test fun aimingAtTheSunFindsTheCompassAndTiltError() {
        // The phone thinks it looks at 100° magnetic, 10° up; declination 5°; the sun truly stands at 108°, 12° up.
        val (compass, tilt) = Ar.alignment(SkyPoint(100.0, 10.0), 5.0, SkyPoint(108.0, 12.0))
        assertEquals(3.0, compass, 1e-9)
        assertEquals(2.0, tilt, 1e-9)
        // Across north it takes the short way round.
        assertEquals(-4.0, Ar.alignment(SkyPoint(2.0, 0.0), 0.0, SkyPoint(358.0, 0.0)).first, 1e-9)
    }
}
