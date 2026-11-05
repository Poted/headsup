package app.headsup.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensTest {
    @Test
    fun `the middle stays and the corners stay inside`() {
        for (d in listOf(-1f, -0.4f, 0.4f, 1f)) {
            val k = Lens.k(d)
            val (mx, my) = Lens.sourceOf(0.5f, 0.5f, k, 1.5f)
            assertEquals(0.5f, mx, 1e-6f)
            assertEquals(0.5f, my, 1e-6f)
            val (cx, cy) = Lens.sourceOf(1f, 1f, k, 1.5f)
            assertTrue(cx in 0f..1.0001f && cy in 0f..1.0001f, "corner $cx $cy for $d")
        }
        // A barrel corrected: the corners sample further out than the edges' middles do, relatively.
        val k = Lens.k(1f)
        val edge = Lens.sourceOf(1f, 0.5f, k, 1f).first - 0.5f
        val corner = Lens.sourceOf(1f, 1f, k, 1f).first - 0.5f
        assertTrue(corner > edge)
    }

    @Test
    fun `the warp carries the lens amounts`() {
        val w = Reframe(distortion = 0.4f, lensVignette = 0.5f).warp!!
        assertEquals(11, w.size)
        assertEquals(Lens.k(0.4f).toDouble(), w[Lens.K], 1e-6)
        assertEquals(0.5, w[Lens.VIGNETTE], 1e-6)
        assertEquals(null, Reframe().warp)
    }
}
