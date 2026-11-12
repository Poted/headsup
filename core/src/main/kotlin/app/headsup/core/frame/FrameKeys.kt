package app.headsup.core.frame

/** Where the framing is at one moment of a clip (file time): its zoom and the point at the frame's middle. */
data class FrameKey(val timeMs: Long, val zoom: Float, val centerX: Float, val centerY: Float)

/**
 * The framing moving through a clip (a Ken Burns move, or the frame following the subject): keys
 * of zoom and centre, eased from one to the next; before the first and after the last it holds.
 * The rest of the framing (shape, turn, fit, perspective) stays the clip's own.
 */
object FrameKeys {
    fun at(base: Reframe, keys: List<FrameKey>, fileMs: Long): Reframe {
        if (keys.isEmpty()) return base
        if (keys.size == 1 || fileMs <= keys.first().timeMs) return base.with(keys.first())
        if (fileMs >= keys.last().timeMs) return base.with(keys.last())
        val i = keys.indexOfLast { it.timeMs <= fileMs }.coerceIn(0, keys.size - 2)
        val a = keys[i]
        val b = keys[i + 1]
        val t = ((fileMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        // Two keys (a Ken Burns move): eased in and out; many (following): straight, as tracked.
        val e = if (keys.size == 2) t * t * (3f - 2f * t) else t
        return base.copy(
            zoom = a.zoom + (b.zoom - a.zoom) * e,
            centerX = a.centerX + (b.centerX - a.centerX) * e,
            centerY = a.centerY + (b.centerY - a.centerY) * e,
        )
    }

    private fun Reframe.with(k: FrameKey) = copy(zoom = k.zoom, centerX = k.centerX, centerY = k.centerY)

    /** [keys] with the one nearest [fileMs] set to [r]'s zoom and centre (its time kept). */
    fun setNearest(keys: List<FrameKey>, fileMs: Long, r: Reframe): List<FrameKey> {
        if (keys.isEmpty()) return keys
        val i = keys.indices.minByOrNull { kotlin.math.abs(keys[it].timeMs - fileMs) } ?: return keys
        return keys.mapIndexed { k, key -> if (k == i) key.copy(zoom = r.zoom, centerX = r.centerX, centerY = r.centerY) else key }
    }

    /**
     * Keys that keep a point ([xs], [ys], the subject's middle, fractions of the picture, at
     * [timesMs]) in the frame, evened out so the frame glides instead of shaking: a moving
     * average over about [smoothMs], then one key every [stepMs].
     */
    fun following(timesMs: LongArray, xs: FloatArray, ys: FloatArray, zoom: Float, smoothMs: Long = 1200L, stepMs: Long = 400L): List<FrameKey> {
        if (timesMs.isEmpty()) return emptyList()
        val out = ArrayList<FrameKey>()
        var next = timesMs.first()
        for (i in timesMs.indices) {
            if (timesMs[i] < next && i != timesMs.lastIndex) continue
            next = timesMs[i] + stepMs
            var sx = 0f
            var sy = 0f
            var n = 0
            for (j in timesMs.indices) {
                if (kotlin.math.abs(timesMs[j] - timesMs[i]) > smoothMs / 2) continue
                sx += xs[j]; sy += ys[j]; n++
            }
            out += FrameKey(timesMs[i], zoom, sx / n, sy / n)
        }
        return out
    }
}
