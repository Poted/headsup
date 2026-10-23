package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColourMaskTest {
    private val w = 1920f
    private val h = 1080f

    @Test
    fun `line - full above, none below, half on it`() {
        val mask = ColourMask(centerY = 0.5f, softness = 0.2f)
        assertEquals(1f, mask.weight(960f, 100f, w, h))
        assertEquals(0f, mask.weight(960f, 1000f, w, h))
        assertEquals(0.5f, mask.weight(960f, 540f, w, h), 1e-4f)
    }

    @Test
    fun `tilting moves the line down on the right`() {
        val mask = ColourMask(centerY = 0.5f, softness = 0.05f, angleDegrees = 10f)
        assertTrue(mask.weight(1800f, 600f, w, h) > 0.9f)
        assertTrue(mask.weight(120f, 600f, w, h) < 0.1f)
    }

    @Test
    fun `oval - inside, outside and inverted`() {
        val mask = ColourMask(MaskKind.ELLIPSE, centerX = 0.25f, centerY = 0.5f, sizeX = 0.4f, sizeY = 0.4f, softness = 0.2f)
        assertEquals(1f, mask.weight(480f, 540f, w, h))
        assertEquals(0f, mask.weight(1500f, 540f, w, h))
        // Edge: radius 0.2 of the height = 216 px from the centre.
        assertEquals(0.5f, mask.weight(480f + 216f, 540f, w, h), 1e-3f)
        assertEquals(0f, mask.copy(inverted = true).weight(480f, 540f, w, h))
    }

    @Test
    fun `transition starts on the previous look and ends on the clip's own`() {
        assertEquals(1f, ColourTransition.fromWeight(0, 2000))
        assertEquals(0.5f, ColourTransition.fromWeight(1000, 2000), 1e-6f)
        assertEquals(0f, ColourTransition.fromWeight(2500, 2000))
        assertEquals(0f, ColourTransition.fromWeight(0, 0))
    }
}
