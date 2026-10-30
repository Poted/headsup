package app.headsup.core.retouch

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A patch over a photo where something was removed: its place as fractions of the upright
 * picture ([left]..[right], [top]..[bottom]) and the id of its pixels (kept by the app, with an
 * alpha that fades at its edge). [hole] is the id of what was asked to be removed (kept, so the
 * patch can be made again: a raw photo developed differently needs new pixels), [develop] the
 * development it was made for (null for photos that are not raw).
 */
data class RetouchPatch(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val hole: String? = null,
    val develop: String? = null,
)

/** A rectangle of pixels, [x0]..[x1) × [y0]..[y1). */
data class PixelBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
}

/** The pure parts of removing things from a photo: which areas, what around them to show the model, and how the fill meets the photo. */
object Retouch {
    /**
     * The separate areas of [hole] ([width]×[height], true = remove), each as its box; areas
     * closer than [join] pixels count as one (they share what is around them). Smallest first
     * is no help: the order is as found, top to bottom.
     */
    fun areas(hole: BooleanArray, width: Int, height: Int, join: Int = 0): List<PixelBox> {
        val grown = if (join > 0) dilate(hole, width, height, join) else hole
        val label = IntArray(width * height)
        val boxes = ArrayList<PixelBox>()
        val stack = IntArray(width * height)
        for (start in grown.indices) {
            if (!grown[start] || label[start] != 0) continue
            var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
            var top = 0
            stack[top++] = start
            label[start] = boxes.size + 1
            var any = false
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                // The box is of the area itself, not of the joining margin.
                if (hole[i]) {
                    any = true
                    x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                }
                fun visit(j: Int) {
                    if (grown[j] && label[j] == 0) {
                        label[j] = boxes.size + 1
                        stack[top++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < width - 1) visit(i + 1)
                if (y > 0) visit(i - width)
                if (y < height - 1) visit(i + width)
            }
            if (any) boxes += PixelBox(x0, y0, x1 + 1, y1 + 1) else boxes += PixelBox(0, 0, 0, 0)
        }
        return boxes.filter { it.width > 0 && it.height > 0 }
    }

    /**
     * The square shown to the model for an area: the area with as much around it again (the model
     * fills from what it sees), at least [minSide] pixels, kept in the picture (smaller only where
     * the picture is).
     */
    fun context(area: PixelBox, width: Int, height: Int, minSide: Int = 256): PixelBox {
        val side = min(max(width, height), max(minSide, (max(area.width, area.height) * 2.0f).roundToInt()))
        val cx = (area.x0 + area.x1) / 2
        val cy = (area.y0 + area.y1) / 2
        val sw = min(side, width)
        val sh = min(side, height)
        val x0 = (cx - sw / 2).coerceIn(0, width - sw)
        val y0 = (cy - sh / 2).coerceIn(0, height - sh)
        return PixelBox(x0, y0, x0 + sw, y0 + sh)
    }

    /** [mask] grown by [radius] pixels (a square neighbourhood, done in two passes). */
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val rows = BooleanArray(mask.size)
        for (y in 0 until height) {
            var last = Int.MIN_VALUE / 2
            for (x in 0 until width) {
                if (mask[y * width + x]) last = x
                if (x - last <= radius) rows[y * width + x] = true
            }
            last = Int.MAX_VALUE / 2
            for (x in width - 1 downTo 0) {
                if (mask[y * width + x]) last = x
                if (last - x <= radius) rows[y * width + x] = true
            }
        }
        val out = BooleanArray(mask.size)
        for (x in 0 until width) {
            var last = Int.MIN_VALUE / 2
            for (y in 0 until height) {
                if (rows[y * width + x]) last = y
                if (y - last <= radius) out[y * width + x] = true
            }
            last = Int.MAX_VALUE / 2
            for (y in height - 1 downTo 0) {
                if (rows[y * width + x]) last = y
                if (last - y <= radius) out[y * width + x] = true
            }
        }
        return out
    }

    /**
     * How much of the fill shows at each pixel (0..1): all of it inside [hole], fading to nothing
     * over [feather] pixels outside it, so the fill meets the photo without a seam.
     */
    fun alpha(hole: BooleanArray, width: Int, height: Int, feather: Int): FloatArray {
        val out = FloatArray(hole.size) { if (hole[it]) 1f else 0f }
        if (feather <= 0) return out
        // Distance (city block, in two passes) to the hole, for pixels outside it.
        val far = IntArray(hole.size) { if (hole[it]) 0 else Int.MAX_VALUE / 2 }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x > 0) far[i] = min(far[i], far[i - 1] + 1)
            if (y > 0) far[i] = min(far[i], far[i - width] + 1)
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            if (x < width - 1) far[i] = min(far[i], far[i + 1] + 1)
            if (y < height - 1) far[i] = min(far[i], far[i + width] + 1)
        }
        for (i in out.indices) if (!hole[i] && far[i] <= feather) {
            val t = 1f - far[i].toFloat() / (feather + 1)
            out[i] = t * t * (3f - 2f * t)
        }
        return out
    }

    /**
     * The photo's grain around the hole: the spread (0..1 per colour) of what is left after a
     * small blur is taken away, over the ring of pixels within [ring] of the hole. A fill made at a
     * smaller size is smoother than the photo; this much grain is added back to it.
     */
    fun grain(rgb: FloatArray, hole: BooleanArray, width: Int, height: Int, ring: Int): Float {
        val near = dilate(hole, width, height, ring)
        var sum = 0.0
        var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (!near[i] || hole[i]) continue
            // Only where none of the 3×3 is in the hole: the hole's own edge is no grain.
            if (hole[i - 1] || hole[i + 1] || hole[i - width] || hole[i + width]) continue
            for (c in 0 until 3) {
                var mean = 0f
                for (dy in -1..1) for (dx in -1..1) mean += rgb[((y + dy) * width + x + dx) * 3 + c]
                val d = rgb[i * 3 + c] - mean / 9f
                sum += d * d
                n++
            }
        }
        // The residual of a 3×3 mean holds 8/9 of white noise's spread.
        return if (n == 0) 0f else sqrt(sum / n * 9.0 / 8.0).toFloat()
    }
}
