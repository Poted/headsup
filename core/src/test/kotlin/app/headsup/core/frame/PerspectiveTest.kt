package app.headsup.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerspectiveTest {
    @Test
    fun `the square's corners go to the quad`() {
        val q = Perspective.corners(0.6f, -0.3f)
        val m = Perspective.squareToQuad(q)
        val unit = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        unit.forEachIndexed { i, (x, y) ->
            val (px, py) = Perspective.apply(m, x, y)
            assertEquals(q[i * 2].toDouble(), px, 1e-6)
            assertEquals(q[i * 2 + 1].toDouble(), py, 1e-6)
        }
        val back = Perspective.apply(Perspective.invert(m), q[4].toDouble(), q[5].toDouble())
        assertEquals(1.0, back.first, 1e-6)
        assertEquals(1.0, back.second, 1e-6)
    }

    @Test
    fun `no correction, no warp`() {
        assertNull(Perspective.sourceOf(0f, 0f))
    }

    @Test
    fun `the corrected picture has no empty corners and keeps its middle`() {
        val m = Perspective.sourceOf(0.8f, 0.4f)!!
        for ((x, y) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.5 to 0.0, 0.0 to 0.5)) {
            val (sx, sy) = Perspective.apply(m, x, y)
            assertTrue(sx in -1e-3..1.001 && sy in -1e-3..1.001, "($x, $y) -> ($sx, $sy)")
        }
        val (cx, cy) = Perspective.apply(m, 0.5, 0.5)
        assertEquals(0.5, cx, 0.05)
        assertEquals(0.5, cy, 0.05)
    }

    @Test
    fun `vertical correction widens the top`() {
        // Lines leaning together at the top: the corrected picture takes the top from a narrower span.
        val m = Perspective.sourceOf(1f, 0f)!!
        val topSpan = Perspective.apply(m, 1.0, 0.1).first - Perspective.apply(m, 0.0, 0.1).first
        val bottomSpan = Perspective.apply(m, 1.0, 0.9).first - Perspective.apply(m, 0.0, 0.9).first
        assertTrue(topSpan < bottomSpan)
    }
}
