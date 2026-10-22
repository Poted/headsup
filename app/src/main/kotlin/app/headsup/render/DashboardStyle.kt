package app.headsup.render

import android.content.Context
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

enum class Corner(val label: String, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT("Top left", right = false, bottom = false),
    TOP_RIGHT("Top right", right = true, bottom = false),
    BOTTOM_LEFT("Bottom left", right = false, bottom = true),
    BOTTOM_RIGHT("Bottom right", right = true, bottom = true),
}

/** One look shared by every clip in a batch, so footage from different cameras matches. */
data class DashboardStyle(
    val widgets: Set<Widget> = setOf(Widget.HEART_RATE, Widget.PACE, Widget.ALTITUDE, Widget.DISTANCE, Widget.MAP),
    val corner: Corner = Corner.BOTTOM_LEFT,
    /** Relative to the default size, which is derived from the shorter side of the frame. */
    val scale: Float = 1f,
    val hrZoneColors: Boolean = true,
)

class StylePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_style", Context.MODE_PRIVATE)

    fun load(): DashboardStyle {
        val defaults = DashboardStyle()
        val widgets = prefs.getString(KEY_WIDGETS, null)
            ?.split(',')
            ?.mapNotNull { name -> Widget.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return DashboardStyle(
            widgets = widgets ?: defaults.widgets,
            corner = Corner.entries.firstOrNull { it.name == prefs.getString(KEY_CORNER, null) } ?: defaults.corner,
            scale = prefs.getFloat(KEY_SCALE, defaults.scale),
            hrZoneColors = prefs.getBoolean(KEY_ZONES, defaults.hrZoneColors),
        )
    }

    fun save(style: DashboardStyle) {
        prefs.edit()
            .putString(KEY_WIDGETS, style.widgets.joinToString(",") { it.name })
            .putString(KEY_CORNER, style.corner.name)
            .putFloat(KEY_SCALE, style.scale)
            .putBoolean(KEY_ZONES, style.hrZoneColors)
            .apply()
    }

    private companion object {
        const val KEY_WIDGETS = "widgets"
        const val KEY_CORNER = "corner"
        const val KEY_SCALE = "scale"
        const val KEY_ZONES = "hr_zone_colors"
    }
}
