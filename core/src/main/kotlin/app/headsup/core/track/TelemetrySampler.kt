package app.headsup.core.track

/** Values of all available metrics at one moment; a missing key means "unknown, show --". */
data class TelemetryFrame(val timeMs: Long, val values: Map<Metric, Double>) {
    operator fun get(metric: Metric): Double? = values[metric]
}

/**
 * Evaluates an [ActivityTrack] at arbitrary UTC times, e.g. the timestamp of every video frame.
 *
 * Values are interpolated linearly between neighbouring records. Garmin "Smart" recording
 * writes a record only when something changes, so gaps of 10–20 s are normal even during
 * steady activity; only longer gaps, or a stopped timer, are treated as missing data.
 */
class TelemetrySampler(
    private val track: ActivityTrack,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    fun frameAt(timeMs: Long): TelemetryFrame {
        val values = HashMap<Metric, Double>()
        for (metric in track.series.keys) valueAt(metric, timeMs)?.let { values[metric] = it }
        return TelemetryFrame(timeMs, values)
    }

    fun valueAt(metric: Metric, timeMs: Long): Double? {
        val series = track.series[metric] ?: return null
        if (series.isEmpty) return null
        val instant = metric.kind == Metric.Kind.INSTANT
        if (instant && track.pauses.any { timeMs > it.startMs && timeMs < it.endMs }) return null

        val i = lowerBound(series.timesMs, timeMs)
        if (i < series.size && series.timesMs[i] == timeMs) return series.values[i]
        if (i == 0 || i == series.size) return null

        val t0 = series.timesMs[i - 1]
        val t1 = series.timesMs[i]
        val v0 = series.values[i - 1]
        if (t1 - t0 > maxGapMs || track.pauses.any { it.startMs < t1 && it.endMs > t0 }) {
            return if (instant) null else v0
        }
        val f = (timeMs - t0).toDouble() / (t1 - t0)
        return v0 + (series.values[i] - v0) * f
    }

    private fun lowerBound(times: LongArray, target: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val DEFAULT_MAX_GAP_MS = 30_000L
    }
}
