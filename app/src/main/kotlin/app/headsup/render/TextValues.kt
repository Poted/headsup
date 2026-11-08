package app.headsup.render

import app.headsup.core.edit.ActivitySummary
import app.headsup.core.edit.TextField
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.TelemetryFrame
import app.headsup.media.PhotoInfo
import app.headsup.media.Places
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** The values of the text fields ([TextField]), formatted for showing. */
object TextValues {
    private val summaries = WeakHashMap<ActivityTrack, ActivitySummary>()

    private fun summary(track: ActivityTrack) = synchronized(summaries) { summaries.getOrPut(track) { ActivitySummary.of(track) } }

    /** Summary of the whole activity. */
    fun activity(track: ActivityTrack?): Map<String, String> {
        track ?: return emptyMap()
        val s = summary(track)
        return buildMap {
            s.distanceM?.let { put(TextField.DISTANCE.key, km(it)) }
            s.ascentM?.let { put(TextField.ASCENT.key, height(it)) }
            s.descentM?.let { put(TextField.DESCENT.key, height(it)) }
            s.highestM?.let { put(TextField.HIGHEST.key, height(it)) }
            put(TextField.DURATION.key, hours(s.movingMs))
            s.avgHr?.let { put(TextField.AVG_HR.key, "${it.roundToInt()} bpm") }
            s.maxHr?.let { put(TextField.MAX_HR.key, "${it.roundToInt()} bpm") }
            s.maxSpeedMps?.let { put(TextField.MAX_SPEED.key, speed(it)) }
            put(TextField.SPORT.key, track.sportName ?: track.sport.name.lowercase().replaceFirstChar { it.uppercase() })
        }
    }

    /** Values of the activity at one moment (and where it was, if the watch recorded it). */
    fun moment(frame: TelemetryFrame?, utcMs: Long?, offsetSeconds: Int?): Map<String, String> {
        frame ?: return emptyMap()
        return buildMap {
            frame[Metric.ALTITUDE]?.let { put(TextField.ALTITUDE_NOW.key, height(it)) }
            frame[Metric.DISTANCE]?.let { put(TextField.DISTANCE_NOW.key, km(it)) }
            frame[Metric.ASCENT]?.let { put(TextField.ASCENT_NOW.key, height(it)) }
            frame[Metric.HEART_RATE]?.let { put(TextField.HR_NOW.key, "${it.roundToInt()} bpm") }
            frame[Metric.SPEED]?.let { put(TextField.SPEED_NOW.key, speed(it)) }
            val lat = frame[Metric.LATITUDE]
            val lon = frame[Metric.LONGITUDE]
            if (lat != null && lon != null) {
                put(TextField.COORDS.key, coords(lat, lon))
                put(TextField.PLACE.key, Places.known(lat, lon) ?: coords(lat, lon))
            }
            if (utcMs != null && offsetSeconds != null) {
                put(TextField.DATE.key, Formats.date(utcMs, offsetSeconds))
                put(TextField.TIME.key, Formats.clock(utcMs, offsetSeconds).take(5))
                put(TextField.CLOCK.key, Formats.clock(utcMs, offsetSeconds))
            }
        }
    }

    /**
     * What a video recorded itself, without a watch: the time at that moment ([utcMs], in the
     * phone's zone then) and where it was filmed.
     */
    fun video(utcMs: Long?, latitude: Double?, longitude: Double?): Map<String, String> = buildMap {
        if (utcMs != null) putAll(clock(utcMs))
        if (latitude != null && longitude != null) {
            put(TextField.COORDS.key, coords(latitude, longitude))
            put(TextField.PLACE.key, Places.known(latitude, longitude) ?: coords(latitude, longitude))
        }
    }

    /** Date, time of day and the running clock at [utcMs], in the phone's zone then. */
    fun clock(utcMs: Long): Map<String, String> {
        val offset = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(utcMs)).totalSeconds
        return mapOf(
            TextField.DATE.key to Formats.date(utcMs, offset),
            TextField.TIME.key to Formats.clock(utcMs, offset).take(5),
            TextField.CLOCK.key to Formats.clock(utcMs, offset),
        )
    }

    /** What the photo recorded; it wins over the watch for place, date and time. */
    fun photo(info: PhotoInfo?): Map<String, String> {
        info ?: return emptyMap()
        return buildMap {
            if (info.latitude != null && info.longitude != null) {
                put(TextField.COORDS.key, coords(info.latitude, info.longitude))
                put(TextField.PLACE.key, Places.known(info.latitude, info.longitude) ?: coords(info.latitude, info.longitude))
            }
            info.altitudeM?.let { put(TextField.PHOTO_ALTITUDE.key, height(it)) }
            info.dateTime?.takeIf { it.length >= 16 }?.let { d ->
                put(TextField.DATE.key, "${d.substring(8, 10)}.${d.substring(5, 7)}.${d.substring(0, 4)}")
                put(TextField.TIME.key, d.substring(11, 16))
            }
            val make = info.make
            val model = info.model
            val camera = when {
                make == null -> model
                model == null -> make
                model.startsWith(make, ignoreCase = true) -> model
                else -> "$make $model"
            }
            if (!camera.isNullOrBlank()) put(TextField.CAMERA.key, camera)
            val exposure = listOfNotNull(
                info.fNumber?.let { String.format(Locale.US, "ƒ/%.1f", it) },
                info.exposureS?.let { if (it >= 1) String.format(Locale.US, "%.1f s", it) else "1/${(1 / it).roundToInt()} s" },
                info.iso?.let { "ISO $it" },
                info.focalMm?.let { "${it.roundToInt()} mm" },
            ).joinToString(" · ")
            if (exposure.isNotEmpty()) put(TextField.EXPOSURE.key, exposure)
        }
    }

    private fun km(m: Double): String {
        val d = Units.distance(m)
        return String.format(Locale.US, if (d >= 10) "%.1f %s" else "%.2f %s", d, Units.distanceUnit)
    }

    private fun height(m: Double) = "${Units.height(m).roundToInt()} ${Units.heightUnit}"

    private fun speed(mps: Double) = String.format(Locale.US, "%.1f %s", Units.speed(mps), Units.speedUnit)

    private fun hours(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) String.format(Locale.US, "%d h %02d min", min / 60, min % 60) else "$min min"
    }

    fun coords(lat: Double, lon: Double) =
        String.format(Locale.US, "%.4f° %s, %.4f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")
}
