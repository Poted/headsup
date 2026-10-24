package app.headsup.core.raw

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class HueSatMapTest {
    private fun map(hueShift: Float, satScale: Float) =
        HueSatMap(6, 2, 1, FloatArray(6 * 2 * 3) { i -> when (i % 3) { 0 -> hueShift; 1 -> satScale; else -> 1f } })

    @Test
    fun `an identity table keeps colours`() {
        val rgb = floatArrayOf(0.6f, 0.3f, 0.1f)
        map(0f, 1f).apply(rgb)
        assertTrue(abs(rgb[0] - 0.6f) < 1e-4f && abs(rgb[1] - 0.3f) < 1e-4f && abs(rgb[2] - 0.1f) < 1e-4f, rgb.joinToString())
    }

    @Test
    fun `a hue shift of 120 degrees turns red to green and saturation scales`() {
        val rgb = floatArrayOf(0.8f, 0.1f, 0.1f)
        map(120f, 1f).apply(rgb)
        assertTrue(rgb[1] > rgb[0] && rgb[1] > rgb[2], rgb.joinToString())
        val grey = floatArrayOf(0.8f, 0.1f, 0.1f)
        map(0f, 0f).apply(grey)
        assertTrue(abs(grey[0] - grey[1]) < 1e-4f && abs(grey[1] - grey[2]) < 1e-4f, grey.joinToString())
    }
}
