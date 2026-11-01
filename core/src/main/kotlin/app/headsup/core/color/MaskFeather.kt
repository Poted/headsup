package app.headsup.core.color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A mask's edge made gradual (feathered): coverage blurred by about [radius] pixels (three box
 * blurs across and down, close to a Gaussian), so an edge that cannot follow a ragged shore or
 * treeline exactly fades over a band instead of cutting like a knife. Areas well inside or outside
 * stay as they are.
 */
object MaskFeather {
    fun apply(map: FloatArray, width: Int, height: Int, radius: Float): FloatArray {
        if (radius < 0.5f || width < 2 || height < 2) return map
        // Three box passes of this half-width make a Gaussian of about [radius] spread.
        val r = max(1, (radius / 1.73f).roundToInt())
        var a = map.copyOf()
        var b = FloatArray(map.size)
        repeat(3) {
            boxAcross(a, b, width, height, r)
            boxDown(b, a, width, height, r)
        }
        return a
    }

    private fun boxAcross(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + min(w - 1, max(0, k))]
            for (x in 0 until w) {
                dst[row + x] = sum / n
                sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
    }

    private fun boxDown(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += src[min(h - 1, max(0, k)) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / n
                sum += src[min(h - 1, y + r + 1) * w + x] - src[max(0, y - r) * w + x]
            }
        }
    }
}
