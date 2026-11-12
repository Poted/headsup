package app.headsup.core.edit

import kotlin.math.floor

/**
 * Film in a projector: the frame never sits exactly where the last one did (gate weave: a small,
 * restless sideways and up-and-down drift, more up and down, as the film is pulled through the
 * gate), and the light through it is never quite even (flicker). Both are made of smooth noise on
 * two time scales (a slow drift and a little from frame to frame at 24 frames a second), the same
 * for the same moment, so the preview and the export move alike.
 */
object GateWeave {
    /** Where the frame is and how bright: moved by [dx], [dy] (fractions of the width, height), light × [gain]. */
    data class Shift(val dx: Float, val dy: Float, val gain: Float) {
        val isNone: Boolean get() = dx == 0f && dy == 0f && gain == 1f
    }

    /** Largest move at full weave: fractions of the width and the height. */
    const val MAX_X = 0.0022f
    const val MAX_Y = 0.0035f

    /** Largest dip of light at full flicker. */
    const val MAX_FLICKER = 0.07f

    /** How much the picture is enlarged so its moving edges stay out of the frame, at [weave]. */
    fun cover(weave: Float): Float = 1f + 2.2f * MAX_Y * weave.coerceIn(0f, 1f)

    fun at(timeMs: Long, weave: Float, flicker: Float): Shift {
        if (weave <= 0f && flicker <= 0f) return Shift(0f, 0f, 1f)
        val frame = timeMs * 24f / 1000f
        val frameIndex = floor(frame)
        // Weave: a slow drift (about a second) and a smaller jump from frame to frame.
        fun weaveOf(seed: Int) = (smooth(frame / 22f, seed) * 0.65f + hash(frameIndex.toInt(), seed + 7) * 0.35f).coerceIn(-1f, 1f)
        val w = weave.coerceIn(0f, 1f)
        val dx = weaveOf(11) * MAX_X * w
        val dy = weaveOf(23) * MAX_Y * w
        // Flicker: frame to frame mostly, with a slow breath under it.
        val f = flicker.coerceIn(0f, 1f)
        // Only ever darker than the frame itself (a lamp that dips), so nothing clips.
        val n = (hash(frameIndex.toInt(), 41) * 0.6f + smooth(frame / 9f, 53) * 0.4f).coerceIn(-1f, 1f)
        return Shift(dx, dy, 1f - (n + 1f) / 2f * MAX_FLICKER * f)
    }

    /** Value noise: a random value per whole [t], eased between them (−1..1). */
    private fun smooth(t: Float, seed: Int): Float {
        val i = floor(t)
        val u = t - i
        val e = u * u * (3f - 2f * u)
        val a = hash(i.toInt(), seed)
        val b = hash(i.toInt() + 1, seed)
        return a + (b - a) * e
    }

    /** A random value for [n] (−1..1), always the same. */
    private fun hash(n: Int, seed: Int): Float {
        var x = n * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 32767.5f - 1f
    }
}
