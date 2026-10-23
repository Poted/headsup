package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradientMaskTest {
    private val w = 1920f
    private val h = 1080f

    @Test
    fun `full above the line, none below, half on it`() {
        val mask = GradientMask(position = 0.5f, softness = 0.2f)
        assertEquals(1f, mask.weight(960f, 100f, w, h))
        assertEquals(0f, mask.weight(960f, 1000f, w, h))
        assertEquals(0.5f, mask.weight(960f, 540f, w, h), 1e-4f)
    }

    @Test
    fun `tilting moves the line up on one side and down on the other`() {
        val mask = GradientMask(position = 0.5f, softness = 0.05f, angleDegrees = 10f)
        // Clockwise tilt: the line is lower on the right, so a point just under the centre line
        // on the right is still in the mask, and on the left it is not.
        assertTrue(mask.weight(1800f, 600f, w, h) > 0.9f)
        assertTrue(mask.weight(120f, 600f, w, h) < 0.1f)
    }
}
