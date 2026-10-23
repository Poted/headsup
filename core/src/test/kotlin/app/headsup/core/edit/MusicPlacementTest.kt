package app.headsup.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicPlacementTest {
    @Test
    fun `a short song loops to the end of the timeline`() {
        val m = MusicPlacement(songDurationMs = 10_000)
        assertEquals(
            listOf(
                MusicPlacement.Segment(0, 0, 10_000),
                MusicPlacement.Segment(10_000, 0, 10_000),
                MusicPlacement.Segment(20_000, 0, 5_000),
            ),
            m.segments(25_000),
        )
        assertEquals(3_000L, m.songPositionAt(13_000, 25_000))
    }

    @Test
    fun `without looping the music stops where the song ends`() {
        val m = MusicPlacement(songDurationMs = 10_000, loop = false)
        assertEquals(listOf(MusicPlacement.Segment(0, 0, 10_000)), m.segments(25_000))
        assertNull(m.songPositionAt(12_000, 25_000))
    }

    @Test
    fun `a long song is cut where the timeline ends, at full level without fades`() {
        val m = MusicPlacement(songDurationMs = 180_000, startOnTimelineMs = 2_000, songStartMs = 30_000)
        assertEquals(listOf(MusicPlacement.Segment(2_000, 30_000, 50_000)), m.segments(22_000))
        assertNull(m.songPositionAt(1_000, 22_000))
        assertEquals(31_000L, m.songPositionAt(3_000, 22_000))
        assertEquals(1f, m.gainAt(21_999, 22_000))
    }

    @Test
    fun `fades only where asked`() {
        val m = MusicPlacement(songDurationMs = 60_000, startOnTimelineMs = 1_000, fadeInMs = 2_000, fadeOutMs = 4_000)
        assertEquals(0.5f, m.gainAt(2_000, 20_000), 1e-4f)
        assertEquals(1f, m.gainAt(10_000, 20_000), 1e-4f)
        assertEquals(0.25f, m.gainAt(19_000, 20_000), 1e-4f)
    }

    @Test
    fun `a trimmed song loops only its used part`() {
        val m = MusicPlacement(songDurationMs = 60_000, songStartMs = 10_000, songEndMs = 14_000)
        assertEquals(4_000L, m.playable)
        assertEquals(11_000L, m.songPositionAt(5_000, 20_000))
        assertEquals(5, m.segments(20_000).size)
    }

    @Test
    fun `clip fade`() {
        assertEquals(0.5f, Fade.gain(500, 10_000, 1_000, 0), 1e-4f)
        assertEquals(1f, Fade.gain(9_500, 10_000, 1_000, 0), 1e-4f)
        assertEquals(0.5f, Fade.gain(9_500, 10_000, 0, 1_000), 1e-4f)
    }
}
