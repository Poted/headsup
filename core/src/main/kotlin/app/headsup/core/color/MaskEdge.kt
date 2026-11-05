package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.max

/**
 * Where a found mask's edge lies (Select, Sky, AI tracking): pulled in, away from what is around
 * the object ([edge] < 0), or pushed out to take in its rim ([edge] > 0). The edge moves the same
 * distance everywhere (up to a few percent of the picture), so it keeps its shape: its corners and
 * the turns that follow the picture stay, never rounded into smooth arcs.
 */
object MaskEdge {
    /**
     * At most this much of the picture's long side is the edge moved by (at |edge| = 1): a few
     * pixels, a rim or a halo; more would round off details of the outline smaller than the move.
     */
    const val REACH = 0.01f

    /** [map] (0..1, [width]×[height]) with its edge at [edge] (-1..1), as a clean edge. */
    fun shape(map: FloatArray, width: Int, height: Int, edge: Float): FloatArray {
        val e = edge.coerceIn(-1f, 1f)
        if (width <= 0 || height <= 0) return map.copyOf()
        if (abs(e) < 1e-4f) {
            // Where it is: only cut cleanly around one half.
            return FloatArray(map.size) { i ->
                val t = ((map[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)
            }
        }
        val shift = e * REACH * max(width, height)
        val d = signedDistance(map, width, height)
        // Across about a pixel and a half: a clean edge, laid onto the picture's at full size later.
        return FloatArray(d.size) { i ->
            val t = ((d[i] + shift + 0.75f) / 1.5f).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
    }

    /**
     * [shape] following the picture ([rgb], RGB per pixel of the same grid): Fuller takes in only
     * pixels by the edge that look like the inside, Tighter lets go only of those that look like the
     * outside, both within the same few pixels; so the outline keeps the picture's shape instead of
     * filling its notches (or cutting its tips) with smooth arcs. Where both sides look alike it is
     * moved as [shape] moves it.
     */
    fun shapeGuided(map: FloatArray, width: Int, height: Int, edge: Float, rgb: FloatArray): FloatArray {
        val e = edge.coerceIn(-1f, 1f)
        if (abs(e) < 1e-4f || rgb.size != map.size * 3) return shape(map, width, height, e)
        val geo = shape(map, width, height, e)
        val reach = max(1f, abs(e) * REACH * max(width, height))
        val sd = signedDistance(map, width, height)
        val n = map.size
        // The colours just inside and just outside, past the reach, nearby.
        val inRing = FloatArray(n) { if (sd[it] in (reach + 1f)..(reach + 6f)) 1f else 0f }
        val outRing = FloatArray(n) { if (-sd[it] in (reach + 1f)..(reach + 6f)) 1f else 0f }
        val win = (3 * reach + 8f).toInt()
        val inN = MaskRefine.box(inRing, width, height, win)
        val outN = MaskRefine.box(outRing, width, height, win)
        val inMean = Array(3) { c -> MaskRefine.box(FloatArray(n) { rgb[it * 3 + c] * inRing[it] }, width, height, win) }
        val outMean = Array(3) { c -> MaskRefine.box(FloatArray(n) { rgb[it * 3 + c] * outRing[it] }, width, height, win) }
        val out = map.copyOf()
        for (i in 0 until n) {
            val d = sd[i]
            // Only the pixels the move can reach: outside for Fuller, inside for Tighter.
            val within = if (e > 0f) d < 0.5f && -d <= reach + 0.75f else d > -0.5f && d <= reach + 0.75f
            if (!within) { out[i] = map[i]; continue }
            var conf = 0f
            var like = 0.5f
            if (inN[i] > 1e-4f && outN[i] > 1e-4f) {
                var dIn = 0f
                var dOut = 0f
                var contrast = 0f
                for (c in 0..2) {
                    val a = inMean[c][i] / inN[i]
                    val b = outMean[c][i] / outN[i]
                    val col = rgb[i * 3 + c]
                    dIn += (col - a) * (col - a)
                    dOut += (col - b) * (col - b)
                    contrast += (a - b) * (a - b)
                }
                like = kotlin.math.sqrt(dOut) / (kotlin.math.sqrt(dIn) + kotlin.math.sqrt(dOut) + 1e-5f)
                conf = smooth(0.03f, 0.1f, kotlin.math.sqrt(contrast))
            }
            out[i] = if (e > 0f) {
                // Taken in when it looks (somewhat) like the inside: a rim, a halo.
                val take = smooth(0.35f, 0.55f, like)
                maxOf(map[i], take * conf + geo[i] * (1f - conf))
            } else {
                // Let go when it looks like the outside.
                val keep = smooth(0.45f, 0.65f, like)
                minOf(map[i], keep * conf + geo[i] * (1f - conf))
            }
        }
        return out
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How far each pixel is from the mask's edge (where it crosses one half), in pixels: positive
     * inside, negative outside. Two passes of a 3-4 chamfer, close to the true distance.
     */
    internal fun signedDistance(map: FloatArray, width: Int, height: Int): FloatArray {
        val inside = BooleanArray(map.size) { map[it] >= 0.5f }
        val toOut = chamfer(inside, width, height, towards = false)
        val toIn = chamfer(inside, width, height, towards = true)
        return FloatArray(map.size) { i -> if (inside[i]) toOut[i] - 0.5f else -(toIn[i] - 0.5f) }
    }

    /** Distance (pixels) from each pixel to the nearest one that is [towards] inside. */
    private fun chamfer(inside: BooleanArray, w: Int, h: Int, towards: Boolean): FloatArray {
        val far = 1e9f
        val d = FloatArray(w * h) { if (inside[it] == towards) 0f else far }
        val a = 1f
        val b = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (v == 0f) continue
            if (x > 0) v = minOf(v, d[i - 1] + a)
            if (y > 0) {
                v = minOf(v, d[i - w] + a)
                if (x > 0) v = minOf(v, d[i - w - 1] + b)
                if (x < w - 1) v = minOf(v, d[i - w + 1] + b)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (v == 0f) continue
            if (x < w - 1) v = minOf(v, d[i + 1] + a)
            if (y < h - 1) {
                v = minOf(v, d[i + w] + a)
                if (x < w - 1) v = minOf(v, d[i + w + 1] + b)
                if (x > 0) v = minOf(v, d[i + w - 1] + b)
            }
            d[i] = v
        }
        return d
    }
}
