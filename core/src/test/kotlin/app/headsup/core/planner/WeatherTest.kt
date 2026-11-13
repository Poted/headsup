package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private val sample = """{"latitude":50.06,"longitude":19.94,"hourly_units":{"time":"iso8601","cloud_cover":"%"},
        "hourly":{"time":["2026-06-21T00:00","2026-06-21T01:00","2026-06-21T02:00"],"cloud_cover":[0,50,null]}}"""

    private fun ms(h: Int) = ZonedDateTime.of(2026, 6, 21, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun anAnswerIsReadHourByHourAndEmptyHoursAreLeftOut() {
        val f = assertNotNull(OpenMeteo.parse(sample))
        assertEquals(2, f.hours.size)
        assertEquals(0.5, f.at(ms(1)), 1e-9)
    }

    @Test fun cloudIsMovedSmoothlyBetweenHoursAndHeldOutsideThem() {
        val f = CloudForecast(listOf(ms(0) to 0.0, ms(2) to 1.0))
        assertEquals(0.5, f.at(ms(1)), 1e-9)
        assertEquals(0.0, f.at(ms(0) - 3_600_000L), 1e-9)
        assertEquals(1.0, f.at(ms(5)), 1e-9)
    }

    @Test fun anAnswerWithNoHoursIsNothing() {
        assertNull(OpenMeteo.parse("""{"error":true,"reason":"out of range"}"""))
    }

    @Test fun theAddressAsksForTheDayAndItsNeighbours() {
        val u = OpenMeteo.url(50.06, 19.94, LocalDate.of(2026, 6, 21))
        assertTrue("latitude=50.0600" in u && "start_date=2026-06-20" in u && "end_date=2026-06-22" in u && "hourly=cloud_cover" in u, u)
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

    @Test fun aTimelapseWorkedUnderAForecastSeesItsClouds() {
        val place = Place(50.0, 20.0)
        val clear = Timelapse.sample(place, SkyConditions(), ms(8), ms(12), 60)
        val over = Timelapse.sample(place, SkyConditions(), ms(8), ms(12), 60, forecast = CloudForecast(listOf(ms(0) to 1.0)))
        assertTrue(over.zip(clear).all { (o, c) -> o.second < c.second })
    }
}
