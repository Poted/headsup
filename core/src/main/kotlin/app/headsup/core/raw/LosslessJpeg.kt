package app.headsup.core.raw

/**
 * Decoder for lossless JPEG (ITU T.81 process 14, "LJ92"), as DNG cameras store raw data: each
 * sample is predicted from its neighbours and the Huffman-coded difference added. Returns the
 * samples row by row, components interleaved, with the frame's width, height and components.
 */
object LosslessJpeg {
    class Frame(val width: Int, val height: Int, val components: Int, val precision: Int, val samples: ShortArray)

    private class Huffman(val maxCode: IntArray, val valPtr: IntArray, val minCode: IntArray, val values: IntArray) {
        // Fast path: the code's length and value for the next 9 bits.
        val lookup = IntArray(1 shl 9) { -1 }
    }

    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Frame {
        var pos = offset
        val end = offset + length
        fun u8() = data[pos++].toInt() and 0xFF
        fun u16(): Int { val v = (u8() shl 8) or u8(); return v }

        if (u8() != 0xFF || u8() != 0xD8) throw RawException("Lossless JPEG: no start marker")
        val tables = arrayOfNulls<Huffman>(4)
        var width = 0
        var height = 0
        var precision = 0
        var components = 0
        val compIds = IntArray(4)
        val compTable = IntArray(4)
        var restart = 0
        while (pos < end) {
            if (u8() != 0xFF) continue
            var marker = u8()
            while (marker == 0xFF) marker = u8()
            when (marker) {
                0xC3 -> {
                    val len = u16()
                    val start = pos
                    precision = u8()
                    height = u16()
                    width = u16()
                    components = u8()
                    for (c in 0 until components) {
                        compIds[c] = u8()
                        u8() // sampling factors (1x1 in DNG)
                        u8() // quantisation table (unused)
                    }
                    pos = start + len - 2
                }
                0xC4 -> {
                    val len = u16()
                    val stop = pos + len - 2
                    while (pos < stop) {
                        val id = u8() and 0x0F
                        val counts = IntArray(17)
                        var total = 0
                        for (l in 1..16) { counts[l] = u8(); total += counts[l] }
                        val values = IntArray(total) { u8() }
                        tables[id] = buildTable(counts, values)
                    }
                }
                0xDD -> { u16(); restart = u16() }
                0xDA -> {
                    val len = u16()
                    val start = pos
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
                    pos = start + len - 2
                    if (width == 0 || height == 0 || components == 0) throw RawException("Lossless JPEG: no frame header")
                    val samples = ShortArray(width * height * components)
                    decodeScan(data, pos, end, width, height, components, precision, predictor, pointTransform, restart, Array(components) { tables[compTable[it]] ?: throw RawException("Lossless JPEG: missing Huffman table") }, samples)
                    return Frame(width, height, components, precision, samples)
                }
                0xD9 -> break
                else -> { val len = u16(); pos += len - 2 }
            }
        }
        throw RawException("Lossless JPEG: no scan")
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

    private class Bits(val data: ByteArray, var pos: Int, val end: Int) {
        var acc = 0L
        var count = 0

        fun fill() {
            while (count <= 48) {
                var b = 0
                if (pos < end) {
                    b = data[pos].toInt() and 0xFF
                    if (b == 0xFF) {
                        val next = if (pos + 1 < end) data[pos + 1].toInt() and 0xFF else 0
                        if (next == 0x00) pos += 2 else { b = 0; /* marker: feed zeros */ }
                    } else pos++
                }
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
            while (pos + 1 < end && !(data[pos] == 0xFF.toByte() && (data[pos + 1].toInt() and 0xF8) == 0xD0)) pos++
            pos += 2
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
        data: ByteArray, start: Int, end: Int, width: Int, height: Int, comps: Int, precision: Int,
        predictor: Int, pt: Int, restart: Int, tables: Array<Huffman>, out: ShortArray,
    ) {
        val bits = Bits(data, start, end)
        val rowLen = width * comps
        val initial = 1 shl (precision - pt - 1)
        var mcu = 0
        var firstRow = true
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (restart > 0 && mcu > 0 && mcu % restart == 0 && x == 0) {
                    bits.restart()
                    firstRow = true
                }
                for (c in 0 until comps) {
                    val i = y * rowLen + x * comps + c
                    val left = if (x > 0) out[i - comps].toInt() and 0xFFFF else 0
                    val up = if (y > 0) out[i - rowLen].toInt() and 0xFFFF else 0
                    val upLeft = if (x > 0 && y > 0) out[i - rowLen - comps].toInt() and 0xFFFF else 0
                    val pred = when {
                        x == 0 && (y == 0 || firstRow) -> initial
                        y == 0 || firstRow -> left
                        x == 0 -> up
                        else -> when (predictor) {
                            1 -> left
                            2 -> up
                            3 -> upLeft
                            4 -> left + up - upLeft
                            5 -> left + ((up - upLeft) shr 1)
                            6 -> up + ((left - upLeft) shr 1)
                            7 -> (left + up) shr 1
                            else -> left
                        }
                    }
                    out[i] = ((pred + decodeDiff(bits, tables[c])) and 0xFFFF).toShort()
                }
                mcu++
            }
            firstRow = false
        }
        if (pt > 0) for (i in out.indices) out[i] = ((out[i].toInt() and 0xFFFF) shl pt).toShort()
    }
}
