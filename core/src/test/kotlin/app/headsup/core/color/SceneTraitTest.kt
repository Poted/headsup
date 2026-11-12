package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneTraitTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A picture made of [parts]: (colour, share of the pixels). */
    private fun picture(vararg parts: Pair<Int, Float>): IntArray {
        val n = 10_000
        val out = IntArray(n)
        var i = 0
        for ((c, share) in parts) repeat((n * share).toInt()) { if (i < n) out[i++] = c }
        while (i < n) out[i++] = parts.last().first
        return out
    }

    @Test
    fun nightWithLampsIsCityLightsNotPeople() {
        // Dark street, warm brick lit by lamps (skin-like hues), a few lamps near white.
        val stats = CineAssistant.analyse(
            picture(argb(250, 245, 230) to 0.01f, argb(120, 70, 45) to 0.25f, argb(25, 22, 20) to 0.74f),
        )
        val traits = stats.traits
        assertTrue(SceneTrait.NIGHT in traits)
        assertTrue(SceneTrait.CITY_LIGHTS in traits)
        assertFalse(SceneTrait.PEOPLE in traits)
        assertFalse(SceneTrait.DAYLIGHT in traits)
    }

    @Test
    fun lowSunIsGolden() {
        val stats = CineAssistant.analyse(
            picture(argb(255, 200, 120) to 0.3f, argb(200, 130, 60) to 0.3f, argb(90, 80, 60) to 0.4f),
        )
        assertTrue(SceneTrait.GOLDEN in stats.traits)
        assertTrue(SceneTrait.WARM in stats.traits)
    }

    @Test
    fun greenWoodsAndBlueSky() {
        val stats = CineAssistant.analyse(
            picture(argb(90, 160, 220) to 0.3f, argb(60, 130, 50) to 0.5f, argb(150, 150, 140) to 0.2f),
        )
        assertTrue(SceneTrait.FOLIAGE in stats.traits)
        assertTrue(SceneTrait.SKY in stats.traits)
        assertTrue(SceneTrait.DAYLIGHT in stats.traits)
    }

    @Test
    fun greyDayIsFlat() {
        val stats = CineAssistant.analyse(picture(argb(150, 150, 152) to 0.5f, argb(110, 112, 110) to 0.5f))
        assertTrue(SceneTrait.FLAT in stats.traits)
        assertFalse(SceneTrait.GOLDEN in stats.traits)
    }

    @Test
    fun peopleFoundByTheModelCount() {
        val stats = CineAssistant.analyse(picture(argb(150, 150, 152) to 1f)).copy(people = 0.1f)
        assertTrue(SceneTrait.PEOPLE in stats.traits)
        assertFalse(SceneTrait.PEOPLE in stats.copy(people = 0f, skin = 0.2f).traits)
    }
}
