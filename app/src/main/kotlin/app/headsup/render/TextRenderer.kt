package app.headsup.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.headsup.core.edit.TextAlign
import app.headsup.core.edit.TextAnimation
import app.headsup.core.edit.TextFont
import app.headsup.core.edit.TextFrame
import app.headsup.core.edit.TextItem

/**
 * Draws the timeline's texts into a frame, the same way in the preview and the export: each at its
 * place, size, font and colour, as its animation has it at the moment.
 */
object TextRenderer {
    private const val NOISE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&*+=-"
    private const val CURSOR = "█"

    fun typeface(font: TextFont): Typeface = when (font) {
        TextFont.SANS -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        TextFont.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        TextFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        TextFont.HAND -> Typeface.create("cursive", Typeface.NORMAL)
    }

    /** Changes whenever what [draw] would draw changes (for reusing an export overlay's bitmap). */
    fun key(items: List<TextItem>, timelineMs: Long): String =
        items.mapNotNull { item -> TextAnimation.frame(item, timelineMs)?.let { "${item.hashCode()}:$it" } }.joinToString("|")

    fun draw(canvas: Canvas, width: Float, height: Float, items: List<TextItem>, timelineMs: Long) {
        for (item in items) {
            val f = TextAnimation.frame(item, timelineMs) ?: continue
            if (f.alpha <= 0.003f) continue
            drawItem(canvas, width, height, item, f)
        }
    }

    /** The text as shown at frame [f]: typed so far, a decoding letter and the cursor. */
    fun shownText(item: TextItem, f: TextFrame): String {
        val sb = StringBuilder(item.text.take(f.shown))
        if (f.decoding && f.shown < item.text.length && !item.text[f.shown].isWhitespace()) {
            sb.append(NOISE[Math.floorMod(f.noise * 31 + f.shown * 7, NOISE.length)])
        }
        if (f.cursor) sb.append(CURSOR)
        return sb.toString()
    }

    private fun drawItem(canvas: Canvas, w: Float, h: Float, item: TextItem, f: TextFrame) {
        val size = item.size * minOf(w, h)
        val text = shownText(item, f)
        // Laid out for the whole text, so typing does not move what is already there.
        val lines = text.split('\n')
        val fullLines = item.text.split('\n')
        // Its own paint: the preview and an export may draw at the same time.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.typeface = typeface(item.font)
        paint.textSize = size
        val lineH = size * 1.2f
        val blockW = fullLines.maxOf { paint.measureText(it) }
        val blockH = lineH * fullLines.size
        val cx = item.x * w
        val cy = item.y * h - f.offset * size
        val left = if (item.align == TextAlign.LEFT) cx else cx - blockW / 2f
        val top = cy - blockH / 2f
        canvas.save()
        canvas.scale(f.scale, f.scale, if (item.align == TextAlign.LEFT) left else cx, cy)
        val alpha = (f.alpha * Color.alpha(item.colour)).toInt().coerceIn(0, 255)
        val baseline0 = top + size
        fun pass(colour: Int, shadowRadius: Float, shadowDy: Float, shadowColour: Int) {
            paint.color = colour
            paint.alpha = alpha
            if (shadowRadius > 0f) paint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColour) else paint.clearShadowLayer()
            lines.forEachIndexed { i, line ->
                val lineW = paint.measureText(fullLines.getOrElse(i) { line })
                val x = if (item.align == TextAlign.LEFT) left else cx - lineW / 2f
                canvas.drawText(line, x, baseline0 + i * lineH, paint)
            }
        }
        if (item.glow > 0f) {
            // Light around the letters: the text blurred in its own colour, twice for a soft core.
            val glow = withAlpha(item.colour, (item.glow * 0.9f * f.alpha * 255).toInt())
            pass(item.colour, size * 0.35f * item.glow, 0f, glow)
            pass(item.colour, size * 0.12f * item.glow, 0f, glow)
            pass(item.colour, 0f, 0f, 0)
        } else if (item.shadow) {
            pass(item.colour, size * 0.08f, size * 0.04f, withAlpha(Color.BLACK, (150 * f.alpha).toInt()))
        } else {
            pass(item.colour, 0f, 0f, 0)
        }
        canvas.restore()
    }

    private fun withAlpha(colour: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(colour), Color.green(colour), Color.blue(colour))
}
