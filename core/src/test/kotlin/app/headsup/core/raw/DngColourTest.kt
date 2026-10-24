package app.headsup.core.raw

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DngColourTest {
    // A Samsung-like pair: tungsten and daylight matrices.
    private val cm1 = doubleArrayOf(0.9, -0.3, -0.1, -0.5, 1.3, 0.2, -0.1, 0.25, 0.6)
    private val cm2 = doubleArrayOf(0.79, -0.18, -0.13, -0.33, 1.3, 0.02, -0.11, 0.34, 0.53)
    private val fm = doubleArrayOf(0.6, 0.2, 0.16, 0.25, 0.85, -0.1, 0.03, -0.2, 0.99)

    private fun assertNeutral(m: DoubleArray) {
        val r = m[0] + m[1] + m[2]
        val g = m[3] + m[4] + m[5]
        val b = m[6] + m[7] + m[8]
        assertTrue(abs(r - g) < 0.03 && abs(b - g) < 0.03, "white should stay neutral: $r $g $b")
        assertTrue(abs(g - 1) < 0.05, "white should stay at 1: $g")
    }

    @Test
    fun `balanced white stays white through either path`() {
        for (neutral in listOf(doubleArrayOf(0.45, 1.0, 0.75), doubleArrayOf(0.7, 1.0, 0.4))) {
            val viaMatrix = DngColour.transform(DngColour.Calibration(cm1, cm2, null, null, 17, 21), neutral)!!
            assertNeutral(viaMatrix.balancedToSrgb)
        }
        // Forward matrices map balanced white to D50 white by construction (rows summing to D50).
        val d50 = doubleArrayOf(0.9642, 1.0, 0.8249)
        val f = DoubleArray(9) { fm[it] / (fm[it / 3 * 3] + fm[it / 3 * 3 + 1] + fm[it / 3 * 3 + 2]) * d50[it / 3] }
        val viaForward = DngColour.transform(DngColour.Calibration(cm1, cm2, f, f, 17, 21), doubleArrayOf(0.5, 1.0, 0.6))!!
        assertNeutral(viaForward.balancedToSrgb)
    }

    @Test
    fun `a warm white reads as a low colour temperature`() {
        // Under tungsten the camera's red is strong relative to blue: neutral has low red, high blue... here a warm one.
        val warm = DngColour.transform(DngColour.Calibration(cm1, cm2, null, null, 17, 21), doubleArrayOf(0.85, 1.0, 0.3))!!
        val cool = DngColour.transform(DngColour.Calibration(cm1, cm2, null, null, 17, 21), doubleArrayOf(0.4, 1.0, 0.8))!!
        assertTrue(warm.kelvin < cool.kelvin, "${warm.kelvin} vs ${cool.kelvin}")
    }
}
