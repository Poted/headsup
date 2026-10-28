package app.headsup.core.image

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class Tiff16Test {
    @Test
    fun `header, tags and pixels are where a reader looks`() {
        val out = java.io.ByteArrayOutputStream()
        Tiff16.write(out, 3, 2) { y, rgb -> for (x in 0 until 3) { rgb[x * 3] = 1000 * x + y; rgb[x * 3 + 1] = 65535; rgb[x * 3 + 2] = 0 } }
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('I'.code.toByte(), b.get(0))
        assertEquals(42, b.getShort(2).toInt())
        val ifd = b.getInt(4)
        val count = b.getShort(ifd).toInt()
        fun tag(id: Int): Int {
            for (i in 0 until count) {
                val at = ifd + 2 + i * 12
                if (b.getShort(at).toInt() == id) {
                    val type = b.getShort(at + 2).toInt()
                    return if (type == 3) b.getShort(at + 8).toInt() and 0xFFFF else b.getInt(at + 8)
                }
            }
            error("no tag $id")
        }
        assertEquals(3, tag(256))
        assertEquals(2, tag(257))
        assertEquals(2, tag(262))
        assertEquals(3 * 2 * 6, tag(279))
        val bits = tag(258)
        assertEquals(16, b.getShort(bits).toInt())
        val data = tag(273)
        // Row 1, pixel 2: red 2001, green 65535, blue 0.
        val p = data + (1 * 3 + 2) * 6
        assertEquals(2001, b.getShort(p).toInt() and 0xFFFF)
        assertEquals(65535, b.getShort(p + 2).toInt() and 0xFFFF)
        assertEquals(0, b.getShort(p + 4).toInt())
        assertEquals(data + 3 * 2 * 6, out.size())
    }
}
