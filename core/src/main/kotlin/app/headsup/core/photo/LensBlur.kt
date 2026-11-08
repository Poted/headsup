package app.headsup.core.photo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wide-aperture lens's background (bokeh) for a photo: what is not kept sharp ([keep], 0..1 per
 * pixel, e.g. the subject) blurred through a round aperture, bright points opening into discs.
 * Only the background is gathered, so the subject's colours do not bleed into a halo around it.
 */
object LensBlur {
    /** Points of the round aperture (a golden-angle spiral, evenly spread over the disc). */
    private const val TAPS = 72

    private val unitDisc: FloatArray by lazy {
        val out = FloatArray(TAPS * 2)
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until TAPS) {
            val r = sqrt((i + 0.5) / TAPS)
            val a = i * golden
            out[i * 2] = (r * cos(a)).toFloat()
            out[i * 2 + 1] = (r * sin(a)).toFloat()
        }
        out
    }

    /**
     * The blurred background of [argb] ([w]×[h]): ARGB with its alpha where the background is
     * (1 − [keep]), to be laid over the sharp photo. [radius] in pixels; [highlights] 0..1 how
     * much the brightest parts open into bright discs (as clipped highlights do through a lens).
     */
    fun background(argb: IntArray, w: Int, h: Int, keep: FloatArray, radius: Float, highlights: Float): IntArray {
        val n = w * h
        // Linear light, the brightest lifted (they were clipped by the camera: their real light is more).
        val lr = FloatArray(n)
        val lg = FloatArray(n)
        val lb = FloatArray(n)
        val bg = FloatArray(n)
        val boost = highlights.coerceIn(0f, 1f) * 8f
        for (i in 0 until n) {
            val c = argb[i]
            val r = lin(((c shr 16) and 0xFF) / 255f)
            val g = lin(((c shr 8) and 0xFF) / 255f)
            val b = lin((c and 0xFF) / 255f)
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val t = ((y - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val k = 1f + boost * t * t
            lr[i] = r * k
            lg[i] = g * k
            lb[i] = b * k
            bg[i] = 1f - keep[i].coerceIn(0f, 1f)
        }
        val disc = unitDisc
        val out = IntArray(n)
        val rad = max(radius, 0.5f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var sw = 0f
            for (t in 0 until TAPS) {
                val sx = (x + disc[t * 2] * rad + 0.5f).toInt().coerceIn(0, w - 1)
                val sy = (y + disc[t * 2 + 1] * rad + 0.5f).toInt().coerceIn(0, h - 1)
                val j = sy * w + sx
                val wt = bg[j]
                sr += lr[j] * wt
                sg += lg[j] * wt
                sb += lb[j] * wt
                sw += wt
            }
            val alpha = bg[i]
            if (alpha <= 0f) continue
            // Where the aperture saw only the subject (deep inside a gap), its own colour stays.
            val r: Float
            val g: Float
            val b: Float
            if (sw > 1e-3f) { r = sr / sw; g = sg / sw; b = sb / sw } else { r = lr[i]; g = lg[i]; b = lb[i] }
            val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
        }
        return out
    }

    private fun lin(v: Float): Float = v.toDouble().pow(2.2).toFloat()

    private fun enc(v: Float): Int = (min(v, 1f).toDouble().pow(1 / 2.2) * 255.0 + 0.5).toInt().coerceIn(0, 255)

    /** The blur radius in pixels for [amount] 0..1 on a picture whose long side is [longSide]. */
    fun radiusFor(amount: Float, longSide: Int): Float = amount.coerceIn(0f, 1f) * 0.035f * longSide
}
