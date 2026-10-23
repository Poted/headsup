package app.headsup.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * The app's own copies of the videos and FIT files used in projects, so a project never loses a
 * clip when the original is moved, renamed or deleted in the gallery (or only lived in the
 * cloud). Copies live in the app's private storage, which the gallery does not show; the app
 * never deletes anything outside it.
 *
 * Each copy keeps its original file name (in its own folder), because the name carries the
 * recording time used to match clips to the watch data.
 */
class MediaLibrary(context: Context) {
    private val app = context.applicationContext
    val dir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "media").apply { mkdirs() }

    /** Copies [source] into the library, reporting bytes copied and the total (0 if unknown). */
    suspend fun import(source: Uri, onProgress: (copied: Long, total: Long) -> Unit): Uri {
        val name = displayName(source)?.let(::safeName) ?: "file"
        val total = size(source)
        val folder = File(dir, System.currentTimeMillis().toString(36) + "_" + (0..9999).random()).apply { mkdirs() }
        val target = File(folder, name)
        try {
            app.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            } ?: error("Cannot open $name")
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
        return Uri.fromFile(target)
    }

    fun isInLibrary(uri: Uri): Boolean = uri.scheme == "file" && uri.path?.startsWith(dir.absolutePath) == true

    /** Bytes taken by the library copies among [uris] (each counted once). */
    fun sizeOf(uris: Collection<Uri>): Long = uris.distinct().filter(::isInLibrary).sumOf { File(it.path!!).length() }

    /** Deletes library copies that none of [inUse] refers to. */
    fun deleteUnused(inUse: Set<Uri>) {
        val keep = inUse.filter(::isInLibrary).mapNotNull { it.path?.let(::File)?.parentFile?.absolutePath }.toSet()
        dir.listFiles()?.filter { it.isDirectory && it.absolutePath !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun size(uri: Uri): Long = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

    private fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120)
}
