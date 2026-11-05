package app.headsup.core.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Curves by hue and by brightness, as DaVinci's and Lightroom's: Hue vs Hue turns one hue to
 * another, Hue vs Saturation and Hue vs Luminance make one hue richer, paler, darker or lighter,
 * and Luminance vs Saturation takes colour out of (or into) the shadows or highlights. Each curve
 * is x, y pairs: x the hue (0..1 round the wheel, red at 0) or the brightness (0..1); y the change,
 * −1..1, 0 none. An empty curve changes nothing.
 */
object HueCurves {
    enum class Channel(val label: String, val periodic: Boolean) {
        HUE_HUE("Hue vs Hue", true),
        HUE_SAT("Hue vs Sat", true),
        HUE_LUM("Hue vs Lum", true),
        LUM_SAT("Lum vs Sat", false),
    }

    val NEUTRAL: List<List<Float>> = List(4) { emptyList() }

    /** Largest hue turn (at ±1), degrees. */
    private const val HUE_RANGE = 60f

    /** The points as (x, y), sorted. */
    fun points(curve: List<Float>): List<Pair<Float, Float>> =
        curve.chunked(2).filter { it.size == 2 }.map { it[0].coerceIn(0f, 1f) to it[1].coerceIn(-1f, 1f) }.sortedBy { it.first }

    /** The change at [x]: smooth between the points; round the wheel for hue curves, flat past the ends for brightness. */
    fun valueAt(points: List<Pair<Float, Float>>, x: Float, periodic: Boolean): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return if (periodic) points[0].second else points[0].second
        val p = if (periodic) listOf(points.last().first - 1f to points.last().second) + points + (points.first().first + 1f to points.first().second) else points
        if (!periodic) {
            if (x <= p.first().first) return p.first().second
            if (x >= p.last().first) return p.last().second
        }
        for (k in 0 until p.size - 1) {
            val (x0, y0) = p[k]
            val (x1, y1) = p[k + 1]
            if (x in x0..x1) {
                val t = if (x1 - x0 < 1e-6f) 0f else (x - x0) / (x1 - x0)
                val s = (1f - cos(PI * t).toFloat()) / 2f
                return y0 + (y1 - y0) * s
            }
        }
        return 0f
    }

    /** Applies [curves] to a display colour (0..1 per channel) in place. */
    fun apply(curves: List<List<Float>>, rgb: FloatArray) {
        if (curves.size < 4 || curves.all { it.isEmpty() }) return
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val v = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val chroma = v - mn
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (v <= 0f) return
        var h = 0f
        if (chroma > 1e-5f) {
            h = when (v) {
                r -> 60f * ((g - b) / chroma)
                g -> 60f * (2f + (b - r) / chroma)
                else -> 60f * (4f + (r - g) / chroma)
            }
            if (h < 0f) h += 360f
        }
        var s = if (v > 0f) chroma / v else 0f
        val hx = h / 360f
        // A near-grey has no clear hue: the hue curves touch it less.
        val strength = (s / 0.2f).coerceIn(0f, 1f)
        val dh = valueAt(points(curves[0]), hx, true) * strength
        val ds = valueAt(points(curves[1]), hx, true) * strength
        val dl = valueAt(points(curves[2]), hx, true) * strength
        val dls = valueAt(points(curves[3]), luma.coerceIn(0f, 1f), false)
        if (dh == 0f && ds == 0f && dl == 0f && dls == 0f) return
        h = ((h + dh * HUE_RANGE) % 360f + 360f) % 360f
        s = (s * (1f + ds) * (1f + dls)).coerceIn(0f, 1f)
        val v2 = (v * (1f + dl * 0.5f)).coerceIn(0f, 1.5f)
        val c2 = v2 * s
        val hp = h / 60f
        val x = c2 * (1f - abs(hp % 2f - 1f))
        val m = v2 - c2
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c2, x, 0f)
            1 -> Triple(x, c2, 0f)
            2 -> Triple(0f, c2, x)
            3 -> Triple(0f, x, c2)
            4 -> Triple(x, 0f, c2)
            else -> Triple(c2, 0f, x)
        }
        rgb[0] = r1 + m
        rgb[1] = g1 + m
        rgb[2] = b1 + m
    }
}
