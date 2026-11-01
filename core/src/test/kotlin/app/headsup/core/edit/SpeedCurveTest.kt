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
        val hero = SpeedCurve.Preset.SPEED_RAMP.points(0.4f)
        val length = SpeedCurve.timelineOf(10_000, 10_000, 1f, hero)
        // Fast ends, a slow middle: about as long as the steady clip, the middle stretched.
        assertTrue(length in 5_000..20_000, "$length")
        for (src in listOf(0L, 2_500L, 5_000L, 7_777L, 10_000L)) {
            val tl = SpeedCurve.timelineOf(src, 10_000, 1f, hero)
            assertTrue(abs(SpeedCurve.sourceOf(tl, 10_000, 1f, hero) - src) < 30, "$src")
        }
        assertEquals(0.4f, SpeedCurve.factor(hero, 0.52f), 1e-4f)
        assertEquals(3f, SpeedCurve.factor(hero, 0.1f), 1e-4f)
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
        assertEquals(0.25f, SpeedCurve.factor(SpeedCurve.Preset.SPEED_RAMP.points(0.25f), 0.52f), 1e-4f)
    }

    @Test
    fun `flat stretches make one piece`() {
        val pieces = SpeedCurve.pieces(20_000, 1f, SpeedCurve.Preset.SPEED_RAMP.points(0.4f))
        // The steady starts and ends are single pieces; only the ramps are cut finely.
        assertTrue(pieces.size <= 48, "${pieces.size}")
        assertTrue(pieces.first().second >= 2_500)
    }

    @Test
    fun `a rush brakes into slow motion within a blink`() {
        val curve = SpeedCurve.Preset.RUSH_IN.points(0.4f, rush = 24f)
        assertEquals(24f, SpeedCurve.factor(curve, 0.1f), 1e-3f)
        assertEquals(0.4f, SpeedCurve.factor(curve, 0.8f), 1e-4f)
        // 10 s of file: the first 3.6 s fly by in a fraction of a second, the rest is slow.
        val rush = SpeedCurve.timelineOf(3_600, 10_000, 1f, curve)
        assertTrue(rush < 300, "$rush")
        val pieces = SpeedCurve.pieces(10_000, 1f, curve)
        val total = pieces.sumOf { (a, b, s) -> ((b - a) / s).toDouble() }
        assertEquals(SpeedCurve.timelineOf(10_000, 10_000, 1f, curve).toDouble(), total, 60.0)
        // The braking is cut into several pieces, each step small.
        val braking = pieces.filter { it.third in 0.5f..20f }
        assertTrue(braking.size >= 3, "$pieces")
        assertTrue(SpeedCurve.Preset.RUSH_IN.rushes && !SpeedCurve.Preset.SLOW_IN.rushes)
    }

    @Test
    fun `a split cut plays as the whole did`() {
        val curve = SpeedCurve.Preset.RUSH_IN.points(0.4f, rush = 16f)
        val span = 10_000L
        for (cut in listOf(2_000L, 3_900L, 7_000L)) {
            val (a, b) = SpeedCurve.split(curve, cut / span.toFloat())
            val whole = SpeedCurve.timelineOf(span, span, 1f, curve)
            val parts = SpeedCurve.timelineOf(cut, cut, 1f, a) + SpeedCurve.timelineOf(span - cut, span - cut, 1f, b)
            assertTrue(abs(whole - parts) < whole / 50 + 20, "cut $cut: $whole vs $parts")
            // The speed on both sides of the cut is the speed there.
            assertEquals(SpeedCurve.factor(curve, cut / span.toFloat()), SpeedCurve.factor(a, 1f), 1e-3f)
            assertEquals(SpeedCurve.factor(curve, cut / span.toFloat()), SpeedCurve.factor(b, 0f), 1e-3f)
        }
    }

    @Test
    fun `a split in a steady stretch leaves no needless points`() {
        val curve = SpeedCurve.Preset.RUSH_IN.points(0.4f, rush = 2f)
        val (_, b) = SpeedCurve.split(curve, 0.7f)
        assertEquals(0.4f, SpeedCurve.steadyFactor(b)!!, 1e-4f)
        assertEquals(4, b.size)
        val (a, _) = SpeedCurve.split(curve, 0.2f)
        assertEquals(2f, SpeedCurve.steadyFactor(a)!!, 1e-4f)
        assertEquals(null, SpeedCurve.steadyFactor(curve))
    }

    @Test
    fun `timeline shares follow the speed`() {
        // Fast for the first half of the file, slow after: the first half is a small part of the time.
        val curve = listOf(0f, 4f, 0.45f, 4f, 0.55f, 0.5f, 1f, 0.5f)
        val shares = SpeedCurve.timeShares(curve, 1f)
        val t = SpeedCurve.shareAt(shares, 0.5f)
        assertTrue(t < 0.2f, "$t")
        assertEquals(0.5f, SpeedCurve.sourceAt(shares, t), 1e-3f)
        assertEquals(0f, SpeedCurve.shareAt(shares, 0f), 1e-6f)
        assertEquals(1f, SpeedCurve.shareAt(shares, 1f), 1e-6f)
        // Agrees with timelineOf.
        val span = 10_000L
        assertEquals(SpeedCurve.timelineOf(5_000L, span, 1f, curve).toFloat() / SpeedCurve.timelineOf(span, span, 1f, curve), t, 0.01f)
    }
}
