package app.headsup.core.planner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceInputTest {
    private fun near(p: ParsedPlace?, lat: Double, lon: Double, tol: Double = 1e-4) {
        assertNotNull(p)
        assertTrue(abs(p.lat - lat) < tol && abs(p.lon - lon) < tol, "got ${p.lat}, ${p.lon}")
    }

    @Test fun decimalCoordinatesAsGoogleShowsThem() {
        near(PlaceInput.parse("49.2318, 19.9817", 0.0, 0.0), 49.2318, 19.9817)
        near(PlaceInput.parse("49,2318 19,9817", 0.0, 0.0), 49.2318, 19.9817)
        near(PlaceInput.parse("49.2318N 19.9817E", 0.0, 0.0), 49.2318, 19.9817)
        near(PlaceInput.parse("-33.8568, 151.2153", 0.0, 0.0), -33.8568, 151.2153)
    }

    @Test fun degreesMinutesSeconds() {
        near(PlaceInput.parse("49°13'54.5\"N 19°58'54.1\"E", 0.0, 0.0), 49.231806, 19.981694)
        near(PlaceInput.parse("33°51'24\"S 151°12'55\"E", 0.0, 0.0), -33.856667, 151.215278)
    }

    @Test fun googleMapsLinks() {
        near(PlaceInput.parse("https://www.google.com/maps/place/Kasprowy/@49.2318,19.9817,15z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d49.23185!4d19.98174", 0.0, 0.0), 49.23185, 19.98174)
        near(PlaceInput.parse("https://www.google.com/maps/@49.2318,19.9817,14z", 0.0, 0.0), 49.2318, 19.9817)
        near(PlaceInput.parse("https://maps.google.com/?q=49.2318,19.9817", 0.0, 0.0), 49.2318, 19.9817)
    }

    @Test fun plusCodesFullAndShort() {
        // Encoding and decoding agree to the code's cell (10 digits: about 14 m).
        val code = PlusCode.encode(49.2318, 19.9817)
        assertEquals(11, code.length)
        near(PlaceInput.parse(code, 0.0, 0.0), 49.2318, 19.9817, 2e-4)
        // A short code (as Google Maps shows: "X3QR+5F Zakopane") completed from a place nearby.
        val short = code.substring(4)
        near(PlaceInput.parse("$short Zakopane", 49.30, 19.95), 49.2318, 19.9817, 2e-4)
        // From a place in the next cell over it still finds the nearest match.
        near(PlaceInput.parse(short, 49.6, 20.4), 49.2318, 19.9817, 2e-4)
        // Known code: the Google office in Zurich is 8FVC9G8F+6X.
        val zurich = assertNotNull(PlusCode.decode("8FVC9G8F+6X"))
        assertTrue(abs(zurich.first - 47.365) < 0.001 && abs(zurich.second - 8.525) < 0.001, "$zurich")
    }

    @Test fun nonsenseIsNothing() {
        assertNull(PlaceInput.parse("Zakopane", 49.3, 19.9))
        assertNull(PlaceInput.parse("", 0.0, 0.0))
        assertNull(PlaceInput.parse("123, 456", 0.0, 0.0))
    }

    @Test fun thePatternsWorkOnAndroidToo() {
        for (p in listOf(PlaceInput.AT_PATTERN, PlaceInput.DATA_PATTERN, PlaceInput.QUERY_PATTERN, PlaceInput.DMS_PATTERN, PlaceInput.DECIMAL_PATTERN, PlaceInput.PLUS_PATTERN)) {
            assertEquals(emptyList(), PatternCheck.loneBraces(p), p)
        }
    }
}

class CaptureTest {
    @Test fun videoShutterFollowsTheFrameRate() {
        assertEquals(1.0 / 50, Capture.videoShutter(25.0), 1e-12)
        assertEquals(1.0 / 120, Capture.videoShutter(60.0), 1e-12)
        assertEquals(1.0 / 100, Capture.videoShutter(25.0, 90.0), 1e-12)
    }

    @Test fun videoAtNoonNeedsAStrongFilter() {
        // Clear noon (EV 15), f/2.8, 1/50: ISO 100 needs about 6–7 stops of filter.
        val cfg = CaptureConfig(CaptureMode.Video, fps = 25.0)
        val setup = Capture.setup(cfg, ExposureSetup(AutoSetting.Shutter, 2.8, 1.0, 400.0, null, CameraLimits(100.0, 3200.0)))
        assertEquals(AutoSetting.Iso, setup.auto)
        assertEquals(0.02, setup.shutter, 1e-12)
        val r = Exposure.solve(15.0, setup)
        assertTrue(r.nd.stops >= 6.0, "nd ${r.nd}")
        assertEquals(null, r.warning)
    }

    @Test fun timelapseShutterIsHalfTheInterval() {
        val setup = Capture.setup(CaptureConfig(CaptureMode.Timelapse, interval = 4.0), ExposureSetup())
        assertEquals(2.0, setup.shutter, 1e-12)
        val r = Exposure.solve(15.0, setup.copy(nd = NdFilter.None))
        assertTrue(Capture.notes(CaptureConfig(CaptureMode.Timelapse, interval = 4.0), r).isNotEmpty())
    }

    @Test fun aPhotoIsAsSet() {
        val base = ExposureSetup(AutoSetting.Shutter, 8.0, 1.0, 100.0)
        assertEquals(base, Capture.setup(CaptureConfig(CaptureMode.Photo), base))
    }
}
