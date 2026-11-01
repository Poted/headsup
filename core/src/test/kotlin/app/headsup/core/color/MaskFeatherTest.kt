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
}
