package app.headsup.core.photo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Photos of one scene at different exposures (a bracket, AEB: e.g. a drone's −2, 0, +2 EV) made
 * into one with the detail of each: exposure fusion (Mertens, Kautz and Van Reeth), each pixel
 * taken most from the photos where it is well exposed, has contrast and colour, blended through
 * Laplacian pyramids so there are no seams or halos. The photos are first lined up on the one in
 * the middle of the bracket (median threshold bitmaps, which do not care about exposure), as a
 * hovering drone or a hand moves a little between shots.
 *
 * Pictures are ARGB pixels, [width]×[height], all the same size.
 */
object ExposureFusion {
    /** How strongly a pixel's place in the middle of the range counts (σ of the bell around 0.5). */
    private const val SIGMA = 0.2f

    /** Smallest pyramid level side. */
    private const val MIN_SIDE = 8

    /**
     * The bracket [pictures] fused into one picture. [onProgress] gets 0..1. Pictures that are
     * shifted against each other should be [align]ed first.
     */
    fun fuse(pictures: List<IntArray>, width: Int, height: Int, onProgress: (Float) -> Unit = {}): IntArray {
        require(pictures.isNotEmpty())
        if (pictures.size == 1) return pictures[0].copyOf()
        val n = width * height
        // The weights' sum first, so each picture's share is known when it is blended in.
        val sum = FloatArray(n)
        for (p in pictures) {
            val w = weights(p, width, height)
            for (i in 0 until n) sum[i] += w[i]
        }
        val sizes = levelSizes(width, height)
        val result = Array(3) { sizes.map { (w, h) -> FloatArray(w * h) } }
        pictures.forEachIndexed { k, p ->
            val w = weights(p, width, height)
            for (i in 0 until n) w[i] = if (sum[i] > 1e-12f) w[i] / sum[i] else 1f / pictures.size
            val weightPyramid = gaussian(w, sizes)
            for (c in 0..2) {
                val shift = 16 - c * 8
                val channel = FloatArray(n) { i -> ((p[i] shr shift) and 0xFF) / 255f }
                val lap = laplacian(channel, sizes)
                for (l in sizes.indices) {
                    val r = result[c][l]
                    val a = lap[l]
                    val g = weightPyramid[l]
                    for (i in r.indices) r[i] += a[i] * g[i]
                }
            }
            onProgress((k + 1f) / pictures.size)
        }
        val channels = Array(3) { c -> collapse(result[c], sizes) }
        val (rc, gc, bc) = channels
        val out = IntArray(n)
        for (i in 0 until n) {
            val r = (rc[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (gc[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (bc[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Each pixel's weight: contrast (Laplacian), colourfulness (spread of r, g, b) and how well exposed. */
    fun weights(p: IntArray, width: Int, height: Int): FloatArray {
        val n = width * height
        val grey = FloatArray(n)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val c = p[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            grey[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val m = (r + g + b) / 3f
            val sat = sqrt(((r - m) * (r - m) + (g - m) * (g - m) + (b - m) * (b - m)) / 3f)
            val e = well(r) * well(g) * well(b)
            out[i] = (sat + 0.02f) * e
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val l = if (x > 0) grey[i - 1] else grey[i]
            val rr = if (x < width - 1) grey[i + 1] else grey[i]
            val u = if (y > 0) grey[i - width] else grey[i]
            val d = if (y < height - 1) grey[i + width] else grey[i]
            val lap = abs(l + rr + u + d - 4f * grey[i])
            out[i] = out[i] * (lap + 0.02f) + 1e-12f
        }
        return out
    }

    private fun well(v: Float): Float { val d = v - 0.5f; return exp(-d * d / (2f * SIGMA * SIGMA)) }

    private fun levelSizes(width: Int, height: Int): List<Pair<Int, Int>> {
        val sizes = ArrayList<Pair<Int, Int>>()
        var w = width
        var h = height
        sizes += w to h
        while (min(w, h) / 2 >= MIN_SIDE) {
            w = (w + 1) / 2
            h = (h + 1) / 2
            sizes += w to h
        }
        return sizes
    }

    /** [src] ([w]×[h]) blurred (binomial 1 4 6 4 1) and halved. */
    private fun down(src: FloatArray, w: Int, h: Int, ow: Int, oh: Int): FloatArray {
        val tmp = FloatArray(ow * h)
        for (y in 0 until h) for (x in 0 until ow) {
            val cx = x * 2
            var s = 0f
            s += src[y * w + clampI(cx - 2, w)] * 1f
            s += src[y * w + clampI(cx - 1, w)] * 4f
            s += src[y * w + clampI(cx, w)] * 6f
            s += src[y * w + clampI(cx + 1, w)] * 4f
            s += src[y * w + clampI(cx + 2, w)] * 1f
            tmp[y * ow + x] = s / 16f
        }
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val cy = y * 2
            var s = 0f
            s += tmp[clampI(cy - 2, h) * ow + x] * 1f
            s += tmp[clampI(cy - 1, h) * ow + x] * 4f
            s += tmp[clampI(cy, h) * ow + x] * 6f
            s += tmp[clampI(cy + 1, h) * ow + x] * 4f
            s += tmp[clampI(cy + 2, h) * ow + x] * 1f
            out[y * ow + x] = s / 16f
        }
        return out
    }

    /** [src] ([w]×[h]) brought up to [ow]×[oh] (bilinear, pixel centres matched). */
    private fun up(src: FloatArray, w: Int, h: Int, ow: Int, oh: Int): FloatArray {
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) {
            val sy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, h - 1f)
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val fy = sy - y0
            for (x in 0 until ow) {
                val sx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, w - 1f)
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val fx = sx - x0
                val top = src[y0 * w + x0] * (1 - fx) + src[y0 * w + x1] * fx
                val bottom = src[y1 * w + x0] * (1 - fx) + src[y1 * w + x1] * fx
                out[y * ow + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }

    private fun clampI(v: Int, n: Int) = if (v < 0) 0 else if (v >= n) n - 1 else v

    private fun gaussian(src: FloatArray, sizes: List<Pair<Int, Int>>): List<FloatArray> {
        val out = ArrayList<FloatArray>(sizes.size)
        out += src
        for (l in 1 until sizes.size) {
            val (w, h) = sizes[l - 1]
            val (ow, oh) = sizes[l]
            out += down(out[l - 1], w, h, ow, oh)
        }
        return out
    }

    private fun laplacian(src: FloatArray, sizes: List<Pair<Int, Int>>): List<FloatArray> {
        val g = gaussian(src, sizes)
        val out = ArrayList<FloatArray>(sizes.size)
        for (l in 0 until sizes.size - 1) {
            val (w, h) = sizes[l]
            val (cw, ch) = sizes[l + 1]
            val e = up(g[l + 1], cw, ch, w, h)
            val a = g[l]
            out += FloatArray(a.size) { i -> a[i] - e[i] }
        }
        out += g.last()
        return out
    }

    private fun collapse(pyr: List<FloatArray>, sizes: List<Pair<Int, Int>>): FloatArray {
        var r = pyr.last()
        for (l in sizes.size - 2 downTo 0) {
            val (w, h) = sizes[l]
            val (cw, ch) = sizes[l + 1]
            val e = up(r, cw, ch, w, h)
            val a = pyr[l]
            r = FloatArray(a.size) { i -> a[i] + e[i] }
        }
        return r
    }

    // Alignment

    /**
     * How far [picture] is shifted against [reference] (pixels to move it by, x and y), found on
     * median threshold bitmaps from coarse to fine (Ward), up to about [maxShift] pixels.
     */
    fun align(reference: IntArray, picture: IntArray, width: Int, height: Int, maxShift: Int = 64): Pair<Int, Int> {
        var levels = 0
        while ((1 shl (levels + 1)) <= maxShift && min(width, height) shr (levels + 1) >= 16) levels++
        val refs = greyPyramid(reference, width, height, levels)
        val pics = greyPyramid(picture, width, height, levels)
        var dx = 0
        var dy = 0
        for (l in levels downTo 0) {
            val (rw, rh, rg) = refs[l]
            val (_, _, pg) = pics[l]
            val (rb, re) = thresholds(rg)
            val (pb, pe) = thresholds(pg)
            dx *= 2
            dy *= 2
            var best = Long.MAX_VALUE
            var bx = dx
            var by = dy
            for (oy in -1..1) for (ox in -1..1) {
                val err = difference(rb, re, pb, pe, rw, rh, dx + ox, dy + oy)
                if (err < best) { best = err; bx = dx + ox; by = dy + oy }
            }
            dx = bx
            dy = by
        }
        return dx to dy
    }

    private fun greyPyramid(p: IntArray, width: Int, height: Int, levels: Int): List<Triple<Int, Int, IntArray>> {
        val out = ArrayList<Triple<Int, Int, IntArray>>()
        var w = width
        var h = height
        var g = IntArray(w * h) { i -> val c = p[i]; (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8 }
        out += Triple(w, h, g)
        repeat(levels) {
            val ow = w / 2
            val oh = h / 2
            val src = g
            val sw = w
            g = IntArray(ow * oh) { i ->
                val x = (i % ow) * 2
                val y = (i / ow) * 2
                (src[y * sw + x] + src[y * sw + x + 1] + src[(y + 1) * sw + x] + src[(y + 1) * sw + x + 1]) / 4
            }
            w = ow
            h = oh
            out += Triple(w, h, g)
        }
        return out
    }

    /** The median threshold bitmap and the mask of pixels not too near the median, as booleans. */
    private fun thresholds(g: IntArray): Pair<BooleanArray, BooleanArray> {
        val hist = IntArray(256)
        for (v in g) hist[v.coerceIn(0, 255)]++
        var acc = 0
        var median = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= g.size / 2) { median = i; break } }
        val bits = BooleanArray(g.size) { g[it] > median }
        val keep = BooleanArray(g.size) { abs(g[it] - median) > 4 }
        return bits to keep
    }

    private fun difference(rb: BooleanArray, re: BooleanArray, pb: BooleanArray, pe: BooleanArray, w: Int, h: Int, dx: Int, dy: Int): Long {
        var err = 0L
        var counted = 0L
        for (y in max(0, -dy) until min(h, h - dy)) {
            val ry = y + dy
            for (x in max(0, -dx) until min(w, w - dx)) {
                val i = ry * w + x + dx
                val j = y * w + x
                if (re[i] && pe[j]) {
                    counted++
                    if (rb[i] != pb[j]) err++
                }
            }
        }
        // As a share of what was compared (shifts leave less overlap), scaled to stay an integer.
        return if (counted == 0L) Long.MAX_VALUE else err * 1_000_000L / counted
    }

    /** [p] moved by ([dx], [dy]) pixels; what comes in from outside repeats the edge. */
    fun shifted(p: IntArray, width: Int, height: Int, dx: Int, dy: Int): IntArray {
        if (dx == 0 && dy == 0) return p
        return IntArray(width * height) { i ->
            val x = (i % width - dx).coerceIn(0, width - 1)
            val y = (i / width - dy).coerceIn(0, height - 1)
            p[y * width + x]
        }
    }
}
