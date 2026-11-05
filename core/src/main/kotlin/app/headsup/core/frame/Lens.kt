package app.headsup.core.frame

/**
 * Lens corrections, as Lightroom's manual ones: distortion (a barrel's bulge or a pincushion's pinch,
 * corrected by sampling the picture nearer or further from its middle, more towards the corners)
 * and the lens's vignetting (corners brightened by as much as they fall off). The shaders do the
 * same, per pixel; coordinates are fractions of the picture, y downwards.
 */
object Lens {
    /** Where the amounts sit in a [Reframe.warp] after its 3×3. */
    const val K = 9
    const val VIGNETTE = 10

    /** The radial factor for a distortion setting −1..1. */
    fun k(distortion: Float): Float = distortion.coerceIn(-1f, 1f) * 0.25f

    /** How far from the middle, squared, with the corners at 1 ([aspect]: width / height). */
    fun r2(x: Float, y: Float, aspect: Float): Float {
        val dx = (x - 0.5f) * aspect
        val dy = y - 0.5f
        return (dx * dx + dy * dy) / (0.25f * aspect * aspect + 0.25f)
    }

    /** Where the corrected point ([x], [y]) samples the picture; zoomed in so no empty edge shows. */
    fun sourceOf(x: Float, y: Float, k: Float, aspect: Float): Pair<Float, Float> {
        if (k == 0f) return x to y
        val s = (1f + k * r2(x, y, aspect)) / (if (k > 0f) 1f + k else 1f)
        return 0.5f + (x - 0.5f) * s to 0.5f + (y - 0.5f) * s
    }
}
