package app.headsup.core.raw

/**
 * Minimal TIFF reader for DNG files: the directories (IFDs) with their tags, including the
 * sub-directories DNG keeps the raw image in. Values are read on demand from the file's bytes.
 */
class Tiff(val bytes: ByteArray) {
    val littleEndian: Boolean = when {
        bytes.size >= 8 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
        bytes.size >= 8 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
        else -> throw RawException("Not a TIFF/DNG file")
    }

    /** IFD0 and every directory reachable from it (next IFDs and SubIFDs), in file order of discovery. */
    val directories: List<Ifd> by lazy {
        val found = ArrayList<Ifd>()
        val seen = HashSet<Long>()
        fun walk(offset: Long, depth: Int) {
            var at = offset
            while (at > 0 && at < bytes.size && seen.add(at) && depth < 4) {
                val ifd = readIfd(at)
                found += ifd
                ifd.longs(Tags.SUB_IFDS)?.forEach { walk(it, depth + 1) }
                at = ifd.next
            }
        }
        walk(u32(4), 0)
        found
    }

    fun u16(at: Long): Int {
        val i = at.toInt()
        val a = bytes[i].toInt() and 0xFF
        val b = bytes[i + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    fun u32(at: Long): Long {
        val lo = u16(if (littleEndian) at else at + 2).toLong()
        val hi = u16(if (littleEndian) at + 2 else at).toLong()
        return (hi shl 16) or lo
    }

    private fun readIfd(at: Long): Ifd {
        val count = u16(at)
        val entries = HashMap<Int, Entry>()
        for (i in 0 until count) {
            val e = at + 2 + i * 12L
            if (e + 12 > bytes.size) break
            val tag = u16(e)
            val type = u16(e + 2)
            val n = u32(e + 4)
            val size = TYPE_SIZES.getOrElse(type) { 1 } * n
            val valueAt = if (size <= 4) e + 8 else u32(e + 8)
            entries[tag] = Entry(tag, type, n.toInt(), valueAt)
        }
        val nextAt = at + 2 + count * 12L
        val next = if (nextAt + 4 <= bytes.size) u32(nextAt) else 0L
        return Ifd(this, entries, next)
    }

    class Entry(val tag: Int, val type: Int, val count: Int, val valueAt: Long)

    class Ifd(private val tiff: Tiff, val entries: Map<Int, Entry>, val next: Long) {
        fun has(tag: Int) = tag in entries

        /** Integer values (BYTE, SHORT, LONG and their signed forms). */
        fun longs(tag: Int): LongArray? {
            val e = entries[tag] ?: return null
            return LongArray(e.count) { i ->
                when (e.type) {
                    1, 2, 7 -> (tiff.bytes[(e.valueAt + i).toInt()].toLong() and 0xFF)
                    6 -> tiff.bytes[(e.valueAt + i).toInt()].toLong()
                    3 -> tiff.u16(e.valueAt + i * 2L).toLong()
                    8 -> tiff.u16(e.valueAt + i * 2L).toShort().toLong()
                    4, 13 -> tiff.u32(e.valueAt + i * 4L)
                    9 -> tiff.u32(e.valueAt + i * 4L).toInt().toLong()
                    else -> tiff.u32(e.valueAt + i * 4L)
                }
            }
        }

        fun long(tag: Int): Long? = longs(tag)?.firstOrNull()

        /** Numeric values of any type as doubles (rationals divided out). */
        fun doubles(tag: Int): DoubleArray? {
            val e = entries[tag] ?: return null
            return when (e.type) {
                5 -> DoubleArray(e.count) { i -> tiff.u32(e.valueAt + i * 8L).toDouble() / tiff.u32(e.valueAt + i * 8L + 4).coerceAtLeast(1) }
                10 -> DoubleArray(e.count) { i ->
                    tiff.u32(e.valueAt + i * 8L).toInt().toDouble() / tiff.u32(e.valueAt + i * 8L + 4).toInt().let { if (it == 0) 1 else it }
                }
                11 -> DoubleArray(e.count) { i -> java.lang.Float.intBitsToFloat(tiff.u32(e.valueAt + i * 4L).toInt()).toDouble() }
                12 -> DoubleArray(e.count) { i ->
                    val lo = tiff.u32(e.valueAt + i * 8L + if (tiff.littleEndian) 0 else 4)
                    val hi = tiff.u32(e.valueAt + i * 8L + if (tiff.littleEndian) 4 else 0)
                    java.lang.Double.longBitsToDouble((hi shl 32) or lo)
                }
                else -> longs(tag)?.let { l -> DoubleArray(l.size) { l[it].toDouble() } }
            }
        }

        /** The raw bytes of a value (e.g. an opcode list). */
        fun bytes(tag: Int): ByteArray? {
            val e = entries[tag] ?: return null
            val start = e.valueAt.toInt()
            return tiff.bytes.copyOfRange(start, start + e.count)
        }

        fun string(tag: Int): String? {
            val e = entries[tag] ?: return null
            val start = e.valueAt.toInt()
            return String(tiff.bytes, start, e.count.coerceAtMost(tiff.bytes.size - start), Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        }
    }

    companion object {
        private val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)
    }
}

object Tags {
    const val NEW_SUBFILE_TYPE = 254
    const val IMAGE_WIDTH = 256
    const val IMAGE_LENGTH = 257
    const val BITS_PER_SAMPLE = 258
    const val COMPRESSION = 259
    const val PHOTOMETRIC = 262
    const val MAKE = 271
    const val MODEL = 272
    const val STRIP_OFFSETS = 273
    const val ORIENTATION = 274
    const val SAMPLES_PER_PIXEL = 277
    const val ROWS_PER_STRIP = 278
    const val STRIP_BYTE_COUNTS = 279
    const val JPEG_INTERCHANGE_FORMAT = 513
    const val JPEG_INTERCHANGE_FORMAT_LENGTH = 514
    const val TILE_WIDTH = 322
    const val TILE_LENGTH = 323
    const val TILE_OFFSETS = 324
    const val TILE_BYTE_COUNTS = 325
    const val SUB_IFDS = 330
    const val CFA_REPEAT_PATTERN_DIM = 33421
    const val CFA_PATTERN = 33422
    const val DNG_VERSION = 50706
    const val UNIQUE_CAMERA_MODEL = 50708
    const val LINEARIZATION_TABLE = 50712
    const val BLACK_LEVEL_REPEAT_DIM = 50713
    const val BLACK_LEVEL = 50714
    const val WHITE_LEVEL = 50717
    const val DEFAULT_CROP_ORIGIN = 50719
    const val DEFAULT_CROP_SIZE = 50720
    const val COLOR_MATRIX_1 = 50721
    const val COLOR_MATRIX_2 = 50722
    const val AS_SHOT_NEUTRAL = 50728
    const val BASELINE_EXPOSURE = 50730
    const val CALIBRATION_ILLUMINANT_1 = 50778
    const val CALIBRATION_ILLUMINANT_2 = 50779
    const val ACTIVE_AREA = 50829
    const val OPCODE_LIST_2 = 51009
    const val FORWARD_MATRIX_1 = 50964
    const val FORWARD_MATRIX_2 = 50965
    const val BASELINE_EXPOSURE_OFFSET = 51109
    const val PROFILE_HUE_SAT_MAP_DATA_1 = 50938
    const val PROFILE_TONE_CURVE = 50940
    const val PROFILE_LOOK_TABLE_DATA = 50982
    const val OPCODE_LIST_3 = 51022

    const val PHOTOMETRIC_CFA = 32803
    const val PHOTOMETRIC_LINEAR_RAW = 34892
    const val COMPRESSION_NONE = 1
    const val COMPRESSION_LOSSLESS_JPEG = 7
    const val COMPRESSION_JPEG_XL = 52546
    const val ILLUMINANT_D65 = 21
}

class RawException(message: String) : Exception(message)
