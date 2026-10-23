package app.headsup.core.sync

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExifTimeParserTest {
    @Test
    fun `with an offset the moment is exact`() {
        val e = ExifTimeParser.parse("2026:10:22 21:18:52", "+02:00")!!
        assertEquals(ClockReading.LabelledUtc(java.time.Instant.parse("2026-10-22T19:18:52Z").toEpochMilli()), e.reading)
        assertEquals(TimeSource.PHOTO_EXIF, e.source)
    }

    @Test
    fun `without an offset it is the camera's wall clock`() {
        val e = ExifTimeParser.parse("2026:10:22 21:18:52", null)!!
        assertEquals(ClockReading.Local(LocalDateTime.of(2026, 10, 22, 21, 18, 52)), e.reading)
    }

    @Test
    fun `missing or broken dates give nothing`() {
        assertNull(ExifTimeParser.parse(null, null))
        assertNull(ExifTimeParser.parse("0000:00:00 00:00:00", null))
    }
}
