package app.headsup.media

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Fonts of the user's own (TTF or OTF), kept by the app for every project's texts. */
object FontLibrary {
    private fun root(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    /** The fonts kept, by name. */
    fun fonts(context: Context): List<File> = root(context).listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }.orEmpty().sortedBy { it.name.lowercase() }

    /** The font at [uri] kept; its file, or null when it is not a font Android can read. */
    fun import(context: Context, uri: Uri): File? = runCatching {
        val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Font.ttf"
        val ext = shown.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val base = shown.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().ifEmpty { "Font" }.take(40)
        var file = File(root(context), "$base.$ext")
        var n = 2
        while (file.exists()) file = File(root(context), "$base $n.$ext").also { n++ }
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        // Not a font: let go.
        if (runCatching { Typeface.createFromFile(file) }.getOrNull() == null) { file.delete(); return null }
        file
    }.getOrNull()

    fun delete(context: Context, file: File) {
        if (file.parentFile?.canonicalFile == root(context).canonicalFile) file.delete()
    }

    /** A font's name as shown: its file name without the extension. */
    fun label(path: String): String = File(path).nameWithoutExtension
}
