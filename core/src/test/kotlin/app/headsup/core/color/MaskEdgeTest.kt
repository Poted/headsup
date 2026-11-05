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
        assertTrue(fuller > none * 1.08f, "fuller $fuller vs $none")
        assertTrue(tighter < none * 0.92f, "tighter $tighter vs $none")
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

    @Test
    fun `fuller following the picture stops at its edge`() {
        val w = 400
        val h = 100
        // The picture: dark left of x = 57, light from it on; the mask starts at 60 (3 px short).
        val rgb = FloatArray(w * h * 3) { i -> if ((i / 3) % w >= 57) 0.9f else 0.1f }
        val mask = FloatArray(w * h) { i -> if (i % w >= 60) 1f else 0f }
        val out = MaskEdge.shapeGuided(mask, w, h, 1f, rgb)
        // Reach 4 px: the light pixels are taken in, the dark one as near is not.
        assertTrue(out[50 * w + 57] > 0.5f, "light ${out[50 * w + 57]}")
        assertTrue(out[50 * w + 56] < 0.5f, "dark ${out[50 * w + 56]}")
        // Tighter: only what looks like the outside goes, so nothing here.
        val tighter = MaskEdge.shapeGuided(mask, w, h, -1f, rgb)
        assertTrue(tighter[50 * w + 61] > 0.5f, "kept ${tighter[50 * w + 61]}")
    }
}
