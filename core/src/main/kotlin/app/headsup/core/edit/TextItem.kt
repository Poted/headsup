package app.headsup.core.edit

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How a text comes in (and, mirrored, goes out). */
enum class TextAnim(val label: String) {
    NONE("Cut"),
    FADE("Fade"),
    RISE("Rise"),
    POP("Pop"),
    TYPE("Type"),

    /** Typing with a block cursor and each letter decoded from random characters first, like a HUD. */
    HUD("HUD"),
}

enum class TextFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono"), HAND("Hand") }

enum class TextAlign { LEFT, CENTER }

/**
 * A text on the timeline: shown from [startMs] for [lengthMs], placed in the frame (centre of the
 * block at [x], [y], 0..1; its left edge at [x] when [align] is LEFT), [size] its letter height as
 * a share of the frame's short side.
 */
data class TextItem(
    val id: Long,
    val text: String,
    val startMs: Long,
    val lengthMs: Long,
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    val size: Float = 0.07f,
    val font: TextFont = TextFont.SANS,
    val colour: Int = 0xFFFFFFFF.toInt(),
    /** Soft light around the letters, 0..1. */
    val glow: Float = 0f,
    /** Dark shadow under the letters, for reading over bright pictures. */
    val shadow: Boolean = true,
    val align: TextAlign = TextAlign.CENTER,
    val animIn: TextAnim = TextAnim.FADE,
    val animOut: TextAnim = TextAnim.FADE,
    /** Length of the fade, rise and pop animations (typing takes as long as its letters need). */
    val animMs: Long = 500L,
    /** In an album: shown only on this photo, or on the photos of this group (both null: on all). */
    val onlyClip: Long? = null,
    val onlyGroup: Long? = null,
    /**
     * A logo instead of letters: the picture kept at this path (a PNG keeps its transparency),
     * its width [size] × 3 of the frame's short side, its [opacity] 0..1. Null: a text.
     */
    val image: String? = null,
    val opacity: Float = 1f,
    /** Turned about its middle, degrees clockwise. */
    val angle: Float = 0f,
    /**
     * The picture inside the letters: the whole frame covered in [colour] with the letters cut out
     * of it, so the video (or photo) shows only through them.
     */
    val cutout: Boolean = false,
    /** Behind the subject of the photo or clip (the person in front stands over the letters). */
    val behind: Boolean = false,
    /** Stuck to a place in a clip's picture: carried, turned and sized with it. */
    val follow: TextFollow? = null,
) {
    val isLogo: Boolean get() = image != null

    /**
     * As shown on an album's photo: there is no timeline, so it is there all the time, still (an
     * album's texts once kept the album's length when they were made, and photos added after
     * that fell outside it).
     */
    fun shownAlways(): TextItem = copy(startMs = 0L, lengthMs = 315_000_000_000L, animIn = TextAnim.NONE, animOut = TextAnim.NONE)

    /** Whether it shows on the photo [clipId] in group [groupId]. */
    fun showsOn(clipId: Long, groupId: Long?): Boolean = when {
        onlyClip != null -> onlyClip == clipId
        onlyGroup != null -> onlyGroup == groupId
        else -> true
    }

    val endMs: Long get() = startMs + lengthMs

    /**
     * As shown at [fileMs] of the clip [clipId]: moved with the place it follows (by how far that
     * place is from where it was when it was set to follow, so dragging it still places it),
     * turned and sized with it. [toFrame] takes a source point (fractions) to the frame's.
     */
    fun placed(clipId: Long, fileMs: Long, toFrame: (Float, Float) -> Pair<Float, Float>): TextItem {
        val f = follow?.takeIf { it.clipId == clipId && it.timesMs.isNotEmpty() } ?: return this
        val now = f.at(fileMs)
        val then = f.at(f.fromMs)
        val (x0, y0) = toFrame(then.x, then.y)
        val (x1, y1) = toFrame(now.x, now.y)
        return copy(x = x + (x1 - x0), y = y + (y1 - y0), angle = angle + now.angle - then.angle, size = size * now.scale / then.scale.coerceAtLeast(0.01f))
    }
}

/**
 * A place in a clip followed through it: at each of [timesMs] (file time, rising) the place in
 * the source picture ([xs], [ys], fractions), how much it turned (degrees) and grew since the
 * first. Between two it moves evenly; before the first and after the last it holds.
 */
data class TextFollow(
    val clipId: Long,
    val timesMs: List<Long>,
    val xs: List<Float>,
    val ys: List<Float>,
    val angles: List<Float>,
    val scales: List<Float>,
    /** The moment it was set to follow: where it was then is where it is placed (x, y), and moves from. */
    val fromMs: Long = timesMs.firstOrNull() ?: 0L,
) {
    data class At(val x: Float, val y: Float, val angle: Float, val scale: Float)

    fun at(fileMs: Long): At {
        val n = timesMs.size
        if (n == 0) return At(0.5f, 0.5f, 0f, 1f)
        val i = timesMs.binarySearch(fileMs).let { if (it >= 0) it else -it - 2 }
        if (i < 0) return At(xs[0], ys[0], angles[0], scales[0])
        if (i >= n - 1) return At(xs[n - 1], ys[n - 1], angles[n - 1], scales[n - 1])
        val t = (fileMs - timesMs[i]).toFloat() / (timesMs[i + 1] - timesMs[i]).coerceAtLeast(1)
        fun mix(v: List<Float>) = v[i] + (v[i + 1] - v[i]) * t
        return At(mix(xs), mix(ys), mix(angles), mix(scales))
    }
}

/** What a text looks like at one moment. */
data class TextFrame(
    val alpha: Float,
    /** Upward offset as a share of the letter height (rise: comes up from below). */
    val offset: Float,
    val scale: Float,
    /** Characters shown (typing). */
    val shown: Int,
    /** Whether the character after [shown] is shown as a random one (HUD decoding). */
    val decoding: Boolean,
    /** Seed for the random character, changing a few times per letter. */
    val noise: Int,
    val cursor: Boolean,
)

object TextAnimation {
    /** Time per letter when typing. */
    const val CHAR_MS = 55L
    private const val BLINK_MS = 260L

    /** How long [anim] takes for [item]. */
    fun durationOf(item: TextItem, anim: TextAnim): Long = when (anim) {
        TextAnim.NONE -> 0L
        TextAnim.TYPE, TextAnim.HUD -> min(item.text.length * CHAR_MS, item.lengthMs / 2)
        else -> min(item.animMs, item.lengthMs / 2)
    }

    /** [item] at timeline time [t], or null when it is not on screen. */
    fun frame(item: TextItem, t: Long): TextFrame? {
        val local = t - item.startMs
        if (local < 0 || local >= item.lengthMs || item.text.isEmpty()) return null
        val total = item.text.length
        var alpha = 1f
        var offset = 0f
        var scale = 1f
        var shown = total
        var decoding = false
        var cursor = false
        val inMs = durationOf(item, item.animIn)
        val outMs = durationOf(item, item.animOut)
        val left = item.lengthMs - local
        // Coming in.
        if (inMs > 0 && local < inMs) {
            val p = local.toFloat() / inMs
            when (item.animIn) {
                TextAnim.FADE -> alpha *= p
                TextAnim.RISE -> { alpha *= p; offset -= (1f - easeOut(p)) * 0.8f }
                TextAnim.POP -> { alpha *= min(1f, p * 2f); scale *= popScale(p) }
                TextAnim.TYPE, TextAnim.HUD -> {
                    shown = (local / CHAR_MS).toInt().coerceIn(0, total)
                    decoding = item.animIn == TextAnim.HUD && shown < total
                }
                TextAnim.NONE -> Unit
            }
        }
        // Going out (mirrored).
        if (outMs > 0 && left < outMs) {
            val q = left.toFloat() / outMs
            when (item.animOut) {
                TextAnim.FADE -> alpha *= q
                TextAnim.RISE -> { alpha *= q; offset += (1f - easeOut(q)) * 0.8f }
                TextAnim.POP -> { alpha *= min(1f, q * 2f); scale *= popScale(q) }
                TextAnim.TYPE, TextAnim.HUD -> {
                    shown = min(shown, (left / CHAR_MS).toInt().coerceIn(0, total))
                    decoding = decoding || (item.animOut == TextAnim.HUD && shown < total)
                }
                TextAnim.NONE -> Unit
            }
        }
        // A HUD text keeps a blinking cursor while typing and a moment after.
        if (item.animIn == TextAnim.HUD && local < inMs + 1_000L || item.animOut == TextAnim.HUD && left < outMs) {
            cursor = (local / BLINK_MS) % 2 == 0L
        }
        return TextFrame(alpha.coerceIn(0f, 1f), offset, scale, shown, decoding, (local / 40L).toInt(), cursor)
    }

    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p)

    /** Grows past its size and settles back. */
    private fun popScale(p: Float): Float = 0.6f + 0.4f * easeOut(p) + 0.12f * sin(p * PI).toFloat() * max(0f, 1f - p)
}
