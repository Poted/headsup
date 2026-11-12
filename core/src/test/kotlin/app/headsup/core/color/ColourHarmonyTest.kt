package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColourHarmonyTest {
    private fun hueOf(rgb: FloatArray): Float {
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val v = maxOf(r, g, b); val c = v - minOf(r, g, b)
        var h = when (v) { r -> 60f * ((g - b) / c); g -> 60f * (2f + (b - r) / c); else -> 60f * (4f + (r - g) / c) }
        if (h < 0f) h += 360f
        return h
    }

    @Test
    fun complementaryPullsColoursTowardsTheKeyAndItsOpposite() {
        // Orange and teal-blue: a green leaf turns towards one of them.
        val grade = ColourHarmony.grade(ColourHarmony.Scheme.COMPLEMENTARY, 30f, 1f)
        val green = floatArrayOf(0.3f, 0.6f, 0.2f)
        val before = hueOf(green)
        ColourMixer.apply(grade.mixer, green)
        val after = hueOf(green)
        val toBlue = kotlin.math.abs(after - 210f) < kotlin.math.abs(before - 210f)
        val toOrange = kotlin.math.abs(after - 30f) < kotlin.math.abs(before - 30f)
        assertTrue(toBlue || toOrange, "green $before -> $after")
    }

    @Test
    fun keyColourStaysAndSkinMovesLittle() {
        val grade = ColourHarmony.grade(ColourHarmony.Scheme.COMPLEMENTARY, 280f, 1f)
        val orange = ColourMixer.index(ColourMixer.Band.ORANGE, ColourMixer.Property.HUE)
        assertTrue(kotlin.math.abs(grade.mixer[orange]) <= 0.25f)
        val purple = ColourMixer.index(ColourMixer.Band.PURPLE, ColourMixer.Property.HUE)
        assertTrue(kotlin.math.abs(grade.mixer[purple]) < 0.15f)
    }

    @Test
    fun splitToningUsesKeyInHighlightsAndPartnerInShadows() {
        val grade = ColourHarmony.grade(ColourHarmony.Scheme.COMPLEMENTARY, 30f, 1f)
        // Orange key: the vectorscope's orange (~140°) in the highlights, its opposite in the shadows.
        assertEquals(ColourHarmony.vectorscope(30f), grade.highlightHue, 0.01f)
        assertTrue(grade.highlightHue in 110f..170f, "${grade.highlightHue}")
        assertTrue(grade.shadowHue in 280f..340f, "${grade.shadowHue}")
    }

    @Test
    fun noneAtZeroAmount() {
        val grade = ColourHarmony.grade(ColourHarmony.Scheme.TRIADIC, 100f, 0f)
        assertTrue(grade.mixer.all { it == 0f })
        assertEquals(0f, grade.toneAmount)
    }
}
