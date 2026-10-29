package app.headsup.core.frame

import app.headsup.core.color.GrayFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StabilizationTest {
    private val w = 192
    private val h = 108

    private fun scene(sx: Double, sy: Double) = GrayFrame(w, h, FloatArray(w * h) { i ->
        val x = i % w - sx
        val y = i / w - sy
        (0.5 + 0.2 * sin(x * 0.31) * cos(y * 0.23) + 0.15 * sin((x + 2 * y) * 0.13) + 0.1 * cos(x * 0.07 - y * 0.41)).toFloat()
    })

    @Test
    fun `finds the shift between two frames`() {
        val m = CameraMotion.between(scene(0.0, 0.0), scene(4.0, -2.0))
        assertNotNull(m)
        assertEquals(4f / w, m.dx, 0.004f)
        assertEquals(-2f / h, m.dy, 0.006f)
        assertEquals(0f, m.turn, 0.02f)
    }

    @Test
    fun `smoothing takes out the shake and keeps the pan`() {
        // A steady pan right with a fast shake up and down.
        val times = (0 until 200).map { it * 50L }
        val x = times.map { it / 10_000f }
        val y = times.mapIndexed { i, _ -> if (i % 2 == 0) 0.01f else -0.01f }
        val zeros = times.map { 0f }
        val stab = Stabilization(CameraPath(times, x, y, zeros, zeros), strength = 0.6f)
        // The shake is taken out: the correction is about the shake, the other way.
        val c1 = stab.at(times[100])
        val c2 = stab.at(times[101])
        assertTrue(abs(c1.dy + 0.01f) < 0.004f, "${c1.dy}")
        assertTrue(abs(c2.dy - 0.01f) < 0.004f, "${c2.dy}")
        // The pan stays (little correction across, away from the ends).
        assertTrue(abs(c1.dx) < 0.003f, "${c1.dx}")
        assertTrue(stab.zoom in 1f..Stabilization.MAX_ZOOM)
    }
}
