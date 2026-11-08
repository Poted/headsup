package app.headsup.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.headsup.core.photo.ExposureFusion
import app.headsup.export.MediaStoreOutput
import java.io.File

/**
 * An exposure bracket (AEB: the same view at several exposures, e.g. from a drone) merged into
 * one photo with the detail of each ([ExposureFusion]), saved next to the app's exports with the
 * middle shot's date, place and camera.
 */
object Bracket {
    /** Long side of the merged photo: as large as the phone's memory comfortably allows for a bracket. */
    private fun sideFor(count: Int) = if (count <= 3) 2560 else 2048

    /**
     * Merges [uris] (raw photos developed as [develop] says); [onProgress] gets 0..1. The saved
     * photo's uri, or null when fewer than two could be read.
     */
    fun merge(context: Context, uris: List<Uri>, raw: (Uri) -> Bitmap?, onProgress: (Float) -> Unit = {}): Uri? {
        val side = sideFor(uris.size)
        val read = uris.mapIndexedNotNull { k, uri ->
            onProgress(0.3f * k / uris.size)
            (raw(uri) ?: decodeUpright(context, uri, side))?.let { uri to it }
        }
        if (read.size < 2) return null
        // All at the first one's size (a bracket is shot at one size; scaled in case one is not).
        val w0 = read[0].second.width
        val h0 = read[0].second.height
        val scale = minOf(1f, side.toFloat() / maxOf(w0, h0))
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        val pixels = read.map { (_, b) ->
            val s = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h)
            s.getPixels(px, 0, w, 0, 0, w, h)
            if (s !== b) s.recycle()
            b.recycle()
            px
        }
        onProgress(0.35f)
        // Lined up on the middle exposure (by brightness).
        val order = pixels.indices.sortedBy { i -> mean(pixels[i]) }
        val ref = order[order.size / 2]
        var maxShift = 0
        val aligned = pixels.mapIndexed { i, px ->
            if (i == ref) px else {
                val (dx, dy) = ExposureFusion.align(pixels[ref], px, w, h, maxShift = maxOf(w, h) / 20)
                maxShift = maxOf(maxShift, kotlin.math.abs(dx), kotlin.math.abs(dy))
                ExposureFusion.shifted(px, w, h, dx, dy)
            }
        }
        onProgress(0.45f)
        val fused = ExposureFusion.fuse(aligned, w, h) { p -> onProgress(0.45f + 0.45f * p) }
        // The edges a shift brought in from outside are cut off.
        val cut = maxShift.coerceAtMost(minOf(w, h) / 10)
        val full = Bitmap.createBitmap(fused, w, h, Bitmap.Config.ARGB_8888)
        val out = if (cut > 0) Bitmap.createBitmap(full, cut, cut, w - 2 * cut, h - 2 * cut) else full
        // Written to a file first, so the middle shot's EXIF can be put in.
        val tmp = File(context.cacheDir, "bracket_${System.currentTimeMillis()}.jpg")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        copyExif(context, read[ref].first, tmp)
        onProgress(0.95f)
        val name = "HDR_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val uri = MediaStoreOutput.savePhoto(context, name) { stream -> tmp.inputStream().use { it.copyTo(stream) } }
        tmp.delete()
        onProgress(1f)
        return uri
    }

    private fun mean(px: IntArray): Double {
        var s = 0L
        var i = 0
        while (i < px.size) { val c = px[i]; s += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF); i += 7 }
        return s.toDouble() / (px.size / 7 + 1)
    }

    private fun decodeUpright(context: Context, uri: Uri, side: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: return@runCatching null
        val degrees = when (Exif.orientation(context, uri)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true).also { raw.recycle() }
    }.getOrNull()

    /** The date, place and camera of [from] written into [to] (upright, so no orientation). */
    private fun copyExif(context: Context, from: Uri, to: File) {
        runCatching {
            val src = context.contentResolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(to.path)
            for (tag in listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER,
            )) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }
    }
}
