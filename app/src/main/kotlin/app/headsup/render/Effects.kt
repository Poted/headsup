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
    /** How much of the grain is in colour (each channel its own) rather than in brightness only, 0..1. */
    val grainColour: Float = 0.3f,
    /** How far glow and halation reach, × the default (0.5..2.5). */
    val glowSpread: Float = 1f,
    /** Colour of the glow: cool (−) to warm (+), −1..1. */
    val glowWarmth: Float = 0f,
    /** A lens's colour fringes towards the corners (lateral chromatic aberration), 0..1. */
    val lensAberration: Float = 0f,
    /** A lens's softness towards the corners, 0..1. */
    val lensSoftness: Float = 0f,
    /** Target aspect ratio for black bars, or null for none. */
    val barsRatio: Float? = null,
    /** How the bars come: there all along, sliding in at the clip's start, or in and out again. */
    val barsMotion: BarsMotion = BarsMotion.STILL,
    /** How far in the bars are at the moment drawn, 0..1 (set while drawing, not saved). */
    val barsAmount: Float = 1f,
    /** Activity profile drawn along the bottom edge, or null for none. */
    val graph: GraphMetric? = null,
    /** Whether this clip shows the dashboard (its layout and widgets are the project's). */
    val dashboard: Boolean = true,
) {
    val changesPixels: Boolean get() = grain > 0f || glow > 0f || halation > 0f || lens

    /** The lens effect is on. */
    val lens: Boolean get() = lensAberration > 0f || lensSoftness > 0f

    /** These effects with the ones changed from [before] to [after] set as in [after] (a sync to other clips). */
    fun synced(before: EffectSettings, after: EffectSettings) = EffectSettings(
        grain = if (after.grain != before.grain) after.grain else grain,
        grainSize = if (after.grainSize != before.grainSize) after.grainSize else grainSize,
        glow = if (after.glow != before.glow) after.glow else glow,
        glowThreshold = if (after.glowThreshold != before.glowThreshold) after.glowThreshold else glowThreshold,
        halation = if (after.halation != before.halation) after.halation else halation,
        grainColour = if (after.grainColour != before.grainColour) after.grainColour else grainColour,
        glowSpread = if (after.glowSpread != before.glowSpread) after.glowSpread else glowSpread,
        glowWarmth = if (after.glowWarmth != before.glowWarmth) after.glowWarmth else glowWarmth,
        lensAberration = if (after.lensAberration != before.lensAberration) after.lensAberration else lensAberration,
        lensSoftness = if (after.lensSoftness != before.lensSoftness) after.lensSoftness else lensSoftness,
        barsRatio = if (after.barsRatio != before.barsRatio) after.barsRatio else barsRatio,
        barsMotion = if (after.barsMotion != before.barsMotion) after.barsMotion else barsMotion,
        graph = if (after.graph != before.graph) after.graph else graph,
        dashboard = if (after.dashboard != before.dashboard) after.dashboard else dashboard,
    )
}

enum class BarsMotion(val label: String) {
    STILL("Still"), SLIDE_IN("Slide in"), IN_OUT("In & out");

    /** How far in the bars are at [ms] into a clip [lengthMs] long (timeline time), 0..1. */
    fun amount(ms: Long, lengthMs: Long): Float {
        if (this == STILL) return 1f
        // About a second, less in a short clip.
        val d = minOf(1_200L, (lengthMs / 3).coerceAtLeast(1L)).toFloat()
        fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val inside = ease(ms / d)
        return if (this == IN_OUT && lengthMs > 0L) minOf(inside, ease((lengthMs - ms) / d)) else inside
    }
}

class EffectPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("effects", Context.MODE_PRIVATE)

    fun load() = EffectSettings(
        grain = prefs.getFloat("grain", 0f),
        grainSize = prefs.getFloat("grain_size", 1f),
        glow = prefs.getFloat("glow", 0f),
        glowThreshold = prefs.getFloat("glow_threshold", 0.75f),
        halation = prefs.getFloat("halation", 0f),
        grainColour = prefs.getFloat("grain_colour", 0.3f),
        glowSpread = prefs.getFloat("glow_spread", 1f),
        glowWarmth = prefs.getFloat("glow_warmth", 0f),
        lensAberration = prefs.getFloat("lens_aberration", 0f),
        lensSoftness = prefs.getFloat("lens_softness", 0f),
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
            .putFloat("grain_colour", e.grainColour)
            .putFloat("glow_spread", e.glowSpread)
            .putFloat("glow_warmth", e.glowWarmth)
            .putFloat("lens_aberration", e.lensAberration)
            .putFloat("lens_softness", e.lensSoftness)
            .putFloat("bars_ratio", e.barsRatio ?: 0f)
            .putString("graph", e.graph?.name)
            .apply()
    }
}

/** Values the effect shaders share (preview, photo and video export). */
object EffectLook {
    /** The glow's colour for [warmth] (−1 cool … 1 warm), as RGB factors; white at 0. */
    fun glowTint(warmth: Float): FloatArray {
        val w = warmth.coerceIn(-1f, 1f)
        val (r, g, b) = if (w >= 0f) Triple(1f, 1f - 0.18f * w, 1f - 0.42f * w) else Triple(1f + 0.35f * w, 1f + 0.18f * w, 1f)
        return floatArrayOf(r, g, b)
    }
}
