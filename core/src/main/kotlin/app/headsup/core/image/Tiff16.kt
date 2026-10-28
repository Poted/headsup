package app.headsup.core.image

import java.io.OutputStream

/**
 * A baseline TIFF with 16 bits per colour (RGB, uncompressed, one strip, little-endian), written
 * row by row so a large picture never needs a second copy in memory. Opens in Lightroom, Photoshop,
 * DaVinci and most viewers.
 */
object Tiff16 {
    private const val TAGS = 11

    /**
     * Writes a [width]×[height] picture; [row] fills one row's colours (R, G, B per pixel, 0..65535)
     * into the array it is given, for rows 0 to height − 1 in order.
     */
    fun write(out: OutputStream, width: Int, height: Int, software: String = "Sweet Spot", row: (y: Int, rgb: IntArray) -> Unit) {
        require(width > 0 && height > 0)
        val dataBytes = width.toLong() * height * 6
        require(dataBytes < 0xFFFF_FFFFL) { "Too big for a TIFF" }
        val ifdOffset = 8
        val ifdSize = 2 + TAGS * 12 + 4
        val bitsOffset = ifdOffset + ifdSize
        val softwareBytes = (software + "\u0000").toByteArray(Charsets.US_ASCII)
        val softwareOffset = bitsOffset + 6
        val dataOffset = (softwareOffset + softwareBytes.size + 1) and 1.inv()

        val head = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { head.write(v and 0xFF); head.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (i in 0 until 4) head.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun tag(id: Int, type: Int, count: Long, value: Long) {
            u16(id); u16(type); u32(count)
            // Short values sit in the first two bytes of the field.
            if (type == 3 && count == 1L) { u16(value.toInt()); u16(0) } else u32(value)
        }
        head.write('I'.code); head.write('I'.code); u16(42); u32(ifdOffset.toLong())
        u16(TAGS)
        tag(256, 4, 1, width.toLong()) // ImageWidth
        tag(257, 4, 1, height.toLong()) // ImageLength
        tag(258, 3, 3, bitsOffset.toLong()) // BitsPerSample: 16, 16, 16
        tag(259, 3, 1, 1) // Compression: none
        tag(262, 3, 1, 2) // Photometric: RGB
        tag(273, 4, 1, dataOffset.toLong()) // StripOffsets
        tag(277, 3, 1, 3) // SamplesPerPixel
        tag(278, 4, 1, height.toLong()) // RowsPerStrip
        tag(279, 4, 1, dataBytes) // StripByteCounts
        tag(284, 3, 1, 1) // PlanarConfiguration: chunky
        tag(305, 2, softwareBytes.size.toLong(), softwareOffset.toLong()) // Software
        u32(0) // no next IFD
        u16(16); u16(16); u16(16)
        head.write(softwareBytes)
        while (head.size() < dataOffset) head.write(0)
        out.write(head.toByteArray())

        val rgb = IntArray(width * 3)
        val bytes = ByteArray(width * 6)
        for (y in 0 until height) {
            row(y, rgb)
            for (i in rgb.indices) {
                val v = rgb[i].coerceIn(0, 65535)
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            out.write(bytes)
        }
    }
}
