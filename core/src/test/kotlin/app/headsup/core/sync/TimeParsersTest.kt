package app.headsup.core.sync

import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeParsersTest {
    private val local = LocalDateTime.of(2026, 10, 20, 21, 18, 40)

    @Test
    fun `DJI file name is local start time`() {
        val e = FilenameTimeParser.parse("DJI_20261020211840_0001_D.MP4")
        assertEquals(TimeEvidence(TimeSource.DJI_FILENAME, ClockReading.Local(local), TimeAnchor.START), e)
    }

    @Test
    fun `DJI Fly and Mimo app names are local start time`() {
        assertEquals(
            TimeEvidence(TimeSource.DJI_FILENAME, ClockReading.Local(LocalDateTime.of(2026, 5, 24, 15, 51, 44)), TimeAnchor.START),
            FilenameTimeParser.parse("dji_fly_20260524_155144_0147_1783265392318_video_d_logm.mp4"),
        )
        val expected = TimeEvidence(TimeSource.DJI_FILENAME, ClockReading.Local(LocalDateTime.of(2026, 10, 20, 21, 18, 52)), TimeAnchor.START)
        assertEquals(expected, FilenameTimeParser.parse("dji_export_20261020_211852_1792527178764_editor.mp4"))
        // Copy saved by the Samsung gallery editor.
        assertEquals(expected, FilenameTimeParser.parse("dji_export_20261020_211852_1792527178764_editor_001.mp4"))
    }

    @Test
    fun `path prefix is ignored`() {
        assertEquals(TimeSource.DJI_FILENAME, FilenameTimeParser.parse("DCIM/DJI_001/DJI_20261020211840_0002_D.MP4")?.source)
    }

    @Test
    fun `Samsung and generic Android names are local start time`() {
        val expected = TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.Local(local), TimeAnchor.START)
        assertEquals(expected, FilenameTimeParser.parse("20261020_211840.mp4"))
        assertEquals(
            TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.Local(LocalDateTime.of(2026, 5, 17, 13, 15, 12)), TimeAnchor.START),
            FilenameTimeParser.parse("20260517_131512.mp4"),
        )
        assertEquals(expected, FilenameTimeParser.parse("20261020_211840(1).mp4"))
        assertEquals(expected, FilenameTimeParser.parse("VID_20261020_211840.mp4"))
    }

    @Test
    fun `Pixel names are UTC with milliseconds`() {
        val e = FilenameTimeParser.parse("PXL_20261020_191840123.mp4")
        val ms = Instant.parse("2026-10-20T19:18:40.123Z").toEpochMilli()
        assertEquals(TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.LabelledUtc(ms), TimeAnchor.START), e)
    }

    @Test
    fun `unknown or invalid names give no evidence`() {
        assertNull(FilenameTimeParser.parse("DJI_0001.MP4"))
        assertNull(FilenameTimeParser.parse("holiday.mp4"))
        assertNull(FilenameTimeParser.parse("20261340_211840.mp4"))
        assertNull(FilenameTimeParser.parse("19991020_211840.mp4"))
        assertNull(FilenameTimeParser.parse("DJI_202610202118401_0001.MP4"))
    }

    @Test
    fun `MP4 creation time formats`() {
        val ms = Instant.parse("2026-10-20T19:18:40Z").toEpochMilli()
        assertEquals(ms, Mp4DateParser.parse("20261020T191840.000Z"))
        assertEquals(ms + 123, Mp4DateParser.parse("2026-10-20T19:18:40.123456Z"))
        assertEquals(ms, Mp4DateParser.parse("2026-10-20 19:18:40"))
    }

    @Test
    fun `unset MP4 creation time is ignored`() {
        assertNull(Mp4DateParser.parse("19040101T000000.000Z"))
        assertNull(Mp4DateParser.parse("19700101T000000.000Z"))
        assertNull(Mp4DateParser.parse(""))
        assertNull(Mp4DateParser.parse(null))
        assertNull(Mp4DateParser.parse("yesterday"))
    }
}
