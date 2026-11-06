package app.headsup.core.retouch

import kotlin.math.max
import kotlin.math.min

/**
 * Healing and cloning a photo, as with a heal brush: what is painted over is covered with
 * another part of the photo, found nearby where its surroundings match best. Clone copies that
 * part as it is; Heal also carries over the light and colour of the painted place, so the copy
 * meets the photo without a seam (the difference between the two is spread smoothly over it,
 * as a membrane fixed at its edge), while its own texture (grain, pores, leaves) stays.
 */
object Heal {
    /** A place to copy from: the painted pixels moved by ([dx], [dy]); [cost] how unlike the surroundings are (smaller is better). */
    data class Source(val dx: Int, val dy: Int, val cost: Float)

    /**
     * The best places to copy [hole] (inside [area]) from, best first, at most [count], none of
     * them overlapping each other much. The surroundings compared are a ring [ring] pixels wide
     * around the hole; the source never covers the hole itself and stays within the picture.
     */
    fun sources(rgb: FloatArray, width: Int, height: Int, hole: BooleanArray, area: PixelBox, ring: Int, count: Int = 4): List<Source> {
        val near = Retouch.dilate(hole, width, height, ring)
        // The ring and the hole, as offsets inside the area grown by the ring.
        val bx0 = max(0, area.x0 - ring)
        val by0 = max(0, area.y0 - ring)
        val bx1 = min(width, area.x1 + ring)
        val by1 = min(height, area.y1 + ring)
        val ringPx = ArrayList<Int>()
        val holePx = ArrayList<Int>()
        for (y in by0 until by1) for (x in bx0 until bx1) {
            val i = y * width + x
            if (hole[i]) holePx += i else if (near[i]) ringPx += i
        }
        if (holePx.isEmpty() || ringPx.isEmpty()) return emptyList()
        // A few hundred pixels of each are plenty to compare.
        val ringSample = ringPx.filterIndexed { k, _ -> k % max(1, ringPx.size / 600) == 0 }
        val holeSample = holePx.filterIndexed { k, _ -> k % max(1, holePx.size / 200) == 0 }
        val size = max(bx1 - bx0, by1 - by0)
        val reach = max(size * 3, 40)
        val step = max(1, size / 6)
        fun cost(dx: Int, dy: Int): Float {
            // Wholly in the picture, and not copying the painted place itself.
            if (bx0 + dx < 0 || by0 + dy < 0 || bx1 + dx > width || by1 + dy > height) return Float.MAX_VALUE
            for (i in holeSample) if (near[i + dy * width + dx]) return Float.MAX_VALUE
            var sum = 0f
            for (i in ringSample) {
                val j = i + dy * width + dx
                if (hole[j]) return Float.MAX_VALUE
                for (c in 0..2) {
                    val d = rgb[i * 3 + c] - rgb[j * 3 + c]
                    sum += d * d
                }
            }
            // A little in favour of places close by (the light is more alike there).
            val far = kotlin.math.sqrt((dx * dx + dy * dy).toFloat()) / reach
            return sum / ringSample.size * (1f + 0.15f * far)
        }
        val coarse = ArrayList<Source>()
        var dy = -reach
        while (dy <= reach) {
            var dx = -reach
            while (dx <= reach) {
                if (kotlin.math.abs(dx) > size / 2 || kotlin.math.abs(dy) > size / 2) {
                    val c = cost(dx, dy)
                    if (c < Float.MAX_VALUE) coarse += Source(dx, dy, c)
                }
                dx += step
            }
            dy += step
        }
        coarse.sortBy { it.cost }
        // Each of the best few made exact around where it is, and kept apart from the others.
        val out = ArrayList<Source>()
        for (s in coarse) {
            if (out.size >= count) break
            if (out.any { kotlin.math.abs(it.dx - s.dx) < size && kotlin.math.abs(it.dy - s.dy) < size }) continue
            var best = s
            for (ry in -step..step) for (rx in -step..step) {
                val c = cost(s.dx + rx, s.dy + ry)
                if (c < best.cost) best = Source(s.dx + rx, s.dy + ry, c)
            }
            if (out.none { kotlin.math.abs(it.dx - best.dx) < size && kotlin.math.abs(it.dy - best.dy) < size }) out += best
        }
        return out
    }

    /**
     * The pixels of [box] with [hole] covered from [source]: copied (clone), or copied and the
     * difference to the photo at the hole's edge spread smoothly inside it (heal). RGB 0..1,
     * [box]'s size.
     */
    fun fill(rgb: FloatArray, width: Int, height: Int, hole: BooleanArray, box: PixelBox, source: Source, heal: Boolean): FloatArray {
        val bw = box.width
        val bh = box.height
        val out = FloatArray(bw * bh * 3)
        val inside = BooleanArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = (box.y0 + y) * width + box.x0 + x
            val sx = (box.x0 + x + source.dx).coerceIn(0, width - 1)
            val sy = (box.y0 + y + source.dy).coerceIn(0, height - 1)
            val j = sy * width + sx
            val k = y * bw + x
            inside[k] = hole[i]
            for (c in 0..2) out[k * 3 + c] = if (hole[i]) rgb[j * 3 + c] else rgb[i * 3 + c]
        }
        if (!heal) return out
        // The difference (photo − copy) on the hole's edge, spread over the hole: Jacobi steps
        // of Laplace's equation, starting from the edge's mean so few are needed.
        val diff = FloatArray(bw * bh * 3)
        val mean = FloatArray(3)
        var edges = 0
        for (y in 0 until bh) for (x in 0 until bw) {
            val k = y * bw + x
            if (inside[k]) continue
            val sx = (box.x0 + x + source.dx).coerceIn(0, width - 1)
            val sy = (box.y0 + y + source.dy).coerceIn(0, height - 1)
            val j = sy * width + sx
            val i = (box.y0 + y) * width + box.x0 + x
            for (c in 0..2) diff[k * 3 + c] = rgb[i * 3 + c] - rgb[j * 3 + c]
            val edge = (x > 0 && inside[k - 1]) || (x < bw - 1 && inside[k + 1]) || (y > 0 && inside[k - bw]) || (y < bh - 1 && inside[k + bw])
            if (edge) {
                for (c in 0..2) mean[c] += diff[k * 3 + c]
                edges++
            }
        }
        if (edges > 0) for (c in 0..2) mean[c] /= edges
        for (k in inside.indices) if (inside[k]) for (c in 0..2) diff[k * 3 + c] = mean[c]
        val next = diff.copyOf()
        val steps = min(600, max(60, 3 * max(bw, bh)))
        repeat(steps) {
            for (y in 0 until bh) for (x in 0 until bw) {
                val k = y * bw + x
                if (!inside[k]) continue
                for (c in 0..2) {
                    var s = 0f
                    var n = 0
                    if (x > 0) { s += diff[(k - 1) * 3 + c]; n++ }
                    if (x < bw - 1) { s += diff[(k + 1) * 3 + c]; n++ }
                    if (y > 0) { s += diff[(k - bw) * 3 + c]; n++ }
                    if (y < bh - 1) { s += diff[(k + bw) * 3 + c]; n++ }
                    next[k * 3 + c] = s / n
                }
            }
            System.arraycopy(next, 0, diff, 0, diff.size)
        }
        for (k in inside.indices) if (inside[k]) for (c in 0..2) out[k * 3 + c] = (out[k * 3 + c] + diff[k * 3 + c]).coerceIn(0f, 1f)
        return out
    }
}
