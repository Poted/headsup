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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * The exposure for the place, day and hour chosen: the light's exposure value and the settings
 * that fit it (aperture, shutter, ISO and filter, one left to follow the light). Each result is
 * given for the sky as set, and, once a forecast is loaded, under the forecast's cloud in brackets.
 * Laid out as the editor's panels: when, the result, then what can be changed, each a section.
 */
@Composable
internal fun CalculatorView(st: PlannerState) {
    val frame = Planner.frame(st.place, st.now, st.conditions, st.setup, st.measured)
    val fc = st.forecastHere?.let { Planner.frame(st.place, st.now, st.conditions, st.setup, st.measured, it) }
    val r = frame.exposure
    val fe = fc?.exposure
    // The setting that follows the light as the mode has it (video and timelapse: always ISO).
    val auto = st.setup.auto
    val photo = st.mode == CaptureMode.Photo
    val scope = rememberCoroutineScope()
    // Which setting the dial below changes.
    var dial by rememberSaveable { mutableStateOf("Aperture") }
    if (!photo && dial == "Shutter") dial = "Aperture"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // When: the day and the hour, and what the sky does then.
        ToolSection("When", info = "The day and the hour the exposure is worked out for; the same in Sun and Live. Drag along the bar to move the hour: its colours are the sun's height (night, twilight, golden hour, day).") {
            DayPicker(st)
            DayBar(st)
            Text(
                "${hhmm(st.minute)} · ${frame.phase} · sun ${frame.sun.altitude.roundToInt()}° · moon ${frame.moon.position.altitude.roundToInt()}° (${(frame.moon.lit * 100).roundToInt()} % lit)",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
            )
        }

        // The result: what to set on the camera at that hour.
        ToolSection(
            "At ${hhmm(st.minute)}",
            info = "What to set on the camera. The setting marked Auto follows the light; tap a tile to change it with the dial below. " +
                "The light is estimated from the sun's and moon's height, the place's height, the night sky (Bortle) and cloud, or worked from a reading taken in Live; good to about 1.5 stops. " +
                "With a forecast loaded, its result is in brackets: a forecast of cloud is sometimes wrong, so it is shown beside the sky as set, not instead of it.",
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("EV ${one(frame.ev)}", color = HeadsupColors.Ink, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = GeistMonoFamily))
                Text(
                    "  ${lux(frame.ev)}" + (fc?.let { "  (with forecast EV ${one(it.ev)})" } ?: ""),
                    color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposureTile(
                    "Aperture", withForecast(auto == AutoSetting.Aperture, Exposure.formatAperture(if (auto == AutoSetting.Aperture) r.aperture else st.aperture), fe?.let { Exposure.formatAperture(it.aperture) }),
                    if (auto == AutoSetting.Aperture) "Auto" else "Held", auto == AutoSetting.Aperture, dial == "Aperture", Modifier.weight(1f),
                ) { dial = "Aperture" }
                if (photo) {
                    ExposureTile(
                        "Shutter", withForecast(auto == AutoSetting.Shutter, Exposure.formatShutter(if (auto == AutoSetting.Shutter) r.shutter else st.shutter), fe?.let { Exposure.formatShutter(it.shutter) }),
                        if (auto == AutoSetting.Shutter) "Auto" else "Held", auto == AutoSetting.Shutter, dial == "Shutter", Modifier.weight(1f),
                    ) { dial = "Shutter" }
                } else {
                    ExposureTile(
                        "Shutter", Exposure.formatShutter(st.setup.shutter), if (st.mode == CaptureMode.Video) "${st.fps.toInt()} fps" else "${st.interval.toInt()} s lapse",
                        auto = false, selected = false, modifier = Modifier.weight(1f), onClick = null,
                    )
                }
                ExposureTile(
                    "ISO", withForecast(auto == AutoSetting.Iso, Exposure.snapIso(if (auto == AutoSetting.Iso) r.iso else st.iso).toString(), fe?.let { Exposure.snapIso(it.iso).toString() }),
                    if (auto == AutoSetting.Iso) "Auto" else "Held", auto == AutoSetting.Iso, dial == "ISO", Modifier.weight(1f),
                ) { dial = "ISO" }
                ExposureTile(
                    "Filter", r.nd.label + (if (fe != null && st.ndIndex < 0 && fe.nd != r.nd) " (${fe.nd.label})" else ""),
                    if (st.ndIndex < 0) "Picked" else "Chosen", auto = st.ndIndex < 0, selected = false, modifier = Modifier.weight(1f), onClick = null,
                )
            }
            // Out of the camera's reach: what to change (photo), or what it means for that kind of shot.
            if (photo) warningText(r, st)?.let { Warning(it) } else Capture.notes(st.capture, r).forEach { Warning(it) }
            if (fe != null && fe.warning != r.warning) {
                val fcNotes = if (photo) listOfNotNull(warningText(fe, st)) else Capture.notes(st.capture, fe)
                if (fcNotes.isEmpty()) Text("With forecast: within the camera's reach.", color = HeadsupColors.Good, style = MaterialTheme.typography.labelMedium)
                else fcNotes.forEach { Warning("With forecast: $it") }
            }
        }

        // The dial: the setting tapped above, on the camera's own steps (a third of a stop apart).
        val dialAuto = when (dial) { "Aperture" -> auto == AutoSetting.Aperture; "Shutter" -> auto == AutoSetting.Shutter; else -> auto == AutoSetting.Iso }
        ToolSection(
            dial,
            info = if (photo) "One of the three follows the light (Auto); the other two are held where you set them, on the camera's steps a third of a stop apart. Double-tap the dial to go back to the usual value."
            else "In ${st.mode.label.lowercase()} the shutter is set by the mode and ISO follows the light; the aperture is yours.",
        ) {
            if (photo) {
                val autos = listOf(AutoSetting.Aperture, AutoSetting.Shutter, AutoSetting.Iso)
                LabeledChoice("Follows the light", listOf("Aperture", "Shutter", "ISO"), { autos[it] == auto }, { st.auto = autos[it] })
            }
            if (dialAuto) {
                Text("$dial follows the light: it is worked out for the hour. Choose another to follow the light to set it here.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            } else {
                val (scale, value, set, label) = when (dial) {
                    "Aperture" -> DialSpec(CameraScale.apertures, st.aperture, { v: Double -> st.aperture = v }, { v: Double -> Exposure.formatAperture(v) })
                    "Shutter" -> DialSpec(CameraScale.shutters, st.shutter, { v: Double -> st.shutter = v }, { v: Double -> Exposure.formatShutter(v) })
                    else -> DialSpec(CameraScale.isos.filter { it in st.isoMin..st.isoMax }.ifEmpty { CameraScale.isos }, st.iso, { v: Double -> st.iso = v }, { v: Double -> Exposure.snapIso(v).toString() })
                }
                val at = CameraScale.nearest(scale, value)
                ValueHeader("", label(scale[at]))
                RulerSlider(
                    at.toFloat(), 0f..(scale.size - 1).toFloat(), 1f, { v -> set(scale[v.roundToInt().coerceIn(0, scale.size - 1)]) },
                    onReset = { set(scale[CameraScale.nearest(scale, if (dial == "Aperture") 2.8 else if (dial == "Shutter") 1.0 / 125 else 100.0)]) },
                )
            }
        }

        // What is being shot: a photo (all three settings yours), a video (shutter from the frame rate), a timelapse (shutter from the interval).
        ToolSection(
            "Shooting",
            info = when (st.mode) {
                CaptureMode.Video -> "A video's shutter is twice the frame rate (180°), ${Exposure.formatShutter(Capture.videoShutter(st.fps))} at ${st.fps.toInt()} fps, for natural motion; in bright light the filter keeps it there."
                CaptureMode.Timelapse -> "A timelapse's shutter is half the interval, ${Exposure.formatShutter(Capture.timelapseShutter(st.interval))} every ${st.interval.toInt()} s, for smooth movement (clouds, water, people)."
                else -> "A photo: aperture, shutter and ISO are all yours, one following the light."
            },
        ) {
            ChoiceGrid(CaptureMode.entries.map { it.label }, CaptureMode.entries.indexOf(st.mode), { st.mode = CaptureMode.entries[it] })
            if (st.mode == CaptureMode.Video) {
                val rates = listOf(24.0, 25.0, 30.0, 50.0, 60.0, 100.0, 120.0)
                LabeledChoice("Frame rate", rates.map { "${it.toInt()}" }, { rates[it] == st.fps }, { st.fps = rates[it] }, minCell = 48.dp)
            }
            if (st.mode == CaptureMode.Timelapse) {
                val intervals = listOf(1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0)
                LabeledChoice("Interval", intervals.map { "${it.toInt()} s" }, { intervals[it] == st.interval }, { st.interval = intervals[it] }, minCell = 48.dp)
            }
        }

        // The filter: picked for the light, or the one in the bag.
        ToolSection(
            "Filter",
            info = "Pick for me: the weakest filter that keeps ISO at the camera's lowest, so the cleanest picture (only while ISO follows the light). Choose: the filter you will put on; the others follow from it.",
        ) {
            ChoiceGrid(listOf("Pick for me", "Choose"), if (st.ndIndex < 0) 0 else 1, { i -> st.ndIndex = if (i == 0) -1 else r.nd.ordinal })
            if (st.ndIndex >= 0) {
                val filters = NdFilter.entries
                ValueHeader("", filters[st.ndIndex.coerceIn(0, filters.size - 1)].let { "${it.label} · ${one(it.stops)} stops" })
                RulerSlider(st.ndIndex.toFloat(), 0f..(filters.size - 1).toFloat(), 1f, { v -> st.ndIndex = v.roundToInt().coerceIn(0, filters.size - 1) }, onReset = { st.ndIndex = 0 })
            }
            // With the shutter and aperture held: which filters keep the ISO inside its range.
            if (auto == AutoSetting.Iso) {
                Text(
                    "For ${Exposure.formatAperture(st.aperture)} and ${Exposure.formatShutter(st.setup.shutter)}: " + ndNeeded(frame, st) + (fc?.let { " (with forecast ${ndNeeded(it, st)})" } ?: ""),
                    color = HeadsupColors.Ink, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // The camera's ISO range.
        ToolSection("ISO range", info = "The lowest and highest ISO you will use on this camera: the settings stay inside, and a warning says when the light needs more. Double-tap a dial for the usual.") {
            val isos = CameraScale.isos
            val lo = CameraScale.nearest(isos, st.isoMin)
            val hi = CameraScale.nearest(isos, st.isoMax)
            ValueHeader("Lowest", isos[lo].roundToInt().toString())
            RulerSlider(lo.toFloat(), 0f..(isos.size - 1).toFloat(), 1f, { v -> st.isoMin = isos[v.roundToInt().coerceIn(0, hi)] }, onReset = { st.isoMin = 100.0 })
            ValueHeader("Highest", isos[hi].roundToInt().toString())
            RulerSlider(hi.toFloat(), 0f..(isos.size - 1).toFloat(), 1f, { v -> st.isoMax = isos[v.roundToInt().coerceIn(lo, isos.size - 1)] }, onReset = { st.isoMax = 25_600.0 })
        }

        // The sky: night brightness, cloud as expected, and the forecast beside it.
        ToolSection(
            "Sky",
            info = "Night sky: how dark the place is at night, Bortle 1 (no lights for many km) to 9 (city centre). Cloud: as you expect it; the forecast, when loaded, is shown beside it, not instead. ${app.headsup.core.planner.MetNorway.CREDIT}.",
        ) {
            ValueHeader("Night sky", "Bortle ${st.bortle}")
            RulerSlider(st.bortle.toFloat(), 1f..9f, 1f, { v -> st.bortle = v.roundToInt().coerceIn(1, 9) }, onReset = { st.bortle = 3 })
            ValueHeader("Cloud", "${(st.cloud * 100).roundToInt()} %")
            RulerSlider(st.cloud, 0f..1f, 0.05f, { v -> st.cloud = v.coerceIn(0f, 1f) }, onReset = { st.cloud = 0f })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.forecastHere?.let { "Forecast at ${hhmm(st.minute)}: ${(it.at(st.now) * 100).roundToInt()} % cloud" } ?: "No forecast for this place and day.",
                    color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                Chip(if (st.forecastHere == null) "Get forecast" else "Refresh", { scope.launch { st.fetchForecast() } })
            }
            if (st.forecastStatus.isNotEmpty()) Text(st.forecastStatus, color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }

        // A reading of the light taken in the live view, when there is one.
        st.measuredEv?.let { ev ->
            ToolSection("Light reading", info = "A reading of the light taken with the camera in Live replaces the estimate's level; the hours then follow from it. Used only near where it was taken, for 12 hours.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (st.measured != null) "EV ${one(ev)} (${lux(ev)}), from the camera." else "From another place: not used.",
                        color = if (st.measured != null) HeadsupColors.Good else HeadsupColors.Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Chip("Clear", { st.clearReading() })
                }
            }
        }
    }
}

/** A dial's steps, the value on it, how to set it and how to print it. */
private data class DialSpec(val scale: List<Double>, val value: Double, val set: (Double) -> Unit, val label: (Double) -> String)

/** The value as set; for the one that follows the light, the forecast's value in brackets when it differs. */
private fun withForecast(isAuto: Boolean, value: String, forecast: String?): String =
    if (isAuto && forecast != null && forecast != value) "$value ($forecast)" else value

/** How many stops of filter keep the ISO in range at this hour, and the listed filters among them. */
private fun ndNeeded(f: PlannerFrame, st: PlannerState): String {
    val choices = Exposure.filterChoices(f.ev, st.aperture, st.setup.shutter, st.limits)
    if (choices.isEmpty()) {
        val window = Exposure.ndWindow(f.ev, st.aperture, st.setup.shutter, st.limits)
        return if (window.endInclusive < 0) "more light than there is: no filter, and still too dark" else "${one(window.start.coerceAtLeast(0.0))} stops of filter, which none of the listed gives"
    }
    // The weakest filter that is enough gives the lowest ISO, so the cleanest picture.
    val (best, bestIso) = choices.first()
    val others = choices.drop(1).take(2).joinToString(", ") { (nd, iso) -> "${nd.label} → ISO ${Exposure.snapIso(iso)}" }
    return "${best.label} (ISO ${Exposure.snapIso(bestIso)}, cleanest)" + (if (others.isNotEmpty()) "; also $others" else "")
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
