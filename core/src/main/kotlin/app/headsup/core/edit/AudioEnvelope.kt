package app.headsup.core.edit

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A sound's loudness over time, for drawing its waveform, and its rhythm ([rhythm]): a beat grid
 * for music with a steady pulse, and accents where the loudness jumps clearly.
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

    /** One accent: its time in the source and how strong it is (0..1, relative to the strong ones). */
    data class Accent(val ms: Long, val strength: Float)

    /** What the timeline shows of a sound: its beat grid (if it has a steady one) and its clear accents. */
    data class Rhythm(val bpm: Float?, val beats: List<Long>, val accents: List<Accent>)

    /**
     * Onset strength per step: how much the loudness rises, in decibels, ignoring the quiet parts
     * (more than 40 dB under the loudest), lightly smoothed.
     */
    fun onsetStrength(): FloatArray {
        val top = levels.maxOrNull()?.takeIf { it > 0f } ?: return FloatArray(levels.size)
        val floor = top * 0.01f
        val db = FloatArray(levels.size) { 20f * log10(max(levels[it], floor) / top) }
        val rise = FloatArray(levels.size) { i -> if (i == 0) 0f else max(0f, db[i] - db[i - 1]) }
        return FloatArray(rise.size) { i ->
            0.25f * rise.getOrElse(i - 1) { 0f } + 0.5f * rise[i] + 0.25f * rise.getOrElse(i + 1) { 0f }
        }
    }

    /**
     * The beat grid and accents. The tempo comes from the autocorrelation of the onset strength
     * (60–200 BPM, leaning towards 120); the grid is placed where the onsets line up best and each
     * beat is nudged to the nearest onset. Without a clear periodicity (birds, wind, speech) there
     * is no grid. Accents are onsets well above their surroundings, at least [minGapMs] apart.
     */
    fun rhythm(minGapMs: Long = 150L): Rhythm {
        val o = onsetStrength()
        if (o.size < 3 * (1000 / stepMs)) return Rhythm(null, emptyList(), accents(o, minGapMs))
        val perSecond = 1000f / stepMs
        val mean = o.average().toFloat()
        val c = FloatArray(o.size) { o[it] - mean }
        val minLag = (perSecond * 60 / 200).toInt().coerceAtLeast(1)
        val maxLag = (perSecond * 60 / 60).toInt().coerceAtMost(o.size / 2)
        fun ac(lag: Int): Double {
            var sum = 0.0
            for (i in 0 until c.size - lag) sum += c[i] * c[i + lag]
            return sum / (c.size - lag)
        }
        val zero = ac(0).takeIf { it > 1e-9 } ?: return Rhythm(null, emptyList(), accents(o, minGapMs))
        var bestLag = -1
        var bestScore = 0.0
        val values = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            values[lag] = ac(lag)
            val bpm = 60 * perSecond / lag
            val w = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0)).let { it * it } / 0.8)
            val score = values[lag] / zero * w
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        val confidence = if (bestLag > 0) values[bestLag] / zero else 0.0
        if (bestLag < 0 || confidence < 0.2) return Rhythm(null, emptyList(), accents(o, minGapMs))
        // Fractional period from the neighbours, then the phase where onsets line up best.
        val a = ac(bestLag - 1)
        val b = values[bestLag]
        val d = ac(bestLag + 1)
        val curve = a - 2 * b + d
        val period = bestLag + if (curve < 0) ((a - d) / (2 * curve)).coerceIn(-0.5, 0.5) else 0.0
        var bestPhase = 0.0
        var bestSum = -1.0
        var phase = 0.0
        while (phase < period) {
            var sum = 0.0
            var t = phase
            while (t < o.size) { sum += o[t.toInt()]; t += period }
            if (sum > bestSum) { bestSum = sum; bestPhase = phase }
            phase += 0.5
        }
        // A real pulse: clear rises on the beats (2 dB on average), well above the rest.
        val beatCount = ((o.size - bestPhase) / period).toInt().coerceAtLeast(1)
        val onBeats = bestSum / beatCount
        if (onBeats < 2.0 || onBeats < 3 * mean) return Rhythm(null, emptyList(), accents(o, minGapMs))
        val beats = mutableListOf<Long>()
        var t = bestPhase
        while (t < o.size) {
            val i = t.toInt()
            val near = (max(0, i - 2)..minOf(o.size - 1, i + 2)).maxByOrNull { o[it] } ?: i
            beats += near.toLong() * stepMs
            t += period
        }
        return Rhythm((60 * perSecond / period).toFloat(), beats, accents(o, minGapMs))
    }

    private fun accents(o: FloatArray, minGapMs: Long): List<Accent> {
        if (o.size < 3) return emptyList()
        val window = (1000 / stepMs).coerceAtLeast(1)
        val prefix = DoubleArray(o.size + 1)
        val prefixSq = DoubleArray(o.size + 1)
        for (i in o.indices) {
            prefix[i + 1] = prefix[i] + o[i]
            prefixSq[i + 1] = prefixSq[i] + o[i] * o[i]
        }
        val sorted = o.sorted()
        val strong = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.lastIndex)].coerceAtLeast(1e-3f)
        val found = mutableListOf<Accent>()
        for (i in 1 until o.size - 1) {
            if (o[i] <= 0f || o[i] < o[i - 1] || o[i] < o[i + 1]) continue
            val a = (i - window).coerceAtLeast(0)
            val b = (i + window + 1).coerceAtMost(o.size)
            val n = (b - a).toDouble()
            val mean = (prefix[b] - prefix[a]) / n
            val sd = sqrt(((prefixSq[b] - prefixSq[a]) / n - mean * mean).coerceAtLeast(0.0))
            // Clearly above the surroundings and a sizeable jump (over 3 dB).
            if (o[i] > mean + 2.5 * sd && o[i] > 3f) found += Accent(i.toLong() * stepMs, (o[i] / strong).coerceAtMost(1f))
        }
        val kept = mutableListOf<Accent>()
        for (x in found.sortedByDescending { it.strength }) {
            if (kept.none { kotlin.math.abs(it.ms - x.ms) < minGapMs }) kept += x
        }
        return kept.sortedBy { it.ms }
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
