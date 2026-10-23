package app.headsup.core.color

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MaskKind {
    /** Graduated filter: everything on one side of a soft line (the sky side by default). */
    LINEAR,

    /** Oval window, e.g. around a face or the subject. */
    ELLIPSE,
}

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
    /** Oval width and height; unused for a line. */
    val sizeX: Float = 0.5f,
    val sizeY: Float = 0.6f,
    /** Tilt in degrees, positive clockwise. */
    val angleDegrees: Float = 0f,
    /** Width of the soft edge: of the frame height for a line, of the radius for an oval. */
    val softness: Float = 0.3f,
    /** Swaps inside and outside (below the line, outside the oval). */
    val inverted: Boolean = false,
    val grade: ColorGrade = ColorGrade(),
) {
    /**
     * How much of the mask applies at pixel ([x], [y]) of a [width]×[height] frame, 0..1.
     * The shaders use the same formula.
     */
    fun weight(x: Float, y: Float, width: Float, height: Float): Float {
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
        }
        return if (inverted) 1f - w else w
    }

    /** Packed for shaders: (kind, centerX, centerY, angle in radians) and (sizeX, sizeY, softness, inverted). */
    fun shaderParams(): Pair<FloatArray, FloatArray> = floatArrayOf(
        kind.ordinal.toFloat(), centerX, centerY, Math.toRadians(angleDegrees.toDouble()).toFloat(),
    ) to floatArrayOf(sizeX, sizeY, softness, if (inverted) 1f else 0f)

    companion object {
        const val MAX_PER_CLIP = 4

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
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
