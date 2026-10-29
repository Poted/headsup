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
        val hero = SpeedCurve.Preset.HERO.points(0.4f)
        val length = SpeedCurve.timelineOf(10_000, 10_000, 1f, hero)
        assertTrue(length > 12_000, "$length")
        for (src in listOf(0L, 2_500L, 5_000L, 7_777L, 10_000L)) {
            val tl = SpeedCurve.timelineOf(src, 10_000, 1f, hero)
            assertTrue(abs(SpeedCurve.sourceOf(tl, 10_000, 1f, hero) - src) < 30, "$src")
        }
        assertEquals(0.4f, SpeedCurve.factor(hero, 0.52f), 1e-4f)
        assertEquals(1f, SpeedCurve.factor(hero, 0.1f), 1e-4f)
    }

    @Test
    fun `steady pieces add up to the curve's length`() {
        val curve = SpeedCurve.Preset.SLOW_OUT.points(0.4f)
        val pieces = SpeedCurve.pieces(8_000, 1.5f, curve)
        assertEquals(0L, pieces.first().first)
        assertEquals(8_000L, pieces.last().second)
        val total = pieces.sumOf { (a, b, s) -> ((b - a) / s).toDouble() }
        assertEquals(SpeedCurve.timelineOf(8_000, 8_000, 1.5f, curve).toDouble(), total, 40.0)
        assertTrue(pieces.last().third < pieces.first().third)
    }

    @Test
    fun `slow speeds follow the footage's frames`() {
        // 120 fps on a 30 fps timeline: every frame down to 0.25x.
        assertEquals(0.25f, SpeedCurve.smoothLimit(120f, 30f)!!, 1e-4f)
        assertEquals(0.5f to 0.25f, SpeedCurve.slowSpeeds(120f, 30f))
        // 30 fps footage has none to spare.
        assertEquals(0.6f to 0.4f, SpeedCurve.slowSpeeds(30f, 30f))
        assertEquals(0.25f, SpeedCurve.factor(SpeedCurve.Preset.HERO.points(0.25f), 0.52f), 1e-4f)
    }

    @Test
    fun `flat stretches make one piece`() {
        val pieces = SpeedCurve.pieces(20_000, 1f, SpeedCurve.Preset.HERO.points(0.4f))
        // The steady starts and ends are single pieces; only the ramps are cut finely.
        assertTrue(pieces.size < 20, "${pieces.size}")
        assertTrue(pieces.first().second >= 5_000)
    }
}
