package app.headsup.core.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteShapeTest {
    private fun track(lats: DoubleArray, lons: DoubleArray): ActivityTrack {
        val t = LongArray(lats.size) { it * 1000L }
        return ActivityTrack(
            series = mapOf(Metric.LATITUDE to Series(t, lats), Metric.LONGITUDE to Series(t, lons)),
            startMs = 0, endMs = t.last(), utcOffsetSeconds = null, pauses = emptyList(),
            hrZoneHighBoundaries = null, sport = Sport.entries.first(), sportName = null,
        )
    }

    @Test
    fun `a route going north is a vertical line, up the picture`() {
        val shape = RouteShape.of(track(doubleArrayOf(49.0, 49.01, 49.02), doubleArrayOf(20.0, 20.0, 20.0)))!!
        // Start at the bottom, end at the top, centred across.
        assertEquals(0.5f, shape.points[0], 1e-4f)
        assertEquals(1f, shape.points[1], 1e-4f)
        assertEquals(0f, shape.points[5], 1e-4f)
        val (x, y) = shape.project(49.01, 20.0)
        assertEquals(0.5f, x, 1e-4f)
        assertEquals(0.5f, y, 1e-4f)
    }

    @Test
    fun `east-west distances shrink with latitude`() {
        // 0.01° of longitude at 60° N is half as long as 0.01° of latitude.
        val shape = RouteShape.of(track(doubleArrayOf(60.0, 60.01), doubleArrayOf(10.0, 10.01)))!!
        val dx = shape.points[2] - shape.points[0]
        val dy = shape.points[1] - shape.points[3]
        assertTrue(kotlin.math.abs(dx / dy - 0.5f) < 0.01f, "dx/dy = ${dx / dy}")
    }

    @Test
    fun `no shape without gps`() {
        assertNull(RouteShape.of(track(doubleArrayOf(49.0), doubleArrayOf(20.0))))
    }
}
