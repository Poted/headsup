package app.headsup.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CutTransitionTest {
    @Test
    fun aDipIsDarkestAtTheCutAndGoneAwayFromIt() {
        // A 4 s clip, a 1 s dip in at its start and out at its end.
        val start = CutTransition.at(0L, 4000L, CutKind.BLACK, 1000L, CutKind.BLACK, 1000L)
        assertEquals(1f, start.dip, 1e-4f)
        assertTrue(CutTransition.at(250L, 4000L, CutKind.BLACK, 1000L, CutKind.BLACK, 1000L).dip in 0.4f..0.6f)
        assertTrue(CutTransition.at(2000L, 4000L, CutKind.BLACK, 1000L, CutKind.BLACK, 1000L).isNone)
        assertTrue(CutTransition.at(3999L, 4000L, CutKind.NONE, 0L, CutKind.BLACK, 1000L).dip > 0.99f)
    }

    @Test
    fun aWhipLeavesOneWayAndArrivesFromTheOther() {
        val leaving = CutTransition.at(3990L, 4000L, CutKind.NONE, 0L, CutKind.WHIP, 800L)
        val arriving = CutTransition.at(10L, 4000L, CutKind.WHIP, 800L, CutKind.NONE, 0L)
        assertTrue(leaving.shift < 0f && arriving.shift > 0f)
        assertTrue(leaving.blur > 0.1f && arriving.blur > 0.1f)
    }

    @Test
    fun aShortClipSharesItself() {
        // A transition longer than the clip takes at most half the clip each side.
        val mid = CutTransition.at(500L, 1000L, CutKind.ZOOM, 4000L, CutKind.ZOOM, 4000L)
        assertTrue(mid.zoom >= 1f)
    }
}
