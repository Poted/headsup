package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskBlendTest {
    private val w = 200
    private val h = 100

    /** The left half selected. */
    private val half = FloatArray(w * h) { i -> if (i % w < 100) 1f else 0f }

    private fun at(map: FloatArray, x: Int) = map[50 * w + x]

    @Test
    fun offLeavesTheMask() {
        val out = MaskBlend.apply(half, w, h, 0f, 1f)
        assertEquals(1f, at(out, 99))
        assertEquals(0f, at(out, 100))
    }

    @Test
    fun outsideKeepsTheSelectionAndFadesBeyondIt() {
        val out = MaskBlend.apply(half, w, h, 0.5f, 1f)
        assertEquals(1f, at(out, 90), 1e-3f)
        assertTrue(at(out, 110) in 0.3f..0.999f)
        assertTrue(at(out, 110) > at(out, 120))
        assertEquals(0f, at(out, 199), 1e-3f)
    }

    @Test
    fun insideLeavesTheRestUntouched() {
        val out = MaskBlend.apply(half, w, h, 0.5f, -1f)
        assertEquals(0f, at(out, 101), 1e-3f)
        assertTrue(at(out, 90) in 0.001f..0.7f)
        assertEquals(1f, at(out, 0), 1e-3f)
    }

    @Test
    fun acrossIsHalfAtTheOutline() {
        val out = MaskBlend.apply(half, w, h, 0.5f, 0f)
        assertEquals(0.5f, (at(out, 99) + at(out, 100)) / 2f, 0.05f)
    }
}
