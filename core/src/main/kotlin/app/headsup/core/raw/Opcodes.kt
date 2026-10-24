package app.headsup.core.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * DNG opcodes: corrections the camera writes into the file for the raw converter to apply (DNG
 * 1.3+). List 2 works on the raw values (lens shading as gain maps), list 3 on the demosaiced
 * picture (vignetting, and lens distortion and lateral chromatic aberration as a warp).
 */
sealed class Opcode {
    /**
     * Gains over the image area: a grid of [pointsV] × [pointsH] points (relative positions from
     * [originV], [originH] by [spacingV], [spacingH]), interpolated between them, applied to the
     * pixels of [planes] planes from [plane] in the rectangle, every [rowPitch] rows and [colPitch] columns.
     */
    class GainMap(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val plane: Int, val planes: Int, val rowPitch: Int, val colPitch: Int,
        val pointsV: Int, val pointsH: Int,
        val spacingV: Double, val spacingH: Double, val originV: Double, val originH: Double,
        val mapPlanes: Int, val gains: FloatArray,
    ) : Opcode() {
        /** Whether it applies to pixel ([row], [col]) of plane [p]. */
        fun covers(row: Int, col: Int, p: Int): Boolean =
            p >= plane && p < plane + planes && row in top until bottom && col in left until right &&
                (row - top) % rowPitch == 0 && (col - left) % colPitch == 0

        /** The gain at pixel ([row], [col]) of plane [p] in an image [width] × [height]. */
        fun gain(row: Int, col: Int, p: Int, width: Int, height: Int): Float {
            val mp = (p - plane).coerceIn(0, mapPlanes - 1)
            val fv = (((row + 0.5) / height - originV) / spacingV.takeIf { it > 0 }.orOne()).coerceIn(0.0, (pointsV - 1).toDouble())
            val fh = (((col + 0.5) / width - originH) / spacingH.takeIf { it > 0 }.orOne()).coerceIn(0.0, (pointsH - 1).toDouble())
            val v0 = fv.toInt()
            val h0 = fh.toInt()
            val v1 = minOf(v0 + 1, pointsV - 1)
            val h1 = minOf(h0 + 1, pointsH - 1)
            val tv = (fv - v0).toFloat()
            val th = (fh - h0).toFloat()
            fun at(v: Int, h: Int) = gains[(v * pointsH + h) * mapPlanes + mp]
            val top = at(v0, h0) + (at(v0, h1) - at(v0, h0)) * th
            val bottom = at(v1, h0) + (at(v1, h1) - at(v1, h0)) * th
            return top + (bottom - top) * tv
        }

        private fun Double?.orOne() = this ?: 1.0
    }

    /**
     * Radial vignetting: gain 1 + k0·r² + k1·r⁴ + k2·r⁶ + k3·r⁸ + k4·r¹⁰, r from the optical
     * centre ([cx], [cy] relative), 1 at the farthest corner.
     */
    class FixVignetteRadial(val k: DoubleArray, val cx: Double, val cy: Double) : Opcode() {
        fun gain(x: Double, y: Double, width: Int, height: Int): Float {
            val r2 = Radial(cx, cy, width, height).r2(x, y)
            var g = 1.0
            var p = r2
            for (c in k) { g += c * p; p *= r2 }
            return g.toFloat()
        }
    }

    /**
     * Lens distortion: for each output pixel, where to take it from (radial kr0..kr3 and
     * tangential kt0, kt1 terms), per plane (3 planes also correct lateral chromatic aberration).
     */
    class WarpRectilinear(val coefficients: Array<DoubleArray>, val cx: Double, val cy: Double) : Opcode() {
        /** Source position (x, y) of output pixel ([x], [y]) for plane [p], into [out]. */
        fun source(x: Double, y: Double, p: Int, width: Int, height: Int, out: DoubleArray) {
            val k = coefficients[minOf(p, coefficients.size - 1)]
            val g = Radial(cx, cy, width, height)
            val dx = (x - g.centerX) / g.norm
            val dy = (y - g.centerY) / g.norm
            val r2 = dx * dx + dy * dy
            val f = k[0] + r2 * (k[1] + r2 * (k[2] + r2 * k[3]))
            val tx = k[4] * 2 * dx * dy + k[5] * (r2 + 2 * dx * dx)
            val ty = k[5] * 2 * dx * dy + k[4] * (r2 + 2 * dy * dy)
            out[0] = g.centerX + (dx * f + tx) * g.norm
            out[1] = g.centerY + (dy * f + ty) * g.norm
        }

        val planes: Int get() = coefficients.size

        /** [pixels] (ARGB, [width] × [height]) corrected: each colour taken from its own source, bilinearly. */
        fun applyTo(pixels: IntArray, width: Int, height: Int): IntArray {
            val out = IntArray(pixels.size)
            val src = DoubleArray(2)
            for (y in 0 until height) for (x in 0 until width) {
                var argb = 0xFF shl 24
                for (c in 0 until 3) {
                    if (c == 0 || planes > 1) source(x.toDouble(), y.toDouble(), c, width, height, src)
                    val shift = 16 - 8 * c
                    argb = argb or (sample(pixels, width, height, src[0], src[1], shift) shl shift)
                }
                out[y * width + x] = argb
            }
            return out
        }

        private fun sample(p: IntArray, w: Int, h: Int, sx: Double, sy: Double, shift: Int): Int {
            val x = sx.coerceIn(0.0, (w - 1).toDouble())
            val y = sy.coerceIn(0.0, (h - 1).toDouble())
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = minOf(x0 + 1, w - 1)
            val y1 = minOf(y0 + 1, h - 1)
            val fx = x - x0
            val fy = y - y0
            fun v(xx: Int, yy: Int) = (p[yy * w + xx] shr shift) and 0xFF
            val top = v(x0, y0) + (v(x1, y0) - v(x0, y0)) * fx
            val bottom = v(x0, y1) + (v(x1, y1) - v(x0, y1)) * fx
            return (top + (bottom - top) * fy + 0.5).toInt().coerceIn(0, 255)
        }
    }

    /** Coordinates from an optical centre, normalised so the farthest corner is at 1. */
    class Radial(cx: Double, cy: Double, width: Int, height: Int) {
        val centerX = cx * (width - 1)
        val centerY = cy * (height - 1)
        // The distance to the farthest corner.
        val norm = max(1.0, sqrt(max(centerX * centerX, (width - 1 - centerX).let { it * it }) + max(centerY * centerY, (height - 1 - centerY).let { it * it })))

        fun r2(x: Double, y: Double): Double {
            val dx = (x - centerX) / norm
            val dy = (y - centerY) / norm
            return dx * dx + dy * dy
        }
    }

    companion object {
        const val ID_WARP_RECTILINEAR = 1
        const val ID_FIX_VIGNETTE_RADIAL = 3
        const val ID_GAIN_MAP = 9

        /** The opcodes the app applies from a list (big-endian, as DNG stores them); others are skipped. */
        fun parse(bytes: ByteArray?): List<Opcode> {
            if (bytes == null || bytes.size < 4) return emptyList()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var at = 0
            fun int() = b.getInt(at).also { at += 4 }
            fun double() = b.getDouble(at).also { at += 8 }
            fun float() = b.getFloat(at).also { at += 4 }
            val list = ArrayList<Opcode>()
            val count = int()
            repeat(count) {
                if (bytes.size - at < 16) return list
                val id = int()
                int() // DNG version
                int() // flags
                val size = int()
                if (size < 0 || size > bytes.size - at) return list
                val next = at + size
                runCatching {
                    when (id) {
                        ID_WARP_RECTILINEAR -> {
                            val planes = int().coerceIn(1, 4)
                            val k = Array(planes) { DoubleArray(6) { double() } }
                            list += WarpRectilinear(k, double(), double())
                        }
                        ID_FIX_VIGNETTE_RADIAL -> list += FixVignetteRadial(DoubleArray(5) { double() }, double(), double())
                        ID_GAIN_MAP -> {
                            val top = int(); val left = int(); val bottom = int(); val right = int()
                            val plane = int(); val planes = int()
                            val rowPitch = int().coerceAtLeast(1); val colPitch = int().coerceAtLeast(1)
                            val pointsV = int().coerceAtLeast(1); val pointsH = int().coerceAtLeast(1)
                            val spacingV = double(); val spacingH = double()
                            val originV = double(); val originH = double()
                            val mapPlanes = int().coerceAtLeast(1)
                            val gains = FloatArray(pointsV * pointsH * mapPlanes) { float() }
                            list += GainMap(top, left, bottom, right, plane, planes, rowPitch, colPitch, pointsV, pointsH, spacingV, spacingH, originV, originH, mapPlanes, gains)
                        }
                    }
                }
                at = next
            }
            return list
        }
    }
}
