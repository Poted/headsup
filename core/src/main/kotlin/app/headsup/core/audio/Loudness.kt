package app.headsup.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Loudness as ITU-R BS.1770 / EBU R128 measure it (LUFS): the sound K-weighted (a high shelf
 * for how the head hears, a high pass below 38 Hz), its mean square per channel summed, then
 * averaged over 400 ms blocks gated at −70 LUFS and at 10 LU under the result. Kept per
 * [BLOCK_MS] block, so the sound of a whole timeline can be added up from its pieces.
 */
object Loudness {
    /** The step of the measured power. */
    const val BLOCK_MS = 100

    /** The sound of a file measured: K-weighted power per [BLOCK_MS] (channels summed) and the highest sample, 0..1. */
    class Measure(val power: FloatArray, val peak: Float)

    /** Measures sound fed to it in pieces (16-bit, interleaved). */
    class Meter(private val sampleRate: Int, private val channels: Int) {
        private val filters = List(channels) { kWeighting(sampleRate) }
        private val perBlock = sampleRate * BLOCK_MS / 1000
        private var inBlock = 0
        private var sum = 0.0
        private val power = ArrayList<Float>()
        private var peak = 0f

        fun add(samples: ShortArray, count: Int = samples.size) {
            var i = 0
            while (i + channels <= count) {
                for (c in 0 until channels) {
                    val v = samples[i + c] / 32768f
                    if (abs(v) > peak) peak = abs(v)
                    val k = filters[c].process(v.toDouble())
                    sum += k * k
                }
                i += channels
                if (++inBlock == perBlock) {
                    power += (sum / perBlock).toFloat()
                    sum = 0.0
                    inBlock = 0
                }
            }
        }

        fun result(): Measure {
            if (inBlock > perBlock / 4) power += (sum / inBlock).toFloat()
            return Measure(power.toFloatArray(), peak)
        }
    }

    /** LUFS of a mean square (channels summed). */
    fun lufs(meanSquare: Double): Double = if (meanSquare <= 0.0) -200.0 else -0.691 + 10.0 * log10(meanSquare)

    /** Integrated loudness (LUFS) of [power] (per [BLOCK_MS]); −200 for silence. */
    fun integrated(power: FloatArray): Double {
        val per = 400 / BLOCK_MS
        if (power.size < per) return if (power.isEmpty()) -200.0 else lufs(power.average())
        // 400 ms blocks, 75 % overlapping.
        val blocks = DoubleArray(power.size - per + 1) { i -> (i until i + per).sumOf { power[it].toDouble() } / per }
        val loud = blocks.filter { lufs(it) > -70.0 }
        if (loud.isEmpty()) return -200.0
        val relative = lufs(loud.average()) - 10.0
        val kept = loud.filter { lufs(it) > relative }
        return if (kept.isEmpty()) -200.0 else lufs(kept.average())
    }

    /** The gain (linear) that brings [lufs] to [target], never above what keeps [peak] under [ceilingDb] dBFS, nor above +20 dB. */
    fun gainTo(target: Double, lufs: Double, peak: Float, ceilingDb: Double = -1.0): Float {
        if (lufs <= -100.0) return 1f
        val wanted = 10.0.pow((target - lufs) / 20.0)
        val ceiling = if (peak > 0f) 10.0.pow(ceilingDb / 20.0) / peak else Double.MAX_VALUE
        return minOf(wanted, ceiling, 10.0).toFloat()
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private class Chain(val a: Biquad, val b: Biquad) {
        fun process(x: Double) = b.process(a.process(x))
    }

    /** BS.1770's two filters, made for any sample rate (as pyloudnorm and libebur128 do). */
    private fun kWeighting(rate: Int): Chain {
        // High shelf: +4 dB above about 1.7 kHz.
        val g = 3.99984385397
        val q1 = 0.7071752369554193
        val f1 = 1681.9744509555319
        val k1 = tan(PI * f1 / rate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k1 / q1 + k1 * k1
        val shelf = Biquad(
            (vh + vb * k1 / q1 + k1 * k1) / a0,
            2.0 * (k1 * k1 - vh) / a0,
            (vh - vb * k1 / q1 + k1 * k1) / a0,
            2.0 * (k1 * k1 - 1.0) / a0,
            (1.0 - k1 / q1 + k1 * k1) / a0,
        )
        // High pass at about 38 Hz.
        val q2 = 0.5003270373253953
        val f2 = 38.13547087613982
        val k2 = tan(PI * f2 / rate)
        val d = 1.0 + k2 / q2 + k2 * k2
        val pass = Biquad(1.0, -2.0, 1.0, 2.0 * (k2 * k2 - 1.0) / d, (1.0 - k2 / q2 + k2 * k2) / d)
        return Chain(shelf, pass)
    }

    @Suppress("unused")
    private fun unused() = sin(0.0) + cos(0.0) + sqrt(0.0) + max(0, 0)
}

/**
 * Music lowered under the clips' own sound (speech, a scene's sound): where that sound is above
 * [thresholdLufs] the music goes down by [depthDb], easing down over [attackMs] and back up
 * over [releaseMs] after it stops (short pauses between words keep it down).
 */
object Ducking {
    fun envelope(sound: FloatArray, thresholdLufs: Double = -45.0, depthDb: Float = -12f, attackMs: Int = 250, releaseMs: Int = 700, holdMs: Int = 400): FloatArray {
        val n = sound.size
        val low = 10.0.pow(depthDb / 20.0).toFloat()
        // Where the sound is (held a little over gaps).
        val on = BooleanArray(n) { Loudness.lufs(sound[it].toDouble()) > thresholdLufs }
        val hold = holdMs / Loudness.BLOCK_MS
        var last = -1_000_000
        val held = BooleanArray(n)
        for (i in 0 until n) {
            if (on[i]) last = i
            held[i] = i - last <= hold
        }
        // Starting early enough to be down when the sound begins.
        val lead = attackMs / Loudness.BLOCK_MS
        val wanted = BooleanArray(n) { i -> (i until minOf(n, i + lead + 1)).any { held[it] } }
        val out = FloatArray(n)
        var g = 1f
        val down = (1f - low) / max(1, attackMs / Loudness.BLOCK_MS)
        val up = (1f - low) / max(1, releaseMs / Loudness.BLOCK_MS)
        for (i in 0 until n) {
            g = if (wanted[i]) maxOf(low, g - down) else minOf(1f, g + up)
            out[i] = g
        }
        return out
    }
}
