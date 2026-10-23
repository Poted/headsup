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

    @Test
    fun `a 9 by 16 reel from landscape is full height, centred, and can be moved sideways`() {
        val r = Reframe(aspect = 9f / 16f)
        val (ow, oh) = r.outputSize(w, h)
        assertEquals(h, oh, 1e-3f)
        assertEquals(h * 9f / 16f, ow, 1e-3f)
        // Output centre shows the picture centre; its left edge is ow/2 left of it.
        assertEquals(w / 2 - ow / 2, r.sourceOf(0f, oh / 2, ow, oh, w, h).first, 1e-2f)
        // Moved fully left, the output starts at the picture's left edge.
        val left = r.copy(centerX = 0f).clamped(ow, oh, w, h)
        assertEquals(0f, left.sourceOf(0f, 0f, ow, oh, w, h).first, 1e-2f)
    }

    @Test
    fun `fill crops a vertical clip to a horizontal frame, fit puts it inside with bars`() {
        val fill = Reframe(aspect = 16f / 9f)
        val (fw, fh) = fill.outputSize(1080f, 1920f)
        assertEquals(1080f, fw, 0.5f)
        assertEquals(607.5f, fh, 0.5f)
        val fit = Reframe(aspect = 16f / 9f, fit = true)
        val (ow, oh) = fit.outputSize(1080f, 1920f)
        assertEquals(3413.3f, ow, 0.5f)
        assertEquals(1920f, oh, 0.5f)
        // At zoom 1 the picture is shown at its own size, centred: its edges are its edges.
        val scale = Reframe.baseScale(ow, oh, 1080f, 1920f) * fit.effectiveZoom(ow, oh, 1080f, 1920f)
        assertEquals(1f, scale, 1e-3f)
        val (x, _) = fit.sourceOf(ow / 2f - 540f, oh / 2f, ow, oh, 1080f, 1920f)
        assertEquals(0f, x, 0.5f)
    }
}
