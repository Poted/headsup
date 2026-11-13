package app.headsup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.ExposureWarning
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.Planner
import app.headsup.core.planner.Timelapse
import app.headsup.core.planner.TimelapseLimits
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The calculator: for the place, day and hour chosen in the sun view, the light's exposure value
 * and the settings that fit it: aperture, shutter, ISO and filter, with one left to follow the
 * light; and, for a timelapse, the one filter that serves a whole stretch of the day.
 */
@Composable
internal fun CalculatorView(st: PlannerState) {
    val frame = Planner.frame(st.place, st.now, st.conditions, st.setup)
    val r = frame.exposure
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Where and when (set in the sun view), and the hour to try.
        Text(
            "${one(st.lat)}, ${one(st.lon)} · ${st.elevation.roundToInt()} m · ${st.day} · ${frame.phase}",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
        )
        DateRow(st)
        Text("${hhmm(st.minute)} · sun ${frame.sun.altitude.roundToInt()}° · moon ${frame.moon.position.altitude.roundToInt()}° (${(frame.moon.lit * 100).roundToInt()}% lit)", color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium)
        Slider(value = st.minute, onValueChange = { st.minute = it }, valueRange = 0f..1439f)

        // The light of the place at night and under cloud.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Night sky (Bortle ${st.bortle})", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Chip("−", { st.bortle = (st.bortle - 1).coerceAtLeast(1) })
            Chip("+", { st.bortle = (st.bortle + 1).coerceAtMost(9) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud ${(st.cloud * 100).roundToInt()}%", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
            Slider(value = st.cloud, onValueChange = { st.cloud = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
        }

        // The exposure at this hour.
        Text("Scene EV ${one(frame.ev)}  (estimated, ±1.5)", color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingTile("Aperture", Exposure.formatAperture(if (st.auto == AutoSetting.Aperture) r.aperture else st.aperture), st.auto == AutoSetting.Aperture, Modifier.weight(1f),
                onToggle = { st.auto = AutoSetting.Aperture }, onLess = { st.aperture = stepStops(st.aperture, -1.0 / 3, true) }, onMore = { st.aperture = stepStops(st.aperture, 1.0 / 3, true) })
            SettingTile("Shutter", Exposure.formatShutter(if (st.auto == AutoSetting.Shutter) r.shutter else st.shutter), st.auto == AutoSetting.Shutter, Modifier.weight(1f),
                onToggle = { st.auto = AutoSetting.Shutter }, onLess = { st.shutter = stepStops(st.shutter, -1.0 / 3, false) }, onMore = { st.shutter = stepStops(st.shutter, 1.0 / 3, false) })
            SettingTile("ISO", Exposure.snapIso(if (st.auto == AutoSetting.Iso) r.iso else st.iso).toString(), st.auto == AutoSetting.Iso, Modifier.weight(1f),
                onToggle = { st.auto = AutoSetting.Iso }, onLess = { st.iso = (st.iso / 2.0.pow(1.0 / 3)).coerceAtLeast(st.isoMin) }, onMore = { st.iso = (st.iso * 2.0.pow(1.0 / 3)).coerceAtMost(st.isoMax) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filter: ${r.nd.label}${if (st.ndIndex < 0) " (picked)" else ""}", color = HeadsupColors.Ink, modifier = Modifier.weight(1f))
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
        if (st.auto == AutoSetting.Iso) {
            val window = Exposure.ndWindow(frame.ev, st.aperture, st.shutter, st.limits)
            val fits = Exposure.filtersIn(window)
            Text(
                "At ${Exposure.formatAperture(st.aperture)} and ${Exposure.formatShutter(st.shutter)}, ISO ${st.isoMin.roundToInt()}–${st.isoMax.roundToInt()} needs " +
                    (if (window.endInclusive < 0) "no filter (too dim even bare)"
                    else "${one(window.start.coerceAtLeast(0.0))}–${one(window.endInclusive)} stops of filter" +
                        (if (fits.isEmpty()) ": none of the listed filters" else ": " + fits.joinToString(" or ") { it.label })),
                color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Out of the camera's reach: red, and what to change.
        when (r.warning) {
            ExposureWarning.IsoTooHigh -> Warning("ISO ${Exposure.snapIso(r.wantedIso)} is needed, above the camera's ${st.isoMax.roundToInt()}. " +
                (r.suggestedNd?.let { "Try ${it.label}, a longer shutter or a wider opening." } ?: "Use a longer shutter or a wider opening."))
            ExposureWarning.IsoTooLow -> Warning("Too bright: even the lowest ISO overexposes. " +
                (r.suggestedNd?.let { if (r.suggestionEnough) "Use ${it.label}." else "Even ${it.label} is not enough: use a faster shutter or a smaller opening too." } ?: "Use a faster shutter or a smaller opening."))
            null -> {}
        }

        TimelapseSection(st)
        Text(
            "The light is estimated from the sun's and moon's height, the place's height, the night sky (Bortle) and cloud. It is a planning guide: measure on the spot when you can.",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** Timelapse: the filter cannot be changed during it, so one filter must serve the whole window. */
@Composable
private fun TimelapseSection(st: PlannerState) {
    Text("Timelapse", color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
    Text(
        "From ${hhmm(st.tlFrom)} to ${hhmm(st.tlTo)} on ${st.day}. Aperture ${Exposure.formatAperture(st.aperture)}, shutter ${Exposure.formatShutter(st.shutterMin)}–${Exposure.formatShutter(st.shutterMax)}, ISO ${st.isoMin.roundToInt()}–${st.isoMax.roundToInt()} (aperture and ISO range are set above).",
        color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
    )
    Slider(value = st.tlFrom, onValueChange = { st.tlFrom = it.coerceAtMost(st.tlTo - 10f) }, valueRange = 0f..1429f)
    Slider(value = st.tlTo, onValueChange = { st.tlTo = it.coerceAtLeast(st.tlFrom + 10f) }, valueRange = 10f..1439f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shortest shutter ${Exposure.formatShutter(st.shutterMin)}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Chip("−", { st.shutterMin = stepStops(st.shutterMin, -1.0, false).coerceAtMost(st.shutterMax) })
        Chip("+", { st.shutterMin = stepStops(st.shutterMin, 1.0, false).coerceAtMost(st.shutterMax) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Longest shutter ${Exposure.formatShutter(st.shutterMax)}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Chip("−", { st.shutterMax = stepStops(st.shutterMax, -1.0, false).coerceAtLeast(st.shutterMin) })
        Chip("+", { st.shutterMax = stepStops(st.shutterMax, 1.0, false) })
    }
    val limits = TimelapseLimits(st.aperture, st.shutterMin, st.shutterMax, st.isoMin, st.isoMax)
    val span = (st.tlTo - st.tlFrom).coerceAtLeast(10f)
    val stepMinutes = (span / 12f).roundToInt().coerceAtLeast(5)
    val place = st.place
    val tl = remember(place, st.day, st.offsetHours, st.tlFrom, st.tlTo, st.bortle, st.cloud, limits) {
        Timelapse.plan(Timelapse.sample(place, st.conditions, st.dayStart + st.tlFrom.toLong() * 60_000L, st.dayStart + st.tlTo.toLong() * 60_000L, stepMinutes), limits)
    }
    Text("Light in this window: EV ${one(tl.evMin)} to ${one(tl.evMax)} (${one(tl.evMax - tl.evMin)} stops); the camera covers ${one(limits.rangeStops)}.",
        color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium)
    if (tl.feasible && tl.filters.isNotEmpty()) {
        Text("Take ${tl.filters.joinToString(" or ") { it.label }} (works from ${one(tl.ndLow.coerceAtLeast(0.0))} to ${one(tl.ndHigh)} stops of filter). The rows use ${tl.chosen.label}.",
            color = HeadsupColors.Good, style = MaterialTheme.typography.bodyMedium)
    } else if (tl.feasible) {
        Warning("The range fits (${one(tl.ndLow.coerceAtLeast(0.0))}–${one(tl.ndHigh)} stops), but none of the listed filters lies in it. The rows use ${tl.chosen.label}, the nearest.")
    } else {
        Warning("No single filter serves this window: the light changes ${one(tl.shortfallStops)} stops more than the shutter and ISO cover. Allow a longer longest shutter, a higher top ISO, or a shorter window. The rows use ${tl.chosen.label}; red rows are out of reach.")
    }
    for (step in tl.steps) {
        val c = if (step.inReach) HeadsupColors.Ink else HeadsupColors.Bad
        Text(
            "${clock(step.millis, st.zone)}  sun ${step.sunAltitude.roundToInt()}°  EV ${one(step.ev)}  ${Exposure.formatShutter(step.shutter)}  ISO ${Exposure.snapIso(step.iso)}" + (if (step.inReach) "" else "  out of reach"),
            color = c, style = MaterialTheme.typography.bodySmall,
        )
    }
}
