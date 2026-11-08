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

class TextFollowTest {
    @Test
    fun movesWithThePlaceItFollows() {
        val f = app.headsup.core.edit.TextFollow(7L, listOf(1000L, 2000L), listOf(0.2f, 0.4f), listOf(0.5f, 0.5f), listOf(0f, 10f), listOf(1f, 2f))
        val item = app.headsup.core.edit.TextItem(1L, "Hi", 0L, 5000L, x = 0.3f, y = 0.6f, size = 0.1f, follow = f)
        val id: (Float, Float) -> Pair<Float, Float> = { x, y -> x to y }
        val mid = item.placed(7L, 1500L, id)
        kotlin.test.assertEquals(0.4f, mid.x, 1e-4f)
        kotlin.test.assertEquals(0.6f, mid.y, 1e-4f)
        kotlin.test.assertEquals(5f, mid.angle, 1e-4f)
        kotlin.test.assertEquals(0.15f, mid.size, 1e-4f)
        // Before the first and after the last it holds; another clip leaves it where it is.
        kotlin.test.assertEquals(0.3f, item.placed(7L, 0L, id).x, 1e-4f)
        kotlin.test.assertEquals(0.5f, item.placed(7L, 9000L, id).x, 1e-4f)
        kotlin.test.assertEquals(item, item.placed(8L, 1500L, id))
    }
}
