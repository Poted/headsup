package app.headsup.core.planner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeterTest {
    private val osmo = CameraProfile("Osmo", isoMin = 100.0, isoMax = 6400.0, shutterMin = 1.0 / 8000, shutterMax = 30.0, apertureMin = 2.8, apertureMax = 2.8,
        filters = listOf(NdFilter.Nd8, NdFilter.Nd16, NdFilter.Nd32, NdFilter.Nd64))

    @Test fun theOsmoPhotoReadsAsItsCameraSetItself() {
        // 14 Nov 2026, 15:56, Kraków: f/2.8, 1/120 s, the camera chose ISO 309 → the scene was EV 8.25.
        val scene = Meter.settingsEv(2.8, 1.0 / 120, 309.0, NdFilter.None)
        assertEquals(8.25, scene, 0.01)
        // With ISO on Auto the meter lands on the same ISO, exposed right.
        val r = Meter.read(scene, MeterSettings(2.8, 1.0 / 120, null, NdFilter.None), osmo)
        assertEquals(309.0, r.iso, 1.0)
        assertEquals(0.0, r.offset, 1e-9)
    }

    @Test fun everythingHeldGivesTheOffsetInStops() {
        // Noon EV 15, f/2.8, 1/125, ISO 100, no filter: log2(7.84 · 125) = 9.94, so 5.1 stops too bright.
        val r = Meter.read(15.0, MeterSettings(2.8, 1.0 / 125, 100.0, NdFilter.None), CameraProfile.ANY)
        assertEquals(15.0 - Meter.settingsEv(2.8, 1.0 / 125, 100.0, NdFilter.None), r.offset, 1e-9)
        assertTrue(r.offset > 5.0)
        // ND32 takes 5 stops of it away.
        val withNd = Meter.read(15.0, MeterSettings(2.8, 1.0 / 125, 100.0, NdFilter.Nd32), CameraProfile.ANY)
        assertEquals(r.offset - 5.0, withNd.offset, 1e-9)
    }

    @Test fun autoIsoStopsAtTheCamerasLimitsAndTheRestIsTheOffset() {
        // At night ISO wants more than 6400: it stays at 6400 and the picture is too dark by the rest.
        val r = Meter.read(-3.0, MeterSettings(2.8, 1.0 / 50, null, NdFilter.None), osmo)
        assertEquals(6400.0, r.iso, 1e-9)
        assertTrue(r.offset < -5.0)
        // In bright light it stays at 100 and the picture is too bright.
        val day = Meter.read(15.0, MeterSettings(2.8, 1.0 / 50, null, NdFilter.None), osmo)
        assertEquals(100.0, day.iso, 1e-9)
        assertTrue(day.offset > 0.0)
    }

    @Test fun autoShutterMovesTheShutterFirstAtTheLowestIso() {
        val r = Meter.read(12.0, MeterSettings(2.8, null, null, NdFilter.Nd8), osmo)
        assertEquals(100.0, r.iso, 1e-9)
        assertEquals(0.0, r.offset, 1e-9)
        assertEquals(12.0, Meter.settingsEv(2.8, r.shutter, r.iso, NdFilter.Nd8), 1e-9)
    }

    @Test fun theApertureStaysInTheLenssRange() {
        // A fixed f/2.8 is f/2.8 whatever is set; a range f/2–f/4 holds a setting inside it.
        assertEquals(2.8, Meter.read(10.0, MeterSettings(8.0, 1.0 / 100, 100.0, NdFilter.None), osmo).aperture, 1e-9)
        assertEquals(2.8, osmo.fixedAperture!!, 1e-9)
        val zoom = CameraProfile("Zoom", apertureMin = 2.0, apertureMax = 4.0)
        assertEquals(null, zoom.fixedAperture)
        assertEquals(4.0, Meter.read(10.0, MeterSettings(8.0, 1.0 / 100, 100.0, NdFilter.None), zoom).aperture, 1e-9)
        assertEquals(2.8, Meter.read(10.0, MeterSettings(2.8, 1.0 / 100, 100.0, NdFilter.None), zoom).aperture, 1e-9)
    }

    @Test fun theProfileOffersNoneAndItsOwnFiltersOnly() {
        assertEquals(listOf(NdFilter.None, NdFilter.Nd8, NdFilter.Nd16, NdFilter.Nd32, NdFilter.Nd64), osmo.choices)
    }

    @Test fun offsetsReadAsAMeterShowsThem() {
        assertEquals("EV +1.7", Meter.formatOffset(1.68))
        assertEquals("EV −1.7", Meter.formatOffset(-1.72))
        assertEquals("EV 0.0", Meter.formatOffset(0.02))
        assertEquals("EV 0.0", Meter.formatOffset(-0.04))
    }

    @Test fun theLightOfAMomentIsTheFramesLight() {
        val place = Place(50.06, 19.94)
        val t = java.time.ZonedDateTime.of(2026, 6, 21, 10, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(abs(Planner.ev(place, t, SkyConditions()) - Planner.frame(place, t, SkyConditions(), ExposureSetup()).ev) < 1e-9)
    }
}
