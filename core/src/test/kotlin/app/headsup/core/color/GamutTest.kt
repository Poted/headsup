package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamutTest {
    @Test
    fun `greys stay grey`() {
        val rgb = floatArrayOf(0.5f, 0.5f, 0.5f)
        Gamut.bt2020ToBt709(rgb)
        for (v in rgb) assertEquals(0.5f, v, 2e-3f)
    }

    @Test
    fun `colours shown in BT 709 get more saturated`() {
        val rgb = floatArrayOf(0.6f, 0.45f, 0.4f)
        Gamut.bt2020ToBt709(rgb)
        assertTrue(rgb[0] > 0.6f && rgb[2] < 0.4f)
    }

    @Test
    fun `wide gamut only applies without a LUT`() {
        val plain = LutBaker.bake(null, ColorGrade(), size = 5)
        val wide = LutBaker.bake(null, ColorGrade(), size = 5, wideGamut = true)
        // Grey stays grey (within one code value).
        for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((plain[2][2][2] shr shift and 0xFF) - (wide[2][2][2] shr shift and 0xFF)) <= 1)
        assertTrue(plain[4][2][1] != wide[4][2][1])
    }
}
