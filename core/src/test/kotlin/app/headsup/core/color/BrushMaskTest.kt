package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushMaskTest {
    private fun at(map: FloatArray, w: Int, x: Int, y: Int) = map[y * w + x]

    @Test
    fun `a stroke covers its path and fades at its edge`() {
        val stroke = BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f), radius = 0.1f, softness = 0.5f)
        val map = BrushMask.rasterize(listOf(stroke), 100, 100)
        assertEquals(1f, at(map, 100, 50, 50), 1e-4f)
        assertTrue(at(map, 100, 50, 58) in 0.01f..0.99f)
        assertEquals(0f, at(map, 100, 50, 80))
        assertEquals(0f, at(map, 100, 5, 50))
    }

    @Test
    fun `flow builds up over strokes and erasing takes it away`() {
        val once = BrushStroke(listOf(0.5f, 0.5f), radius = 0.2f, softness = 0f, flow = 0.5f)
        val one = BrushMask.rasterize(listOf(once), 50, 50)
        val two = BrushMask.rasterize(listOf(once, once), 50, 50)
        assertEquals(0.5f, at(one, 50, 25, 25), 1e-4f)
        assertEquals(0.75f, at(two, 50, 25, 25), 1e-4f)
        val erased = BrushMask.rasterize(listOf(once.copy(flow = 1f), once.copy(flow = 1f, erase = true)), 50, 50)
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
}
