package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CineTest {
    private fun graded(grade: ColorGrade, r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(r, g, b).also { LutBaker.apply(null, grade, it) }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `roll-off leaves mid-tones and bends white below full`() {
        val grade = ColorGrade(rolloff = 0.8f)
        val mid = graded(grade, 0.4f, 0.4f, 0.4f)
        assertEquals(0.4f, mid[1], 1e-4f)
        val white = graded(grade, 1f, 1f, 1f)
        assertEquals(0.8f, white[1], 0.01f)
        // Still increasing: a brighter input stays brighter.
        assertTrue(graded(grade, 0.95f, 0.95f, 0.95f)[1] < white[1])
    }

    @Test
    fun `split toning cools shadows and warms highlights but spares skin`() {
        val grade = ColorGrade(toneAmount = 1f)
        val shadow = graded(grade, 0.15f, 0.15f, 0.15f)
        assertTrue(shadow[2] > shadow[0], "shadows towards teal")
        val highlight = graded(grade, 0.85f, 0.85f, 0.85f)
        assertTrue(highlight[0] > highlight[2], "highlights towards orange")
        val skin = floatArrayOf(0.8f, 0.6f, 0.5f)
        val toned = graded(grade, skin[0], skin[1], skin[2])
        assertTrue(kotlin.math.abs(toned[2] - skin[2]) < 0.03f, "skin barely moves")
    }

    @Test
    fun `tame calms green more than skin`() {
        val grade = ColorGrade(tame = 1f)
        val green = graded(grade, 0.2f, 0.7f, 0.2f)
        assertTrue(green[1] - green[0] < 0.5f * 0.8f, "green less saturated")
        val skin = graded(grade, 0.8f, 0.6f, 0.5f)
        assertEquals(0.5f, skin[2], 0.02f)
    }

    @Test
    fun `assistant brings a bright clipping frame down and adds a shoulder`() {
        val bright = IntArray(1000) { if (it < 100) argb(255, 255, 255) else argb(200, 200, 200) }
        val stats = CineAssistant.analyse(bright)
        assertTrue(stats.clipped > 0.09f)
        val s = CineAssistant.suggest(stats, CineStyle.TEAL_ORANGE)
        assertTrue(s.grade.exposure < 0f)
        assertTrue(s.grade.rolloff > 0.7f)
    }

    @Test
    fun `assistant tames more on a green frame`() {
        val green = CineAssistant.analyse(IntArray(1000) { argb(60, 170, 60) })
        val grey = CineAssistant.analyse(IntArray(1000) { argb(120, 120, 120) })
        assertTrue(green.foliage > 0.9f)
        assertTrue(CineAssistant.suggest(green, CineStyle.WARM_FILM).grade.tame > CineAssistant.suggest(grey, CineStyle.WARM_FILM).grade.tame)
    }
}
