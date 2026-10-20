package app.headsup.core.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TelemetrySamplerTest {
    private val t0 = 1_800_000_000_000L

    private fun track(
        series: Map<Metric, List<Pair<Long, Double>>>,
        pauses: List<TimeRange> = emptyList(),
    ): ActivityTrack {
        val built = series.mapValues { (_, points) ->
            Series(points.map { t0 + it.first }.toLongArray(), points.map { it.second }.toDoubleArray())
        }
        val times = built.values.flatMap { it.timesMs.toList() }
        return ActivityTrack(built, times.min(), times.max(), 7200, pauses, null, Sport.RUNNING, null)
    }

    @Test
    fun `exact record time returns the recorded value`() {
        val s = TelemetrySampler(track(mapOf(Metric.HEART_RATE to listOf(0L to 100.0, 1000L to 110.0))))
        assertEquals(110.0, s.valueAt(Metric.HEART_RATE, t0 + 1000))
    }

    @Test
    fun `values between records are interpolated linearly`() {
        val s = TelemetrySampler(track(mapOf(Metric.HEART_RATE to listOf(0L to 100.0, 10_000L to 120.0))))
        assertEquals(105.0, s.valueAt(Metric.HEART_RATE, t0 + 2_500)!!, 1e-9)
        // Sub-second frame times (e.g. 29.97 fps) interpolate too.
        assertEquals(100.0667, s.valueAt(Metric.HEART_RATE, t0 + 33)!!, 1e-3)
    }

    @Test
    fun `smart recording gaps are bridged`() {
        // Forerunner 255 sample file: 16 s between records while standing still.
        val s = TelemetrySampler(track(mapOf(Metric.HEART_RATE to listOf(0L to 56.0, 16_000L to 59.0))))
        assertEquals(57.5, s.valueAt(Metric.HEART_RATE, t0 + 8_000)!!, 1e-9)
    }

    @Test
    fun `long gaps show unknown for readings but hold totals`() {
        val s = TelemetrySampler(
            track(
                mapOf(
                    Metric.HEART_RATE to listOf(0L to 100.0, 45_000L to 130.0),
                    Metric.DISTANCE to listOf(0L to 1000.0, 45_000L to 1150.0),
                ),
            ),
        )
        assertNull(s.valueAt(Metric.HEART_RATE, t0 + 20_000))
        assertEquals(1000.0, s.valueAt(Metric.DISTANCE, t0 + 20_000))
    }

    @Test
    fun `gap threshold is configurable`() {
        val t = track(mapOf(Metric.HEART_RATE to listOf(0L to 100.0, 8_000L to 120.0)))
        assertNull(TelemetrySampler(t, maxGapMs = 5_000).valueAt(Metric.HEART_RATE, t0 + 4_000))
        assertEquals(110.0, TelemetrySampler(t).valueAt(Metric.HEART_RATE, t0 + 4_000)!!, 1e-9)
    }

    @Test
    fun `paused timer hides readings even across a short gap`() {
        val s = TelemetrySampler(
            track(
                mapOf(
                    Metric.HEART_RATE to listOf(0L to 150.0, 20_000L to 90.0),
                    Metric.DISTANCE to listOf(0L to 500.0, 20_000L to 500.0),
                ),
                pauses = listOf(TimeRange(t0 + 5_000, t0 + 20_000)),
            ),
        )
        assertNull(s.valueAt(Metric.HEART_RATE, t0 + 10_000))
        assertNull(s.valueAt(Metric.HEART_RATE, t0 + 2_000)) // segment spans the pause
        assertEquals(500.0, s.valueAt(Metric.DISTANCE, t0 + 10_000))
    }

    @Test
    fun `outside the recorded span is unknown`() {
        val s = TelemetrySampler(track(mapOf(Metric.HEART_RATE to listOf(0L to 100.0, 1000L to 101.0))))
        assertNull(s.valueAt(Metric.HEART_RATE, t0 - 1))
        assertNull(s.valueAt(Metric.HEART_RATE, t0 + 1001))
    }

    @Test
    fun `frame contains only known metrics`() {
        val s = TelemetrySampler(
            track(
                mapOf(
                    Metric.HEART_RATE to listOf(0L to 100.0, 2000L to 104.0),
                    Metric.SPEED to listOf(0L to 3.0, 60_000L to 3.0),
                ),
            ),
        )
        val frame = s.frameAt(t0 + 1000)
        assertEquals(102.0, frame[Metric.HEART_RATE]!!, 1e-9)
        assertFalse(Metric.SPEED in frame.values)
        assertNull(frame[Metric.ALTITUDE])
    }
}
