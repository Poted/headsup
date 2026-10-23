package app.headsup.core.sync

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The moment a photo was taken, from EXIF: `DateTimeOriginal` ("2026:10:22 21:18:52") is the
 * camera's wall clock; with `OffsetTimeOriginal` ("+02:00") it is an exact instant.
 */
object ExifTimeParser {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun parse(dateTimeOriginal: String?, offsetTimeOriginal: String?): TimeEvidence? {
        val local = dateTimeOriginal?.trim()?.takeIf { it.length >= 19 }?.let {
            runCatching { LocalDateTime.parse(it.substring(0, 19), FORMAT) }.getOrNull()
        } ?: return null
        val offset = offsetTimeOriginal?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        val reading = if (offset != null) {
            ClockReading.LabelledUtc(local.toInstant(offset).toEpochMilli())
        } else {
            ClockReading.Local(local)
        }
        return TimeEvidence(TimeSource.PHOTO_EXIF, reading, TimeAnchor.START)
    }
}
