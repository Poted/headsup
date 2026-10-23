package app.headsup.core.edit

/**
 * One audio clip on the timeline, like a clip on an audio track in DaVinci: it starts at
 * [startOnTimelineMs] and plays its source from [sourceStartMs] to [sourceEndMs] (0 = the end of
 * the source). Fades are only where set, in at its start and out before its end.
 */
data class AudioPiece(
    val sourceDurationMs: Long,
    val startOnTimelineMs: Long = 0L,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = 0L,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    /** Where the used part of the source ends. */
    val sourceStopMs: Long get() = if (sourceEndMs in 1..sourceDurationMs || sourceDurationMs <= 0L) sourceEndMs else sourceDurationMs

    val lengthMs: Long get() = (sourceStopMs - sourceStartMs).coerceAtLeast(0L)
    val endOnTimelineMs: Long get() = startOnTimelineMs + lengthMs

    /** Fade lengths as heard: together never longer than the clip. */
    val fadeIn: Long get() = fadeInMs.coerceIn(0L, lengthMs)
    val fadeOut: Long get() = fadeOutMs.coerceIn(0L, lengthMs - fadeIn)

    /** The source position heard at [timelineMs], or null outside the clip. */
    fun sourceAt(timelineMs: Long): Long? =
        if (timelineMs < startOnTimelineMs || timelineMs >= endOnTimelineMs) null else sourceStartMs + (timelineMs - startOnTimelineMs)

    /** Fade gain at [timelineMs], 0..1 (0 outside the clip). */
    fun gainAt(timelineMs: Long): Float {
        if (sourceAt(timelineMs) == null) return 0f
        val since = timelineMs - startOnTimelineMs
        val left = endOnTimelineMs - timelineMs
        var g = 1f
        if (fadeIn > 0 && since < fadeIn) g = since.toFloat() / fadeIn
        if (fadeOut > 0 && left < fadeOut) g = minOf(g, left.toFloat() / fadeOut)
        return g.coerceIn(0f, 1f)
    }

    /** Moves the start of the used part by [deltaMs] (the clip's end stays put), within the source. */
    fun trimStart(deltaMs: Long, minLengthMs: Long = 200L): AudioPiece {
        val d = deltaMs.coerceIn(-minOf(sourceStartMs, startOnTimelineMs), lengthMs - minLengthMs)
        return copy(startOnTimelineMs = startOnTimelineMs + d, sourceStartMs = sourceStartMs + d)
    }

    /** Moves the end of the used part by [deltaMs], within the source. */
    fun trimEnd(deltaMs: Long, minLengthMs: Long = 200L): AudioPiece {
        val max = if (sourceDurationMs > 0) sourceDurationMs else Long.MAX_VALUE
        val stop = (sourceStopMs + deltaMs).coerceIn(sourceStartMs + minLengthMs, max)
        return copy(sourceEndMs = if (stop >= sourceDurationMs && sourceDurationMs > 0) 0L else stop)
    }

    /** Slides the source under the clip by [deltaMs] (the clip keeps its place and length). */
    fun slip(deltaMs: Long): AudioPiece {
        val length = lengthMs
        val maxStart = if (sourceDurationMs > 0) sourceDurationMs - length else sourceStartMs
        val start = (sourceStartMs + deltaMs).coerceIn(0L, maxStart.coerceAtLeast(0L))
        return copy(sourceStartMs = start, sourceEndMs = start + length)
    }
}

/** Fading a clip in from black and out to black (picture and sound), in timeline time. */
object Fade {
    fun gain(positionMs: Long, durationMs: Long, fadeInMs: Long, fadeOutMs: Long): Float {
        var g = 1f
        if (fadeInMs > 0 && positionMs < fadeInMs) g = positionMs.toFloat() / fadeInMs
        val left = durationMs - positionMs
        if (fadeOutMs > 0 && left < fadeOutMs) g = minOf(g, left.toFloat() / fadeOutMs)
        return g.coerceIn(0f, 1f)
    }
}
