package app.headsup.core.photo

import kotlin.math.exp
import kotlin.math.max

/**
 * Glow and halation as light spreading from what is bright: the bright parts' light (in the glow's
 * tint, and halation's red-orange), blurred by a true Gaussian, then laid over the picture as
 * light adds (screen). The preview and the video export do the same on the GPU; this is the photo
 * export's, worked out on a smaller copy (a blur needs no detail).
 */
object Glow {
    /** How wide the glow reaches (σ, pixels) on a picture whose short side is [shortSide], at [spread]. */
    fun sigma(shortSide: Float, spread: Float): Float = 1.1f * 0.012f * shortSide * spread

    /** The light the bright parts of [argb] give: ARGB, opaque, black where nothing is bright enough. */
    fun light(argb: IntArray, threshold: Float, glow: Float, halation: Float, tint: FloatArray): IntArray {
        val out = IntArray(argb.size)
        val th = threshold.coerceIn(0f, 0.99f)
        for (i in argb.indices) {
            val c = argb[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val k = max(y - th, 0f) / max(1f - th, 0.01f)
            val br = r * k
            val bg = g * k
            val bb = b * k
            val m = (br + bg + bb) * 0.33f * halation * 2f
            val lr = br * glow * 1.5f * tint[0] + m
            val lg = bg * glow * 1.5f * tint[1] + m * 0.3f
            val lb = bb * glow * 1.5f * tint[2] + m * 0.1f
            out[i] = (0xFF shl 24) or (enc(lr) shl 16) or (enc(lg) shl 8) or enc(lb)
        }
        return out
    }

    private fun enc(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** [argb] ([w]×[h]) blurred by a Gaussian of [sigma] pixels, across then down. */
    fun blur(argb: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        if (sigma < 0.3f) return argb.copyOf()
        val reach = (sigma * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(reach * 2 + 1) { k -> val d = (k - reach).toFloat(); exp(-d * d / (2f * sigma * sigma)) }
        val total = kernel.sum()
        for (k in kernel.indices) kernel[k] /= total
        val chans = Array(3) { c -> FloatArray(w * h) { i -> ((argb[i] shr (16 - 8 * c)) and 0xFF) / 255f } }
        val tmp = FloatArray(w * h)
        for (ch in chans) {
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in kernel.indices) s += ch[y * w + (x + k - reach).coerceIn(0, w - 1)] * kernel[k]
                tmp[y * w + x] = s
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in kernel.indices) s += tmp[(y + k - reach).coerceIn(0, h - 1) * w + x] * kernel[k]
                ch[y * w + x] = s
            }
        }
        return IntArray(w * h) { i -> (0xFF shl 24) or (enc(chans[0][i]) shl 16) or (enc(chans[1][i]) shl 8) or enc(chans[2][i]) }
    }
}
