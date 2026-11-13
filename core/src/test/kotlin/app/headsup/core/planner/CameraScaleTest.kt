package app.headsup.core.planner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraScaleTest {
    @Test fun theScalesAreAThirdOfAStopApart() {
        // Up to 30 s, and along the f-numbers and ISOs, each step is close to a third of a stop.
        val upTo30 = CameraScale.shutters.filter { it <= 30.0 }
        for (scale in listOf(upTo30, CameraScale.isos)) {
            for (i in 0 until scale.size - 1) // Printed values are rounded (1/15 → 1/13 is 0.21 of a stop).
            assertTrue(abs(log2(scale[i + 1] / scale[i]) - 1.0 / 3) < 0.15, "${scale[i]} → ${scale[i + 1]}")
        }
        for (i in 0 until CameraScale.apertures.size - 1) {
            val stops = 2 * log2(CameraScale.apertures[i + 1] / CameraScale.apertures[i])
            assertTrue(abs(stops - 1.0 / 3) < 0.15, "${CameraScale.apertures[i]}")
        }
    }

    @Test fun stepsGoAlongTheDialAndReachTwoSeconds() {
        var t = 0.5
        val seen = ArrayList<String>()
        repeat(6) { t = CameraScale.step(CameraScale.shutters, t, 1); seen += CameraScale.shutterLabel(t) }
        assertEquals(listOf("0.6s", "0.8s", "1s", "1.3s", "1.6s", "2s"), seen)
        assertEquals(2.0, t, 1e-12)
        assertEquals(1.0 / 8000, CameraScale.step(CameraScale.shutters, 1.0 / 8000, -3), 1e-12)
    }

    @Test fun shutterTimesReadAsOnACamera() {
        val cases = mapOf(
            1.0 to "1s", 0.79 to "0.8s", 0.8 to "0.8s", 0.5 to "1/2s", 1.0 / 3 to "1/3s", 0.25 to "1/4s",
            1.0 / 250 to "1/250s", 1.0 / 13 to "1/13s", 1.26 to "1.3s", 2.0 to "2s", 30.0 to "30s", 45.0 to "45s", 150.0 to "2.5m",
        )
        for ((v, label) in cases) assertEquals(label, CameraScale.shutterLabel(v), "for $v")
        // Nothing reads as 1/1s.
        for (k in 0..300) assertTrue(CameraScale.shutterLabel(0.25 * Math.pow(2.0, k / 100.0)) != "1/1s")
    }

    @Test fun fNumbersAndIsosReadAsPrinted() {
        assertEquals("f/2.8", CameraScale.apertureLabel(2.83))
        assertEquals("f/2", CameraScale.apertureLabel(2.0))
        assertEquals("f/11", CameraScale.apertureLabel(11.3))
        assertEquals(3200, CameraScale.isoValue(3345.0))
        assertEquals(64, CameraScale.isoValue(60.0))
    }
}
