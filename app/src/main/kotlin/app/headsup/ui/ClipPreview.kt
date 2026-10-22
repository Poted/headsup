package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import app.headsup.color.LiveColorLut
import androidx.media3.exoplayer.ExoPlayer
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import app.headsup.core.color.LutBaker
import app.headsup.render.EffectSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.view.TextureView
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.graphicsLayer
import app.headsup.color.GradePreviewShader
import app.headsup.core.track.TelemetrySampler
import app.headsup.render.DashboardData
import app.headsup.render.DashboardRenderer
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.Layouts
import app.headsup.render.Placement
import app.headsup.render.Widget
import java.time.Instant
import java.time.ZoneId

/**
 * Plays the clip with the dashboard drawn on top by the same renderer the export uses.
 * Widgets can be dragged with a finger; [onMoveWidget] receives the new placement while
 * dragging and [onMoveFinished] once the finger lifts.
 */
@Composable
fun ClipPreview(
    clip: ClipState,
    activity: LoadedActivity?,
    style: DashboardStyle,
    /** Baked colour table to preview, or null for untouched colours. */
    gradeCube: Array<Array<IntArray>>?,
    vignette: Float,
    effects: EffectSettings,
    maxHeight: Dp,
    onMoveWidget: (Widget, Placement) -> Unit,
    onMoveFinished: () -> Unit,
    /** When set, tapping the picture (away from widgets) grabs the frame for the vectorscope. */
    onSample: ((Bitmap, Float, Float) -> Unit)? = null,
    /** Point last measured, 0..1 of the frame, drawn as a target. */
    marker: Offset? = null,
    /** Playhead position within the (trimmed) clip, reported as it changes. */
    onPosition: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    // Android 13+: colours are applied by a GPU shader on the displayed picture, so slider changes
    // are immediate. Older versions fall back to a Media3 effect inside the player.
    val shaderPreview = Build.VERSION.SDK_INT >= 33
    val liveLut = remember { LiveColorLut() }
    LaunchedEffect(clip.meta.uri, clip.trimStartMs, clip.trimEndMs) {
        if (!shaderPreview) exoPlayer.setVideoEffects(listOf<Effect>(liveLut))
        // Only the used part of the file plays; positions count from the cut.
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(clip.meta.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.trimStartMs).setEndPositionMs(clip.trimEndMs).build(),
                )
                .build(),
        )
        exoPlayer.prepare()
    }
    var nudge by remember { mutableStateOf(1L) }
    LaunchedEffect(gradeCube) {
        if (shaderPreview) return@LaunchedEffect
        liveLut.update(gradeCube)
        // A paused player only draws when a new frame is decoded and ignores a seek to its current
        // position, so step 1 ms back and forth to re-render the picture.
        if (!exoPlayer.isPlaying) {
            exoPlayer.seekTo((exoPlayer.currentPosition + nudge).coerceAtLeast(0L))
            nudge = -nudge
        }
    }
    // Zebra: diagonal stripes over clipped highlights, as on a camera monitor.
    var zebra by remember { mutableStateOf(false) }
    var histogramOn by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf<FloatArray?>(null) }
    val previewShader = remember { if (Build.VERSION.SDK_INT >= 33) GradePreviewShader() else null }
    var tableVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(gradeCube) {
        previewShader?.setTable(gradeCube)
        tableVersion++
    }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val sample by rememberUpdatedState(onSample)

    LaunchedEffect(histogramOn, gradeCube) {
        while (histogramOn) {
            val tv = textureView
            val frame = tv?.let { v -> v.getBitmap(160, (160 * v.height / v.width.coerceAtLeast(1)).coerceAtLeast(1)) }
            if (frame != null) histogram = withContext(Dispatchers.Default) { luminanceHistogram(frame, gradeCube) }
            delay(250)
        }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    val reportPosition by rememberUpdatedState(onPosition)
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(exoPlayer) {
        while (true) withFrameMillis {
            if (positionMs != exoPlayer.currentPosition) {
                positionMs = exoPlayer.currentPosition
                reportPosition(positionMs)
            }
            playing = exoPlayer.isPlaying
        }
    }

    val sampler = remember(activity) { activity?.let { TelemetrySampler(it.track) } }
    val renderer = remember { DashboardRenderer(context.assets) }
    val currentStyle by rememberUpdatedState(style)
    val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.heightIn(max = maxHeight).aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
                // A TextureView (unlike PlayerView's default SurfaceView) is drawn by the UI, so the
                // colour shader can be applied to it.
                AndroidView(
                    factory = { TextureView(it).also { tv -> exoPlayer.setVideoTextureView(tv); textureView = tv } },
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        // Reading these here re-applies the shader when they change, without recomposing.
                        tableVersion
                        val frame = if (effects.grain > 0f) (positionMs / 33).toFloat() else 0f
                        renderEffect = previewShader?.effect(effects, zebra, frame, minOf(size.width, size.height))
                    },
                )
                Canvas(
                    Modifier
                        .matchParentSize()
                        .pointerInput(renderer) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val widget = renderer.hitTest(down.position.x, down.position.y, slop = 12.dp.toPx())
                                if (widget == null) {
                                    // A tap elsewhere measures colour when the skin tool is open.
                                    val onTap = sample ?: return@awaitEachGesture
                                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                    up.consume()
                                    val tv = textureView ?: return@awaitEachGesture
                                    val w = SAMPLE_WIDTH
                                    val h = (SAMPLE_WIDTH * size.height / size.width.coerceAtLeast(1)).coerceAtLeast(1)
                                    tv.getBitmap(w, h)?.let { onTap(it, up.position.x / size.width, up.position.y / size.height) }
                                    return@awaitEachGesture
                                }
                                val start = renderer.boxOf(widget) ?: return@awaitEachGesture
                                down.consume()
                                var dx = 0f
                                var dy = 0f
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    dx += delta.x
                                    dy += delta.y
                                    change.consume()
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val placement = Layouts.placementFor(
                                        start.left + dx, start.top + dy, start.width(), start.height(),
                                        w, h, DashboardRenderer.unit(w, h, currentStyle),
                                    )
                                    onMoveWidget(widget, placement)
                                }
                                onMoveFinished()
                            }
                        },
                ) {
                    drawIntoCanvas { renderer.drawVignette(it.nativeCanvas, size.width, size.height, vignette) }
                    marker?.let { m ->
                        val c = Offset(m.x * size.width, m.y * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 14.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 14.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 2.dp.toPx(), center = c)
                    }
                    val utc = clip.telemetryUtc(positionMs) ?: return@Canvas
                    val track = activity?.track ?: return@Canvas
                    val frame = sampler?.frameAt(utc) ?: return@Canvas
                    val zone = track.utcOffsetSeconds
                        ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
                    val data = DashboardData(track, frame, utc, zone)
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, style, data, effects) }
                }
                histogram?.takeIf { histogramOn }?.let { bins ->
                    Histogram(bins, Modifier.align(Alignment.BottomStart).padding(10.dp).size(width = 128.dp, height = 56.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(HeadsupColors.CardGradient).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(HeadsupColors.Accent)
                    .clickable { if (playing) exoPlayer.pause() else exoPlayer.play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) HeadsupIcons.Pause else HeadsupIcons.Play, contentDescription = if (playing) "Pause" else "Play", tint = HeadsupColors.OnAccent)
            }
            Text(
                "  ${timecode(positionMs, clip.meta.frameRate)}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { exoPlayer.seekTo(it.toLong()) },
                valueRange = 0f..clip.durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            ToggleDot("H", histogramOn) { histogramOn = !histogramOn }
            Spacer(Modifier.width(4.dp))
            if (Build.VERSION.SDK_INT >= 33) ToggleDot("Z", zebra) { zebra = !zebra }
        }
    }
}

private const val SAMPLE_WIDTH = 192

@Composable
private fun ToggleDot(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (on) HeadsupColors.Accent else Color.Transparent)
            .border(1.dp, if (on) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) HeadsupColors.OnAccent else HeadsupColors.TextMuted)
    }
}

/** Luminance histogram of the graded picture, drawn over the preview's corner. */
@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))) {
        val path = androidx.compose.ui.graphics.Path()
        val step = size.width / (bins.size - 1)
        path.moveTo(0f, size.height)
        bins.forEachIndexed { i, v -> path.lineTo(i * step, size.height - v * size.height * 0.92f) }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, Color.White.copy(alpha = 0.55f))
        // Clipping warnings at both ends.
        if (bins.first() > 0.6f) drawRect(HeadsupColors.Accent, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
        if (bins.last() > 0.6f) drawRect(HeadsupColors.Accent, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
    }
}

/** 64-bin luminance histogram of a small frame grab after the colour table, scaled to its peak. */
private fun luminanceHistogram(frame: Bitmap, cube: Array<Array<IntArray>>?): FloatArray {
    val pixels = IntArray(frame.width * frame.height)
    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    frame.recycle()
    val bins = FloatArray(64)
    val rgb = FloatArray(3)
    for (p in pixels) {
        rgb[0] = ((p shr 16) and 0xFF) / 255f
        rgb[1] = ((p shr 8) and 0xFF) / 255f
        rgb[2] = (p and 0xFF) / 255f
        if (cube != null) LutBaker.sampleBaked(cube, rgb[0], rgb[1], rgb[2], rgb)
        val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        bins[(y * 63).toInt().coerceIn(0, 63)] += 1f
    }
    val peak = bins.max().coerceAtLeast(1f)
    // Square root keeps small populations visible next to a dominant tone.
    for (i in bins.indices) bins[i] = kotlin.math.sqrt(bins[i] / peak)
    return bins
}

/** Camera-style timecode HH:MM:SS:FF. */
private fun timecode(ms: Long, frameRate: Float?): String {
    val fps = (frameRate ?: 30f).let { kotlin.math.round(it).toInt().coerceAtLeast(1) }
    val totalSeconds = ms / 1000
    val frames = ((ms % 1000) * fps / 1000).toInt()
    return String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60, frames)
}
