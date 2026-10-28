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

    /** Ready-made ramps. */
    enum class Preset(val label: String, val points: List<Float>) {
        // Gentle on purpose: slow motion that looks filmed, not a trick (deep slow-downs need
        // footage shot at a high frame rate to stay smooth).
        SLOW_IN("Slow in", listOf(0f, 0.4f, 0.4f, 1f, 1f, 1f)),
        SLOW_OUT("Slow out", listOf(0f, 1f, 0.6f, 1f, 1f, 0.4f)),
        HERO("Hero", listOf(0f, 1f, 0.3f, 1f, 0.45f, 0.4f, 0.6f, 0.4f, 0.75f, 1f, 1f, 1f)),
        BULLET("Bullet", listOf(0f, 1.6f, 0.35f, 1.6f, 0.47f, 0.35f, 0.58f, 0.35f, 0.7f, 1.6f, 1f, 1.6f)),
        FLASH_IN("Flash in", listOf(0f, 2.5f, 0.3f, 1f, 1f, 1f)),
        FLASH_OUT("Flash out", listOf(0f, 1f, 0.7f, 1f, 1f, 2.5f)),
        MONTAGE("Montage", listOf(0f, 1.6f, 0.25f, 1.6f, 0.38f, 0.6f, 0.6f, 0.6f, 0.72f, 1.6f, 1f, 1.6f)),
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
        return (0 until n).map { k ->
            val from = spanMs * k / n
            val to = spanMs * (k + 1) / n
            val length = (timelineOf(to, spanMs, base, curve) - timelineOf(from, spanMs, base, curve)).coerceAtLeast(1L)
            Triple(from, to, ((to - from).toFloat() / length).coerceIn(MIN, MAX))
        }
    }
}
