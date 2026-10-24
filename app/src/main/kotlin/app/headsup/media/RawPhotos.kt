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
        val raw = read(context, uri) ?: return null
        val w = raw.width / 2
        val h = raw.height / 2
        return if (raw.orientation == 6 || raw.orientation == 8) h to w else w to h
    }

    /**
     * [uri] developed with [develop], at most about [maxSide] on its long side, upright; null when
     * the file is not one the app can develop (the reason in [lastError]), so callers fall back.
     */
    fun decode(context: Context, uri: Uri, develop: RawDevelop, maxSide: Int): Bitmap? {
        val raw = read(context, uri) ?: return null
        val half = maxOf(raw.width, raw.height) / 2
        val step = ((half + maxSide - 1) / maxSide).coerceAtLeast(1)
        val out = Dng.develop(raw, develop, step)
        val bitmap = Bitmap.createBitmap(out.pixels, out.width, out.height, Bitmap.Config.ARGB_8888)
        val degrees = when (raw.orientation) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    @Volatile
    var lastError: String? = null
        private set

    private fun read(context: Context, uri: Uri): RawImage? = synchronized(lock) {
        cached?.takeIf { it.first == uri }?.let { return it.second }
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
            Dng.read(bytes)
        }.onFailure { lastError = it.message }.getOrNull()?.also {
            lastError = null
            cached = uri to it
        }
    }
}
