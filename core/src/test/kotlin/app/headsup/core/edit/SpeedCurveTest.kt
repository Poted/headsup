package app.headsup.core.edit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedCurveTest {
    @Test
    fun `no curve is the steady speed`() {
        assertEquals(5000L, SpeedCurve.timelineOf(10_000, 10_000, 2f, emptyList()))
        assertEquals(10_000L, SpeedCurve.sourceOf(5000, 10_000, 2f, emptyList()))
    }

    @Test
    fun `a slow middle makes the clip longer, and times map both ways`() {
        val hero = SpeedCurve.Preset.HERO.points
        val length = SpeedCurve.timelineOf(10_000, 10_000, 1f, hero)
        assertTrue(length > 13_000, "$length")
        for (src in listOf(0L, 2_500L, 5_000L, 7_777L, 10_000L)) {
            val tl = SpeedCurve.timelineOf(src, 10_000, 1f, hero)
            assertTrue(abs(SpeedCurve.sourceOf(tl, 10_000, 1f, hero) - src) < 30, "$src")
        }
        assertEquals(0.25f, SpeedCurve.factor(hero, 0.52f), 1e-4f)
        assertEquals(1f, SpeedCurve.factor(hero, 0.1f), 1e-4f)
    }

    @Test
    fun `steady pieces add up to the curve's length`() {
        val curve = SpeedCurve.Preset.SLOW_OUT.points
        val pieces = SpeedCurve.pieces(8_000, 1.5f, curve)
        assertEquals(0L, pieces.first().first)
        assertEquals(8_000L, pieces.last().second)
        val total = pieces.sumOf { (a, b, s) -> ((b - a) / s).toDouble() }
        assertEquals(SpeedCurve.timelineOf(8_000, 8_000, 1.5f, curve).toDouble(), total, 40.0)
        assertTrue(pieces.last().third < pieces.first().third)
    }
}
