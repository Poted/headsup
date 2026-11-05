package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HueCurvesTest {
    private fun hsv(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val v = maxOf(r, g, b)
        val c = v - minOf(r, g, b)
        val h = when {
            c == 0f -> 0f
            v == r -> 60f * ((g - b) / c)
            v == g -> 60f * (2f + (b - r) / c)
            else -> 60f * (4f + (r - g) / c)
        }.let { if (it < 0f) it + 360f else it }
        return Triple(h, if (v > 0f) c / v else 0f, v)
    }

    @Test
    fun `hue vs sat pales one hue and leaves the others`() {
        // Blue (hue 240) at -0.8 saturation; red untouched.
        val curves = listOf(emptyList(), listOf(0.5f, 0f, 240f / 360f, -0.8f, 0.9f, 0f), emptyList(), emptyList())
        val blue = floatArrayOf(0.2f, 0.3f, 0.8f)
        val before = hsv(blue[0], blue[1], blue[2]).second
        HueCurves.apply(curves, blue)
        assertTrue(hsv(blue[0], blue[1], blue[2]).second < before * 0.4f)
        val red = floatArrayOf(0.8f, 0.2f, 0.2f)
        HueCurves.apply(curves, red)
        assertEquals(0.8f, red[0], 1e-3f)
        assertEquals(0.2f, red[1], 1e-3f)
    }

    @Test
    fun `hue vs hue turns the hue and wraps round the wheel`() {
        // A single point near red: the whole wheel turns by it (periodic).
        val curves = listOf(listOf(0f, 0.5f), emptyList(), emptyList(), emptyList())
        val c = floatArrayOf(0.8f, 0.2f, 0.2f)
        HueCurves.apply(curves, c)
        assertEquals(30f, hsv(c[0], c[1], c[2]).first, 1f)
    }

    @Test
    fun `lum vs sat takes colour out of the shadows`() {
        val curves = listOf(emptyList(), emptyList(), emptyList(), listOf(0f, -1f, 0.3f, -1f, 0.5f, 0f, 1f, 0f))
        val dark = floatArrayOf(0.15f, 0.05f, 0.05f)
        HueCurves.apply(curves, dark)
        assertEquals(dark[0], dark[1], 1e-3f)
        val bright = floatArrayOf(0.9f, 0.6f, 0.6f)
        HueCurves.apply(curves, bright)
        assertTrue(bright[0] - bright[1] > 0.25f)
    }
}
