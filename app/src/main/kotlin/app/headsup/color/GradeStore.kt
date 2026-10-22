package app.headsup.color

import android.content.Context
import android.net.Uri
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.CubeLut
import java.io.File

/** Keeps the imported LUTs (e.g. one for the camera, one for the drone) and the basic corrections. */
class GradeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("color_grade", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "luts").apply { mkdirs() }

    init {
        // Version 0.3 kept a single LUT in camera.cube.
        val legacy = File(context.filesDir, "camera.cube")
        val legacyName = prefs.getString("lut_name", null)
        if (legacy.exists() && legacyName != null) {
            legacy.copyTo(fileFor(legacyName), overwrite = true)
            legacy.delete()
            prefs.edit().remove("lut_name").putString(KEY_DEFAULT, legacyName).apply()
        }
    }

    /** Names of the stored LUTs, alphabetically. */
    fun names(): List<String> = dir.listFiles { f -> f.extension == "cube" }.orEmpty()
        .map { it.nameWithoutExtension }
        .sortedBy { it.lowercase() }

    /** The LUT new clips get; the last one imported or picked. */
    var defaultLut: String?
        get() = prefs.getString(KEY_DEFAULT, null)?.takeIf { fileFor(it).exists() }
        set(value) = prefs.edit().putString(KEY_DEFAULT, value).apply()

    /** Parses and stores a `.cube` file under its display name; throws if it is not a valid 3D LUT. */
    fun import(uri: Uri, displayName: String): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Cannot open $displayName")
        CubeLut.parse(text)
        val name = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.()-]"), "_").ifBlank { "LUT" }
        fileFor(name).writeText(text)
        defaultLut = name
        return name
    }

    fun load(name: String): CubeLut? = runCatching { CubeLut.parse(fileFor(name).readText()) }.getOrNull()

    fun delete(name: String) {
        fileFor(name).delete()
        if (prefs.getString(KEY_DEFAULT, null) == name) prefs.edit().remove(KEY_DEFAULT).apply()
    }

    private fun fileFor(name: String) = File(dir, "$name.cube")

    fun loadGrade() = ColorGrade(
        exposure = prefs.getFloat("exposure", 0f),
        brightness = prefs.getFloat("brightness", 0f),
        contrast = prefs.getFloat("contrast", 0f),
        shadows = prefs.getFloat("shadows", 0f),
        highlights = prefs.getFloat("highlights", 0f),
        saturation = prefs.getFloat("saturation", 0f),
        temperature = prefs.getFloat("temperature", 0f),
        vignette = prefs.getFloat("vignette", 0f),
        blacks = prefs.getFloat("blacks", 0f),
        whites = prefs.getFloat("whites", 0f),
        vibrance = prefs.getFloat("vibrance", 0f),
        tint = prefs.getFloat("tint", 0f),
    )

    fun saveGrade(g: ColorGrade) {
        prefs.edit()
            .putFloat("exposure", g.exposure)
            .putFloat("brightness", g.brightness)
            .putFloat("contrast", g.contrast)
            .putFloat("shadows", g.shadows)
            .putFloat("highlights", g.highlights)
            .putFloat("saturation", g.saturation)
            .putFloat("temperature", g.temperature)
            .putFloat("vignette", g.vignette)
            .putFloat("blacks", g.blacks)
            .putFloat("whites", g.whites)
            .putFloat("vibrance", g.vibrance)
            .putFloat("tint", g.tint)
            .apply()
    }

    private companion object {
        const val KEY_DEFAULT = "default_lut"
    }
}
