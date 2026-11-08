package app.headsup.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.headsup.core.edit.TextAlign
import app.headsup.core.edit.TextAnimation
import app.headsup.core.edit.TextFields
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
    fun key(items: List<TextItem>, timelineMs: Long, values: Map<String, String> = emptyMap()): String =
        items.mapNotNull { raw -> val item = filled(raw, values); TextAnimation.frame(item, timelineMs)?.let { "${item.hashCode()}:$it" } }.joinToString("|")

    /** [item] with its data fields (`{ascent}`, `{place}`…) filled in from [values]. */
    private fun filled(item: TextItem, values: Map<String, String>) =
        if (TextFields.hasFields(item.text)) item.copy(text = TextFields.fill(item.text, values)) else item

    fun draw(canvas: Canvas, width: Float, height: Float, items: List<TextItem>, timelineMs: Long, values: Map<String, String> = emptyMap()) {
        for (raw in items) {
            val item = filled(raw, values)
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

    /** Logos, read once per file (the preview and an export draw them on every frame). */
    private val logos = android.util.LruCache<String, android.graphics.Bitmap>(6)

    private fun logo(path: String): android.graphics.Bitmap? = logos.get(path) ?: runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        // At most 1024 px across: plenty for a corner of a 4K frame.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()?.also { logos.put(path, it) }

    /**
     * Where [item] sits on a [w]×[h] frame before it is turned: its box in pixels (the letters'
     * block, or the logo's picture). Turned by [TextItem.angle] about the box's middle.
     */
    fun box(item: TextItem, w: Float, h: Float, values: Map<String, String> = emptyMap()): android.graphics.RectF {
        val it2 = filled(item, values)
        if (it2.isLogo) {
            val bmp = it2.image?.let { logo(it) }
            val width = it2.size * 3f * minOf(w, h)
            val height = if (bmp != null) width * bmp.height / bmp.width.coerceAtLeast(1) else width
            val cx = it2.x * w
            val cy = it2.y * h
            return android.graphics.RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
        }
        val size = it2.size * minOf(w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = typeface(it2.font); textSize = size }
        val lines = it2.text.split('\n')
        val blockW = lines.maxOf { paint.measureText(it) }
        val blockH = size * 1.2f * lines.size
        val cx = it2.x * w
        val cy = it2.y * h
        val left = if (it2.align == TextAlign.LEFT) cx else cx - blockW / 2f
        return android.graphics.RectF(left, cy - blockH / 2f, left + blockW, cy + blockH / 2f)
    }

    /** A logo: its picture at the text's place and timing, as wide as size × 3 of the frame's short side. */
    private fun drawLogo(canvas: Canvas, w: Float, h: Float, item: TextItem, f: TextFrame) {
        val bmp = logo(item.image ?: return) ?: return
        val width = item.size * 3f * minOf(w, h) * f.scale
        val height = width * bmp.height / bmp.width.coerceAtLeast(1)
        val cx = item.x * w
        val cy = item.y * h - f.offset * height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f * f.alpha * item.opacity).toInt().coerceIn(0, 255) }
        canvas.save()
        canvas.rotate(item.angle, cx, cy)
        canvas.drawBitmap(bmp, null, android.graphics.RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f), paint)
        canvas.restore()
    }

    private fun drawItem(canvas: Canvas, w: Float, h: Float, item: TextItem, f: TextFrame) {
        if (item.isLogo) { drawLogo(canvas, w, h, item, f); return }
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
        // Turned about the middle of its block.
        if (item.angle != 0f) canvas.rotate(item.angle, left + blockW / 2f, cy)
        canvas.scale(f.scale, f.scale, if (item.align == TextAlign.LEFT) left else cx, cy)
        val alpha = (f.alpha * Color.alpha(item.colour)).toInt().coerceIn(0, 255)
        val baseline0 = top + size
        if (item.cutout) {
            // The frame covered in the colour, the letters cut out of it: the picture shows through them.
            canvas.restore()
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            canvas.drawColor(withAlpha(item.colour, alpha))
            canvas.save()
            if (item.angle != 0f) canvas.rotate(item.angle, left + blockW / 2f, cy)
            canvas.scale(f.scale, f.scale, if (item.align == TextAlign.LEFT) left else cx, cy)
            paint.color = Color.BLACK
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            lines.forEachIndexed { i, line ->
                val lineW = paint.measureText(fullLines.getOrElse(i) { line })
                val x = if (item.align == TextAlign.LEFT) left else cx - lineW / 2f
                canvas.drawText(line, x, baseline0 + i * lineH, paint)
            }
            canvas.restore()
            canvas.restoreToCount(layer)
            return
        }
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
