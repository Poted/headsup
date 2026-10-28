package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushMaskTest {
    private fun at(map: FloatArray, w: Int, x: Int, y: Int) = map[y * w + x]

    @Test
    fun `a stroke covers its path and fades at its edge`() {
        val stroke = BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f), radius = 0.1f)
        val map = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 0.5f)
        assertEquals(1f, at(map, 100, 50, 50), 1e-4f)
        assertTrue(at(map, 100, 50, 58) in 0.01f..0.99f)
        assertEquals(0f, at(map, 100, 50, 80))
        assertEquals(0f, at(map, 100, 5, 50))
    }

    @Test
    fun `flow builds up over strokes and erasing takes it away`() {
        val once = BrushStroke(listOf(0.5f, 0.5f), radius = 0.2f, flow = 0.5f)
        val one = BrushMask.rasterize(listOf(once), 50, 50, softness = 0f)
        val two = BrushMask.rasterize(listOf(once, once), 50, 50, softness = 0f)
        assertEquals(0.5f, at(one, 50, 25, 25), 1e-4f)
        assertEquals(0.75f, at(two, 50, 25, 25), 1e-4f)
        val erased = BrushMask.rasterize(listOf(once.copy(flow = 1f), once.copy(flow = 1f, erase = true)), 50, 50, softness = 0f)
        assertEquals(0f, at(erased, 50, 25, 25), 1e-4f)
    }

    @Test
    fun `painting on a moved mask lands where it shows`() {
        val base = ColourMask(MaskKind.BRUSH)
        assertEquals(0.3f to 0.7f, BrushMask.unmove(0.3f, 0.7f, base, 16f / 9f))
        // Moved right by a tenth: a point there was painted a tenth to the left.
        val moved = base.copy(centerX = 0.6f)
        val (x, y) = BrushMask.unmove(0.6f, 0.5f, moved, 16f / 9f)
        assertTrue(abs(x - 0.5f) < 1e-5f && abs(y - 0.5f) < 1e-5f)
        // Doubled in size: a point 0.2 below the centre was painted 0.1 below.
        val (_, y2) = BrushMask.unmove(0.5f, 0.7f, base.copy(sizeX = 2f, sizeY = 2f), 1f)
        assertEquals(0.6f, y2, 1e-5f)
    }

    @Test
    fun `softness widens the fading edge`() {
        val stroke = BrushStroke(listOf(0.5f, 0.5f), radius = 0.3f)
        val hard = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 0f)
        val soft = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 1f)
        assertTrue(at(soft, 100, 70, 50) < at(hard, 100, 70, 50) - 0.3f)
    }

    @Test
    fun `an edge-finding stroke stops where the colour changes`() {
        // Left half sky blue, right half a dark tree.
        val w = 100
        val h = 100
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            if (x < 50) { rgb[i] = 0.5f; rgb[i + 1] = 0.7f; rgb[i + 2] = 0.95f } else { rgb[i] = 0.1f; rgb[i + 1] = 0.25f; rgb[i + 2] = 0.1f }
        }
        val ref = BrushReference(w, h, rgb, BrushMask.BASE)
        val stroke = BrushStroke(listOf(0.4f, 0.5f), radius = 0.3f, reference = "sky")
        val map = BrushMask.rasterize(listOf(stroke), w, h, softness = 0f, references = mapOf("sky" to ref))
        assertTrue(at(map, w, 45, 50) > 0.9f)
        assertTrue(at(map, w, 55, 50) < 0.05f)
        // Without edge finding the brush goes over the tree.
        val plain = BrushMask.rasterize(listOf(stroke.copy(reference = null)), w, h, softness = 0f)
        assertTrue(at(plain, w, 55, 50) > 0.9f)
    }

    @Test
    fun `place undoes unmove`() {
        val now = MaskKey(0L, 0.6f, 0.4f, 25f, 1.3f, 1.3f)
        val (ux, uy) = BrushMask.unmove(0.7f, 0.2f, now, 1.5f)
        val (x, y) = BrushMask.place(ux, uy, now, 1.5f)
        assertEquals(0.7f, x, 1e-4f)
        assertEquals(0.2f, y, 1e-4f)
    }
}
