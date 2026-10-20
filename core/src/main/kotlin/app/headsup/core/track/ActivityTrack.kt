package app.headsup.core.track

/**
 * Every value the dashboard can show. [Kind] decides what happens between two samples
 * that are too far apart: instantaneous readings become unknown, running totals hold.
 */
enum class Metric(val kind: Kind) {
    HEART_RATE(Kind.INSTANT),       // bpm
    SPEED(Kind.INSTANT),            // m/s; pace is derived from it at display time
    ALTITUDE(Kind.INSTANT),         // m
    DISTANCE(Kind.CUMULATIVE),      // m
    ASCENT(Kind.CUMULATIVE),        // m, derived from altitude
    DESCENT(Kind.CUMULATIVE),       // m, derived from altitude
    GRADE(Kind.INSTANT),            // %, from FIT or derived from altitude and distance
    CADENCE(Kind.INSTANT),          // steps/min for foot sports, rpm otherwise
    POWER(Kind.INSTANT),            // W
    TEMPERATURE(Kind.INSTANT),      // °C
    LATITUDE(Kind.INSTANT),         // degrees
    LONGITUDE(Kind.INSTANT);        // degrees

    enum class Kind { INSTANT, CUMULATIVE }
}

/** Time-ordered values of one metric. Only timestamps where the metric was recorded are kept. */
class Series(val timesMs: LongArray, val values: DoubleArray) {
    init {
        require(timesMs.size == values.size)
    }

    val size: Int get() = timesMs.size
    val isEmpty: Boolean get() = timesMs.isEmpty()
}

data class TimeRange(val startMs: Long, val endMs: Long) {
    operator fun contains(timeMs: Long) = timeMs in startMs..endMs
    val durationMs: Long get() = endMs - startMs
}

/** One recorded activity, all times in UTC epoch milliseconds. */
data class ActivityTrack(
    val series: Map<Metric, Series>,
    val startMs: Long,
    val endMs: Long,
    /** Watch's offset from UTC during the activity (`activity.local_timestamp - timestamp`), if recorded. */
    val utcOffsetSeconds: Int?,
    /** Periods when the activity timer was stopped. */
    val pauses: List<TimeRange>,
    /** Upper bounds of HR zones 0..5 as configured on the watch, e.g. [96, 115, 134, 154, 173, 192]. */
    val hrZoneHighBoundaries: List<Int>?,
    val sport: Sport,
    val sportName: String?,
) {
    val range: TimeRange get() = TimeRange(startMs, endMs)

    fun has(metric: Metric) = series[metric]?.isEmpty == false

    /** Zone 1..5 for [heartRate], 0 below zone 1, or null when the watch did not export zones. */
    fun hrZone(heartRate: Double): Int? {
        val bounds = hrZoneHighBoundaries ?: return null
        val index = bounds.indexOfFirst { heartRate <= it }
        return if (index < 0) bounds.lastIndex else index
    }
}

enum class Sport {
    RUNNING, CYCLING, WALKING, HIKING, OTHER;

    /** Garmin stores cadence per leg for foot sports; the dashboard shows steps per minute. */
    val doublesCadence: Boolean get() = this == RUNNING || this == WALKING || this == HIKING
}
