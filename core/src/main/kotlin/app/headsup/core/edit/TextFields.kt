package app.headsup.core.edit

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric

/** Where a text field's value comes from. */
enum class FieldSource(val label: String) { ACTIVITY("Activity"), MOMENT("At this moment"), PHOTO("Photo") }

/**
 * Values a text can show, written into it as `{key}`: a summary of the watch activity, its values
 * at the moment of the clip or photo, and what the photo itself recorded.
 */
enum class TextField(val key: String, val label: String, val source: FieldSource) {
    DISTANCE("distance", "Distance", FieldSource.ACTIVITY),
    ASCENT("ascent", "Total ascent", FieldSource.ACTIVITY),
    DESCENT("descent", "Total descent", FieldSource.ACTIVITY),
    HIGHEST("highest", "Highest point", FieldSource.ACTIVITY),
    DURATION("duration", "Time", FieldSource.ACTIVITY),
    AVG_HR("avgHr", "Average HR", FieldSource.ACTIVITY),
    MAX_HR("maxHr", "Max HR", FieldSource.ACTIVITY),
    MAX_SPEED("maxSpeed", "Max speed", FieldSource.ACTIVITY),
    SPORT("sport", "Sport", FieldSource.ACTIVITY),

    ALTITUDE_NOW("altitudeNow", "Altitude", FieldSource.MOMENT),
    DISTANCE_NOW("distanceNow", "Distance so far", FieldSource.MOMENT),
    ASCENT_NOW("ascentNow", "Ascent so far", FieldSource.MOMENT),
    HR_NOW("hrNow", "Heart rate", FieldSource.MOMENT),
    SPEED_NOW("speedNow", "Speed", FieldSource.MOMENT),

    PLACE("place", "Place", FieldSource.PHOTO),
    COORDS("coords", "Coordinates", FieldSource.PHOTO),
    PHOTO_ALTITUDE("photoAltitude", "Altitude (GPS)", FieldSource.PHOTO),
    DATE("date", "Date", FieldSource.PHOTO),
    TIME("time", "Time of day", FieldSource.PHOTO),
    CAMERA("camera", "Camera", FieldSource.PHOTO),
    EXPOSURE("exposure", "Exposure", FieldSource.PHOTO),
}

object TextFields {
    private val pattern = Regex("""\{([A-Za-z]+)\}""")

    fun hasFields(text: String) = pattern.containsMatchIn(text)

    /** [template] with each `{key}` replaced by its value, or a dash when it is not known. */
    fun fill(template: String, values: Map<String, String>): String =
        if (!hasFields(template)) template else pattern.replace(template) { m -> values[m.groupValues[1]] ?: "–" }
}

/** Totals of an activity, for the summary fields. */
data class ActivitySummary(
    val distanceM: Double?,
    val ascentM: Double?,
    val descentM: Double?,
    val highestM: Double?,
    /** Recording time without the pauses. */
    val movingMs: Long,
    val avgHr: Double?,
    val maxHr: Double?,
    val maxSpeedMps: Double?,
) {
    companion object {
        fun of(track: ActivityTrack): ActivitySummary {
            fun last(m: Metric) = track.series[m]?.takeIf { !it.isEmpty }?.let { it.values[it.size - 1] }
            fun max(m: Metric) = track.series[m]?.takeIf { !it.isEmpty }?.values?.max()
            val hr = track.series[Metric.HEART_RATE]?.takeIf { !it.isEmpty }
            val paused = track.pauses.sumOf { it.durationMs }
            return ActivitySummary(
                distanceM = last(Metric.DISTANCE),
                ascentM = last(Metric.ASCENT),
                descentM = last(Metric.DESCENT),
                highestM = max(Metric.ALTITUDE),
                movingMs = (track.endMs - track.startMs - paused).coerceAtLeast(0L),
                avgHr = hr?.values?.average(),
                maxHr = max(Metric.HEART_RATE),
                maxSpeedMps = max(Metric.SPEED),
            )
        }
    }
}
