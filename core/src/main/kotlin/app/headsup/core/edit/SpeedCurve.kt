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
    const val MAX = 8f

    /**
     * Ready-made ramps. Their slow parts (0 in [template]) take the slow speed the footage allows
     * ([slowSpeeds]); the rest are factors as they are.
     */
    enum class Preset(val label: String, private val template: List<Float>) {
        // As in reels and property films: the camera rushes in, eases into slow motion, rushes on.
        SPEED_RAMP("Speed ramp", listOf(0f, 3f, 0.15f, 3f, 0.38f, 0f, 0.62f, 0f, 0.85f, 3f, 1f, 3f)),
        FAST_IN("Fast in", listOf(0f, 3f, 0.2f, 3f, 0.48f, 0f, 1f, 0f)),
        FAST_OUT("Fast out", listOf(0f, 0f, 0.52f, 0f, 0.8f, 3f, 1f, 3f)),
        DOUBLE("Double ramp", listOf(0f, 3f, 0.1f, 3f, 0.24f, 0f, 0.38f, 0f, 0.5f, 3f, 0.62f, 0f, 0.76f, 0f, 0.9f, 3f, 1f, 3f)),
        SLOW_IN("Slow in", listOf(0f, 0f, 0.45f, 1f, 1f, 1f)),
        SLOW_OUT("Slow out", listOf(0f, 1f, 0.55f, 1f, 1f, 0f)),
        ;

        /** The ramp with its slow parts at [slow]. */
        fun points(slow: Float): List<Float> = template.mapIndexed { i, v -> if (i % 2 == 1 && v == 0f) slow else v }
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
    fun pieces(spanMs: Long, base: Float, curve: List<Float>, pieceMs: Long = 350L, maxPieces: Int = 32): List<Triple<Long, Long, Float>> {
        if (curve.isEmpty() || spanMs <= 0L) return listOf(Triple(0L, spanMs, base))
        val n = (spanMs / pieceMs).toInt().coerceIn(1, maxPieces)
        val fine = (0 until n).map { k ->
            val from = spanMs * k / n
            val to = spanMs * (k + 1) / n
            val length = (timelineOf(to, spanMs, base, curve) - timelineOf(from, spanMs, base, curve)).coerceAtLeast(1L)
            Triple(from, to, ((to - from).toFloat() / length).coerceIn(MIN, MAX))
        }
        // Neighbours at nearly the same speed become one piece: every join is a seam in the export.
        val merged = mutableListOf<Triple<Long, Long, Float>>()
        for (p in fine) {
            val last = merged.lastOrNull()
            if (last != null && kotlin.math.abs(p.third - last.third) < 0.03f * last.third) {
                val from = last.first
                val to = p.second
                val length = (timelineOf(to, spanMs, base, curve) - timelineOf(from, spanMs, base, curve)).coerceAtLeast(1L)
                merged[merged.lastIndex] = Triple(from, to, ((to - from).toFloat() / length).coerceIn(MIN, MAX))
            } else {
                merged += p
            }
        }
        return merged
    }
}
