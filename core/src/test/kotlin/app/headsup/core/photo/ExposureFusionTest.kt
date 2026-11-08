package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposureFusionTest {
    private val w = 160
    private val h = 120

    /** A scene with a bright sky over dark ground, with texture, at [ev] stops, shifted by [sx], [sy]. */
    private fun shot(ev: Float, sx: Int = 0, sy: Int = 0): IntArray = IntArray(w * h) { i ->
        val x = i % w - sx
        val y = i / w - sy
        // A slanted horizon and a lit window below it: the median threshold sees edges both ways.
        val sky = y < h / 2 + (x - w / 2) / 3 || (x in 30..60 && y in 80..100)
        val base = if (sky) 0.9f else 0.06f
        // Texture that does not repeat within the search (a check pattern would line up at many shifts).
        val wave = 0.25f * kotlin.math.sin(x * 0.21f + y * 0.05f) * kotlin.math.cos(y * 0.17f - x * 0.03f)
        val blob = if ((x - 50) * (x - 50) + (y - 30) * (y - 30) < 120 || (x - 110) * (x - 110) + (y - 90) * (y - 90) < 150) 0.35f else 0f
        val tex = 1f + wave - blob
        val lin = base * tex * Math.pow(2.0, ev.toDouble()).toFloat()
        val v = (Math.pow(lin.coerceIn(0f, 1f).toDouble(), 1 / 2.2) * 255).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun contrast(p: IntArray, top: Boolean): Float {
        // Away from the horizon and the window.
        val ys = if (top) 5 until 25 else h - 15 until h - 3
        val vals = ys.flatMap { y -> (70 until w - 10).map { x -> (p[y * w + x] and 0xFF).toFloat() } }
        return vals.max() - vals.min()
    }

    @Test
    fun keepsDetailInTheSkyAndTheGround() {
        val dark = shot(-2f)
        val mid = shot(0f)
        val bright = shot(2f)
        val fused = ExposureFusion.fuse(listOf(dark, mid, bright), w, h)
        // The sky burns out in the bright shot and the ground is black in the dark one; fused, both have texture.
        assertTrue(contrast(fused, top = true) > 0.6f * contrast(dark, top = true), "sky ${contrast(fused, true)} vs ${contrast(dark, true)}")
        assertTrue(contrast(fused, top = false) > 0.6f * contrast(bright, top = false), "ground ${contrast(fused, false)} vs ${contrast(bright, false)}")
    }

    @Test
    fun findsTheShiftBetweenExposures() {
        val ref = shot(0f)
        val moved = shot(-2f, sx = 5, sy = -3)
        val (dx, dy) = ExposureFusion.align(ref, moved, w, h, maxShift = 16)
        assertEquals(-5, dx)
        assertEquals(3, dy)
        val back = ExposureFusion.shifted(moved, w, h, dx, dy)
        // Lined up: the checks fall where the reference has them.
        val (dx2, dy2) = ExposureFusion.align(ref, back, w, h, maxShift = 16)
        assertEquals(0, dx2)
        assertEquals(0, dy2)
    }

    @Test
    fun oneShotIsItself() {
        val mid = shot(0f)
        assertTrue(ExposureFusion.fuse(listOf(mid), w, h).contentEquals(mid))
    }
}
