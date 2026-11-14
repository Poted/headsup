package app.headsup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.CaptureMode
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.Planner

/**
 * The exposure bar of the plan's hour, on one line, as the first sketch had it:
 * EV | aperture | shutter | filter → the setting that follows the light. The one that follows is
 * marked, red when the camera cannot reach it; the forecast's value is in brackets once loaded.
 * Tap it to open the calculator, where the settings are changed.
 */
@Composable
internal fun ExposureHud(st: PlannerState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val setup = st.setup
    val frame = Planner.frame(st.place, st.now, st.conditions, setup, st.measured)
    val fc = st.forecastHere?.let { Planner.frame(st.place, st.now, st.conditions, setup, st.measured, it) }
    val e = frame.exposure
    val fe = fc?.exposure
    val bad = e.warning != null
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell("EV", one(frame.ev), fc?.let { one(it.ev) }, follows = false, bad = false)
            Cell("Aperture", Exposure.formatAperture(e.aperture), fe?.let { Exposure.formatAperture(it.aperture) }, setup.auto == AutoSetting.Aperture, bad && setup.auto == AutoSetting.Aperture)
            Cell(
                if (st.mode == CaptureMode.Photo) "Shutter" else if (st.mode == CaptureMode.Video) "${st.fps.toInt()} fps" else "${st.interval.toInt()} s lapse",
                Exposure.formatShutter(e.shutter), fe?.let { Exposure.formatShutter(it.shutter) }, setup.auto == AutoSetting.Shutter, bad && setup.auto == AutoSetting.Shutter,
            )
            Cell("Filter", e.nd.label, fe?.let { it.nd.label }, follows = st.ndIndex < 0, bad = false)
            Cell("ISO", Exposure.snapIso(e.iso).toString(), fe?.let { Exposure.snapIso(it.iso).toString() }, setup.auto == AutoSetting.Iso, bad && setup.auto == AutoSetting.Iso)
        }
        val note = when {
            e.warning != null -> "Out of the camera's reach: tap for what to change"
            st.measured != null -> "From the camera's reading"
            else -> "Estimated light; tap to change the settings"
        }
        Text(note, color = if (bad) Color(0xFFFF8A80) else Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

/** One value of the bar: its name, its value, the forecast's in brackets when it differs. */
@Composable
private fun RowScope.Cell(label: String, value: String, forecast: String?, follows: Boolean, bad: Boolean) {
    val colour = when {
        bad -> Color(0xFFFF8A80)
        follows -> HeadsupColors.Accent
        else -> Color.White
    }
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, color = colour, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        if (forecast != null && forecast != value) Text("($forecast)", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
