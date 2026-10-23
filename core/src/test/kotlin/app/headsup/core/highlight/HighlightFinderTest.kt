package app.headsup.core.highlight

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.Series
import app.headsup.core.track.Sport
import app.headsup.core.track.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightFinderTest {
    private val t0 = 1_800_000_000_000L

    /** 20 minutes at 5 m/s on the flat, with a 12 m/s burst at 8 min and a 100 m drop at 15 min. */
    private val track: ActivityTrack = run {
        val seconds = 0..1200
        val speed = seconds.map { s -> if (s in 470..490) 12.0 else 5.0 }
        val altitude = seconds.map { s ->
            when {
                s < 880 -> 500.0
                s < 920 -> 500.0 - (s - 880) * 2.5
                else -> 400.0
            }
        }
        val times = seconds.map { t0 + it * 1000L }.toLongArray()
        ActivityTrack(
            mapOf(
                Metric.SPEED to Series(times, speed.toDoubleArray()),
                Metric.ALTITUDE to Series(times, altitude.toDoubleArray()),
            ),
            times.first(), times.last(), 7200, emptyList(), null, Sport.CYCLING, null,
        )
    }

    @Test
    fun `finds the burst and the drop where there is video`() {
        val found = HighlightFinder.find(track, listOf(track.range))
        val speed = found.first { it.kind == HighlightKind.TOP_SPEED }
        assertTrue(speed.timeMs in t0 + 470_000..t0 + 490_000, "top speed at ${(speed.timeMs - t0) / 1000}s")
        val descent = found.first { it.kind == HighlightKind.FAST_DESCENT }
        assertTrue(descent.timeMs in t0 + 875_000..t0 + 925_000, "descent at ${(descent.timeMs - t0) / 1000}s")
        assertEquals(2.5, descent.value, 0.3)
        // Close moments merge: no two highlights within 20 s.
        assertTrue(found.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 20_000 })
    }

    @Test
    fun `ignores moments without video`() {
        val firstFiveMinutes = listOf(TimeRange(t0, t0 + 300_000))
        val found = HighlightFinder.find(track, firstFiveMinutes)
        assertTrue(found.none { it.kind == HighlightKind.TOP_SPEED || it.kind == HighlightKind.FAST_DESCENT })
    }

    @Test
    fun `a chosen metric gives only its moments`() {
        val found = HighlightFinder.find(track, listOf(track.range), metric = Metric.ALTITUDE)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.kind.metric == Metric.ALTITUDE })
    }
}
