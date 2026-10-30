package app.headsup.core.retouch

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetouchTest {
    private val w = 100
    private val h = 80

    private fun holeAt(vararg boxes: PixelBox) = BooleanArray(w * h) { i ->
        val x = i % w
        val y = i / w
        boxes.any { x in it.x0 until it.x1 && y in it.y0 until it.y1 }
    }

    @Test
    fun `separate areas are found apart, close ones together`() {
        val a = PixelBox(5, 5, 15, 15)
        val b = PixelBox(60, 50, 70, 70)
        val c = PixelBox(17, 5, 20, 10)
        assertEquals(listOf(a, b), Retouch.areas(holeAt(a, b), w, h))
        assertEquals(3, Retouch.areas(holeAt(a, b, c), w, h).size)
        // Two pixels apart, joined at 2: one area with both in its box.
        val joined = Retouch.areas(holeAt(a, b, c), w, h, join = 2)
        assertEquals(2, joined.size)
        assertEquals(PixelBox(5, 5, 20, 15), joined.first { it.x0 == 5 })
    }

    @Test
    fun `context is twice the area, square, inside the picture`() {
        val box = Retouch.context(PixelBox(80, 60, 100, 80), w, h, minSide = 10)
        assertEquals(40, box.width)
        assertEquals(40, box.height)
        assertTrue(box.x1 <= w && box.y1 <= h && box.x0 >= 0 && box.y0 >= 0)
        // Never more than the picture.
        val big = Retouch.context(PixelBox(0, 0, 90, 70), w, h)
        assertEquals(PixelBox(0, 0, w, h), big)
    }

    @Test
    fun `dilate grows by the radius`() {
        val grown = Retouch.dilate(holeAt(PixelBox(50, 40, 51, 41)), w, h, 3)
        assertEquals(49, grown.count { it })
        assertTrue(grown[37 * w + 47] && grown[43 * w + 53] && !grown[36 * w + 50])
    }

    @Test
    fun `alpha is whole inside and fades outside`() {
        val a = Retouch.alpha(holeAt(PixelBox(40, 30, 60, 50)), w, h, feather = 4)
        assertEquals(1f, a[40 * w + 50])
        assertTrue(a[40 * w + 61] in 0.5f..1f)
        assertTrue(a[40 * w + 63] in 0f..0.5f)
        assertEquals(0f, a[40 * w + 66])
    }

    @Test
    fun `grain measures the photo's noise around the hole`() {
        val rnd = Random(3)
        val sigma = 0.04f
        val rgb = FloatArray(w * h * 3) { 0.5f + sigma * rnd.nextGaussian() }
        val g = Retouch.grain(rgb, holeAt(PixelBox(40, 30, 60, 50)), w, h, ring = 10)
        assertTrue(abs(g - sigma) < 0.008f, "grain $g")
        val flat = Retouch.grain(FloatArray(w * h * 3) { 0.5f }, holeAt(PixelBox(40, 30, 60, 50)), w, h, ring = 10)
        assertEquals(0f, flat, 1e-6f)
    }

    private fun Random.nextGaussian(): Float {
        // Box-Muller.
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
    }
}
