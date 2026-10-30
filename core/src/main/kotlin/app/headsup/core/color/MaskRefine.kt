package app.headsup.core.color

import kotlin.math.max
import kotlin.math.min

/**
 * A rough mask (from a model that sees the picture small) laid onto the picture's own edges: the
 * colour guided filter (He, Sun and Tang). Within each small window the mask is taken as a linear
 * function of the picture's colour, so where the colour changes (a ridge against the sky, a mug
 * against the table) the mask changes with it, and where the colour stays it stays.
 */
object MaskRefine {
    /**
     * [mask] (0..1, [width]×[height], row by row) following the edges of [rgb] (the same size, RGB
     * 0..1 per pixel). [radius] is the window's half size in pixels: about how far off the rough
     * mask's edge may be. [eps] is how big a colour change counts as an edge (smaller: finer).
     */
    fun guided(rgb: FloatArray, mask: FloatArray, width: Int, height: Int, radius: Int, eps: Float = 1e-3f): FloatArray {
        val n = width * height
        require(rgb.size >= n * 3 && mask.size >= n)
        val r = FloatArray(n) { rgb[it * 3] }
        val g = FloatArray(n) { rgb[it * 3 + 1] }
        val b = FloatArray(n) { rgb[it * 3 + 2] }
        fun mean(a: FloatArray) = box(a, width, height, radius)
        fun mul(a: FloatArray, c: FloatArray) = FloatArray(n) { a[it] * c[it] }
        val mr = mean(r)
        val mg = mean(g)
        val mb = mean(b)
        val mp = mean(mask)
        val cr = mean(mul(r, mask))
        val cg = mean(mul(g, mask))
        val cb = mean(mul(b, mask))
        val rr = mean(mul(r, r))
        val rg = mean(mul(r, g))
        val rb = mean(mul(r, b))
        val gg = mean(mul(g, g))
        val gb = mean(mul(g, b))
        val bb = mean(mul(b, b))
        val ar = FloatArray(n)
        val ag = FloatArray(n)
        val ab = FloatArray(n)
        val bv = FloatArray(n)
        for (i in 0 until n) {
            // Covariance of the colour (3×3, symmetric) plus eps, and of colour with the mask.
            val s11 = rr[i] - mr[i] * mr[i] + eps
            val s12 = rg[i] - mr[i] * mg[i]
            val s13 = rb[i] - mr[i] * mb[i]
            val s22 = gg[i] - mg[i] * mg[i] + eps
            val s23 = gb[i] - mg[i] * mb[i]
            val s33 = bb[i] - mb[i] * mb[i] + eps
            val pr = cr[i] - mr[i] * mp[i]
            val pg = cg[i] - mg[i] * mp[i]
            val pb = cb[i] - mb[i] * mp[i]
            // Solved by the inverse through cofactors.
            val i11 = s22 * s33 - s23 * s23
            val i12 = s13 * s23 - s12 * s33
            val i13 = s12 * s23 - s13 * s22
            val i22 = s11 * s33 - s13 * s13
            val i23 = s13 * s12 - s11 * s23
            val i33 = s11 * s22 - s12 * s12
            val det = s11 * i11 + s12 * i12 + s13 * i13
            if (det <= 1e-12f) {
                bv[i] = mp[i]
                continue
            }
            val a1 = (i11 * pr + i12 * pg + i13 * pb) / det
            val a2 = (i12 * pr + i22 * pg + i23 * pb) / det
            val a3 = (i13 * pr + i23 * pg + i33 * pb) / det
            ar[i] = a1
            ag[i] = a2
            ab[i] = a3
            bv[i] = mp[i] - a1 * mr[i] - a2 * mg[i] - a3 * mb[i]
        }
        val mar = mean(ar)
        val mag = mean(ag)
        val mab = mean(ab)
        val mbv = mean(bv)
        return FloatArray(n) { i -> (mar[i] * r[i] + mag[i] * g[i] + mab[i] * b[i] + mbv[i]).coerceIn(0f, 1f) }
    }

    /** The mean over a (2[radius]+1)² window at each pixel; at the borders, of the part inside. */
    fun box(a: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val tmp = FloatArray(width * height)
        val out = FloatArray(width * height)
        val row = DoubleArray(width + 1)
        for (y in 0 until height) {
            val o = y * width
            for (x in 0 until width) row[x + 1] = row[x] + a[o + x]
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                tmp[o + x] = ((row[x1 + 1] - row[x0]) / (x1 - x0 + 1)).toFloat()
            }
        }
        val col = DoubleArray(height + 1)
        for (x in 0 until width) {
            for (y in 0 until height) col[y + 1] = col[y] + tmp[y * width + x]
            for (y in 0 until height) {
                val y0 = max(0, y - radius)
                val y1 = min(height - 1, y + radius)
                out[y * width + x] = ((col[y1 + 1] - col[y0]) / (y1 - y0 + 1)).toFloat()
            }
        }
        return out
    }
}
