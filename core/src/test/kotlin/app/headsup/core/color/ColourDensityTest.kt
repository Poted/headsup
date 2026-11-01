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

    @Test
    fun `a look is fitted to the clip only where it needs it`() {
        fun stats(median: Float, clipped: Float = 0f, foliage: Float = 0f, crushed: Float = 0f) =
            FrameStats(low = 0.05f, median = median, high = 0.9f, clipped = clipped, crushed = crushed, colourfulness = 0.05f, skin = 0f, foliage = foliage, warmth = 0f)
        // A well exposed, calm clip needs nothing.
        assertTrue(CineAssistant.fit(stats(0.4f)) == ColorGrade())
        // Too dark: a little brighter; not for a look meant to stay dark.
        assertTrue(CineAssistant.fit(stats(0.12f)).exposure in 0.1f..0.5f)
        assertEquals(0f, CineAssistant.fit(stats(0.12f), keepDark = true).exposure)
        // Clipping highlights and loud greens: a softer shoulder, calmer colours.
        val f = CineAssistant.fit(stats(0.4f, clipped = 0.03f, foliage = 0.4f))
        assertTrue(f.rolloff > 0.2f && f.tame > 0.3f)
    }
}
