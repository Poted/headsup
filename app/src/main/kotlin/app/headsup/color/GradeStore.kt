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

    /** [prefix] "" is the default grade for new clips, "global_" the grade applied to all clips. */
    fun loadGrade(prefix: String = "") = ColorGrade(
        exposure = prefs.getFloat(prefix + "exposure", 0f),
        brightness = prefs.getFloat(prefix + "brightness", 0f),
        contrast = prefs.getFloat(prefix + "contrast", 0f),
        shadows = prefs.getFloat(prefix + "shadows", 0f),
        highlights = prefs.getFloat(prefix + "highlights", 0f),
        saturation = prefs.getFloat(prefix + "saturation", 0f),
        temperature = prefs.getFloat(prefix + "temperature", 0f),
        vignette = prefs.getFloat(prefix + "vignette", 0f),
        blacks = prefs.getFloat(prefix + "blacks", 0f),
        whites = prefs.getFloat(prefix + "whites", 0f),
        vibrance = prefs.getFloat(prefix + "vibrance", 0f),
        tint = prefs.getFloat(prefix + "tint", 0f),
        skinHue = prefs.getFloat(prefix + "skin_hue", 0f),
        rolloff = prefs.getFloat(prefix + "rolloff", 0f),
        toneAmount = prefs.getFloat(prefix + "tone_amount", 0f),
        shadowHue = prefs.getFloat(prefix + "shadow_hue", ColorGrade.DEFAULT_SHADOW_HUE),
        highlightHue = prefs.getFloat(prefix + "highlight_hue", ColorGrade.DEFAULT_HIGHLIGHT_HUE),
        tame = prefs.getFloat(prefix + "tame", 0f),
        mixer = prefs.getString(prefix + "mixer", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == app.headsup.core.color.ColourMixer.SIZE } ?: app.headsup.core.color.ColourMixer.NEUTRAL,
        look = prefs.getString(prefix + "look", null),
        lookAmount = prefs.getFloat(prefix + "look_amount", 1f),
        curves = prefs.getString(prefix + "curves", null)?.split(';')?.takeIf { it.size == 4 }
            ?.map { c -> c.split(',').mapNotNull { it.toFloatOrNull() } } ?: app.headsup.core.color.ToneCurve.NEUTRAL,
        wheels = prefs.getString(prefix + "wheels", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == app.headsup.core.color.ColourWheels.SIZE } ?: app.headsup.core.color.ColourWheels.NEUTRAL,
        texture = prefs.getFloat(prefix + "texture", 0f),
        clarity = prefs.getFloat(prefix + "clarity", 0f),
        dehaze = prefs.getFloat(prefix + "dehaze", 0f),
        channelMix = floats(prefix + "channel_mix", app.headsup.core.color.ChannelMixer.SIZE) ?: app.headsup.core.color.ChannelMixer.NEUTRAL,
        bw = floats(prefix + "bw", app.headsup.core.color.BlackAndWhite.SIZE) ?: app.headsup.core.color.BlackAndWhite.NEUTRAL,
        calibration = floats(prefix + "calibration", app.headsup.core.color.Calibration.SIZE) ?: app.headsup.core.color.Calibration.NEUTRAL,
    )

    private fun floats(key: String, size: Int): List<Float>? =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == size }

    fun saveGrade(g: ColorGrade, prefix: String = "") {
        prefs.edit()
            .putFloat(prefix + "exposure", g.exposure)
            .putFloat(prefix + "brightness", g.brightness)
            .putFloat(prefix + "contrast", g.contrast)
            .putFloat(prefix + "shadows", g.shadows)
            .putFloat(prefix + "highlights", g.highlights)
            .putFloat(prefix + "saturation", g.saturation)
            .putFloat(prefix + "temperature", g.temperature)
            .putFloat(prefix + "vignette", g.vignette)
            .putFloat(prefix + "blacks", g.blacks)
            .putFloat(prefix + "whites", g.whites)
            .putFloat(prefix + "vibrance", g.vibrance)
            .putFloat(prefix + "tint", g.tint)
            .putFloat(prefix + "skin_hue", g.skinHue)
            .putFloat(prefix + "rolloff", g.rolloff)
            .putFloat(prefix + "tone_amount", g.toneAmount)
            .putFloat(prefix + "shadow_hue", g.shadowHue)
            .putFloat(prefix + "highlight_hue", g.highlightHue)
            .putFloat(prefix + "tame", g.tame)
            .putString(prefix + "mixer", g.mixer.joinToString(","))
            .putString(prefix + "look", g.look)
            .putFloat(prefix + "look_amount", g.lookAmount)
            .putString(prefix + "curves", g.curves.joinToString(";") { it.joinToString(",") })
            .putString(prefix + "wheels", g.wheels.joinToString(","))
            .putFloat(prefix + "texture", g.texture)
            .putFloat(prefix + "clarity", g.clarity)
            .putFloat(prefix + "dehaze", g.dehaze)
            .putString(prefix + "channel_mix", g.channelMix.joinToString(","))
            .putString(prefix + "bw", g.bw.joinToString(","))
            .putString(prefix + "calibration", g.calibration.joinToString(","))
            .apply()
    }

    private companion object {
        const val KEY_DEFAULT = "default_lut"
    }
}
