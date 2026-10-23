package app.headsup.core.color

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A small grey picture (0..1 per pixel, row by row), e.g. a scaled-down video frame. */
class GrayFrame(val width: Int, val height: Int, val pixels: FloatArray) {
    operator fun get(x: Int, y: Int): Float = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/**
 * Makes a mask follow the picture through a clip, like a tracker in DaVinci: two patches of the
 * picture along the mask (either side of its centre) are found again in each next frame by
 * normalised cross-correlation. Their midpoint moves the mask, the line between them turns and
 * scales it. Tracking stops where a patch can no longer be found with confidence.
 */
class MaskTracker(start: ColourMask, first: GrayFrame, private val startMs: Long) {
    private val radius = (first.height / 24).coerceAtLeast(6)
    private val search = radius * 2
    private val base = start
    private val p1: Point
    private val p2: Point
    private val startAngle: Double
    private val startLength: Double

    /** Set once a patch was lost; later frames are ignored. */
    var lost: Boolean = false
        private set

    init {
        val cx = start.centerX * first.width
        val cy = start.centerY * first.height
        val a = Math.toRadians(start.angleDegrees.toDouble())
        // Points along the mask, kept inside the frame with room for their patch.
        var d = first.height * 0.15
        fun inside(x: Double, y: Double) = x > radius + 1 && x < first.width - radius - 2 && y > radius + 1 && y < first.height - radius - 2
        while (d > radius && !(inside(cx - d * cos(a), cy - d * sin(a)) && inside(cx + d * cos(a), cy + d * sin(a)))) d *= 0.8
        p1 = Point(first, cx - d * cos(a), cy - d * sin(a), radius)
        p2 = Point(first, cx + d * cos(a), cy + d * sin(a), radius)
        startAngle = atan2(p2.y - p1.y, p2.x - p1.x)
        startLength = hypot(p2.x - p1.x, p2.y - p1.y).coerceAtLeast(1.0)
    }

    /** The mask's key for [frame] at [timeMs], or null once tracking is lost. */
    fun next(frame: GrayFrame, timeMs: Long): MaskKey? {
        if (lost) return null
        if (!p1.follow(frame, search) || !p2.follow(frame, search)) {
            lost = true
            return null
        }
        val cx = (p1.x + p2.x) / 2
        val cy = (p1.y + p2.y) / 2
        val angle = atan2(p2.y - p1.y, p2.x - p1.x)
        val scale = (hypot(p2.x - p1.x, p2.y - p1.y) / startLength).toFloat()
        return MaskKey(
            timeMs,
            (cx / frame.width).toFloat(),
            (cy / frame.height).toFloat(),
            base.angleDegrees + Math.toDegrees(angle - startAngle).toFloat(),
            base.sizeX * scale,
            base.sizeY * scale,
        )
    }

    /** The key at the starting frame, so the track begins where the mask was placed. */
    fun startKey(): MaskKey = MaskKey.of(base, startMs)

    /** One followed patch. */
    private class Point(frame: GrayFrame, var x: Double, var y: Double, private val r: Int) {
        private var vx = 0.0
        private var vy = 0.0
        private var template = patch(frame, x.toInt(), y.toInt())

        private fun patch(f: GrayFrame, px: Int, py: Int): FloatArray {
            val n = 2 * r + 1
            return FloatArray(n * n) { i -> f[px - r + i % n, py - r + i / n] }
        }

        /** Normalised cross-correlation of the template with the patch centred at ([px], [py]), -1..1. */
        private fun score(f: GrayFrame, px: Int, py: Int): Double {
            val n = 2 * r + 1
            var st = 0.0; var sp = 0.0; var stt = 0.0; var spp = 0.0; var stp = 0.0
            for (j in 0 until n) for (i in 0 until n) {
                val t = template[j * n + i].toDouble()
                val p = f[px - r + i, py - r + j].toDouble()
                st += t; sp += p; stt += t * t; spp += p * p; stp += t * p
            }
            val m = (n * n).toDouble()
            val cov = stp - st * sp / m
            val vt = stt - st * st / m
            val vp = spp - sp * sp / m
            if (vt < 1e-6 || vp < 1e-6) return 0.0
            return cov / sqrt(vt * vp)
        }

        /** Finds the patch again near where it is expected; false when no place matches well. */
        fun follow(f: GrayFrame, search: Int): Boolean {
            val ex = (x + vx).toInt()
            val ey = (y + vy).toInt()
            // Coarse (every 2 px), then the best spot's neighbours.
            var best = -2.0; var bx = ex; var by = ey
            for (dy in -search..search step 2) for (dx in -search..search step 2) {
                val s = score(f, ex + dx, ey + dy)
                if (s > best) { best = s; bx = ex + dx; by = ey + dy }
            }
            val cx = bx; val cy = by
            for (dy in -1..1) for (dx in -1..1) {
                val s = score(f, cx + dx, cy + dy)
                if (s > best) { best = s; bx = cx + dx; by = cy + dy }
            }
            if (best < 0.6) return false
            // Sub-pixel position from a parabola through the neighbouring scores.
            fun offset(a: Double, b: Double, c: Double): Double {
                val d = a - 2 * b + c
                return if (d < -1e-9) ((a - c) / (2 * d)).coerceIn(-0.5, 0.5) else 0.0
            }
            val nx = bx + offset(score(f, bx - 1, by), best, score(f, bx + 1, by))
            val ny = by + offset(score(f, bx, by - 1), best, score(f, bx, by + 1))
            vx = nx - x
            vy = ny - y
            x = nx
            y = ny
            // Follow slow changes of appearance without drifting off the original.
            if (best > 0.85) {
                val fresh = patch(f, bx, by)
                for (i in template.indices) template[i] = template[i] * 0.8f + fresh[i] * 0.2f
            }
            return true
        }
    }
}
