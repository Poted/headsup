package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.MaskFollow

/**
 * Carries a brush mask through a clip, finding it again on each frame: the sky by the sky model,
 * anything else by MobileSAM, told where the mask was on the frame before (its box, a point well
 * inside it and the model's own answer there), so it takes the object's new shape, not just its
 * place. Maps are [width]×[height]; [start] is the mask on the first frame.
 */
class MaskFollower(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val sky: Boolean,
    start: FloatArray,
) {
    private var previous = start
    private var rough: FloatArray? = null

    /** Why [next] gave nothing: the object left the picture (or became too small to see). */
    var gone = false
        private set

    /** Why [next] gave nothing: on the picture still, but no longer recognised. */
    var lost = false
        private set

    /** The mask on [picture] (the next frame, any size with the clip's shape); null once gone or lost. */
    fun next(picture: Bitmap): FloatArray? {
        if (gone || lost) return null
        val small = Bitmap.createScaledBitmap(picture, width, height, true)
        val guide = Segmenter.rgb(small)
        if (sky) return sharp(Segmenter.refine(guide, Segmenter.sky(context, small, width, height), width, height))
        val box = MaskFollow.box(previous, width, height)
        if (box == null) {
            gone = true
            return null
        }
        val prompts = buildList {
            add(Segmenter.Prompt(box[0], box[1], Segmenter.Prompt.Label.BOX_START))
            add(Segmenter.Prompt(box[2], box[3], Segmenter.Prompt.Label.BOX_END))
            MaskFollow.inside(previous, width, height)?.let { (x, y) -> add(Segmenter.Prompt(x, y, Segmenter.Prompt.Label.IN)) }
        }
        val reading = Segmenter.read(context, picture)
        val found = Segmenter.follow(context, reading, prompts, width, height, rough)
        val k = MaskFollow.pick(previous, found.map { it.map to it.score })
        if (k == null) {
            // Nothing that is the same thing: leaving the picture, or no longer recognised.
            if (MaskFollow.touchesEdge(previous, width, height) || MaskFollow.area(previous) < SMALLEST * 4) gone = true else lost = true
            return null
        }
        val map = sharp(Segmenter.refine(guide, found[k].map, width, height))
        if (MaskFollow.area(map) < SMALLEST) {
            gone = true
            return null
        }
        previous = map
        rough = found[k].rough
        return map
    }

    /** A clean edge, as found objects get in a brush mask. */
    private fun sharp(map: FloatArray) = FloatArray(map.size) { i ->
        val t = ((map[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }

    private companion object {
        /** Less of the frame than this is nothing left to follow. */
        const val SMALLEST = 0.0005f
    }
}
