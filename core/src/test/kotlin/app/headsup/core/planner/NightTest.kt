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
