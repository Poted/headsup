package app.headsup.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameKeysTest {
    @Test
    fun aKenBurnsMoveEasesFromStartToEnd() {
        val keys = listOf(FrameKey(0L, 1f, 0.5f, 0.5f), FrameKey(4000L, 1.5f, 0.3f, 0.5f))
        val base = Reframe(aspect = 9f / 16f)
        assertEquals(1f, FrameKeys.at(base, keys, -100L).zoom, 1e-4f)
        assertEquals(1.25f, FrameKeys.at(base, keys, 2000L).zoom, 1e-4f)
        assertEquals(0.3f, FrameKeys.at(base, keys, 5000L).centerX, 1e-4f)
        // Eased: a quarter of the way in time is less than a quarter of the way.
        assertTrue(FrameKeys.at(base, keys, 1000L).zoom < 1.125f)
        // The rest of the framing stays.
        assertEquals(9f / 16f, FrameKeys.at(base, keys, 1000L).aspect!!, 1e-4f)
    }

    @Test
    fun followingGlidesOverShake() {
        val times = LongArray(30) { it * 100L }
        // The subject walks right, with jitter.
        val xs = FloatArray(30) { 0.3f + it * 0.01f + if (it % 2 == 0) 0.03f else -0.03f }
        val ys = FloatArray(30) { 0.5f }
        val keys = FrameKeys.following(times, xs, ys, 1f)
        assertTrue(keys.size in 6..9)
        // Steadily to the right, the jitter gone.
        for (k in 1 until keys.size) assertTrue(keys[k].centerX >= keys[k - 1].centerX - 0.005f)
    }

    @Test
    fun theNearestKeyIsTheOneSet() {
        val keys = listOf(FrameKey(0L, 1f, 0.5f, 0.5f), FrameKey(4000L, 1f, 0.5f, 0.5f))
        val set = FrameKeys.setNearest(keys, 3500L, Reframe(zoom = 2f, centerX = 0.7f))
        assertEquals(1f, set[0].zoom, 1e-4f)
        assertEquals(2f, set[1].zoom, 1e-4f)
        assertEquals(4000L, set[1].timeMs)
    }
}
