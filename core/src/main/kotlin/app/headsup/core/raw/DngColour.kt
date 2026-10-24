package app.headsup.core.raw

import kotlin.math.roundToInt

/**
 * The colour transform of the DNG specification (as Adobe's converters do it): the white the
 * camera saw gives a colour temperature, the two calibrated matrices (e.g. tungsten and daylight)
 * are blended for it, and the camera's colours go through the forward matrix (or the inverted
 * colour matrix) to XYZ and on to linear sRGB.
 */
object DngColour {
    class Calibration(
        val colorMatrix1: DoubleArray?,
        val colorMatrix2: DoubleArray?,
        val forwardMatrix1: DoubleArray?,
        val forwardMatrix2: DoubleArray?,
        val illuminant1: Int?,
        val illuminant2: Int?,
    )

    class Result(
        /** White-balanced camera values (the as-shot neutral at 1, 1, 1) to linear sRGB. */
        val balancedToSrgb: DoubleArray,
        /** Colour temperature of the white as shot. */
        val kelvin: Int,
        val forward: Boolean,
    )

    /** For [neutral] (the camera's values for white), or null without any colour matrix. */
    fun transform(c: Calibration, neutral: DoubleArray): Result? {
        if (c.colorMatrix1 == null && c.colorMatrix2 == null) return null
        val t1 = kelvinOf(c.illuminant1)
        val t2 = kelvinOf(c.illuminant2)
        // The white's chromaticity, found iteratively: it decides the blend, the blend decides it.
        var x = 0.3457
        var y = 0.3585
        var weight = 1.0
        repeat(6) {
            weight = weightFor(cct(x, y), t1, t2, c.colorMatrix2 != null && c.colorMatrix1 != null)
            val xyzToCam = blend(c.colorMatrix1, c.colorMatrix2, weight)
            val xyz = apply(Dng.invert(xyzToCam), neutral)
            val sum = xyz[0] + xyz[1] + xyz[2]
            if (sum > 0) {
                x = xyz[0] / sum
                y = xyz[1] / sum
            }
        }
        val forward = blendOrNull(c.forwardMatrix1, c.forwardMatrix2, weight)
        val balancedToSrgb = if (forward != null) {
            // Balanced camera → XYZ (D50) → linear sRGB.
            Dng.multiply(XYZ_D50_TO_SRGB, forward)
        } else {
            // Camera → XYZ of the scene's white, adapted to D65, → sRGB.
            val camToXyz = Dng.invert(blend(c.colorMatrix1, c.colorMatrix2, weight))
            val balancedToXyz = Dng.multiply(camToXyz, diagonal(neutral))
            val white = apply(balancedToXyz, doubleArrayOf(1.0, 1.0, 1.0))
            // The adaptation takes the white to D65 itself (Y = 1), so white comes out at 1.
            val adapt = bradford(white, D65)
            Dng.multiply(XYZ_D65_TO_SRGB, Dng.multiply(adapt, balancedToXyz))
        }
        return Result(balancedToSrgb, cct(x, y).roundToInt(), forward != null)
    }

    /** McCamy's approximation of the correlated colour temperature. */
    private fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return (449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33).coerceIn(1500.0, 20000.0)
    }

    /** Weight of the first matrix: blended linearly in inverse temperature, as the DNG specification says. */
    private fun weightFor(t: Double, t1: Double, t2: Double, both: Boolean): Double {
        if (!both || t1 == t2) return 1.0
        val (low, high, lowIsFirst) = if (t1 < t2) Triple(t1, t2, true) else Triple(t2, t1, false)
        val g = when {
            t <= low -> 1.0
            t >= high -> 0.0
            else -> (1 / t - 1 / high) / (1 / low - 1 / high)
        }
        return if (lowIsFirst) g else 1 - g
    }

    private fun blend(a: DoubleArray?, b: DoubleArray?, w: Double): DoubleArray = blendOrNull(a, b, w)!!

    private fun blendOrNull(a: DoubleArray?, b: DoubleArray?, w: Double): DoubleArray? = when {
        a != null && b != null -> DoubleArray(9) { a[it] * w + b[it] * (1 - w) }
        else -> (a ?: b)?.copyOf(9)
    }

    private fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    private fun diagonal(v: DoubleArray) = doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2])

    /** Bradford adaptation from white [from] to white [to] (XYZ). */
    private fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val s = apply(BRADFORD, from)
        val d = apply(BRADFORD, to)
        val scale = doubleArrayOf(d[0] / s[0], 0.0, 0.0, 0.0, d[1] / s[1], 0.0, 0.0, 0.0, d[2] / s[2])
        return Dng.multiply(Dng.invert(BRADFORD), Dng.multiply(scale, BRADFORD))
    }

    /** EXIF light source codes to colour temperature. */
    private fun kelvinOf(illuminant: Int?): Double = when (illuminant) {
        1, 4, 9 -> 5500.0
        2, 14 -> 4150.0
        3 -> 2850.0
        10 -> 6500.0
        11 -> 7500.0
        12 -> 6430.0
        13 -> 5000.0
        15 -> 3450.0
        17 -> 2856.0
        18 -> 4874.0
        19 -> 6774.0
        20 -> 5503.0
        21 -> 6504.0
        22 -> 7504.0
        23 -> 5003.0
        24 -> 3200.0
        else -> 6504.0
    }

    private val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    private val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    private val XYZ_D65_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )
}
