package app.headsup.core.color

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MaskKind {
    /** Graduated filter: everything on one side of a soft line (the sky side by default). */
    LINEAR,

    /** Oval window, e.g. around a face or the subject. */
    ELLIPSE,

    /**
     * Painted with a brush ([BrushMask]). Its strokes are where they were painted with the mask at
     * [BrushMask.BASE]; moving, turning or scaling the mask (by hand or by keys) carries them along.
     */
    BRUSH,

    /**
     * The whole picture, narrowed only by its colour and/or luminance range ([ColourMask.colour],
     * [ColourMask.luma]): everything of that colour or brightness, wherever it is.
     */
    RANGE,
}

/**
 * How a mask works with the mask before it in the clip's list: on its own (a layer with its own
 * corrections), or changing the coverage of the layer above it (added to it, taken from it, or
 * kept only where both are), as masks are combined in Lightroom. A joined mask has no corrections
 * of its own: the layer it joins applies its corrections over the combined coverage.
 */
enum class MaskJoin(val symbol: String, val label: String) {
    NEW("", "Own layer"),
    ADD("+", "Add"),
    SUBTRACT("−", "Subtract"),
    INTERSECT("∩", "Intersect"),
}

/**
 * Brightness the mask keeps: from [low] to [high] (0..1, of the picture's luma, Rec. 709 weights
 * of the encoded values), fading over [soft] beyond each end.
 */
data class LumaRange(val low: Float = 0.6f, val high: Float = 1f, val soft: Float = 0.15f)

/**
 * Colour the mask keeps: like the sampled colour ([r], [g], [b], encoded 0..1), within
 * [tolerance] (0 just that colour … 1 much of its family), measured in Oklab (lightness counts
 * less than hue and colourfulness, so a colour in shade and in sun is the same colour).
 */
data class ColourRange(val r: Float, val g: Float, val b: Float, val tolerance: Float = 0.35f)

/**
 * A window with its own corrections ([grade], added to the clip's), like a power window in
 * DaVinci Resolve. Positions and sizes are fractions of the frame: x of its width, y and sizes
 * of its height (so an oval with equal sizes is a circle).
 */
data class ColourMask(
    val kind: MaskKind = MaskKind.LINEAR,
    val centerX: Float = 0.5f,
    /** Height of the line (linear) or the centre (oval), 0 = top. */
    val centerY: Float = if (kind == MaskKind.LINEAR) 0.45f else 0.5f,
    /** Oval width and height; unused for a line; a brush mask's scale (1 = as painted). */
    val sizeX: Float = if (kind == MaskKind.BRUSH) 1f else 0.5f,
    val sizeY: Float = if (kind == MaskKind.BRUSH) 1f else 0.6f,
    /** Tilt in degrees, positive clockwise. */
    val angleDegrees: Float = 0f,
    /** Width of the soft edge: of the frame height for a line, of the radius for an oval, of each print for a brush. */
    val softness: Float = if (kind == MaskKind.BRUSH) 0.5f else 0.3f,
    /** Swaps inside and outside (below the line, outside the oval). */
    val inverted: Boolean = false,
    val grade: ColorGrade = ColorGrade(),
    /**
     * Where the mask is at moments of the clip (file time), for following movement in the shot;
     * empty = it stays put. With keys, the centre, angle and size above are only defaults.
     */
    val keys: List<MaskKey> = emptyList(),
    /** A brush mask's strokes, in the order painted. */
    val strokes: List<BrushStroke> = emptyList(),
    /**
     * A brush mask found again on each frame (tracked by an AI model): the id of its maps over
     * time, kept by the app. While set, the maps are the mask (the strokes are where it started)
     * and it does not move by keys: each map is already where the object is.
     */
    val sequence: String? = null,
    /**
     * How many of [strokes] the [sequence] was tracked from; strokes after them (a fix made on
     * some frame) are drawn on top of each map until the mask is tracked again.
     */
    val sequenceStrokes: Int = 0,
    /** Where the edge of what was found (Select, Sky, AI tracking) lies: -1 tighter … 1 fuller ([MaskEdge]). */
    val edge: Float = 0f,
    /**
     * Frames (file time) a mask tracked with AI was fixed on and tracked from: keys of its
     * tracking. Tracking again from a frame runs to the next key either way, never over it.
     */
    val anchors: List<Long> = emptyList(),
    /** Narrows the mask to a range of brightness (null = any). */
    val luma: LumaRange? = null,
    /** Narrows the mask to a colour (null = any). */
    val colour: ColourRange? = null,
    /** On its own, or joined to the mask before it ([MaskJoin]). */
    val join: MaskJoin = MaskJoin.NEW,
    /**
     * A painted or found mask's edge made gradual, 0..1 (up to [FEATHER_REACH] of the frame's
     * height): for edges no selection can follow exactly (a ragged shore), a soft band instead
     * of a cut ([MaskFeather]).
     */
    val feather: Float = 0f,
) {
    /** True when a colour or luminance range narrows the mask. */
    val ranged: Boolean get() = luma != null || colour != null

    /** The mask at [fileMs] into the clip's file: between keys it moves smoothly, outside them it holds. */
    fun at(fileMs: Long): ColourMask {
        if (sequence != null) return copy(centerX = 0.5f, centerY = 0.5f, angleDegrees = 0f, sizeX = 1f, sizeY = 1f, keys = emptyList())
        if (keys.isEmpty()) return this
        val k = MaskKey.interpolate(keys, fileMs)
        return copy(centerX = k.centerX, centerY = k.centerY, angleDegrees = k.angleDegrees, sizeX = k.sizeX, sizeY = k.sizeY)
    }

    /** Sets the mask's place at [fileMs] (a key there, or the defaults when it has no keys). */
    fun placedAt(fileMs: Long, centerX: Float, centerY: Float, angleDegrees: Float, sizeX: Float, sizeY: Float): ColourMask =
        if (keys.isEmpty()) {
            copy(centerX = centerX, centerY = centerY, angleDegrees = angleDegrees, sizeX = sizeX, sizeY = sizeY)
        } else {
            copy(keys = MaskKey.upsert(keys, MaskKey(fileMs, centerX, centerY, angleDegrees, sizeX, sizeY)))
        }

    /**
     * How much of the mask applies at pixel ([x], [y]) of a [width]×[height] frame, 0..1.
     * The shaders use the same formula. A brush mask is drawn from its strokes ([BrushMask]), so 0 here.
     */
    fun weight(x: Float, y: Float, width: Float, height: Float): Float {
        if (kind == MaskKind.BRUSH) return 0f
        // A range mask is the whole picture before its range (inverting it inverts the range: MaskRange).
        if (kind == MaskKind.RANGE) return 1f
        val a = Math.toRadians(angleDegrees.toDouble())
        val dx = (x - centerX * width) / height
        val dy = (y - centerY * height) / height
        val w = when (kind) {
            MaskKind.LINEAR -> {
                // Signed distance from the line, positive below it.
                val d = (-dx * sin(a) + dy * cos(a)).toFloat()
                1f - smoothstep(-softness / 2f, softness / 2f, d)
            }
            MaskKind.ELLIPSE -> {
                val u = (dx * cos(a) + dy * sin(a)).toFloat() / (sizeX / 2f).coerceAtLeast(0.001f)
                val v = (-dx * sin(a) + dy * cos(a)).toFloat() / (sizeY / 2f).coerceAtLeast(0.001f)
                val r = sqrt(u * u + v * v)
                1f - smoothstep(1f - softness / 2f, 1f + softness / 2f, r)
            }
            MaskKind.BRUSH, MaskKind.RANGE -> 0f
        }
        return if (inverted) 1f - w else w
    }

    /**
     * Packed for shaders: (kind, centerX, centerY, angle in radians) and (sizeX, sizeY, softness,
     * inverted). A brush mask is drawn from its slot of the brush atlas, moved from [BrushMask.BASE]
     * to here.
     */
    fun shaderParams(): Pair<FloatArray, FloatArray> = floatArrayOf(
        kind.ordinal.toFloat(), centerX, centerY, Math.toRadians(angleDegrees.toDouble()).toFloat(),
    ) to floatArrayOf(sizeX, sizeY, if (kind == MaskKind.BRUSH) feather else softness, if (inverted) 1f else 0f)

    /**
     * The range and join packed for shaders: (luma low, high, soft, join) with low < -0.5 when
     * there is no luminance range, and (Oklab L, a, b, tolerance) with tolerance 0 when there is
     * no colour range ([MaskRange]).
     */
    fun rangeParams(): Pair<FloatArray, FloatArray> {
        val l = luma
        val c = colour
        val first = if (l == null) floatArrayOf(-1f, 1f, 0f, join.ordinal.toFloat()) else floatArrayOf(l.low, l.high, l.soft, join.ordinal.toFloat())
        val second = if (c == null) FloatArray(4) else MaskRange.oklab(c.r, c.g, c.b).let { floatArrayOf(it[0], it[1], it[2], MaskRange.reach(c.tolerance)) }
        return first to second
    }

    companion object {
        const val MAX_PER_CLIP = 4

        /** How wide a full [feather] is: of the frame's height. */
        const val FEATHER_REACH = 0.08f

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/** The mask's place at one moment of the clip's file. */
data class MaskKey(
    val timeMs: Long,
    val centerX: Float,
    val centerY: Float,
    val angleDegrees: Float,
    val sizeX: Float,
    val sizeY: Float,
) {
    companion object {
        /** Keys closer than this to an edited moment are replaced rather than added. */
        const val SAME_MS = 40L

        fun of(mask: ColourMask, timeMs: Long) = MaskKey(timeMs, mask.centerX, mask.centerY, mask.angleDegrees, mask.sizeX, mask.sizeY)

        fun upsert(keys: List<MaskKey>, key: MaskKey): List<MaskKey> =
            (keys.filter { kotlin.math.abs(it.timeMs - key.timeMs) > SAME_MS } + key).sortedBy { it.timeMs }

        /**
         * Drops keys the others already describe: a key stays only where leaving it out would move
         * the mask more than [tolerance] (of the frame; angle in degrees × 0.01) from its path
         * (Douglas–Peucker). A dense track becomes a few keys that are easy to adjust.
         */
        fun simplify(keys: List<MaskKey>, tolerance: Float = 0.004f): List<MaskKey> {
            if (keys.size <= 2) return keys
            val keep = BooleanArray(keys.size)
            keep[0] = true
            keep[keys.lastIndex] = true
            fun error(k: MaskKey, a: MaskKey, b: MaskKey): Float {
                val i = interpolate(listOf(a, b), k.timeMs)
                return maxOf(
                    kotlin.math.abs(i.centerX - k.centerX), kotlin.math.abs(i.centerY - k.centerY),
                    kotlin.math.abs(i.angleDegrees - k.angleDegrees) * 0.01f,
                    kotlin.math.abs(i.sizeX - k.sizeX), kotlin.math.abs(i.sizeY - k.sizeY),
                )
            }
            val stack = ArrayDeque(listOf(0 to keys.lastIndex))
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                var worst = -1
                var worstError = tolerance
                for (i in a + 1 until b) {
                    val e = error(keys[i], keys[a], keys[b])
                    if (e > worstError) { worstError = e; worst = i }
                }
                if (worst >= 0) {
                    keep[worst] = true
                    stack += a to worst
                    stack += worst to b
                }
            }
            return keys.filterIndexed { i, _ -> keep[i] }
        }

        /** Linear between the neighbouring keys (sorted by time); the first or last outside them. */
        fun interpolate(keys: List<MaskKey>, timeMs: Long): MaskKey {
            if (timeMs <= keys.first().timeMs) return keys.first()
            if (timeMs >= keys.last().timeMs) return keys.last()
            val i = keys.indexOfFirst { it.timeMs > timeMs }
            val a = keys[i - 1]
            val b = keys[i]
            val t = (timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).coerceAtLeast(1L)
            fun mix(x: Float, y: Float) = x + (y - x) * t
            return MaskKey(timeMs, mix(a.centerX, b.centerX), mix(a.centerY, b.centerY), mix(a.angleDegrees, b.angleDegrees), mix(a.sizeX, b.sizeX), mix(a.sizeY, b.sizeY))
        }
    }
}

/** Gradual change of look at the start of a clip, from the previous clip's grade to its own. */
object ColourTransition {
    /** Share of the previous clip's look at [positionMs] into the clip, for a blend lasting [lengthMs]. */
    fun fromWeight(positionMs: Long, lengthMs: Long): Float {
        if (lengthMs <= 0L) return 0f
        val t = (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
}
