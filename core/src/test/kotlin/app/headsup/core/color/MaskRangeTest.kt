package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskRangeTest {
    @Test
    fun `oklab of white and black`() {
        val white = MaskRange.oklab(1f, 1f, 1f)
        assertEquals(1f, white[0], 1e-3f)
        assertEquals(0f, white[1], 1e-3f)
        assertEquals(0f, white[2], 1e-3f)
        assertEquals(0f, MaskRange.oklab(0f, 0f, 0f)[0], 1e-4f)
    }

    @Test
    fun `luminance range keeps its band and fades past it`() {
        val r = LumaRange(low = 0.4f, high = 0.6f, soft = 0.1f)
        assertEquals(1f, MaskRange.lumaWeight(r, 0.5f), 1e-4f)
        assertEquals(0f, MaskRange.lumaWeight(r, 0.2f), 1e-4f)
        assertEquals(0f, MaskRange.lumaWeight(r, 0.8f), 1e-4f)
        assertEquals(0.5f, MaskRange.lumaWeight(r, 0.35f), 1e-3f)
    }

    @Test
    fun `colour range keeps the colour in shade and leaves other hues`() {
        val sky = ColourRange(0.35f, 0.55f, 0.85f, tolerance = 0.35f)
        assertEquals(1f, MaskRange.colourWeight(sky, 0.35f, 0.55f, 0.85f), 1e-4f)
        // The same blue a little darker is still the sky; orange and green are not.
        assertTrue(MaskRange.colourWeight(sky, 0.28f, 0.45f, 0.72f) > 0.9f)
        assertEquals(0f, MaskRange.colourWeight(sky, 0.9f, 0.5f, 0.15f), 1e-4f)
        assertEquals(0f, MaskRange.colourWeight(sky, 0.3f, 0.6f, 0.25f), 1e-4f)
    }

    @Test
    fun `a range mask is its ranges, inverted when the mask is`() {
        val m = ColourMask(MaskKind.RANGE, luma = LumaRange(0.5f, 1f, 0.05f))
        assertEquals(1f, MaskRange.cover(m, m.weight(0f, 0f, 1f, 1f), 0.9f, 0.9f, 0.9f), 1e-4f)
        assertEquals(0f, MaskRange.cover(m, 1f, 0.1f, 0.1f, 0.1f), 1e-4f)
        assertEquals(1f, MaskRange.cover(m.copy(inverted = true), 1f, 0.1f, 0.1f, 0.1f), 1e-4f)
        // A shape narrowed by a range.
        val oval = ColourMask(MaskKind.ELLIPSE, luma = LumaRange(0.5f, 1f, 0.05f))
        assertEquals(0.5f, MaskRange.cover(oval, 0.5f, 0.9f, 0.9f, 0.9f), 1e-4f)
        assertEquals(0f, MaskRange.cover(oval, 0.5f, 0.1f, 0.1f, 0.1f), 1e-4f)
    }

    @Test
    fun `joined masks combine into their layer in order`() {
        val joins = listOf(MaskJoin.NEW, MaskJoin.ADD, MaskJoin.SUBTRACT, MaskJoin.NEW)
        val out = MaskRange.joined(floatArrayOf(0.2f, 0.7f, 0.5f, 0.9f), joins)
        // (0.2 + 0.7) − 0.5: max then times (1 − 0.5).
        assertEquals(0.35f, out[0], 1e-5f)
        assertEquals(0f, out[1])
        assertEquals(0f, out[2])
        assertEquals(0.9f, out[3], 1e-5f)
        val both = MaskRange.joined(floatArrayOf(0.8f, 0.5f), listOf(MaskJoin.NEW, MaskJoin.INTERSECT))
        assertEquals(0.4f, both[0], 1e-5f)
        // The first mask is a layer whatever it says.
        assertEquals(0.3f, MaskRange.joined(floatArrayOf(0.3f), listOf(MaskJoin.ADD))[0], 1e-5f)
        val masks = listOf(ColourMask(), ColourMask(join = MaskJoin.ADD), ColourMask(join = MaskJoin.SUBTRACT), ColourMask())
        assertEquals(listOf(0, 0, 0, 3), masks.indices.map { MaskRange.layerOf(masks, it) })
    }

    @Test
    fun `shader params say when there is no range`() {
        val (a, b) = ColourMask().rangeParams()
        assertTrue(a[0] < -0.5f && b[3] == 0f && a[3] == 0f)
        val (c, d) = ColourMask(luma = LumaRange(0.2f, 0.8f, 0.1f), colour = ColourRange(1f, 0f, 0f), join = MaskJoin.INTERSECT).rangeParams()
        assertEquals(0.2f, c[0]); assertEquals(3f, c[3]); assertTrue(d[3] > 0f)
    }
}
