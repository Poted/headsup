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
}
