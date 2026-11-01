package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskFeatherTest {
    @Test
    fun `a hard edge becomes a gradual band, the rest stays`() {
        val w = 80; val h = 20
        val map = FloatArray(w * h) { if (it % w < 40) 1f else 0f }
        val soft = MaskFeather.apply(map, w, h, 6f)
        val row = 10 * w
        assertEquals(1f, soft[row + 5], 1e-3f)
        assertEquals(0f, soft[row + 75], 1e-3f)
        assertEquals(0.5f, (soft[row + 39] + soft[row + 40]) / 2f, 0.05f)
        // Gradual: several steps between in and out, falling steadily.
        assertTrue((34..46).all { soft[row + it] >= soft[row + it + 1] - 1e-6f })
        assertTrue(soft[row + 35] in 0.6f..0.99f && soft[row + 44] in 0.01f..0.4f)
        assertTrue(MaskFeather.apply(map, w, h, 0f) === map)
    }

    @Test
    fun `soften makes the edge gradual only where painted`() {
        val w = 80; val h = 40
        val base = FloatArray(w * h) { if (it % w < 40) 1f else 0f }
        // A soften stroke down the edge in the top half only.
        val stroke = BrushStroke(listOf(40f / w, 2f / h, 40f / w, 15f / h), radius = 0.15f, flow = 1f, soften = true)
        val out = BrushMask.rasterize(listOf(stroke), w, h, 0.5f, start = base)
        val top = 8 * w
        val bottom = 35 * w
        // Top: a gradual band across the edge.
        assertTrue(out[top + 37] in 0.55f..0.99f && out[top + 42] in 0.01f..0.45f, "${out[top + 37]} ${out[top + 42]}")
        // Bottom (not painted): still a sharp cut.
        assertEquals(1f, out[bottom + 39], 1e-6f)
        assertEquals(0f, out[bottom + 40], 1e-6f)
        val soft = BrushMask.softened(listOf(stroke), w, h)!!
        assertTrue(soft[top + 40] > 0.5f && soft[bottom + 40] == 0f)
    }
}
