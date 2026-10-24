package app.headsup.core.raw

import kotlin.math.max
import kotlin.math.min

/**
 * A camera profile's hue/saturation/value table (ProfileHueSatMap or ProfileLookTable): for
 * colours by hue, saturation and (optionally) value, a hue shift in degrees and scales for
 * saturation and value, interpolated between the entries, as the DNG reference code applies it
 * to linear ProPhoto RGB.
 */
class HueSatMap(
    val hueDivisions: Int,
    val satDivisions: Int,
    val valDivisions: Int,
    /** (hue shift, saturation scale, value scale) per entry: value outermost, then hue, saturation innermost. */
    val data: FloatArray,
    /** Whether the value axis is spaced in sRGB (ProfileHueSatMapEncoding / ProfileLookTableEncoding 1). */
    val srgbValues: Boolean = false,
) {
    init {
        require(hueDivisions >= 1 && satDivisions >= 2 && valDivisions >= 1) { "HueSatMap dimensions" }
        require(data.size >= hueDivisions * satDivisions * valDivisions * 3) { "HueSatMap data" }
    }

    /** Applies the table to linear ProPhoto RGB in [rgb] (changed in place). */
    fun apply(rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val v = max(r, max(g, b))
        if (v <= 0f) return
        val mn = min(r, min(g, b))
        val gap = v - mn
        val s = gap / v
        var h = when {
            gap <= 0f -> 0f
            r == v -> (g - b) / gap
            g == v -> 2f + (b - r) / gap
            else -> 4f + (r - g) / gap
        }
        if (h < 0f) h += 6f
        if (h >= 6f) h -= 6f

        // Position in the table.
        val hScaled = if (hueDivisions < 2) 0f else h * (hueDivisions / 6f)
        val sScaled = s * (satDivisions - 1)
        val vAxis = if (srgbValues) srgb(v.coerceIn(0f, 1f)) else v.coerceIn(0f, 1f)
        val vScaled = if (valDivisions < 2) 0f else vAxis * (valDivisions - 1)
        val h0 = min(hScaled.toInt(), hueDivisions - 1)
        val h1 = if (h0 + 1 >= hueDivisions) 0 else h0 + 1
        val s0 = min(sScaled.toInt(), satDivisions - 2)
        val v0 = if (valDivisions < 2) 0 else min(vScaled.toInt(), valDivisions - 2)
        val v1 = if (valDivisions < 2) 0 else v0 + 1
        val hf = (hScaled - h0).coerceIn(0f, 1f)
        val sf = (sScaled - s0).coerceIn(0f, 1f)
        val vf = if (valDivisions < 2) 0f else (vScaled - v0).coerceIn(0f, 1f)

        var hueShift = 0f
        var satScale = 0f
        var valScale = 0f
        for (vi in 0..1) {
            val vv = if (vi == 0) v0 else v1
            val wv = if (vi == 0) 1f - vf else vf
            if (wv == 0f) continue
            for (hi in 0..1) {
                val hh = if (hi == 0) h0 else h1
                val wh = if (hi == 0) 1f - hf else hf
                for (si in 0..1) {
                    val w = wv * wh * (if (si == 0) 1f - sf else sf)
                    if (w == 0f) continue
                    val i = ((vv * hueDivisions + hh) * satDivisions + s0 + si) * 3
                    hueShift += data[i] * w
                    satScale += data[i + 1] * w
                    valScale += data[i + 2] * w
                }
            }
        }

        h += hueShift * (6f / 360f)
        while (h < 0f) h += 6f
        while (h >= 6f) h -= 6f
        val s2 = min(s * satScale, 1f)
        val v2 = v * valScale
        // Back to RGB.
        val sector = h.toInt()
        val f = h - sector
        val p = v2 * (1f - s2)
        val q = v2 * (1f - s2 * f)
        val t = v2 * (1f - s2 * (1f - f))
        when (sector) {
            0 -> { rgb[0] = v2; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v2; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v2; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v2 }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v2 }
            else -> { rgb[0] = v2; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun srgb(x: Float): Float = if (x <= 0.0031308f) 12.92f * x else 1.055f * Math.pow(x.toDouble(), 1 / 2.4).toFloat() - 0.055f

    /** Dimensions, encoding and data in one array (to pass between processes). */
    fun pack(): FloatArray = floatArrayOf(hueDivisions.toFloat(), satDivisions.toFloat(), valDivisions.toFloat(), if (srgbValues) 1f else 0f) + data

    companion object {
        fun unpack(a: FloatArray?): HueSatMap? = a?.takeIf { it.size > 4 }?.let {
            runCatching { HueSatMap(it[0].toInt(), it[1].toInt(), it[2].toInt(), it.copyOfRange(4, it.size), it[3] > 0.5f) }.getOrNull()
        }

        /** Map 1 and map 2 blended with [weight] for map 1 (the same blend as the colour matrices). */
        fun blend(a: HueSatMap?, b: HueSatMap?, weight: Double): HueSatMap? = when {
            a != null && b != null && a.data.size == b.data.size ->
                HueSatMap(a.hueDivisions, a.satDivisions, a.valDivisions, FloatArray(a.data.size) { (a.data[it] * weight + b.data[it] * (1 - weight)).toFloat() }, a.srgbValues)
            else -> a ?: b
        }
    }
}
