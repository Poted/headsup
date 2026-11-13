package app.headsup.core.planner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExposureTest {
    @Test fun sunnySixteenIsAboutEv15() {
        assertTrue(abs(Exposure.ev100(16.0, 1.0 / 100, 100.0) - 14.64) < 0.05)
    }

    @Test fun isoFollowsTheLightWhenTheOthersAreHeld() {
        // EV 10 at f/2.8, 1/2 s, no filter: ISO 100·7.84·2/(1·1024)... worked by hand = 100 · 7.84 / (0.5 · 1024) ≈ 1.5 → below the camera's lowest.
        val s = ExposureSetup(AutoSetting.Iso, 2.8, 0.5, nd = NdFilter.None)
        val r = Exposure.solve(10.0, s)
        assertEquals(ExposureWarning.IsoTooLow, r.warning)
        assertEquals(100.0, r.iso)
        assertEquals(NdFilter.Nd1000, r.suggestedNd)
    }

    @Test fun darkNightAsksForAHighIso() {
        val s = ExposureSetup(AutoSetting.Iso, 2.8, 15.0, nd = NdFilter.None)
        val r = Exposure.solve(-6.0, s)
        // 100 · 7.84 / (15 · 2^-6) = 3345.
        assertTrue(abs(r.wantedIso - 3344.6) < 5, "iso ${r.wantedIso}")
        assertNull(r.warning)
    }

    @Test fun isoAboveTheSensorGivesAWarning() {
        val s = ExposureSetup(AutoSetting.Iso, 2.8, 1.0 / 30, nd = NdFilter.None, limits = CameraLimits(100.0, 25_600.0))
        val r = Exposure.solve(-4.0, s)
        assertEquals(ExposureWarning.IsoTooHigh, r.warning)
        assertEquals(25_600.0, r.iso)
        assertTrue(r.wantedIso > 25_600.0)
    }

    @Test fun aFilterRaisesTheIsoByItsStops() {
        val a = Exposure.isoFor(8.0, 2.8, 0.5, 0.0)
        val b = Exposure.isoFor(8.0, 2.8, 0.5, 4.0)
        assertEquals(16.0, b / a, 1e-9)
    }

    @Test fun shutterAndApertureInvertTheTriangle() {
        val ev = 12.0
        val t = Exposure.shutterFor(ev, 4.0, 200.0, 3.0)
        assertEquals(ev, Exposure.ev100(4.0, t, 200.0, 3.0), 1e-9)
        val n = Exposure.apertureFor(ev, 1.0 / 60, 400.0, 0.0)
        assertEquals(ev, Exposure.ev100(n, 1.0 / 60, 400.0, 0.0), 1e-9)
    }

    @Test fun solvedExposureReproducesTheScene() {
        val s = ExposureSetup(AutoSetting.Shutter, 5.6, iso = 100.0, nd = NdFilter.Nd8)
        val r = Exposure.solve(13.0, s)
        assertEquals(13.0, Exposure.ev100(r.aperture, r.shutter, r.iso, r.nd.stops), 1e-9)
    }

    @Test fun recommendedFilterIsTheWeakestThatKeepsIsoInReach() {
        val s = ExposureSetup(AutoSetting.Iso, 2.8, 0.5, nd = null)
        val r = Exposure.solve(12.0, s) // needs about 0.4 ISO without a filter
        val needed = Exposure.isoFor(12.0, 2.8, 0.5, r.nd.stops)
        assertTrue(needed >= 100.0 / 1.02, "iso $needed")
        val weaker = NdFilter.entries.lastOrNull { it.stops < r.nd.stops }
        if (weaker != null) assertTrue(Exposure.isoFor(12.0, 2.8, 0.5, weaker.stops) < 100.0)
        assertNotNull(r.nd)
    }

    @Test fun printedValues() {
        assertEquals("1/2s", Exposure.formatShutter(0.5))
        assertEquals("1/250s", Exposure.formatShutter(1.0 / 250))
        assertEquals("30s", Exposure.formatShutter(30.0))
        assertEquals("4s", Exposure.formatShutter(4.0))
        assertEquals("f/2.8", Exposure.formatAperture(2.8))
        assertEquals("f/11", Exposure.formatAperture(11.0))
        assertEquals(3200, Exposure.snapIso(3345.0))
        assertEquals(400, Exposure.snapIso(380.0))
    }

    @Test fun ndWindowForOneSecondAndIsoOneHundredToThirtyTwoHundred() {
        // EV 10 at f/2.8: the bare ISO is 100·7.84/(1·1024) = 0.77, so ND must be log2(100/0.77) = 7.0 to 12.0 stops.
        val w = Exposure.ndWindow(10.0, 2.8, 1.0, CameraLimits(100.0, 3200.0))
        assertEquals(7.0, w.start, 0.05)
        assertEquals(12.0, w.endInclusive, 0.05)
        assertEquals(listOf(NdFilter.Nd1000), Exposure.filtersIn(w))
        // At that filter the ISO sits inside the range.
        val iso = Exposure.isoFor(10.0, 2.8, 1.0, NdFilter.Nd1000.stops)
        assertTrue(iso in 100.0..3200.0, "iso $iso")
    }

    @Test fun ndWindowIsNegativeWhenTheLightIsTooDimEvenBare() {
        val w = Exposure.ndWindow(-5.0, 2.8, 1.0, CameraLimits(100.0, 3200.0))
        assertTrue(w.endInclusive < 0.0)
        assertEquals(emptyList(), Exposure.filtersIn(w))
    }
}
