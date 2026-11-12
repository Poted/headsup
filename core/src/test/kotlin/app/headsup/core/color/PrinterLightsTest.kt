package app.headsup.core.color

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrinterLightsTest {
    @Test
    fun twelvePointsIsOneStopOfThatColour() {
        val rgb = floatArrayOf(0.4f, 0.4f, 0.4f)
        PrinterLights.applyDisplay(listOf(12f, 0f, 0f), rgb)
        assertEquals((0.4f.pow(2.2f) * 2f).pow(1f / 2.2f), rgb[0], 1e-4f)
        assertEquals(0.4f, rgb[1], 1e-6f)
    }

    @Test
    fun masterKeyMovesAllThree() {
        val p = PrinterLights.pressed(PrinterLights.NEUTRAL, 3, 2f)
        assertEquals(listOf(2f, 2f, 2f), p)
        assertEquals(listOf(2f, 3f, 2f), PrinterLights.pressed(p, 1, 1f))
    }

    @Test
    fun gradeWithPrinterLightsIsNotNeutralAndScales() {
        val g = ColorGrade(printer = listOf(4f, 0f, -2f))
        assertTrue(!g.isNeutralColour)
        assertEquals(listOf(2f, 0f, -1f), (g * 0.5f).printer)
        assertEquals(listOf(8f, 0f, -4f), (g + g).printer)
    }

    @Test
    fun logOffsetIsEvenAcrossTones() {
        val dark = floatArrayOf(0.2f, 0.2f, 0.2f)
        val bright = floatArrayOf(0.8f, 0.8f, 0.8f)
        PrinterLights.applyLog(listOf(0f, 0f, 6f), dark, 0.075f)
        PrinterLights.applyLog(listOf(0f, 0f, 6f), bright, 0.075f)
        assertEquals(dark[2] - 0.2f, bright[2] - 0.8f, 1e-6f)
    }
}
