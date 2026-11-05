package app.headsup.core.color

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Built-in conversions of cameras' log footage to Rec.709, made from the curves and gamuts their
 * makers publish (no LUT file needed). Each is the log curve taken back to scene light, the
 * camera's gamut turned into Rec.709's, and a display rendering: 18 % grey a little above the
 * middle, a soft shoulder that keeps highlights from clipping hard and a gentle toe.
 *
 * Only curves with a published formula are here: a profile whose maker publishes only a LUT
 * (DJI D-Log M) is loaded from that LUT file instead.
 */
object CameraLog {
    /** Where the decoded picture's values sit against the curve's own code values. */
    private enum class Levels {
        /** The curve is defined on video levels (0 = black at 64/1023, 1 = 940/1023): as decoded. */
        VIDEO,

        /** The curve is defined on full 10-bit code values (0..1023): decoded video levels are mapped back first. */
        FULL,
    }

    enum class Gamut(val r: Pair<Double, Double>, val g: Pair<Double, Double>, val b: Pair<Double, Double>) {
        REC709(0.64 to 0.33, 0.30 to 0.60, 0.15 to 0.06),
        REC2020(0.708 to 0.292, 0.170 to 0.797, 0.131 to 0.046),
        S_GAMUT3(0.730 to 0.280, 0.140 to 0.855, 0.100 to -0.050),
        S_GAMUT3_CINE(0.766 to 0.275, 0.225 to 0.800, 0.089 to -0.087),
        ARRI_WG3(0.6840 to 0.3130, 0.2210 to 0.8480, 0.0861 to -0.1020),
        ARRI_WG4(0.7347 to 0.2653, 0.1424 to 0.8576, 0.0991 to -0.0308),
        V_GAMUT(0.730 to 0.280, 0.165 to 0.840, 0.100 to -0.030),
        CINEMA_GAMUT(0.740 to 0.270, 0.170 to 1.140, 0.080 to -0.100),
        D_GAMUT(0.71 to 0.31, 0.21 to 0.88, 0.09 to -0.08),
    }

    enum class Profile(val id: String, val label: String, private val levels: Levels, val gamut: Gamut) {
        SLOG3_CINE("slog3-sgamut3cine", "Sony S-Log3 · S-Gamut3.Cine", Levels.FULL, Gamut.S_GAMUT3_CINE),
        SLOG3("slog3-sgamut3", "Sony S-Log3 · S-Gamut3", Levels.FULL, Gamut.S_GAMUT3),
        VLOG("vlog", "Panasonic V-Log · V-Gamut", Levels.VIDEO, Gamut.V_GAMUT),
        LOGC3("logc3", "ARRI LogC3 · AWG3 (EI 800)", Levels.FULL, Gamut.ARRI_WG3),
        LOGC4("logc4", "ARRI LogC4 · AWG4", Levels.FULL, Gamut.ARRI_WG4),
        CLOG3("clog3", "Canon Log 3 · Cinema Gamut", Levels.VIDEO, Gamut.CINEMA_GAMUT),
        FLOG("flog", "Fujifilm F-Log · F-Gamut", Levels.FULL, Gamut.REC2020),
        FLOG2("flog2", "Fujifilm F-Log2 · F-Gamut", Levels.FULL, Gamut.REC2020),
        DLOG("dlog", "DJI D-Log · D-Gamut", Levels.VIDEO, Gamut.D_GAMUT),
        ;

        /** Scene light (18 % grey = 0.18) of a decoded value [v] (video levels, 0..1). */
        fun toLinear(v: Double): Double {
            val x = if (levels == Levels.FULL) (v * 876.0 + 64.0) / 1023.0 else v
            return when (this) {
                SLOG3, SLOG3_CINE -> {
                    val c = x * 1023.0
                    if (c >= 171.2102946929) 10.0.pow((c - 420.0) / 261.5) * 0.19 - 0.01 else (c - 95.0) * 0.01125 / (171.2102946929 - 95.0)
                }
                VLOG -> if (x < 0.181) (x - 0.125) / 5.6 else 10.0.pow((x - 0.598206) / 0.241514) - 0.00873
                LOGC3 -> if (x > 5.367655 * 0.010591 + 0.092809) (10.0.pow((x - 0.385537) / 0.247190) - 0.052272) / 5.555556 else (x - 0.092809) / 5.367655
                LOGC4 -> {
                    val a = (2.0.pow(18) - 16.0) / 117.45
                    val b = (1023.0 - 95.0) / 1023.0
                    val c = 95.0 / 1023.0
                    val s = (7.0 * ln(2.0) * 2.0.pow(7.0 - 14.0 * c / b)) / (a * b)
                    val t = (2.0.pow(14.0 * (-c / b) + 6.0) - 64.0) / a
                    if (x >= 0.0) (2.0.pow(14.0 * (x - c) / b + 6.0) - 64.0) / a else x * s + t
                }
                CLOG3 -> when {
                    x < 0.097465473 -> -(10.0.pow((0.12783901 - x) / 0.36726845) - 1.0) / 14.98325
                    x <= 0.15277891 -> (x - 0.12512219) / 1.9754798
                    else -> (10.0.pow((x - 0.12240537) / 0.36726845) - 1.0) / 14.98325
                }
                FLOG -> if (x >= 0.100537775223865) (10.0.pow((x - 0.790453) / 0.344676) - 0.009468) / 0.555556 else (x - 0.092864) / 8.735631
                FLOG2 -> if (x >= 0.100686685370811) (10.0.pow((x - 0.384316) / 0.245281) - 0.064829) / 5.555556 else (x - 0.092864) / 8.799461
                DLOG -> if (x <= 0.14) (x - 0.0929) / 6.025 else (10.0.pow(3.89616 * x - 2.27752) - 0.0108) / 0.9892
            }
        }
    }

    /** Name a built-in conversion goes by where LUT names are kept (a clip's LUT). */
    const val PREFIX = "builtin:"

    fun nameOf(profile: Profile) = PREFIX + profile.id

    fun profileOf(name: String?): Profile? = name?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { id -> Profile.entries.firstOrNull { it.id == id } }

    /** What a LUT name shows as: a built-in one by its camera's curve and gamut. */
    fun labelOf(name: String): String = profileOf(name)?.label ?: name

    /** 3×3 (row by row) from [g]'s RGB to CIE XYZ, white D65. */
    fun toXyz(g: Gamut): DoubleArray {
        fun xyz(p: Pair<Double, Double>) = doubleArrayOf(p.first / p.second, 1.0, (1.0 - p.first - p.second) / p.second)
        val r = xyz(g.r)
        val gr = xyz(g.g)
        val b = xyz(g.b)
        val m = doubleArrayOf(r[0], gr[0], b[0], r[1], gr[1], b[1], r[2], gr[2], b[2])
        val w = doubleArrayOf(0.3127 / 0.3290, 1.0, (1.0 - 0.3127 - 0.3290) / 0.3290)
        val s = mul(invert(m), w)
        return DoubleArray(9) { i -> m[i] * s[i % 3] }
    }

    /** 3×3 from [from]'s RGB to Rec.709's (both white D65). */
    fun matrixTo709(from: Gamut): DoubleArray = mul3(invert(toXyz(Gamut.REC709)), toXyz(from))

    /**
     * Scene light to the Rec.709 picture (display gamma 2.4): grey 0.18 at [GREY] and a shoulder
     * reaching white about four stops over it, so highlights roll off instead of clipping.
     */
    fun render(x: Double): Double {
        val l = max(x, 0.0)
        val p = l.pow(CONTRAST)
        val y = SHOULDER * p / (p + knee)
        return y.coerceIn(0.0, 1.0).pow(1.0 / 2.4)
    }

    /** The conversion as a 3D table of [size] points a side (red changing fastest, as in a .cube). */
    fun lut(profile: Profile, size: Int = 33): CubeLut {
        val m = matrixTo709(profile.gamut)
        val data = FloatArray(size * size * size * 3)
        val curve = DoubleArray(size) { i -> profile.toLinear(i / (size - 1.0)) }
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val lr = curve[r]
            val lg = curve[g]
            val lb = curve[b]
            val o = ((b * size + g) * size + r) * 3
            for (c in 0..2) {
                data[o + c] = render(m[c * 3] * lr + m[c * 3 + 1] * lg + m[c * 3 + 2] * lb).toFloat()
            }
        }
        return CubeLut(size, data, profile.label + " → Rec.709")
    }

    /** Where 18 % grey lands on the Rec.709 picture. */
    const val GREY = 0.42
    private const val CONTRAST = 1.5
    private const val SHOULDER = 1.04

    /** Set so 0.18 lands on [GREY]: SHOULDER·g^c / (g^c + knee) = GREY^2.4. */
    private val knee: Double = run {
        val p = 0.18.pow(CONTRAST)
        val target = GREY.pow(2.4)
        (SHOULDER * p - target * p) / target
    }

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val (a, b, c, d, e, f, g, h, k) = m.toList().let { Nine(it) }
        val det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * k - f * h) / det, (c * h - b * k) / det, (b * f - c * e) / det,
            (f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    private class Nine(val v: List<Double>) {
        operator fun component1() = v[0]
        operator fun component2() = v[1]
        operator fun component3() = v[2]
        operator fun component4() = v[3]
        operator fun component5() = v[4]
        operator fun component6() = v[5]
        operator fun component7() = v[6]
        operator fun component8() = v[7]
        operator fun component9() = v[8]
    }
}
