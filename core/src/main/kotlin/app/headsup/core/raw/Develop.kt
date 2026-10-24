package app.headsup.core.raw

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Development of a raw image, row by row, in floating point: white balance, highlights clipped in
 * the camera rebuilt from the channels that still hold them (white with their brightness, not
 * pink), camera to sRGB colours, exposure, highlights and shadows in linear
 * light, then a gentle film-like curve. A mosaic at step 1 is demosaiced at full size; at larger
 * steps each output pixel averages a 2×2 block. Rows are asked for in order.
 */
class Developer(raw: RawImage, private val settings: RawDevelop = RawDevelop(), step: Int = 1) {
    private val s = step.coerceAtLeast(1)
    val width = raw.developedWidth / s
    val height = raw.developedHeight / s
    // Balanced camera values to linear sRGB: the file's full colour model when it has one.
    private val rgbCam = raw.balancedToSrgb ?: Dng.cameraToSrgb(raw.colorMatrix)
    private val gain: Float
    private val camera: CameraRows

    /** Balanced value each channel reaches where the sensor clipped. */
    private val clip = FloatArray(3)

    /** The camera's vignetting correction (opcode list 3), as a gain per pixel of this size. */
    private val vignette = raw.corrections.filterIsInstance<Opcode.FixVignetteRadial>().firstOrNull()
    private val vignetteRow = vignette?.let { FloatArray(width) }

    init {
        // White balance: the as-shot neutral becomes grey, then the user's warmth and tint. The
        // multipliers are scaled so the smallest is 1; the scale goes into the exposure instead.
        val warm = 2.0.pow(settings.temperature * 0.6)
        val tint = 2.0.pow(settings.tint * 0.4)
        val wb = doubleArrayOf(1.0 / raw.asShotNeutral[0] * warm, 1.0 / tint, 1.0 / raw.asShotNeutral[2] / warm)
        val least = wb.min()
        val balance = FloatArray(3) { (wb[it] / least).toFloat() }
        for (c in 0 until 3) clip[c] = balance[c] * 0.995f
        gain = (2.0.pow(raw.baselineExposure + settings.exposure) * least).toFloat()
        camera = when {
            raw.channels == 3 -> LinearRows(raw, balance, s, width)
            s == 1 && raw.width >= 8 && raw.height >= 8 && Demosaic.isBayer(raw.cfa) -> Demosaic(raw, balance)
            else -> BlockRows(raw, balance, s, width)
        }
    }

    private val cam = FloatArray(width * 3)

    /** Row [y]: display values (sRGB with the curve), 0..1, three per pixel, into [out]. */
    fun row(y: Int, out: FloatArray) {
        camera.row(y, cam)
        if (vignette != null && vignetteRow != null) {
            for (x in 0 until width) vignetteRow[x] = vignette.gain(x.toDouble(), y.toDouble(), width, height)
        }
        val m = rgbCam
        for (x in 0 until width) {
            var r0 = cam[x * 3]
            var g0 = cam[x * 3 + 1]
            var b0 = cam[x * 3 + 2]
            // A channel clipped in the camera takes the brightest one's value: the highlight keeps
            // its brightness (above white, for the roll-off and Highlights to bring back) as white.
            val cr = r0 >= clip[0]
            val cg = g0 >= clip[1]
            val cb = b0 >= clip[2]
            if (cr || cg || cb) {
                val top = max(r0, max(g0, b0))
                if (cr) r0 = top
                if (cg) g0 = top
                if (cb) b0 = top
            }
            if (vignetteRow != null) {
                val v = vignetteRow[x]
                r0 *= v; g0 *= v; b0 *= v
            }
            var r = (m[0] * r0 + m[1] * g0 + m[2] * b0).toFloat() * gain
            var g = (m[3] * r0 + m[4] * g0 + m[5] * b0).toFloat() * gain
            var b = (m[6] * r0 + m[7] * g0 + m[8] * b0).toFloat() * gain
            // Highlights and shadows by the pixel's brightness, keeping its colour.
            val lum = max(0.0001f, 0.2126f * r + 0.7152f * g + 0.0722f * b)
            val k = Dng.tone(lum, settings) / lum
            r *= k; g *= k; b *= k
            out[x * 3] = Dng.encode(r)
            out[x * 3 + 1] = Dng.encode(g)
            out[x * 3 + 2] = Dng.encode(b)
        }
    }
}

/** Camera colours (balanced: a channel clipped in the camera reaches its multiplier) of the developed picture, row by row. */
internal interface CameraRows {
    fun row(y: Int, out: FloatArray)
}

/** An RGB image (demosaiced by the camera, or reduced while read), sampled every [step] pixels. */
private class LinearRows(private val raw: RawImage, private val wb: FloatArray, private val step: Int, private val outW: Int) : CameraRows {
    private val line = ShortArray(raw.width * 3)
    private val black = raw.black[0]
    private val span = (raw.white - black).coerceAtLeast(1f)

    override fun row(y: Int, out: FloatArray) {
        raw.data.duplicate().apply { position(y * step * raw.width * 3) }.get(line)
        for (x in 0 until outW) {
            val i = x * step * 3
            for (c in 0 until 3) {
                val n = (((line[i + c].toInt() and 0xFFFF) - black) / span).coerceIn(0f, 1f)
                out[x * 3 + c] = n * wb[c]
            }
        }
    }
}

/** A mosaic reduced by [step]: each output pixel the 2×2 block at its corner. */
private class BlockRows(private val raw: RawImage, private val wb: FloatArray, private val step: Int, private val outW: Int) : CameraRows {
    private val top = ShortArray(raw.width)
    private val bottom = ShortArray(raw.width)
    private val at = IntArray(4) { raw.cfa.getOrElse(it) { 1 } }
    private val span = FloatArray(4) { (raw.white - raw.black[it]).coerceAtLeast(1f) }
    private val sum = FloatArray(3)
    private val count = IntArray(3)

    override fun row(y: Int, out: FloatArray) {
        val y0 = ((y * step) and 1.inv()).coerceAtMost(raw.height - 2)
        raw.data.duplicate().apply { position(y0 * raw.width) }.get(top)
        raw.data.duplicate().apply { position((y0 + 1) * raw.width) }.get(bottom)
        for (ox in 0 until outW) {
            val x = ((ox * step) and 1.inv()).coerceAtMost(raw.width - 2)
            sum.fill(0f)
            count.fill(0)
            for (k in 0 until 4) {
                val v = (if (k < 2) top else bottom)[x + (k and 1)].toInt() and 0xFFFF
                val c = at[k]
                sum[c] += ((v - raw.black[k]) / span[k]).coerceIn(0f, 1f)
                count[c]++
            }
            for (c in 0 until 3) out[ox * 3 + c] = sum[c] / max(1, count[c]) * wb[c]
        }
    }
}

/**
 * Bayer demosaicing at full size, in bands of rows: green is interpolated along the direction
 * with the smaller gradient (Hamilton–Adams, blended by how clear the direction is), red and blue
 * from their differences to green, so edges keep their colour without zippers. The mosaic is
 * balanced first, so neutral areas stay neutral.
 */
internal class Demosaic(private val raw: RawImage, private val wb: FloatArray) : CameraRows {
    private val w = raw.width
    private val h = raw.height
    private val colour = IntArray(4) { raw.cfa[it] }
    private val span = FloatArray(4) { (raw.white - raw.black[it]).coerceAtLeast(1f) }

    // Balanced mosaic rows [start - PAD, start + BAND + PAD), green rows [start - 1, start + BAND + 1),
    // and the band's colours.
    private val m = FloatArray((BAND + 2 * PAD) * w)
    private val g = FloatArray((BAND + 2) * w)
    private val rgb = FloatArray(BAND * w * 3)
    private val line = ShortArray(w)
    private var start = -1

    override fun row(y: Int, out: FloatArray) {
        if (start < 0 || y < start || y >= start + BAND) compute(y - y % BAND)
        System.arraycopy(rgb, (y - start) * w * 3, out, 0, w * 3)
    }

    private fun reflect(i: Int, n: Int): Int = when {
        i < 0 -> -i
        i >= n -> 2 * (n - 1) - i
        else -> i
    }

    private fun colourAt(x: Int, y: Int) = colour[((y and 1) shl 1) or (x and 1)]

    private fun compute(bandStart: Int) {
        start = bandStart
        // The balanced mosaic.
        for (r in 0 until BAND + 2 * PAD) {
            val y = bandStart - PAD + r
            val yy = reflect(y, h).coerceIn(0, h - 1)
            raw.data.duplicate().apply { position(yy * w) }.get(line)
            val base = r * w
            for (x in 0 until w) {
                val k = ((yy and 1) shl 1) or (x and 1)
                val n = (((line[x].toInt() and 0xFFFF) - raw.black[k]) / span[k]).coerceIn(0f, 1f)
                m[base + x] = n * wb[colour[k]]
            }
        }
        // Green everywhere.
        for (gr in 0 until BAND + 2) {
            val y = bandStart - 1 + gr
            val r = gr - 1 + PAD
            for (x in 0 until w) {
                val c = m[r * w + x]
                g[gr * w + x] = if (colourAt(x, y) == 1) c else {
                    val gw = mAt(x - 1, r)
                    val ge = mAt(x + 1, r)
                    val gn = mAt(x, r - 1)
                    val gs = mAt(x, r + 1)
                    val lapH = 2 * c - mAt(x - 2, r) - mAt(x + 2, r)
                    val lapV = 2 * c - mAt(x, r - 2) - mAt(x, r + 2)
                    val dH = abs(gw - ge) + abs(lapH)
                    val dV = abs(gn - gs) + abs(lapV)
                    val eH = (gw + ge) / 2 + lapH / 4
                    val eV = (gn + gs) / 2 + lapV / 4
                    val wH = 1f / (EPS + dH * dH)
                    val wV = 1f / (EPS + dV * dV)
                    ((wH * eH + wV * eV) / (wH + wV)).coerceAtLeast(0f)
                }
            }
        }
        // Red and blue from their differences to green.
        for (yb in 0 until BAND) {
            val y = bandStart + yb
            if (y >= h) break
            val r = yb + PAD
            val gr = yb + 1
            val o = yb * w * 3
            for (x in 0 until w) {
                val c = colourAt(x, y)
                val gc = g[gr * w + x]
                var red: Float
                var blue: Float
                if (c == 1) {
                    val dH = (mAt(x - 1, r) - gAt(x - 1, gr) + mAt(x + 1, r) - gAt(x + 1, gr)) / 2
                    val dV = (mAt(x, r - 1) - gAt(x, gr - 1) + mAt(x, r + 1) - gAt(x, gr + 1)) / 2
                    if (colourAt(x + 1, y) == 0) { red = gc + dH; blue = gc + dV } else { blue = gc + dH; red = gc + dV }
                } else {
                    val own = m[r * w + x]
                    val other = gc + (
                        mAt(x - 1, r - 1) - gAt(x - 1, gr - 1) + mAt(x + 1, r - 1) - gAt(x + 1, gr - 1) +
                            mAt(x - 1, r + 1) - gAt(x - 1, gr + 1) + mAt(x + 1, r + 1) - gAt(x + 1, gr + 1)
                        ) / 4
                    if (c == 0) { red = own; blue = other } else { blue = own; red = other }
                }
                rgb[o + x * 3] = red.coerceAtLeast(0f)
                rgb[o + x * 3 + 1] = gc
                rgb[o + x * 3 + 2] = blue.coerceAtLeast(0f)
            }
        }
    }

    private fun mAt(x: Int, r: Int) = m[r * w + reflect(x, w)]

    private fun gAt(x: Int, gr: Int) = g[gr * w + reflect(x, w)]

    companion object {
        private const val BAND = 32
        private const val PAD = 3
        private const val EPS = 1e-6f

        /** A 2×2 repeat with the two greens on a diagonal, red and blue on the other. */
        fun isBayer(cfa: IntArray): Boolean = cfa.size >= 4 && (
            (cfa[0] == 1 && cfa[3] == 1 && setOf(cfa[1], cfa[2]) == setOf(0, 2)) ||
                (cfa[1] == 1 && cfa[2] == 1 && setOf(cfa[0], cfa[3]) == setOf(0, 2))
            )
    }
}
