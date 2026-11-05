package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraLogTest {
    /** 18 % grey as each maker gives it, in decoded video levels (0 at 64/1023, 1 at 940/1023). */
    private val greys = mapOf(
        CameraLog.Profile.SLOG3 to (420.0 - 64.0) / 876.0,
        CameraLog.Profile.VLOG to 0.4233,
        CameraLog.Profile.LOGC3 to (0.391 * 1023.0 - 64.0) / 876.0,
        CameraLog.Profile.DLOG to 0.3988,
        CameraLog.Profile.CLOG3 to 0.3310,
    )

    @Test
    fun greyComesBackAsEighteenPercent() {
        for ((p, v) in greys) assertEquals(0.18, p.toLinear(v), 0.005, p.name)
    }

    @Test
    fun curvesRiseSteadily() {
        for (p in CameraLog.Profile.entries) {
            var last = p.toLinear(0.0)
            for (i in 1..100) {
                val y = p.toLinear(i / 100.0)
                assertTrue(y > last, "${p.name} at $i")
                last = y
            }
        }
    }

    @Test
    fun rec709ToItselfIsIdentity() {
        val m = CameraLog.matrixTo709(CameraLog.Gamut.REC709)
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1.0 else 0.0, m[i], 1e-9)
    }

    @Test
    fun whiteStaysWhiteAcrossGamuts() {
        for (g in CameraLog.Gamut.entries) {
            val m = CameraLog.matrixTo709(g)
            for (r in 0..2) assertEquals(1.0, m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2], 1e-6, g.name)
        }
    }

    @Test
    fun renderingPutsGreyAndRollsOff() {
        assertEquals(CameraLog.GREY, CameraLog.render(0.18), 1e-6)
        assertTrue(CameraLog.render(0.0) == 0.0)
        assertTrue(CameraLog.render(2.88) in 0.95..1.0)
        assertTrue(CameraLog.render(1.0) < CameraLog.render(2.0))
    }

    @Test
    fun greyCardIsNeutralGrey() {
        for ((p, v) in greys) {
            val lut = CameraLog.lut(p, 33)
            val out = FloatArray(3)
            lut.sample(v.toFloat(), v.toFloat(), v.toFloat(), out)
            for (c in 0..2) assertTrue(abs(out[c] - CameraLog.GREY) < 0.02, "${p.name} channel $c: ${out[c]}")
        }
    }

    @Test
    fun namesRoundTrip() {
        for (p in CameraLog.Profile.entries) assertEquals(p, CameraLog.profileOf(CameraLog.nameOf(p)))
        assertEquals(null, CameraLog.profileOf("My drone LUT"))
    }
}
