package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.BrushReference
import app.headsup.core.color.MaskKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Pictures that edge-finding brush strokes were painted on ([BrushReference]), kept in the app's
 * files (small, compressed) and in memory, by id. The picture is the one before any colour change,
 * so edges are found the same way however the clip is graded.
 */
object BrushReferences {
    private val cache = HashMap<String, BrushReference?>()

    private fun file(context: Context, id: String) = File(File(context.filesDir, "brush"), "$id.ref")

    /** Keeps [bitmap] (the picture with the mask at [placement]) and returns its id. */
    fun create(context: Context, bitmap: Bitmap, placement: MaskKey): String {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = FloatArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = ((p shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (p and 0xFF) / 255f
        }
        val id = UUID.randomUUID().toString()
        synchronized(cache) { cache[id] = BrushReference(w, h, rgb, placement) }
        val target = file(context.applicationContext, id)
        Thread {
            runCatching {
                target.parentFile?.mkdirs()
                DataOutputStream(GZIPOutputStream(target.outputStream().buffered())).use { out ->
                    out.writeInt(w)
                    out.writeInt(h)
                    out.writeLong(placement.timeMs)
                    for (v in floatArrayOf(placement.centerX, placement.centerY, placement.angleDegrees, placement.sizeX, placement.sizeY)) out.writeFloat(v)
                    val bytes = ByteArray(w * h * 3)
                    for (i in pixels.indices) {
                        bytes[i * 3] = (pixels[i] shr 16).toByte()
                        bytes[i * 3 + 1] = (pixels[i] shr 8).toByte()
                        bytes[i * 3 + 2] = pixels[i].toByte()
                    }
                    out.write(bytes)
                }
            }
        }.start()
        return id
    }

    /** Keeps coverage ([values], 0..1, [width]×[height]) as a grey picture, e.g. an object found in a frame. */
    fun create(context: Context, width: Int, height: Int, values: FloatArray, placement: MaskKey): String {
        val pixels = IntArray(width * height) { i ->
            val v = (values[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return create(context, Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), placement)
    }

    /** The picture kept as [id], or null when it is gone. */
    fun get(context: Context, id: String): BrushReference? {
        synchronized(cache) { if (id in cache) return cache[id] }
        val ref = runCatching {
            DataInputStream(GZIPInputStream(file(context, id).inputStream().buffered())).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val placement = MaskKey(input.readLong(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                val bytes = ByteArray(w * h * 3)
                input.readFully(bytes)
                BrushReference(w, h, FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }, placement)
            }
        }.getOrNull()
        synchronized(cache) {
            if (cache.size > 16) cache.clear()
            cache[id] = ref
        }
        return ref
    }
}
