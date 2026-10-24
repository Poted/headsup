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
) {
    val endMs: Long get() = startMs + lengthMs
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
