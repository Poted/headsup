package app.headsup.core.edit

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.Series
import app.headsup.core.track.Sport
import app.headsup.core.track.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFieldsTest {
    @Test
    fun `fills known fields and dashes unknown ones`() {
        val text = TextFields.fill("↑ {ascent} · {distance} · {nope}", mapOf("ascent" to "1 240 m", "distance" to "18.4 km"))
        assertEquals("↑ 1 240 m · 18.4 km · –", text)
    }

    @Test
    fun `plain text stays as it is`() {
        assertFalse(TextFields.hasFields("TATRY {"))
        assertEquals("TATRY", TextFields.fill("TATRY", emptyMap()))
        assertTrue(TextFields.hasFields("{place}"))
    }

    @Test
    fun `summary of an activity`() {
        val t = longArrayOf(0, 1_000, 2_000, 3_000)
        val track = ActivityTrack(
            series = mapOf(
                Metric.DISTANCE to Series(t, doubleArrayOf(0.0, 10.0, 20.0, 30.0)),
                Metric.ALTITUDE to Series(t, doubleArrayOf(100.0, 120.0, 110.0, 105.0)),
                Metric.ASCENT to Series(t, doubleArrayOf(0.0, 20.0, 20.0, 20.0)),
                Metric.HEART_RATE to Series(t, doubleArrayOf(100.0, 120.0, 140.0, 120.0)),
            ),
            startMs = 0, endMs = 3_000, utcOffsetSeconds = null, pauses = listOf(TimeRange(1_000, 1_500)),
            hrZoneHighBoundaries = null, sport = Sport.entries.first(), sportName = null,
        )
        val s = ActivitySummary.of(track)
        assertEquals(30.0, s.distanceM)
        assertEquals(20.0, s.ascentM)
        assertEquals(120.0, s.highestM)
        assertEquals(2_500L, s.movingMs)
        assertEquals(120.0, s.avgHr)
        assertEquals(140.0, s.maxHr)
    }
}
