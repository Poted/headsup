package app.headsup.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.CameraLimits
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.ExposureSetup
import app.headsup.core.planner.ExposureWarning
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.Place
import app.headsup.core.planner.Planner
import app.headsup.core.planner.Sky
import app.headsup.core.planner.SkyConditions
import app.headsup.core.planner.Timelapse
import app.headsup.core.planner.TimelapseLimits
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** The planner's settings, kept on the phone between visits. */
private class PlannerPrefs(context: Context) {
    private val p = context.getSharedPreferences("planner", Context.MODE_PRIVATE)
    fun get(k: String, d: Float) = p.getFloat(k, d)
    fun put(k: String, v: Float) = p.edit().putFloat(k, v).apply()
    fun getString(k: String, d: String) = p.getString(k, d) ?: d
    fun putString(k: String, v: String) = p.edit().putString(k, v).apply()
}

/**
 * The light planner: for any place and day, where the sun, moon and Milky Way are, how bright the
 * light will be, and what to set on the camera at each hour. Worked on the phone with no network.
 * Drag the sky to look round; move the time bar to see the light change through the day.
 */
@Composable
fun PlannerScreen(onBack: () -> Unit, onEditor: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = remember { PlannerPrefs(context) }
    val deviceOffsetHours = remember { java.util.TimeZone.getDefault().rawOffset / 3_600_000 }

    var lat by remember { mutableStateOf(prefs.get("lat", 50.06f).toDouble()) }
    var lon by remember { mutableStateOf(prefs.get("lon", 19.94f).toDouble()) }
    var elevation by remember { mutableStateOf(prefs.get("elev", 200f).toDouble()) }
    var offsetHours by remember { mutableStateOf(prefs.get("utc", deviceOffsetHours.toFloat()).roundToInt()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var minute by remember { mutableStateOf(prefs.get("min", 18f * 60f)) }
    var bortle by remember { mutableStateOf(prefs.get("bortle", 3f).roundToInt()) }
    var cloud by remember { mutableStateOf(prefs.get("cloud", 0f)) }
    var viewAz by remember { mutableStateOf(prefs.get("az", 180f)) }

    var auto by remember { mutableStateOf(AutoSetting.entries[prefs.get("auto", 0f).roundToInt().coerceIn(0, 2)]) }
    var aperture by remember { mutableStateOf(prefs.get("ap", 2.8f).toDouble()) }
    var shutter by remember { mutableStateOf(prefs.get("sh", 0.5f).toDouble()) }
    var iso by remember { mutableStateOf(prefs.get("iso", 100f).toDouble()) }
    var ndIndex by remember { mutableStateOf(prefs.get("nd", 3f).roundToInt()) } // -1: the planner picks
    var isoMax by remember { mutableStateOf(prefs.get("isomax", 25_600f).toDouble()) }
    var isoMin by remember { mutableStateOf(prefs.get("isomin", 100f).toDouble()) }
    // Timelapse: the window of the day (minutes from midnight) and the shutter the camera may use in it.
    var tlFrom by remember { mutableStateOf(prefs.get("tlfrom", 4f * 60f)) }
    var tlTo by remember { mutableStateOf(prefs.get("tlto", 8f * 60f)) }
    var shutterMin by remember { mutableStateOf(prefs.get("smin", 0.5f).toDouble()) }
    var shutterMax by remember { mutableStateOf(prefs.get("smax", 4f).toDouble()) }
    // Saved places; reload counts the times the place was set from outside, so the typed fields follow.
    var places by remember { mutableStateOf(readPlaces(prefs)) }
    var placeName by remember { mutableStateOf("") }
    var selectedPlace by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(lat, lon, elevation, offsetHours, minute, bortle, cloud, viewAz, auto, aperture, shutter, iso, ndIndex, isoMax, isoMin, tlFrom, tlTo, shutterMin, shutterMax) {
        prefs.put("lat", lat.toFloat()); prefs.put("lon", lon.toFloat()); prefs.put("elev", elevation.toFloat())
        prefs.put("utc", offsetHours.toFloat()); prefs.put("min", minute); prefs.put("bortle", bortle.toFloat())
        prefs.put("cloud", cloud); prefs.put("az", viewAz); prefs.put("auto", auto.ordinal.toFloat())
        prefs.put("ap", aperture.toFloat()); prefs.put("sh", shutter.toFloat()); prefs.put("iso", iso.toFloat())
        prefs.put("nd", ndIndex.toFloat()); prefs.put("isomax", isoMax.toFloat()); prefs.put("isomin", isoMin.toFloat())
        prefs.put("tlfrom", tlFrom); prefs.put("tlto", tlTo); prefs.put("smin", shutterMin.toFloat()); prefs.put("smax", shutterMax.toFloat())
    }

    val place = Place(lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0), elevation)
    val zone = ZoneOffset.ofHours(offsetHours.coerceIn(-12, 14))
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val now = dayStart + minute.toLong() * 60_000L
    val conditions = SkyConditions(bortle, cloud.toDouble())
    val nd = if (ndIndex < 0) null else NdFilter.entries[ndIndex.coerceIn(0, NdFilter.entries.size - 1)]
    val setup = ExposureSetup(auto, aperture, shutter, iso, nd, CameraLimits(isoMin, isoMax))
    val frame = Planner.frame(place, now, conditions, setup)
    val times = remember(place, day, offsetHours) { Sky.sunTimes(place, zone, day) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lastKnown(context)?.let { lat = it.first; lon = it.second; elevation = it.third; reload++ }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HeadsupColors.Background)
            // Touches stop here: the editor beneath must not feel them.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Light planner", style = MaterialTheme.typography.titleMedium, color = HeadsupColors.Ink, modifier = Modifier.weight(1f))
            Chip("Editor", onEditor)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Place and date.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Latitude", lat, reload, Modifier.weight(1f)) { lat = it.coerceIn(-90.0, 90.0) }
                NumberField("Longitude", lon, reload, Modifier.weight(1f)) { lon = it.coerceIn(-180.0, 180.0) }
                NumberField("Height (m)", elevation, reload, Modifier.weight(1f)) { elevation = it.coerceIn(-400.0, 8900.0) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("◀", { day = day.minusDays(1) })
                Text(day.toString(), color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyLarge)
                Chip("▶", { day = day.plusDays(1) })
                Chip("Today", { day = LocalDate.now() })
                Box(Modifier.weight(1f))
                Chip("UTC${if (offsetHours >= 0) "+" else ""}$offsetHours −", { offsetHours = (offsetHours - 1).coerceAtLeast(-12) })
                Chip("+", { offsetHours = (offsetHours + 1).coerceAtMost(14) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Use my location", {
                    val ok = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (ok) lastKnown(context)?.let { lat = it.first; lon = it.second; elevation = it.third; reload++ }
                    else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                })
            }

            // Saved places: tap one to work on it; a place is its coordinates, height and time zone.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = placeName, onValueChange = { placeName = it }, singleLine = true,
                    label = { Text("Name this place", maxLines = 1) }, modifier = Modifier.weight(1f),
                )
                Chip("Save place", {
                    val name = placeName.trim()
                    if (name.isNotEmpty()) {
                        val one = SavedPlace(name, lat, lon, elevation, offsetHours)
                        val at = places.indexOfFirst { it.name == name }
                        places = if (at >= 0) places.toMutableList().also { it[at] = one } else places + one
                        selectedPlace = places.indexOfFirst { it.name == name }
                        writePlaces(prefs, places)
                        placeName = ""
                    }
                })
            }
            if (places.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    places.forEachIndexed { i, sp ->
                        Chip((if (selectedPlace == i) "● " else "") + sp.name, {
                            lat = sp.lat; lon = sp.lon; elevation = sp.elev; offsetHours = sp.utc
                            selectedPlace = i; confirmDelete = false; reload++
                        })
                    }
                }
                selectedPlace?.takeIf { it in places.indices }?.let { i ->
                    Chip(if (confirmDelete) "Tap again to delete ${places[i].name}" else "Delete ${places[i].name}", {
                        if (confirmDelete) {
                            places = places.filterIndexed { j, _ -> j != i }
                            writePlaces(prefs, places)
                            selectedPlace = null; confirmDelete = false
                        } else confirmDelete = true
                    })
                }
            }

            // The sky: drag sideways to turn.
            SkyChart(place, now, dayStart, viewAz, onTurn = { viewAz = ((it % 360f) + 360f) % 360f })

            // Time bar.
            Text(
                "${hhmm(minute)} · ${frame.phase} · sun ${frame.sun.altitude.roundToInt()}° · moon ${frame.moon.position.altitude.roundToInt()}° (${(frame.moon.lit * 100).roundToInt()}% lit)",
                color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium,
            )
            Slider(value = minute, onValueChange = { minute = it }, valueRange = 0f..1439f)
            Text(
                "Sunrise ${clock(times.sunrise, zone)} · Sunset ${clock(times.sunset, zone)} · Blue hour ends ${clock(times.civilDawn, zone)} / starts ${clock(times.civilDusk, zone)} · Dark from ${clock(times.astroDusk, zone)}",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
            )

            // The light of the place at night and under cloud.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Night sky (Bortle $bortle)", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Chip("−", { bortle = (bortle - 1).coerceAtLeast(1) })
                Chip("+", { bortle = (bortle + 1).coerceAtMost(9) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cloud ${(cloud * 100).roundToInt()}%", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                Slider(value = cloud, onValueChange = { cloud = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
            }

            // The exposure bar.
            Text("Scene EV ${"%.1f".format(Locale.US, frame.ev)}  (estimated, ±1.5)", color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
            val r = frame.exposure
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingTile("Aperture", Exposure.formatAperture(if (auto == AutoSetting.Aperture) r.aperture else aperture), auto == AutoSetting.Aperture, Modifier.weight(1f),
                    onToggle = { auto = AutoSetting.Aperture }, onLess = { aperture = stepStops(aperture, -1.0 / 3, true) }, onMore = { aperture = stepStops(aperture, 1.0 / 3, true) })
                SettingTile("Shutter", Exposure.formatShutter(if (auto == AutoSetting.Shutter) r.shutter else shutter), auto == AutoSetting.Shutter, Modifier.weight(1f),
                    onToggle = { auto = AutoSetting.Shutter }, onLess = { shutter = stepStops(shutter, -1.0 / 3, false) }, onMore = { shutter = stepStops(shutter, 1.0 / 3, false) })
                SettingTile("ISO", Exposure.snapIso(if (auto == AutoSetting.Iso) r.iso else iso).toString(), auto == AutoSetting.Iso, Modifier.weight(1f),
                    onToggle = { auto = AutoSetting.Iso }, onLess = { iso = (iso / 2.0.pow(1.0 / 3)).coerceAtLeast(100.0) }, onMore = { iso = (iso * 2.0.pow(1.0 / 3)).coerceAtMost(isoMax) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Filter: ${r.nd.label}${if (ndIndex < 0) " (picked)" else ""}", color = HeadsupColors.Ink, modifier = Modifier.weight(1f))
                Chip("Pick for me", { ndIndex = -1 })
                Chip("◀", { ndIndex = ((if (ndIndex < 0) r.nd.ordinal else ndIndex) - 1).coerceAtLeast(0) })
                Chip("▶", { ndIndex = ((if (ndIndex < 0) r.nd.ordinal else ndIndex) + 1).coerceAtMost(NdFilter.entries.size - 1) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ISO range: ${isoMin.roundToInt()}–${isoMax.roundToInt()}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Chip("max −", { isoMax = (isoMax / 2).coerceAtLeast(maxOf(800.0, isoMin)) })
                Chip("max +", { isoMax = (isoMax * 2).coerceAtMost(102_400.0) })
                Chip("min −", { isoMin = (isoMin / 2).coerceAtLeast(50.0) })
                Chip("min +", { isoMin = (isoMin * 2).coerceAtMost(isoMax) })
            }
            // With the shutter and aperture held: which filters keep the ISO inside its range.
            if (auto == AutoSetting.Iso) {
                val window = Exposure.ndWindow(frame.ev, aperture, shutter, CameraLimits(isoMin, isoMax))
                val fits = Exposure.filtersIn(window)
                Text(
                    "At ${Exposure.formatAperture(aperture)} and ${Exposure.formatShutter(shutter)}, ISO ${isoMin.roundToInt()}–${isoMax.roundToInt()} needs " +
                        (if (window.endInclusive < 0) "no filter (too dim even bare)"
                        else "${"%.1f".format(Locale.US, window.start.coerceAtLeast(0.0))}–${"%.1f".format(Locale.US, window.endInclusive)} stops of filter" +
                            (if (fits.isEmpty()) ": none of the listed filters" else ": " + fits.joinToString(" or ") { it.label })),
                    color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Out of the camera's reach: red, and what to change.
            when (r.warning) {
                ExposureWarning.IsoTooHigh -> Warning("ISO ${Exposure.snapIso(r.wantedIso)} is needed, above the camera's ${isoMax.roundToInt()}. " +
                    (r.suggestedNd?.let { "Try ${it.label}, a longer shutter or a wider opening." } ?: "Use a longer shutter or a wider opening."))
                ExposureWarning.IsoTooLow -> Warning("Too bright: even the lowest ISO overexposes. " +
                    (r.suggestedNd?.let { if (r.suggestionEnough) "Use ${it.label}." else "Even ${it.label} is not enough: use a faster shutter or a smaller opening too." } ?: "Use a faster shutter or a smaller opening."))
                null -> {}
            }
            // Timelapse: the filter cannot be changed during it, so one filter must serve the whole window.
            Text("Timelapse", color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
            Text(
                "From ${hhmm(tlFrom)} to ${hhmm(tlTo)} on ${day}. Aperture ${Exposure.formatAperture(aperture)}, shutter ${Exposure.formatShutter(shutterMin)}–${Exposure.formatShutter(shutterMax)}, ISO ${isoMin.roundToInt()}–${isoMax.roundToInt()} (set above).",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
            )
            Slider(value = tlFrom, onValueChange = { tlFrom = it.coerceAtMost(tlTo - 10f) }, valueRange = 0f..1429f)
            Slider(value = tlTo, onValueChange = { tlTo = it.coerceAtLeast(tlFrom + 10f) }, valueRange = 10f..1439f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shortest shutter ${Exposure.formatShutter(shutterMin)}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Chip("−", { shutterMin = stepStops(shutterMin, -1.0, false).coerceAtMost(shutterMax) })
                Chip("+", { shutterMin = stepStops(shutterMin, 1.0, false).coerceAtMost(shutterMax) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Longest shutter ${Exposure.formatShutter(shutterMax)}", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Chip("−", { shutterMax = stepStops(shutterMax, -1.0, false).coerceAtLeast(shutterMin) })
                Chip("+", { shutterMax = stepStops(shutterMax, 1.0, false) })
            }
            val tlLimits = TimelapseLimits(aperture, shutterMin, shutterMax, isoMin, isoMax)
            val span = (tlTo - tlFrom).coerceAtLeast(10f)
            val stepMinutes = (span / 12f).roundToInt().coerceAtLeast(5)
            val tl = remember(place, day, offsetHours, tlFrom, tlTo, bortle, cloud, tlLimits) {
                Timelapse.plan(Timelapse.sample(place, conditions, dayStart + tlFrom.toLong() * 60_000L, dayStart + tlTo.toLong() * 60_000L, stepMinutes), tlLimits)
            }
            Text("Light in this window: EV ${"%.1f".format(Locale.US, tl.evMin)} to ${"%.1f".format(Locale.US, tl.evMax)} (${"%.1f".format(Locale.US, tl.evMax - tl.evMin)} stops); the camera covers ${"%.1f".format(Locale.US, tlLimits.rangeStops)}.",
                color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium)
            if (tl.feasible && tl.filters.isNotEmpty()) {
                Text("Take ${tl.filters.joinToString(" or ") { it.label }} (works from ${"%.1f".format(Locale.US, tl.ndLow.coerceAtLeast(0.0))} to ${"%.1f".format(Locale.US, tl.ndHigh)} stops of filter). The rows use ${tl.chosen.label}.",
                    color = HeadsupColors.Good, style = MaterialTheme.typography.bodyMedium)
            } else if (tl.feasible) {
                Warning("The range fits (${"%.1f".format(Locale.US, tl.ndLow.coerceAtLeast(0.0))}–${"%.1f".format(Locale.US, tl.ndHigh)} stops), but none of the listed filters lies in it. The rows use ${tl.chosen.label}, the nearest.")
            } else {
                Warning("No single filter serves this window: the light changes ${"%.1f".format(Locale.US, tl.shortfallStops)} stops more than the shutter and ISO cover. Allow a longer longest shutter, a higher top ISO, or a shorter window. The rows use ${tl.chosen.label}; red rows are out of reach.")
            }
            for (st in tl.steps) {
                val c = if (st.inReach) HeadsupColors.Ink else HeadsupColors.Bad
                Text(
                    "${clock(st.millis, zone)}  sun ${st.sunAltitude.roundToInt()}°  EV ${"%.1f".format(Locale.US, st.ev)}  ${Exposure.formatShutter(st.shutter)}  ISO ${Exposure.snapIso(st.iso)}" + (if (st.inReach) "" else "  out of reach"),
                    color = c, style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "The light is estimated from the sun's and moon's height, the place's height, the night sky (Bortle) and cloud. It is a planning guide: measure on the spot when you can.",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** The sky as a flat chart: the horizon, the sun's path with its disc at the chosen time, the moon's path and the Milky Way. */
@Composable
private fun SkyChart(place: Place, now: Long, dayStart: Long, viewAz: Float, onTurn: (Float) -> Unit) {
    val measurer = rememberTextMeasurer()
    val turn = rememberUpdatedState(onTurn)
    val centre = rememberUpdatedState(viewAz)
    val fov = 120f
    val top = 75f
    val bottom = -25f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HeadsupColors.Surface)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    turn.value(centre.value - drag.x / size.width * fov)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        fun x(az: Double): Float {
            val rel = ((az - centre.value + 540.0) % 360.0) - 180.0
            return (rel / fov * w + w / 2).toFloat()
        }
        fun y(alt: Double): Float = ((top - alt) / (top - bottom) * h).toFloat()
        val horizon = y(0.0)
        // The ground below the horizon, and its line.
        drawRect(HeadsupColors.SurfaceHigh, Offset(0f, horizon), androidx.compose.ui.geometry.Size(w, h - horizon))
        drawLine(HeadsupColors.Ink, Offset(0f, horizon), Offset(w, horizon), strokeWidth = 3.dp.toPx())
        for ((name, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            val px = x(az)
            if (px in 0f..w) drawText(measurer.measure(name, TextStyle(color = HeadsupColors.TextMuted, fontSize = 13.sp)), topLeft = Offset(px - 4.dp.toPx(), horizon + 4.dp.toPx()))
        }
        // A path as joined points; a jump across the chart's edge is not joined.
        fun path(points: List<Pair<Double, Double>>, colour: Color, width: Float, closed: Boolean = false) {
            val n = points.size
            val last = if (closed) n else n - 1
            for (i in 0 until last) {
                val a = points[i]; val b = points[(i + 1) % n]
                val ax = x(a.first); val bx = x(b.first)
                if (abs(ax - bx) > w / 2) continue
                drawLine(colour, Offset(ax, y(a.second)), Offset(bx, y(b.second)), strokeWidth = width, alpha = if (a.second < 0 && b.second < 0) 0.35f else 1f)
            }
        }
        // The Milky Way's band.
        val arc = Sky.milkyWayArc(place, now, 5).map { it.azimuth to it.altitude }
        path(arc, Color(0xFF8FB4FF), 5.dp.toPx(), closed = true)
        val gc = Sky.galacticCentre(place, now)
        drawCircle(Color(0xFFBFD3FF), 7.dp.toPx(), Offset(x(gc.azimuth), y(gc.altitude)))
        drawText(measurer.measure("Galactic centre", TextStyle(color = Color(0xFFBFD3FF), fontSize = 11.sp)), topLeft = Offset(x(gc.azimuth) + 9.dp.toPx(), y(gc.altitude) - 7.dp.toPx()))
        // The moon's and the sun's paths over the day.
        val samples = (0..144).map { dayStart + it * 10L * 60_000L }
        path(samples.map { Sky.moon(place, it).position.let { p -> p.azimuth to p.altitude } }, Color(0xFFCFCFCF), 2.dp.toPx())
        path(samples.map { Sky.sun(place, it).let { p -> p.azimuth to p.altitude } }, Color(0xFFFF9800), 4.dp.toPx())
        // The moon and the sun as they stand at the chosen time (the sun a large disc, ringed).
        val moon = Sky.moon(place, now).position
        drawCircle(Color(0xFFEFEFEF), 7.dp.toPx(), Offset(x(moon.azimuth), y(moon.altitude)))
        val sun = Sky.sun(place, now)
        val c = Offset(x(sun.azimuth), y(sun.altitude))
        drawCircle(Color(0xFFFF9800), 14.dp.toPx(), c)
        drawCircle(Color(0xFFF44336), 17.dp.toPx(), c, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun SettingTile(
    label: String, value: String, isAuto: Boolean, modifier: Modifier,
    onToggle: () -> Unit, onLess: () -> Unit, onMore: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isAuto) HeadsupColors.Accent else HeadsupColors.SurfaceHigh)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ink = if (isAuto) HeadsupColors.OnAccent else HeadsupColors.Ink
        Text(label + if (isAuto) " · auto" else " 🔒", color = ink, style = MaterialTheme.typography.labelSmall)
        Text(value, color = ink, style = MaterialTheme.typography.titleMedium)
        if (isAuto) {
            Text(" ", style = MaterialTheme.typography.labelSmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("−", onLess)
                Chip("+", onMore)
            }
            Chip("Auto", onToggle)
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(HeadsupColors.SurfaceTop)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = HeadsupColors.Ink, style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HeadsupColors.Bad.copy(alpha = 0.18f)).padding(10.dp),
        color = HeadsupColors.Bad, style = MaterialTheme.typography.bodyMedium,
    )
}

/** A number typed in, kept as typed until it parses. */
@Composable
private fun NumberField(label: String, value: Double, reload: Int, modifier: Modifier, onValue: (Double) -> Unit) {
    // Kept as typed ("50." stays), and set again only when the place is set from outside (reload changes).
    var text by remember(reload) { mutableStateOf(String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A value moved by [stops] of light along the usual scale: an f-number moves by √2 per stop, a shutter by 2×. */
private fun stepStops(v: Double, stops: Double, aperture: Boolean): Double =
    if (aperture) (v * 2.0.pow(stops / 2)).coerceIn(1.0, 64.0) else (v * 2.0.pow(stops)).coerceIn(1.0 / 8000, 600.0)

private fun hhmm(minute: Float): String = String.format(Locale.US, "%02d:%02d", (minute / 60).toInt(), (minute % 60).toInt())

private fun clock(millis: Long?, zone: ZoneOffset): String =
    if (millis == null) "–" else ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }

/** The phone's last known place (latitude, longitude, height), if it has one. */
@Suppress("MissingPermission")
private fun lastKnown(context: Context): Triple<Double, Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
    return Triple(best.latitude, best.longitude, if (best.hasAltitude()) best.altitude else 0.0)
}

/** A place kept by name: where it is, how high, and its time zone as whole hours from UTC. */
private data class SavedPlace(val name: String, val lat: Double, val lon: Double, val elev: Double, val utc: Int)

private fun readPlaces(p: PlannerPrefs): List<SavedPlace> = runCatching {
    val a = org.json.JSONArray(p.getString("places", "[]"))
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        SavedPlace(o.getString("n"), o.getDouble("a"), o.getDouble("o"), o.getDouble("e"), o.getInt("u"))
    }
}.getOrDefault(emptyList())

private fun writePlaces(p: PlannerPrefs, list: List<SavedPlace>) {
    val a = org.json.JSONArray()
    list.forEach { a.put(org.json.JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lon).put("e", it.elev).put("u", it.utc)) }
    p.putString("places", a.toString())
}
