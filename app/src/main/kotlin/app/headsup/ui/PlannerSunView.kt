package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.core.planner.Place
import app.headsup.core.planner.Sky
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private val SunOrange = Color(0xFFFF9800)
private val SunriseBlue = Color(0xFF42A5F5)
private val SunsetRed = Color(0xFFEF5350)

/**
 * The sun view: the sky of the place as a chart (the sun's path with its disc at the chosen hour,
 * the moon, the Milky Way) with a small map in its corner showing the place, the way the phone
 * looks, and where the sun rises, sets and stands. The map and the sky swap places with ⤢.
 * Below: the hour, the day and the place itself, typed, found by the phone, or picked on the map.
 */
@Composable
internal fun SunView(st: PlannerState, onLocate: () -> Unit) {
    val place = st.place
    val sun = Sky.sun(place, st.now)
    val moon = Sky.moon(place, st.now)
    val times = remember(place, st.day, st.offsetHours) { Sky.sunTimes(place, st.zone, st.day) }
    val map = remember { TileMapState(st.lat, st.lon) }
    LaunchedEffect(st.recentre) { map.centre(st.lat, st.lon) }
    var mapBig by rememberSaveable { mutableStateOf(false) }

    // Where the sun rises, sets and stands, as directions from the pin.
    val rays = buildList {
        times.sunrise?.let { add(Sky.sun(place, it).azimuth to SunriseBlue) }
        times.sunset?.let { add(Sky.sun(place, it).azimuth to SunsetRed) }
        add(sun.azimuth to SunOrange)
    }
    // A dot for each hour the sun is up: its direction, height and the hour.
    val hourMarks = remember(place, st.day, st.offsetHours) {
        (0..23).mapNotNull { h -> Sky.sun(place, st.dayStart + h * 3_600_000L).let { if (it.altitude > 0) Triple(it.azimuth, it.altitude, h) else null } }
    }
    val mapView: @Composable (Modifier) -> Unit = { m ->
        TileMap(map, st.lat, st.lon, st.viewAz, rays, onPick = { la, lo -> st.setPlace(la, lo, fromMap = true) }, modifier = m, hours = hourMarks)
    }
    val skyView: @Composable (Modifier) -> Unit = { m ->
        SkyChart(place, st.now, st.dayStart, st.viewAz, onTurn = { st.viewAz = ((it % 360f) + 360f) % 360f }, modifier = m)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(14.dp))) {
            if (!mapBig) skyView(Modifier.fillMaxSize()) else mapView(Modifier.fillMaxSize())
            // The other view, small, in the corner.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, HeadsupColors.Ink, RoundedCornerShape(10.dp)),
            ) {
                if (!mapBig) mapView(Modifier.fillMaxSize()) else skyView(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { Chip("⤢", { mapBig = !mapBig }) }
            }
        }
        if (mapBig) Text("Tap the big map to put the pin there; drag and pinch to move and zoom.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        else Text("Drag the sky to turn where the phone looks (the red cone on the map). Tap the small map to put the pin there, or ⤢ to make it big.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        Text(
            "Map lines: blue sunrise ${clock(times.sunrise, st.zone)} (${times.sunrise?.let { Sky.sun(place, it).azimuth.roundToInt() } ?: "–"}°), red sunset ${clock(times.sunset, st.zone)} (${times.sunset?.let { Sky.sun(place, it).azimuth.roundToInt() } ?: "–"}°), orange the sun at ${hhmm(st.minute)}. The phone looks ${st.viewAz.roundToInt()}°.",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
        )

        // The hour.
        Text(
            "${hhmm(st.minute)} · sun ${sun.altitude.roundToInt()}° at ${sun.azimuth.roundToInt()}° · moon ${moon.position.altitude.roundToInt()}° (${(moon.lit * 100).roundToInt()}% lit)",
            color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyMedium,
        )
        DayBar(st)
        Text(
            "Sunrise ${clock(times.sunrise, st.zone)} · Sunset ${clock(times.sunset, st.zone)} · Blue hour ends ${clock(times.civilDawn, st.zone)} / starts ${clock(times.civilDusk, st.zone)} · Dark from ${clock(times.astroDusk, st.zone)}",
            color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
        )
        DateRow(st)

        // The place.
        Text("Place", color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Latitude", st.lat, st.reload, Modifier.weight(1f)) { st.lat = it.coerceIn(-90.0, 90.0) }
            NumberField("Longitude", st.lon, st.reload, Modifier.weight(1f)) { st.lon = it.coerceIn(-180.0, 180.0) }
            NumberField("Height (m)", st.elevation, st.reload, Modifier.weight(1f)) { st.elevation = it.coerceIn(-400.0, 8900.0) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Use my location", onLocate)
            Chip("Go to the pin", { map.centre(st.lat, st.lon) })
            Text("Height is not on the map: set it by hand.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        PlacesBar(st)
    }
}

/** Day and time zone of the plan. */
@Composable
internal fun DateRow(st: PlannerState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("◀", { st.day = st.day.minusDays(1) })
        Text(st.day.toString(), color = HeadsupColors.Ink, style = MaterialTheme.typography.bodyLarge)
        Chip("▶", { st.day = st.day.plusDays(1) })
        Chip("Today", { st.day = LocalDate.now() })
        Box(Modifier.weight(1f))
        Chip("UTC${if (st.offsetHours >= 0) "+" else ""}${st.offsetHours} −", { st.offsetHours = (st.offsetHours - 1).coerceAtLeast(-12) })
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

/** The sky as a flat chart: the horizon, the sun's path with its disc at the chosen time, the moon's path and the Milky Way. */
@Composable
internal fun SkyChart(place: Place, now: Long, dayStart: Long, viewAz: Float, onTurn: (Float) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val turn = rememberUpdatedState(onTurn)
    val centre = rememberUpdatedState(viewAz)
    val fov = 120f
    val top = 75f
    val bottom = -25f
    Canvas(
        modifier
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
        drawRect(HeadsupColors.SurfaceHigh, Offset(0f, horizon), Size(w, h - horizon))
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
        path(samples.map { Sky.sun(place, it).let { p -> p.azimuth to p.altitude } }, SunOrange, 4.dp.toPx())
        // A dot on the sun's path for each hour, with its number.
        for (hour in 0..23) {
            val s = Sky.sun(place, dayStart + hour * 3_600_000L)
            if (s.altitude < -3) continue
            val c = Offset(x(s.azimuth), y(s.altitude))
            if (c.x < 0f || c.x > w) continue
            drawCircle(Color.White, 9.dp.toPx(), c)
            drawCircle(SunOrange, 7.5f.dp.toPx(), c)
            val l = measurer.measure(hour.toString(), TextStyle(color = Color.Black, fontSize = 9.sp))
            drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f))
        }
        // The moon and the sun as they stand at the chosen time (the sun a large disc, ringed).
        val moon = Sky.moon(place, now).position
        drawCircle(Color(0xFFEFEFEF), 7.dp.toPx(), Offset(x(moon.azimuth), y(moon.altitude)))
        val sun = Sky.sun(place, now)
        val c = Offset(x(sun.azimuth), y(sun.altitude))
        drawCircle(SunOrange, 14.dp.toPx(), c)
        drawCircle(Color(0xFFF44336), 17.dp.toPx(), c, style = Stroke(width = 3.dp.toPx()))
    }
}
