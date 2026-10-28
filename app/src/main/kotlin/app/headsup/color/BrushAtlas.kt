package app.headsup.color

import android.graphics.Bitmap
import app.headsup.core.color.BrushMask
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

    /** Null when no mask is painted. */
    fun build(masks: List<ColourMask>, aspect: Float): Bitmap? {
        if (masks.none { it.kind == MaskKind.BRUSH && it.strokes.isNotEmpty() }) return null
        val (w, h) = slotSize(aspect)
        val pixels = IntArray(w * ColourMask.MAX_PER_CLIP * h) { 0xFF000000.toInt() }
        val stride = w * ColourMask.MAX_PER_CLIP
        masks.take(ColourMask.MAX_PER_CLIP).forEachIndexed { slot, m ->
            if (m.kind != MaskKind.BRUSH || m.strokes.isEmpty()) return@forEachIndexed
            val map = BrushMask.rasterize(m.strokes, w, h)
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
