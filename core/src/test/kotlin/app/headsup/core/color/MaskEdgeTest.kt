package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskEdgeTest {
    private val w = 200
    private val h = 100

    /** A disc of radius 30 with a soft rim, as a model would find it. */
    private val disc = FloatArray(w * h) { i ->
        val d = kotlin.math.hypot((i % w) - 100f, (i / w) - 50f)
        ((35f - d) / 10f).coerceIn(0f, 1f)
    }

    private fun area(m: FloatArray) = m.count { it > 0.5f }

    @Test
    fun `edge moves the boundary out and in`() {
        val none = area(MaskEdge.shape(disc, w, h, 0f))
        val fuller = area(MaskEdge.shape(disc, w, h, 1f))
        val tighter = area(MaskEdge.shape(disc, w, h, -1f))
        assertTrue(fuller > none * 1.15f, "fuller $fuller vs $none")
        assertTrue(tighter < none * 0.85f, "tighter $tighter vs $none")
        // The centre stays in, far away stays out.
        assertEquals(1f, MaskEdge.shape(disc, w, h, -1f)[50 * w + 100], 1e-3f)
        assertEquals(0f, MaskEdge.shape(disc, w, h, 1f)[50 * w + 5], 1e-3f)
    }

    @Test
    fun `edge keeps a square's corners`() {
        // A square 60 px wide: moved out by the same distance on all sides, its corner still square.
        val sq = FloatArray(w * h) { i -> if ((i % w) in 70 until 130 && (i / w) in 20 until 80) 1f else 0f }
        val out = MaskEdge.shape(sq, w, h, 0.4f)
        val shift = (0.4f * MaskEdge.REACH * w).toInt()
        // Just outside the old corner, diagonally, within the shift: now in.
        assertTrue(out[(20 - shift / 2) * w + (70 - shift / 2)] > 0.5f)
        // The side moved out by about the shift.
        assertTrue(out[50 * w + (70 - shift + 1)] > 0.5f)
        assertTrue(out[50 * w + (70 - shift - 2)] < 0.5f)
    }
}
