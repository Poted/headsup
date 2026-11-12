package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowTest {
    @Test
    fun aSmallLightSpreadsSmoothlyWithoutRingsOrDots() {
        val w = 80
        val h = 80
        // One small bright square on black.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 38..41 && y in 38..41) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val light = Glow.light(px, 0.6f, 1f, 0f, floatArrayOf(1f, 1f, 1f))
        val blurred = Glow.blur(light, w, h, 6f)
        // Falling off steadily with distance from it along a row: never rising again (no ring, no copy).
        var last = 256
        for (x in 40 until 70) {
            val v = blurred[40 * w + x] and 0xFF
            assertTrue(v <= last, "rises again at $x: $v after $last")
            last = v
        }
        // And the same at the same distance in every direction (no pattern).
        val right = blurred[40 * w + 50] and 0xFF
        val down = blurred[50 * w + 40] and 0xFF
        assertEquals(right.toFloat(), down.toFloat(), 2f)
        // Dark parts give no light.
        assertEquals(0, Glow.light(intArrayOf(0xFF202020.toInt()), 0.6f, 1f, 1f, floatArrayOf(1f, 1f, 1f))[0] and 0xFFFFFF)
    }
}
