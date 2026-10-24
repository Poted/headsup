package app.headsup.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextAnimationTest {
    private val item = TextItem(id = 1, text = "TATRY", startMs = 1_000, lengthMs = 4_000)

    @Test
    fun `shown only within its time`() {
        assertNull(TextAnimation.frame(item, 999))
        assertNotNull(TextAnimation.frame(item, 1_000))
        assertNull(TextAnimation.frame(item, 5_000))
    }

    @Test
    fun `fades in and out`() {
        assertEquals(0f, TextAnimation.frame(item, 1_000)!!.alpha)
        assertEquals(0.5f, TextAnimation.frame(item, 1_250)!!.alpha, 0.01f)
        assertEquals(1f, TextAnimation.frame(item, 3_000)!!.alpha)
        assertTrue(TextAnimation.frame(item, 4_900)!!.alpha < 0.3f)
    }

    @Test
    fun `typing shows one letter after another`() {
        val typed = item.copy(animIn = TextAnim.TYPE, animOut = TextAnim.NONE)
        assertEquals(0, TextAnimation.frame(typed, 1_000)!!.shown)
        assertEquals(2, TextAnimation.frame(typed, 1_000 + 2 * TextAnimation.CHAR_MS)!!.shown)
        assertEquals(5, TextAnimation.frame(typed, 3_000)!!.shown)
    }

    @Test
    fun `hud decodes the next letter and blinks a cursor`() {
        val hud = item.copy(animIn = TextAnim.HUD, animOut = TextAnim.HUD)
        val typing = TextAnimation.frame(hud, 1_100)!!
        assertTrue(typing.decoding)
        val done = TextAnimation.frame(hud, 2_500)!!
        assertEquals(5, done.shown)
        assertEquals(false, done.decoding)
        // Deleted again at the end.
        assertTrue(TextAnimation.frame(hud, 4_950)!!.shown < 5)
    }

    @Test
    fun `pop starts small and ends at full size`() {
        val pop = item.copy(animIn = TextAnim.POP)
        assertTrue(TextAnimation.frame(pop, 1_000)!!.scale < 0.7f)
        assertEquals(1f, TextAnimation.frame(pop, 3_000)!!.scale)
    }

    @Test
    fun `a short text gets at most half its time for each animation`() {
        val short = item.copy(lengthMs = 400, animMs = 1_000)
        assertEquals(200L, TextAnimation.durationOf(short, TextAnim.FADE))
    }
}
