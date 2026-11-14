package app.headsup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.Meter

/**
 * The plan's hour on one line, as a light meter: the scene's EV, the camera's aperture, shutter,
 * filter and ISO (Auto ones in the accent: the camera's own choice), and how far the exposure is from
 * right (EV +1.7 too bright, EV −1.7 too dark). The forecast's values are in brackets once loaded.
 * Tap it to open Exposure, where the settings are changed.
 */
@Composable
internal fun ExposureHud(st: PlannerState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ev = st.lightEv()
    val fcEv = st.forecastHere?.let { st.lightEv(it) }
    val r = st.reading(ev)
    val fr = fcEv?.let { st.reading(it) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HeadsupColors.Surface)
            .border(1.dp, HeadsupColors.Outline, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Open Exposure", onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell("Light", lux(ev).removeSuffix(" lux"), null, follows = false)
            Cell("Aperture", Exposure.formatAperture(r.aperture), null, follows = false)
            Cell("Shutter", Exposure.formatShutter(r.shutter), fr?.let { Exposure.formatShutter(it.shutter) }.takeIf { st.shutterAuto }, follows = st.shutterAuto)
            Cell("Filter", st.ndFilter.label, null, follows = false)
            Cell("ISO", Exposure.snapIso(r.iso).toString(), fr?.let { Exposure.snapIso(it.iso).toString() }.takeIf { st.isoAuto }, follows = st.isoAuto)
            Cell("Result", Meter.formatOffset(r.offset).removePrefix("EV "), fr?.let { Meter.formatOffset(it.offset).removePrefix("EV ") }, follows = false)
        }
        Text(
            (if (st.measured != null) "From the camera's reading" else "Estimated light") + " · ${st.camera.name} · tap to change",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

/** One value of the bar: its name, its value, the forecast's in brackets when it differs. */
@Composable
private fun RowScope.Cell(label: String, value: String, forecast: String?, follows: Boolean) {
    val colour = if (follows) HeadsupColors.AccentInk else HeadsupColors.Ink
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, color = colour, style = MaterialTheme.typography.titleSmall.copy(fontFamily = GeistMonoFamily), maxLines = 1)
        if (forecast != null && forecast != value) Text("($forecast)", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
