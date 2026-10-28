package app.headsup.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Publishes finished files where the gallery and file managers can see them. */
object MediaStoreOutput {
    const val VIDEO_FOLDER = "DCIM/Sweet Spot"
    const val OVERLAY_FOLDER = "Download/Sweet Spot"

    fun saveVideo(context: Context, file: File, displayName: String): Uri {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return write(context, collection, displayName, "video/mp4", VIDEO_FOLDER) { out ->
            file.inputStream().use { it.copyTo(out, 1 shl 20) }
        }
    }

    /** A photo (JPEG unless [mime] says otherwise) in the camera's folder, next to the exported videos. */
    fun savePhoto(context: Context, displayName: String, mime: String = "image/jpeg", block: (OutputStream) -> Unit): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return write(context, collection, displayName, mime, VIDEO_FOLDER, block)
    }

    fun <T> writeDownload(context: Context, displayName: String, mime: String, block: (OutputStream) -> T): Uri =
        write(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, displayName, mime, OVERLAY_FOLDER) { block(it) }

    private fun write(context: Context, collection: Uri, displayName: String, mime: String, folder: String, block: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create $displayName")
        try {
            resolver.openOutputStream(uri)?.use(block) ?: error("Could not write $displayName")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
