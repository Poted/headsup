package app.headsup.color

import android.graphics.Bitmap
import app.headsup.core.color.LutBaker
import app.headsup.core.color.SkinTone

/** Vectorscope image of one preview frame plus the skin measurement at the tapped point. */
class ScopeResult(
    /** Square image, centre = neutral; colours plotted at their Cb/Cr position. */
    val image: Bitmap,
    /** Colour around the tapped point, after grading; null if it is too grey to measure. */
    val measured: SkinTone.Chroma?,
    /** Tapped point, 0..1 of the frame. */
    val markerX: Float,
    val markerY: Float,
)

/** Builds a [ScopeResult] from a small frame grab, applying the same colour table as the preview. */
object Vectorscope {
    const val SIZE = 160

    /** Chroma at the edge of the scope (Rec.709 primaries reach roughly 0.5). */
    const val RANGE = 0.4

    fun analyse(pixels: IntArray, width: Int, height: Int, cube: Array<Array<IntArray>>?, markerX: Float, markerY: Float): ScopeResult {
        val sums = FloatArray(SIZE * SIZE * 3)
        val counts = IntArray(SIZE * SIZE)
        val rgb = FloatArray(3)
        val scale = SIZE / 2 / RANGE
        val stride = maxOf(1, (pixels.size / 12_000))
        for (i in pixels.indices step stride) {
            graded(pixels[i], cube, rgb)
            val c = SkinTone.chroma(rgb[0], rgb[1], rgb[2])
            val x = (SIZE / 2 + c.cb * scale).toInt()
            val y = (SIZE / 2 - c.cr * scale).toInt()
            if (x !in 0 until SIZE || y !in 0 until SIZE) continue
            val bin = y * SIZE + x
            counts[bin]++
            sums[bin * 3] += rgb[0]
            sums[bin * 3 + 1] += rgb[1]
            sums[bin * 3 + 2] += rgb[2]
        }
        val out = IntArray(SIZE * SIZE)
        for (bin in counts.indices) {
            val n = counts[bin]
            if (n == 0) continue
            // Each dot shows the average colour that lands there, brighter where more pixels fall.
            val boost = 1.4f
            fun ch(v: Float) = (v / n * 255 * boost).toInt().coerceIn(40, 255)
            val alpha = (90 + n * 30).coerceAtMost(255)
            out[bin] = (alpha shl 24) or (ch(sums[bin * 3]) shl 16) or (ch(sums[bin * 3 + 1]) shl 8) or ch(sums[bin * 3 + 2])
        }
        val image = Bitmap.createBitmap(out, SIZE, SIZE, Bitmap.Config.ARGB_8888)

        // Average a small patch around the tap so one noisy pixel does not decide the result.
        val cx = (markerX * width).toInt().coerceIn(0, width - 1)
        val cy = (markerY * height).toInt().coerceIn(0, height - 1)
        val avg = FloatArray(3)
        var n = 0
        for (y in cy - 3..cy + 3) for (x in cx - 3..cx + 3) {
            if (x !in 0 until width || y !in 0 until height) continue
            graded(pixels[y * width + x], cube, rgb)
            for (k in 0..2) avg[k] += rgb[k]
            n++
        }
        val measured = if (n == 0) null else {
            SkinTone.chroma(avg[0] / n, avg[1] / n, avg[2] / n).takeIf { it.magnitude > 0.01 }
        }
        return ScopeResult(image, measured, markerX, markerY)
    }

    private fun graded(argb: Int, cube: Array<Array<IntArray>>?, out: FloatArray) {
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
        if (cube != null) LutBaker.sampleBaked(cube, out[0], out[1], out[2], out)
    }
}
