package app.headsup.media

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri

/**
 * EXIF of a photo read through a file descriptor: the reader then seeks to the tags it needs
 * instead of copying the stream into memory (a 70 MB raw file would otherwise be held whole).
 */
object Exif {
    fun read(context: Context, uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.getOrNull() ?: runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()

    fun orientation(context: Context, uri: Uri): Int =
        read(context, uri)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL

    /** The small preview a camera stores in the file, if any. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? = runCatching { read(context, uri)?.thumbnailBitmap }.getOrNull()
}
