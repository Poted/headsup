package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColourDensityTest {
    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun `greys stay, saturated colours get deeper or paler`() {
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        ColourDensity.apply(1f, grey)
        assertEquals(0.5f, grey[0], 1e-6f)

        val red = floatArrayOf(0.8f, 0.2f, 0.15f)
        val deeper = red.copyOf().also { ColourDensity.apply(0.6f, it) }
        assertTrue(luma(deeper) < luma(red))
        // Still red: red stays the largest channel, by more of the whole than before.
        assertTrue(deeper[0] > deeper[1] && deeper[0] > deeper[2])
        val paler = red.copyOf().also { ColourDensity.apply(-0.6f, it) }
        assertTrue(luma(paler) > luma(red))
        assertTrue(paler[0] - paler[2] < red[0] - red[2])

        // A muted colour changes less than a loud one.
        val muted = floatArrayOf(0.55f, 0.5f, 0.48f)
        val m2 = muted.copyOf().also { ColourDensity.apply(0.6f, it) }
        assertTrue(luma(muted) - luma(m2) < luma(red) - luma(deeper))
    }
}
