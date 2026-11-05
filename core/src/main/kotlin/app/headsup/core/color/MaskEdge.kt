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
