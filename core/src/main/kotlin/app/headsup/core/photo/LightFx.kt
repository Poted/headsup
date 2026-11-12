package app.headsup.core.photo

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Light effects drawn from what is bright in the picture, added over it as light adds (screen):
 * Mist (a diffusion filter's soft haze round the lights), Anamorphic (long, thin, blue streaks
 * across the lights), Star (rays up, down and to the sides) and Prism (the lights repeated beside
 * themselves in the colours of a rainbow, as a prism held before the lens). Worked out on a smaller
 * copy (all of them are soft), with blurs whose cost does not grow with their reach, so a video
 * frame takes as long as a photo. The preview draws the same with the phone's own blurs.
 */
object LightFx {
    /** The amounts and reaches (1 = usual) of the four effects; all 0: nothing. */
    data class Settings(
        val mist: Float = 0f,
        val streaks: Float = 0f,
        val streakLength: Float = 1f,
        val star: Float = 0f,
        val starLength: Float = 1f,
        val prism: Float = 0f,
        val prismSpread: Float = 1f,
        /** Which way the prism's copies go, degrees from the right, clockwise on screen. */
        val prismAngle: Float = PRISM_ANGLE,
    ) {
        val any: Boolean get() = mist > 0f || streaks > 0f || star > 0f || prism > 0f
    }

    // What each effect takes its light from (how bright), and how far it reaches (of the frame's short side).
    const val MIST_THRESHOLD = 0.35f
    const val MIST_SIGMA = 0.03f
    const val STREAK_THRESHOLD = 0.75f
    const val STREAK_SIGMA = 0.08f
    const val STREAK_THIN = 0.002f
    const val STAR_THRESHOLD = 0.8f
    const val STAR_SIGMA = 0.04f
    const val STAR_THIN = 0.0015f
    const val PRISM_THRESHOLD = 0.7f
    const val PRISM_SOFT = 0.006f
    const val PRISM_STEP = 0.03f
    /** The prism's copies go this way (degrees from the right, clockwise on screen). */
    const val PRISM_ANGLE = 30f
    val STREAK_TINT = floatArrayOf(0.35f, 0.6f, 1f)
    /** Each copy's colour and strength, nearest first. */
    val PRISM_TINTS = arrayOf(floatArrayOf(1f, 0.25f, 0.2f), floatArrayOf(0.3f, 1f, 0.35f), floatArrayOf(0.3f, 0.45f, 1f))
    val PRISM_WEIGHTS = floatArrayOf(0.9f, 0.7f, 0.5f)

    /**
     * The effects' light for [argb] ([w]×[h], a small copy of the corrected picture whose short side
     * stands for [shortSide] pixels of the frame): ARGB to be laid over the picture (screen).
     * [flipY]: the rows run from the bottom (a GPU's read), so the prism's direction is turned back.
     */
    fun light(argb: IntArray, w: Int, h: Int, s: Settings, flipY: Boolean = false): IntArray {
        val n = w * h
        val short = minOf(w, h).toFloat()
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            r[i] = ((c shr 16) and 0xFF) / 255f
            g[i] = ((c shr 8) and 0xFF) / 255f
            b[i] = (c and 0xFF) / 255f
            y[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
        }
        val out = Array(3) { FloatArray(n) }
        fun source(threshold: Float, gain: Float, tint: FloatArray): Array<FloatArray> {
            val res = Array(3) { FloatArray(n) }
            for (i in 0 until n) {
                val k = max(y[i] - threshold, 0f) / max(1f - threshold, 0.01f) * gain
                res[0][i] = r[i] * k * tint[0]
                res[1][i] = g[i] * k * tint[1]
                res[2][i] = b[i] * k * tint[2]
            }
            return res
        }
        fun add(from: Array<FloatArray>, dx: Int = 0, dy: Int = 0, tint: FloatArray? = null, weight: Float = 1f) {
            for (yy in 0 until h) for (xx in 0 until w) {
                val sx = xx - dx
                val sy = yy - dy
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                val j = sy * w + sx
                val i = yy * w + xx
                for (c in 0..2) out[c][i] += from[c][j] * weight * (tint?.get(c) ?: 1f)
            }
        }
        val white = floatArrayOf(1f, 1f, 1f)
        if (s.mist > 0f) add(gaussian(source(MIST_THRESHOLD, s.mist * 0.6f, white), w, h, MIST_SIGMA * short, MIST_SIGMA * short))
        if (s.streaks > 0f) add(gaussian(source(STREAK_THRESHOLD, s.streaks * 2f, STREAK_TINT), w, h, STREAK_SIGMA * short * s.streakLength, STREAK_THIN * short))
        if (s.star > 0f) {
            val src = source(STAR_THRESHOLD, s.star * 2f, white)
            add(gaussian(src, w, h, STAR_SIGMA * short * s.starLength, STAR_THIN * short))
            add(gaussian(src, w, h, STAR_THIN * short, STAR_SIGMA * short * s.starLength))
        }
        if (s.prism > 0f) {
            val soft = gaussian(source(PRISM_THRESHOLD, s.prism, white), w, h, PRISM_SOFT * short, PRISM_SOFT * short)
            val a = Math.toRadians(s.prismAngle.toDouble())
            for (k in 0..2) {
                val d = PRISM_STEP * short * s.prismSpread * (k + 1)
                val dx = (d * cos(a)).roundToInt()
                val dy = (d * sin(a)).roundToInt() * if (flipY) -1 else 1
                add(soft, dx, dy, PRISM_TINTS[k], PRISM_WEIGHTS[k])
            }
        }
        return IntArray(n) { i ->
            (0xFF shl 24) or (enc(out[0][i]) shl 16) or (enc(out[1][i]) shl 8) or enc(out[2][i])
        }
    }

    private fun enc(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Each channel blurred by about a Gaussian of [sx] across and [sy] down (three box passes each way). */
    fun gaussian(ch: Array<FloatArray>, w: Int, h: Int, sx: Float, sy: Float): Array<FloatArray> {
        val res = Array(ch.size) { ch[it].copyOf() }
        val rx = boxRadius(sx)
        val ry = boxRadius(sy)
        val tmp = FloatArray(w * h)
        for (c in res) {
            repeat(3) {
                if (rx > 0) boxH(c, tmp, w, h, rx)
                if (ry > 0) boxV(c, tmp, w, h, ry)
            }
        }
        return res
    }

    /** The radius of three box passes that together blur about as a Gaussian of [sigma]. */
    private fun boxRadius(sigma: Float): Int = if (sigma < 0.5f) 0 else ((kotlin.math.sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)

    private fun boxH(c: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (yy in 0 until h) {
            val row = yy * w
            var sum = 0f
            for (k in -r..r) sum += c[row + k.coerceIn(0, w - 1)]
            for (xx in 0 until w) {
                tmp[row + xx] = sum * norm
                sum += c[row + (xx + r + 1).coerceAtMost(w - 1)] - c[row + (xx - r).coerceAtLeast(0)]
            }
        }
        System.arraycopy(tmp, 0, c, 0, w * h)
    }

    private fun boxV(c: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (xx in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += c[k.coerceIn(0, h - 1) * w + xx]
            for (yy in 0 until h) {
                tmp[yy * w + xx] = sum * norm
                sum += c[(yy + r + 1).coerceAtMost(h - 1) * w + xx] - c[(yy - r).coerceAtLeast(0) * w + xx]
            }
        }
        System.arraycopy(tmp, 0, c, 0, w * h)
    }
}
