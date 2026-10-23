package app.headsup.core.highlight

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.TelemetrySampler
import app.headsup.core.track.TimeRange

enum class HighlightKind(val label: String, val metric: Metric) {
    TOP_SPEED("Top speed", Metric.SPEED),
    ACCELERATION("Sprint", Metric.SPEED),
    FAST_DESCENT("Fast descent", Metric.ALTITUDE),
    STEEP_CLIMB("Steep climb", Metric.ALTITUDE),
    SUMMIT("Highest point", Metric.ALTITUDE),
    PEAK_HEART_RATE("Heart-rate peak", Metric.HEART_RATE),
    POWER_PEAK("Power peak", Metric.POWER),
}

/**
 * A moment worth showing. [value] is in the metric's unit (m/s, m, bpm, W); for sprints it is the
 * speed gained over [ACCEL_WINDOW_S] and for climbs/descents the vertical speed in m/s.
 * [score] ranks moments of different kinds against each other, 0..1.
 */
data class Highlight(val kind: HighlightKind, val timeMs: Long, val value: Double, val score: Double)

/**
 * Finds the best moments of an activity from the watch data alone: where the speed, heart rate
 * or power peaked, the hardest sprint, the fastest descent and steepest climb, and the highest
 * point. Only moments inside [covered] (the parts with video) count.
 */
object HighlightFinder {
    const val ACCEL_WINDOW_S = 5
    private const val VERTICAL_WINDOW_S = 10
    private const val SMOOTH_S = 5

    fun find(track: ActivityTrack, covered: List<TimeRange>, maxCount: Int = 10, minGapMs: Long = 20_000L): List<Highlight> {
        val sampler = TelemetrySampler(track)
        val ranges = covered.mapNotNull { r ->
            val start = maxOf(r.startMs, track.startMs)
            val end = minOf(r.endMs, track.endMs)
            if (end - start >= 2_000L) TimeRange(start, end) else null
        }
        if (ranges.isEmpty()) return emptyList()

        val candidates = mutableListOf<Highlight>()
        for (kind in HighlightKind.entries) {
            if (!track.has(kind.metric)) continue
            // The whole activity sets the scale, so a quiet stretch does not look exciting.
            val reference = signal(sampler, kind, listOf(track.range))
            val scale = Scale.of(reference.map { it.second }) ?: continue
            val points = signal(sampler, kind, ranges)
            val peaks = if (kind == HighlightKind.SUMMIT) listOfNotNull(points.maxByOrNull { it.second }) else localPeaks(points, minGapMs)
            peaks.take(3).forEach { (t, v) ->
                val score = scale.score(v) * kind.weight
                if (score > 0.15) candidates += Highlight(kind, t, v, score)
            }
        }
        // Best first; nearby moments merge into the stronger one.
        val chosen = mutableListOf<Highlight>()
        for (h in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= maxCount) break
            if (chosen.none { kotlin.math.abs(it.timeMs - h.timeMs) < minGapMs }) chosen += h
        }
        return chosen.sortedBy { it.timeMs }
    }

    /** How much each kind counts when ranking: speed and descents are what people want to see. */
    private val HighlightKind.weight: Double
        get() = when (this) {
            HighlightKind.TOP_SPEED, HighlightKind.FAST_DESCENT -> 1.0
            HighlightKind.ACCELERATION, HighlightKind.SUMMIT -> 0.9
            HighlightKind.STEEP_CLIMB, HighlightKind.POWER_PEAK -> 0.8
            HighlightKind.PEAK_HEART_RATE -> 0.7
        }

    /** (time, value) once a second over [ranges] for the quantity [kind] looks at. */
    private fun signal(sampler: TelemetrySampler, kind: HighlightKind, ranges: List<TimeRange>): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        for (r in ranges) {
            var t = r.startMs
            while (t <= r.endMs) {
                value(sampler, kind, t)?.let { out += t to it }
                t += 1000L
            }
        }
        return out
    }

    private fun value(sampler: TelemetrySampler, kind: HighlightKind, t: Long): Double? = when (kind) {
        HighlightKind.TOP_SPEED, HighlightKind.PEAK_HEART_RATE, HighlightKind.POWER_PEAK -> smoothed(sampler, kind.metric, t)
        HighlightKind.ACCELERATION -> {
            val before = smoothed(sampler, Metric.SPEED, t - ACCEL_WINDOW_S * 1000L)
            val after = smoothed(sampler, Metric.SPEED, t)
            if (before != null && after != null) after - before else null
        }
        HighlightKind.FAST_DESCENT, HighlightKind.STEEP_CLIMB -> {
            val half = VERTICAL_WINDOW_S * 500L
            val a = sampler.valueAt(Metric.ALTITUDE, t - half)
            val b = sampler.valueAt(Metric.ALTITUDE, t + half)
            if (a != null && b != null) {
                val rate = (b - a) / VERTICAL_WINDOW_S
                if (kind == HighlightKind.FAST_DESCENT) -rate else rate
            } else {
                null
            }
        }
        HighlightKind.SUMMIT -> sampler.valueAt(Metric.ALTITUDE, t)
    }

    private fun smoothed(sampler: TelemetrySampler, metric: Metric, t: Long): Double? {
        var sum = 0.0
        var n = 0
        for (i in -SMOOTH_S / 2..SMOOTH_S / 2) {
            sampler.valueAt(metric, t + i * 1000L)?.let { sum += it; n++ }
        }
        return if (n > 0) sum / n else null
    }

    /** Highest points first, each the maximum within [gapMs] around it. */
    private fun localPeaks(points: List<Pair<Long, Double>>, gapMs: Long): List<Pair<Long, Double>> {
        val peaks = mutableListOf<Pair<Long, Double>>()
        for (p in points.sortedByDescending { it.second }) {
            if (peaks.none { kotlin.math.abs(it.first - p.first) < gapMs }) peaks += p
            if (peaks.size >= 3) break
        }
        return peaks
    }

    /** Maps a value to 0..1 between the activity's typical (median) and its best (max). */
    private class Scale(val median: Double, val max: Double) {
        fun score(v: Double) = if (max <= median) 0.0 else ((v - median) / (max - median)).coerceIn(0.0, 1.0)

        companion object {
            fun of(values: List<Double>): Scale? {
                if (values.size < 5) return null
                val sorted = values.sorted()
                return Scale(sorted[sorted.size / 2], sorted.last())
            }
        }
    }
}
