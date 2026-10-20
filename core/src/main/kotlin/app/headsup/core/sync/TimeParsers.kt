package app.headsup.core.sync

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Extracts recording time from the naming schemes of the supported cameras. */
object FilenameTimeParser {
    private val DJI = Regex("""^DJI_(\d{8})(\d{6})(?:\D|$)""", RegexOption.IGNORE_CASE)
    private val PIXEL = Regex("""^PXL_(\d{8})_(\d{6})(\d{3})""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""^(?:VID_|IMG_)?(\d{8})_(\d{6})(?:\D|$)""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): TimeEvidence? {
        val name = fileName.substringAfterLast('/')
        DJI.find(name)?.let { m ->
            val time = localDateTime(m.groupValues[1], m.groupValues[2]) ?: return null
            return TimeEvidence(TimeSource.DJI_FILENAME, ClockReading.Local(time), TimeAnchor.START)
        }
        PIXEL.find(name)?.let { m ->
            // Pixel camera names are in UTC with milliseconds.
            val time = localDateTime(m.groupValues[1], m.groupValues[2]) ?: return null
            val ms = time.toInstant(ZoneOffset.UTC).toEpochMilli() + m.groupValues[3].toLong()
            return TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.LabelledUtc(ms), TimeAnchor.START)
        }
        PHONE.find(name)?.let { m ->
            val time = localDateTime(m.groupValues[1], m.groupValues[2]) ?: return null
            return TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.Local(time), TimeAnchor.START)
        }
        return null
    }

    private fun localDateTime(date: String, time: String): LocalDateTime? = try {
        LocalDateTime.of(
            date.substring(0, 4).toInt(), date.substring(4, 6).toInt(), date.substring(6, 8).toInt(),
            time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt(),
        ).takeIf { it.year in 2000..2100 }
    } catch (_: DateTimeException) {
        null
    }
}

/** Parses the MP4 `creation_time` strings returned by Android's `MediaMetadataRetriever`. */
object Mp4DateParser {
    // "20251020T191840.000Z" (MediaMetadataRetriever) or ISO-like "2025-10-20T19:18:40.000000Z".
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.(\d+))?Z?$""")
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z?$""")

    /** Epoch milliseconds as labelled by the file, or null when unset (1904/1970 placeholders) or unparsable. */
    fun parse(raw: String?): Long? {
        val value = raw?.trim() ?: return null
        val m = COMPACT.find(value) ?: ISO.find(value) ?: return null
        val g = m.groupValues
        return try {
            val time = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            if (time.year < 2000) return null
            val millis = g[7].padEnd(3, '0').take(3).toLong()
            time.toInstant(ZoneOffset.UTC).toEpochMilli() + millis
        } catch (_: DateTimeException) {
            null
        }
    }
}
