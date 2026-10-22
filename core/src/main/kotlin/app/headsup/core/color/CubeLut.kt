package app.headsup.core.color

import java.io.IOException

class CubeFormatException(message: String) : IOException(message)

/**
 * A 3D colour lookup table in the Adobe/Resolve `.cube` format, as published by DJI for D-Log M.
 * Values are stored with red changing fastest, as in the file.
 */
class CubeLut(val size: Int, private val data: FloatArray, val title: String?) {
    init {
        require(size >= 2 && data.size == size * size * size * 3)
    }

    /** Trilinear lookup; inputs and outputs are 0..1. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = size - 1
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = fr.toInt().coerceAtMost(max - 1)
        val g0 = fg.toInt().coerceAtMost(max - 1)
        val b0 = fb.toInt().coerceAtMost(max - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0 until 3) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
            val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
            val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            out[c] = c0 * (1 - db) + c1 * db
        }
    }

    companion object {
        fun parse(text: String): CubeLut {
            var size = 0
            var title: String? = null
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: throw CubeFormatException("Bad LUT_3D_SIZE")
                    "LUT_1D_SIZE" -> throw CubeFormatException("1D LUTs are not supported")
                    "DOMAIN_MIN" -> domainMin = floats(parts, line)
                    "DOMAIN_MAX" -> domainMax = floats(parts, line)
                    "LUT_3D_INPUT_RANGE" -> {
                        val (lo, hi) = parts.drop(1).map { it.toFloat() }
                        domainMin = floatArrayOf(lo, lo, lo)
                        domainMax = floatArrayOf(hi, hi, hi)
                    }
                    else -> {
                        if (parts.size != 3) throw CubeFormatException("Unexpected line: $line")
                        for (p in parts) values += p.toFloatOrNull() ?: throw CubeFormatException("Unexpected line: $line")
                    }
                }
            }
            if (size < 2) throw CubeFormatException("Missing LUT_3D_SIZE")
            if (values.size != size * size * size * 3) {
                throw CubeFormatException("Expected ${size * size * size} entries, found ${values.size / 3}")
            }
            // Output values are normalised to 0..1 using the declared domain.
            val data = FloatArray(values.size) { i ->
                val c = i % 3
                (values[i] - domainMin[c]) / (domainMax[c] - domainMin[c])
            }
            return CubeLut(size, data, title)
        }

        private fun floats(parts: List<String>, line: String): FloatArray {
            if (parts.size != 4) throw CubeFormatException("Unexpected line: $line")
            return FloatArray(3) { parts[it + 1].toFloatOrNull() ?: throw CubeFormatException("Unexpected line: $line") }
        }
    }
}
