package app.headsup.core.edit

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A song's loudness over time, for drawing its waveform, and its accents (beats, hits, entries):
 * moments where the loudness jumps clearly above its recent level.
 */
class AudioEnvelope(
    /** Loudness (RMS, 0..1) per [stepMs]. */
    val levels: FloatArray,
    val stepMs: Int,
) {
    val durationMs: Long get() = levels.size.toLong() * stepMs

    /** Loudness at [ms] into the song (0 outside it). */
    fun levelAt(ms: Long): Float = levels.getOrElse((ms / stepMs).toInt()) { 0f }

    /** Peak loudness between [fromMs] and [toMs], for drawing at any zoom. */
    fun peak(fromMs: Long, toMs: Long): Float {
        val a = (fromMs / stepMs).toInt().coerceIn(0, levels.size)
        val b = ((toMs + stepMs - 1) / stepMs).toInt().coerceIn(a, levels.size)
        var m = 0f
        for (i in a until b) m = max(m, levels[i])
        return m
    }

    /** One accent: its time in the song and how strong it is (0..1, relative to the strongest). */
    data class Accent(val ms: Long, val strength: Float)

    /**
     * Onsets: rises in loudness well above the average rise around them (a second either side),
     * at least [minGapMs] apart; the strongest are kept when two are close.
     */
    fun accents(minGapMs: Long = 180L): List<Accent> {
        if (levels.size < 3) return emptyList()
        val rise = FloatArray(levels.size) { i -> if (i == 0) 0f else max(0f, levels[i] - levels[i - 1]) }
        val window = (1000 / stepMs).coerceAtLeast(1)
        val found = mutableListOf<Accent>()
        // Running sums for the local mean and spread of the rises.
        val prefix = DoubleArray(rise.size + 1)
        val prefixSq = DoubleArray(rise.size + 1)
        for (i in rise.indices) {
            prefix[i + 1] = prefix[i] + rise[i]
            prefixSq[i + 1] = prefixSq[i] + rise[i] * rise[i]
        }
        for (i in 1 until rise.size - 1) {
            if (rise[i] <= 0f || rise[i] < rise[i - 1] || rise[i] < rise[i + 1]) continue
            val a = (i - window).coerceAtLeast(0)
            val b = (i + window + 1).coerceAtMost(rise.size)
            val n = (b - a).toDouble()
            val mean = (prefix[b] - prefix[a]) / n
            val sd = sqrt(((prefixSq[b] - prefixSq[a]) / n - mean * mean).coerceAtLeast(0.0))
            if (rise[i] > mean + 1.5 * sd && rise[i] > 0.02f) found += Accent(i.toLong() * stepMs, rise[i])
        }
        // Keep the strongest within each gap.
        val kept = mutableListOf<Accent>()
        for (a in found.sortedByDescending { it.strength }) {
            if (kept.none { kotlin.math.abs(it.ms - a.ms) < minGapMs }) kept += a
        }
        val top = kept.maxOfOrNull { it.strength } ?: return emptyList()
        return kept.map { it.copy(strength = it.strength / top) }.sortedBy { it.ms }
    }

    companion object {
        /** Builds the envelope from mono samples (-1..1) at [sampleRate]. */
        fun fromSamples(samples: FloatArray, sampleRate: Int, stepMs: Int = 20): AudioEnvelope {
            val perStep = (sampleRate * stepMs / 1000).coerceAtLeast(1)
            val n = (samples.size + perStep - 1) / perStep
            val levels = FloatArray(n) { i ->
                var sum = 0.0
                val from = i * perStep
                val to = minOf(from + perStep, samples.size)
                for (k in from until to) sum += samples[k] * samples[k]
                sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
            }
            return AudioEnvelope(levels, stepMs)
        }
    }
}
