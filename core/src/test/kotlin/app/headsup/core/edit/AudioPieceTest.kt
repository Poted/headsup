package app.headsup.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioPieceTest {
    private val song = AudioPiece(sourceDurationMs = 60_000, startOnTimelineMs = 2_000)

    @Test
    fun `plays the source from its start on the timeline`() {
        assertNull(song.sourceAt(1_000))
        assertEquals(3_000L, song.sourceAt(5_000))
        assertEquals(62_000L, song.endOnTimelineMs)
    }

    @Test
    fun `trimming the end shortens it and the start keeps the end in place`() {
        val cut = song.trimEnd(-50_000)
        assertEquals(10_000L, cut.lengthMs)
        assertEquals(12_000L, cut.endOnTimelineMs)
        val later = cut.trimStart(3_000)
        assertEquals(5_000L, later.startOnTimelineMs)
        assertEquals(3_000L, later.sourceStartMs)
        assertEquals(12_000L, later.endOnTimelineMs)
    }

    @Test
    fun `trimming the end back out to the source end clears it`() {
        assertEquals(0L, song.trimEnd(-10_000).trimEnd(20_000).sourceEndMs)
    }

    @Test
    fun `slip keeps place and length`() {
        val cut = song.trimEnd(-50_000).slip(4_000)
        assertEquals(2_000L, cut.startOnTimelineMs)
        assertEquals(10_000L, cut.lengthMs)
        assertEquals(4_000L, cut.sourceStartMs)
    }

    @Test
    fun `fades never overlap`() {
        val p = AudioPiece(10_000, fadeInMs = 8_000, fadeOutMs = 8_000)
        assertEquals(8_000L, p.fadeIn)
        assertEquals(2_000L, p.fadeOut)
        assertEquals(0.5f, p.gainAt(4_000), 1e-4f)
        assertEquals(0.5f, p.gainAt(9_000), 1e-4f)
    }

    @Test
    fun `clip fade`() {
        assertEquals(0.5f, Fade.gain(500, 10_000, 1_000, 0), 1e-4f)
        assertEquals(0.5f, Fade.gain(9_500, 10_000, 0, 1_000), 1e-4f)
    }
}
