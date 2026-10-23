package app.headsup.core.color

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaskTrackerTest {
    private val w = 320
    private val h = 180

    /** A textured scene seen shifted by ([sx], [sy]) pixels and turned by [deg] around the centre. */
    private fun scene(sx: Double, sy: Double, deg: Double = 0.0): GrayFrame {
        val a = Math.toRadians(deg)
        return GrayFrame(w, h, FloatArray(w * h) { i ->
            val px = i % w - w / 2.0
            val py = i / w - h / 2.0
            val x = px * cos(a) + py * sin(a) - sx
            val y = -px * sin(a) + py * cos(a) - sy
            (0.5 + 0.2 * sin(x * 0.21) * cos(y * 0.17) + 0.15 * sin((x + 2 * y) * 0.09) + 0.1 * cos(x * 0.05 - y * 0.31)).toFloat()
        })
    }

    @Test
    fun `follows a pan`() {
        val mask = ColourMask(MaskKind.ELLIPSE, centerX = 0.5f, centerY = 0.5f)
        val tracker = MaskTracker(mask, scene(0.0, 0.0), 0)
        var key: MaskKey? = null
        for (step in 1..10) key = tracker.next(scene(step * 3.0, step * -1.0), step * 100L)
        assertNotNull(key)
        assertEquals(0.5f + 30f / w, key.centerX, 0.01f)
        assertEquals(0.5f - 10f / h, key.centerY, 0.01f)
        assertEquals(0f, key.angleDegrees, 1.5f)
    }

    @Test
    fun `follows a turn`() {
        val mask = ColourMask(MaskKind.LINEAR, centerX = 0.5f, centerY = 0.5f)
        val tracker = MaskTracker(mask, scene(0.0, 0.0), 0)
        var key: MaskKey? = null
        for (step in 1..8) key = tracker.next(scene(0.0, 0.0, step * 1.0), step * 100L)
        assertNotNull(key)
        // The scene turns clockwise (y grows downwards), as does a positive mask angle.
        assertEquals(8f, key.angleDegrees, 2f)
    }

    @Test
    fun `stops when the picture changes completely`() {
        val tracker = MaskTracker(ColourMask(MaskKind.ELLIPSE), scene(0.0, 0.0), 0)
        val flat = GrayFrame(w, h, FloatArray(w * h) { 0.5f })
        tracker.next(flat, 100)
        assertTrue(tracker.lost)
        assertFalse(tracker.next(scene(0.0, 0.0), 200) != null)
    }

    @Test
    fun `keys interpolate`() {
        val keys = listOf(MaskKey(0, 0.2f, 0.5f, 0f, 0.5f, 0.6f), MaskKey(1000, 0.6f, 0.5f, 10f, 0.5f, 0.6f))
        val m = ColourMask(MaskKind.ELLIPSE, keys = keys).at(250)
        assertEquals(0.3f, m.centerX, 1e-4f)
        assertEquals(2.5f, m.angleDegrees, 1e-4f)
    }

    @Test
    fun `a steady track simplifies to its ends, a turn keeps its corner`() {
        val straight = (0..20).map { MaskKey(it * 100L, 0.2f + it * 0.01f, 0.5f, 0f, 0.5f, 0.6f) }
        assertEquals(2, MaskKey.simplify(straight).size)
        val corner = (0..20).map { MaskKey(it * 100L, if (it <= 10) 0.2f + it * 0.02f else 0.4f, 0.5f, 0f, 0.5f, 0.6f) }
        val kept = MaskKey.simplify(corner)
        assertEquals(listOf(0L, 1000L, 2000L), kept.map { it.timeMs })
    }
}
