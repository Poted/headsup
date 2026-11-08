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
        val large = Segmenter.scaled(picture, SIDE)
        val refined = detect(context, large, large.width, large.height, centre = true) ?: return null
        val w = large.width
        val h = large.height
        val grey = Bitmap.createBitmap(IntArray(w * h) { i ->
            val v = (refined[i].coerceIn(0f, 1f) * 255f).roundToInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }, w, h, Bitmap.Config.ARGB_8888)
        runCatching { file(context, key).outputStream().use { grey.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return alpha(grey).also { memory.put(key, it) }
    }

    /**
     * The subject in [picture] as a [w]×[h] map: the people, animals and vehicles the scene model
     * finds, made exact by MobileSAM and laid onto the picture's edges; with [centre], else what
     * MobileSAM finds in the middle (a photo's subject; a video's frames without one have none).
     */
    fun detect(context: Context, picture: Bitmap, w: Int, h: Int, centre: Boolean): FloatArray? {
        val seg = Segmenter
        val small = if (picture.width == w && picture.height == h) picture else Bitmap.createScaledBitmap(picture, w, h, true)
        val guide = seg.rgb(small)
        val groups = seg.scene(context, small, w, h)
        fun enough(m: FloatArray) = m.count { it > 0.5f } >= w * h / 500
        val things = Scene.entries.filter { it.thing }
        val coarse = FloatArray(w * h) { i -> things.maxOf { groups[it.ordinal][i] } }
        val subject = if (enough(coarse)) seg.exact(context, picture, coarse, w, h) else {
            if (!centre) return null
            val reading = seg.read(context, picture)
            seg.objects(context, reading, listOf(Segmenter.Prompt(0.5f, 0.5f, Segmenter.Prompt.Label.IN)), w, h)
                .map { it.first }
                .firstOrNull { m -> app.headsup.core.color.MaskFollow.area(m) in 0.01f..0.6f }
                ?: return null
        }
        if (!enough(subject)) return null
        return seg.refine(guide, subject, w, h)
    }

    /** The long side of a clip's subject maps. */
    const val CLIP_SIDE = 512

    private var lastFrame: Triple<String, Int, Bitmap>? = null

    /**
     * The clip's subject (sequence [id]) at [fileMs] as an alpha map, the frame found nearest
     * before it; the last one is kept, so a frame shown again (or a still preview) costs nothing.
     */
    @Synchronized
    fun frameAt(context: Context, id: String, fileMs: Long): Bitmap? {
        val seq = MaskSequences.get(context, id) ?: return null
        if (seq.isEmpty) return null
        val i = seq.timesMs.indexOfLast { it <= fileMs }.coerceAtLeast(0)
        lastFrame?.let { (lid, li, bmp) -> if (lid == id && li == i) return bmp }
        val bytes = seq.frames[i]
        val px = IntArray(bytes.size) { k -> (bytes[k].toInt() and 0xFF) shl 24 }
        val bmp = Bitmap.createBitmap(px, seq.width, seq.height, Bitmap.Config.ARGB_8888)
        lastFrame = Triple(id, i, bmp)
        return bmp
    }

    /** Which of the clip's maps shows at [fileMs] (for knowing when the picture of it changes). */
    fun frameIndex(context: Context, id: String, fileMs: Long): Int =
        MaskSequences.get(context, id)?.timesMs?.indexOfLast { it <= fileMs } ?: -1

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
