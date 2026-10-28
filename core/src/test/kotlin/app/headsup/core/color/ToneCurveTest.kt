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
}
