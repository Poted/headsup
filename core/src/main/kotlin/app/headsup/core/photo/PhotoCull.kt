package app.headsup.core.photo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A first pass over an album: how sharp and how well exposed each photo is, which photos are
 * takes of the same shot (a burst, a few frames of one scene, one after another), and which of
 * each such group is best. It only ever suggests good photos (stars them); it never rejects one.
 */
object PhotoCull {
    /** What is measured of one photo, from a copy of it big enough to show blur (about 1500 pixels across). */
    class Features(
        /** How sharp the sharpest parts are (the subject in focus, whatever the background does); larger is sharper. */
        val sharpness: Float,
        /** 1 = well exposed; less for highlights burnt out or shadows crushed over much of it. */
        val exposure: Float,
        /** A coarse picture of its brightness (zero mean, unit length), for telling takes of one shot. */
        val print: FloatArray,
        /** A coarse picture of its colour (mean RGB of a 4×4 grid, 0..1). */
        val colour: FloatArray,
        /**
         * How far the picture's dominant straight lines (a horizon, walls) are off level or
         * plumb, in degrees; null when it has no dominant lines to tell by.
         */
        val tilt: Float? = null,
    )

    class Result(
        /** For each photo, the group of takes it is in (photos of one shot share it). */
        val group: IntArray,
        /** For each photo, how good it is within the album (0..1; sharpness first, then exposure). */
        val score: FloatArray,
        /** The photos suggested: the best of each group, unless that is clearly soft or badly exposed. */
        val suggested: Set<Int>,
    )

    private const val PRINT = 16

    /** Edges this wide (pixels, at the size measured) are blurred whatever the rest of the album is like. */
    const val BLURRED_EDGE = 8f

    /** A photo less sharp than this share of the album's sharp ones is soft. */
    const val SOFT = 0.7f

    /** Takes of one shot look at least this alike ([similarity]). */
    const val SAME_SHOT = 0.82f

    fun features(argb: IntArray, width: Int, height: Int): Features {
        require(width > 2 && height > 2 && argb.size >= width * height)
        val n = width * height
        val y = FloatArray(n) { i ->
            val p = argb[i]
            (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
        }
        val sharpness = sharpnessOf(y, width, height)
        // Exposure: what is burnt out or crushed to black, over the whole picture.
        var bright = 0
        var dark = 0
        for (v in y) {
            if (v >= 0.985f) bright++
            if (v <= 0.015f) dark++
        }
        // Some burnt-out sky or black clothes are fine; much of the picture lost is not.
        val exposure = (1f - 4f * (bright.toFloat() / n - 0.05f).coerceAtLeast(0f) - 2f * (dark.toFloat() / n - 0.25f).coerceAtLeast(0f)).coerceIn(0f, 1f)
        // The coarse prints.
        val print = FloatArray(PRINT * PRINT)
        val pc = IntArray(PRINT * PRINT)
        val colour = FloatArray(4 * 4 * 3)
        val cc = IntArray(16)
        for (yy in 0 until height) for (xx in 0 until width) {
            val i = yy * width + xx
            val k = (yy * PRINT / height) * PRINT + (xx * PRINT / width)
            print[k] += y[i]
            pc[k]++
            val q = (yy * 4 / height) * 4 + (xx * 4 / width)
            val p = argb[i]
            colour[q * 3] += ((p shr 16) and 0xFF) / 255f
            colour[q * 3 + 1] += ((p shr 8) and 0xFF) / 255f
            colour[q * 3 + 2] += (p and 0xFF) / 255f
            cc[q]++
        }
        for (k in print.indices) print[k] /= pc[k].coerceAtLeast(1)
        for (q in 0 until 16) for (c in 0..2) colour[q * 3 + c] /= cc[q].coerceAtLeast(1)
        val mean = print.average().toFloat()
        for (k in print.indices) print[k] -= mean
        val len = sqrt(print.sumOf { (it * it).toDouble() }).toFloat()
        if (len > 1e-6f) for (k in print.indices) print[k] /= len
        return Features(sharpness, exposure, print, colour, tiltOf(y, width, height))
    }

    /**
     * The tilt of the dominant lines: edge directions (Sobel) folded onto ±45° of the nearest of
     * level and plumb, weighted by how strong each edge is; a clear peak is the picture's lines.
     */
    fun tiltOf(y: FloatArray, width: Int, height: Int): Float? {
        val bins = 180 // 0.5° each over -45..45
        val hist = FloatArray(bins)
        var total = 0f
        for (yy in 1 until height - 1) for (xx in 1 until width - 1) {
            val i = yy * width + xx
            val gx = (y[i - width + 1] + 2f * y[i + 1] + y[i + width + 1]) - (y[i - width - 1] + 2f * y[i - 1] + y[i + width - 1])
            val gy = (y[i + width - 1] + 2f * y[i + width] + y[i + width + 1]) - (y[i - width - 1] + 2f * y[i - width] + y[i - width + 1])
            val m = gx * gx + gy * gy
            if (m < 0.02f) continue
            // The edge runs across its gradient; folded to the nearest axis.
            var a = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble())).toFloat() + 90f
            a = ((a + 45f) % 90f + 90f) % 90f - 45f
            val b = ((a + 45f) * 2f).toInt().coerceIn(0, bins - 1)
            hist[b] += m
            total += m
        }
        if (total <= 0f) return null
        // The strongest direction, over ±1.5°.
        var best = 0
        var bestSum = 0f
        for (b in 0 until bins) {
            var sum = 0f
            for (d in -3..3) sum += hist[(b + d + bins) % bins]
            if (sum > bestSum) { bestSum = sum; best = b }
        }
        // Spread evenly, ±1.5° would hold 7/180 of it: a peak holds well over twice that.
        if (bestSum / total < 0.1f) return null
        var acc = 0f
        var w = 0f
        for (d in -3..3) {
            val k = (best + d + bins) % bins
            val centre = (best + d) / 2f - 45f + 0.25f
            acc += centre * hist[k]
            w += hist[k]
        }
        return acc / w.coerceAtLeast(1e-6f)
    }

    /** Lines further off level than this (and not so far as to be meant) look crooked. */
    const val CROOKED_DEGREES = 1.5f

    /**
     * How sharp the photo is, as 1 / the width (pixels) of its sharpest edges: across each clear
     * edge (a rise of at least [EDGE_RISE]) the run over which the brightness keeps climbing is
     * measured, along rows and along columns apart. The sharpest quarter of the edges count (a
     * subject in focus before a soft background is sharp), and the worse of the two directions
     * (shake smears edges one way only). It does not care about contrast (a dim landscape's
     * edges are as narrow as a bright one's) nor about fine noise, which never makes a clear edge.
     */
    fun sharpnessOf(y: FloatArray, width: Int, height: Int): Float {
        val across = edgeWidths(y, width, height, 1, width, height, width)
        val down = edgeWidths(y, width, height, width, height, width, 1)
        val a = quarter(across)
        val d = quarter(down)
        val worst = when {
            a == null && d == null -> return 0f
            a == null -> d!!
            d == null -> a
            else -> maxOf(a, d)
        }
        return 1f / worst
    }

    /** A clear edge rises at least this much (of full brightness) from its foot to its top. */
    const val EDGE_RISE = 0.06f

    /** The width of the sharpest quarter's edges (the mean from 15 % to 35 % of [widths], sorted); null when there are too few to tell. */
    private fun quarter(widths: MutableList<Int>): Float? {
        if (widths.size < 40) return null
        widths.sort()
        val from = widths.size * 15 / 100
        val to = (widths.size * 35 / 100).coerceAtLeast(from + 1)
        return widths.subList(from, to).average().toFloat()
    }

    /**
     * Edge widths along lines of the picture: [step] apart along a line of [length] pixels,
     * [lines] lines [lineStep] apart. Each local peak of the slope is walked out both ways while
     * the brightness keeps going the same way.
     */
    private fun edgeWidths(y: FloatArray, width: Int, height: Int, step: Int, length: Int, lines: Int, lineStep: Int): MutableList<Int> {
        val out = ArrayList<Int>()
        // Every other line is plenty.
        for (line in 0 until lines step 2) {
            val base = line * lineStep
            fun v(k: Int) = y[base + k * step]
            for (k in 2 until length - 2) {
                val g = v(k + 1) - v(k - 1)
                if (kotlin.math.abs(g) < 0.02f) continue
                val gl = v(k) - v(k - 2)
                val gr = v(k + 2) - v(k)
                if (kotlin.math.abs(g) < kotlin.math.abs(gl) || kotlin.math.abs(g) <= kotlin.math.abs(gr)) continue
                val up = g > 0f
                var l = k
                while (l > 0) {
                    val d = v(l) - v(l - 1)
                    if ((up && d <= 0.002f) || (!up && d >= -0.002f)) break
                    l--
                }
                var r = k
                while (r < length - 1) {
                    val d = v(r + 1) - v(r)
                    if ((up && d <= 0.002f) || (!up && d >= -0.002f)) break
                    r++
                }
                if (kotlin.math.abs(v(r) - v(l)) < EDGE_RISE) continue
                out += (r - l).coerceAtLeast(1)
            }
        }
        return out
    }

    /** How alike two photos look, 0..1: their brightness layout and their colours. */
    fun similarity(a: Features, b: Features): Float {
        var dot = 0f
        for (k in a.print.indices) dot += a.print[k] * b.print[k]
        // A flat picture (no layout to compare) is told by its colours alone.
        val flat = a.print.all { it == 0f } || b.print.all { it == 0f }
        var diff = 0f
        for (k in a.colour.indices) diff += abs(a.colour[k] - b.colour[k])
        val colourAlike = (1f - diff / a.colour.size * 4f).coerceIn(0f, 1f)
        return if (flat) colourAlike else (0.65f * ((dot + 1f) / 2f).coerceIn(0f, 1f).let { it * it } + 0.35f * colourAlike)
    }

    /** Groups the photos (in album order: takes of one shot follow each other) and picks the best of each. */
    fun choose(photos: List<Features>, eligible: (Int) -> Boolean = { true }): Result {
        val n = photos.size
        val group = IntArray(n)
        var g = 0
        for (i in 1 until n) {
            if (similarity(photos[i - 1], photos[i]) < SAME_SHOT) g++
            group[i] = g
        }
        if (n == 0) return Result(group, FloatArray(0), emptySet())
        val sharp = photos.map { it.sharpness }.sorted()
        // Against the album's sharp photos (its top tenth), not its middle: a soft photo among
        // a few others is still soft.
        val reference = sharp[kotlin.math.ceil((n - 1) * 0.9).toInt().coerceIn(0, n - 1)].coerceAtLeast(1e-6f)
        val top = sharp.last().coerceAtLeast(1e-6f)
        val score = FloatArray(n) { i -> (0.75f * photos[i].sharpness / top + 0.25f * photos[i].exposure).coerceIn(0f, 1f) }
        fun good(i: Int): Boolean {
            val p = photos[i]
            val level = p.tilt == null || abs(p.tilt) < CROOKED_DEGREES || abs(p.tilt) > 20f
            return eligible(i) && level && p.exposure >= 0.55f && p.sharpness >= SOFT * reference && p.sharpness >= 1f / BLURRED_EDGE
        }
        val suggested = HashSet<Int>()
        for (members in (0 until n).groupBy { group[it] }.values) {
            // Within a group, sharpness against the group's sharpest matters most.
            val best = members.filter { good(it) }.maxByOrNull { score[it] } ?: continue
            suggested += best
            // Another take as good (a tie) is suggested too, to choose between.
            members.filter { it != best && good(it) && score[it] >= score[best] - 0.02f }.forEach { suggested += it }
        }
        return Result(group, score, suggested)
    }
}
