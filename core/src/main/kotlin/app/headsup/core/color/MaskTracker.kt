package app.headsup.core.color

import kotlin.math.abs
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
 * Makes a mask follow the picture through a clip, like a planar tracker in DaVinci: patches of the
 * picture spread over the mask's area (along it, for a line) are found again in each next frame by
 * normalised cross-correlation, and one affine change (move, turn, scale, and the squash and slant
 * of a surface seen from a new angle, e.g. a round table becoming an ellipse) is fitted to them
 * all, leaving out the ones that disagree (lost on a reflection, covered by something). Patches slowly take on the
 * picture's new look, so an object seen ever closer is still found. Patches that leave the frame
 * stop counting; when the whole area leaves it, the mask carries on at its last speed until it is
 * out of the picture ([leftFrame]). Tracking stops where too few patches can be found.
 */
class MaskTracker(
    start: ColourMask,
    first: GrayFrame,
    private val startMs: Long,
    /**
     * Where in the frame to follow the picture (fractions), when not the mask's centre: a painted
     * mask is followed where it is painted, and its centre moves, turns and scales with that place.
     */
    anchor: Pair<Float, Float>? = null,
    /** Half the width and height of the area to follow (fractions of the frame), when known (a painted mask's). */
    spread: Pair<Float, Float>? = null,
) {
    private val radius = (first.height / 20).coerceAtLeast(6)
    private val search = radius * 2
    private val base = start
    private val width = first.width
    private val height = first.height
    private val points: List<Point>

    /** The mask's centre at the start, in pixels. */
    private val c0x = start.centerX * first.width.toDouble()
    private val c0y = start.centerY * first.height.toDouble()

    /** How far the followed area reaches from its middle, in pixels (for when it leaves the frame). */
    private val reach: Double

    /** The mask as last fitted: angle (degrees), sizes, its centre and how it moved since the frame before. */
    private var angle = start.angleDegrees.toDouble()
    private var sizeX = start.sizeX.toDouble()
    private var sizeY = start.sizeY.toDouble()
    private var cx = c0x
    private var cy = c0y
    private var vx = 0.0
    private var vy = 0.0

    /** Set once tracking stopped; later frames are ignored. */
    var lost: Boolean = false
        private set

    /** Set when tracking stopped because the area went out of the picture (the mask was carried out with it). */
    var leftFrame: Boolean = false
        private set

    init {
        val ax = (anchor?.first ?: start.centerX) * first.width.toDouble()
        val ay = (anchor?.second ?: start.centerY) * first.height.toDouble()
        val a = Math.toRadians(start.angleDegrees.toDouble())
        val candidates = if (start.kind == MaskKind.LINEAR && anchor == null) {
            // Along the line.
            val d = first.height * 0.15
            listOf(-1.0, -0.5, 0.0, 0.5, 1.0).map { t -> ax + t * d * cos(a) to ay + t * d * sin(a) }
        } else {
            // A 3×3 grid over the middle of the area, turned with the mask.
            val hx = spread?.first?.times(first.width)?.toDouble() ?: (start.sizeX / 2.0 * first.height * 0.6)
            val hy = spread?.second?.times(first.height)?.toDouble() ?: (start.sizeY / 2.0 * first.height * 0.6)
            val ux = hx.coerceAtLeast(radius * 1.5)
            val uy = hy.coerceAtLeast(radius * 1.5)
            (-1..1).flatMap { j -> (-1..1).map { i -> i * ux * 0.7 to j * uy * 0.7 } }
                .map { (dx, dy) -> ax + dx * cos(a) - dy * sin(a) to ay + dx * sin(a) + dy * cos(a) }
        }
        reach = (spread?.let { hypot(it.first * first.width.toDouble(), it.second * first.height.toDouble()) } ?: (start.sizeX / 2.0 * first.height))
            .coerceAtLeast(radius * 2.0)
        val inFrame = candidates.filter { (x, y) -> inside(x, y) }
        // Patches with some texture to find again; flat ones (a white wall) only if nothing else.
        val textured = inFrame.filter { (x, y) -> Point.contrast(first, x.toInt(), y.toInt(), radius) > 0.0004 }
        points = (if (textured.size >= 2) textured else inFrame).map { (x, y) -> Point(first, x, y, radius) }
    }

    private fun inside(x: Double, y: Double) = x > radius + 1 && x < width - radius - 2 && y > radius + 1 && y < height - radius - 2

    /** The mask's key for [frame] at [timeMs], or null once tracking stopped. */
    fun next(frame: GrayFrame, timeMs: Long): MaskKey? {
        if (lost) return null
        for (p in points) {
            if (!p.alive || p.out) continue
            // Leaving the frame: no longer measured, not lost.
            if (!inside(p.x + p.vx, p.y + p.vy)) { p.out = true; continue }
            if (!p.follow(frame, search)) p.alive = false
        }
        var measured = points.filter { it.alive && !it.out }
        var fit = if (measured.size >= 2) fit(measured) else null
        if (fit != null) {
            // Patches far from what the others agree on are lost (a reflection, something in front).
            val residuals = measured.map { p -> fit!!.residual(p) }
            val typical = residuals.sorted()[residuals.size / 2]
            val limit = maxOf(2.0, typical * 3.0)
            measured.forEachIndexed { i, p -> if (residuals[i] > limit) p.alive = false }
            measured = measured.filter { it.alive }
            fit = if (measured.size >= 2) fit(measured) else null
        }
        if (fit == null) {
            val goingOut = points.any { it.out } && hypot(vx, vy) > 0.5
            if (!goingOut) {
                lost = true
                return null
            }
            // The area is leaving the picture: carry the mask on until it is out.
            cx += vx
            cy += vy
            val gone = cx < -reach || cx > width + reach || cy < -reach || cy > height + reach
            if (gone) {
                lost = true
                leftFrame = true
            }
            return key(timeMs)
        }
        val (nx, ny) = fit.apply(c0x, c0y)
        vx = nx - cx
        vy = ny - cy
        cx = nx
        cy = ny
        shape(fit)
        return key(timeMs)
    }

    private fun key(timeMs: Long) = MaskKey(
        timeMs,
        (cx / width).toFloat(),
        (cy / height).toFloat(),
        angle.toFloat(),
        sizeX.toFloat(),
        sizeY.toFloat(),
    )

    /**
     * The mask's angle and sizes after [fit]'s change. An oval stays an exact oval (its axes from the
     * changed ellipse); a painted mask takes the nearest turn with a stretch along its own axes; a
     * line takes the turn of its direction.
     */
    private fun shape(fit: Fit) {
        val a0 = Math.toRadians(base.angleDegrees.toDouble())
        val c = cos(a0)
        val s = sin(a0)
        // The mask's axes (in pixels, isotropic) after the change: M = A · R(a0) · diag(sx, sy).
        val m11 = (fit.a11 * c + fit.a12 * s) * base.sizeX
        val m21 = (fit.a21 * c + fit.a22 * s) * base.sizeX
        val m12 = (-fit.a11 * s + fit.a12 * c) * base.sizeY
        val m22 = (-fit.a21 * s + fit.a22 * c) * base.sizeY
        when (base.kind) {
            MaskKind.LINEAR -> angle = Math.toDegrees(atan2(fit.a21 * c + fit.a22 * s, fit.a11 * c + fit.a12 * s))
            MaskKind.ELLIPSE -> {
                // Singular values of M: the new axes; the left rotation: their angle.
                val e = (m11 + m22) / 2
                val f = (m11 - m22) / 2
                val g = (m21 + m12) / 2
                val h = (m21 - m12) / 2
                val q = hypot(e, h)
                val r = hypot(f, g)
                val theta = Math.toDegrees((atan2(h, e) + atan2(g, f)) / 2)
                // The same oval can be named by a turn of half a revolution, or a quarter with the
                // sizes swapped: take the one nearest the last, so keys glide.
                val options = listOf(Triple(theta, q + r, abs(q - r)), Triple(theta + 90, abs(q - r), q + r))
                    .flatMap { (t, x, y) -> listOf(-360.0, -180.0, 0.0, 180.0, 360.0).map { Triple(t + it, x, y) } }
                val best = options.minBy { abs(it.first - angle) }
                angle = best.first
                sizeX = best.second
                sizeY = best.third
            }
            MaskKind.BRUSH -> {
                // Nearest turn (polar decomposition), then the stretch along the mask's own axes.
                val t = atan2(m21 - m12, m11 + m22)
                val ct = cos(t)
                val st = sin(t)
                angle = Math.toDegrees(t)
                sizeX = abs(ct * m11 + st * m21)
                sizeY = abs(-st * m12 + ct * m22)
            }
        }
    }

    /**
     * The change taking the patches' starting places to where they are now (least squares):
     * p = m + A · (p0 − m0). Affine with enough patches spread out, else a move, turn and scale.
     */
    private class Fit(
        val m0x: Double, val m0y: Double, val mx: Double, val my: Double,
        val a11: Double, val a12: Double, val a21: Double, val a22: Double,
    ) {
        fun apply(x: Double, y: Double): Pair<Double, Double> {
            val dx = x - m0x
            val dy = y - m0y
            return mx + a11 * dx + a12 * dy to my + a21 * dx + a22 * dy
        }

        fun residual(p: Point): Double {
            val (ex, ey) = apply(p.x0, p.y0)
            return hypot(p.x - ex, p.y - ey)
        }
    }

    private fun fit(ps: List<Point>): Fit? {
        val m0x = ps.sumOf { it.x0 } / ps.size
        val m0y = ps.sumOf { it.y0 } / ps.size
        val mx = ps.sumOf { it.x } / ps.size
        val my = ps.sumOf { it.y } / ps.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var bxx = 0.0; var bxy = 0.0; var byx = 0.0; var byy = 0.0
        var a = 0.0; var b = 0.0
        for (p in ps) {
            val dx0 = p.x0 - m0x
            val dy0 = p.y0 - m0y
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx0 * dx0; sxy += dx0 * dy0; syy += dy0 * dy0
            bxx += dx * dx0; bxy += dx * dy0; byx += dy * dx0; byy += dy * dy0
            a += dx0 * dx + dy0 * dy
            b += dx0 * dy - dy0 * dx
        }
        val s0 = sxx + syy
        if (s0 < 1e-6) return null
        val det = sxx * syy - sxy * sxy
        // Spread in both directions (not all on a line) and enough of them: the full change.
        val spread = det / (s0 * s0 / 4)
        if (ps.size >= 4 && spread > 0.05) {
            val a11 = (bxx * syy - bxy * sxy) / det
            val a12 = (bxy * sxx - bxx * sxy) / det
            val a21 = (byx * syy - byy * sxy) / det
            val a22 = (byy * sxx - byx * sxy) / det
            val area = a11 * a22 - a12 * a21
            // Mirrored, collapsed or blown up: a bad match, not the camera.
            if (area < 0.04 || area > 25.0) return null
            val s1 = hypot(a11, a21)
            val s2 = hypot(a12, a22)
            if (s1 / s2 !in 0.2..5.0) return null
            return Fit(m0x, m0y, mx, my, a11, a12, a21, a22)
        }
        val s = hypot(a, b) / s0
        if (s !in 0.2..5.0) return null
        val t = atan2(b, a)
        return Fit(m0x, m0y, mx, my, s * cos(t), -s * sin(t), s * sin(t), s * cos(t))
    }

    /** The key at the starting frame, so the track begins where the mask was placed. */
    fun startKey(): MaskKey = MaskKey.of(base, startMs)

    /** One followed patch. */
    private class Point(frame: GrayFrame, var x: Double, var y: Double, private val r: Int) {
        val x0 = x
        val y0 = y
        var alive = true
        var out = false
        var vx = 0.0
        var vy = 0.0
        private var template = patch(frame, x.toInt(), y.toInt(), r)

        companion object {
            fun patch(f: GrayFrame, px: Int, py: Int, r: Int): FloatArray {
                val n = 2 * r + 1
                return FloatArray(n * n) { i -> f[px - r + i % n, py - r + i / n] }
            }

            /** The patch's variance: how much there is to find again. */
            fun contrast(f: GrayFrame, px: Int, py: Int, r: Int): Double {
                val p = patch(f, px, py, r)
                val mean = p.average()
                return p.sumOf { (it - mean) * (it - mean) } / p.size
            }
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
            if (best < 0.55) return false
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
            // Take on the picture's new look (an object seen closer, from another side), more
            // when the match is sure; the fit over all patches keeps one patch's drift in check.
            if (best > 0.75) {
                val fresh = patch(f, bx, by, r)
                val k = if (best > 0.9) 0.35f else 0.2f
                for (i in template.indices) template[i] = template[i] * (1f - k) + fresh[i] * k
            }
            return true
        }
    }
}
