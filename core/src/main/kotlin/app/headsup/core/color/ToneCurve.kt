package app.headsup.core.color

/**
 * Point tone curves (Lightroom's Point Curve): points (x, y in 0..1) joined by a smooth curve
 * that never turns back on itself (monotone cubic), one for all channels and one per channel.
 * A curve with no points is a straight line (no change).
 */
object ToneCurve {
    enum class Channel(val label: String) { RGB("RGB"), RED("Red"), GREEN("Green"), BLUE("Blue") }

    /** No curves: four empty point lists. */
    val NEUTRAL: List<List<Float>> = List(4) { emptyList() }

    /** Points as (x, y) pairs, sorted by x, with the ends at x = 0 and 1 added when missing. */
    fun points(flat: List<Float>): List<Pair<Float, Float>> {
        val p = flat.chunked(2).filter { it.size == 2 }.map { it[0].coerceIn(0f, 1f) to it[1].coerceIn(0f, 1f) }.sortedBy { it.first }.toMutableList()
        if (p.none { it.first <= 0f }) p.add(0, 0f to 0f)
        if (p.none { it.first >= 1f }) p.add(1f to 1f)
        return p
    }

    /** The curve through [flat] at [x] (monotone cubic interpolation, Fritsch–Carlson). */
    fun at(flat: List<Float>, x: Float): Float = through(points(flat), x)

    fun through(p: List<Pair<Float, Float>>, x: Float): Float {
        if (p.size < 2) return x
        if (x <= p.first().first) return p.first().second
        if (x >= p.last().first) return p.last().second
        val n = p.size
        val d = FloatArray(n - 1) { i -> (p[i + 1].second - p[i].second) / (p[i + 1].first - p[i].first).coerceAtLeast(1e-6f) }
        val m = FloatArray(n) { i ->
            when (i) {
                0 -> d[0]
                n - 1 -> d[n - 2]
                else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            }
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        var k = 0
        while (k < n - 2 && x > p[k + 1].first) k++
        val h = (p[k + 1].first - p[k].first).coerceAtLeast(1e-6f)
        val t = (x - p[k].first) / h
        val t2 = t * t
        val t3 = t2 * t
        return ((2 * t3 - 3 * t2 + 1) * p[k].second + (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * p[k + 1].second + (t3 - t2) * h * m[k + 1]).coerceIn(0f, 1f)
    }

    /** Applies [curves] (RGB, red, green, blue) to a display colour in place. */
    fun apply(curves: List<List<Float>>, rgb: FloatArray) {
        if (curves.all { it.isEmpty() }) return
        val master = curves.getOrNull(0).orEmpty()
        if (master.isNotEmpty()) {
            val p = points(master)
            for (i in 0..2) rgb[i] = through(p, rgb[i].coerceIn(0f, 1f))
        }
        for (c in 0..2) {
            val own = curves.getOrNull(c + 1).orEmpty()
            if (own.isNotEmpty()) rgb[c] = through(points(own), rgb[c].coerceIn(0f, 1f))
        }
    }
}
