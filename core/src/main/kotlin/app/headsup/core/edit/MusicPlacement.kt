package app.headsup.core.edit

/**
 * Where a music track sits on the timeline: it starts at [startOnTimelineMs], playing the song
 * from [songStartMs]; when the song ends it starts again from [songStartMs] if [loop], otherwise
 * the rest is silent. Fades are only where asked: in over [fadeInMs] from its start, out over
 * [fadeOutMs] before it stops (the song's end without looping, else the timeline's end).
 */
data class MusicPlacement(
    val songDurationMs: Long,
    val startOnTimelineMs: Long = 0L,
    val songStartMs: Long = 0L,
    val loop: Boolean = true,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    /** One stretch of song on the timeline, for export. */
    data class Segment(val timelineStartMs: Long, val songFromMs: Long, val songToMs: Long) {
        val lengthMs: Long get() = songToMs - songFromMs
    }

    private val playable: Long get() = (songDurationMs - songStartMs).coerceAtLeast(0L)

    /** Where the music ends on a timeline of [timelineMs]. */
    fun endMs(timelineMs: Long): Long =
        if (loop || playable == 0L) timelineMs else minOf(timelineMs, startOnTimelineMs + playable)

    /** The song position heard at [timelineMs], or null where the music is silent. */
    fun songPositionAt(timelineMs: Long, totalMs: Long): Long? {
        if (timelineMs < startOnTimelineMs || timelineMs >= endMs(totalMs) || playable == 0L) return null
        return songStartMs + (timelineMs - startOnTimelineMs) % playable
    }

    /** Fade gain at [timelineMs], 0..1. */
    fun gainAt(timelineMs: Long, totalMs: Long): Float {
        if (songPositionAt(timelineMs, totalMs) == null) return 0f
        var g = 1f
        val sinceStart = timelineMs - startOnTimelineMs
        if (fadeInMs > 0 && sinceStart < fadeInMs) g = sinceStart.toFloat() / fadeInMs
        val left = endMs(totalMs) - timelineMs
        if (fadeOutMs > 0 && left < fadeOutMs) g = minOf(g, left.toFloat() / fadeOutMs)
        return g.coerceIn(0f, 1f)
    }

    /** The song pieces covering the timeline, back to back from [startOnTimelineMs]. */
    fun segments(totalMs: Long): List<Segment> {
        if (playable == 0L || startOnTimelineMs >= totalMs) return emptyList()
        val end = endMs(totalMs)
        val out = mutableListOf<Segment>()
        var at = startOnTimelineMs
        while (at < end) {
            val length = minOf(playable, end - at)
            out += Segment(at, songStartMs, songStartMs + length)
            at += length
        }
        return out
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
