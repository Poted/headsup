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

    fun isRaw(name: String): Boolean = name.substringAfterLast('.', "").equals("dng", ignoreCase = true)

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
