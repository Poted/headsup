package app.headsup.core.color

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One brush stroke of a mask: the finger's path ([points] as x, y pairs; x a fraction of the
 * frame's width, y of its height), the brush radius (of the height), how much one stroke adds
 * ([flow]) and whether it paints or erases. A stroke with a [reference] finds edges (Lightroom's
 * Auto Mask): each print of the brush reaches only colours close to the one under its centre, in
 * the picture the stroke was painted on, within [tolerance].
 */
data class BrushStroke(
    val points: List<Float>,
    val radius: Float,
    val flow: Float = 1f,
    val erase: Boolean = false,
    val reference: String? = null,
    val tolerance: Float = 0.15f,
)

/**
 * The picture an edge-finding stroke was painted on, small ([width]×[height], RGB 0..1 per
 * pixel), and where the mask was then ([placement]), so the strokes (kept in the mask's own frame)
 * meet the picture where they were drawn.
 */
class BrushReference(val width: Int, val height: Int, val rgb: FloatArray, val placement: MaskKey) {
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)
}

/** Brush masks: strokes drawn into a coverage map that the shaders sample. */
object BrushMask {
    /** Where a brush mask's strokes are painted: the frame as it is. */
    val BASE = MaskKey(0L, 0.5f, 0.5f, 0f, 1f, 1f)

    /**
     * The strokes as coverage, 0..1, on a [width]×[height] grid over the frame. Within a stroke the
     * brush prints do not build up (each pixel takes the strongest); strokes then add (or erase)
     * by their flow, so going over a place again paints it more. [softness] is the mask's edge:
     * 0 hard, 1 fading from each print's centre. [start] is coverage already drawn (by earlier
     * strokes) to paint on.
     */
    fun rasterize(
        strokes: List<BrushStroke>,
        width: Int,
        height: Int,
        softness: Float = 0.5f,
        references: Map<String, BrushReference> = emptyMap(),
        start: FloatArray? = null,
    ): FloatArray {
        val out = start?.takeIf { it.size == width * height }?.copyOf() ?: FloatArray(width * height)
        if (strokes.isEmpty() || width <= 0 || height <= 0) return out
        val layer = FloatArray(width * height)
        val hard = (1f - softness).coerceIn(0f, 0.99f)
        val gridAspect = width.toFloat() / height
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
            val ref = stroke.reference?.let { references[it] }
            // Edge finding: the picture's colour at each grid pixel, looked up once per stroke.
            val colours = ref?.let { sampleArea(it, width, height, gridAspect, x0, x1, y0, y1) }
            val spanW = x1 - x0 + 1
            val tol = stroke.tolerance.coerceIn(0.01f, 1f)
            fun stamp(cx: Float, cy: Float) {
                val sx0 = max(x0, floor(cx - r).toInt())
                val sx1 = min(x1, ceil(cx + r).toInt())
                val sy0 = max(y0, floor(cy - r).toInt())
                val sy1 = min(y1, ceil(cy + r).toInt())
                var cr = 0f
                var cg = 0f
                var cb = 0f
                if (colours != null) {
                    // The colour under the centre, averaged over a few pixels against noise.
                    var n = 0
                    val px = cx.toInt().coerceIn(x0, x1)
                    val py = cy.toInt().coerceIn(y0, y1)
                    for (yy in max(y0, py - 1)..min(y1, py + 1)) for (xx in max(x0, px - 1)..min(x1, px + 1)) {
                        val k = ((yy - y0) * spanW + (xx - x0)) * 3
                        cr += colours[k]; cg += colours[k + 1]; cb += colours[k + 2]; n++
                    }
                    cr /= n; cg /= n; cb /= n
                }
                for (y in sy0..sy1) {
                    val dy = y + 0.5f - cy
                    for (x in sx0..sx1) {
                        val d = hypot(x + 0.5f - cx, dy) / r
                        if (d >= 1f) continue
                        var v = 1f - smoothstep(hard, 1f, d)
                        if (colours != null) {
                            val k = ((y - y0) * spanW + (x - x0)) * 3
                            val dr = colours[k] - cr
                            val dg = colours[k + 1] - cg
                            val db = colours[k + 2] - cb
                            // Green weighs most, as the eye sees.
                            val diff = sqrt(0.3f * dr * dr + 0.5f * dg * dg + 0.2f * db * db)
                            v *= 1f - smoothstep(tol * 0.6f, tol * 1.4f, diff)
                        }
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

    /** [ref]'s colours at the grid pixels x0..x1, y0..y1 (the grid over the mask's own frame), RGB in a row. */
    private fun sampleArea(ref: BrushReference, width: Int, height: Int, gridAspect: Float, x0: Int, x1: Int, y0: Int, y1: Int): FloatArray {
        val out = FloatArray((x1 - x0 + 1) * (y1 - y0 + 1) * 3)
        var k = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val (fx, fy) = place((x + 0.5f) / width, (y + 0.5f) / height, ref.placement, gridAspect)
                val px = (fx * ref.width).toInt().coerceIn(0, ref.width - 1)
                val py = (fy * ref.height).toInt().coerceIn(0, ref.height - 1)
                val i = (py * ref.width + px) * 3
                out[k++] = ref.rgb[i]
                out[k++] = ref.rgb[i + 1]
                out[k++] = ref.rgb[i + 2]
            }
        }
        return out
    }

    /**
     * A point of the frame ([x], [y]) with the mask placed at [now], in the strokes' own frame
     * ([BASE]): the place to paint so the stroke shows under the finger. [aspect] is the frame's
     * width over its height. The shaders do the same, per pixel.
     */
    fun unmove(x: Float, y: Float, now: ColourMask, aspect: Float): Pair<Float, Float> =
        unmove(x, y, MaskKey.of(now, 0L), aspect)

    fun unmove(x: Float, y: Float, now: MaskKey, aspect: Float): Pair<Float, Float> {
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

    /** The reverse of [unmove]: a point of the strokes' own frame where it shows with the mask at [now]. */
    fun place(x: Float, y: Float, now: MaskKey, aspect: Float): Pair<Float, Float> {
        val delta = Math.toRadians((now.angleDegrees - BASE.angleDegrees).toDouble())
        val c = cos(delta).toFloat()
        val s = sin(delta).toFloat()
        val k = now.sizeX / BASE.sizeX
        val ux = (x - BASE.centerX) * aspect * k
        val uy = (y - BASE.centerY) * k
        return now.centerX + (ux * c - uy * s) / aspect to now.centerY + (ux * s + uy * c)
    }

    /**
     * Where the painting is in the frame with the mask at [now] (the middle of the painted strokes),
     * so tracking follows what was painted rather than the middle of the frame. Null with nothing painted.
     */
    fun centreOf(strokes: List<BrushStroke>, now: MaskKey, aspect: Float): Pair<Float, Float>? {
        val pts = strokes.filter { !it.erase }.flatMap { it.points.chunked(2).filter { p -> p.size == 2 } }
        if (pts.isEmpty()) return null
        return place(pts.sumOf { it[0].toDouble() }.toFloat() / pts.size, pts.sumOf { it[1].toDouble() }.toFloat() / pts.size, now, aspect)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
