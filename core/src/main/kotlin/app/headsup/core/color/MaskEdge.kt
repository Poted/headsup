package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a found mask's edge lies (Select, Sky, AI tracking): pulled in, away from what is around
 * the object ([edge] < 0), or pushed out to take in its rim ([edge] > 0). The map is softened over
 * a width that grows with |edge| and cut where it is less (or more) sure, so the edge moves by up
 * to a few percent of the picture while keeping its shape.
 */
object MaskEdge {
    /** At most this much of the picture's long side is the edge moved by (at |edge| = 1). */
    const val REACH = 0.025f

    /** [map] (0..1, [width]×[height]) with its edge at [edge] (-1..1), as a clean edge. */
    fun shape(map: FloatArray, width: Int, height: Int, edge: Float): FloatArray {
        val e = edge.coerceIn(-1f, 1f)
        val r = (abs(e) * REACH * max(width, height)).roundToInt()
        val soft = if (r >= 1) MaskRefine.box(map, width, height, r) else map
        // Where the softened map crosses this, the edge lies: lower takes more, higher less.
        val centre = 0.5f - 0.38f * e
        val lo = (centre - 0.2f).coerceAtLeast(0.01f)
        val hi = (centre + 0.2f).coerceAtMost(0.99f)
        return FloatArray(soft.size) { i ->
            val t = ((soft[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
    }
}
