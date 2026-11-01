package app.headsup.core.edit

import kotlin.math.exp
import kotlin.math.ln

/**
 * Speed ramps: a clip's speed changing over its cut. Points are (where in the cut, 0..1; speed
 * factor on top of the clip's speed), as pairs in a row; between points the speed eases from one
 * to the next (in steps of ratio, so 0.25× to 1× takes as long as 1× to 4×). No points: steady.
 * Times here: "source" is the clip's file time from its cut start, "timeline" its time on the timeline.
 */
object SpeedCurve {
    /** Slowest and fastest speed a ramp can reach. */
    const val MIN = 0.1f
    const val MAX = 64f

    /** How fast the presets' fast parts go unless chosen otherwise. */
    const val DEFAULT_RUSH = 3f

    /** Rush speeds offered for the presets' fast parts: up to a lightning move into the frame. */
    val RUSHES = listOf(3f, 8f, 16f, 32f, 48f)

    /**
     * Ready-made ramps. Their slow parts (0 in [template]) take the slow speed the footage allows
     * ([slowSpeeds]); their fast parts (−1) the rush speed chosen; the rest are factors as they are.
     */
    enum class Preset(val label: String, private val template: List<Float>) {
        // As in reels and property films: the camera rushes in, eases into slow motion, rushes on.
        SPEED_RAMP("Speed ramp", listOf(0f, -1f, 0.15f, -1f, 0.38f, 0f, 0.62f, 0f, 0.85f, -1f, 1f, -1f)),
        // The camera shoots up to the door in a blink and brakes hard into slow motion.
        // The braking takes a little of the file, so it lasts a moment on screen, not one frame.
        RUSH_IN("Rush in", listOf(0f, -1f, 0.33f, -1f, 0.45f, 0f, 1f, 0f)),
        // In a blink, slow through the moment, out in a blink.
        RUSH_THROUGH("Rush through", listOf(0f, -1f, 0.27f, -1f, 0.37f, 0f, 0.63f, 0f, 0.73f, -1f, 1f, -1f)),
        FAST_IN("Fast in", listOf(0f, -1f, 0.2f, -1f, 0.48f, 0f, 1f, 0f)),
        FAST_OUT("Fast out", listOf(0f, 0f, 0.52f, 0f, 0.8f, -1f, 1f, -1f)),
        DOUBLE("Double ramp", listOf(0f, -1f, 0.1f, -1f, 0.24f, 0f, 0.38f, 0f, 0.5f, -1f, 0.62f, 0f, 0.76f, 0f, 0.9f, -1f, 1f, -1f)),
        SLOW_IN("Slow in", listOf(0f, 0f, 0.45f, 1f, 1f, 1f)),
        SLOW_OUT("Slow out", listOf(0f, 1f, 0.55f, 1f, 1f, 0f)),
        ;

        /** The ramp with its slow parts at [slow] and its fast parts at [rush]. */
        fun points(slow: Float, rush: Float = DEFAULT_RUSH): List<Float> = template.mapIndexed { i, v ->
            when {
                i % 2 == 0 -> v
                v == 0f -> slow
                v < 0f -> rush.coerceIn(1f, MAX)
                else -> v
            }
        }

        /** Whether the ramp has fast parts (the rush speed matters). */
        val rushes: Boolean get() = template.withIndex().any { it.index % 2 == 1 && it.value < 0f }
    }

    /**
     * The slowest speed at which footage shot at [sourceFps] still gives every timeline frame a
     * frame of its own ([timelineFps]): 120 fps on a 30 fps timeline, 0.25×. Slower, frames repeat
     * and the picture stutters. Null when the frame rate is unknown.
     */
    fun smoothLimit(sourceFps: Float?, timelineFps: Float): Float? =
        sourceFps?.takeIf { it > 0f }?.let { (timelineFps / it).coerceIn(MIN, MAX) }

    /**
     * The presets' slow speeds for this footage: "very slow" down to [smoothLimit] (every frame
     * shown), "slow" twice as fast; footage without frames to spare (30 fps on a 30 fps timeline)
     * gets 0.6× and 0.4×, which will stutter.
     */
    fun slowSpeeds(sourceFps: Float?, timelineFps: Float): Pair<Float, Float> {
        val limit = smoothLimit(sourceFps, timelineFps) ?: 0.5f
        return if (limit < 0.9f) minOf(0.75f, limit * 2f) to limit else 0.6f to 0.4f
    }

    /** The points as (where, factor), sorted. */
    fun points(curve: List<Float>): List<Pair<Float, Float>> =
        curve.chunked(2).filter { it.size == 2 }.map { it[0].coerceIn(0f, 1f) to it[1].coerceIn(MIN, MAX) }.sortedBy { it.first }

    /**
     * The curve of a cut split at [f] (0..1 of it): the part before and the part after, each over
     * its own 0..1, so both pieces play exactly as the whole did. Around the split the curve is
     * sampled finely, so its easing keeps its shape.
     */
    fun split(curve: List<Float>, f: Float): Pair<List<Float>, List<Float>> {
        if (curve.isEmpty() || f <= 0f || f >= 1f) return curve to curve
        val p = points(curve)
        // The points, plus samples across the stretch the split falls in.
        val before = p.lastOrNull { it.first <= f }?.first ?: 0f
        val after = p.firstOrNull { it.first >= f }?.first ?: 1f
        val samples = (1 until 12).map { k -> before + (after - before) * k / 12f }
        val all = (p.map { it.first } + samples + listOf(0f, f, 1f)).distinct().sorted()
        val left = all.filter { it <= f }.flatMap { u -> listOf(u / f, factor(curve, u)) }
        val right = all.filter { it >= f }.flatMap { u -> listOf((u - f) / (1f - f), factor(curve, u)) }
        return left to right
    }

    /** The curves of two pieces joined into one cut: [a] over its share [fa] of it, then [b]. */
    fun join(a: List<Float>, fa: Float, b: List<Float>): List<Float> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        val f = fa.coerceIn(0.001f, 0.999f)
        val pa = points(a).ifEmpty { listOf(0f to 1f, 1f to 1f) }
        val pb = points(b).ifEmpty { listOf(0f to 1f, 1f to 1f) }
        return (pa.map { (u, v) -> u * f to v } + pb.map { (u, v) -> f + u * (1f - f) to v })
            .distinctBy { it.first }.flatMap { listOf(it.first, it.second) }
    }

    /** The factor at [u] (0..1 of the cut). */
    fun factor(curve: List<Float>, u: Float): Float {
        val p = points(curve)
        if (p.isEmpty()) return 1f
        if (u <= p.first().first) return p.first().second
        if (u >= p.last().first) return p.last().second
        val i = p.indexOfFirst { it.first > u }
        val (u0, f0) = p[i - 1]
        val (u1, f1) = p[i]
        val t = ((u - u0) / (u1 - u0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return exp(ln(f0) + (ln(f1) - ln(f0)) * s)
    }

    /** The speed at [u] of the cut, for a clip at [base] speed. */
    fun speedAt(base: Float, curve: List<Float>, u: Float): Float = (base * factor(curve, u)).coerceIn(MIN, MAX)

    private const val STEPS = 240

    /** Timeline time of the first [sourceMs] of a [spanMs] cut. */
    fun timelineOf(sourceMs: Long, spanMs: Long, base: Float, curve: List<Float>): Long {
        if (curve.isEmpty() || spanMs <= 0L) return (sourceMs / base).toLong()
        val end = (sourceMs.toDouble() / spanMs).coerceIn(0.0, 1.0)
        val n = (STEPS * end).toInt().coerceAtLeast(1)
        val du = end / n
        var t = 0.0
        for (k in 0 until n) {
            val u = ((k + 0.5) * du).toFloat()
            t += du * spanMs / speedAt(base, curve, u)
        }
        // Past the end of the cut (a trim being dragged): at the last speed.
        if (sourceMs > spanMs) t += (sourceMs - spanMs) / speedAt(base, curve, 1f).toDouble()
        return t.toLong()
    }

    /** Source time at timeline time [timelineMs] into a [spanMs] cut (the reverse of [timelineOf]). */
    fun sourceOf(timelineMs: Long, spanMs: Long, base: Float, curve: List<Float>): Long {
        if (curve.isEmpty() || spanMs <= 0L) return (timelineMs * base).toLong()
        val du = 1.0 / STEPS
        var t = 0.0
        for (k in 0 until STEPS) {
            val u = ((k + 0.5) * du).toFloat()
            val step = du * spanMs / speedAt(base, curve, u)
            if (t + step >= timelineMs) {
                val f = ((timelineMs - t) / step).coerceIn(0.0, 1.0)
                return ((k + f) * du * spanMs).toLong()
            }
            t += step
        }
        return spanMs + ((timelineMs - t) * speedAt(base, curve, 1f)).toLong()
    }

    /**
     * The cut as pieces of steady speed, for an export that cannot change speed smoothly: (source
     * start, source end, speed), each piece's speed making it last exactly its share of the timeline.
     */
    fun pieces(spanMs: Long, base: Float, curve: List<Float>, pieceMs: Long = 250L, maxPieces: Int = 48): List<Triple<Long, Long, Float>> {
        if (curve.isEmpty() || spanMs <= 0L) return listOf(Triple(0L, spanMs, base))
        // Cut evenly both in timeline time (a slow part is cut as finely as it plays) and in file
        // time (a fast rush braking into slow motion passes much file in little time): every
        // change of speed is cut finely enough to show no steps. Steady stretches merge below.
        val total = timelineOf(spanMs, spanMs, base, curve).coerceAtLeast(1L)
        val byTime = (total / pieceMs).toInt().coerceIn(1, maxPieces)
        val byFile = (spanMs / pieceMs).toInt().coerceIn(1, maxPieces)
        val bounds = ((0..byTime).map { k -> sourceOf(total * k / byTime, spanMs, base, curve) } + (0..byFile).map { k -> spanMs * k / byFile })
            .map { it.coerceIn(0L, spanMs) }.plus(listOf(0L, spanMs)).distinct().sorted()
        val n = bounds.size - 1
        val fine = (0 until n).filter { bounds[it + 1] > bounds[it] }.map { k ->
            val from = bounds[k]
            val to = bounds[k + 1]
            val length = (timelineOf(to, spanMs, base, curve) - timelineOf(from, spanMs, base, curve)).coerceAtLeast(1L)
            Triple(from, to, ((to - from).toFloat() / length).coerceIn(MIN, MAX))
        }
        // Neighbours at nearly the same speed become one piece: every join is a seam in the export.
        val merged = mutableListOf<Triple<Long, Long, Float>>()
        for (p in fine) {
            val last = merged.lastOrNull()
            if (last != null && kotlin.math.abs(p.third - last.third) < 0.06f * last.third) {
                val from = last.first
                val to = p.second
                val length = (timelineOf(to, spanMs, base, curve) - timelineOf(from, spanMs, base, curve)).coerceAtLeast(1L)
                merged[merged.lastIndex] = Triple(from, to, ((to - from).toFloat() / length).coerceIn(MIN, MAX))
            } else {
                merged += p
            }
        }
        // Every piece is a seam in the export: a curve changing all the time is cut coarser.
        if (merged.size > maxPieces && pieceMs < 2_000L) return pieces(spanMs, base, curve, pieceMs * 3 / 2, maxPieces)
        return merged
    }
}
