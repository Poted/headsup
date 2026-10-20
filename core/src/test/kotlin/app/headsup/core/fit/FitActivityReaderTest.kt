package app.headsup.core.fit

import app.headsup.core.track.Metric
import app.headsup.core.track.Sport
import app.headsup.core.track.TimeRange
import com.garmin.fit.EventType
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.garmin.fit.Sport as FitSport

class FitActivityReaderTest {
    private val t0 = Instant.parse("2026-10-20T19:18:40Z").toEpochMilli()

    @Test
    fun `FIT epoch timestamps decode to UTC`() {
        // 1161458320 s after 1989-12-31T00:00:00Z, as written by a Forerunner 255.
        val fitSeconds = 1161458320L
        assertEquals(t0, com.garmin.fit.DateTime(fitSeconds).date.time)
        val track = TestFit(t0).apply { record(t0, hr = 52) }.read()
        assertEquals(t0, track.startMs)
    }

    @Test
    fun `record fields are converted to SI units and degrees`() {
        val track = TestFit(t0).apply {
            record(t0, hr = 140, altitude = 218.6, distance = 0.0, speed = 3.2, lat = 52.2297, lon = 21.0122)
            record(t0 + 1000, hr = 142, altitude = 219.0, distance = 3.2, speed = 3.3, lat = 52.2298, lon = 21.0123)
        }.read()

        assertEquals(t0, track.startMs)
        assertEquals(t0 + 1000, track.endMs)
        assertEquals(listOf(140.0, 142.0), track.series.getValue(Metric.HEART_RATE).values.toList())
        assertEquals(218.6, track.series.getValue(Metric.ALTITUDE).values[0], 0.2)
        assertEquals(3.2, track.series.getValue(Metric.DISTANCE).values[1], 0.01)
        assertEquals(3.3, track.series.getValue(Metric.SPEED).values[1], 0.01)
        assertEquals(52.2297, track.series.getValue(Metric.LATITUDE).values[0], 1e-6)
        assertEquals(21.0123, track.series.getValue(Metric.LONGITUDE).values[1], 1e-6)
    }

    @Test
    fun `metrics the watch did not record are absent`() {
        val track = TestFit(t0).apply { record(t0, hr = 60); record(t0 + 4000, hr = 63) }.read()
        assertFalse(track.has(Metric.LATITUDE))
        assertFalse(track.has(Metric.POWER))
        assertNull(track.series[Metric.SPEED])
    }

    @Test
    fun `utc offset comes from the activity local timestamp`() {
        val track = TestFit(t0).apply {
            record(t0, hr = 60)
            activity(t0 + 60_000, utcOffsetSeconds = 7200)
        }.read()
        assertEquals(7200, track.utcOffsetSeconds)
    }

    @Test
    fun `missing activity message leaves offset unknown`() {
        assertNull(TestFit(t0).apply { record(t0, hr = 60) }.read().utcOffsetSeconds)
    }

    @Test
    fun `timer stop and start become pauses`() {
        val track = TestFit(t0).apply {
            timer(t0, EventType.START)
            record(t0, hr = 100)
            record(t0 + 10_000, hr = 110)
            timer(t0 + 10_000, EventType.STOP_ALL)
            timer(t0 + 70_000, EventType.START)
            record(t0 + 70_000, hr = 95)
            timer(t0 + 80_000, EventType.STOP_ALL)
        }.read()
        assertEquals(listOf(TimeRange(t0 + 10_000, t0 + 70_000)), track.pauses)
    }

    @Test
    fun `hr zones from time_in_zone`() {
        val track = TestFit(t0).apply {
            record(t0, hr = 60)
            hrZones(96, 115, 134, 154, 173, 192)
        }.read()
        assertEquals(listOf(96, 115, 134, 154, 173, 192), track.hrZoneHighBoundaries)
        assertEquals(0, track.hrZone(80.0))
        assertEquals(1, track.hrZone(100.0))
        assertEquals(3, track.hrZone(150.0))
        assertEquals(5, track.hrZone(185.0))
        assertEquals(5, track.hrZone(200.0))
    }

    @Test
    fun `running cadence is reported in steps per minute`() {
        val track = TestFit(t0).apply {
            session(FitSport.RUNNING, "Run")
            record(t0, cadence = 85, fractionalCadence = 0.5)
        }.read()
        assertEquals(Sport.RUNNING, track.sport)
        assertEquals("Run", track.sportName)
        assertEquals(171.0, track.series.getValue(Metric.CADENCE).values[0], 0.01)
    }

    @Test
    fun `cycling cadence stays in rpm`() {
        val track = TestFit(t0).apply {
            session(FitSport.CYCLING)
            record(t0, cadence = 90)
        }.read()
        assertEquals(90.0, track.series.getValue(Metric.CADENCE).values[0], 0.01)
    }

    @Test
    fun `ascent and descent ignore noise below the threshold`() {
        val altitudes = listOf(100.0, 101.0, 100.0, 101.5, 105.0, 104.0, 110.0, 107.0, 108.0, 100.0)
        val track = TestFit(t0).apply {
            altitudes.forEachIndexed { i, a -> record(t0 + i * 1000L, altitude = a) }
        }.read()
        val ascent = track.series.getValue(Metric.ASCENT).values
        val descent = track.series.getValue(Metric.DESCENT).values
        // Up 100 -> 110 (noise dips under 2 m ignored), then down 110 -> 100.
        assertEquals(10.0, ascent.last(), 0.3)
        assertEquals(10.0, descent.last(), 0.3)
        assertEquals(0.0, ascent[3], 0.01)
    }

    @Test
    fun `grade is derived from altitude over distance when not recorded`() {
        val track = TestFit(t0).apply {
            // 5 % climb: 1 m up every 20 m.
            for (i in 0..10) record(t0 + i * 5000L, altitude = 200.0 + i, distance = i * 20.0)
        }.read()
        val grade = assertNotNull(track.series[Metric.GRADE])
        assertEquals(5.0, grade.values.last(), 0.2)
    }

    @Test
    fun `non FIT input is rejected`() {
        assertFailsWith<FitFormatException> {
            FitActivityReader.read(ByteArrayInputStream("<gpx></gpx>".toByteArray()))
        }
    }
}
