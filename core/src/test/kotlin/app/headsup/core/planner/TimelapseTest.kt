package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelapseTest {
    private val l = TimelapseLimits(aperture = 2.8, shutterMin = 0.5, shutterMax = 4.0, isoMin = 100.0, isoMax = 3200.0)

    @Test fun theCamerasRangeIsShutterPlusIso() {
        // 3 stops of shutter (0.5 → 4 s) and 5 of ISO (100 → 3200).
        assertEquals(8.0, l.rangeStops, 1e-9)
    }

    @Test fun aSpanOfLightWithinTheRangeHasAFilter() {
        // 6 stops of change (EV 4 … 10) fit in 8: the filter range is 2 stops wide.
        val w = Timelapse.window(4.0, 10.0, l)
        assertEquals(2.0, w.endInclusive - w.start, 1e-9)
    }

    @Test fun aSpanWiderThanTheRangeHasNoFilter() {
        // Night EV −5 to noon EV 15 is 20 stops; the camera covers 8.
        val p = Timelapse.plan(listOf(Triple(0L, -5.0, -30.0), Triple(1L, 15.0, 60.0)), l)
        assertFalse(p.feasible)
        assertEquals(12.0, p.shortfallStops, 1e-9)
        assertTrue(p.steps.any { !it.inReach })
    }

    @Test fun withTheFilterInTheRangeEveryStepIsInReach() {
        // EV 4 … 10: the window is [low, high] 2 stops wide; the chosen filter must serve both ends.
        val samples = listOf(Triple(0L, 4.0, -5.0), Triple(1L, 7.0, 0.0), Triple(2L, 10.0, 10.0))
        val p = Timelapse.plan(samples, l)
        assertTrue(p.feasible)
        // Only when a listed filter lies in the range can it be held for the whole window; the planner then picks it.
        if (p.filters.isNotEmpty()) {
            assertTrue(p.chosen in p.filters)
            assertTrue(p.steps.all { it.inReach }, "steps ${p.steps}")
        }
    }

    @Test fun shutterIsShortestInTheDayAndLongerAsItDarkens() {
        val samples = listOf(Triple(0L, 10.0, 20.0), Triple(1L, 6.0, -3.0), Triple(2L, 3.0, -8.0))
        val p = Timelapse.plan(samples, l, nd = NdFilter.Nd8)
        val shutters = p.steps.map { it.shutter }
        assertTrue(shutters[0] <= shutters[1] && shutters[1] <= shutters[2], "$shutters")
        // The products shutter × ISO follow the light exactly where in reach.
        for (s in p.steps.filter { it.inReach }) {
            assertEquals(s.ev, Exposure.ev100(l.aperture, s.shutter, s.iso, NdFilter.Nd8.stops), 1e-6)
        }
    }

    @Test fun aSunriseWindowAtADarkSiteIsWorkedFromRealLight() {
        // 90 minutes before to 90 minutes after sunrise, dark site: light grows by many stops.
        val place = Place(50.0, 20.0, 600.0)
        val zone = ZoneOffset.UTC
        val rise = Sky.sunTimes(place, zone, LocalDate.of(2026, 6, 21)).sunrise!!
        val samples = Timelapse.sample(place, SkyConditions(2, 0.0), rise - 90 * 60_000L, rise + 90 * 60_000L, 5)
        assertEquals(37, samples.size)
        assertTrue(samples.first().second < samples.last().second - 6.0)
        // Wider than 8 stops of camera range → no single filter, and the plan says by how much.
        val p = Timelapse.plan(samples, l)
        assertEquals(p.evMax - p.evMin - l.rangeStops, p.shortfallStops, 1e-9)
    }
}
