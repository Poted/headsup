package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt

/**
 * The subject of a photo (people, animals, vehicles the scene model finds, else what MobileSAM
 * finds in the middle), for a text standing behind it: an alpha map over the whole upright photo,
 * found once per photo and kept in the app's files.
 */
object SubjectMaps {
    /** The map's long side: enough for the outline of a person against large letters. */
    private const val SIDE = 1024

    private val memory = LruCache<String, Bitmap>(4)

    private fun file(context: Context, key: String) =
        File(File(context.filesDir, "subject").apply { mkdirs() }, "${key.hashCode().toUInt().toString(16)}_${key.length}.png")

    /** The map kept for the photo [key] (its uri), or null when it was never found. */
    fun get(context: Context, key: String): Bitmap? = memory.get(key) ?: run {
        val f = file(context, key)
        if (!f.exists()) return null
        val grey = BitmapFactory.decodeFile(f.path) ?: return null
        alpha(grey).also { memory.put(key, it) }
    }

    /** The map of the photo [key], found in [picture] (upright, as shown) when not yet kept; null when nothing stands out. */
    fun find(context: Context, key: String, picture: Bitmap): Bitmap? {
        get(context, key)?.let { return it }
        val seg = Segmenter
        val large = seg.scaled(picture, SIDE)
        val w = large.width
        val h = large.height
        val guide = seg.rgb(large)
        val groups = seg.scene(context, large, w, h)
        fun enough(m: FloatArray) = m.count { it > 0.5f } >= w * h / 500
        val coarse = FloatArray(w * h) { i -> Scene.entries.filter { it.thing }.maxOf { groups[it.ordinal][i] } }
        val subject = if (enough(coarse)) seg.exact(context, large, coarse, w, h) else {
            val reading = seg.read(context, large)
            seg.objects(context, reading, listOf(Segmenter.Prompt(0.5f, 0.5f, Segmenter.Prompt.Label.IN)), w, h)
                .map { it.first }
                .firstOrNull { m -> app.headsup.core.color.MaskFollow.area(m) in 0.01f..0.6f }
                ?: return null
        }
        if (!enough(subject)) return null
        val refined = seg.refine(guide, subject, w, h)
        val grey = Bitmap.createBitmap(IntArray(w * h) { i ->
            val v = (refined[i].coerceIn(0f, 1f) * 255f).roundToInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }, w, h, Bitmap.Config.ARGB_8888)
        runCatching { file(context, key).outputStream().use { grey.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return alpha(grey).also { memory.put(key, it) }
    }

    /** Forgets the photo's map (found again next time). */
    fun forget(context: Context, key: String) {
        memory.remove(key)
        file(context, key).delete()
    }

    /** A grey picture as an alpha map (white: the subject). */
    private fun alpha(grey: Bitmap): Bitmap {
        val w = grey.width
        val h = grey.height
        val px = IntArray(w * h)
        grey.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = (px[i] and 0xFF) shl 24
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
