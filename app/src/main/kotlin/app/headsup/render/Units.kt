package app.headsup.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Metric or imperial, for the activity's data wherever it is shown (dashboard, data texts,
 * highlights, preview and export alike). An app setting (Settings › Units); kept in snapshot state
 * so the preview redraws when it changes.
 */
object Units {
    var imperial by mutableStateOf(false)

    const val MILE_M = 1609.344
    const val FOOT_M = 0.3048

    /** km/h or mph from metres per second. */
    fun speed(mps: Double) = if (imperial) mps * 3600.0 / MILE_M else mps * 3.6
    val speedUnit get() = if (imperial) "mph" else "km/h"

    /** km or mi from metres. */
    fun distance(m: Double) = if (imperial) m / MILE_M else m / 1000.0
    val distanceUnit get() = if (imperial) "mi" else "km"

    /** m or ft. */
    fun height(m: Double) = if (imperial) m / FOOT_M else m
    val heightUnit get() = if (imperial) "ft" else "m"

    /** The length a pace is given for, in metres. */
    val paceMetres get() = if (imperial) MILE_M else 1000.0
    val paceUnit get() = if (imperial) "/mi" else "/km"

    fun temperature(c: Double) = if (imperial) c * 9.0 / 5.0 + 32.0 else c
    val temperatureUnit get() = if (imperial) "°F" else "°C"
}
