package app.headsup.core.frame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Reframing of a clip: zoom into the picture, choose which part fills the frame, and straighten
 * it. The output keeps the clip's size; zoom never drops below what keeps the corners filled
 * after straightening.
 */
data class Reframe(
    /** 1 = whole picture. */
    val zoom: Float = 1f,
    /** Point of the source picture shown at the frame's centre, 0..1 of its width and height. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Rotation in degrees, positive clockwise, e.g. to level a horizon. */
    val angleDegrees: Float = 0f,
) {
    val isIdentity: Boolean get() = zoom == 1f && centerX == 0.5f && centerY == 0.5f && angleDegrees == 0f

    /**
     * The zoom actually used for a [width]×[height] frame: at least the scale at which the rotated
     * picture still covers the frame.
     */
    fun effectiveZoom(width: Float, height: Float): Float {
        val a = Math.toRadians(angleDegrees.toDouble())
        val c = abs(cos(a)).toFloat()
        val s = abs(sin(a)).toFloat()
        // The frame rotated back into the picture must fit inside the picture.
        val cover = max((width * c + height * s) / width, (width * s + height * c) / height)
        return max(zoom, cover)
    }

    /** [centerX]/[centerY] limited so the view stays inside the picture (for the effective zoom). */
    fun clamped(width: Float, height: Float): Reframe {
        val z = effectiveZoom(width, height)
        val a = Math.toRadians(angleDegrees.toDouble())
        val c = abs(cos(a)).toFloat()
        val s = abs(sin(a)).toFloat()
        // Half-extent of the (rotated) view in source fractions.
        val hx = ((width * c + height * s) / (2f * z)) / width
        val hy = ((width * s + height * c) / (2f * z)) / height
        return copy(
            centerX = if (hx >= 0.5f) 0.5f else centerX.coerceIn(hx, 1f - hx),
            centerY = if (hy >= 0.5f) 0.5f else centerY.coerceIn(hy, 1f - hy),
        )
    }

    /** Source pixel shown at output pixel ([x], [y]) of a [width]×[height] frame. */
    fun sourceOf(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> {
        val r = clamped(width, height)
        val z = r.effectiveZoom(width, height)
        val a = Math.toRadians(angleDegrees.toDouble())
        val dx = (x - width / 2f) / z
        val dy = (y - height / 2f) / z
        // Undo the clockwise rotation (screen coordinates, y down).
        val sx = (dx * cos(a) + dy * sin(a)).toFloat()
        val sy = (-dx * sin(a) + dy * cos(a)).toFloat()
        return (r.centerX * width + sx) to (r.centerY * height + sy)
    }
}
