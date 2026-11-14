package app.headsup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.CameraProfile
import app.headsup.core.planner.CameraScale
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.Meter
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.SceneLight
import app.headsup.core.planner.Sky
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A light meter for the plan's place and hour: the light of the scene (estimated, or read with the
 * camera in Live), the camera's settings as set on it (shutter and ISO by hand or Auto), and how far
 * the exposure is from right, in stops: EV +1.7 too bright, EV 0.0 right, EV −1.7 too dark.
 * A camera profile only keeps the camera's limits and the filters owned for it.
 */
@Composable
internal fun CalculatorView(st: PlannerState) {
    val ev = st.lightEv()
    val fcEv = st.forecastHere?.let { st.lightEv(it) }
    val r = st.reading(ev)
    val fr = fcEv?.let { st.reading(it) }
    val sun = Sky.sun(st.place, st.now)
    val moon = Sky.moon(st.place, st.now)
    val camera = st.camera
    val scope = rememberCoroutineScope()
    // Which setting the dial below changes.
    var dial by rememberSaveable { mutableStateOf("Shutter") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // When: the day and the hour, and what the sky does then.
        ToolSection("When", info = "The day and the hour the light is worked out for; the same in Sun and Live. Drag along the bar to move the hour: its colours are the sun's height.") {
            DayPicker(st)
            DayBar(st)
            Text(
                "${hhmm(st.minute)} · ${SceneLight.phase(sun.altitude)} · sun ${sun.altitude.roundToInt()}° · moon ${moon.position.altitude.roundToInt()}° (${(moon.lit * 100).roundToInt()} % lit)",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
            )
        }

        // The settings and the result: how far from right the exposure is.
        ToolSection(
            "Exposure",
            info = "The camera's settings as set on it; Auto is the camera's own choice within its range. " +
                "The result is how far the picture is from a right exposure, in stops: + too bright, − too dark. " +
                "The light is estimated from the sun's and moon's height, the place's height, the night sky and cloud (good to about 1.5 stops), " +
                "or taken from the camera's reading in Live (Use as base). With a forecast loaded, the result under its cloud is in brackets.",
        ) {
            // Where the light comes from (one number is shown: the result below).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Light ${lux(ev)} · " + when {
                        st.measured != null -> "from the camera's reading"
                        st.measuredEv != null -> "estimated (the reading is from another place)"
                        else -> "estimated"
                    },
                    color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                if (st.measuredEv != null) Chip("Clear reading", { st.clearReading() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposureTile("Aperture", Exposure.formatAperture(r.aperture), if (camera.fixedAperture != null) "Fixed" else "Set", false, dial == "Aperture", Modifier.weight(1f)) { dial = "Aperture" }
                ExposureTile("Shutter", Exposure.formatShutter(r.shutter), if (st.shutterAuto) "Auto" else "Set", st.shutterAuto, dial == "Shutter", Modifier.weight(1f)) { dial = "Shutter" }
                ExposureTile("ISO", Exposure.snapIso(r.iso).toString(), if (st.isoAuto) "Auto" else "Set", st.isoAuto, dial == "ISO", Modifier.weight(1f)) { dial = "ISO" }
                ExposureTile("Filter", st.ndFilter.label, "", false, dial == "Filter", Modifier.weight(1f)) { dial = "Filter" }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(Meter.formatOffset(r.offset), color = HeadsupColors.Ink, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = GeistMonoFamily))
                fr?.let {
                    Text("  (forecast ${Meter.formatOffset(it.offset)})", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }

        // The dial: the setting tapped above, on the camera's steps within its range.
        ToolSection(dial, info = "Set as on the camera, a third of a stop apart, within this camera's range. Shutter and ISO can be left to the camera (Auto).") {
            when (dial) {
                "Aperture" -> {
                    val fixed = camera.fixedAperture
                    if (fixed != null) {
                        Text("Fixed at ${Exposure.formatAperture(fixed)} on ${camera.name}.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val scale = CameraScale.apertures.filter { it >= camera.apertureMin * 0.97 && it <= camera.apertureMax * 1.03 }.ifEmpty { CameraScale.apertures }
                        Dial(scale, st.aperture, { st.aperture = it }, { Exposure.formatAperture(it) }, reset = camera.apertureMin)
                    }
                }
                "Shutter" -> {
                    ChoiceGrid(listOf("Set", "Auto"), if (st.shutterAuto) 1 else 0, { st.shutterAuto = it == 1 })
                    if (!st.shutterAuto) {
                        val scale = CameraScale.shutters.filter { it >= camera.shutterMin * 0.97 && it <= camera.shutterMax * 1.03 }.ifEmpty { CameraScale.shutters }
                        Dial(scale, st.shutter, { st.shutter = it }, { Exposure.formatShutter(it) }, reset = 1.0 / 125)
                    }
                }
                "ISO" -> {
                    ChoiceGrid(listOf("Set", "Auto"), if (st.isoAuto) 1 else 0, { st.isoAuto = it == 1 })
                    if (!st.isoAuto) {
                        val scale = CameraScale.isos.filter { it >= camera.isoMin * 0.97 && it <= camera.isoMax * 1.03 }.ifEmpty { CameraScale.isos }
                        Dial(scale, st.iso, { st.iso = it }, { Exposure.snapIso(it).toString() }, reset = camera.isoMin)
                    }
                }
                else -> {
                    val filters = camera.choices
                    val at = filters.indexOf(st.ndFilter).coerceAtLeast(0)
                    ValueHeader("", filters[at].let { if (it == NdFilter.None) "None" else "${it.label} · ${one(it.stops)} stops" })
                    RulerSlider(
                        at.toFloat(), 0f..(filters.size - 1).coerceAtLeast(1).toFloat(), 1f,
                        { v -> st.ndIndex = filters[v.roundToInt().coerceIn(0, filters.size - 1)].ordinal },
                        onReset = { st.ndIndex = NdFilter.None.ordinal },
                    )
                }
            }
        }

        CameraSection(st)

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
    }
}

/** A value on a scale of camera steps: its value large, a ruler to move along the steps, double-tap for [reset]. */
@Composable
private fun Dial(scale: List<Double>, value: Double, set: (Double) -> Unit, label: (Double) -> String, reset: Double) {
    val at = CameraScale.nearest(scale, value)
    ValueHeader("", label(scale[at]))
    RulerSlider(
        at.toFloat(), 0f..(scale.size - 1).coerceAtLeast(1).toFloat(), 1f,
        { v -> set(scale[v.roundToInt().coerceIn(0, scale.size - 1)]) },
        onReset = { set(scale[CameraScale.nearest(scale, reset)]) },
    )
}

/** The cameras: choose the one in use; adding or changing one opens its own window. */
@Composable
private fun CameraSection(st: PlannerState) {
    var editing by remember { mutableStateOf<Int?>(null) } // index edited, or −1 for a new one
    ToolSection("Camera", info = "The camera in use: its ISO, shutter and aperture range and the filters you own for it. Auto stays inside the range; the dials offer only what the camera has.") {
        ChoiceGrid(st.profiles.map { it.name }, st.profileIndex, { st.useProfile(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Edit", { editing = st.profileIndex })
            Chip("New camera", { editing = -1 })
        }
    }
    editing?.let { at ->
        val start = if (at >= 0) st.profiles.getOrElse(at) { st.camera } else CameraProfile("Camera ${st.profiles.size + 1}", filters = emptyList())
        OptionsSheet(if (at >= 0) "Edit camera" else "New camera", { editing = null }) {
            CameraEditor(st, start, at, onDone = { editing = null })
        }
    }
}

/**
 * A camera described: its name, ISO, shutter and aperture range (one value for a fixed aperture),
 * and the filters owned for it. A new camera starts with none: tap those you have.
 */
@Composable
private fun CameraEditor(st: PlannerState, start: CameraProfile, at: Int, onDone: () -> Unit) {
    var draft by remember { mutableStateOf(start) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.name, onValueChange = { draft = draft.copy(name = it) }, singleLine = true,
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
        )
        val isos = CameraScale.isos
        val lo = CameraScale.nearest(isos, draft.isoMin)
        val hi = CameraScale.nearest(isos, draft.isoMax)
        ValueHeader("Lowest ISO", isos[lo].roundToInt().toString())
        RulerSlider(lo.toFloat(), 0f..(isos.size - 1).toFloat(), 1f, { v -> draft = draft.copy(isoMin = isos[v.roundToInt().coerceIn(0, hi)]) }, onReset = { draft = draft.copy(isoMin = 100.0) })
        ValueHeader("Highest ISO", isos[hi].roundToInt().toString())
        RulerSlider(hi.toFloat(), 0f..(isos.size - 1).toFloat(), 1f, { v -> draft = draft.copy(isoMax = isos[v.roundToInt().coerceIn(lo, isos.size - 1)]) }, onReset = { draft = draft.copy(isoMax = 25_600.0) })
        val sh = CameraScale.shutters
        val sLo = CameraScale.nearest(sh, draft.shutterMin)
        val sHi = CameraScale.nearest(sh, draft.shutterMax)
        ValueHeader("Shortest shutter", Exposure.formatShutter(sh[sLo]))
        RulerSlider(sLo.toFloat(), 0f..(sh.size - 1).toFloat(), 1f, { v -> draft = draft.copy(shutterMin = sh[v.roundToInt().coerceIn(0, sHi)]) }, onReset = { draft = draft.copy(shutterMin = 1.0 / 8000) })
        ValueHeader("Longest shutter", Exposure.formatShutter(sh[sHi]))
        RulerSlider(sHi.toFloat(), 0f..(sh.size - 1).toFloat(), 1f, { v -> draft = draft.copy(shutterMax = sh[v.roundToInt().coerceIn(sLo, sh.size - 1)]) }, onReset = { draft = draft.copy(shutterMax = 30.0) })
        val ap = CameraScale.apertures
        val aLo = CameraScale.nearest(ap, draft.apertureMin)
        val aHi = CameraScale.nearest(ap, draft.apertureMax)
        ValueHeader("Widest aperture", Exposure.formatAperture(ap[aLo]))
        RulerSlider(aLo.toFloat(), 0f..(ap.size - 1).toFloat(), 1f, { v -> draft = draft.copy(apertureMin = ap[v.roundToInt().coerceIn(0, aHi)]) }, onReset = { draft = draft.copy(apertureMin = 2.8) })
        ValueHeader("Narrowest aperture", Exposure.formatAperture(ap[aHi]) + if (aHi == aLo) " (fixed)" else "")
        RulerSlider(aHi.toFloat(), 0f..(ap.size - 1).toFloat(), 1f, { v -> draft = draft.copy(apertureMax = ap[v.roundToInt().coerceIn(aLo, ap.size - 1)]) }, onReset = { draft = draft.copy(apertureMax = draft.apertureMin) })
        val all = NdFilter.entries.filter { it != NdFilter.None }
        LabeledChoice(
            "Filters owned (tap those you have)", all.map { it.label }, { all[it] in draft.filters },
            { i -> draft = draft.copy(filters = if (all[i] in draft.filters) draft.filters - all[i] else (draft.filters + all[i]).sortedBy { it.stops }) },
            minCell = 64.dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Save", {
                if (draft.name.isNotBlank()) {
                    st.saveProfile(draft.copy(name = draft.name.trim()), if (at >= 0) at else null)
                    onDone()
                }
            }, selected = true)
            Chip("Cancel", onDone)
            if (at >= 0 && st.profiles.size > 1) {
                Chip(if (confirmDelete) "Tap again to delete" else "Delete", {
                    if (confirmDelete) { st.deleteProfile(at); onDone() } else confirmDelete = true
                })
            }
        }
    }
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
