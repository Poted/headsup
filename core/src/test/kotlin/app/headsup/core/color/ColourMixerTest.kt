package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ColourMixerTest {
    private fun mix(band: ColourMixer.Band, property: ColourMixer.Property, v: Float) =
        ColourMixer.NEUTRAL.toMutableList().apply { set(ColourMixer.index(band, property), v) }

    @Test
    fun `neutral mixer and greys are left alone`() {
        val c = floatArrayOf(0.7f, 0.2f, 0.1f)
        ColourMixer.apply(ColourMixer.NEUTRAL, c)
        assertTrue(abs(c[0] - 0.7f) < 1e-5f && abs(c[1] - 0.2f) < 1e-5f)
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        ColourMixer.apply(mix(ColourMixer.Band.RED, ColourMixer.Property.SATURATION, -1f), grey)
        assertTrue(grey.all { abs(it - 0.5f) < 1e-5f })
    }

    @Test
    fun `desaturating reds greys a red and spares a blue`() {
        val m = mix(ColourMixer.Band.RED, ColourMixer.Property.SATURATION, -1f)
        val red = floatArrayOf(0.8f, 0.1f, 0.1f)
        ColourMixer.apply(m, red)
        assertTrue(abs(red[0] - red[1]) < 0.02f, red.joinToString())
        val blue = floatArrayOf(0.1f, 0.2f, 0.8f)
        ColourMixer.apply(m, blue)
        assertTrue(blue[2] - blue[0] > 0.6f, blue.joinToString())
    }

    @Test
    fun `every look keeps colours in range and mono has no colour`() {
        for (look in Look.entries) {
            val c = floatArrayOf(0.9f, 0.4f, 0.1f)
            Looks.apply(look, 1f, c)
            assertTrue(c.all { it in 0f..1f }, "$look ${c.joinToString()}")
        }
        val c = floatArrayOf(0.9f, 0.4f, 0.1f)
        Looks.apply(Look.MONO, 1f, c)
        assertTrue(abs(c[0] - c[1]) < 1e-5f && abs(c[1] - c[2]) < 1e-5f)
    }
}
