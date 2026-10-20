package app.headsup.core.sync

import app.headsup.core.track.ActivityTrack
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ClipInfo(val durationMs: Long, val evidence: List<TimeEvidence>)

enum class Confidence { HIGH, MEDIUM, LOW }

/** How the zone of a clock reading was decided. */
enum class ZoneReason {
    /** A UTC-labelled value taken as real UTC. */
    AS_UTC,

    /** Interpreted in the zone the watch recorded during the activity. */
    ACTIVITY_ZONE,

    /** Interpreted in the phone's current time zone. */
    DEVICE_ZONE,

    /** Any other whole or fractional hour offset, found by search. */
    SEARCHED,
}

/** One interpretation of one piece of evidence. */
data class Candidate(
    val evidence: TimeEvidence,
    val startUtcMs: Long,
    /** Offset subtracted from the reading to get UTC (0 for [ZoneReason.AS_UTC]). */
    val zoneOffsetSeconds: Int,
    val zoneReason: ZoneReason,
    /** Whether the reading was treated as the start or the end of the recording. */
    val anchor: TimeAnchor,
    val weight: Double,
)

data class SyncResult(
    val startUtcMs: Long,
    val trackIndex: Int,
    /** Fraction of the clip covered by the track, 0..1. */
    val coverage: Double,
    val confidence: Confidence,
    val primary: Candidate,
    /** Other evidence that independently gives the same start time. */
    val agreeing: List<Candidate>,
    /** Distinct start times that would also fit inside an activity. */
    val alternatives: Int,
)

/**
 * Decides when a clip started, in UTC, and which activity covers it.
 *
 * Every piece of evidence is expanded into candidates (zone interpretation × start/end
 * reading), each weighted by how plausible that interpretation is. Candidates that land
 * within [AGREEMENT_MS] of each other are merged, so independent sources confirm each other.
 * The best-scoring start that puts the whole clip inside an activity wins.
 */
class SyncResolver(private val deviceZone: ZoneId) {

    fun resolve(clip: ClipInfo, tracks: List<ActivityTrack>): SyncResult? {
        if (tracks.isEmpty()) return null
        val activityOffsets = tracks.mapNotNull { it.utcOffsetSeconds }.distinct()
        val candidates = clip.evidence
            .flatMap { candidatesFor(it, clip.durationMs, activityOffsets) }
            .filter { c -> tracks.any { coverage(c.startUtcMs, clip.durationMs, it) > 0.0 } }
            .sortedByDescending { it.weight }
        if (candidates.isEmpty()) return null

        val clusters = cluster(candidates).map { members ->
            val start = members.first().startUtcMs
            val (trackIndex, cover) = tracks.indices
                .map { it to coverage(start, clip.durationMs, tracks[it]) }
                .maxBy { it.second }
            val uncoveredMs = ((1.0 - cover) * clip.durationMs).toLong()
            Cluster(members, trackIndex, cover, fits = uncoveredMs <= FIT_TOLERANCE_MS)
        }

        val fitting = clusters.filter { it.fits }.sortedByDescending { it.score }
        val best = fitting.firstOrNull()
            ?: clusters.maxWith(compareBy<Cluster> { it.coverage }.thenBy { it.score })
        val runnerUp = fitting.firstOrNull { it !== best }
        val confidence = when {
            !best.fits -> Confidence.LOW
            best.score >= 1.0 && (runnerUp == null || best.score >= 2 * runnerUp.score) -> Confidence.HIGH
            best.score >= MIN_MEDIUM_SCORE -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return SyncResult(
            startUtcMs = best.members.first().startUtcMs,
            trackIndex = best.trackIndex,
            coverage = best.coverage,
            confidence = confidence,
            primary = best.members.first(),
            agreeing = best.members.drop(1),
            alternatives = fitting.count { it !== best },
        )
    }

    private class Cluster(val members: List<Candidate>, val trackIndex: Int, val coverage: Double, val fits: Boolean) {
        val score = members.sumOf { it.weight }
    }

    /** Greedy grouping around the heaviest candidates; each evidence contributes at most once per group. */
    private fun cluster(sorted: List<Candidate>): List<List<Candidate>> {
        val used = BooleanArray(sorted.size)
        val groups = mutableListOf<List<Candidate>>()
        for (i in sorted.indices) {
            if (used[i]) continue
            val seed = sorted[i]
            used[i] = true
            val group = mutableListOf(seed)
            for (j in i + 1 until sorted.size) {
                val c = sorted[j]
                if (used[j] || abs(c.startUtcMs - seed.startUtcMs) > AGREEMENT_MS) continue
                if (group.any { it.evidence == c.evidence }) {
                    used[j] = true // a weaker reading of evidence already in the group
                    continue
                }
                group += c
                used[j] = true
            }
            groups += group
        }
        return groups
    }

    private fun candidatesFor(evidence: TimeEvidence, durationMs: Long, activityOffsets: List<Int>): List<Candidate> {
        val zones = LinkedHashMap<Int, Pair<ZoneReason, Double>>()
        fun offer(offset: Int, reason: ZoneReason, weight: Double) {
            zones.putIfAbsent(offset, reason to weight)
        }

        val wallClockMs: Long
        when (val reading = evidence.reading) {
            is ClockReading.Local -> {
                wallClockMs = reading.dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
                activityOffsets.forEach { offer(it, ZoneReason.ACTIVITY_ZONE, 1.0) }
                offer(deviceZone.rules.getOffset(reading.dateTime).totalSeconds, ZoneReason.DEVICE_ZONE, 0.5)
            }
            is ClockReading.LabelledUtc -> {
                wallClockMs = reading.epochMs
                offer(0, ZoneReason.AS_UTC, 1.0)
                // Local time stored as if it were UTC.
                activityOffsets.forEach { offer(it, ZoneReason.ACTIVITY_ZONE, 0.5) }
                offer(deviceZone.rules.getOffset(java.time.Instant.ofEpochMilli(reading.epochMs)).totalSeconds,
                    ZoneReason.DEVICE_ZONE, 0.3)
            }
        }
        for (offset in MIN_OFFSET_S..MAX_OFFSET_S step OFFSET_STEP_S) offer(offset, ZoneReason.SEARCHED, 0.1)

        val anchors = when (evidence.anchor) {
            TimeAnchor.START -> listOf(TimeAnchor.START to 1.0, TimeAnchor.END to 0.3)
            TimeAnchor.END -> listOf(TimeAnchor.END to 1.0, TimeAnchor.START to 0.3)
            TimeAnchor.UNKNOWN -> listOf(TimeAnchor.START to 0.6, TimeAnchor.END to 0.6)
        }

        return zones.flatMap { (offset, zone) ->
            val (reason, zoneWeight) = zone
            val utcMs = wallClockMs - offset * 1000L
            anchors.map { (anchor, anchorWeight) ->
                Candidate(
                    evidence = evidence,
                    startUtcMs = if (anchor == TimeAnchor.END) utcMs - durationMs else utcMs,
                    zoneOffsetSeconds = offset,
                    zoneReason = reason,
                    anchor = anchor,
                    weight = evidence.source.reliability * zoneWeight * anchorWeight,
                )
            }
        }
    }

    companion object {
        /** Readings this close are treated as the same start; file names have 1 s resolution. */
        const val AGREEMENT_MS = 2_000L

        /** A clip still "fits" if this much of it runs past the activity (watch stopped slightly early). */
        const val FIT_TOLERANCE_MS = 5_000L

        /** Below this only a searched zone supports the start, which is no better than a guess. */
        private const val MIN_MEDIUM_SCORE = 0.2

        private const val MIN_OFFSET_S = -12 * 3600
        private const val MAX_OFFSET_S = 14 * 3600
        private const val OFFSET_STEP_S = 15 * 60

        fun coverage(startMs: Long, durationMs: Long, track: ActivityTrack): Double {
            val end = startMs + durationMs
            if (durationMs <= 0) return if (startMs in track.startMs..track.endMs) 1.0 else 0.0
            val overlap = min(end, track.endMs) - max(startMs, track.startMs)
            return (overlap.toDouble() / durationMs).coerceIn(0.0, 1.0)
        }
    }
}
