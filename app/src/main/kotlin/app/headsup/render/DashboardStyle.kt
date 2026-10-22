package app.headsup.render

import android.content.Context
import android.graphics.Color
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric

enum class Widget(val label: String, val metric: Metric?) {
    TIME("Time", null),
    HEART_RATE("Heart rate", Metric.HEART_RATE),
    SPEED("Speed", Metric.SPEED),
    PACE("Pace", Metric.SPEED),
    ALTITUDE("Altitude", Metric.ALTITUDE),
    DISTANCE("Distance", Metric.DISTANCE),
    ASCENT("Ascent", Metric.ASCENT),
    GRADE("Grade", Metric.GRADE),
    CADENCE("Cadence", Metric.CADENCE),
    POWER("Power", Metric.POWER),
    TEMPERATURE("Temperature", Metric.TEMPERATURE),
    MAP("Map", Metric.LATITUDE);

    fun isAvailableIn(track: ActivityTrack) = metric == null || track.has(metric)
}

enum class HAnchor { LEFT, CENTER, RIGHT }
enum class VAnchor { TOP, CENTER, BOTTOM }

/**
 * Where one widget sits, measured from the nearest frame edge (or the centre line) in dashboard
 * units of 1/1000 of the shorter frame side. A widget dragged to the bottom-left corner therefore
 * stays in that corner on 16:9, 9:16 and square footage.
 */
data class Placement(val h: HAnchor, val v: VAnchor, val dx: Float, val dy: Float)

enum class LayoutPreset(val label: String) {
    TOP_LEFT("Top left"),
    TOP_CENTER("Top centre"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_CENTER("Bottom centre"),
    BOTTOM_RIGHT("Bottom right"),
    SIDES("Both sides"),
}

enum class DashboardFont(val label: String, val asset: String?, val weight: Int? = null) {
    CLASSIC("Classic", null),
    BARLOW("Barlow", "fonts/barlow_condensed_bold_italic.ttf"),
    OSWALD("Oswald", "fonts/oswald.ttf", weight = 600),
    RAJDHANI("Rajdhani", "fonts/rajdhani_bold.ttf"),
    ORBITRON("Orbitron", "fonts/orbitron.ttf", weight = 700),
    MONO("Mono", null),
}

enum class TextEffect(val label: String) { SHADOW("Shadow"), OUTLINE("Outline"), NONE("None") }

/** One look shared by every clip in a batch, so footage from different cameras matches. */
data class DashboardStyle(
    val widgets: Set<Widget> = setOf(Widget.HEART_RATE, Widget.PACE, Widget.ALTITUDE, Widget.DISTANCE, Widget.MAP),
    val preset: LayoutPreset = LayoutPreset.BOTTOM_LEFT,
    /** Widgets the user dragged; all others follow [preset]. */
    val placements: Map<Widget, Placement> = emptyMap(),
    /** Relative to the default size, which is derived from the shorter side of the frame. */
    val scale: Float = 1f,
    val hrZoneColors: Boolean = true,
    val font: DashboardFont = DashboardFont.CLASSIC,
    val valueColor: Int = Color.WHITE,
    val labelColor: Int = Color.WHITE,
    val unitColor: Int = Layouts.ACCENT,
    val effect: TextEffect = TextEffect.SHADOW,
) {
    fun resolvedPlacements(shown: List<Widget>): Map<Widget, Placement> {
        val auto = Layouts.arrange(preset, shown.filter { it !in placements })
        return shown.associateWith { placements[it] ?: auto.getValue(it) }
    }
}

/** Sizes in dashboard units and the geometry shared by the renderer, presets and dragging. */
object Layouts {
    const val LABEL_SIZE = 24f
    const val LABEL_GAP = 4f
    const val VALUE_SIZE = 64f
    const val UNIT_SIZE = 28f
    const val BLOCK_GAP = 22f
    const val MAP_SIZE = 260f
    const val MARGIN = 40f
    const val COLUMN_WIDTH = 320f
    private const val SNAP = 15f
    private const val MAX_COLUMN = 1000f - 2 * MARGIN

    val ACCENT = Color.rgb(255, 214, 10)

    fun contentHeight(widget: Widget) = LABEL_SIZE + LABEL_GAP + if (widget == Widget.MAP) MAP_SIZE else VALUE_SIZE

    fun arrange(preset: LayoutPreset, widgets: List<Widget>): Map<Widget, Placement> = when (preset) {
        LayoutPreset.TOP_LEFT -> stack(widgets, HAnchor.LEFT, VAnchor.TOP)
        LayoutPreset.TOP_CENTER -> stack(widgets, HAnchor.CENTER, VAnchor.TOP)
        LayoutPreset.TOP_RIGHT -> stack(widgets, HAnchor.RIGHT, VAnchor.TOP)
        LayoutPreset.BOTTOM_LEFT -> stack(widgets, HAnchor.LEFT, VAnchor.BOTTOM)
        LayoutPreset.BOTTOM_CENTER -> stack(widgets, HAnchor.CENTER, VAnchor.BOTTOM)
        LayoutPreset.BOTTOM_RIGHT -> stack(widgets, HAnchor.RIGHT, VAnchor.BOTTOM)
        LayoutPreset.SIDES -> {
            val half = (widgets.size + 1) / 2
            stack(widgets.take(half), HAnchor.LEFT, VAnchor.TOP) + stack(widgets.drop(half), HAnchor.RIGHT, VAnchor.TOP)
        }
    }

    /** Stacks widgets in reading order from the anchor edge, wrapping into further columns. */
    private fun stack(widgets: List<Widget>, h: HAnchor, v: VAnchor): Map<Widget, Placement> {
        val columns = mutableListOf(mutableListOf<Widget>())
        var used = 0f
        for (w in widgets) {
            val step = contentHeight(w) + BLOCK_GAP
            if (used > 0f && used + contentHeight(w) > MAX_COLUMN) {
                columns += mutableListOf<Widget>()
                used = 0f
            }
            columns.last() += w
            used += step
        }
        val result = mutableMapOf<Widget, Placement>()
        columns.forEachIndexed { index, column ->
            val dx = when (h) {
                HAnchor.CENTER -> (index - (columns.size - 1) / 2f) * COLUMN_WIDTH
                else -> MARGIN + index * COLUMN_WIDTH
            }
            // Vertical offsets from the anchor edge; for the bottom edge the last widget is lowest.
            val ordered = if (v == VAnchor.BOTTOM) column.reversed() else column
            var dy = MARGIN
            for (w in ordered) {
                result[w] = Placement(h, v, dx, dy)
                dy += contentHeight(w) + BLOCK_GAP
            }
        }
        return result
    }

    /** Converts a widget box dragged to [left],[top] (pixels) back into an edge-relative placement. */
    fun placementFor(left: Float, top: Float, width: Float, height: Float, frameW: Float, frameH: Float, unit: Float): Placement {
        val l = left.coerceIn(0f, (frameW - width).coerceAtLeast(0f))
        val t = top.coerceIn(0f, (frameH - height).coerceAtLeast(0f))
        val cx = l + width / 2
        val cy = t + height / 2
        val h = when {
            cx < frameW / 3 -> HAnchor.LEFT
            cx > frameW * 2 / 3 -> HAnchor.RIGHT
            else -> HAnchor.CENTER
        }
        val v = when {
            cy < frameH / 3 -> VAnchor.TOP
            cy > frameH * 2 / 3 -> VAnchor.BOTTOM
            else -> VAnchor.CENTER
        }
        val dx = when (h) {
            HAnchor.LEFT -> snap(l / unit, MARGIN)
            HAnchor.RIGHT -> snap((frameW - l - width) / unit, MARGIN)
            HAnchor.CENTER -> snap((cx - frameW / 2) / unit, 0f)
        }
        val dy = when (v) {
            VAnchor.TOP -> snap(t / unit, MARGIN)
            VAnchor.BOTTOM -> snap((frameH - t - height) / unit, MARGIN)
            VAnchor.CENTER -> snap((cy - frameH / 2) / unit, 0f)
        }
        return Placement(h, v, dx, dy)
    }

    private fun snap(value: Float, target: Float) = if (kotlin.math.abs(value - target) < SNAP) target else value
}

class StylePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_style", Context.MODE_PRIVATE)

    fun load(): DashboardStyle {
        val d = DashboardStyle()
        val widgets = prefs.getString(KEY_WIDGETS, null)
            ?.split(',')
            ?.mapNotNull { name -> enumOrNull<Widget>(name) }
            ?.toSet()
        val placements = prefs.getString(KEY_PLACEMENTS, null).orEmpty()
            .split(';')
            .mapNotNull { entry ->
                val p = entry.split(',')
                if (p.size != 5) return@mapNotNull null
                val w = enumOrNull<Widget>(p[0]) ?: return@mapNotNull null
                val h = enumOrNull<HAnchor>(p[1]) ?: return@mapNotNull null
                val v = enumOrNull<VAnchor>(p[2]) ?: return@mapNotNull null
                val dx = p[3].toFloatOrNull() ?: return@mapNotNull null
                val dy = p[4].toFloatOrNull() ?: return@mapNotNull null
                w to Placement(h, v, dx, dy)
            }
            .toMap()
        return DashboardStyle(
            widgets = widgets ?: d.widgets,
            preset = enumOrNull<LayoutPreset>(prefs.getString(KEY_PRESET, null)) ?: d.preset,
            placements = placements,
            scale = prefs.getFloat(KEY_SCALE, d.scale),
            hrZoneColors = prefs.getBoolean(KEY_ZONES, d.hrZoneColors),
            font = enumOrNull<DashboardFont>(prefs.getString(KEY_FONT, null)) ?: d.font,
            valueColor = prefs.getInt(KEY_VALUE_COLOR, d.valueColor),
            labelColor = prefs.getInt(KEY_LABEL_COLOR, d.labelColor),
            unitColor = prefs.getInt(KEY_UNIT_COLOR, d.unitColor),
            effect = enumOrNull<TextEffect>(prefs.getString(KEY_EFFECT, null)) ?: d.effect,
        )
    }

    fun save(style: DashboardStyle) {
        prefs.edit()
            .putString(KEY_WIDGETS, style.widgets.joinToString(",") { it.name })
            .putString(KEY_PRESET, style.preset.name)
            .putString(
                KEY_PLACEMENTS,
                style.placements.entries.joinToString(";") { (w, p) -> "${w.name},${p.h.name},${p.v.name},${p.dx},${p.dy}" },
            )
            .putFloat(KEY_SCALE, style.scale)
            .putBoolean(KEY_ZONES, style.hrZoneColors)
            .putString(KEY_FONT, style.font.name)
            .putInt(KEY_VALUE_COLOR, style.valueColor)
            .putInt(KEY_LABEL_COLOR, style.labelColor)
            .putInt(KEY_UNIT_COLOR, style.unitColor)
            .putString(KEY_EFFECT, style.effect.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        enumValues<E>().firstOrNull { it.name == name }

    private companion object {
        const val KEY_WIDGETS = "widgets"
        const val KEY_PRESET = "preset"
        const val KEY_PLACEMENTS = "placements"
        const val KEY_SCALE = "scale"
        const val KEY_ZONES = "hr_zone_colors"
        const val KEY_FONT = "font"
        const val KEY_VALUE_COLOR = "value_color"
        const val KEY_LABEL_COLOR = "label_color"
        const val KEY_UNIT_COLOR = "unit_color"
        const val KEY_EFFECT = "effect"
    }
}
