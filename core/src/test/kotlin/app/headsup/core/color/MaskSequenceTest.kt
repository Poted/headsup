package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaskSequenceTest {
    private val w = 40
    private val h = 30

    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = FloatArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) 1f else 0f }

    @Test
    fun `fades between maps and holds at the ends`() {
        val a = MaskSequence.bytes(rect(0, 0, 10, 10))
        val b = MaskSequence.bytes(FloatArray(w * h))
        val seq = MaskSequence(w, h, longArrayOf(1000, 1100), listOf(a, b))
        assertEquals(1f, seq.at(500)[5 * w + 5], 1e-3f)
        assertEquals(0.5f, seq.at(1050)[5 * w + 5], 0.02f)
        assertEquals(0f, seq.at(5000)[5 * w + 5], 1e-3f)
        // Sampled to another size.
        assertEquals(1f, seq.at(1000, w / 2, h / 2)[2 * (w / 2) + 2], 1e-3f)
    }

    @Test
    fun `tracking a part again replaces only that part`() {
        val one = MaskSequence.bytes(rect(0, 0, 5, 5))
        val two = MaskSequence.bytes(rect(5, 5, 10, 10))
        val old = MaskSequence(w, h, longArrayOf(0, 100, 200, 300), List(4) { one })
        val newer = MaskSequence(w, h, longArrayOf(150, 250), List(2) { two })
        val m = old.merged(newer)
        assertEquals(listOf(0L, 100L, 150L, 250L, 300L), m.timesMs.toList())
        assertTrue(m.frames[2] === two && m.frames[4] === one)
    }

    @Test
    fun `box, inside point and edge`() {
        val m = rect(10, 5, 30, 25)
        val box = assertNotNull(MaskFollow.box(m, w, h, grow = 0f))
        assertEquals(10f / w, box[0], 1e-4f)
        assertEquals(5f / h, box[1], 1e-4f)
        assertEquals(30f / w, box[2], 1e-4f)
        assertEquals(25f / h, box[3], 1e-4f)
        val (px, py) = assertNotNull(MaskFollow.inside(m, w, h))
        assertTrue(px * w in 15f..25f && py * h in 10f..20f, "inside at ${px * w}, ${py * h}")
        assertTrue(!MaskFollow.touchesEdge(m, w, h))
        assertTrue(MaskFollow.touchesEdge(rect(0, 5, 10, 10), w, h))
        assertNull(MaskFollow.box(FloatArray(w * h), w, h))
        assertNull(MaskFollow.inside(FloatArray(w * h), w, h))
    }

    @Test
    fun `picks the candidate that is the same object`() {
        val previous = rect(10, 10, 20, 20)
        val moved = rect(11, 10, 21, 20)
        val whole = rect(0, 0, w, h)
        val elsewhere = rect(25, 0, 35, 8)
        assertEquals(1, MaskFollow.pick(previous, listOf(whole to 0.99f, moved to 0.8f, elsewhere to 0.95f)))
        assertNull(MaskFollow.pick(previous, listOf(elsewhere to 0.99f)))
        assertEquals(0.25f, MaskFollow.area(rect(0, 0, 20, 15)), 1e-4f)
    }
}
