package app.headsup.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import app.headsup.core.raw.Dng
import app.headsup.core.raw.RawDevelop
import app.headsup.core.raw.RawImage

/**
 * Raw (DNG) photos developed by the app itself, from the sensor data, instead of Android's 8-bit
 * decoder: exposure, highlights, shadows and white balance work on the raw values. The last file
 * read stays in memory, so moving a slider only develops it again.
 */
object RawPhotos {
    private var cached: Pair<Uri, RawImage>? = null
    private val lock = Any()

    /** Whether [uri] starts like a TIFF/DNG file (whatever its name says). */
    fun looksRaw(context: Context, uri: Uri): Boolean = runCatching {
        val head = ByteArray(4)
        val n = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: 0
        n == 4 && ((head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte() && head[3] == 0.toByte()) ||
            (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2] == 0.toByte() && head[3] == 42.toByte()))
    }.getOrDefault(false)

    /** Size of the developed picture (upright), or null when the app cannot develop the file. */
    fun size(context: Context, uri: Uri): Pair<Int, Int>? {
        // From the file's directories only (its first few MB), not by decoding the whole picture.
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(4 shl 20)
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
        }.getOrNull() ?: return null
        return Dng.probe(head)
    }

    /**
     * [uri] developed with [develop], at most about [maxSide] on its long side, upright; null when
     * the file is not one the app can develop (the reason in [lastError]), so callers fall back.
     */
    fun decode(context: Context, uri: Uri, develop: RawDevelop, maxSide: Int): Bitmap? = try {
        developed(context, uri, develop, maxSide)
    } catch (e: OutOfMemoryError) {
        // Too large for the memory left: the caller falls back to Android's decoder.
        synchronized(lock) { cached = null }
        lastError = "Not enough memory to develop this file"
        null
    }

    private fun developed(context: Context, uri: Uri, develop: RawDevelop, maxSide: Int): Bitmap? {
        val raw = read(context, uri) ?: return null
        val full = maxOf(raw.developedWidth, raw.developedHeight)
        val step = ((full + maxSide - 1) / maxSide).coerceAtLeast(1)
        val out = Dng.develop(raw, develop, step)
        val bitmap = Bitmap.createBitmap(out.pixels, out.width, out.height, Bitmap.Config.ARGB_8888)
        val degrees = when (raw.orientation) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    @Volatile
    var lastError: String? = null
        private set

    /** The whole file in one array of its exact size (no growing copies of a 70 MB file). */
    private fun readAll(context: Context, uri: Uri): ByteArray {
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        return context.contentResolver.openInputStream(uri)?.use { s ->
            if (size in 1 until Int.MAX_VALUE) {
                val buf = ByteArray(size.toInt())
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                if (n == buf.size) buf else buf.copyOf(n)
            } else s.readBytes()
        } ?: error("Cannot open the file")
    }

    private fun read(context: Context, uri: Uri): RawImage? = synchronized(lock) {
        cached?.takeIf { it.first == uri }?.let { return it.second }
        return runCatching {
            cached = null // the previous file's data goes before the next one is read
            val bytes = readAll(context, uri)
            Dng.read(bytes)
        }.onFailure { lastError = if (it is OutOfMemoryError) "Not enough memory to develop this file" else it.message }.getOrNull()?.also {
            lastError = null
            cached = uri to it
        }
    }
}
