package app.headsup.core.color

import kotlin.math.max

/**
 * A wide, smooth transition around a selected or painted mask, so its change fades into the
 * picture instead of stopping at the outline ([Feather] blurs only a narrow band at the edge).
 * [ColourMask.blend] sets how wide the transition is (up to [REACH] of the picture's long side),
 * [ColourMask.blendPosition] where it lies: +1 all outside the outline (what was selected keeps the
 * full change, which fades out around it), -1 all inside (nothing outside changes), 0 across it.
 */
object MaskBlend {
    /** The widest transition (blend = 1), of the picture's long side. */
    const val REACH = 0.35f

    /** [map] (0..1, [width]×[height]) with its outline turned into a transition [blend] (0..1) wide. */
    fun apply(map: FloatArray, width: Int, height: Int, blend: Float, position: Float): FloatArray {
        val b = blend.coerceIn(0f, 1f)
        if (b <= 0f || width <= 0 || height <= 0) return map
        val reach = max(b * REACH * max(width, height), 1.5f)
        val p = position.coerceIn(-1f, 1f)
        // Signed distance from the outline: positive inside. The band runs from lo (all out) to hi.
        val d = MaskEdge.signedDistance(map, width, height)
        val lo = -reach * (1f + p) / 2f
        val hi = lo + reach
        return FloatArray(map.size) { i ->
            val t = ((d[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
    }
}
