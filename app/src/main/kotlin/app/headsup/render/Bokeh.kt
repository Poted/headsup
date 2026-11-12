package app.headsup.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import app.headsup.core.color.ColourMask
import app.headsup.core.photo.LensBlur

/**
 * The background of a photo blurred as by a wide-open lens ([LensBlur]): worked out on a smaller
 * copy (a blur needs no detail) and laid over the photo, so a full-size export costs little more
 * than the preview. What stays sharp is the subject found on the photo, or one of its masks.
 */
object Bokeh {
    /** Long side the blur is worked out at: [PREVIEW] while editing, [EXPORT] for the file. */
    const val PREVIEW = 1024
    const val EXPORT = 1600

    /**
     * The blurred background of [photo] (upright, as shown) as a layer the size of a [side]-long
     * copy, transparent where it stays sharp; null when there is nothing to blur or nothing to keep.
     */
    fun layer(context: Context, photo: Bitmap, key: String, effects: EffectSettings, masks: List<ColourMask>, side: Int): Bitmap? =
        layerWithOutline(context, photo, key, effects, masks, side)?.first

    /**
     * [layer], with the outline of what stays sharp (white along the edge, a dark rim outside it,
     * the copy's size) to show for a moment what was found.
     */
    fun layerWithOutline(context: Context, photo: Bitmap, key: String, effects: EffectSettings, masks: List<ColourMask>, side: Int): Pair<Bitmap, Bitmap>? {
        if (effects.bokeh <= 0f) return null
        val small = app.headsup.color.Segmenter.scaled(photo, side)
        val w = small.width
        val h = small.height
        // What stays sharp, at the copy's size.
        val sharp: Bitmap = effects.bokehMask?.let { i -> masks.getOrNull(i)?.let { app.headsup.color.TextMasks.alphaOf(context, it, w.toFloat() / h, 0L) } }
            ?: app.headsup.color.SubjectMaps.find(context, key, photo)
            ?: return null
        val keepBmp = Bitmap.createScaledBitmap(sharp, w, h, true)
        val alpha = IntArray(w * h)
        keepBmp.getPixels(alpha, 0, w, 0, 0, w, h)
        val keep = FloatArray(w * h) { i -> (alpha[i] ushr 24) / 255f }
        // The other side: what the mask covers blurred, the rest sharp.
        if (effects.bokehInvert) for (i in keep.indices) keep[i] = 1f - keep[i]
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val out = LensBlur.background(px, w, h, keep, LensBlur.radiusFor(effects.bokeh, maxOf(w, h)), effects.bokehHighlights, oval = effects.bokehOval)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888) to outline(keep, w, h)
    }

    /** The edge of [keep] ([w]×[h]): a white line two pixels wide, and a soft dark rim just outside it. */
    private fun outline(keep: FloatArray, w: Int, h: Int): Bitmap {
        val inside = BooleanArray(w * h) { keep[it] >= 0.5f }
        // Distance (in steps) from the edge, up to 3, on the outside and inside.
        val dist = IntArray(w * h) { 99 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val me = inside[i]
            if ((x > 0 && inside[i - 1] != me) || (x < w - 1 && inside[i + 1] != me) || (y > 0 && inside[i - w] != me) || (y < h - 1 && inside[i + w] != me)) dist[i] = 0
        }
        repeat(3) { step ->
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (dist[i] != 99) continue
                if ((x > 0 && dist[i - 1] == step) || (x < w - 1 && dist[i + 1] == step) || (y > 0 && dist[i - w] == step) || (y < h - 1 && dist[i + w] == step)) dist[i] = step + 1
            }
        }
        val px = IntArray(w * h) { i ->
            val d = dist[i]
            when {
                d <= 1 -> 0xFFFFFFFF.toInt()
                d <= 3 && !inside[i] -> 0x66000000
                else -> 0
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** [photo] with [layer] laid over it (a copy, in the photo's own config); [degrees] turns the layer to the photo's own orientation. */
    fun over(photo: Bitmap, layer: Bitmap, degrees: Int = 0, inPlace: Boolean = false): Bitmap {
        // A full-size export draws on the photo itself when it can (a copy of a large raw is a lot of memory).
        val out = if (inPlace && photo.isMutable) photo else photo.copy(photo.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (degrees == 0) {
            canvas.drawBitmap(layer, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), paint)
        } else {
            // The layer is upright; the photo is stored turned by [degrees]: turned back onto it.
            val turned = degrees == 90 || degrees == 270
            val uw = (if (turned) out.height else out.width).toFloat()
            val uh = (if (turned) out.width else out.height).toFloat()
            val m = Matrix().apply {
                postScale(uw / layer.width, uh / layer.height)
                postTranslate(-uw / 2f, -uh / 2f)
                postRotate(-degrees.toFloat())
                postTranslate(out.width / 2f, out.height / 2f)
            }
            canvas.drawBitmap(layer, m, paint)
        }
        return out
    }
}
