package app.headsup.core.color

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the picture moved from one frame to the next, in pixels: a point p of the first frame is at
 * [centreX], [centreY] + ([shiftX], [shiftY]) + [scale] × (p − centre) turned by [turn] (radians)
 * in the second.
 */
data class Similarity(val centreX: Float, val centreY: Float, val shiftX: Float, val shiftY: Float, val turn: Float, val scale: Float) {
    /** Where the point ([x], [y]) of the second frame was in the first. */
    fun back(x: Float, y: Float): Pair<Float, Float> {
        val dx = (x - centreX - shiftX) / scale
        val dy = (y - centreY - shiftY) / scale
        val c = cos(-turn)
        val s = sin(-turn)
        return centreX + dx * c - dy * s to centreY + dx * s + dy * c
    }

    companion object {
        val NONE = Similarity(0f, 0f, 0f, 0f, 0f, 1f)
    }
}

/**
 * The camera's move between two frames, found from the picture: patches on a grid are found again
 * in the next frame (normalised cross-correlation) and one shift, turn and scale is fitted to them,
 * leaving out the ones that disagree (something moving through the shot). Used to carry a fix
 * painted on one frame of an AI-tracked mask to the next frames.
 */
object CameraMotion {
    fun between(a: GrayFrame, b: GrayFrame): Similarity? {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h) return null
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
                val sc = score(t, b, x + dx, y + dy, r)
                if (sc > best) { best = sc; bx = x + dx; by = y + dy }
            }
            val cx = bx
            val cy = by
            for (dy in -1..1) for (dx in -1..1) {
                val sc = score(t, b, cx + dx, cy + dy, r)
                if (sc > best) { best = sc; bx = cx + dx; by = cy + dy }
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
            val keep = pts.filterIndexed { i, _ -> res[i] <= max(1.5, typical * 2.5) }
            if (keep.size >= 3) { pts = keep; fit = fit(pts) ?: return null }
        }
        return Similarity(fit[4].toFloat(), fit[5].toFloat(), fit[0].toFloat(), fit[1].toFloat(), fit[2].toFloat(), fit[3].toFloat())
    }

    /** Shift (of the points' middle), turn, scale and that middle, taking the points' first places to the second. */
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
        val dx = q[0] - f[4]
        val dy = q[1] - f[5]
        val ex = f[4] + f[0] + f[3] * (dx * cos(f[2]) - dy * sin(f[2]))
        val ey = f[5] + f[1] + f[3] * (dx * sin(f[2]) + dy * cos(f[2]))
        return hypot(q[2] - ex, q[3] - ey)
    }

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

    /** [map] ([width]×[height], of the first frame) moved as the picture moved ([motion]), into the second frame. */
    fun carry(map: FloatArray, width: Int, height: Int, motion: Similarity): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val (sx0, sy0) = motion.back(x + 0.5f, y + 0.5f)
            val sx = sx0 - 0.5f
            val sy = sy0 - 0.5f
            if (sx < -0.5f || sy < -0.5f || sx > width - 0.5f || sy > height - 0.5f) continue
            val x0 = sx.toInt().coerceIn(0, width - 1)
            val y0 = sy.toInt().coerceIn(0, height - 1)
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val fx = (sx - x0).coerceIn(0f, 1f)
            val fy = (sy - y0).coerceIn(0f, 1f)
            val top = map[y0 * width + x0] * (1 - fx) + map[y0 * width + x1] * fx
            val bottom = map[y1 * width + x0] * (1 - fx) + map[y1 * width + x1] * fx
            out[y * width + x] = top * (1 - fy) + bottom * fy
        }
        return out
    }
}
