package app.headsup.core.frame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StabilizerTest {
    private val w = 160
    private val h = 96

    /** A textured scene seen from ([ox], [oy]) pixels off, turned by [deg]. */
    private fun frame(ox: Float, oy: Float, deg: Float = 0f): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        return FloatArray(w * h) { i ->
            val x0 = i % w - w / 2.0
            val y0 = i / w - h / 2.0
            // The scene point shown here: the frame moved and turned over it.
            val x = x0 * cos(a) - y0 * sin(a) - ox
            val y = x0 * sin(a) + y0 * cos(a) - oy
            (0.5 + 0.25 * sin(x / 5.0) * cos(y / 7.0) + 0.15 * sin((x + 2 * y) / 11.0)).toFloat()
        }
    }

    @Test
    fun aShiftIsMeasured() {
        val s = Stabilizer.step(frame(0f, 0f), frame(4f, -3f), w, h)
        assertEquals(4f / w, s.dx, 1.2f / w)
        assertEquals(-3f / h, s.dy, 1.2f / h)
        assertTrue(abs(s.degrees) < 0.6f)
    }

    @Test
    fun aTurnIsMeasured() {
        val s = Stabilizer.step(frame(0f, 0f), frame(0f, 0f, 1.5f), w, h)
        assertTrue(abs(abs(s.degrees) - 1.5f) < 0.7f, "${s.degrees}")
    }

    @Test
    fun shakeIsTakenOutAndAPanStays() {
        // A slow pan right with shake on top.
        val steps = List(120) { i -> Stabilizer.Step(if (i == 0) 0f else 0.002f + if (i % 2 == 0) 0.01f else -0.01f, 0f, 0f) }
        val path = Stabilizer.Path(0L, 33.3f, steps, strength = 1f)
        val c = Stabilizer.corrections(path, 16f / 9f)
        // Moved by the correction, the frame-to-frame shake is far smaller than before.
        var before = 0f
        var after = 0f
        var x = 0f
        var prev = 0f
        for (i in 1 until steps.size) {
            x += steps[i].dx
            val steady = x + c[i].dx
            before += abs(steps[i].dx - 0.002f)
            after += abs((steady - prev) - 0.002f)
            prev = steady
        }
        assertTrue(after < before * 0.3f, "after $after before $before")
        assertTrue(c.all { it.zoom in 1f..Stabilizer.MAX_ZOOM })
    }

    @Test
    fun noStrengthNoChange() {
        val path = Stabilizer.Path(0L, 33f, List(10) { Stabilizer.Step(0.01f, 0f, 0f) }, strength = 0f)
        assertTrue(Stabilizer.corrections(path, 1.5f).all { it.isNone })
    }
}
