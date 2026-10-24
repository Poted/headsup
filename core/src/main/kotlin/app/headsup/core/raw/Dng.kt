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
    /** Samples row by row (unsigned 16-bit in shorts), held wherever the reader allocated them. */
    val data: java.nio.ShortBuffer,
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
    /** 1: a colour filter mosaic; 3: RGB (demosaiced by the camera, or reduced while read), interleaved. */
    val channels: Int = 1,
    /** The DNG's opcode list 3 (corrections of the demosaiced picture), as stored. */
    val opcodes3: ByteArray? = null,
    /** White-balanced camera values to linear sRGB by the DNG colour model, or null for the simple one from [colorMatrix]. */
    val balancedToSrgb: DoubleArray? = null,
    /** What was found in the file and is applied, in a line (shown with the raw controls). */
    val info: String = "",
) {
    /** Vignetting and lens distortion corrections the camera wrote for this picture. */
    val corrections: List<Opcode> by lazy { Opcode.parse(opcodes3) }

    val warp: Opcode.WarpRectilinear? get() = corrections.filterIsInstance<Opcode.WarpRectilinear>().firstOrNull()
    /** Size of the developed picture before orientation (a mosaic is demosaiced to its full size). */
    val developedWidth: Int get() = width
    val developedHeight: Int get() = height
}

/** Gives memory for the image data; the app may place it outside the Java heap. */
fun interface Allocator {
    fun allocate(bytes: Long): java.nio.ByteBuffer

    companion object {
        val HEAP = Allocator { java.nio.ByteBuffer.allocate(Math.toIntExact(it)) }
    }
}

private fun Allocator.shorts(count: Long): java.nio.ShortBuffer = allocate(count * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()

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
    /** 0..1: sharpening of fine detail (a raw is soft before it), on the picture's brightness only. */
    val sharpen: Float = 0.3f,
    /** 0..1: colour noise removed (blotches of colour in the darks), keeping the brightness detail. */
    val colourNoise: Float = 0.25f,
) {
    /** These settings with the ones changed from [before] to [after] set as in [after]. */
    fun synced(before: RawDevelop, after: RawDevelop) = RawDevelop(
        exposure = if (after.exposure != before.exposure) after.exposure else exposure,
        highlights = if (after.highlights != before.highlights) after.highlights else highlights,
        shadows = if (after.shadows != before.shadows) after.shadows else shadows,
        temperature = if (after.temperature != before.temperature) after.temperature else temperature,
        tint = if (after.tint != before.tint) after.tint else tint,
        sharpen = if (after.sharpen != before.sharpen) after.sharpen else sharpen,
        colourNoise = if (after.colourNoise != before.colourNoise) after.colourNoise else colourNoise,
    )
}

/** A raw file read in parts, so a large one need not be held in memory whole. */
interface RawSource {
    val size: Long

    /** [length] bytes from [offset] (fewer at the end of the file). */
    fun read(offset: Long, length: Int): ByteArray

    /** The [length] bytes from [offset], read front to back. */
    fun stream(offset: Long, length: Long): java.io.InputStream
}

class BytesSource(private val bytes: ByteArray) : RawSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray =
        bytes.copyOfRange(offset.toInt().coerceIn(0, bytes.size), (offset + length).toInt().coerceIn(0, bytes.size))
    override fun stream(offset: Long, length: Long): java.io.InputStream =
        java.io.ByteArrayInputStream(bytes, offset.toInt().coerceIn(0, bytes.size), length.toInt().coerceAtLeast(0))
}

object Dng {
    /** The start of a file read for its directories. */
    private const val HEAD_BYTES = 8 shl 20

    /**
     * Size of the developed picture (upright) and whether it is a raw the app can develop, from
     * the directories only, so [head] can be just the start of the file; null when unsure.
     */
    fun probe(head: ByteArray): Pair<Int, Int>? = runCatching {
        val tiff = Tiff(head)
        val ifd0 = tiff.directories.first()
        val raw = tiff.directories.firstOrNull { (it.long(Tags.NEW_SUBFILE_TYPE) ?: 0L) == 0L && it.long(Tags.PHOTOMETRIC)?.toInt() in setOf(Tags.PHOTOMETRIC_CFA, Tags.PHOTOMETRIC_LINEAR_RAW) }
            ?: return@runCatching null
        val cfa = raw.long(Tags.PHOTOMETRIC)!!.toInt() == Tags.PHOTOMETRIC_CFA
        if (cfa && raw.long(Tags.COMPRESSION)?.toInt() == Tags.COMPRESSION_JPEG_XL) return@runCatching null
        val w = raw.long(Tags.IMAGE_WIDTH)!!.toInt().let { if (cfa) it / 2 else it }
        val h = raw.long(Tags.IMAGE_LENGTH)!!.toInt().let { if (cfa) it / 2 else it }
        val o = ifd0.long(Tags.ORIENTATION)?.toInt() ?: 1
        if (o == 6 || o == 8) h to w else w to h
    }.getOrNull()

    /**
     * Where the largest preview JPEG the camera stored in the file is (offset, length), from the
     * directories in [head]; null when there is none.
     */
    fun preview(head: ByteArray): Pair<Long, Int>? = runCatching {
        Tiff(head).directories.mapNotNull { d ->
            if ((d.long(Tags.NEW_SUBFILE_TYPE) ?: 0L) and 1L == 0L) return@mapNotNull null
            val compression = d.long(Tags.COMPRESSION)?.toInt()
            val jif = d.long(Tags.JPEG_INTERCHANGE_FORMAT)
            val jifLength = d.long(Tags.JPEG_INTERCHANGE_FORMAT_LENGTH)
            when {
                jif != null && jifLength != null && jifLength > 0 -> jif to jifLength.toInt()
                (compression == 6 || compression == Tags.COMPRESSION_LOSSLESS_JPEG) && d.long(Tags.PHOTOMETRIC)?.toInt() != Tags.PHOTOMETRIC_CFA -> {
                    val offsets = d.longs(Tags.STRIP_OFFSETS)
                    val counts = d.longs(Tags.STRIP_BYTE_COUNTS)
                    if (offsets?.size == 1 && counts?.size == 1 && counts[0] > 0) offsets[0] to counts[0].toInt() else null
                }
                else -> null
            }
        }.maxByOrNull { it.second }
    }.getOrNull()

    /** Reads the raw image of a DNG held in memory, at full (developed) size. */
    fun read(bytes: ByteArray, mosaic: Boolean = false): RawImage = read(BytesSource(bytes), mosaic = mosaic)

    /**
     * Reads the raw image of a DNG for development: a mosaic (Bayer; uncompressed or lossless
     * JPEG) as one colour pixel per 2×2 block, a linear raw (uncompressed or JPEG XL) as it is.
     * With [maxSide] the picture is reduced while it is read, to at most about that on its long
     * side, so only the reduced picture is ever held: the file is read in parts from [source].
     * The result holds camera colours normalised to 0..65535 (levels applied, not yet balanced).
     */
    fun read(
        source: RawSource,
        maxSide: Int = 0,
        mosaic: Boolean = false,
        alloc: Allocator = Allocator.HEAP,
        jxl: JxlDecoder? = null,
    ): RawImage {
        val layout = layoutOf(source)
        return if (layout.cfa) readMosaic(source, layout, maxSide, mosaic, alloc) else readLinear(source, layout, maxSide, alloc, jxl)
    }

    /** What is needed from the directories, read from the start of the file (the whole file if a value lies further). */
    private class Layout(
        val cfa: Boolean, val width: Int, val height: Int, val compression: Int, val bits: Int, val spp: Int,
        val offsets: LongArray, val counts: LongArray, val tileW: Int, val tileH: Int, val littleEndian: Boolean,
        val pattern: IntArray, val black: FloatArray, val white: Float, val blackLinear: Double, val whiteLinear: Double,
        val matrix: DoubleArray, val neutral: DoubleArray, val baseline: Double, val make: String, val model: String, val orientation: Int,
        /** Lens shading and similar gains on the raw values (opcode list 2). */
        val gainMaps: List<Opcode.GainMap>, val opcodes3: ByteArray?,
        val balancedToSrgb: DoubleArray?, val info: String,
    )

    private fun layoutOf(source: RawSource): Layout {
        val head = source.read(0, minOf(source.size, HEAD_BYTES.toLong()).toInt())
        return try {
            layoutOf(Tiff(head))
        } catch (e: IndexOutOfBoundsException) {
            if (head.size.toLong() >= source.size) throw RawException("Damaged DNG")
            layoutOf(Tiff(source.read(0, source.size.toInt())))
        }
    }

    private fun layoutOf(tiff: Tiff): Layout {
        val dirs = tiff.directories
        val ifd0 = dirs.first()
        val raw = dirs.firstOrNull { (it.long(Tags.NEW_SUBFILE_TYPE) ?: 0L) == 0L && it.long(Tags.PHOTOMETRIC)?.toInt() in setOf(Tags.PHOTOMETRIC_CFA, Tags.PHOTOMETRIC_LINEAR_RAW) }
            ?: throw RawException("No raw image in this DNG")
        val photometric = raw.long(Tags.PHOTOMETRIC)!!.toInt()
        val cfa = photometric == Tags.PHOTOMETRIC_CFA
        val compression = raw.long(Tags.COMPRESSION)?.toInt() ?: 1
        if (cfa && compression == Tags.COMPRESSION_JPEG_XL) throw RawException("A JPEG XL Bayer (mosaic) raw is not supported yet")
        val width = raw.long(Tags.IMAGE_WIDTH)!!.toInt()
        val height = raw.long(Tags.IMAGE_LENGTH)!!.toInt()
        val bits = raw.long(Tags.BITS_PER_SAMPLE)?.toInt() ?: 16
        val spp = raw.long(Tags.SAMPLES_PER_PIXEL)?.toInt() ?: if (cfa) 1 else 3
        if (!cfa && spp < 3) throw RawException("Linear raw with $spp channels is not supported")
        // The image comes in tiles or strips; each is placed into the full picture.
        val tiled = raw.has(Tags.TILE_OFFSETS)
        val offsets = raw.longs(if (tiled) Tags.TILE_OFFSETS else Tags.STRIP_OFFSETS) ?: throw RawException("No image data")
        val counts = raw.longs(if (tiled) Tags.TILE_BYTE_COUNTS else Tags.STRIP_BYTE_COUNTS) ?: throw RawException("No image data")
        val tileW = if (tiled) raw.long(Tags.TILE_WIDTH)!!.toInt() else width
        val tileH = if (tiled) raw.long(Tags.TILE_LENGTH)!!.toInt() else (raw.long(Tags.ROWS_PER_STRIP)?.toInt() ?: height)
        val pattern = raw.longs(Tags.CFA_PATTERN)?.map { it.toInt() }?.toIntArray() ?: intArrayOf(0, 1, 1, 2)
        val blackRaw = raw.doubles(Tags.BLACK_LEVEL) ?: doubleArrayOf(0.0)
        val black = FloatArray(4) { blackRaw[it % blackRaw.size].toFloat() }
        val whiteRaw = raw.doubles(Tags.WHITE_LEVEL)?.firstOrNull()
        val white = (whiteRaw ?: ((1 shl bits) - 1).toDouble()).toFloat()
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
        val balanced = doubleArrayOf(neutral[0] / neutral[1], 1.0, neutral[2] / neutral[1])
        val colour = runCatching {
            DngColour.transform(
                DngColour.Calibration(cm1, cm2, ifd0.doubles(Tags.FORWARD_MATRIX_1), ifd0.doubles(Tags.FORWARD_MATRIX_2), ill1, ill2),
                balanced,
            )
        }.getOrNull()
        // The profile's offset is part of the baseline, as Adobe's converters read it.
        val baseline = (ifd0.doubles(Tags.BASELINE_EXPOSURE)?.firstOrNull() ?: 0.0) + (ifd0.doubles(Tags.BASELINE_EXPOSURE_OFFSET)?.firstOrNull() ?: 0.0)
        val allGainMaps = Opcode.parse(raw.bytes(Tags.OPCODE_LIST_2)).filterIsInstance<Opcode.GainMap>()
        // Maps for a mosaic's positions mean nothing on a linear (RGB) image.
        val gainMaps = allGainMaps.filter { cfa || (it.rowPitch == 1 && it.colPitch == 1) }
        val opcodes3 = raw.bytes(Tags.OPCODE_LIST_3)
        val corrections = Opcode.parse(opcodes3)
        val info = listOfNotNull(
            when (compression) {
                Tags.COMPRESSION_JPEG_XL -> "JPEG XL"
                Tags.COMPRESSION_LOSSLESS_JPEG -> "lossless JPEG"
                Tags.COMPRESSION_NONE -> "uncompressed"
                else -> "compression $compression"
            } + if (cfa) " mosaic" else " linear",
            String.format(java.util.Locale.US, "baseline %+.2f EV", baseline),
            colour?.let { "white ${it.kelvin} K, ${if (it.forward) "forward matrix" else "colour matrix"}" },
            "lens shading ${gainMaps.size}/${allGainMaps.size}".takeIf { allGainMaps.isNotEmpty() },
            corrections.joinToString(", ") {
                when (it) {
                    is Opcode.WarpRectilinear -> "distortion (${it.planes} planes)"
                    is Opcode.FixVignetteRadial -> "vignetting"
                    is Opcode.GainMap -> "gain map"
                }
            }.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        return Layout(
            cfa, width, height, compression, bits, spp, offsets, counts, tileW, tileH, tiff.littleEndian,
            pattern, black, white, blackRaw.first(), whiteRaw ?: 65535.0,
            matrix.copyOf(9), balanced, baseline,
            ifd0.string(Tags.MAKE).orEmpty(), ifd0.string(Tags.MODEL).orEmpty(),
            ifd0.long(Tags.ORIENTATION)?.toInt() ?: 1,
            gainMaps, opcodes3, colour?.balancedToSrgb, info,
        )
    }

    /** The gain the opcode list 2 maps give pixel ([px], [py]) of plane [p]; 1 without any. */
    private fun Layout.gainAt(px: Int, py: Int, p: Int): Float {
        var g = 1f
        for (m in gainMaps) if (m.covers(py, px, p)) g *= m.gain(py, px, p, width, height)
        return g
    }

    /**
     * Collects samples into the developed picture, reduced by a whole step: each output pixel the
     * average of step × step developed pixels, per colour.
     */
    private class Reducer(devW: Int, devH: Int, maxSide: Int, private val shares: IntArray, private val alloc: Allocator) {
        val step = if (maxSide <= 0) 1 else ((maxOf(devW, devH) + maxSide - 1) / maxSide).coerceIn(1, 64)
        val width = devW / step
        val height = devH / step
        private val count = width.toLong() * height * 3
        private val sums = if (step > 1) alloc.allocate(count * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer() else null
        private val direct = if (step == 1) alloc.shorts(count) else null

        /** Adds [n] (0..1) to colour [c] of developed pixel ([dx], [dy]). */
        fun add(dx: Int, dy: Int, c: Int, n: Float) {
            val ox = dx / step
            val oy = dy / step
            if (ox >= width || oy >= height) return
            val v = (n.coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
            val i = (oy * width + ox) * 3 + c
            if (sums != null) sums.put(i, sums.get(i) + v) else direct!!.put(i, ((direct.get(i).toInt() and 0xFFFF) + v / shares[c]).toShort())
        }

        fun finish(): java.nio.ShortBuffer = direct ?: alloc.shorts(count).also { out ->
            val sums = sums!!
            for (i in 0 until count.toInt()) out.put(i, (sums.get(i) / (shares[i % 3] * step * step)).toShort())
        }
    }

    private fun image(l: Layout, r: Reducer, data: java.nio.ShortBuffer) = RawImage(
        r.width, r.height, data, intArrayOf(0, 1, 1, 2), FloatArray(4), 65535f, l.matrix, l.neutral,
        l.baseline, l.make, l.model, l.orientation, channels = 3, opcodes3 = l.opcodes3,
        balancedToSrgb = l.balancedToSrgb, info = l.info,
    )

    /** 16-bit samples read front to back in the file's byte order. */
    private class Samples(stream: java.io.InputStream, private val littleEndian: Boolean) {
        private val input = java.io.BufferedInputStream(stream, 1 shl 16)
        fun next(): Int {
            val a = input.read()
            val b = input.read()
            if (a < 0 || b < 0) return -1
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }
    }

    /**
     * A mosaic: kept as it is for demosaicing at full size ([full]), or reduced while read, one
     * colour pixel per 2×2 block (then further by the step [maxSide] asks for).
     */
    private fun readMosaic(source: RawSource, l: Layout, maxSide: Int, full: Boolean, alloc: Allocator): RawImage {
        val at = IntArray(4) { l.pattern.getOrElse(it) { 1 }.coerceIn(0, 2) }
        val shares = IntArray(3) { c -> at.count { it == c }.coerceAtLeast(1) }
        val r = if (full) null else Reducer(l.width / 2, l.height / 2, maxSide, shares, alloc)
        val mosaic = if (full) alloc.shorts(l.width.toLong() * l.height) else null
        val span = FloatArray(4) { (l.white - l.black[it]).coerceAtLeast(1f) }
        val gains = l.gainMaps.isNotEmpty()
        fun put(px: Int, py: Int, v: Int) {
            if (px >= l.width || py >= l.height) return
            val k = ((py and 1) shl 1) or (px and 1)
            val g = if (gains) l.gainAt(px, py, 0) else 1f
            if (mosaic != null) {
                val gained = if (gains) (l.black[k] + (v - l.black[k]) * g).toInt().coerceIn(0, 65535) else v
                mosaic.put(py * l.width + px, gained.toShort())
                return
            }
            r!!.add(px shr 1, py shr 1, at[k], (v - l.black[k]) / span[k] * g)
        }
        val across = (l.width + l.tileW - 1) / l.tileW
        for (t in l.offsets.indices) {
            val x0 = (t % across) * l.tileW
            val y0 = (t / across) * l.tileH
            val stream = source.stream(l.offsets[t], l.counts[t])
            stream.use {
                when (l.compression) {
                    Tags.COMPRESSION_LOSSLESS_JPEG -> LosslessJpeg.decode(stream) { y, row ->
                        // The decoded samples fill the tile row by row, however the JPEG frame is shaped
                        // (cameras pack two columns as two components, or two rows into one JPEG row).
                        val first = y.toLong() * row.size
                        if (first >= l.tileW.toLong() * l.tileH) return@decode
                        var tx = (first % l.tileW).toInt()
                        var ty = (first / l.tileW).toInt()
                        for (s in row) {
                            if (ty >= l.tileH) break
                            put(x0 + tx, y0 + ty, s.toInt() and 0xFFFF)
                            if (++tx == l.tileW) { tx = 0; ty++ }
                        }
                    }
                    Tags.COMPRESSION_NONE -> {
                        if (l.bits != 16) throw RawException("Uncompressed raw with ${l.bits} bits is not supported yet")
                        val samples = Samples(stream, l.littleEndian)
                        loop@ for (y in 0 until l.tileH) for (x in 0 until l.tileW) {
                            val v = samples.next()
                            if (v < 0) break@loop
                            put(x0 + x, y0 + y, v)
                        }
                    }
                    else -> throw RawException("Raw compression ${l.compression} is not supported")
                }
            }
        }
        if (mosaic != null) {
            return RawImage(
                l.width, l.height, mosaic, at, l.black, l.white, l.matrix, l.neutral,
                l.baseline, l.make, l.model, l.orientation, channels = 1, opcodes3 = l.opcodes3,
                balancedToSrgb = l.balancedToSrgb, info = l.info,
            )
        }
        return image(l, r!!, r.finish())
    }

    /**
     * A linear (already demosaiced) raw image: three channels per pixel, in strips or tiles, each
     * compressed with JPEG XL (DNG 1.7) or stored as 16-bit samples.
     */
    private fun readLinear(source: RawSource, l: Layout, maxSide: Int, alloc: Allocator, jxl: JxlDecoder?): RawImage {
        val r = Reducer(l.width, l.height, maxSide, intArrayOf(1, 1, 1), alloc)
        val gains = l.gainMaps.isNotEmpty()
        fun add(px: Int, py: Int, c: Int, n: Float) = r.add(px, py, c, if (gains) n * l.gainAt(px, py, c) else n)
        val across = (l.width + l.tileW - 1) / l.tileW
        // JPEG XL data comes as 0..1 of its own range; the levels are scaled to it.
        val jxlBlack = (l.blackLinear / l.whiteLinear).toFloat()
        val jxlSpan = (1f - jxlBlack).coerceAtLeast(1e-6f)
        val black = l.black[0]
        val span = (l.white - black).coerceAtLeast(1f)
        for (t in l.offsets.indices) {
            val x0 = (t % across) * l.tileW
            val y0 = (t / across) * l.tileH
            source.stream(l.offsets[t], l.counts[t]).use { stream ->
                when {
                    l.compression == Tags.COMPRESSION_JPEG_XL && jxl != null -> {
                        // The native decoder: linear 16-bit RGB outside the Java heap, read row by row.
                        jxl.decode(source.read(l.offsets[t], l.counts[t].toInt())).use { image ->
                            val line = ShortArray(image.width * 3)
                            for (y in 0 until minOf(image.height, l.tileH)) {
                                val py = y0 + y
                                if (py >= l.height) break
                                image.rgb.duplicate().apply { position(y * image.width * 3) }.get(line)
                                for (x in 0 until minOf(image.width, l.tileW)) {
                                    val px = x0 + x
                                    if (px >= l.width) break
                                    for (c in 0 until 3) add(px, py, c, ((line[x * 3 + c].toInt() and 0xFFFF) / 65535f - jxlBlack) / jxlSpan)
                                }
                            }
                        }
                    }
                    l.compression == Tags.COMPRESSION_JPEG_XL -> {
                        var image = com.traneptora.jxlatte.JXLDecoder(stream).decode()
                        if (image.transfer != com.traneptora.jxlatte.color.ColorFlags.TF_LINEAR) {
                            image = image.transfer(com.traneptora.jxlatte.color.ColorFlags.TF_LINEAR, com.traneptora.jxlatte.JXLOptions.PEAK_DETECT_OFF)
                        }
                        val buffers = image.getBuffer(false)
                        for (c in 0 until 3) {
                            val b = buffers[minOf(c, buffers.size - 1)]
                            val max = ((1L shl image.getTaggedBitDepth(minOf(c, buffers.size - 1))) - 1).toFloat()
                            val ints = if (b.isInt) b.intBuffer else null
                            val floats = if (b.isFloat) b.floatBuffer else null
                            for (y in 0 until minOf(b.height, l.tileH)) {
                                val py = y0 + y
                                if (py >= l.height) break
                                for (x in 0 until minOf(b.width, l.tileW)) {
                                    val px = x0 + x
                                    if (px >= l.width) break
                                    val v = if (ints != null) ints[y][x] / max else floats!![y][x]
                                    add(px, py, c, (v - jxlBlack) / jxlSpan)
                                }
                            }
                        }
                    }
                    l.compression == Tags.COMPRESSION_NONE -> {
                        val samples = Samples(stream, l.littleEndian)
                        loop@ for (y in 0 until l.tileH) for (x in 0 until l.tileW) for (c in 0 until l.spp) {
                            val v = samples.next()
                            if (v < 0) break@loop
                            val px = x0 + x
                            val py = y0 + y
                            if (px < l.width && py < l.height && c < 3) add(px, py, c, (v - black) / span)
                        }
                    }
                    else -> throw RawException("Linear raw compression ${l.compression} is not supported")
                }
            }
        }
        return image(l, r, r.finish())
    }

    /**
     * Develops [raw] into sRGB pixels (ARGB, 8 bits), reduced by [step]; see [Developer] for the
     * steps. A mosaic is demosaiced at full size (step 1) or averaged per 2×2 block.
     */
    fun develop(raw: RawImage, settings: RawDevelop = RawDevelop(), step: Int = 1): Developed {
        val dev = Developer(raw, settings, step)
        val pixels = IntArray(dev.width * dev.height)
        val row = FloatArray(dev.width * 3)
        for (y in 0 until dev.height) {
            dev.row(y, row)
            for (x in 0 until dev.width) {
                fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                pixels[y * dev.width + x] = (0xFF shl 24) or (c(row[x * 3]) shl 16) or (c(row[x * 3 + 1]) shl 8) or c(row[x * 3 + 2])
            }
        }
        return Developed(dev.width, dev.height, pixels)
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

    /** Linear 0..1 to sRGB 0..1 with a slight S-curve (as raw developers do by default). */
    internal fun encode(linear: Float): Float {
        val x = linear.coerceIn(0f, 1f)
        val srgb = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (srgb + 0.12f * (srgb - 0.5f) * (1f - kotlin.math.abs(2f * srgb - 1f))).coerceIn(0f, 1f)
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
