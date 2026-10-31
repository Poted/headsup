package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.CameraMotion
import app.headsup.core.color.GrayFrame
import app.headsup.core.color.MaskFollow

/**
 * Carries a brush mask through a clip, finding it again on each frame: the sky by the sky model,
 * anything else by MobileSAM, told where the mask was on the frame before (its box, a point well
 * inside it and the model's own answer there), so it takes the object's new shape, not just its
 * place. Maps are [width]×[height]; [start] is the mask on the first frame. What the user's mask
 * has beyond what the model finds (fixes painted in or out, [begin]) is carried with the picture's
 * movement ([CameraMotion]) and laid over each frame's answer.
 */
class MaskFollower(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val sky: Boolean,
    start: FloatArray,
) {
    /** The mask the model is asked to find again (its own answer on the frame before). */
    private var previous = start
    private var rough: FloatArray? = null

    /**
     * What the user's mask has that the model does not find (painted in: [added]) or finds and
     * the user took out ([removed]), on the last frame: carried with the picture's movement and
     * laid over the model's answer on each frame, so a fix made on one frame lasts.
     */
    private var added: FloatArray? = null
    private var removed: FloatArray? = null
    private var lastGray: GrayFrame? = null

    /** Why [next] gave nothing: the object left the picture (or became too small to see). */
    var gone = false
        private set

    /** Why [next] gave nothing: on the picture still, but no longer recognised. */
    var lost = false
        private set

    /**
     * The frame the mask starts on: what the model finds there is compared with the mask, and the
     * difference (the user's fixes) is carried on from it.
     */
    fun begin(picture: Bitmap) {
        val small = Bitmap.createScaledBitmap(picture, width, height, true)
        val guide = Segmenter.rgb(small)
        lastGray = gray(guide)
        val user = previous
        val found = find(picture, small, guide, user) ?: return
        added = FloatArray(user.size) { (user[it] - found.first[it]).coerceAtLeast(0f) }
        removed = FloatArray(user.size) { (found.first[it] - user[it]).coerceAtLeast(0f) }
        previous = found.first
        rough = found.second
    }

    /** The mask on [picture] (the next frame, any size with the clip's shape); null once gone or lost. */
    fun next(picture: Bitmap): FloatArray? {
        if (gone || lost) return null
        val small = Bitmap.createScaledBitmap(picture, width, height, true)
        val guide = Segmenter.rgb(small)
        // The fixes go where the picture went.
        val gray = gray(guide)
        val motion = lastGray?.let { CameraMotion.between(it, gray) }
        lastGray = gray
        if (motion != null) {
            added = added?.let { CameraMotion.carry(it, width, height, motion) }
            removed = removed?.let { CameraMotion.carry(it, width, height, motion) }
        }
        val found = find(picture, small, guide, previous)
        if (found == null) {
            if (!sky) {
                // Nothing that is the same thing: leaving the picture, or no longer recognised.
                if (MaskFollow.touchesEdge(previous, width, height) || MaskFollow.area(previous) < SMALLEST * 4) gone = true else lost = true
            }
            return null
        }
        if (!sky && MaskFollow.area(found.first) < SMALLEST) {
            gone = true
            return null
        }
        previous = found.first
        rough = found.second
        return withFixes(found.first)
    }

    /** The model's answer with the user's fixes laid over it. */
    private fun withFixes(map: FloatArray): FloatArray {
        val a = added
        val r = removed
        if (a == null && r == null) return map
        return FloatArray(map.size) { i ->
            val v = maxOf(map[i], a?.get(i) ?: 0f)
            v * (1f - (r?.get(i) ?: 0f))
        }
    }

    /** What the model finds on [picture] for the mask [like] (its box, a point inside, its last rough answer). */
    private fun find(picture: Bitmap, small: Bitmap, guide: FloatArray, like: FloatArray): Pair<FloatArray, FloatArray?>? {
        if (sky) return sharp(Segmenter.refine(guide, Segmenter.sky(context, small, width, height), width, height)) to null
        val box = MaskFollow.box(like, width, height) ?: return null
        val prompts = buildList {
            add(Segmenter.Prompt(box[0], box[1], Segmenter.Prompt.Label.BOX_START))
            add(Segmenter.Prompt(box[2], box[3], Segmenter.Prompt.Label.BOX_END))
            MaskFollow.inside(like, width, height)?.let { (x, y) -> add(Segmenter.Prompt(x, y, Segmenter.Prompt.Label.IN)) }
        }
        val reading = Segmenter.read(context, picture)
        val found = Segmenter.follow(context, reading, prompts, width, height, rough)
        val k = MaskFollow.pick(like, found.map { it.map to it.score }) ?: return null
        return sharp(Segmenter.refine(guide, found[k].map, width, height)) to found[k].rough
    }

    private fun gray(rgb: FloatArray) = GrayFrame(width, height, FloatArray(width * height) { i ->
        0.2126f * rgb[i * 3] + 0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2]
    })

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
