package app.headsup.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.Capture
import app.headsup.core.planner.CaptureMode
import app.headsup.core.planner.CameraScale
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.ExposureResult
import app.headsup.core.planner.ExposureWarning
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.Planner
import app.headsup.core.planner.PlannerFrame
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The calculator: for the place, day and hour chosen, the light's exposure value and the settings
 * that fit it (aperture, shutter, ISO and filter, one left to follow the light). Each result is
 * given for the sky as set, and, once a forecast is loaded, under the forecast's cloud in brackets.
 */
@Composable
internal fun CalculatorView(st: PlannerState) {
    val frame = Planner.frame(st.place, st.now, st.conditions, st.setup, st.measured)
    val fc = st.forecastHere?.let { Planner.frame(st.place, st.now, st.conditions, st.setup, st.measured, it) }
    val r = frame.exposure
    // The setting that follows the light as the mode has it (video and timelapse: always ISO).
    val auto = st.setup.auto
    val photo = st.mode == CaptureMode.Photo
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Where and when, and the hour to try.
        Text(
            "${one(st.lat)}, ${one(st.lon)} · ${st.elevation.roundToInt()} m" + (if (st.aboveGround > 0) " + ${st.aboveGround.roundToInt()} m up" else "") + " · ${st.day} · ${frame.phase}",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
        )
        DateRow(st)
        Text("${hhmm(st.minute)} · sun ${frame.sun.altitude.roundToInt()}° · moon ${frame.moon.position.altitude.roundToInt()}° (${(frame.moon.lit * 100).roundToInt()}% lit)", color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium)
        DayBar(st)

        // What is being shot: a photo (all three settings yours), a video (shutter from the frame rate), a timelapse (shutter from the interval).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in CaptureMode.entries) Chip(m.label, { st.mode = m }, selected = st.mode == m)
        }
        if (st.mode == CaptureMode.Video) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in listOf(24.0, 25.0, 30.0, 50.0, 60.0, 100.0, 120.0)) Chip("${f.toInt()} fps", { st.fps = f }, selected = st.fps == f)
            }
            Text("Shutter ${Exposure.formatShutter(Capture.videoShutter(st.fps))}: twice the frame rate (180°), for natural motion. The filter keeps it there in bright light.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        if (st.mode == CaptureMode.Timelapse) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in listOf(1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0)) Chip("${i.toInt()} s", { st.interval = i }, selected = st.interval == i)
            }
            Text("Interval ${st.interval.toInt()} s; shutter ${Exposure.formatShutter(Capture.timelapseShutter(st.interval))}: half the interval, for smooth movement (clouds, water, people).", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }

        // A reading of the light taken in the live view, when there is one.
        st.measuredEv?.let { ev ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (st.measured != null) "Base: the camera's reading, EV ${one(ev)} (${lux(ev)}). The hours follow from it." else "The camera's reading is from another place: not used.",
                    color = if (st.measured != null) HeadsupColors.Good else HeadsupColors.Warn, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                Chip("Clear", { st.clearReading() })
            }
        }

        // The sky: night brightness, cloud as expected, and the forecast beside it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Night sky (Bortle ${st.bortle})", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Chip("−", { st.bortle = (st.bortle - 1).coerceAtLeast(1) })
            Chip("+", { st.bortle = (st.bortle + 1).coerceAtMost(9) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud ${(st.cloud * 100).roundToInt()}%", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
            Slider(value = st.cloud, onValueChange = { st.cloud = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                st.forecastHere?.let { "Forecast cloud at ${hhmm(st.minute)}: ${(it.at(st.now) * 100).roundToInt()}% · ${app.headsup.core.planner.MetNorway.CREDIT}" } ?: "No forecast for this place and day",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            Chip(if (st.forecastHere == null) "Get forecast" else "Refresh forecast", { scope.launch { st.fetchForecast() } })
        }
        if (st.forecastStatus.isNotEmpty()) Text(st.forecastStatus, color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)

        // The exposure at this hour: as set, (with forecast).
        Text(
            "Scene EV ${one(frame.ev)}" + (fc?.let { " (with forecast ${one(it.ev)})" } ?: "") + " · ${lux(frame.ev)}",
            color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall,
        )
        val fe = fc?.exposure
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingTile("Aperture", withForecast(auto == AutoSetting.Aperture, Exposure.formatAperture(if (auto == AutoSetting.Aperture) r.aperture else st.aperture), fe?.let { Exposure.formatAperture(it.aperture) }),
                auto == AutoSetting.Aperture, Modifier.weight(1f),
                onToggle = { st.auto = AutoSetting.Aperture }, onLess = { st.aperture = CameraScale.step(CameraScale.apertures, st.aperture, -1) }, onMore = { st.aperture = CameraScale.step(CameraScale.apertures, st.aperture, 1) })
            if (photo) {
                SettingTile("Shutter", withForecast(auto == AutoSetting.Shutter, Exposure.formatShutter(if (auto == AutoSetting.Shutter) r.shutter else st.shutter), fe?.let { Exposure.formatShutter(it.shutter) }),
                    auto == AutoSetting.Shutter, Modifier.weight(1f),
                    onToggle = { st.auto = AutoSetting.Shutter }, onLess = { st.shutter = CameraScale.step(CameraScale.shutters, st.shutter, -1) }, onMore = { st.shutter = CameraScale.step(CameraScale.shutters, st.shutter, 1) })
            } else {
                FixedTile("Shutter", Exposure.formatShutter(st.setup.shutter), if (st.mode == CaptureMode.Video) "${st.fps.toInt()} fps" else "${st.interval.toInt()} s interval", Modifier.weight(1f))
            }
            SettingTile("ISO", withForecast(auto == AutoSetting.Iso, Exposure.snapIso(if (auto == AutoSetting.Iso) r.iso else st.iso).toString(), fe?.let { Exposure.snapIso(it.iso).toString() }),
                auto == AutoSetting.Iso, Modifier.weight(1f),
                onToggle = { st.auto = AutoSetting.Iso }, onLess = { st.iso = CameraScale.step(CameraScale.isos, st.iso, -1).coerceAtLeast(st.isoMin) }, onMore = { st.iso = CameraScale.step(CameraScale.isos, st.iso, 1).coerceAtMost(st.isoMax) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Filter: ${r.nd.label}${if (st.ndIndex < 0) " (picked)" else ""}" + (if (fe != null && st.ndIndex < 0 && fe.nd != r.nd) " (with forecast ${fe.nd.label})" else ""),
                color = HeadsupColors.Ink, modifier = Modifier.weight(1f),
            )
            Chip("Pick for me", { st.ndIndex = -1 })
            Chip("◀", { st.ndIndex = ((if (st.ndIndex < 0) r.nd.ordinal else st.ndIndex) - 1).coerceAtLeast(0) })
            Chip("▶", { st.ndIndex = ((if (st.ndIndex < 0) r.nd.ordinal else st.ndIndex) + 1).coerceAtMost(NdFilter.entries.size - 1) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ISO range: ${st.isoMin.roundToInt()}–${st.isoMax.roundToInt()}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Chip("max −", { st.isoMax = (st.isoMax / 2).coerceAtLeast(maxOf(800.0, st.isoMin)) })
            Chip("max +", { st.isoMax = (st.isoMax * 2).coerceAtMost(102_400.0) })
            Chip("min −", { st.isoMin = (st.isoMin / 2).coerceAtLeast(50.0) })
            Chip("min +", { st.isoMin = (st.isoMin * 2).coerceAtMost(st.isoMax) })
        }
        // With the shutter and aperture held: which filters keep the ISO inside its range.
        if (auto == AutoSetting.Iso) {
            Text(
                "At ${Exposure.formatAperture(st.aperture)} and ${Exposure.formatShutter(st.setup.shutter)}, ISO ${st.isoMin.roundToInt()}–${st.isoMax.roundToInt()} needs " +
                    ndNeeded(frame, st) + (fc?.let { " (with forecast ${ndNeeded(it, st)})" } ?: ""),
                color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Out of the camera's reach: red, and what to change.
        // Out of reach: in a photo what to change; in a video or timelapse what it means for that kind of shot.
        if (photo) warningText(r, st)?.let { Warning(it) } else Capture.notes(st.capture, r).forEach { Warning(it) }
        if (fe != null && fe.warning != r.warning) {
            val fcNotes = if (photo) listOfNotNull(warningText(fe, st)) else Capture.notes(st.capture, fe)
            if (fcNotes.isEmpty()) Text("With forecast: within the camera's reach.", color = HeadsupColors.Good, style = MaterialTheme.typography.labelMedium)
            else fcNotes.forEach { Warning("With forecast: $it") }
        }
        Text(
            "The light is estimated from the sun's and moon's height, the place's height, the night sky (Bortle) and cloud, or worked from a reading taken in Live. A forecast of cloud is sometimes wrong, so it is shown beside the sky as set, not instead of it.",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** The value as set; for the one that follows the light, the forecast's value in brackets when it differs. */
private fun withForecast(isAuto: Boolean, value: String, forecast: String?): String =
    if (isAuto && forecast != null && forecast != value) "$value ($forecast)" else value

/** How many stops of filter keep the ISO in range at this hour, and the listed filters among them. */
private fun ndNeeded(f: PlannerFrame, st: PlannerState): String {
    val window = Exposure.ndWindow(f.ev, st.aperture, st.setup.shutter, st.limits)
    val fits = Exposure.filtersIn(window)
    return if (window.endInclusive < 0) "no filter (too dim even bare)"
    else "${one(window.start.coerceAtLeast(0.0))}–${one(window.endInclusive)} stops of filter" +
        (if (fits.isEmpty()) ", none of the listed" else ": " + fits.joinToString(" or ") { it.label })
}

private fun warningText(r: ExposureResult, st: PlannerState): String? = when (r.warning) {
    ExposureWarning.IsoTooHigh -> "ISO ${Exposure.snapIso(r.wantedIso)} is needed, above the camera's ${st.isoMax.roundToInt()}. " +
        (r.suggestedNd?.let { "Try ${it.label}, a longer shutter or a wider opening." } ?: "Use a longer shutter or a wider opening.")
    ExposureWarning.IsoTooLow -> "Too bright: even the lowest ISO overexposes. " +
        (r.suggestedNd?.let { if (r.suggestionEnough) "Use ${it.label}." else "Even ${it.label} is not enough: use a faster shutter or a smaller opening too." } ?: "Use a faster shutter or a smaller opening.")
    null -> null
}

/** Light on the scene in lux, rounded as a meter shows it. */
internal fun lux(ev: Double): String {
    val l = Exposure.luxFromEv(ev)
    return when {
        l >= 10_000 -> "${(l / 1000).roundToInt()}k lux"
        l >= 1000 -> "${one(l / 1000)}k lux"
        l >= 10 -> "${l.roundToInt()} lux"
        l >= 0.1 -> "${one(l)} lux"
        else -> String.format(java.util.Locale.US, "%.3f lux", l)
    }
}
