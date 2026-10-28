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
    /**
     * Not painted but a loose outline around an object (closed from its end back to its start):
     * the object inside is found from the colours of the [reference] picture ([BrushMask.select]).
     */
    val lasso: Boolean = false,
    /**
     * Not painted but an object found in the picture (a tap on it): [reference] holds how sure the
     * finder is, per pixel, that it is the object (grey, 0..1).
     */
    val segment: Boolean = false,
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
            // The part of the grid the stroke can reach (an outline: with some background around it).
            // A line splits the whole picture, and a found object may be anywhere in it.
            val pad = if (stroke.segment || (stroke.lasso && !isLoop(pts))) {
                (width + height).toFloat()
            } else if (stroke.lasso) {
                max(8f, 0.12f * max(pts.maxOf { it.first } - pts.minOf { it.first }, pts.maxOf { it.second } - pts.minOf { it.second }))
            } else {
                r
            }
            val x0 = max(0, floor(pts.minOf { it.first } - pad).toInt())
            val x1 = min(width - 1, ceil(pts.maxOf { it.first } + pad).toInt())
            val y0 = max(0, floor(pts.minOf { it.second } - pad).toInt())
            val y1 = min(height - 1, ceil(pts.maxOf { it.second } + pad).toInt())
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
            if (stroke.segment) {
                // The finder's certainty, sharpened a little around its middle: a clean edge.
                if (colours != null) {
                    for (y in y0..y1) for (x in x0..x1) {
                        val c = colours[((y - y0) * spanW + (x - x0)) * 3]
                        layer[y * width + x] = smoothstep(0.3f, 0.7f, c)
                    }
                }
            } else if (stroke.lasso) {
                val found = select(pts, colours, x0, x1, y0, y1, softness)
                for (y in y0..y1) for (x in x0..x1) layer[y * width + x] = found[(y - y0) * spanW + (x - x0)]
            } else {
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
     * True when an outline comes back near where it started (a loop around something), false for
     * a line across the picture.
     */
    fun isLoop(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 3) return false
        val extent = max(points.maxOf { it.first } - points.minOf { it.first }, points.maxOf { it.second } - points.minOf { it.second })
        val gap = hypot(points.last().first - points.first().first, points.last().second - points.first().second)
        return gap < 0.35f * extent
    }

    /**
     * What a drawn [outline] (grid pixels) selects over the area x0..x1, y0..y1, as coverage 0..1 in
     * rows of that area. A loop selects the object inside it (as an oval mask does): what is outside
     * is background, inside is decided by colour. A line splits the picture in two (as a line mask
     * does): it is carried on to the edges, and the side above it (left of it, for a line drawn up
     * or down) is selected; only near the line is each pixel decided by colour, so the split follows
     * edges such as a tree against the sky. Deciding by colour is GrabCut without its graph cut:
     * colour models of both sides, the undecided pixels given to the likelier one a few times over,
     * smoothed over their neighbours. Then specks are dropped, small holes filled, and the edge
     * softened by [softness]. Without [colours] (no picture) the plain inside or side.
     */
    fun select(outline: List<Pair<Float, Float>>, colours: FloatArray?, x0: Int, x1: Int, y0: Int, y1: Int, softness: Float): FloatArray {
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val n = w * h
        val pts = outline.map { (x, y) -> x - x0 to y - y0 }
        // Pixels sure to be in, sure to be out, and the ones colour decides.
        val sureIn = BooleanArray(n)
        val sureOut = BooleanArray(n)
        val side = BooleanArray(n)
        if (isLoop(pts)) {
            fillPolygon(pts, w, h, side)
            for (i in 0 until n) sureOut[i] = !side[i]
        } else {
            splitByLine(pts, w, h, side)
            // Only near the line is anything in doubt.
            val near = nearLine(pts, w, h, max(6, (0.06f * max(w, h)).toInt()))
            for (i in 0 until n) {
                sureIn[i] = side[i] && !near[i]
                sureOut[i] = !side[i] && !near[i]
            }
        }
        val fg = BooleanArray(n) { side[it] }
        if (colours != null && side.any { it } && sureOut.any { it }) {
            // Colours in 8 steps per channel: 512 bins, filled softly into the neighbouring bins.
            fun bin(i: Int): Int {
                val r = (colours[i * 3] * 7.999f).toInt().coerceIn(0, 7)
                val g = (colours[i * 3 + 1] * 7.999f).toInt().coerceIn(0, 7)
                val b = (colours[i * 3 + 2] * 7.999f).toInt().coerceIn(0, 7)
                return (r * 8 + g) * 8 + b
            }
            val bins = IntArray(n) { bin(it) }
            fun model(take: (Int) -> Boolean): FloatArray {
                val hist = FloatArray(512)
                var total = 0f
                for (i in 0 until n) if (take(i)) { hist[bins[i]] += 1f; total += 1f }
                val smooth = FloatArray(512)
                for (r in 0..7) for (g in 0..7) for (b in 0..7) {
                    var acc = 0f
                    for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
                        val rr = r + dr
                        val gg = g + dg
                        val bb = b + db
                        if (rr !in 0..7 || gg !in 0..7 || bb !in 0..7) continue
                        acc += hist[(rr * 8 + gg) * 8 + bb] * if (dr == 0 && dg == 0 && db == 0) 1f else 0.15f
                    }
                    smooth[(r * 8 + g) * 8 + b] = (acc + 0.5f) / (total + 256f)
                }
                return smooth
            }
            val background = model { sureOut[it] }
            val p = FloatArray(n)
            repeat(5) {
                val selected = model { fg[it] }
                for (i in 0 until n) {
                    p[i] = when {
                        sureIn[i] -> 1f
                        sureOut[i] -> 0f
                        else -> selected[bins[i]] / (selected[bins[i]] + background[bins[i]])
                    }
                }
                // Neighbours agree: the share is averaged over a small square, twice.
                blur(p, w, h, 2)
                blur(p, w, h, 2)
                for (i in 0 until n) fg[i] = sureIn[i] || (!sureOut[i] && p[i] > 0.5f)
            }
            keepLarge(fg, w, h, side.count { it })
        }
        val out = FloatArray(n) { if (fg[it]) 1f else 0f }
        // A slightly soft edge always (no stair steps), more with the mask's softness.
        blur(out, w, h, 1 + (softness * 6f).toInt())
        return out
    }

    /** Even-odd fill of the closed polygon [pts], row by row. */
    private fun fillPolygon(pts: List<Pair<Float, Float>>, w: Int, h: Int, into: BooleanArray) {
        val crossings = ArrayList<Float>()
        for (y in 0 until h) {
            val cy = y + 0.5f
            crossings.clear()
            for (i in pts.indices) {
                val (ax, ay) = pts[i]
                val (bx, by) = pts[(i + 1) % pts.size]
                if ((ay <= cy && by > cy) || (by <= cy && ay > cy)) crossings += ax + (cy - ay) / (by - ay) * (bx - ax)
            }
            crossings.sort()
            var k = 0
            while (k + 1 < crossings.size) {
                val from = max(0, ceil(crossings[k] - 0.5f).toInt())
                val to = min(w - 1, floor(crossings[k + 1] - 0.5f).toInt())
                for (x in from..to) into[y * w + x] = true
                k += 2
            }
        }
    }

    /** The line [pts] carried on past the picture's edges along its end directions. */
    private fun extended(pts: List<Pair<Float, Float>>, w: Int, h: Int): List<Pair<Float, Float>> {
        val far = (w + h).toFloat() * 2f
        fun direction(from: Pair<Float, Float>, towards: List<Pair<Float, Float>>): Pair<Float, Float> {
            // Over a stretch of the line, not its last wobble.
            val back = towards.firstOrNull { hypot(it.first - from.first, it.second - from.second) > 0.05f * max(w, h) } ?: towards.last()
            val dx = from.first - back.first
            val dy = from.second - back.second
            val len = hypot(dx, dy).coerceAtLeast(1e-3f)
            return dx / len to dy / len
        }
        val (ex, ey) = direction(pts.last(), pts.reversed().drop(1))
        val (sx, sy) = direction(pts.first(), pts.drop(1))
        return listOf(pts.first().first + sx * far to pts.first().second + sy * far) + pts +
            (pts.last().first + ex * far to pts.last().second + ey * far)
    }

    /** Marks the side of the line [pts] (carried to the edges) above it, or left of it for a line drawn up or down. */
    private fun splitByLine(pts: List<Pair<Float, Float>>, w: Int, h: Int, into: BooleanArray) {
        val line = extended(pts, w, h)
        // The side is a polygon: the line, then round the picture's edge back to its start.
        val horizontal = kotlin.math.abs(pts.last().first - pts.first().first) >= kotlin.math.abs(pts.last().second - pts.first().second)
        val far = (w + h).toFloat() * 2f
        val a = line.first()
        val b = line.last()
        val closing = if (horizontal) {
            // Up and back over the top.
            listOf(b.first to -far, a.first to -far)
        } else {
            listOf(-far to b.second, -far to a.second)
        }
        fillPolygon(line + closing, w, h, into)
    }

    /** Pixels within [band] of the line [pts] (carried to the edges). */
    private fun nearLine(pts: List<Pair<Float, Float>>, w: Int, h: Int, band: Int): BooleanArray {
        val near = BooleanArray(w * h)
        val line = extended(pts, w, h)
        val b2 = band.toFloat() * band
        for (k in 1 until line.size) {
            val (ax, ay) = line[k - 1]
            val (bx, by) = line[k]
            val minX = max(0, floor(min(ax, bx) - band).toInt())
            val maxX = min(w - 1, ceil(max(ax, bx) + band).toInt())
            val minY = max(0, floor(min(ay, by) - band).toInt())
            val maxY = min(h - 1, ceil(max(ay, by) + band).toInt())
            if (minX > maxX || minY > maxY) continue
            val dx = bx - ax
            val dy = by - ay
            val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
            for (y in minY..maxY) for (x in minX..maxX) {
                val i = y * w + x
                if (near[i]) continue
                val px = x + 0.5f - ax
                val py = y + 0.5f - ay
                val t = ((px * dx + py * dy) / len2).coerceIn(0f, 1f)
                val qx = px - t * dx
                val qy = py - t * dy
                if (qx * qx + qy * qy <= b2) near[i] = true
            }
        }
        return near
    }

    /** Box blur of radius [r] in place, rows then columns. */
    private fun blur(v: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val tmp = FloatArray(max(w, h))
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += v[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = acc / (2 * r + 1)
                acc += v[y * w + (x + r + 1).coerceIn(0, w - 1)] - v[y * w + (x - r).coerceIn(0, w - 1)]
            }
            for (x in 0 until w) v[y * w + x] = tmp[x]
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += v[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = acc / (2 * r + 1)
                acc += v[(y + r + 1).coerceIn(0, h - 1) * w + x] - v[(y - r).coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) v[y * w + x] = tmp[y]
        }
    }

    /** Drops pieces of the object smaller than 2 % of the outline's inside, and fills holes smaller than 1 %. */
    private fun keepLarge(fg: BooleanArray, w: Int, h: Int, insideArea: Int) {
        val label = IntArray(fg.size) { -1 }
        val stack = IntArray(fg.size)
        for (target in listOf(true, false)) {
            label.fill(-1)
            for (start in fg.indices) {
                if (label[start] != -1 || fg[start] != target) continue
                var top = 0
                stack[top++] = start
                label[start] = start
                val members = ArrayList<Int>()
                var touchesBorder = false
                while (top > 0) {
                    val i = stack[--top]
                    members += i
                    val x = i % w
                    val y = i / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                    for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                        if (j >= 0 && label[j] == -1 && fg[j] == target) { label[j] = start; stack[top++] = j }
                    }
                }
                val small = if (target) members.size < insideArea * 0.02f else !touchesBorder && members.size < insideArea * 0.01f
                if (small) for (i in members) fg[i] = !target
            }
        }
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
        // Stretched along the mask's own axes (a surface seen from a new angle).
        val ux = (dx * c + dy * s) * BASE.sizeX / now.sizeX.coerceAtLeast(0.001f)
        val uy = (-dx * s + dy * c) * BASE.sizeY / now.sizeY.coerceAtLeast(0.001f)
        return BASE.centerX + ux / aspect to BASE.centerY + uy
    }

    /** The reverse of [unmove]: a point of the strokes' own frame where it shows with the mask at [now]. */
    fun place(x: Float, y: Float, now: MaskKey, aspect: Float): Pair<Float, Float> {
        val delta = Math.toRadians((now.angleDegrees - BASE.angleDegrees).toDouble())
        val c = cos(delta).toFloat()
        val s = sin(delta).toFloat()
        val ux = (x - BASE.centerX) * aspect * now.sizeX / BASE.sizeX
        val uy = (y - BASE.centerY) * now.sizeY / BASE.sizeY
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

    /**
     * Half the width and height (fractions of the frame) of what is painted, with the mask at [now]:
     * the area tracking follows. Null with nothing painted.
     */
    fun extentOf(strokes: List<BrushStroke>, now: MaskKey, aspect: Float): Pair<Float, Float>? {
        val painted = strokes.filter { !it.erase }
        val pts = painted.flatMap { it.points.chunked(2).filter { p -> p.size == 2 } }.map { place(it[0], it[1], now, aspect) }
        if (pts.isEmpty()) return null
        val r = painted.filter { !it.lasso }.maxOfOrNull { it.radius * now.sizeX } ?: 0f
        return ((pts.maxOf { it.first } - pts.minOf { it.first }) / 2f + r / aspect) to ((pts.maxOf { it.second } - pts.minOf { it.second }) / 2f + r)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
