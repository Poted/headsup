package app.headsup.core.color

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One brush stroke of a mask: the finger's path ([points] as x, y pairs; x a fraction of the
 * frame's width, y of its height), the brush radius (of the height), how soft its edge is (0 hard,
 * 1 fading from the centre), how much one stroke adds ([flow]), and whether it paints or erases.
 */
data class BrushStroke(
    val points: List<Float>,
    val radius: Float,
    val softness: Float = 0.5f,
    val flow: Float = 1f,
    val erase: Boolean = false,
)

/** Brush masks: strokes drawn into a coverage map that the shaders sample. */
object BrushMask {
    /** Where a brush mask's strokes are painted: the frame as it is. */
    val BASE = MaskKey(0L, 0.5f, 0.5f, 0f, 1f, 1f)

    /**
     * The strokes as coverage, 0..1, on a [width]×[height] grid over the frame. Within a stroke the
     * brush prints do not build up (each pixel takes the strongest); strokes then add (or erase)
     * by their flow, so going over a place again paints it more.
     */
    fun rasterize(strokes: List<BrushStroke>, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        if (strokes.isEmpty() || width <= 0 || height <= 0) return out
        val layer = FloatArray(width * height)
        for (stroke in strokes) {
            val r = (stroke.radius * height).coerceAtLeast(0.5f)
            val pts = stroke.points.chunked(2).filter { it.size == 2 }.map { it[0] * width to it[1] * height }
            if (pts.isEmpty()) continue
            // The part of the grid the stroke can reach.
            val x0 = max(0, floor(pts.minOf { it.first } - r).toInt())
            val x1 = min(width - 1, ceil(pts.maxOf { it.first } + r).toInt())
            val y0 = max(0, floor(pts.minOf { it.second } - r).toInt())
            val y1 = min(height - 1, ceil(pts.maxOf { it.second } + r).toInt())
            if (x0 > x1 || y0 > y1) continue
            for (y in y0..y1) java.util.Arrays.fill(layer, y * width + x0, y * width + x1 + 1, 0f)
            val hard = (1f - stroke.softness).coerceIn(0f, 0.99f)
            fun stamp(cx: Float, cy: Float) {
                val sx0 = max(x0, floor(cx - r).toInt())
                val sx1 = min(x1, ceil(cx + r).toInt())
                val sy0 = max(y0, floor(cy - r).toInt())
                val sy1 = min(y1, ceil(cy + r).toInt())
                for (y in sy0..sy1) {
                    val dy = y + 0.5f - cy
                    for (x in sx0..sx1) {
                        val d = hypot(x + 0.5f - cx, dy) / r
                        if (d >= 1f) continue
                        val v = 1f - smoothstep(hard, 1f, d)
                        val i = y * width + x
                        if (v > layer[i]) layer[i] = v
                    }
                }
            }
            // Prints close enough together that the edge stays smooth.
            val spacing = max(0.5f, r * 0.2f)
            stamp(pts[0].first, pts[0].second)
            for (k in 1 until pts.size) {
                val (ax, ay) = pts[k - 1]
                val (bx, by) = pts[k]
                val n = ceil(hypot(bx - ax, by - ay) / spacing).toInt()
                for (j in 1..n) {
                    val t = j.toFloat() / n
                    stamp(ax + (bx - ax) * t, ay + (by - ay) * t)
                }
            }
            val flow = stroke.flow.coerceIn(0f, 1f)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val i = y * width + x
                    val v = layer[i] * flow
                    if (v == 0f) continue
                    out[i] = if (stroke.erase) out[i] * (1f - v) else out[i] + (1f - out[i]) * v
                }
            }
        }
        return out
    }

    /**
     * A point of the frame ([x], [y]) with the mask placed at [now], in the strokes' own frame
     * ([BASE]): the place to paint so the stroke shows under the finger. [aspect] is the frame's
     * width over its height. The shaders do the same, per pixel.
     */
    fun unmove(x: Float, y: Float, now: ColourMask, aspect: Float): Pair<Float, Float> {
        val delta = Math.toRadians((now.angleDegrees - BASE.angleDegrees).toDouble())
        val c = cos(delta).toFloat()
        val s = sin(delta).toFloat()
        val dx = (x - now.centerX) * aspect
        val dy = y - now.centerY
        val k = BASE.sizeX / now.sizeX.coerceAtLeast(0.001f)
        val ux = (dx * c + dy * s) * k
        val uy = (-dx * s + dy * c) * k
        return BASE.centerX + ux / aspect to BASE.centerY + uy
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
