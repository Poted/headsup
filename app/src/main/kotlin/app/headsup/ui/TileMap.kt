package app.headsup.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.core.planner.WebMercator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where a map is looking: its centre and zoom. Held outside the map so it survives the map moving to another place on screen. */
internal class TileMapState(lat: Double, lon: Double, zoom: Double = 11.0) {
    var lat by mutableStateOf(lat)
    var lon by mutableStateOf(lon)
    var zoom by mutableStateOf(zoom)
    fun centre(newLat: Double, newLon: Double) { lat = newLat; lon = newLon }
}

/**
 * Street-map tiles, fetched when seen and kept on the phone, so a place looked at once can be seen
 * again with no signal (in a forest). The tiles are OpenStreetMap's, shown with its credit.
 */
internal object TileStore {
    private val memory = object : LruCache<String, ImageBitmap>(160) {}

    suspend fun load(context: Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z-$x-$y"
        memory.get(key)?.let { return@withContext it }
        val dir = File(context.cacheDir, "map_tiles").apply { mkdirs() }
        val file = File(dir, "$key.png")
        var bytes: ByteArray? = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
        if (bytes == null) {
            bytes = runCatching {
                val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", PLANNER_USER_AGENT)
                try {
                    if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
            if (bytes != null) runCatching { file.writeBytes(bytes) }
        }
        val image = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        if (image != null) memory.put(key, image)
        image
    }
}

/**
 * A map to look at and to pick a place on: drag to move, pinch to zoom, tap to put the pin there.
 * The pin is the place; the cone is the way the phone looks ([viewAz]); each of [rays] is a
 * direction from the pin (azimuth in degrees and a colour), used for where the sun rises, sets and is.
 */
@Composable
internal fun TileMap(
    state: TileMapState,
    pinLat: Double,
    pinLon: Double,
    viewAz: Float,
    rays: List<Pair<Double, Color>>,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    hours: List<Triple<Double, Double, Int>> = emptyList(),
    target: Pair<Double, Double>? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val pick = rememberUpdatedState(onPick)
    // One tile is drawn this many pixels across at its own zoom; between zooms it is scaled smoothly.
    val base = 192.0 * density
    val zoomInt = floor(state.zoom).toInt().coerceIn(2, 18)
    val tilePx = base * 2.0.pow(state.zoom - zoomInt)
    val cx = WebMercator.x(state.lon, state.zoom, base)
    val cy = WebMercator.y(state.lat, state.zoom, base)

    // The tiles that cover the view.
    val wanted = ArrayList<Triple<Int, Int, Int>>() // z, x, y
    val places = ArrayList<Pair<Triple<Int, Int, Int>, Offset>>()
    if (box.width > 0 && box.height > 0) {
        val n = 1 shl zoomInt
        val x0 = floor((cx - box.width / 2.0) / tilePx).toInt()
        val x1 = floor((cx + box.width / 2.0) / tilePx).toInt()
        val y0 = floor((cy - box.height / 2.0) / tilePx).toInt().coerceAtLeast(0)
        val y1 = floor((cy + box.height / 2.0) / tilePx).toInt().coerceAtMost(n - 1)
        for (ty in y0..y1) for (tx in x0..x1) {
            val t = Triple(zoomInt, ((tx % n) + n) % n, ty)
            wanted += t
            places += t to Offset((tx * tilePx - cx + box.width / 2.0).toFloat(), (ty * tilePx - cy + box.height / 2.0).toFloat())
        }
    }
    val wantedKey = wanted.joinToString(",") { "${it.first}-${it.second}-${it.third}" }
    LaunchedEffect(wantedKey) {
        for ((z, x, y) in wanted) {
            val key = "$z-$x-$y"
            if (key !in tiles) launch { TileStore.load(context, z, x, y)?.let { tiles[key] = it } }
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .background(Color(0xFFDDDDDD))
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val z = state.zoom
                    val nx = WebMercator.x(state.lon, z, base) - pan.x
                    val ny = WebMercator.y(state.lat, z, base) - pan.y
                    state.lon = WebMercator.lon(nx, z, base)
                    state.lat = WebMercator.lat(ny, z, base).coerceIn(-85.0, 85.0)
                    state.zoom = (z + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(2.0, 18.0)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val z = state.zoom
                    val px = WebMercator.x(state.lon, z, base) + at.x - box.width / 2.0
                    val py = WebMercator.y(state.lat, z, base) + at.y - box.height / 2.0
                    pick.value(WebMercator.lat(py, z, base).coerceIn(-85.0, 85.0), WebMercator.lon(px, z, base))
                }
            },
    ) {
        for ((t, at) in places) {
            val image = tiles["${t.first}-${t.second}-${t.third}"] ?: continue
            drawImage(
                image,
                dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
                dstSize = IntSize(tilePx.roundToInt() + 1, tilePx.roundToInt() + 1),
            )
        }
        val pin = Offset(
            (WebMercator.x(pinLon, state.zoom, base) - cx + box.width / 2.0).toFloat(),
            (WebMercator.y(pinLat, state.zoom, base) - cy + box.height / 2.0).toFloat(),
        )
        fun dir(az: Double) = Offset(sin(Math.toRadians(az)).toFloat(), -cos(Math.toRadians(az)).toFloat())
        val len = min(size.width, size.height) * 0.45f
        // The sun's directions: where it rises, sets and stands at the chosen time.
        for ((az, colour) in rays) {
            drawLine(colour, pin, pin + dir(az) * (len * 1.6f), strokeWidth = 2.dp.toPx())
        }
        // The sun's hours around the pin: the higher it climbs the nearer the pin, as the shadow of its path on the ground.
        if (hours.isNotEmpty()) {
            val r1 = len * 1.3f
            for ((az, alt, hour) in hours) {
                val p = pin + dir(az) * (r1 * cos(Math.toRadians(alt)).toFloat())
                drawCircle(Color.White, 9.dp.toPx(), p)
                drawCircle(Color(0xFFFFB300), 7.5f.dp.toPx(), p)
                val l = measurer.measure(hour.toString(), TextStyle(color = Color.Black, fontSize = 9.sp))
                drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f))
            }
        }
        // The way the phone looks: a cone.
        val cone = Path().apply {
            moveTo(pin.x, pin.y)
            val a = pin + dir(viewAz - 30.0) * len
            val b = pin + dir(viewAz + 30.0) * len
            lineTo(a.x, a.y)
            lineTo(b.x, b.y)
            close()
        }
        drawPath(cone, Color(0x55F44336))
        drawPath(cone, Color(0xFFF44336), style = Stroke(width = 1.5.dp.toPx()))
        // The target looked for (a peak, a tower) and the line of sight to it.
        if (target != null) {
            val t = Offset(
                (WebMercator.x(target.second, state.zoom, base) - cx + box.width / 2.0).toFloat(),
                (WebMercator.y(target.first, state.zoom, base) - cy + box.height / 2.0).toFloat(),
            )
            drawLine(Color(0xFF7C4DFF), pin, t, strokeWidth = 2.5.dp.toPx())
            drawCircle(Color.White, 8.dp.toPx(), t)
            drawCircle(Color(0xFF7C4DFF), 6.dp.toPx(), t)
        }
        drawCircle(Color.White, 7.dp.toPx(), pin)
        drawCircle(Color(0xFFF44336), 5.dp.toPx(), pin)
        drawText(
            measurer.measure("© OpenStreetMap contributors", TextStyle(color = Color(0xFF333333), fontSize = 9.sp)),
            topLeft = Offset(4.dp.toPx(), size.height - 14.dp.toPx()),
        )
    }
}
