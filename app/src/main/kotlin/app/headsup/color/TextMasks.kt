package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.BrushMask
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKey
import app.headsup.core.color.MaskKind

/**
 * A mask of the picture as an alpha map over the whole upright picture, for texts that go behind
 * it (out from behind a wall, behind a person): painted and AI masks (tracked on video, with
 * their Edge and feather), lines and ovals. Colour and brightness range masks depend on each
 * pixel's colour and are not offered.
 */
object TextMasks {
    /** Whether texts can go behind [mask]. */
    fun usable(mask: ColourMask): Boolean = when (mask.kind) {
        MaskKind.BRUSH -> mask.strokes.isNotEmpty() || mask.sequence != null
        MaskKind.LINEAR, MaskKind.ELLIPSE -> true
        MaskKind.RANGE -> false
    }

    private val cache = object : LinkedHashMap<List<Any>, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, Bitmap>?) = size > 6
    }

    /** [mask] at [fileMs] of a picture of [aspect] (width / height), white where it covers; null when it cannot be drawn. */
    fun alphaOf(context: Context, mask: ColourMask, aspect: Float, fileMs: Long): Bitmap? {
        if (!usable(mask)) return null
        val m = mask.at(fileMs)
        // Only the moment matters for a tracked mask (its maps change in steps).
        val key = listOf<Any>(m.kind, m.strokes, m.sequence ?: "", m.sequenceStrokes, m.centerX, m.centerY, m.angleDegrees, m.sizeX, m.sizeY,
            m.softness, m.feather, m.edge, m.inverted, aspect, if (m.sequence != null) fileMs / 40 else 0L)
        synchronized(cache) { cache[key] }?.let { return it }
        val (map, w, h) = when (m.kind) {
            MaskKind.BRUSH -> {
                val (raw, w, h) = BrushAtlas.coverageAt(context, m, aspect, fileMs) ?: return null
                val placed = BrushMask.placed(raw, w, h, MaskKey.of(m, 0L))
                Triple(if (m.inverted) FloatArray(placed.size) { 1f - placed[it] } else placed, w, h)
            }
            MaskKind.LINEAR, MaskKind.ELLIPSE -> {
                val (sw, sh) = BrushAtlas.slotSize(aspect)
                val w = (sw / 2).coerceAtLeast(1)
                val h = (sh / 2).coerceAtLeast(1)
                val out = FloatArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[y * w + x] = m.weight(x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat())
                Triple(out, w, h)
            }
            MaskKind.RANGE -> return null
        }
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = ((map[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()) shl 24
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }
}
