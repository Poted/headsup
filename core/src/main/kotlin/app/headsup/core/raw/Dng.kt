package app.headsup.core.raw

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A DNG's raw image as stored: a colour filter mosaic (Bayer, one sample per pixel) with what is
 * needed to develop it: levels, the 2×2 filter pattern, the camera's colour matrix and the white
 * balance as shot.
 */
class RawImage(
    val width: Int,
    val height: Int,
    /** Samples row by row (unsigned 16-bit in shorts). */
    val data: ShortArray,
    /** Colour of each pixel of the 2×2 repeat, row by row: 0 red, 1 green, 2 blue. */
    val cfa: IntArray,
    /** Black level per position of the 2×2 repeat, and the white level. */
    val black: FloatArray,
    val white: Float,
    /** XYZ (D65) to camera colour matrix, row-major 3×3. */
    val colorMatrix: DoubleArray,
    /** Camera neutral (white) as shot, normalised to green = 1. */
    val asShotNeutral: DoubleArray,
    val baselineExposure: Double,
    val make: String,
    val model: String,
    /** TIFF orientation (1 upright, 3 upside down, 6 and 8 turned). */
    val orientation: Int = 1,
)

/** Settings of a raw development, applied to the raw data before the usual colour corrections. */
data class RawDevelop(
    /** Exposure in stops, on top of the camera's baseline. */
    val exposure: Float = 0f,
    /** -1..1: negative pulls bright areas down (recovers highlights), positive lifts them. */
    val highlights: Float = 0f,
    /** -1..1: positive lifts the darks. */
    val shadows: Float = 0f,
    /** -1..1: warmer / cooler than as shot, and green / magenta. */
    val temperature: Float = 0f,
    val tint: Float = 0f,
)

object Dng {
    /** Reads the raw image of a DNG (Bayer mosaic; uncompressed or lossless JPEG). */
    fun read(bytes: ByteArray): RawImage {
        val tiff = Tiff(bytes)
        val dirs = tiff.directories
        val ifd0 = dirs.first()
        val raw = dirs.firstOrNull { (it.long(Tags.NEW_SUBFILE_TYPE) ?: 0L) == 0L && it.long(Tags.PHOTOMETRIC)?.toInt() in setOf(Tags.PHOTOMETRIC_CFA, Tags.PHOTOMETRIC_LINEAR_RAW) }
            ?: throw RawException("No raw image in this DNG")
        val photometric = raw.long(Tags.PHOTOMETRIC)!!.toInt()
        val compression = raw.long(Tags.COMPRESSION)?.toInt() ?: 1
        if (compression == Tags.COMPRESSION_JPEG_XL) throw RawException("This DNG is compressed with JPEG XL (DNG 1.7, e.g. Samsung Expert RAW), not supported yet")
        if (photometric != Tags.PHOTOMETRIC_CFA) throw RawException("Only Bayer (CFA) raw images are supported so far")
        val width = raw.long(Tags.IMAGE_WIDTH)!!.toInt()
        val height = raw.long(Tags.IMAGE_LENGTH)!!.toInt()
        val bits = raw.long(Tags.BITS_PER_SAMPLE)?.toInt() ?: 16
        val data = ShortArray(width * height)

        // The image comes in tiles or strips; each is placed into the full picture.
        val tiled = raw.has(Tags.TILE_OFFSETS)
        val offsets = raw.longs(if (tiled) Tags.TILE_OFFSETS else Tags.STRIP_OFFSETS) ?: throw RawException("No image data")
        val counts = raw.longs(if (tiled) Tags.TILE_BYTE_COUNTS else Tags.STRIP_BYTE_COUNTS) ?: throw RawException("No image data")
        val tileW = if (tiled) raw.long(Tags.TILE_WIDTH)!!.toInt() else width
        val tileH = if (tiled) raw.long(Tags.TILE_LENGTH)!!.toInt() else (raw.long(Tags.ROWS_PER_STRIP)?.toInt() ?: height)
        val across = (width + tileW - 1) / tileW
        for (t in offsets.indices) {
            val x0 = (t % across) * tileW
            val y0 = (t / across) * tileH
            val off = offsets[t].toInt()
            val len = counts[t].toInt()
            when (compression) {
                Tags.COMPRESSION_LOSSLESS_JPEG -> {
                    val f = LosslessJpeg.decode(bytes, off, len)
                    // The JPEG frame may pack several columns into "components": samples in order fill the tile row by row.
                    val rowSamples = f.width * f.components
                    for (y in 0 until f.height) {
                        val py = y0 + y
                        if (py >= height) break
                        for (s in 0 until rowSamples) {
                            val px = x0 + s
                            if (px >= width || s >= tileW) break
                            data[py * width + px] = f.samples[y * rowSamples + s]
                        }
                    }
                }
                Tags.COMPRESSION_NONE -> {
                    if (bits != 16) throw RawException("Uncompressed raw with $bits bits is not supported yet")
                    var p = off
                    for (y in 0 until tileH) {
                        for (x in 0 until tileW) {
                            val px = x0 + x
                            val py = y0 + y
                            if (p + 1 >= bytes.size) break
                            if (px < width && py < height) data[py * width + px] = tiff.u16(p.toLong()).toShort()
                            p += 2
                        }
                    }
                }
                else -> throw RawException("Raw compression $compression is not supported")
            }
        }

        val pattern = raw.longs(Tags.CFA_PATTERN)?.map { it.toInt() }?.toIntArray() ?: intArrayOf(0, 1, 1, 2)
        val blackRaw = raw.doubles(Tags.BLACK_LEVEL) ?: doubleArrayOf(0.0)
        val black = FloatArray(4) { blackRaw[it % blackRaw.size].toFloat() }
        val white = (raw.doubles(Tags.WHITE_LEVEL)?.firstOrNull() ?: ((1 shl bits) - 1).toDouble()).toFloat()
        // The colour matrix for daylight (D65), whichever slot it is in.
        val ill1 = ifd0.long(Tags.CALIBRATION_ILLUMINANT_1)?.toInt()
        val ill2 = ifd0.long(Tags.CALIBRATION_ILLUMINANT_2)?.toInt()
        val cm1 = ifd0.doubles(Tags.COLOR_MATRIX_1)
        val cm2 = ifd0.doubles(Tags.COLOR_MATRIX_2)
        val matrix = when {
            ill2 == Tags.ILLUMINANT_D65 && cm2 != null -> cm2
            ill1 == Tags.ILLUMINANT_D65 && cm1 != null -> cm1
            else -> cm2 ?: cm1
        } ?: throw RawException("No colour matrix")
        val neutral = ifd0.doubles(Tags.AS_SHOT_NEUTRAL)?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        return RawImage(
            width, height, data, pattern, black, white, matrix.copyOf(9),
            doubleArrayOf(neutral[0] / neutral[1], 1.0, neutral[2] / neutral[1]),
            ifd0.doubles(Tags.BASELINE_EXPOSURE)?.firstOrNull() ?: 0.0,
            ifd0.string(Tags.MAKE).orEmpty(), ifd0.string(Tags.MODEL).orEmpty(),
            ifd0.long(Tags.ORIENTATION)?.toInt() ?: 1,
        )
    }

    /**
     * Develops [raw] into sRGB pixels (ARGB) at half size (each 2×2 filter block one pixel, so no
     * demosaicing artefacts), further reduced by [step]: levels, white balance, camera to sRGB
     * colours, exposure, highlights and shadows in linear light, then a gentle film-like curve.
     */
    fun develop(raw: RawImage, settings: RawDevelop = RawDevelop(), step: Int = 1): Developed {
        val s = step.coerceAtLeast(1)
        val outW = raw.width / 2 / s
        val outH = raw.height / 2 / s
        val pixels = IntArray(outW * outH)
        val rgbCam = cameraToSrgb(raw.colorMatrix)
        // White balance: the as-shot neutral becomes grey, then the user's warmth and tint.
        val warm = 2.0.pow(settings.temperature * 0.6)
        val tint = 2.0.pow(settings.tint * 0.4)
        val wb = floatArrayOf(
            (1.0 / raw.asShotNeutral[0] * warm).toFloat(),
            (1.0 / tint).toFloat(),
            (1.0 / raw.asShotNeutral[2] / warm).toFloat(),
        )
        val gain = 2.0.pow(raw.baselineExposure + settings.exposure).toFloat()
        // Where each colour sits in the 2×2 block.
        val at = IntArray(4) { raw.cfa.getOrElse(it) { 1 } }
        val span = FloatArray(4) { (raw.white - raw.black[it]).coerceAtLeast(1f) }
        val cam = FloatArray(3)
        for (oy in 0 until outH) {
            val y = oy * 2 * s
            for (ox in 0 until outW) {
                val x = ox * 2 * s
                cam[0] = 0f; cam[1] = 0f; cam[2] = 0f
                var greens = 0
                for (k in 0 until 4) {
                    val v = raw.data[(y + k / 2) * raw.width + x + k % 2].toInt() and 0xFFFF
                    val n = ((v - raw.black[k]) / span[k]).coerceIn(0f, 1f)
                    val c = at[k]
                    if (c == 1) greens++
                    cam[c] += n
                }
                if (greens > 1) cam[1] /= greens
                val r0 = cam[0] * wb[0]
                val g0 = cam[1] * wb[1]
                val b0 = cam[2] * wb[2]
                var r = (rgbCam[0] * r0 + rgbCam[1] * g0 + rgbCam[2] * b0).toFloat() * gain
                var g = (rgbCam[3] * r0 + rgbCam[4] * g0 + rgbCam[5] * b0).toFloat() * gain
                var b = (rgbCam[6] * r0 + rgbCam[7] * g0 + rgbCam[8] * b0).toFloat() * gain
                // Highlights and shadows by the pixel's brightness, keeping its colour.
                val lum = max(0.0001f, 0.2126f * r + 0.7152f * g + 0.0722f * b)
                val k = tone(lum, settings) / lum
                r *= k; g *= k; b *= k
                pixels[oy * outW + ox] = (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)
            }
        }
        return Developed(outW, outH, pixels)
    }

    class Developed(val width: Int, val height: Int, val pixels: IntArray)

    /** Brightness after shadows, highlights and a soft roll-off into white (no hard clipping). */
    internal fun tone(l: Float, s: RawDevelop): Float {
        var v = l
        if (s.shadows != 0f) {
            // Lifts (or deepens) the darks, fading out by mid grey.
            val w = (1f - min(1f, v / 0.25f)).let { it * it }
            v *= 2f.pow(s.shadows * 1.5f * w)
        }
        if (s.highlights != 0f) {
            val w = min(1f, max(0f, (v - 0.25f) / 0.75f))
            v *= 2f.pow(s.highlights * 1.2f * w)
        }
        // Shoulder: above 0.8 values approach 1 smoothly, so bright skies keep their detail.
        val knee = 0.8f
        if (v > knee) v = knee + (1f - knee) * (1f - exp(-(v - knee) / (1f - knee)))
        return v
    }

    /** Linear 0..1 to 8-bit sRGB with a slight S-curve (as raw developers do by default). */
    private fun encode(linear: Float): Int {
        val x = linear.coerceIn(0f, 1f)
        val srgb = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        val curved = srgb + 0.12f * (srgb - 0.5f) * (1f - kotlin.math.abs(2f * srgb - 1f))
        return (curved.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    /**
     * Camera (white balanced) to linear sRGB, from the XYZ→camera matrix: the camera's response to
     * sRGB primaries, rows normalised so white stays white, inverted (as dcraw does).
     */
    internal fun cameraToSrgb(xyzToCam: DoubleArray): DoubleArray {
        val xyzRgb = doubleArrayOf(
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041,
        )
        val camRgb = multiply(xyzToCam, xyzRgb)
        for (r in 0 until 3) {
            val sum = camRgb[r * 3] + camRgb[r * 3 + 1] + camRgb[r * 3 + 2]
            if (sum != 0.0) for (c in 0 until 3) camRgb[r * 3 + c] /= sum
        }
        return invert(camRgb)
    }

    internal fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    internal fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (det == 0.0) throw RawException("Colour matrix cannot be inverted")
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }
}
