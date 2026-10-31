package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraMotionTest {
    private val w = 160
    private val h = 90

    /** A textured scene seen shifted by ([ox], [oy]) pixels. */
    private fun scene(ox: Float, oy: Float) = GrayFrame(w, h, FloatArray(w * h) { i ->
        val x = i % w - ox
        val y = i / w - oy
        (0.5f + 0.2f * sin(x * 0.31f) * sin(y * 0.23f) + 0.15f * sin(x * 0.07f + y * 0.11f) + 0.1f * sin(x * 0.53f - y * 0.17f)).coerceIn(0f, 1f)
    })

    @Test
    fun `finds the shift between frames and carries a map with it`() {
        val m = assertNotNull(CameraMotion.between(scene(0f, 0f), scene(6f, -3f)))
        val (bx, by) = m.back(80f, 45f)
        assertTrue(abs(80f - bx - 6f) < 1.2f && abs(45f - by + 3f) < 1.2f, "back ($bx, $by)")
        // A fix painted on the first frame is where the same things are in the second.
        val fix = FloatArray(w * h) { i -> if (i % w in 40 until 50 && i / w in 30 until 40) 1f else 0f }
        val carried = CameraMotion.carry(fix, w, h, m)
        assertTrue(carried[35 * w + 51] > 0.5f, "moved fix")
        assertTrue(carried[35 * w + 41] < 0.5f)
        assertEquals(fix.toList(), CameraMotion.carry(fix, w, h, Similarity(0f, 0f, 0f, 0f, 0f, 1f)).toList())
    }
}
