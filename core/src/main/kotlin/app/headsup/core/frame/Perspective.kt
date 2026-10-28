package app.headsup.core.frame

import kotlin.math.abs

/**
 * Perspective correction (Lightroom's Transform): [vertical] straightens lines that lean together
 * when the camera looks up or down (a building's sides), [horizontal] the same sideways; −1..1.
 * The picture is warped by the homography taking its corners to a keystoned quad, and zoomed in
 * just enough that no empty corner shows. Coordinates are fractions of the picture, y downwards.
 */
object Perspective {
    /** How far each edge widens (or narrows) at ±1, of the picture's width or height. */
    private const val REACH = 0.18f

    /** Corners of the warped picture (TL, TR, BR, BL), x and y in turn. */
    fun corners(vertical: Float, horizontal: Float): FloatArray {
        val v = vertical.coerceIn(-1f, 1f) * REACH
        val h = horizontal.coerceIn(-1f, 1f) * REACH
        // Vertical: the top widens and the bottom narrows (or the other way); horizontal: right and left.
        return floatArrayOf(
            -v / 2f, h / 2f,
            1f + v / 2f, -h / 2f,
            1f - v / 2f, 1f + h / 2f,
            v / 2f, 1f - h / 2f,
        )
    }

    /** The homography (row by row, 3×3) taking the unit square's corners (TL, TR, BR, BL) to [q]. */
    fun squareToQuad(q: FloatArray): DoubleArray {
        val x0 = q[0].toDouble(); val y0 = q[1].toDouble()
        val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
        val x2 = q[4].toDouble(); val y2 = q[5].toDouble()
        val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        if (abs(sx) < 1e-12 && abs(sy) < 1e-12) {
            return doubleArrayOf(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0, 0.0, 1.0)
        }
        val dx1 = x1 - x2; val dx2 = x3 - x2
        val dy1 = y1 - y2; val dy2 = y3 - y2
        val det = dx1 * dy2 - dx2 * dy1
        val g = (sx * dy2 - dx2 * sy) / det
        val h = (dx1 * sy - sx * dy1) / det
        // Unit square (u, v) with TL (0,0), TR (1,0), BR (1,1), BL (0,1).
        return doubleArrayOf(
            x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
            g, h, 1.0,
        )
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        return doubleArrayOf(
            A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det,
        )
    }

    fun apply(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    /**
     * Where each point of the corrected picture comes from in the original (3×3, row by row, on
     * fractions of the picture), with the zoom that keeps empty corners out; null without correction.
     */
    fun sourceOf(vertical: Float, horizontal: Float): DoubleArray? {
        if (vertical == 0f && horizontal == 0f) return null
        val forward = squareToQuad(corners(vertical, horizontal))
        val back = invert(forward)
        // The picture's middle stays in the middle: the output point p is first taken to
        // m + (p − 0.5) / z, where m is where the middle went, zoomed by z.
        val (mx, my) = apply(forward, 0.5, 0.5)
        fun withZoom(z: Double): DoubleArray {
            val k = 1.0 / z
            val s = doubleArrayOf(k, 0.0, mx - 0.5 * k, 0.0, k, my - 0.5 * k, 0.0, 0.0, 1.0)
            return multiply(back, s)
        }
        fun covers(m: DoubleArray): Boolean {
            val edge = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
            val border = edge.flatMap { t -> listOf(t to 0.0, t to 1.0, 0.0 to t, 1.0 to t) }
            return border.all { (x, y) ->
                val (sx, sy) = apply(m, x, y)
                sx >= -1e-4 && sx <= 1 + 1e-4 && sy >= -1e-4 && sy <= 1 + 1e-4
            }
        }
        var lo = 1.0
        var hi = 4.0
        if (covers(withZoom(lo))) return withZoom(lo)
        repeat(30) {
            val mid = (lo + hi) / 2
            if (covers(withZoom(mid))) hi = mid else lo = mid
        }
        return withZoom(hi)
    }

    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }
}
