package app.headsup.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals

class CropTest {
    private val w = 4000f
    private val h = 3000f

    /** The picture point shown at a frame fraction, for [r]'s own frame. */
    private fun at(r: Reframe, fx: Float, fy: Float): Pair<Float, Float> {
        val (ow, oh) = r.outputSize(w, h)
        return r.sourceOf(fx * ow, fy * oh, ow, oh, w, h)
    }

    @Test
    fun rightHalfOfTheWholePhoto() {
        val r = Reframe().cropped(0.5f, 0f, 1f, 1f, w, h)
        assertEquals(2000f / 3000f, r.aspect!!, 1e-3f)
        val (x0, y0) = at(r, 0f, 0f)
        val (x1, y1) = at(r, 1f, 1f)
        assertEquals(2000f, x0, 2f); assertEquals(0f, y0, 2f)
        assertEquals(4000f, x1, 2f); assertEquals(3000f, y1, 2f)
    }

    @Test
    fun cropOfACropKeepsWhatWasInside() {
        val first = Reframe().cropped(0.1f, 0.2f, 0.9f, 0.8f, w, h)
        val (ax, ay) = at(first, 0.25f, 0.5f)
        val (bx, by) = at(first, 1f, 1f)
        val second = first.cropped(0.25f, 0.5f, 1f, 1f, w, h)
        val (cx, cy) = at(second, 0f, 0f)
        val (dx, dy) = at(second, 1f, 1f)
        assertEquals(ax, cx, 2f); assertEquals(ay, cy, 2f)
        assertEquals(bx, dx, 2f); assertEquals(by, dy, 2f)
    }

    @Test
    fun turnedAQuarterStillCutsWhatIsShown() {
        val turned = Reframe().turned(1)
        val (ax, ay) = at(turned, 0f, 0.5f)
        val (bx, by) = at(turned, 1f, 1f)
        val r = turned.cropped(0f, 0.5f, 1f, 1f, w, h)
        val (cx, cy) = at(r, 0f, 0f)
        val (dx, dy) = at(r, 1f, 1f)
        assertEquals(ax, cx, 3f); assertEquals(ay, cy, 3f)
        assertEquals(bx, dx, 3f); assertEquals(by, dy, 3f)
    }
}
