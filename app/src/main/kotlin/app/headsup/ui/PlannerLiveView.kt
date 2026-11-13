package app.headsup.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.headsup.core.planner.Ar
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.Exposure
import app.headsup.core.planner.Planner
import app.headsup.core.planner.Sky
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private val Gold = Color(0xFFFFB300)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * The live view: the camera with the sky laid over it. The sun's path for the chosen day is drawn
 * where it really goes, with a dot for every hour, the horizon, the moon and (at night) the Milky
 * Way, turning as the phone does. Aim it at the place you will shoot: it reads the light there,
 * which can be taken as the base of the hours, so the exposure for later is worked from what the
 * camera really sees. Held upright (the view keeps the phone from turning sideways while it is open).
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
internal fun LiveView(st: PlannerState, onLocate: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // The overlay is laid for the phone held upright, so the screen stays so while this view is open.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (activity != null && before != null) activity.requestedOrientation = before }
    }

    // The phone's attitude (smoothed), and a count of its changes to redraw by.
    val rotation = remember { DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 } }
    var tick by remember { mutableStateOf(0) }
    var hasSensor by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasSensor = sensor != null
        val m = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(m, e.values)
                for (i in 0 until 9) rotation[i] += (m[i] - rotation[i]) * 0.25
                tick++
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    // The light the camera reads (exposure value at ISO 100), and the field of view of its lens.
    var reading by remember { mutableStateOf<Double?>(null) }
    val smooth = remember { doubleArrayOf(Double.NaN) }
    var longFov by remember { mutableStateOf<Double?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(granted, lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = future.get()
                provider = p
                val builder = Preview.Builder()
                // What the camera set itself to for the scene (f-number, shutter, ISO) is the light it read.
                Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                        if (t <= 0L || iso <= 0) return
                        val f = (result.get(CaptureResult.LENS_APERTURE) ?: 1.8f).toDouble()
                        val ev = log2(f * f / (t / 1e9)) - log2(iso / 100.0)
                        smooth[0] = if (smooth[0].isNaN()) ev else smooth[0] + (ev - smooth[0]) * 0.15
                        // Shown only when it has moved a little, so the screen is not redrawn for every frame.
                        val shown = reading
                        if (shown == null || abs(shown - smooth[0]) > 0.05) reading = smooth[0]
                    }
                })
                val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                val camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                runCatching {
                    val info = Camera2CameraInfo.from(camera.cameraInfo)
                    val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (size != null && focal != null) longFov = Ar.fieldOfView(max(size.width, size.height).toDouble(), focal.toDouble())
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { provider?.unbindAll() }
    }

    val place = st.place
    val declination = remember(place, st.day) { GeomagneticField(place.lat.toFloat(), place.lon.toFloat(), place.elevation.toFloat(), st.now).declination.toDouble() }
    val sunSamples = remember(place, st.day, st.offsetHours) {
        (0..144).map { Sky.sun(place, st.dayStart + it * 10L * 60_000L) }
    }
    val hours = remember(place, st.day, st.offsetHours) {
        (0..23).map { it to Sky.sun(place, st.dayStart + it * 3_600_000L) }
    }

    Box(modifier.background(Color.Black)) {
        if (!granted) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The camera shows the real sky under the sun's path, and reads the light.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Chip("Allow the camera", { permission.launch(Manifest.permission.CAMERA) })
            }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val measurer = rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize()) {
                tick.let { } // redraw as the phone turns
                val w = size.width.toDouble()
                val h = size.height.toDouble()
                val f = Ar.focalPx(longFov ?: 65.0, 0.75, w, h)
                fun at(az: Double, alt: Double) = Ar.project(rotation, Ar.magneticAzimuth(az, declination), alt, f, w, h)
                fun ok(p: app.headsup.core.planner.ScreenPoint) = p.front && abs(p.x) < 20_000 && abs(p.y) < 20_000
                fun line(a: app.headsup.core.planner.ScreenPoint, b: app.headsup.core.planner.ScreenPoint, c: Color, width: Float) {
                    if (ok(a) && ok(b)) drawLine(c, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), strokeWidth = width)
                }
                fun label(text: String, p: app.headsup.core.planner.ScreenPoint, colour: Color, fontSize: Int) {
                    val l = measurer.measure(text, TextStyle(color = colour, fontSize = fontSize.sp))
                    drawText(l, topLeft = Offset(p.x.toFloat() - l.size.width / 2f, p.y.toFloat() - l.size.height / 2f))
                }

                // The horizon, and the points of the compass on it.
                var prev = at(0.0, 0.0)
                for (az in 4..360 step 4) {
                    val p = at(az.toDouble(), 0.0)
                    line(prev, p, Color.White, 2.dp.toPx())
                    prev = p
                }
                for ((name, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                    val p = at(az, 0.0)
                    if (ok(p)) label(name, app.headsup.core.planner.ScreenPoint(p.x, p.y - 22.dp.toPx(), true), Color.White, 16)
                }
                // The sun's path over the day; dimmer under the horizon.
                for (i in 0 until sunSamples.size - 1) {
                    val a = sunSamples[i]; val b = sunSamples[i + 1]
                    line(at(a.azimuth, a.altitude), at(b.azimuth, b.altitude), Gold.copy(alpha = if (a.altitude < 0 && b.altitude < 0) 0.35f else 1f), 3.dp.toPx())
                }
                // A dot for each hour of the day with its number.
                for ((hour, s) in hours) {
                    if (s.altitude < -2) continue
                    val p = at(s.azimuth, s.altitude)
                    if (!ok(p)) continue
                    val c = Offset(p.x.toFloat(), p.y.toFloat())
                    drawCircle(Color.White, 11.dp.toPx(), c)
                    drawCircle(Gold, 9.dp.toPx(), c)
                    label(hour.toString(), p, Color.Black, 11)
                }
                // At night: the Milky Way's band and its centre.
                if (Sky.sun(place, st.now).altitude < -6) {
                    val arc = Sky.milkyWayArc(place, st.now, 5)
                    for (i in arc.indices) {
                        val a = arc[i]; val b = arc[(i + 1) % arc.size]
                        line(at(a.azimuth, a.altitude), at(b.azimuth, b.altitude), Color(0xCC8FB4FF), 4.dp.toPx())
                    }
                    val gc = Sky.galacticCentre(place, st.now)
                    val p = at(gc.azimuth, gc.altitude)
                    if (ok(p)) {
                        drawCircle(Color(0xFFBFD3FF), 8.dp.toPx(), Offset(p.x.toFloat(), p.y.toFloat()))
                        label("Galactic centre", app.headsup.core.planner.ScreenPoint(p.x, p.y - 20.dp.toPx(), true), Color(0xFFBFD3FF), 12)
                    }
                }
                // The moon and the sun at the chosen hour (the sun ringed, as on the sky chart).
                val moon = Sky.moon(place, st.now).position
                at(moon.azimuth, moon.altitude).let { if (ok(it)) drawCircle(Color(0xFFEFEFEF), 9.dp.toPx(), Offset(it.x.toFloat(), it.y.toFloat())) }
                val sun = Sky.sun(place, st.now)
                val sunPoint = at(sun.azimuth, sun.altitude)
                if (ok(sunPoint) && sunPoint.x in 0.0..w && sunPoint.y in 0.0..h) {
                    val c = Offset(sunPoint.x.toFloat(), sunPoint.y.toFloat())
                    drawCircle(Gold, 16.dp.toPx(), c)
                    drawCircle(Color(0xFFF44336), 20.dp.toPx(), c, style = Stroke(width = 3.dp.toPx()))
                } else {
                    // Out of view: an arrow at the edge towards it.
                    val d = Ar.enu(Ar.magneticAzimuth(sun.azimuth, declination), sun.altitude)
                    val dx = rotation[0] * d[0] + rotation[3] * d[1] + rotation[6] * d[2]
                    val dy = rotation[1] * d[0] + rotation[4] * d[1] + rotation[7] * d[2]
                    val angle = atan2(-dy, dx).toFloat()
                    val centre = Offset(size.width / 2, size.height / 2)
                    val r = minOf(size.width, size.height) * 0.42f
                    val tip = centre + Offset(cos(angle) * r, sin(angle) * r)
                    val arrow = Path().apply {
                        moveTo(tip.x + cos(angle) * 16.dp.toPx(), tip.y + sin(angle) * 16.dp.toPx())
                        lineTo(tip.x + cos(angle + 2.5f) * 14.dp.toPx(), tip.y + sin(angle + 2.5f) * 14.dp.toPx())
                        lineTo(tip.x + cos(angle - 2.5f) * 14.dp.toPx(), tip.y + sin(angle - 2.5f) * 14.dp.toPx())
                        close()
                    }
                    drawPath(arrow, Gold)
                }
                // The middle of the picture.
                val mid = Offset(size.width / 2, size.height / 2)
                drawLine(Color.White, mid - Offset(12.dp.toPx(), 0f), mid + Offset(12.dp.toPx(), 0f), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, mid - Offset(0f, 12.dp.toPx()), mid + Offset(0f, 12.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }

            // What the phone looks at.
            LookReadout({ tick }, rotation, declination, st, hasSensor, Modifier.align(Alignment.TopStart).padding(12.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { Chip("My location", onLocate) }

            // The hour, the light the camera reads, and the settings that fit it.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val frame = Planner.frame(place, st.now, st.conditions, st.setup, st.measured)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("◀", { st.day = st.day.minusDays(1) })
                    Text("${hhmm(st.minute)} · ${st.day}", color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Chip("▶", { st.day = st.day.plusDays(1) })
                }
                DayBar(st)
                val r = reading
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (r == null) "Reading the light…" else "Camera reads EV ${one(r)}" + (if (st.measured != null) " · base in use" else ""),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    if (r != null) Chip("Use as base", { st.useReading(r) })
                    if (st.measuredEv != null) Chip("Clear", { st.clearReading() })
                }
                if (st.readingElsewhere) Text("The reading is from another place: not used.", color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelSmall)
                val e = frame.exposure
                Text(
                    "At ${hhmm(st.minute)}: EV ${one(frame.ev)} → ${Exposure.formatAperture(e.aperture)} · ${Exposure.formatShutter(e.shutter)} · ISO ${Exposure.snapIso(e.iso)} · ${e.nd.label}" +
                        (if (e.warning != null) "  (out of the camera's reach)" else ""),
                    color = if (e.warning != null) Color(0xFFFF8A80) else Color.White, style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Aim at the scene you will shoot, not at the sun. Settings: ${if (st.auto == AutoSetting.Iso) "ISO follows" else if (st.auto == AutoSetting.Shutter) "shutter follows" else "aperture follows"} (set in Calculator).",
                    color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun compass(az: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((az + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]

/** What the phone looks at and where the sun is; reads the sensor's count itself, so only this little part is redrawn as the phone turns. */
@Composable
private fun LookReadout(tick: () -> Int, rotation: DoubleArray, declination: Double, st: PlannerState, hasSensor: Boolean, modifier: Modifier) {
    tick() // follow the phone
    val look = Ar.cameraDirection(rotation)
    val trueAz = (look.azimuth + declination + 360.0) % 360.0
    val sun = Sky.sun(st.place, st.now)
    // The way the phone looks is the cone on the sun view's map. Not while pointing up or down, where the heading is not steady.
    SideEffect {
        if (abs(look.altitude) < 75.0 && abs(((trueAz - st.viewAz + 540.0) % 360.0) - 180.0) > 2.0) st.viewAz = trueAz.toFloat()
    }
    Column(modifier.background(Color(0x99000000)).padding(8.dp)) {
        Text("Looking ${trueAz.roundToInt()}° (${compass(trueAz)}), tilt ${look.altitude.roundToInt()}°", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text("Sun at ${hhmm(st.minute)}: ${sun.azimuth.roundToInt()}°, ${sun.altitude.roundToInt()}° up", color = Gold, style = MaterialTheme.typography.labelMedium)
        if (!hasSensor) Text("This phone has no orientation sensor.", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
