package app.headsup.color

import android.content.Context
import android.net.Uri
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.CubeLut
import java.io.File

/** Keeps the imported camera LUT and the basic corrections between sessions. */
class GradeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("color_grade", Context.MODE_PRIVATE)
    private val lutFile get() = File(context.filesDir, "camera.cube")

    val lutName: String? get() = prefs.getString(KEY_LUT_NAME, null)?.takeIf { lutFile.exists() }

    /** Parses and stores a `.cube` file; throws if it is not a valid 3D LUT. */
    fun importLut(uri: Uri, name: String): CubeLut {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Cannot open $name")
        val lut = CubeLut.parse(text)
        lutFile.writeText(text)
        prefs.edit().putString(KEY_LUT_NAME, name).apply()
        return lut
    }

    fun loadLut(): CubeLut? = lutName?.let { runCatching { CubeLut.parse(lutFile.readText()) }.getOrNull() }

    fun removeLut() {
        lutFile.delete()
        prefs.edit().remove(KEY_LUT_NAME).apply()
    }

    fun loadGrade() = ColorGrade(
        exposure = prefs.getFloat("exposure", 0f),
        brightness = prefs.getFloat("brightness", 0f),
        contrast = prefs.getFloat("contrast", 0f),
        shadows = prefs.getFloat("shadows", 0f),
        highlights = prefs.getFloat("highlights", 0f),
        saturation = prefs.getFloat("saturation", 0f),
        temperature = prefs.getFloat("temperature", 0f),
        vignette = prefs.getFloat("vignette", 0f),
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
            .apply()
    }

    private companion object {
        const val KEY_LUT_NAME = "lut_name"
    }
}
