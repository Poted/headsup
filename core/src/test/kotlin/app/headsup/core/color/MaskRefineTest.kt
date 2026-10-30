package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskRefineTest {
    private val w = 96
    private val h = 64

    /** The true edge: a ridge (sky above it) that wanders. */
    private fun ridge(x: Int) = 30f + 8f * sin(x * 0.21f) + 4f * sin(x * 0.7f)

    private fun picture() = FloatArray(w * h * 3).also { rgb ->
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val sky = y < ridge(x)
            // Blue sky; a darker green-brown slope with some texture.
            rgb[i] = if (sky) 0.45f else 0.25f + 0.05f * ((x * 7 + y * 3) % 5) / 5f
            rgb[i + 1] = if (sky) 0.62f else 0.30f
            rgb[i + 2] = if (sky) 0.92f else 0.18f
        }
    }

    @Test
    fun `a rough blocky mask follows the picture's edge`() {
        val rgb = picture()
        // What a model seeing the picture at an eighth of its size gives: blocks, off by a few pixels.
        val rough = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val bx = x / 8 * 8 + 4
            if (y < ridge(bx)) 1f else 0f
        }
        val refined = MaskRefine.guided(rgb, rough, w, h, radius = 6, eps = 1e-3f)
        fun wrong(m: FloatArray): Int {
            var n = 0
            for (y in 0 until h) for (x in 0 until w) if ((m[y * w + x] > 0.5f) != (y < ridge(x))) n++
            return n
        }
        assertTrue(wrong(refined) * 3 < wrong(rough), "refined ${wrong(refined)} vs rough ${wrong(rough)}")
        assertTrue(refined.all { it in 0f..1f })
    }

    @Test
    fun `a flat picture keeps the mask's mean`() {
        val rgb = FloatArray(w * h * 3) { 0.5f }
        val mask = FloatArray(w * h) { if (it % w < w / 2) 1f else 0f }
        val out = MaskRefine.guided(rgb, mask, w, h, radius = 4)
        // No edge to follow: the mask is only smoothed.
        assertEquals(1f, out[10 * w + 2], 1e-3f)
        assertEquals(0f, out[10 * w + w - 3], 1e-3f)
        assertTrue(abs(out[10 * w + w / 2] - 0.5f) < 0.2f)
    }

    @Test
    fun `box mean at the border uses only what is inside`() {
        val a = FloatArray(5 * 3) { 1f }
        val m = MaskRefine.box(a, 5, 3, 2)
        assertTrue(m.all { abs(it - 1f) < 1e-6f })
    }
}
