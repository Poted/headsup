package app.headsup.core.raw

/**
 * Decoder for lossless JPEG (ITU T.81 process 14, "LJ92"), as DNG cameras store raw data: each
 * sample is predicted from its neighbours and the Huffman-coded difference added. Returns the
 * samples row by row, components interleaved, with the frame's width, height and components.
 */
object LosslessJpeg {
    class Frame(val width: Int, val height: Int, val components: Int, val precision: Int, val samples: ShortArray)

    /** Receives each decoded row ([samples]: the row's samples, components interleaved; valid during the call only). */
    fun interface RowSink {
        fun row(y: Int, samples: ShortArray)
    }

    private class Huffman(val maxCode: IntArray, val valPtr: IntArray, val minCode: IntArray, val values: IntArray) {
        // Fast path: the code's length and value for the next 9 bits.
        val lookup = IntArray(1 shl 9) { -1 }
    }

    /** The whole frame in memory (small images and tests). */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Frame {
        val rows = ArrayList<ShortArray>()
        val h = decode(java.io.ByteArrayInputStream(data, offset, length)) { _, row -> rows += row.copyOf() }
        val samples = ShortArray(rows.sumOf { it.size })
        var at = 0
        for (r in rows) { System.arraycopy(r, 0, samples, at, r.size); at += r.size }
        return Frame(h.width, h.height, h.components, h.precision, samples)
    }

    class Header(val width: Int, val height: Int, val components: Int, val precision: Int)

    /**
     * Decodes the stream front to back, handing each row to [sink] as it is done: only two rows
     * are held, however large the picture.
     */
    fun decode(stream: java.io.InputStream, sink: RowSink): Header {
        val input = Input(stream)
        fun u8(): Int { val b = input.read(); if (b < 0) throw RawException("Lossless JPEG: data ends early"); return b }
        fun u16(): Int = (u8() shl 8) or u8()

        if (u8() != 0xFF || u8() != 0xD8) throw RawException("Lossless JPEG: no start marker")
        val tables = arrayOfNulls<Huffman>(4)
        var width = 0
        var height = 0
        var precision = 0
        var components = 0
        val compIds = IntArray(4)
        val compTable = IntArray(4)
        var restart = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b != 0xFF) continue
            var marker = u8()
            while (marker == 0xFF) marker = u8()
            when (marker) {
                0xC3 -> {
                    val len = u16()
                    precision = u8()
                    height = u16()
                    width = u16()
                    components = u8()
                    if (components !in 1..4) throw RawException("Lossless JPEG: $components components")
                    for (c in 0 until components) {
                        compIds[c] = u8()
                        u8() // sampling factors (1x1 in DNG)
                        u8() // quantisation table (unused)
                    }
                    input.skip(len - 8 - 3 * components)
                }
                0xC4 -> {
                    var left = u16() - 2
                    while (left > 0) {
                        val id = u8() and 0x0F
                        val counts = IntArray(17)
                        var total = 0
                        for (l in 1..16) { counts[l] = u8(); total += counts[l] }
                        val values = IntArray(total) { u8() }
                        tables[id] = buildTable(counts, values)
                        left -= 17 + total
                    }
                }
                0xDD -> { u16(); restart = u16() }
                0xDA -> {
                    val len = u16()
                    val n = u8()
                    for (i in 0 until n) {
                        val id = u8()
                        val t = u8() shr 4
                        val c = (0 until components).firstOrNull { compIds[it] == id } ?: i
                        compTable[c] = t
                    }
                    val predictor = u8()
                    u8() // end of spectral selection (unused)
                    val pointTransform = u8() and 0x0F
                    input.skip(len - 6 - 2 * n)
                    if (width == 0 || height == 0 || components == 0) throw RawException("Lossless JPEG: no frame header")
                    val huffman = Array(components) { tables[compTable[it]] ?: throw RawException("Lossless JPEG: missing Huffman table") }
                    decodeScan(input, width, height, components, precision, predictor, pointTransform, restart, huffman, sink)
                    return Header(width, height, components, precision)
                }
                0xD9 -> break
                else -> input.skip(u16() - 2)
            }
        }
        throw RawException("Lossless JPEG: no scan")
    }

    /** Bytes read front to back through a buffer, with a look two bytes ahead. */
    private class Input(private val stream: java.io.InputStream) {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var len = 0

        /** The byte [ahead] places on (0 or 1), or -1 past the end. */
        fun peek(ahead: Int = 0): Int {
            if (pos + ahead >= len) refill()
            return if (pos + ahead < len) buf[pos + ahead].toInt() and 0xFF else -1
        }

        fun read(): Int {
            val b = peek()
            if (b >= 0) pos++
            return b
        }

        fun advance(n: Int) { pos = minOf(pos + n, len) }

        fun skip(n: Int) {
            var left = n
            while (left > 0) {
                if (pos >= len) { refill(); if (pos >= len) return }
                val k = minOf(left, len - pos)
                pos += k
                left -= k
            }
        }

        private fun refill() {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
            pos = 0
            while (len < buf.size) {
                val r = stream.read(buf, len, buf.size - len)
                if (r <= 0) break
                len += r
            }
        }
    }

    private fun buildTable(counts: IntArray, values: IntArray): Huffman {
        val maxCode = IntArray(18) { -1 }
        val minCode = IntArray(17)
        val valPtr = IntArray(17)
        var code = 0
        var k = 0
        for (l in 1..16) {
            valPtr[l] = k
            minCode[l] = code
            code += counts[l]
            k += counts[l]
            maxCode[l] = if (counts[l] > 0) code - 1 else -1
            code = code shl 1
        }
        maxCode[17] = Int.MAX_VALUE
        val h = Huffman(maxCode, valPtr, minCode, values)
        // Fill the 9-bit lookup for short codes.
        code = 0
        k = 0
        for (l in 1..9) {
            for (i in 0 until counts[l]) {
                val shift = 9 - l
                for (fill in 0 until (1 shl shift)) h.lookup[(code shl shift) or fill] = (l shl 8) or values[k]
                code++
                k++
            }
            code = code shl 1
        }
        return h
    }

    private class Bits(val input: Input) {
        var acc = 0L
        var count = 0

        fun fill() {
            while (count <= 48) {
                var b = input.peek()
                if (b < 0) {
                    b = 0
                } else if (b == 0xFF) {
                    // 0xFF 0x00 is a stuffed 0xFF; any other marker ends the data: feed zeros.
                    if (input.peek(1) == 0x00) input.advance(2) else b = 0
                } else input.advance(1)
                acc = (acc shl 8) or b.toLong()
                count += 8
            }
        }

        fun peek(n: Int): Int { if (count < n) fill(); return ((acc ushr (count - n)) and ((1L shl n) - 1)).toInt() }
        fun skip(n: Int) { count -= n }
        fun read(n: Int): Int { if (n == 0) return 0; val v = peek(n); skip(n); return v }

        /** Skips to the next restart marker. */
        fun restart() {
            count = 0
            acc = 0
            while (true) {
                val b = input.peek()
                if (b < 0) return
                if (b == 0xFF && (input.peek(1) and 0xF8) == 0xD0) { input.advance(2); return }
                input.advance(1)
            }
        }
    }

    private fun decodeDiff(bits: Bits, h: Huffman): Int {
        val p = bits.peek(9)
        val fast = h.lookup[p]
        val ssss: Int
        if (fast >= 0) {
            bits.skip(fast shr 8)
            ssss = fast and 0xFF
        } else {
            var l = 10
            var code = bits.peek(10)
            while (l <= 16 && code > h.maxCode[l]) { l++; code = bits.peek(l) }
            if (l > 16) throw RawException("Lossless JPEG: bad Huffman code")
            bits.skip(l)
            ssss = h.values[h.valPtr[l] + code - h.minCode[l]]
        }
        return when (ssss) {
            0 -> 0
            16 -> 32768
            else -> {
                val v = bits.read(ssss)
                if (v < (1 shl (ssss - 1))) v - (1 shl ssss) + 1 else v
            }
        }
    }

    private fun decodeScan(
        input: Input, width: Int, height: Int, comps: Int, precision: Int,
        predictor: Int, pt: Int, restart: Int, tables: Array<Huffman>, sink: RowSink,
    ) {
        val bits = Bits(input)
        val rowLen = width * comps
        val initial = 1 shl (precision - pt - 1)
        // The row above and the row being decoded; predictions use the values before the point transform.
        var up = ShortArray(rowLen)
        var row = ShortArray(rowLen)
        val shifted = if (pt > 0) ShortArray(rowLen) else null
        var mcu = 0
        var firstRow = true
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (restart > 0 && mcu > 0 && mcu % restart == 0 && x == 0) {
                    bits.restart()
                    firstRow = true
                }
                for (c in 0 until comps) {
                    val i = x * comps + c
                    val left = if (x > 0) row[i - comps].toInt() and 0xFFFF else 0
                    val above = if (y > 0) up[i].toInt() and 0xFFFF else 0
                    val upLeft = if (x > 0 && y > 0) up[i - comps].toInt() and 0xFFFF else 0
                    val pred = when {
                        x == 0 && (y == 0 || firstRow) -> initial
                        y == 0 || firstRow -> left
                        x == 0 -> above
                        else -> when (predictor) {
                            1 -> left
                            2 -> above
                            3 -> upLeft
                            4 -> left + above - upLeft
                            5 -> left + ((above - upLeft) shr 1)
                            6 -> above + ((left - upLeft) shr 1)
                            7 -> (left + above) shr 1
                            else -> left
                        }
                    }
                    row[i] = ((pred + decodeDiff(bits, tables[c])) and 0xFFFF).toShort()
                }
                mcu++
            }
            firstRow = false
            if (shifted != null) {
                for (i in 0 until rowLen) shifted[i] = ((row[i].toInt() and 0xFFFF) shl pt).toShort()
                sink.row(y, shifted)
            } else sink.row(y, row)
            val t = up; up = row; row = t
        }
    }
}
