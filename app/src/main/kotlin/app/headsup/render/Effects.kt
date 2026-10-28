package app.headsup.render

import android.content.Context
import app.headsup.core.track.Metric

enum class GraphMetric(val label: String, val metric: Metric) {
    ALTITUDE("Altitude", Metric.ALTITUDE),
    HEART_RATE("Heart rate", Metric.HEART_RATE),
    SPEED("Speed", Metric.SPEED),
}

/**
 * Picture effects shared by every clip. Grain and glow change pixels (GPU shader in preview and
 * export); cinema bars and the activity graph are drawn with the dashboard overlay.
 */
data class EffectSettings(
    /** Film grain strength, 0..1. */
    val grain: Float = 0f,
    /** Grain size relative to the frame; 1 is fine 35 mm-like grain. */
    val grainSize: Float = 1f,
    /** Glow (bloom) strength, 0..1. */
    val glow: Float = 0f,
    /** Brightness above which highlights glow, 0..1. */
    val glowThreshold: Float = 0.75f,
    /** Film halation: a red-orange glow bleeding around bright highlights, 0..1. */
    val halation: Float = 0f,
    /** Target aspect ratio for black bars, or null for none. */
    val barsRatio: Float? = null,
    /** Activity profile drawn along the bottom edge, or null for none. */
    val graph: GraphMetric? = null,
    /** Whether this clip shows the dashboard (its layout and widgets are the project's). */
    val dashboard: Boolean = true,
) {
    val changesPixels: Boolean get() = grain > 0f || glow > 0f || halation > 0f
}

class EffectPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("effects", Context.MODE_PRIVATE)

    fun load() = EffectSettings(
        grain = prefs.getFloat("grain", 0f),
        grainSize = prefs.getFloat("grain_size", 1f),
        glow = prefs.getFloat("glow", 0f),
        glowThreshold = prefs.getFloat("glow_threshold", 0.75f),
        halation = prefs.getFloat("halation", 0f),
        barsRatio = prefs.getFloat("bars_ratio", 0f).takeIf { it > 0f },
        graph = GraphMetric.entries.firstOrNull { it.name == prefs.getString("graph", null) },
    )

    fun save(e: EffectSettings) {
        prefs.edit()
            .putFloat("grain", e.grain)
            .putFloat("grain_size", e.grainSize)
            .putFloat("glow", e.glow)
            .putFloat("glow_threshold", e.glowThreshold)
            .putFloat("halation", e.halation)
            .putFloat("bars_ratio", e.barsRatio ?: 0f)
            .putString("graph", e.graph?.name)
            .apply()
    }
}
