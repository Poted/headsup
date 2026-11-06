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
        val exposure = (1f - 2.5f * bright / n - 1.5f * dark / n).coerceIn(0f, 1f)
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
     * How sharp the sharpest parts are, whatever their contrast: in each tile of a 6×6 grid the
     * finest detail (second differences at full size) against the coarser detail of the same
     * tile (at half size), in four directions, the weakest counting: shake blurs one direction
     * and leaves the others, so it shows there. Focus or shake takes the fine detail first, so the
     * ratio falls; a dim, soft-lit landscape in focus scores as well as a contrasty one. Tiles with
     * hardly any structure (open sky, where only noise is fine) do not count; of the rest, the
     * sharpest third does.
     */
    private fun sharpnessOf(y: FloatArray, width: Int, height: Int): Float {
        val hw = width / 2
        val hh = height / 2
        val half = FloatArray(hw * hh) { i ->
            val x = (i % hw) * 2
            val yy = (i / hw) * 2
            (y[yy * width + x] + y[yy * width + x + 1] + y[(yy + 1) * width + x] + y[(yy + 1) * width + x + 1]) / 4f
        }
        val grid = 6
        val fine = energy(y, width, height, grid)
        val coarse = energy(half, hw, hh, grid)
        val tiles = (0 until grid * grid).filter { t -> coarse[t].average() > 0.012 }.ifEmpty { (0 until grid * grid).toList() }
        val ratios = tiles.map { t -> (0 until 4).minOf { d -> fine[t][d] / (coarse[t][d] + 0.004f) } }.sortedDescending()
        return ratios.take((ratios.size / 3).coerceAtLeast(1)).average().toFloat()
    }

    /** Root mean square of the second differences across, down and along both diagonals, in each tile of a [grid]×[grid] split. */
    private fun energy(y: FloatArray, width: Int, height: Int, grid: Int): Array<FloatArray> {
        val sums = Array(grid * grid) { DoubleArray(4) }
        val counts = IntArray(grid * grid)
        for (yy in 1 until height - 1) for (xx in 1 until width - 1) {
            val i = yy * width + xx
            val c = 2f * y[i]
            val h = c - y[i - 1] - y[i + 1]
            val v = c - y[i - width] - y[i + width]
            val d1 = c - y[i - width - 1] - y[i + width + 1]
            val d2 = c - y[i - width + 1] - y[i + width - 1]
            val t = (yy * grid / height) * grid + (xx * grid / width)
            val s = sums[t]
            s[0] += (h * h).toDouble()
            s[1] += (v * v).toDouble()
            // Diagonal steps are longer (√2): their differences scaled to match.
            s[2] += (d1 * d1 / 2f).toDouble()
            s[3] += (d2 * d2 / 2f).toDouble()
            counts[t]++
        }
        return Array(grid * grid) { t -> FloatArray(4) { d -> sqrt(sums[t][d] / counts[t].coerceAtLeast(1)).toFloat() } }
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
            return eligible(i) && level && p.exposure >= 0.55f && p.sharpness >= SOFT * reference
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
