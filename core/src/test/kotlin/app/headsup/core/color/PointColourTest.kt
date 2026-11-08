package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointColourTest {
    private fun applied(points: List<PointColour>, pop: Float, r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(r, g, b).also { PointColours.apply(points, pop, it) }

    @Test
    fun oklabRoundTrips() {
        for (c in listOf(floatArrayOf(0.8f, 0.2f, 0.1f), floatArrayOf(0.1f, 0.5f, 0.9f), floatArrayOf(0.5f, 0.5f, 0.5f))) {
            val lab = MaskRange.oklab(c[0], c[1], c[2])
            val back = PointColours.fromOklab(lab[0], lab[1], lab[2])
            for (i in 0..2) assertEquals(c[i], back[i], 0.002f)
        }
    }

    @Test
    fun onlyThePickedFamilyChanges() {
        val red = PointColour(0.8f, 0.15f, 0.1f, saturation = -1f)
        val r = applied(listOf(red), 0f, 0.78f, 0.17f, 0.12f)
        assertTrue(kotlin.math.abs(r[0] - r[1]) < 0.15f, "red made grey: ${r.toList()}")
        val sky = applied(listOf(red), 0f, 0.2f, 0.45f, 0.85f)
        assertEquals(0.2f, sky[0], 1e-4f)
        assertEquals(0.85f, sky[2], 1e-4f)
    }

    @Test
    fun colourPopKeepsOnlyThePicked() {
        val red = PointColour(0.8f, 0.15f, 0.1f)
        val keptRed = applied(listOf(red), 1f, 0.8f, 0.15f, 0.1f)
        assertTrue(keptRed[0] - keptRed[1] > 0.5f)
        val greyed = applied(listOf(red), 1f, 0.2f, 0.45f, 0.85f)
        assertEquals(greyed[0], greyed[2], 1e-4f)
    }

    @Test
    fun aHueTurnMovesTheColour() {
        val green = PointColour(0.2f, 0.7f, 0.2f, hue = 1f)
        val out = applied(listOf(green), 0f, 0.2f, 0.7f, 0.2f)
        // Turned towards cyan: more blue than before.
        assertTrue(out[2] > 0.3f, out.toList().toString())
    }
}
