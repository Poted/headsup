package app.headsup.core.planner

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    // The shape of MET Norway's Locationforecast compact answer, cut short.
    private val sample = """{"type":"Feature","geometry":{"type":"Point","coordinates":[19.94,50.06,219]},
        "properties":{"meta":{"updated_at":"2026-06-20T22:00:00Z","units":{"cloud_area_fraction":"%"}},"timeseries":[
        {"time":"2026-06-21T00:00:00Z","data":{"instant":{"details":{"air_pressure_at_sea_level":1015.2,"cloud_area_fraction":0.0,"wind_speed":2.1}},"next_1_hours":{"summary":{"symbol_code":"clearsky_night"}}}},
        {"time":"2026-06-21T01:00:00Z","data":{"instant":{"details":{"cloud_area_fraction":50.0}}}},
        {"time":"2026-06-21T02:00:00Z","data":{"instant":{"details":{"air_temperature":12.0}}}}
        ]}}"""

    private fun ms(h: Int) = ZonedDateTime.of(2026, 6, 21, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun anAnswerIsReadMomentByMomentAndMomentsWithoutCloudAreLeftOut() {
        val f = assertNotNull(MetNorway.parse(sample))
        assertEquals(2, f.hours.size)
        assertEquals(0.25, f.at(ms(0) + 1_800_000L), 1e-9)
        assertTrue(f.covers(ms(0), ms(1)))
        assertTrue(!f.covers(ms(5), ms(6)))
    }

    @Test fun cloudIsMovedSmoothlyBetweenHoursAndHeldOutsideThem() {
        val f = CloudForecast(listOf(ms(0) to 0.0, ms(2) to 1.0))
        assertEquals(0.5, f.at(ms(1)), 1e-9)
        assertEquals(0.0, f.at(ms(0) - 3_600_000L), 1e-9)
        assertEquals(1.0, f.at(ms(5)), 1e-9)
    }

    @Test fun anAnswerWithNoMomentsIsNothing() {
        assertNull(MetNorway.parse("""{"type":"Feature","properties":{"timeseries":[]}}"""))
    }

    @Test fun theAddressHasFourDecimals() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=50.0612&lon=19.9400", MetNorway.url(50.06123, 19.94))
    }

    @Test fun aForecastOfOvercastDarkensTheDayByAboutTwoStops() {
        val place = Place(50.0, 20.0)
        val setup = ExposureSetup()
        val clear = Planner.frame(place, ms(10), SkyConditions(), setup)
        val over = Planner.frame(place, ms(10), SkyConditions(), setup, forecast = CloudForecast(listOf(ms(0) to 1.0)))
        assertTrue(clear.ev - over.ev in 1.7..2.3, "${clear.ev} vs ${over.ev}")
    }

    @Test fun aMeasuredReadingIsMovedByTheForecastsCloudNotJustByTheSun() {
        val place = Place(50.0, 20.0)
        val setup = ExposureSetup()
        val m = Measured(ms(9), 12.0)
        val same = Planner.frame(place, ms(9), SkyConditions(), setup, m, CloudForecast(listOf(ms(0) to 0.0)))
        assertEquals(12.0, same.ev, 1e-9)
        val over = Planner.frame(place, ms(9), SkyConditions(), setup, m, CloudForecast(listOf(ms(0) to 1.0)))
        assertTrue(12.0 - over.ev in 1.7..2.3, "${over.ev}")
    }
}
