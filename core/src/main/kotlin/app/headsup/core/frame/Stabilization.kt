package app.headsup.core.frame

import app.headsup.core.color.GrayFrame
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the picture moved from one frame to the next: shift (fractions of the frame's width and
 * height), turn (radians, clockwise) and change of size (log).
 */
data class FrameMotion(val dx: Float, val dy: Float, val turn: Float, val logScale: Float)

/**
 * The camera's shake, found from the picture: patches on a grid are found again in the next frame
 * (normalised cross-correlation) and one shift, turn and scale is fitted to them, leaving out the
 * ones that disagree (something moving through the shot).
 */
object CameraMotion {
    fun between(a: GrayFrame, b: GrayFrame): FrameMotion? {
        val w = a.width
        val h = a.height
        val r = (h / 16).coerceAtLeast(5)
        val search = (h / 8).coerceAtLeast(6)
        val found = ArrayList<DoubleArray>()
        for (j in 0 until 4) for (i in 0 until 6) {
            val x = ((i + 0.5) / 6 * w).toInt()
            val y = ((j + 0.5) / 4 * h).toInt()
            if (x - r - search < 0 || x + r + search >= w || y - r - search < 0 || y + r + search >= h) continue
            val t = patch(a, x, y, r)
            if (variance(t) < 0.0004) continue
            var best = -2.0
            var bx = x
            var by = y
            for (dy in -search..search step 2) for (dx in -search..search step 2) {
                val s = score(t, b, x + dx, y + dy, r)
                if (s > best) { best = s; bx = x + dx; by = y + dy }
            }
            val cx = bx
            val cy = by
            for (dy in -1..1) for (dx in -1..1) {
                val s = score(t, b, cx + dx, cy + dy, r)
                if (s > best) { best = s; bx = cx + dx; by = cy + dy }
            }
            if (best < 0.6) continue
            found += doubleArrayOf(x.toDouble(), y.toDouble(), bx.toDouble(), by.toDouble())
        }
        if (found.size < 3) return null
        var pts: List<DoubleArray> = found
        var fit = fit(pts) ?: return null
        // Patches far from what the others agree on: something moving in the shot.
        repeat(2) {
            val res = pts.map { residual(fit, it) }
            val typical = res.sorted()[res.size / 2]
            val keep = pts.filterIndexed { i, _ -> res[i] <= maxOf(1.5, typical * 2.5) }
            if (keep.size >= 3) { pts = keep; fit = fit(pts) ?: return null }
        }
        val (tx, ty, turn, scale) = fit
        return FrameMotion((tx / w).toFloat(), (ty / h).toFloat(), turn.toFloat(), ln(scale).toFloat())
    }

    /** Shift (pixels, of the frame's middle), turn, scale taking the points' first places to the second. */
    private fun fit(p: List<DoubleArray>): DoubleArray? {
        val m0x = p.sumOf { it[0] } / p.size
        val m0y = p.sumOf { it[1] } / p.size
        val mx = p.sumOf { it[2] } / p.size
        val my = p.sumOf { it[3] } / p.size
        var a = 0.0
        var b = 0.0
        var s0 = 0.0
        for (q in p) {
            val dx0 = q[0] - m0x
            val dy0 = q[1] - m0y
            val dx = q[2] - mx
            val dy = q[3] - my
            a += dx0 * dx + dy0 * dy
            b += dx0 * dy - dy0 * dx
            s0 += dx0 * dx0 + dy0 * dy0
        }
        if (s0 < 1e-6) return null
        val s = hypot(a, b) / s0
        if (s !in 0.7..1.4) return null
        return doubleArrayOf(mx - m0x, my - m0y, atan2(b, a), s, m0x, m0y)
    }

    private fun residual(f: DoubleArray, q: DoubleArray): Double {
        val (tx, ty, turn, s, cx, cy) = f
        val dx = q[0] - cx
        val dy = q[1] - cy
        val ex = cx + tx + s * (dx * cos(turn) - dy * sin(turn))
        val ey = cy + ty + s * (dx * sin(turn) + dy * cos(turn))
        return hypot(q[2] - ex, q[3] - ey)
    }

    private operator fun DoubleArray.component6() = this[5]

    private fun patch(f: GrayFrame, px: Int, py: Int, r: Int): FloatArray {
        val n = 2 * r + 1
        return FloatArray(n * n) { i -> f[px - r + i % n, py - r + i / n] }
    }

    private fun variance(p: FloatArray): Double {
        val mean = p.average()
        return p.sumOf { (it - mean) * (it - mean) } / p.size
    }

    private fun score(t: FloatArray, f: GrayFrame, px: Int, py: Int, r: Int): Double {
        val n = 2 * r + 1
        var st = 0.0; var sp = 0.0; var stt = 0.0; var spp = 0.0; var stp = 0.0
        for (j in 0 until n) for (i in 0 until n) {
            val a = t[j * n + i].toDouble()
            val b = f[px - r + i, py - r + j].toDouble()
            st += a; sp += b; stt += a * a; spp += b * b; stp += a * b
        }
        val m = (n * n).toDouble()
        val cov = stp - st * sp / m
        val va = stt - st * st / m
        val vb = spp - sp * sp / m
        if (va < 1e-6 || vb < 1e-6) return 0.0
        return cov / sqrt(va * vb)
    }
}

/**
 * Where the picture's content was at moments of the clip's file (fractions of the frame, turn in
 * radians, log scale), built from the motion between frames: the camera's path as the picture shows it.
 */
data class CameraPath(
    val timesMs: List<Long>,
    val x: List<Float>,
    val y: List<Float>,
    val turn: List<Float>,
    val logScale: List<Float>,
) {
    companion object {
        /** The path from [motions] between frames at [timesMs] (one fewer motion than times; a missing one: no motion). */
        fun of(timesMs: List<Long>, motions: List<FrameMotion?>): CameraPath {
            val x = ArrayList<Float>(); val y = ArrayList<Float>(); val t = ArrayList<Float>(); val s = ArrayList<Float>()
            var cx = 0f; var cy = 0f; var ct = 0f; var cs = 0f
            for (i in timesMs.indices) {
                if (i > 0) motions.getOrNull(i - 1)?.let { m -> cx += m.dx; cy += m.dy; ct += m.turn; cs += m.logScale }
                x += cx; y += cy; t += ct; s += cs
            }
            return CameraPath(timesMs, x, y, t, s)
        }
    }
}

/**
 * Smoothing a clip's camera path ([path]) by [strength] (0 a little, 1 nearly a slow glide): each
 * frame is moved, turned and scaled from where the shake put it to where the smooth path is, and the
 * picture zoomed in ([zoom]) just enough that no edge shows; where more would be needed, the
 * correction gives way a little.
 */
data class Stabilization(val path: CameraPath, val strength: Float = 0.6f, val aspect: Float = 16f / 9f) {
    /** The correction at a moment: shift (fractions), turn (degrees, clockwise) and scale. */
    data class Correction(val dx: Float, val dy: Float, val degrees: Float, val scale: Float)

    private val corrections: List<Correction> by lazy { compute() }

    /** Zoom applied throughout, so the moved frames still cover the picture. */
    val zoom: Float by lazy { zoomFor(raw()) }

    private fun raw(): List<FloatArray> {
        val n = path.timesMs.size
        if (n < 2) return List(n) { FloatArray(4) }
        val stepMs = ((path.timesMs.last() - path.timesMs.first()).toFloat() / (n - 1)).coerceAtLeast(1f)
        val sigma = ((0.25f + 1.75f * strength.coerceIn(0f, 1f)) * 1000f / stepMs).coerceAtLeast(0.5f)
        val sx = smooth(path.x, sigma); val sy = smooth(path.y, sigma)
        val st = smooth(path.turn, sigma); val ss = smooth(path.logScale, sigma)
        // The content goes where the smooth path is: move the frame by the difference.
        return List(n) { i -> floatArrayOf(sx[i] - path.x[i], sy[i] - path.y[i], st[i] - path.turn[i], ss[i] - path.logScale[i]) }
    }

    private fun need(c: FloatArray): Float {
        val a = abs(c[2])
        val wide = max(aspect, 1f / aspect)
        val turnCover = cos(a) + sin(a) * wide
        return turnCover * (1f + 2f * max(abs(c[0]), abs(c[1]))) * exp(max(0f, -c[3]))
    }

    private fun zoomFor(raw: List<FloatArray>): Float {
        if (raw.isEmpty()) return 1f
        val needs = raw.map { need(it) }.sorted()
        return needs[((needs.size - 1) * 0.98f).toInt()].coerceIn(1f, MAX_ZOOM)
    }

    private fun compute(): List<Correction> {
        val raw = raw()
        val z = zoomFor(raw)
        return raw.map { c ->
            val n = need(c)
            // More than the zoom allows: the correction gives way.
            val k = if (n > z && n > 1f) ((z - 1f) / (n - 1f)).coerceIn(0f, 1f) else 1f
            Correction(c[0] * k, c[1] * k, Math.toDegrees((c[2] * k).toDouble()).toFloat(), exp(c[3] * k))
        }
    }

    /** The correction at [fileMs] (between samples: in between). */
    fun at(fileMs: Long): Correction {
        val t = path.timesMs
        val c = corrections
        if (c.isEmpty()) return Correction(0f, 0f, 0f, 1f)
        if (fileMs <= t.first()) return c.first()
        if (fileMs >= t.last()) return c.last()
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (t[mid] <= fileMs) lo = mid else hi = mid }
        val f = (fileMs - t[lo]).toFloat() / (t[hi] - t[lo]).coerceAtLeast(1L)
        val a = c[lo]
        val b = c[hi]
        fun mix(p: Float, q: Float) = p + (q - p) * f
        return Correction(mix(a.dx, b.dx), mix(a.dy, b.dy), mix(a.degrees, b.degrees), mix(a.scale, b.scale))
    }

    companion object {
        /** Stabilizing never zooms in more than this. */
        const val MAX_ZOOM = 1.3f

        /** Gaussian smoothing, the ends mirrored. */
        fun smooth(v: List<Float>, sigma: Float): FloatArray {
            val n = v.size
            val r = (sigma * 3f).toInt().coerceAtLeast(1)
            val weights = FloatArray(2 * r + 1) { k -> val d = (k - r).toFloat(); exp(-d * d / (2f * sigma * sigma)) }
            return FloatArray(n) { i ->
                var acc = 0f
                var sum = 0f
                for (k in -r..r) {
                    var j = i + k
                    if (j < 0) j = -j
                    if (j >= n) j = 2 * n - 2 - j
                    j = j.coerceIn(0, n - 1)
                    val wgt = weights[k + r]
                    acc += v[j] * wgt
                    sum += wgt
                }
                acc / sum
            }
        }
    }
}
