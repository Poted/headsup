package app.headsup.core.frame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Reframing of a clip: an output shape ([aspect], e.g. 9:16 for a reel), zoom into the picture,
 * which part fills the frame, and straightening. Zoom never drops below what keeps the frame
 * filled after straightening.
 *
 * Sizes: "source" is the clip's picture, "output" the frame shown/exported. Without [aspect] they
 * are the same; with it the output is the largest frame of that shape inside the source.
 */
data class Reframe(
    /** 1 = the output frame shows as much of the picture as fits. */
    val zoom: Float = 1f,
    /** Point of the source picture shown at the output's centre, 0..1 of its width and height. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Rotation in degrees, positive clockwise, e.g. to level a horizon. */
    val angleDegrees: Float = 0f,
    /** Output width / height, or null to keep the clip's own shape. */
    val aspect: Float? = null,
) {
    val isIdentity: Boolean get() = zoom == 1f && centerX == 0.5f && centerY == 0.5f && angleDegrees == 0f && aspect == null

    /** Output frame size for a [srcW]×[srcH] clip. */
    fun outputSize(srcW: Float, srcH: Float): Pair<Float, Float> {
        val a = aspect ?: return srcW to srcH
        return if (a < srcW / srcH) srcH * a to srcH else srcW to srcW / a
    }

    /**
     * Zoom actually used: at least the scale at which the rotated picture still covers the
     * output frame. [outW]×[outH] and [srcW]×[srcH] may be in different units (e.g. a preview box
     * and the picture laid out inside it); the picture is first scaled to just cover the output.
     */
    fun effectiveZoom(outW: Float, outH: Float, srcW: Float, srcH: Float): Float {
        val s0 = baseScale(outW, outH, srcW, srcH)
        val (c, s) = cosSin()
        val cover = max((outW * c + outH * s) / (s0 * srcW), (outW * s + outH * c) / (s0 * srcH))
        return max(zoom, cover)
    }

    /** [centerX]/[centerY] limited so the output stays inside the picture. */
    fun clamped(outW: Float, outH: Float, srcW: Float, srcH: Float): Reframe {
        val scale = baseScale(outW, outH, srcW, srcH) * effectiveZoom(outW, outH, srcW, srcH)
        val (c, s) = cosSin()
        // Half-extent of the (rotated) output frame in source fractions.
        val hx = (outW * c + outH * s) / (2f * scale) / srcW
        val hy = (outW * s + outH * c) / (2f * scale) / srcH
        return copy(
            centerX = if (hx >= 0.5f) 0.5f else centerX.coerceIn(hx, 1f - hx),
            centerY = if (hy >= 0.5f) 0.5f else centerY.coerceIn(hy, 1f - hy),
        )
    }

    /** Source pixel shown at output pixel ([x], [y]). */
    fun sourceOf(x: Float, y: Float, outW: Float, outH: Float, srcW: Float, srcH: Float): Pair<Float, Float> {
        val r = clamped(outW, outH, srcW, srcH)
        val scale = baseScale(outW, outH, srcW, srcH) * r.effectiveZoom(outW, outH, srcW, srcH)
        val a = Math.toRadians(angleDegrees.toDouble())
        val dx = (x - outW / 2f) / scale
        val dy = (y - outH / 2f) / scale
        // Undo the clockwise rotation (screen coordinates, y down).
        val sx = (dx * cos(a) + dy * sin(a)).toFloat()
        val sy = (-dx * sin(a) + dy * cos(a)).toFloat()
        return (r.centerX * srcW + sx) to (r.centerY * srcH + sy)
    }

    // Same-size output (no aspect change), as used by most callers.
    fun effectiveZoom(width: Float, height: Float) = effectiveZoom(width, height, width, height)
    fun clamped(width: Float, height: Float) = clamped(width, height, width, height)
    fun sourceOf(x: Float, y: Float, width: Float, height: Float) = sourceOf(x, y, width, height, width, height)

    private fun cosSin(): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees.toDouble())
        return abs(cos(a)).toFloat() to abs(sin(a)).toFloat()
    }

    companion object {
        /** Scale at which the picture just covers the output frame. */
        fun baseScale(outW: Float, outH: Float, srcW: Float, srcH: Float) = max(outW / srcW, outH / srcH)

        /** Output shapes offered in the app, with their labels; null = the clip's own. */
        val PRESETS: List<Pair<Float?, String>> = listOf(
            null to "Original",
            16f / 9f to "16:9",
            4f / 3f to "4:3",
            1f to "1:1",
            4f / 5f to "4:5",
            9f / 16f to "9:16",
        )
    }
}
