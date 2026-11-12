package app.headsup.core.edit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateWeaveTest {
    @Test
    fun noneWhenOff() {
        assertTrue(GateWeave.at(1234L, 0f, 0f).isNone)
    }

    @Test
    fun sameMomentSameShiftAndWithinLimits() {
        for (t in 0L..5000L step 37L) {
            val a = GateWeave.at(t, 1f, 1f)
            assertEquals(a, GateWeave.at(t, 1f, 1f))
            assertTrue(abs(a.dx) <= GateWeave.MAX_X + 1e-6f)
            assertTrue(abs(a.dy) <= GateWeave.MAX_Y + 1e-6f)
            assertTrue(abs(a.gain - 1f) <= GateWeave.MAX_FLICKER + 1e-6f)
        }
    }

    @Test
    fun itMovesAndTheCoverHidesTheEdges() {
        val shifts = (0L..3000L step 41L).map { GateWeave.at(it, 1f, 1f) }
        assertTrue(shifts.map { it.dy }.distinct().size > 10)
        assertTrue(shifts.map { it.gain }.distinct().size > 10)
        // Enlarged by more than twice the largest move: no edge shows.
        assertTrue((GateWeave.cover(1f) - 1f) / 2f >= GateWeave.MAX_Y)
    }
}
