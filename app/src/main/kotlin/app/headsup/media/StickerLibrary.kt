package app.headsup.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Sticker packs kept by the app (frames, marks, stickers: pictures, PNG with transparency best),
 * each a folder of pictures, for every project. Imported from a few pictures at once or a ZIP.
 */
object StickerLibrary {
    data class Pack(val name: String, val stickers: List<File>)

    /** Where [save] puts pictures kept from the app (a logo saved as a sticker). */
    const val MINE = "My stickers"

    private val pictures = setOf("png", "webp", "jpg", "jpeg")

    private fun root(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().ifEmpty { "Pack" }.take(40)

    /** The packs, My stickers first, then by name; empty folders left out. */
    fun packs(context: Context): List<Pack> = root(context).listFiles { f -> f.isDirectory }.orEmpty()
        .map { dir -> Pack(dir.name, dir.listFiles { f -> f.extension.lowercase() in pictures }.orEmpty().sortedBy { it.name.lowercase() }) }
        .filter { it.stickers.isNotEmpty() }
        .sortedWith(compareBy<Pack> { it.name != MINE }.thenBy { it.name.lowercase() })

    /** A pack name not yet taken, from [wanted]. */
    private fun freeName(context: Context, wanted: String): String {
        val base = safe(wanted)
        var name = base
        var n = 2
        while (File(root(context), name).exists()) name = "$base ${n++}"
        return name
    }

    /** [uris] (pictures) as a new pack named [name]; how many were kept. */
    fun importPictures(context: Context, uris: List<Uri>, name: String): Int {
        val dir = File(root(context), freeName(context, name)).apply { mkdirs() }
        var kept = 0
        for ((i, uri) in uris.withIndex()) {
            val ext = context.contentResolver.getType(uri)?.substringAfter('/')?.let { if (it == "jpeg") "jpg" else it }?.takeIf { it in pictures } ?: "png"
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> File(dir, "%03d.%s".format(i, ext)).outputStream().use { input.copyTo(it) } }
                kept++
            }
        }
        if (kept == 0) dir.deleteRecursively()
        return kept
    }

    /** The pictures in the ZIP [uri] as a pack named after it (folders inside flattened); how many were kept. */
    fun importZip(context: Context, uri: Uri, name: String): Int {
        val dir = File(root(context), freeName(context, name)).apply { mkdirs() }
        var kept = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val file = entry.name.substringAfterLast('/')
                        val ext = file.substringAfterLast('.', "").lowercase()
                        // Pictures only, and never a path out of the pack's folder.
                        if (!entry.isDirectory && ext in pictures && !file.startsWith(".") && file.isNotBlank()) {
                            File(dir, "%03d_%s".format(kept, safe(file.substringBeforeLast('.'))) + ".$ext").outputStream().use { zip.copyTo(it) }
                            kept++
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        if (kept == 0) dir.deleteRecursively()
        return kept
    }

    /** The picture at [path] kept in [MINE]. */
    fun save(context: Context, path: String): Boolean = runCatching {
        val dir = File(root(context), MINE).apply { mkdirs() }
        val src = File(path)
        src.copyTo(File(dir, "${System.currentTimeMillis()}.png"), overwrite = false)
        true
    }.getOrDefault(false)

    fun delete(context: Context, pack: String) {
        File(root(context), safe(pack)).takeIf { it.parentFile == root(context) }?.deleteRecursively()
    }
}
