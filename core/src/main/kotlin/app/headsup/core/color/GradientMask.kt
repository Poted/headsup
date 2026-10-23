package app.headsup.core.color

import kotlin.math.cos
import kotlin.math.sin

/**
 * Graduated mask, like an ND grad filter: the part above the line gets its own corrections
 * ([grade], added to the clip's), blending into the rest over [softness].
 */
data class GradientMask(
    /** Height of the line's centre, 0 = top of the frame, 1 = bottom. */
    val position: Float = 0.45f,
    /** Width of the blend, as a fraction of the frame height. */
    val softness: Float = 0.3f,
    /** Tilt of the line in degrees, positive clockwise. */
    val angleDegrees: Float = 0f,
    /** Corrections inside the mask, on top of the clip's own. */
    val grade: ColorGrade = ColorGrade(),
) {
    /**
     * How much of the mask applies at pixel ([x], [y]) of a [width]×[height] frame: 1 above the
     * line, 0 below, smooth in between. Shaders use the same formula.
     */
    fun weight(x: Float, y: Float, width: Float, height: Float): Float {
        val a = Math.toRadians(angleDegrees.toDouble())
        val px = (x - width / 2f) / height
        val py = y / height - position
        // Signed distance from the line, positive below it.
        val d = (-px * sin(a) + py * cos(a)).toFloat()
        val half = (softness / 2f).coerceAtLeast(0.001f)
        val t = ((d + half) / (2f * half)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
}
