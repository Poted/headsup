package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * RGB channel mixer (as in Photoshop or DaVinci): each output channel made from the red, green and
 * blue inputs. Nine values, row by row (red out from R, G, B; green out…; blue out…), each an
 * offset from no change (the diagonal adds to 1), −2..2. Film looks (Aerochrome), casts, swaps.
 */
object ChannelMixer {
    const val SIZE = 9
    val NEUTRAL: List<Float> = List(SIZE) { 0f }

    fun apply(mix: List<Float>, rgb: FloatArray) {
        if (mix.size < SIZE || mix.all { it == 0f }) return
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        for (o in 0..2) {
            rgb[o] = (if (o == 0) 1f else 0f) * r + (if (o == 1) 1f else 0f) * g + (if (o == 2) 1f else 0f) * b +
                mix[o * 3] * r + mix[o * 3 + 1] * g + mix[o * 3 + 2] * b
        }
    }
}

/**
 * Black and white (Lightroom's B&W mix): the picture in grey, each hue band ([ColourMixer.Band])
 * made lighter or darker, −1..1 (a darker blue sky, lighter skin). Index 0 turns it on.
 */
object BlackAndWhite {
    const val SIZE = 1 + 8
    val NEUTRAL: List<Float> = List(SIZE) { 0f }

    fun on(bw: List<Float>) = bw.getOrElse(0) { 0f } != 0f

    fun apply(bw: List<Float>, rgb: FloatArray) {
        if (!on(bw)) return
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val v = max(r, max(g, b))
        val chroma = v - min(r, min(g, b))
        var grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (chroma > 1e-5f) {
            val h = hue(r, g, b, v, chroma)
            var change = 0f
            val bands = ColourMixer.Band.entries
            for (i in bands.indices) {
                val w = bandWeight(h, i)
                if (w > 0f) change += w * bw.getOrElse(1 + i) { 0f }
            }
            // A saturated colour moves the most; a near-grey hardly at all.
            grey += change * chroma * 0.6f
        }
        val out = grey.coerceIn(0f, 1f)
        rgb[0] = out
        rgb[1] = out
        rgb[2] = out
    }

    internal fun hue(r: Float, g: Float, b: Float, v: Float, chroma: Float): Float {
        var h = when (v) {
            r -> 60f * ((g - b) / chroma)
            g -> 60f * (2f + (b - r) / chroma)
            else -> 60f * (4f + (r - g) / chroma)
        }
        if (h < 0f) h += 360f
        return h
    }

    /** How much band [i] takes of hue [h] (degrees): 1 at its centre, falling to 0 at its neighbours'. */
    internal fun bandWeight(h: Float, i: Int): Float {
        val bands = ColourMixer.Band.entries
        val c = bands[i].hue
        var next = bands[(i + 1) % bands.size].hue
        if (next <= c) next += 360f
        var prev = bands[(i + bands.size - 1) % bands.size].hue
        if (prev >= c) prev -= 360f
        var d = h - c
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return if (d >= 0f) (1f - d / (next - c)).coerceIn(0f, 1f) else (1f - d / (prev - c)).coerceIn(0f, 1f)
    }
}

/**
 * Calibration (Lightroom's): the hue and saturation of the red, green and blue primaries, −1..1,
 * moving every colour made of them; grey stays grey. Six values: red hue, red saturation, green…, blue….
 */
object Calibration {
    const val SIZE = 6
    val NEUTRAL: List<Float> = List(SIZE) { 0f }

    /** Largest hue turn of a primary (at ±1), degrees. */
    private const val HUE_RANGE = 30.0

    /** The 3×3 matrix (row by row) taking colours to the calibrated primaries, or null for none. */
    fun matrix(cal: List<Float>): FloatArray? {
        if (cal.size < SIZE || cal.all { it == 0f }) return null
        // Each primary turned around the grey axis and pushed from or towards grey.
        val columns = Array(3) { p ->
            val primary = FloatArray(3).also { it[p] = 1f }
            val turned = turn(primary, cal[p * 2] * HUE_RANGE)
            val s = 1f + cal[p * 2 + 1]
            val grey = (turned[0] + turned[1] + turned[2]) / 3f
            FloatArray(3) { grey + (turned[it] - grey) * s }
        }
        // Scaled so white stays white: solve M · k = (1, 1, 1).
        val m = Array(3) { r -> DoubleArray(3) { c -> columns[c][r].toDouble() } }
        val k = solve(m, doubleArrayOf(1.0, 1.0, 1.0)) ?: return null
        return FloatArray(9) { i -> (m[i / 3][i % 3] * k[i % 3]).toFloat() }
    }

    fun apply(matrix: FloatArray?, rgb: FloatArray) {
        if (matrix == null) return
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        for (o in 0..2) rgb[o] = matrix[o * 3] * r + matrix[o * 3 + 1] * g + matrix[o * 3 + 2] * b
    }

    /** [c] turned by [degrees] around the grey axis (Rodrigues' rotation about (1,1,1)/√3). */
    private fun turn(c: FloatArray, degrees: Double): FloatArray {
        val a = Math.toRadians(degrees)
        val k = 1.0 / Math.sqrt(3.0)
        val cosA = cos(a)
        val sinA = sin(a)
        val dot = (c[0] + c[1] + c[2]) * k
        val cross = doubleArrayOf(k * (c[2] - c[1]), k * (c[0] - c[2]), k * (c[1] - c[0]))
        return FloatArray(3) { i -> (c[i] * cosA + cross[i] * sinA + k * dot * (1 - cosA)).toFloat() }
    }

    private fun solve(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) m[r][c] else v[r] } }
        for (col in 0..2) {
            val pivot = (col..2).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-9) return null
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
            for (r in 0..2) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..3) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(3) { a[it][3] / a[it][it] }
    }
}

/**
 * Dehaze, per colour (no halos, works in masks): haze lifts the darkest channel towards a pale
 * airlight, so a share of it proportional to that channel is taken out (the dark-channel model with
 * a white airlight) and the colours it washed out are deepened. White stays white, deep shadows
 * hardly move. Negative adds haze: everything drawn towards a pale grey-blue.
 */
object Dehaze {
    fun apply(amount: Float, rgb: FloatArray) {
        if (amount == 0f) return
        if (amount > 0f) {
            val dark = min(rgb[0], min(rgb[1], rgb[2])).coerceIn(0f, 1f)
            val h = (amount * 0.55f * dark).coerceIn(0f, 0.85f)
            for (i in 0..2) rgb[i] = (rgb[i] - h) / (1f - h)
            val l = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            val k = 1f + 0.3f * amount
            for (i in 0..2) rgb[i] = (l + (rgb[i] - l) * k).coerceIn(0f, 1f)
        } else {
            val t = -amount * 0.45f
            val air = floatArrayOf(0.78f, 0.8f, 0.84f)
            for (i in 0..2) rgb[i] = rgb[i] * (1f - t) + air[i] * t
        }
    }
}
