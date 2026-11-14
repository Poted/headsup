package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.core.planner.Geo
import app.headsup.core.planner.HorizonProfile
import app.headsup.core.planner.Place
import app.headsup.core.planner.Sky
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SunOrange = Color(0xFFFF9800)
private val SunriseBlue = Color(0xFF42A5F5)
private val SunsetRed = Color(0xFFEF5350)
private val MilkyBlue = Color(0xFF8FB4FF)

/**
 * The sun view: the sky of the place over the whole screen (the sun's path with a dot for every hour
 * and its disc at the chosen hour, the moon, the Milky Way), turned and tilted by dragging and
 * zoomed by pinching, with a small map in the corner showing the place, the way the phone looks,
 * and where the sun rises, sets and stands (⤢ swaps the two). Below: the day bar and the day.
 * The place and the day's times open as panels over the sky.
 */
@Composable
internal fun SunView(st: PlannerState, onCalculator: () -> Unit, onTimes: () -> Unit, modifier: Modifier = Modifier) {
    val place = st.place
    val sun = Sky.sun(place, st.now)
    val moon = Sky.moon(place, st.now)
    val times = remember(place, st.day, st.offsetHours, st.aboveGround) { Sky.sunTimes(place, st.zone, st.day, st.aboveGround) }
    val map = remember { TileMapState(st.lat, st.lon) }
    LaunchedEffect(st.recentre) { map.centre(st.lat, st.lon) }
    var mapBig by rememberSaveable { mutableStateOf(false) }

    // Where the sun rises, sets and stands, as directions from the pin; a dot for each hour it is up.
    val rays = buildList {
        times.sunrise?.let { add(Sky.sun(place, it).azimuth to SunriseBlue) }
        times.sunset?.let { add(Sky.sun(place, it).azimuth to SunsetRed) }
        add(sun.azimuth to SunOrange)
    }
    val hourMarks = remember(place, st.day, st.offsetHours) {
        (0..23).mapNotNull { h -> Sky.sun(place, st.dayStart + h * 3_600_000L).let { if (it.altitude > 0) Triple(it.azimuth, it.altitude, h) else null } }
    }
    val mapView: @Composable (Modifier) -> Unit = { m ->
        TileMap(
            map, st.lat, st.lon, st.viewAz, rays,
            onPick = { la, lo -> st.setPlace(la, lo, fromMap = true) },
            modifier = m, hours = hourMarks,
        )
    }
    val skyView: @Composable (Modifier) -> Unit = { m ->
        SkyChart(
            place, st.now, st.dayStart, st.viewAz, onTurn = { st.viewAz = ((it % 360f) + 360f) % 360f }, modifier = m,
            skyline = st.skylineHere,
        )
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (!mapBig) skyView(Modifier.fillMaxSize()) else mapView(Modifier.fillMaxSize())
            // What is up at the chosen hour.
            Column(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("${hhmm(st.minute)} · ${st.day}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text("Sun ${sun.azimuth.roundToInt()}° · ${sun.altitude.roundToInt()}° up", color = SunOrange, style = MaterialTheme.typography.labelMedium)
                Text("Moon ${moon.position.azimuth.roundToInt()}° · ${moon.position.altitude.roundToInt()}° up · ${(moon.lit * 100).roundToInt()}% lit", color = Color(0xFFE0E0E0), style = MaterialTheme.typography.labelMedium)
            }
            // The other view, small, in the corner.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, HeadsupColors.ControlEdge, RoundedCornerShape(10.dp)),
            ) {
                if (!mapBig) mapView(Modifier.fillMaxSize()) else skyView(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd)) { IconAction(HeadsupIcons.SwapViews, if (mapBig) "Show the sky large" else "Show the map large", { mapBig = !mapBig }) }
            }
        }
        // The hour and the day.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposureHud(st, onCalculator)
            DayBar(st)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DayPicker(st, Modifier.weight(1f))
                Chip("Times", onTimes)
            }
        }
    }
}

/** The place: typed, found by the phone, picked on the map, or one kept by name. */
@Composable
internal fun PlacePanel(st: PlannerState, onLocate: () -> Unit) {
    // A place found by name (towns, villages, peaks, passes, lakes, huts in Poland, Slovakia and Czechia),
    // or pasted: coordinates, a Plus Code (as Google Maps now shows), or a Google Maps link.
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { st.loadPlaceNames() }
    val names = st.gazetteer
    val locate = { words: String -> names?.locate(words, st.lat, st.lon)?.let { it.lat to it.lon } }
    // Worked again once the names are read: a short Plus Code needs its town's place.
    val parsed = remember(query, names) { app.headsup.core.planner.PlaceInput.parse(query, st.lat, st.lon, locate) }
    val found = remember(query, names) { if (parsed == null) names?.search(query, st.lat, st.lon, 8).orEmpty() else emptyList() }
    ToolSection("Find", info = "Search towns, villages, peaks, passes, lakes and huts in Poland, Slovakia and Czechia, or paste coordinates, a Plus Code or a Google Maps link. On the Sun view, a tap on the map puts the pin there.") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Use my location", onLocate)
        Chip("Go to the pin", { st.recentre++ })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; note = "" }, singleLine = true,
            label = { Text("Place, coordinates or Maps link", maxLines = 1) }, modifier = Modifier.weight(1f),
        )
        if (parsed != null) Chip("Go", {
            st.setPlace(parsed.lat, parsed.lon)
            note = "From the ${parsed.from}: ${String.format(java.util.Locale.US, "%.5f, %.5f", parsed.lat, parsed.lon)}"
            query = ""
        })
    }
    for (p in found) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HeadsupColors.SurfaceHigh)
                .clickable {
                    st.setPlace(p.lat, p.lon)
                    note = "${p.name} (${p.kind}${p.elevation?.let { ", $it m" } ?: ""})"
                    query = ""
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.name, color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${p.kind}${p.elevation?.let { " · $it m" } ?: ""} · ${p.country} · ${one(Geo.distance(st.lat, st.lon, p.lat, p.lon) / 1000)} km",
                color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (query.length >= 2 && parsed == null && found.isEmpty()) {
        Text(
            if (names?.places?.isEmpty() == true) "Place names are not in this build; coordinates, Plus Codes and Maps links still work."
            else "Nothing by that name. A short Maps link (maps.app.goo.gl) has no place in it: open it and copy the Plus Code or the coordinates.",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
        )
    }
    if (note.isNotEmpty()) Text(note, color = HeadsupColors.Good, style = MaterialTheme.typography.labelMedium)
    }
    ToolSection("Coordinates", info = "The ground's height comes from the terrain model when the place changes; it can be typed over.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Latitude", st.lat, st.reload, Modifier.weight(1f)) { st.lat = it.coerceIn(-90.0, 90.0) }
            NumberField("Longitude", st.lon, st.reload, Modifier.weight(1f)) { st.lon = it.coerceIn(-180.0, 180.0) }
            NumberField("Ground (m)", st.elevation, st.reload + st.groundVersion * 100_000, Modifier.weight(1f)) { st.elevation = it.coerceIn(-430.0, 8900.0) }
        }
        if (st.groundStatus.isNotEmpty()) Text(st.groundStatus, color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
    }
    ToolSection("Camera height", info = "How far above the ground the camera is, for a drone, a roof or a tower: the horizon dips, and the sun rises sooner and sets later. Double-tap the dial for the ground.") {
        ValueHeader("Above the ground", "${st.aboveGround.roundToInt()} m")
        RulerSlider(st.aboveGround.toFloat(), 0f..500f, 5f, { v -> st.aboveGround = v.toDouble().coerceIn(0.0, 500.0) }, onReset = { st.aboveGround = 0.0 })
    }
    ToolSection("Day and time zone", info = "The day of the plan, and the zone its hours are given in.") {
        DateRow(st)
    }
    ToolSection("Saved places", info = "Places kept by name, to open again. Delete asks once more before it removes one.") {
        PlacesBar(st)
    }
    Text(
        "Place names: GeoNames (CC BY 4.0). Map © OpenStreetMap contributors. Heights: Terrain Tiles on AWS (SRTM and USGS 3DEP courtesy of the U.S. Geological Survey; Copernicus EU-DEM, funded by the European Union; and others).",
        color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
    )
}

/** The day's times: sun, golden and blue hours, the moon, and when the Milky Way's centre can be shot. */
@Composable
internal fun TimesPanel(st: PlannerState) {
    val place = st.place
    val zone = st.zone
    val key = listOf(place, st.day, st.offsetHours, st.aboveGround)
    val sunT = remember(key) { Sky.sunTimes(place, zone, st.day, st.aboveGround) }
    val moonT = remember(key) { Sky.moonTimes(place, zone, st.day, st.aboveGround) }
    val golden = remember(key) { Sky.goldenHours(place, zone, st.day) }
    val blue = remember(key) { Sky.blueHours(place, zone, st.day) }
    val milky = remember(key) { Sky.milkyWayNight(place, zone, st.day) }
    val moonNoon = remember(key) { Sky.moon(place, st.dayStart + 12 * 3_600_000L) }
    fun spans(list: List<Sky.Interval>) = if (list.isEmpty()) "–" else list.joinToString(", ") { "${clock(it.start, zone)}–${clock(it.end, zone)}" }
    @Composable fun line(label: String, value: String, colour: Color = HeadsupColors.Ink) {
        Row {
            Text(label, color = HeadsupColors.TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, color = colour, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMonoFamily))
        }
    }
    ToolSection("Sun and moon", info = "The day's light at this place, in the plan's time zone. Golden hour: the sun up to 6° high, warm and low. Blue hour: the sun 4° to 6° below the horizon.") {
    line("Sunrise / sunset", "${clock(sunT.sunrise, zone)} / ${clock(sunT.sunset, zone)}", SunOrange)
    line("Golden hour", spans(golden))
    line("Blue hour", spans(blue))
    line("Fully dark", "${clock(sunT.astroDusk, zone)} → ${clock(sunT.astroDawn, zone)}")
    line("Moonrise / moonset", "${clock(moonT.rise, zone)} / ${clock(moonT.set, zone)}")
    line("Moon", "${(moonNoon.lit * 100).roundToInt()}% lit")
    }
    st.skylineHere?.let { sky ->
      ToolSection("Over the land", info = "When the sun or moon clears the hills and mountains around (the green line on the sky), up to 60 km away.") {
        val sunLand = remember(key, sky) { Sky.sunOverSkyline(place, zone, st.day, sky) }
        val moonLand = remember(key, sky) { Sky.moonOverSkyline(place, zone, st.day, sky) }
        line("Sun over the land / behind it", "${clock(sunLand.rise, zone)} / ${clock(sunLand.set, zone)}", Color(0xFF8BC34A))
        line("Moon over the land / behind it", "${clock(moonLand.rise, zone)} / ${clock(moonLand.set, zone)}", Color(0xFF8BC34A))
      }
    }
    if (st.skylineStatus.isNotEmpty()) Text(st.skylineStatus, color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
    ToolSection("Milky Way", info = "Its centre at least 5° up while the sky is fully dark (sun 18° down), in the night after this day; \"no moon\" also needs the moon down or under a quarter lit.") {
    line("Centre, dark", spans(milky.dark), MilkyBlue)
    line("… and no moon", spans(milky.moonless), MilkyBlue)
    }

    // The moon's phases from this day.
    ToolSection("Moon phases", info = "The moon's phases in the month from this day, in the plan's time zone.") {
    val phases = remember(st.day, st.offsetHours) { Sky.moonPhases(st.dayStart, 30) }
    for (p in phases) {
        val t = java.time.Instant.ofEpochMilli(p.millis).atZone(zone)
        line(p.name, "${t.toLocalDate()} ${String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)}")
    }
    }

    // The best nights for the Milky Way in the year from this day.
    val scope = rememberCoroutineScope()
    var season by remember(key) { mutableStateOf<List<Sky.MilkyWayScore>?>(null) }
    var searching by remember(key) { mutableStateOf(false) }
    ToolSection("Best Milky Way nights", info = "The nights in the year from this day with the centre highest in a dark, moonless sky. Tap one to open it at the start of its moonless window.") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(season?.let { "${it.size.coerceAtMost(10)} found" } ?: "Not looked for yet.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Chip(if (searching) "Looking…" else "Find in the next year", {
            if (!searching) {
                searching = true
                scope.launch {
                    season = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Sky.milkyWaySeason(place, zone, st.day, 365) }
                    searching = false
                }
            }
        })
    }
    season?.let { list ->
        if (list.isEmpty()) Text("No night here has the centre up in a dark, moonless sky.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        for (n in list.take(10)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HeadsupColors.SurfaceHigh)
                    .clickable {
                        // Opens that night at the start of its moonless window.
                        n.moonless.firstOrNull()?.let { iv ->
                            val t = java.time.Instant.ofEpochMilli(iv.start).atZone(zone)
                            st.day = t.toLocalDate()
                            st.minute = (t.hour * 60 + t.minute).toFloat()
                        }
                    }
                    .padding(10.dp),
            ) {
                Text(n.date.toString(), color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${spans(n.moonless)} · up to ${n.bestAltitude.roundToInt()}°", color = MilkyBlue, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    }
}

/** Day and time zone of the plan. */
@Composable
internal fun DateRow(st: PlannerState) {
    DayPicker(st)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Time zone", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Chip("−", { st.offsetHours = (st.offsetHours - 1).coerceAtLeast(-12) })
        Text(
            "UTC${if (st.offsetHours >= 0) "+" else ""}${st.offsetHours}",
            color = HeadsupColors.Ink, style = MaterialTheme.typography.labelLarge.copy(fontFamily = GeistMonoFamily), modifier = Modifier.padding(horizontal = 8.dp),
        )
        Chip("+", { st.offsetHours = (st.offsetHours + 1).coerceAtMost(14) })
    }
}

/** Saved places: name the current one to keep it; tap a kept one to work on it. */
@Composable
private fun PlacesBar(st: PlannerState) {
    var name by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name this place", maxLines = 1) }, modifier = Modifier.weight(1f),
        )
        Chip("Save place", {
            val n = name.trim()
            if (n.isNotEmpty()) { st.savePlace(n); name = "" }
        })
    }
    if (st.places.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.places.forEachIndexed { i, sp ->
                Chip(sp.name, { confirmDelete = false; st.openPlace(i) }, selected = st.selectedPlace == i)
            }
        }
        st.selectedPlace?.takeIf { it in st.places.indices }?.let { i ->
            Chip(if (confirmDelete) "Tap again to delete ${st.places[i].name}" else "Delete ${st.places[i].name}", {
                if (confirmDelete) { st.deletePlace(i); confirmDelete = false } else confirmDelete = true
            })
        }
    }
}

/**
 * The sky as a flat chart: the horizon, height lines every 30°, the sun's path with a dot for every
 * hour and its disc at the chosen time, the moon's path and the Milky Way. Drag to turn and tilt,
 * pinch to zoom; degrees are the same size across and up.
 */
@Composable
internal fun SkyChart(
    place: Place, now: Long, dayStart: Long, viewAz: Float, onTurn: (Float) -> Unit, modifier: Modifier = Modifier,
    skyline: HorizonProfile? = null,
) {
    val measurer = rememberTextMeasurer()
    val turn = rememberUpdatedState(onTurn)
    val centre = rememberUpdatedState(viewAz)
    var fov by remember { mutableStateOf(120f) } // degrees across
    var viewAlt by remember { mutableStateOf(20f) } // the height at the middle of the chart
    Canvas(
        modifier
            .background(Color(0xFF0B1020))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val perPx = fov / size.width
                    turn.value(centre.value - pan.x * perPx)
                    viewAlt = (viewAlt + pan.y * perPx).coerceIn(-10f, 80f)
                    fov = (fov / zoom).coerceIn(30f, 200f)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val perPx = fov / w
        fun x(az: Double): Float {
            val rel = ((az - centre.value + 540.0) % 360.0) - 180.0
            return (w / 2 + rel / perPx).toFloat()
        }
        fun y(alt: Double): Float = (h / 2 - (alt - viewAlt) / perPx).toFloat()
        val horizon = y(0.0)
        // The ground, the horizon, height lines and the points of the compass.
        if (skyline == null) {
            if (horizon < h) drawRect(Color(0xFF1C1F26), Offset(0f, horizon), Size(w, h - horizon))
        } else {
            // The land: hills and mountains as they stand around the place.
            val land = Path().apply {
                moveTo(0f, h)
                var px = 0f
                while (px <= w + 3f) {
                    lineTo(px, y(skyline.at((centre.value + (px - w / 2) * perPx).toDouble())))
                    px += 3f
                }
                lineTo(w, h)
                close()
            }
            drawPath(land, Color(0xFF1C1F26))
        }
        for (alt in listOf(30.0, 60.0)) drawLine(Color(0x33FFFFFF), Offset(0f, y(alt)), Offset(w, y(alt)), strokeWidth = 1.dp.toPx())
        for (alt in listOf(30, 60)) drawText(measurer.measure("$alt°", TextStyle(color = Color(0x66FFFFFF), fontSize = 10.sp)), topLeft = Offset(4.dp.toPx(), y(alt.toDouble()) - 14.dp.toPx()))
        drawLine(Color.White.copy(alpha = if (skyline == null) 1f else 0.35f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = 2.dp.toPx())
        if (skyline != null) {
            var px = 0f
            var prev = Offset(0f, y(skyline.at((centre.value - w / 2 * perPx).toDouble())))
            while (px <= w) {
                px += 3f
                val p = Offset(px, y(skyline.at((centre.value + (px - w / 2) * perPx).toDouble())))
                drawLine(Color(0xFF8BC34A), prev, p, strokeWidth = 2.dp.toPx())
                prev = p
            }
        }
        for ((i, name) in listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").withIndex()) {
            val px = x(i * 45.0)
            if (px in 0f..w) {
                drawLine(Color(0x88FFFFFF), Offset(px, horizon), Offset(px, horizon + 6.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                val l = measurer.measure(name, TextStyle(color = Color(0xCCFFFFFF), fontSize = 12.sp))
                drawText(l, topLeft = Offset(px - l.size.width / 2f, horizon + 8.dp.toPx()))
            }
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
        // The Milky Way's band and its centre.
        val arc = Sky.milkyWayArc(place, now, 5).map { it.azimuth to it.altitude }
        path(arc, MilkyBlue.copy(alpha = 0.7f), 5.dp.toPx(), closed = true)
        val gc = Sky.galacticCentre(place, now)
        drawCircle(Color(0xFFBFD3FF), 6.dp.toPx(), Offset(x(gc.azimuth), y(gc.altitude)))
        drawText(measurer.measure("Galactic centre", TextStyle(color = Color(0xFFBFD3FF), fontSize = 11.sp)), topLeft = Offset(x(gc.azimuth) + 9.dp.toPx(), y(gc.altitude) - 7.dp.toPx()))
        // The moon's and the sun's paths over the day.
        val samples = (0..144).map { dayStart + it * 10L * 60_000L }
        path(samples.map { Sky.moon(place, it).position.let { p -> p.azimuth to p.altitude } }, Color(0xFF9E9E9E), 2.dp.toPx())
        path(samples.map { Sky.sun(place, it).let { p -> p.azimuth to p.altitude } }, SunOrange, 3.dp.toPx())
        // A dot on the sun's path for each hour, with its number.
        for (hour in 0..23) {
            val s = Sky.sun(place, dayStart + hour * 3_600_000L)
            if (s.altitude < -3) continue
            val c = Offset(x(s.azimuth), y(s.altitude))
            if (c.x < 0f || c.x > w) continue
            drawCircle(Color.White, 10.dp.toPx(), c)
            drawCircle(SunOrange, 8.5f.dp.toPx(), c)
            val l = measurer.measure(hour.toString(), TextStyle(color = Color.Black, fontSize = 10.sp))
            drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f))
        }
        // The moon and the sun as they stand at the chosen time (the sun a large disc, ringed).
        val moon = Sky.moon(place, now).position
        drawCircle(Color(0xFFEFEFEF), 8.dp.toPx(), Offset(x(moon.azimuth), y(moon.altitude)))
        val sun = Sky.sun(place, now)
        val c = Offset(x(sun.azimuth), y(sun.altitude))
        drawCircle(Color(0xFFFFB300), 14.dp.toPx(), c)
        drawCircle(Color(0xFFF44336), 17.dp.toPx(), c, style = Stroke(width = 3.dp.toPx()))
    }
}
