package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ToneCurveTest {
    @Test
    fun `no points is a straight line and points are passed through`() {
        assertTrue(abs(ToneCurve.at(emptyList(), 0.3f) - 0.3f) < 1e-5f)
        val lift = listOf(0.5f, 0.6f)
        assertTrue(abs(ToneCurve.at(lift, 0.5f) - 0.6f) < 1e-4f)
        assertTrue(ToneCurve.at(lift, 0.25f) > 0.25f)
    }

    @Test
    fun `a curve never turns back`() {
        val s = listOf(0.25f, 0.15f, 0.75f, 0.85f)
        var last = -1f
        for (i in 0..100) {
            val v = ToneCurve.at(s, i / 100f)
            assertTrue(v >= last - 1e-5f, "at $i: $v < $last")
            last = v
        }
    }

    @Test
    fun `cinematic contrast lifts and flattens the shadows, steepens the middle, softens the top`() {
        val c = ToneCurve.cinematic(1f)
        fun at(x: Float) = ToneCurve.at(c, x)
        // No crushed black, no hard white.
        kotlin.test.assertTrue(at(0f) > 0.05f && at(1f) < 0.95f)
        // A flat toe: the shadows change slowly…
        val toe = (at(0.15f) - at(0.02f)) / 0.13f
        // …the middle has contrast, steeper than straight…
        val middle = (at(0.6f) - at(0.4f)) / 0.2f
        kotlin.test.assertTrue(toe < 0.6f && middle > 1.05f, "toe $toe middle $middle")
        // …and the curve never turns back.
        kotlin.test.assertTrue((0 until 100).all { at(it / 100f) <= at((it + 1) / 100f) + 1e-6f })
        // Strength: 0 is straight, half is half way.
        kotlin.test.assertTrue(ToneCurve.cinematic(0f).isEmpty())
        val half = ToneCurve.scaled(c, 0.5f)
        kotlin.test.assertEquals(0.035f, ToneCurve.at(half, 0f), 1e-4f)
    }
}
