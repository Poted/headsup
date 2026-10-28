package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.BrushMask
import app.headsup.core.color.BrushStroke
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKind
import kotlin.math.roundToInt

/**
 * The brush masks of a clip as one picture for the shaders: one slot per mask, side by side,
 * coverage in grey (opaque, so nothing is lost to premultiplied alpha). Linear filtering smooths
 * the coverage between its pixels.
 */
object BrushAtlas {
    /** Longer side of one slot, in pixels: plenty for soft brush edges. */
    private const val SLOT = 640

    /** Slot size for a frame of [aspect] (width / height). */
    fun slotSize(aspect: Float): Pair<Int, Int> =
        if (aspect >= 1f) SLOT to (SLOT / aspect).roundToInt().coerceAtLeast(1) else (SLOT * aspect).roundToInt().coerceAtLeast(1) to SLOT

    /** All strokes but the one being painted, already drawn: while a finger paints, only its stroke is drawn again. */
    private class Drawn(val strokes: List<BrushStroke>, val softness: Float, val width: Int, val height: Int, val map: FloatArray)

    private val drawn = HashMap<Int, Drawn>()

    private fun coverage(context: Context, slot: Int, mask: ColourMask, w: Int, h: Int): FloatArray {
        val refs = mask.strokes.mapNotNull { it.reference }.distinct().mapNotNull { id -> BrushReferences.get(context, id)?.let { id to it } }.toMap()
        val earlier = mask.strokes.dropLast(1)
        val cached = synchronized(drawn) { drawn[slot] }
        val base = if (cached != null && cached.width == w && cached.height == h && cached.softness == mask.softness && cached.strokes == earlier) {
            cached.map
        } else {
            BrushMask.rasterize(earlier, w, h, mask.softness, refs).also { synchronized(drawn) { drawn[slot] = Drawn(earlier, mask.softness, w, h, it) } }
        }
        return BrushMask.rasterize(listOf(mask.strokes.last()), w, h, mask.softness, refs, start = base)
    }

    /** Null when no mask is painted. */
    fun build(context: Context, masks: List<ColourMask>, aspect: Float): Bitmap? {
        if (masks.none { it.kind == MaskKind.BRUSH && it.strokes.isNotEmpty() }) return null
        val (w, h) = slotSize(aspect)
        val pixels = IntArray(w * ColourMask.MAX_PER_CLIP * h) { 0xFF000000.toInt() }
        val stride = w * ColourMask.MAX_PER_CLIP
        masks.take(ColourMask.MAX_PER_CLIP).forEachIndexed { slot, m ->
            if (m.kind != MaskKind.BRUSH || m.strokes.isEmpty()) return@forEachIndexed
            val map = coverage(context, slot, m, w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = (map[y * w + x] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[y * stride + slot * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        return Bitmap.createBitmap(pixels, stride, h, Bitmap.Config.ARGB_8888)
    }

    /** A 1×1 black atlas for clips without brush masks (the shaders always need one). */
    val EMPTY: Bitmap by lazy { Bitmap.createBitmap(intArrayOf(0xFF000000.toInt()), 1, 1, Bitmap.Config.ARGB_8888) }
}
