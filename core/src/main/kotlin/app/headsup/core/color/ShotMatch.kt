package app.headsup.core.color

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** What a shot looks like on screen, for matching: tones, colour cast and colourfulness. */
data class MatchStats(
    val low: Float,
    val median: Float,
    val high: Float,
    val cb: Float,
    val cr: Float,
    val chroma: Float,
    /** Mean vectorscope angle of skin-like pixels, or null when the frame shows (almost) no skin. */
    val skinAngle: Float? = null,
) {
    companion object {
        /** Below this share of skin-like pixels, skin is not matched. */
        const val MIN_SKIN_SHARE = 0.02

        /** Statistics of display colours given as 0..1 RGB triples. */
        fun of(rgb: FloatArray): MatchStats {
            val n = rgb.size / 3
            if (n == 0) return MatchStats(0f, 0f, 0f, 0f, 0f, 0f)
            val luma = FloatArray(n)
            var cbSum = 0.0
            var crSum = 0.0
            var chromaSum = 0.0
            var weightSum = 0.0
            // Skin hue as a mean direction (vector sum), so angles average correctly.
            var skinCb = 0.0
            var skinCr = 0.0
            var skinWeight = 0.0
            for (i in 0 until n) {
                val r = rgb[3 * i]
                val g = rgb[3 * i + 1]
                val b = rgb[3 * i + 2]
                val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                luma[i] = y
                val cb = (b - y) / 1.8556f
                val cr = (r - y) / 1.5748f
                // The cast is read mostly from mid-tones; clipped and black pixels say little.
                val w = (1f - kotlin.math.abs(y - 0.5f) * 1.6f).coerceAtLeast(0.05f).toDouble()
                cbSum += cb * w
                crSum += cr * w
                weightSum += w
                val chroma = sqrt(cb * cb + cr * cr)
                chromaSum += chroma
                val skin = FilmLook.skinness(cb, cr, chroma)
                if (skin > 0.5f) {
                    skinCb += cb / chroma * skin
                    skinCr += cr / chroma * skin
                    skinWeight += skin
                }
            }
            luma.sort()
            return MatchStats(
                low = luma[(n * 0.1f).toInt().coerceAtMost(n - 1)],
                median = luma[n / 2],
                high = luma[(n * 0.9f).toInt().coerceAtMost(n - 1)],
                cb = (cbSum / weightSum).toFloat(),
                cr = (crSum / weightSum).toFloat(),
                chroma = (chromaSum / n).toFloat(),
                skinAngle = if (skinWeight / n >= MIN_SKIN_SHARE) FilmLook.angleOf(skinCb.toFloat(), skinCr.toFloat()) else null,
            )
        }
    }
}

/**
 * Shot match, as in DaVinci Resolve: changes a clip's exposure, contrast, white balance and
 * saturation so a frame of it looks like a reference frame from another camera. It runs the
 * clip's own colour path (corrections, then its LUT) on samples of the frame and adjusts the five
 * corrections step by step (damped Gauss–Newton) until tones, cast and colourfulness agree.
 * When both frames show skin, the skin hue is then turned to the reference's as well (the same
 * skin-hue correction as "Match line"), since cameras differ most visibly in how they render skin.
 */
object ShotMatch {
    private const val MAX_SAMPLES = 3000
    private const val ITERATIONS = 10

    /** The five corrections being solved for, as (getter, setter, limits). */
    private class Param(val get: (ColorGrade) -> Float, val set: (ColorGrade, Float) -> ColorGrade, val range: ClosedFloatingPointRange<Float>)

    private val PARAMS = listOf(
        Param({ it.exposure }, { g, v -> g.copy(exposure = v) }, -3f..3f),
        Param({ it.contrast }, { g, v -> g.copy(contrast = v) }, -1f..1f),
        Param({ it.temperature }, { g, v -> g.copy(temperature = v) }, -1f..1f),
        Param({ it.tint }, { g, v -> g.copy(tint = v) }, -1f..1f),
        Param({ it.saturation }, { g, v -> g.copy(saturation = v) }, -1f..1f),
    )

    /** Weights of the matched quantities: tones count most, then cast, then colourfulness. */
    private val WEIGHTS = floatArrayOf(1.2f, 2f, 1.2f, 6f, 6f, 3f)

    /**
     * Returns [grade] with exposure, contrast, temperature, tint and saturation changed so that
     * [sourcePixels] (ARGB, before any colour work) look like [reference] through [lut].
     */
    fun match(sourcePixels: IntArray, lut: CubeLut?, grade: ColorGrade, reference: MatchStats): ColorGrade {
        val step = max(1, sourcePixels.size / MAX_SAMPLES)
        val samples = FloatArray((sourcePixels.size + step - 1) / step * 3)
        var k = 0
        for (i in sourcePixels.indices step step) {
            val p = sourcePixels[i]
            samples[k++] = ((p shr 16) and 0xFF) / 255f
            samples[k++] = ((p shr 8) and 0xFF) / 255f
            samples[k++] = (p and 0xFF) / 255f
        }
        val target = vector(reference)
        var current = grade
        var lambda = 0.05f
        var error = error(residual(samples, k, lut, current, target))
        repeat(ITERATIONS) {
            val r = residual(samples, k, lut, current, target)
            // Numeric Jacobian of the residual with respect to each correction.
            val jacobian = PARAMS.map { p ->
                val h = 0.05f
                val shifted = p.set(current, (p.get(current) + h).coerceIn(p.range))
                val rh = residual(samples, k, lut, shifted, target)
                FloatArray(r.size) { (rh[it] - r[it]) / h }
            }
            val delta = solveDamped(jacobian, r, lambda) ?: return current
            var candidate = current
            PARAMS.forEachIndexed { i, p -> candidate = p.set(candidate, (p.get(candidate) - delta[i]).coerceIn(p.range)) }
            val candidateError = error(residual(samples, k, lut, candidate, target))
            if (candidateError < error) {
                current = candidate
                error = candidateError
                lambda = max(lambda * 0.5f, 1e-4f)
            } else {
                lambda *= 4f
            }
        }
        return matchSkin(samples, k, lut, current, reference)
    }

    /** Turns skin hues towards the reference's, if both frames show skin. */
    private fun matchSkin(samples: FloatArray, count: Int, lut: CubeLut?, grade: ColorGrade, reference: MatchStats): ColorGrade {
        val wanted = reference.skinAngle ?: return grade
        var current = grade
        // Two passes: the rotation is weighted by distance from the skin line, so one step may fall short.
        repeat(2) {
            val now = MatchStats.of(graded(samples, count, lut, current)).skinAngle ?: return current
            val delta = SkinTone.angleDifference(wanted.toDouble(), now.toDouble()).toFloat()
            current = current.copy(skinHue = (current.skinHue + delta).coerceIn(-30f, 30f))
        }
        return current
    }

    private fun graded(samples: FloatArray, count: Int, lut: CubeLut?, grade: ColorGrade): FloatArray {
        val out = FloatArray(count)
        val rgb = FloatArray(3)
        for (i in 0 until count step 3) {
            rgb[0] = samples[i]
            rgb[1] = samples[i + 1]
            rgb[2] = samples[i + 2]
            LutBaker.apply(lut, grade, rgb)
            out[i] = rgb[0]
            out[i + 1] = rgb[1]
            out[i + 2] = rgb[2]
        }
        return out
    }

    private fun vector(s: MatchStats) = floatArrayOf(stops(s.low), stops(s.median), stops(s.high), s.cb, s.cr, s.chroma)

    /** Weighted difference between the frame through [grade] and the reference. */
    private fun residual(samples: FloatArray, count: Int, lut: CubeLut?, grade: ColorGrade, target: FloatArray): FloatArray {
        val v = vector(MatchStats.of(graded(samples, count, lut, grade)))
        return FloatArray(v.size) { (v[it] - target[it]) * WEIGHTS[it] }
    }

    private fun error(r: FloatArray) = r.sumOf { (it * it).toDouble() }.toFloat()

    /** Tones compared in stops (gamma 2.2), so shadows and highlights weigh alike. */
    private fun stops(display: Float) = (ln(display.coerceIn(0.01f, 1f).pow(2.2f).toDouble()) / ln(2.0)).toFloat() / 6f

    /** Solves (JᵀJ + λ·diag(JᵀJ) + λ·I)·δ = Jᵀr for δ (Levenberg–Marquardt step). */
    private fun solveDamped(jacobian: List<FloatArray>, r: FloatArray, lambda: Float): FloatArray? {
        val n = jacobian.size
        val a = Array(n) { i -> DoubleArray(n) { j -> jacobian[i].indices.sumOf { (jacobian[i][it] * jacobian[j][it]).toDouble() } } }
        val b = DoubleArray(n) { i -> jacobian[i].indices.sumOf { (jacobian[i][it] * r[it]).toDouble() } }
        for (i in 0 until n) a[i][i] += lambda * a[i][i] + lambda
        // Gaussian elimination with partial pivoting.
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { kotlin.math.abs(a[it][col]) } ?: return null
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
            a[col] = a[pivot].also { a[pivot] = a[col] }
            b[col] = b[pivot].also { b[pivot] = b[col] }
            for (row in col + 1 until n) {
                val f = a[row][col] / a[col][col]
                for (c in col until n) a[row][c] -= f * a[col][c]
                b[row] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = b[row]
            for (c in row + 1 until n) s -= a[row][c] * x[c]
            x[row] = s / a[row][row]
        }
        return FloatArray(n) { x[it].toFloat() }
    }
}
