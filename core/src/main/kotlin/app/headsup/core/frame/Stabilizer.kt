package app.headsup.core.frame

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A shot steadied: how the camera moved from frame to frame (shift and turn, measured on small
 * grey copies of the frames), that path smoothed, and each frame moved by the difference, so
 * the shake goes and the camera's intended move (a pan, a walk) stays. The frame is zoomed in a
 * little so the moved picture always covers it.
 */
object Stabilizer {
    /** One frame's move against the one before: in fractions of the frame's width and height, and degrees. */
    data class Step(val dx: Float, val dy: Float, val degrees: Float)

    /**
     * A shot's measured path: [steps] one per frame from [startMs] (file time), [frameMs] apart.
     * [strength] 0..1 how much of the shake goes (1: as steady as the crop allows).
     */
    data class Path(
        val startMs: Long,
        val frameMs: Float,
        val steps: List<Step>,
        val strength: Float = 0.7f,
        /**
         * Locked: all the wobble and roll taken out, only the camera's steady drift kept (a straight,
         * even move through the shot, as a slow Ken Burns), not just the shake.
         */
        val locked: Boolean = false,
        /** The most the frame may be zoomed in to hide the moved picture's edges (1.2 is 20 %). */
        val maxZoom: Float = MAX_ZOOM,
    )

    /** A frame's correction: the picture moved by ([dx], [dy]) (fractions), turned by [degrees] and zoomed by [zoom]. */
    data class Correction(val dx: Float, val dy: Float, val degrees: Float, val zoom: Float) {
        val isNone: Boolean get() = dx == 0f && dy == 0f && degrees == 0f && zoom == 1f
    }

    /** The most a frame is zoomed in to hide the moved picture's edges. */
    const val MAX_ZOOM = 1.2f

    /**
     * How [cur] moved against [prev] (grey 0..1, [width]×[height] each): blocks of a grid found
     * again by the best match nearby, and one shift and turn fitted to them, the blocks that
     * moved on their own (a car, a hand) left out.
     */
    fun step(prev: FloatArray, cur: FloatArray, width: Int, height: Int): Step {
        val block = max(8, min(width, height) / 12)
        val reach = max(4, min(width, height) / 10)
        val cols = 8
        val rows = 6
        data class V(val x: Float, val y: Float, val dx: Float, val dy: Float)
        val vectors = ArrayList<V>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val bx = reach + (width - 2 * reach - block) * c / (cols - 1)
            val by = reach + (height - 2 * reach - block) * r / (rows - 1)
            if (bx < reach || by < reach || bx + block + reach > width || by + block + reach > height) continue
            // A flat block (sky, a wall) cannot tell where it went.
            var mean = 0f
            for (y in 0 until block) for (x in 0 until block) mean += prev[(by + y) * width + bx + x]
            mean /= block * block
            var spread = 0f
            for (y in 0 until block) for (x in 0 until block) spread += abs(prev[(by + y) * width + bx + x] - mean)
            if (spread / (block * block) < 0.015f) continue
            var best = Float.MAX_VALUE
            var bdx = 0
            var bdy = 0
            for (dy in -reach..reach) for (dx in -reach..reach) {
                var sad = 0f
                var y = 0
                while (y < block && sad < best) {
                    val a = (by + y) * width + bx
                    val b = (by + y + dy) * width + bx + dx
                    for (x in 0 until block step 2) sad += abs(prev[a + x] - cur[b + x])
                    y += 2
                }
                if (sad < best) { best = sad; bdx = dx; bdy = dy }
            }
            // Between pixels (at high frame rates a frame moves less than a pixel, which whole
            // pixels would miss): what is left after the whole-pixel match, from how the picture
            // changes against its slopes (one Lucas–Kanade step), both axes at once.
            var gxx = 0.0; var gxy = 0.0; var gyy = 0.0; var bxt = 0.0; var byt = 0.0
            for (y in 0 until block) {
                val row = (by + y) * width + bx
                val moved = (by + y + bdy) * width + bx + bdx
                for (x in 0 until block) {
                    val p = row + x
                    val ix = (prev[p + 1] - prev[p - 1]) * 0.5f
                    val iy = (prev[p + width] - prev[p - width]) * 0.5f
                    val it = cur[moved + x] - prev[p]
                    gxx += ix * ix; gxy += ix * iy; gyy += iy * iy
                    bxt += ix * it; byt += iy * it
                }
            }
            val det = gxx * gyy - gxy * gxy
            var fx = 0f
            var fy = 0f
            if (det > 1e-9) {
                fx = (-(gyy * bxt - gxy * byt) / det).toFloat().coerceIn(-1f, 1f)
                fy = (-(gxx * byt - gxy * bxt) / det).toFloat().coerceIn(-1f, 1f)
            }
            vectors += V(bx + block / 2f - width / 2f, by + block / 2f - height / 2f, bdx + fx, bdy + fy)
        }
        if (vectors.size < 4) return Step(0f, 0f, 0f)
        // The shift most blocks agree on (median), then those near it fit the turn too.
        val mx = vectors.map { it.dx }.sorted()[vectors.size / 2]
        val my = vectors.map { it.dy }.sorted()[vectors.size / 2]
        var kept = vectors.filter { abs(it.dx - mx) <= 3f && abs(it.dy - my) <= 3f }
        if (kept.size < 3) kept = vectors
        // Least squares for a small turn θ and shift t: d ≈ t + θ × (−y, x).
        var sx = 0.0; var sy = 0.0; var num = 0.0; var den = 0.0
        for (v in kept) { sx += v.dx; sy += v.dy }
        val tx = sx / kept.size
        val ty = sy / kept.size
        val cx = kept.map { it.x }.average()
        val cy = kept.map { it.y }.average()
        for (v in kept) {
            val px = v.x - cx
            val py = v.y - cy
            num += px * (v.dy - ty) - py * (v.dx - tx)
            den += px * px + py * py
        }
        val theta = if (den > 1e-6) num / den else 0.0
        return Step((tx / width).toFloat(), (ty / height).toFloat(), Math.toDegrees(theta).toFloat())
    }

    /**
     * The corrections for each frame of [path]: its cumulative move, smoothed over about a second
     * (more with more [Path.strength]), minus the move itself; the zoom that covers the largest
     * of them, at most [MAX_ZOOM] (corrections beyond that are cut back to fit).
     */
    fun corrections(path: Path, aspect: Float): List<Correction> {
        val n = path.steps.size
        if (n == 0 || path.strength <= 0f) return List(n) { Correction(0f, 0f, 0f, 1f) }
        val x = FloatArray(n)
        val y = FloatArray(n)
        val a = FloatArray(n)
        for (i in 1 until n) {
            x[i] = x[i - 1] + path.steps[i].dx
            y[i] = y[i - 1] + path.steps[i].dy
            a[i] = a[i - 1] + path.steps[i].degrees
        }
        val sigma = max(1f, (250f + 1000f * path.strength) / path.frameMs)
        // Locked: the path a straight line through the shot (its even drift); else smoothed.
        val sx = if (path.locked) line(x) else gaussian(x, sigma)
        val sy = if (path.locked) line(y) else gaussian(y, sigma)
        // Locked, the turn is held still (a horizon does not drift): the shot's mean angle.
        val sa = if (path.locked) FloatArray(n) { a.average().toFloat() } else gaussian(a, sigma)
        val raw = List(n) { i -> Triple(sx[i] - x[i], sy[i] - y[i], sa[i] - a[i]) }
        // The zoom that keeps the picture over the frame: a shift of d needs 1 + 2d; a turn
        // of θ about cos θ + sin θ · (long side / short side).
        fun need(c: Triple<Float, Float, Float>): Float {
            val t = Math.toRadians(abs(c.third).toDouble())
            val long = max(aspect, 1f / aspect)
            val turn = (cos(t) + sin(t) * long).toFloat()
            return turn + 2f * max(abs(c.first), abs(c.second))
        }
        val zoom = min(path.maxZoom.coerceIn(1f, 2f), raw.maxOf { need(it) }.coerceAtLeast(1f))
        return raw.map { c ->
            // More than the zoom can hide: cut back, the turn kept.
            val over = need(c)
            val k = if (over > zoom) ((zoom - (need(Triple(0f, 0f, c.third)))) / (over - need(Triple(0f, 0f, c.third))).coerceAtLeast(1e-6f)).coerceIn(0f, 1f) else 1f
            Correction(c.first * k, c.second * k, c.third, zoom)
        }
    }

    /** The correction at [fileMs] of [path] (from [corrections]), the frame nearest. */
    fun at(path: Path, corrections: List<Correction>, fileMs: Long): Correction {
        if (corrections.isEmpty()) return Correction(0f, 0f, 0f, 1f)
        val i = ((fileMs - path.startMs) / path.frameMs).toInt().coerceIn(0, corrections.lastIndex)
        return corrections[i]
    }

    /** The straight line that fits [v] best (least squares): its even drift. */
    private fun line(v: FloatArray): FloatArray {
        val n = v.size
        if (n < 2) return v.copyOf()
        val mt = (n - 1) / 2.0
        val mv = v.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - mt) * (v[i] - mv)
            den += (i - mt) * (i - mt)
        }
        val slope = if (den > 0) num / den else 0.0
        return FloatArray(n) { i -> (mv + slope * (i - mt)).toFloat() }
    }

    private fun gaussian(v: FloatArray, sigma: Float): FloatArray {
        val r = (3f * sigma).toInt().coerceAtLeast(1)
        val w = FloatArray(2 * r + 1) { k -> exp(-((k - r) * (k - r)) / (2f * sigma * sigma)) }
        return FloatArray(v.size) { i ->
            var s = 0f
            var ws = 0f
            for (k in -r..r) {
                // At the ends the path is mirrored, so it does not drift back to zero there.
                var j = i + k
                if (j < 0) j = -j
                if (j >= v.size) j = 2 * (v.size - 1) - j
                j = j.coerceIn(0, v.size - 1)
                s += v[j] * w[k + r]
                ws += w[k + r]
            }
            s / ws
        }
    }

    @Suppress("unused")
    private fun angleOf(x: Float, y: Float) = atan2(y, x)

    @Suppress("unused")
    private fun length(x: Float, y: Float) = sqrt(x * x + y * y)
}
