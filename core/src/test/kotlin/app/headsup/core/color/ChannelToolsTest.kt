package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelToolsTest {
    @Test
    fun `channel mixer swaps red and blue`() {
        val swap = ChannelMixer.NEUTRAL.toMutableList().apply {
            this[0] = -1f; this[2] = 1f // red out = blue in
            this[8] = -1f; this[6] = 1f // blue out = red in
        }
        val c = floatArrayOf(0.8f, 0.4f, 0.1f)
        ChannelMixer.apply(swap, c)
        assertEquals(0.1f, c[0], 1e-6f)
        assertEquals(0.4f, c[1], 1e-6f)
        assertEquals(0.8f, c[2], 1e-6f)
    }

    @Test
    fun `black and white darkens a blue sky when asked`() {
        val sky = floatArrayOf(0.3f, 0.5f, 0.9f)
        val plain = sky.copyOf().also { BlackAndWhite.apply(BlackAndWhite.NEUTRAL.toMutableList().apply { this[0] = 1f }, it) }
        val darkBlue = BlackAndWhite.NEUTRAL.toMutableList().apply { this[0] = 1f; this[1 + ColourMixer.Band.BLUE.ordinal] = -1f }
        val dark = sky.copyOf().also { BlackAndWhite.apply(darkBlue, it) }
        assertEquals(plain[0], plain[2], 1e-6f)
        assertTrue(dark[0] < plain[0] - 0.1f)
        // Off: nothing changes.
        val c = sky.copyOf().also { BlackAndWhite.apply(BlackAndWhite.NEUTRAL, it) }
        assertEquals(0.9f, c[2], 1e-6f)
    }

    @Test
    fun `calibration keeps grey and white, and moves the primaries`() {
        val m = Calibration.matrix(listOf(0.5f, 0.3f, 0f, 0f, -0.4f, 0f))!!
        val white = floatArrayOf(1f, 1f, 1f).also { Calibration.apply(m, it) }
        for (v in white) assertEquals(1f, v, 1e-4f)
        val grey = floatArrayOf(0.4f, 0.4f, 0.4f).also { Calibration.apply(m, it) }
        for (v in grey) assertEquals(0.4f, v, 1e-4f)
        val red = floatArrayOf(0.8f, 0.1f, 0.1f).also { Calibration.apply(m, it) }
        // The red hue turned (towards orange or magenta): green or blue changed.
        assertTrue(kotlin.math.abs(red[1] - 0.1f) > 0.02f || kotlin.math.abs(red[2] - 0.1f) > 0.02f)
        assertEquals(null, Calibration.matrix(Calibration.NEUTRAL))
    }
}
