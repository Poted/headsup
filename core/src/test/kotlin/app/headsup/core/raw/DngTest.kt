package app.headsup.core.raw

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DngTest {
    /** Lossless JPEG encoder (predictor 1, one Huffman table), only for building test files. */
    private fun encodeLj92(samples: IntArray, width: Int, height: Int, comps: Int, precision: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        u8(0xFF); u8(0xD8)
        // Huffman table: 17 categories, all with 5-bit codes.
        u8(0xFF); u8(0xC4); u16(2 + 1 + 16 + 17); u8(0x00)
        for (l in 1..16) u8(if (l == 5) 17 else 0)
        for (s in 0..16) u8(s)
        u8(0xFF); u8(0xC3); u16(8 + 3 * comps); u8(precision); u16(height); u16(width); u8(comps)
        for (c in 0 until comps) { u8(c + 1); u8(0x11); u8(0) }
        u8(0xFF); u8(0xDA); u16(6 + 2 * comps); u8(comps)
        for (c in 0 until comps) { u8(c + 1); u8(0x00) }
        u8(1); u8(0); u8(0)
        // Bits.
        var acc = 0L
        var n = 0
        fun put(value: Int, bits: Int) {
            if (bits == 0) return
            acc = (acc shl bits) or (value.toLong() and ((1L shl bits) - 1))
            n += bits
            while (n >= 8) {
                val b = ((acc shr (n - 8)) and 0xFF).toInt()
                u8(b)
                if (b == 0xFF) u8(0)
                n -= 8
            }
        }
        val row = width * comps
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until comps) {
            val i = y * row + x * comps + c
            val pred = when {
                x == 0 && y == 0 -> 1 shl (precision - 1)
                y == 0 -> samples[i - comps]
                x == 0 -> samples[i - row]
                else -> samples[i - comps]
            }
            var diff = (samples[i] - pred) and 0xFFFF
            if (diff >= 32768) diff -= 65536
            val ssss = if (diff == 0) 0 else 32 - Integer.numberOfLeadingZeros(abs(diff))
            put(ssss, 5)
            if (ssss in 1..15) put(if (diff < 0) diff - 1 else diff, ssss)
        }
        if (n > 0) put(0x7F, 8 - n)
        u8(0xFF); u8(0xD9)
        return out.toByteArray()
    }

    /** A little-endian DNG: IFD0 with colour data, one SubIFD with a CFA tile in lossless JPEG. */
    private fun buildDng(width: Int, height: Int, raw: IntArray, comps: Int, rowsPerJpegRow: Int = 1): ByteArray {
        val jpeg = encodeLj92(raw, width * rowsPerJpegRow / comps, height / rowsPerJpegRow, comps)
        val out = ByteArrayOutputStream()
        val buf = java.nio.ByteBuffer.allocate(4096 + jpeg.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        // Extra data area after the IFDs.
        val dataAt = 1024
        val extra = java.nio.ByteBuffer.allocate(2048).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun rationals(vararg v: Double): Int {
            val at = dataAt + extra.position()
            for (x in v) { extra.putInt((x * 10000).toInt()); extra.putInt(10000) }
            return at
        }
        val cm = rationals(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val neutral = rationals(0.5, 1.0, 0.5)
        val subIfdAt = 512
        val jpegAt = dataAt + 2048
        fun entry(tag: Int, type: Int, count: Int, value: Int) { buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        fun shortEntry(tag: Int, value: Int) { buf.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0) }
        // IFD0 (tags in ascending order).
        buf.position(8)
        val ifd0 = listOf<() -> Unit>(
            { entry(Tags.NEW_SUBFILE_TYPE, 4, 1, 1) },
            { entry(Tags.SUB_IFDS, 4, 1, subIfdAt) },
            { entry(Tags.COLOR_MATRIX_1, 10, 9, cm) },
            { entry(Tags.AS_SHOT_NEUTRAL, 5, 3, neutral) },
            { shortEntry(Tags.CALIBRATION_ILLUMINANT_1, Tags.ILLUMINANT_D65) },
        )
        buf.putShort(ifd0.size.toShort()); ifd0.forEach { it() }; buf.putInt(0)
        buf.position(subIfdAt)
        val sub = listOf<() -> Unit>(
            { entry(Tags.NEW_SUBFILE_TYPE, 4, 1, 0) },
            { entry(Tags.IMAGE_WIDTH, 4, 1, width) },
            { entry(Tags.IMAGE_LENGTH, 4, 1, height) },
            { shortEntry(Tags.BITS_PER_SAMPLE, 16) },
            { shortEntry(Tags.COMPRESSION, Tags.COMPRESSION_LOSSLESS_JPEG) },
            { shortEntry(Tags.PHOTOMETRIC, Tags.PHOTOMETRIC_CFA) },
            { entry(Tags.TILE_WIDTH, 4, 1, width) },
            { entry(Tags.TILE_LENGTH, 4, 1, height) },
            { entry(Tags.TILE_OFFSETS, 4, 1, jpegAt) },
            { entry(Tags.TILE_BYTE_COUNTS, 4, 1, jpeg.size) },
            { buf.putShort(Tags.CFA_PATTERN.toShort()).putShort(1).putInt(4).put(0).put(1).put(1).put(2) },
            { shortEntry(Tags.BLACK_LEVEL, 1000) },
            { shortEntry(Tags.WHITE_LEVEL, 17000) },
        )
        buf.putShort(sub.size.toShort()); sub.forEach { it() }; buf.putInt(0)
        buf.position(dataAt); buf.put(extra.array())
        buf.position(jpegAt); buf.put(jpeg)
        out.write(buf.array(), 0, jpegAt + jpeg.size)
        return out.toByteArray()
    }

    private fun mosaic(width: Int, height: Int) = IntArray(width * height) { i -> 1000 + (i * 37 % 16000) }

    @Test
    fun `lossless jpeg round trip`() {
        val samples = IntArray(8 * 5) { (it * 977 + 13) % 65536 }
        val f = LosslessJpeg.decode(encodeLj92(samples, 8, 5, 1))
        assertEquals(8, f.width)
        assertContentEquals(samples, IntArray(samples.size) { f.samples[it].toInt() and 0xFFFF })
    }

    @Test
    fun `reads a lossless jpeg dng with columns packed as two components`() {
        val raw = mosaic(16, 6)
        val image = Dng.read(buildDng(16, 6, raw, comps = 2))
        assertEquals(16, image.width)
        assertEquals(6, image.height)
        assertContentEquals(raw, IntArray(raw.size) { image.data[it].toInt() and 0xFFFF })
        assertContentEquals(intArrayOf(0, 1, 1, 2), image.cfa)
        assertEquals(1000f, image.black[0])
        assertEquals(17000f, image.white)
        assertEquals(2.0, 1.0 / image.asShotNeutral[0], 1e-3)
    }

    @Test
    fun `reads a dng whose jpeg rows hold two image rows`() {
        val raw = mosaic(8, 6)
        val image = Dng.read(buildDng(8, 6, raw, comps = 2, rowsPerJpegRow = 2))
        assertContentEquals(raw, IntArray(raw.size) { image.data[it].toInt() and 0xFFFF })
    }

    @Test
    fun `a neutral grey develops grey`() {
        // Red and blue at half of green: the neutral (0.5, 1, 0.5) makes them equal.
        val w = 8
        val h = 8
        val raw = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val green = (x + y) % 2 == 1
            1000 + if (green) 4000 else 2000
        }
        val image = Dng.read(buildDng(w, h, raw, comps = 1))
        val out = Dng.develop(image)
        val p = out.pixels[0]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue(abs(r - g) <= 2 && abs(b - g) <= 2, "grey expected, got $r $g $b")
        assertEquals(4, out.width)
    }

    @Test
    fun `highlights roll off instead of clipping`() {
        assertTrue(Dng.tone(2f, RawDevelop()) < 1f)
        assertTrue(Dng.tone(2f, RawDevelop()) > Dng.tone(1f, RawDevelop()))
        assertTrue(Dng.tone(0.05f, RawDevelop(shadows = 1f)) > 0.05f)
    }

    @Test
    fun `jpeg xl dngs are reported as such`() {
        val bytes = buildDng(8, 2, IntArray(16) { 1000 }, 1)
        // Patch the compression tag value to JPEG XL.
        val tiff = Tiff(bytes)
        val raw = tiff.directories[1]
        val at = raw.entries.getValue(Tags.COMPRESSION).valueAt.toInt()
        bytes[at] = (Tags.COMPRESSION_JPEG_XL and 0xFF).toByte()
        bytes[at + 1] = (Tags.COMPRESSION_JPEG_XL shr 8).toByte()
        val e = assertFailsWith<RawException> { Dng.read(bytes) }
        assertTrue(e.message!!.contains("JPEG XL"))
    }

    /** A DNG whose IFD0 holds a linear raw image in one JPEG XL strip (as Samsung Expert RAW writes). */
    private fun buildLinearJxlDng(jxl: ByteArray, width: Int, height: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(4096 + jxl.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        val dataAt = 1024
        val jxlAt = 2048
        fun entry(tag: Int, type: Int, count: Int, value: Int) { buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        fun shortEntry(tag: Int, value: Int) { buf.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0) }
        val entries = listOf<() -> Unit>(
            { entry(Tags.NEW_SUBFILE_TYPE, 4, 1, 0) },
            { entry(Tags.IMAGE_WIDTH, 4, 1, width) },
            { entry(Tags.IMAGE_LENGTH, 4, 1, height) },
            { shortEntry(Tags.BITS_PER_SAMPLE, 16) },
            { shortEntry(Tags.COMPRESSION, Tags.COMPRESSION_JPEG_XL) },
            { shortEntry(Tags.PHOTOMETRIC, Tags.PHOTOMETRIC_LINEAR_RAW) },
            { entry(Tags.STRIP_OFFSETS, 4, 1, jxlAt) },
            { shortEntry(Tags.SAMPLES_PER_PIXEL, 3) },
            { entry(Tags.ROWS_PER_STRIP, 4, 1, height) },
            { entry(Tags.STRIP_BYTE_COUNTS, 4, 1, jxl.size) },
            { entry(Tags.COLOR_MATRIX_1, 10, 9, dataAt) },
            { entry(Tags.AS_SHOT_NEUTRAL, 5, 3, dataAt + 72) },
            { shortEntry(Tags.CALIBRATION_ILLUMINANT_1, Tags.ILLUMINANT_D65) },
        )
        buf.putShort(entries.size.toShort()); entries.forEach { it() }; buf.putInt(0)
        buf.position(dataAt)
        for (v in doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) { buf.putInt((v * 10000).toInt()); buf.putInt(10000) }
        for (v in doubleArrayOf(1.0, 1.0, 1.0)) { buf.putInt(10000); buf.putInt(10000) }
        buf.position(jxlAt); buf.put(jxl)
        return buf.array().copyOf(jxlAt + jxl.size)
    }

    @Test
    fun `reads a linear dng compressed with jpeg xl`() {
        val jxl = javaClass.getResourceAsStream("/white.jxl")!!.readBytes()
        val probe = com.traneptora.jxlatte.JXLDecoder(java.io.ByteArrayInputStream(jxl)).decode()
        val image = Dng.read(buildLinearJxlDng(jxl, probe.width, probe.height))
        assertEquals(3, image.channels)
        assertEquals(probe.width, image.developedWidth)
        val out = Dng.develop(image)
        val p = out.pixels[out.pixels.size / 2]
        // A white picture stays (nearly) white and neutral.
        assertTrue(((p shr 8) and 0xFF) > 200, "got ${Integer.toHexString(p)}")
        assertTrue(abs(((p shr 16) and 0xFF) - (p and 0xFF)) <= 3)
    }
}
