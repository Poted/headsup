package app.headsup.core.color

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The colour and luminance ranges of masks, and how joined masks combine. The preview and export
 * shaders use the same formulas (GradePreviewShader, GradeEffect).
 */
object MaskRange {
    /** Rec. 709 luma of encoded values. */
    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Oklab (L, a, b) of an encoded (sRGB) colour. */
    fun oklab(r: Float, g: Float, b: Float): FloatArray {
        fun lin(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        val lr = lin(r.coerceIn(0f, 1f))
        val lg = lin(g.coerceIn(0f, 1f))
        val lb = lin(b.coerceIn(0f, 1f))
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** The colour distance a [ColourRange.tolerance] keeps fully (in Oklab units). */
    fun reach(tolerance: Float): Float = 0.02f + 0.16f * tolerance.coerceIn(0f, 1f)

    /** How far [lab] is from [ref] as the colour range measures it: hue and colourfulness, lightness a third. */
    fun distance(lab: FloatArray, ref: FloatArray): Float {
        val dl = (lab[0] - ref[0]) * 0.35f
        val da = lab[1] - ref[1]
        val db = lab[2] - ref[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How much of [range] the brightness [l] is in, 0..1. */
    fun lumaWeight(range: LumaRange, l: Float): Float =
        smooth(range.low - range.soft, range.low, l) * (1f - smooth(range.high, range.high + range.soft, l))

    /** How much of [range] the colour is in, 0..1: all within its reach, fading over as much again. */
    fun colourWeight(range: ColourRange, r: Float, g: Float, b: Float): Float {
        val reach = reach(range.tolerance)
        val d = distance(oklab(r, g, b), oklab(range.r, range.g, range.b))
        return 1f - smooth(reach, reach * 2f, d)
    }

    /**
     * The mask's coverage at a pixel of colour ([r], [g], [b]) whose shape coverage is [shape]
     * (already inverted when the mask is): the shape narrowed by the ranges. A range mask is the
     * ranges alone, and inverting it inverts them.
     */
    fun cover(mask: ColourMask, shape: Float, r: Float, g: Float, b: Float): Float {
        var w = 1f
        mask.luma?.let { w *= lumaWeight(it, luma(r, g, b)) }
        mask.colour?.let { w *= colourWeight(it, r, g, b) }
        return if (mask.kind == MaskKind.RANGE) (if (mask.inverted) 1f - w else w) else shape * w
    }

    /** [a] and [b] as [join] combines them (b joined to a). */
    fun combine(a: Float, b: Float, join: MaskJoin): Float = when (join) {
        MaskJoin.NEW -> a
        MaskJoin.ADD -> maxOf(a, b)
        MaskJoin.SUBTRACT -> a * (1f - b)
        MaskJoin.INTERSECT -> a * b
    }

    /**
     * Each mask's coverage once joined masks are combined into the layer they join, in list order:
     * a layer's coverage is its own combined with each mask joined after it in turn; a joined
     * mask's own is 0 (its layer applies the corrections). The first mask is always a layer.
     */
    fun joined(weights: FloatArray, joins: List<MaskJoin>): FloatArray {
        val out = FloatArray(weights.size)
        var head = 0
        var acc = weights.getOrElse(0) { 0f }
        for (i in 1 until weights.size) {
            val j = joins.getOrElse(i) { MaskJoin.NEW }
            if (j == MaskJoin.NEW) {
                out[head] = acc
                head = i
                acc = weights[i]
            } else {
                acc = combine(acc, weights[i], j)
            }
        }
        if (weights.isNotEmpty()) out[head] = acc
        return out
    }

    /** The layer mask [index] belongs to: itself, or the layer it is joined to. */
    fun layerOf(masks: List<ColourMask>, index: Int): Int {
        var i = index.coerceIn(0, (masks.size - 1).coerceAtLeast(0))
        while (i > 0 && masks[i].join != MaskJoin.NEW) i--
        return i
    }
}
