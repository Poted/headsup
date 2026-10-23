package app.headsup.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReframeTest {
    private val w = 1920f
    private val h = 1080f

    @Test
    fun `identity maps every pixel to itself`() {
        val (x, y) = Reframe().sourceOf(300f, 700f, w, h)
        assertEquals(300f, x, 1e-3f)
        assertEquals(700f, y, 1e-3f)
    }

    @Test
    fun `zoom 2 at a corner shows that quarter`() {
        val r = Reframe(zoom = 2f, centerX = 0.25f, centerY = 0.25f)
        val (x, y) = r.sourceOf(0f, 0f, w, h)
        assertEquals(0f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
        val (cx, cy) = r.sourceOf(w / 2, h / 2, w, h)
        assertEquals(w / 4, cx, 1e-3f)
        assertEquals(h / 4, cy, 1e-3f)
    }

    @Test
    fun `centre cannot leave the picture`() {
        val r = Reframe(zoom = 2f, centerX = 0f, centerY = 1f).clamped(w, h)
        assertEquals(0.25f, r.centerX, 1e-4f)
        assertEquals(0.75f, r.centerY, 1e-4f)
    }

    @Test
    fun `straightening zooms in enough to keep the corners filled`() {
        val r = Reframe(angleDegrees = 5f)
        assertTrue(r.effectiveZoom(w, h) > 1.1f)
        // Every output corner comes from inside the source picture.
        for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
            val (sx, sy) = r.sourceOf(x, y, w, h)
            assertTrue(sx in -0.5f..w + 0.5f && sy in -0.5f..h + 0.5f, "corner ($x, $y) -> ($sx, $sy)")
        }
    }
}
