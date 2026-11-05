package app.headsup.core.photo

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A first pass over an album: how sharp and how well exposed each photo is, which photos are
 * takes of the same shot (a burst, a few frames of one scene, one after another), and which of
 * each such group is best. It only ever suggests good photos (stars them); it never rejects one.
 */
object PhotoCull {
    /** What is measured of one photo, from a small copy of it (a few hundred pixels across). */
    class Features(
        /** How sharp the sharpest parts are (the subject in focus, whatever the background does); larger is sharper. */
        val sharpness: Float,
        /** 1 = well exposed; less for highlights burnt out or shadows crushed over much of it. */
        val exposure: Float,
        /** A coarse picture of its brightness (zero mean, unit length), for telling takes of one shot. */
        val print: FloatArray,
        /** A coarse picture of its colour (mean RGB of a 4×4 grid, 0..1). */
        val colour: FloatArray,
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

    /** Takes of one shot look at least this alike ([similarity]). */
    const val SAME_SHOT = 0.82f

    fun features(argb: IntArray, width: Int, height: Int): Features {
        require(width > 2 && height > 2 && argb.size >= width * height)
        val n = width * height
        val y = FloatArray(n) { i ->
            val p = argb[i]
            (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
        }
        // Sharpness: the spread of the Laplacian in each tile of a 6×6 grid; the sharpest tiles
        // (a sixth of them) count, so a sharp subject before a soft background is sharp.
        val grid = 6
        val tiles = FloatArray(grid * grid)
        val counts = IntArray(grid * grid)
        val sums = FloatArray(grid * grid)
        val squares = FloatArray(grid * grid)
        for (yy in 1 until height - 1) for (xx in 1 until width - 1) {
            val i = yy * width + xx
            val l = 4f * y[i] - y[i - 1] - y[i + 1] - y[i - width] - y[i + width]
            val t = (yy * grid / height) * grid + (xx * grid / width)
            sums[t] += l
            squares[t] += l * l
            counts[t]++
        }
        for (t in tiles.indices) {
            val c = counts[t].coerceAtLeast(1)
            val m = sums[t] / c
            tiles[t] = squares[t] / c - m * m
        }
        tiles.sortDescending()
        val top = tiles.take((tiles.size / 6).coerceAtLeast(1))
        val sharpness = ln(1f + top.average().toFloat() * 1e4f)
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
        return Features(sharpness, exposure, print, colour)
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
    fun choose(photos: List<Features>): Result {
        val n = photos.size
        val group = IntArray(n)
        var g = 0
        for (i in 1 until n) {
            if (similarity(photos[i - 1], photos[i]) < SAME_SHOT) g++
            group[i] = g
        }
        if (n == 0) return Result(group, FloatArray(0), emptySet())
        val sharp = photos.map { it.sharpness }.sorted()
        val median = sharp[n / 2]
        val top = sharp.last().coerceAtLeast(1e-6f)
        val score = FloatArray(n) { i -> (0.75f * photos[i].sharpness / top + 0.25f * photos[i].exposure).coerceIn(0f, 1f) }
        val suggested = HashSet<Int>()
        for (members in (0 until n).groupBy { group[it] }.values) {
            // Within a group, sharpness against the group's sharpest matters most.
            val best = members.maxByOrNull { score[it] } ?: continue
            val p = photos[best]
            if (p.sharpness >= 0.6f * median && p.exposure >= 0.55f) {
                suggested += best
                // Another take as good (a tie) is suggested too, to choose between.
                members.filter { it != best && score[it] >= score[best] - 0.02f && photos[it].exposure >= 0.55f }.forEach { suggested += it }
            }
        }
        return Result(group, score, suggested)
    }
}
