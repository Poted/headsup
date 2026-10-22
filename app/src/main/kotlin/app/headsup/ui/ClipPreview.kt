package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.SingleColorLut
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    onMoveWidget: (Widget, Placement) -> Unit,
    onMoveFinished: () -> Unit,
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    val lutEffect = remember(gradeCube) { gradeCube?.let { SingleColorLut.createFromCube(it) } }
    var loadedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(clip.meta.uri, lutEffect) {
        // Effects are picked up when the item is prepared, so re-prepare at the same position.
        val position = if (loadedUri == clip.meta.uri) exoPlayer.currentPosition else 0L
        loadedUri = clip.meta.uri
        val wasPlaying = exoPlayer.isPlaying
        exoPlayer.setVideoEffects(listOfNotNull<Effect>(lutEffect))
        exoPlayer.setMediaItem(MediaItem.fromUri(clip.meta.uri), position)
        exoPlayer.prepare()
        if (wasPlaying) exoPlayer.play()
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(exoPlayer) {
        while (true) withFrameMillis {
            positionMs = exoPlayer.currentPosition
            playing = exoPlayer.isPlaying
        }
    }

    val sampler = remember(activity) { activity?.let { TelemetrySampler(it.track) } }
    val renderer = remember { DashboardRenderer(context.assets) }
    val currentStyle by rememberUpdatedState(style)
    val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f

    Column {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.heightIn(max = 560.dp).aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
                AndroidView(
                    factory = { PlayerView(it).apply { player = exoPlayer; useController = false } },
                    modifier = Modifier.matchParentSize(),
                )
                Canvas(
                    Modifier
                        .matchParentSize()
                        .pointerInput(renderer) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val widget = renderer.hitTest(down.position.x, down.position.y, slop = 12.dp.toPx())
                                    ?: return@awaitEachGesture
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
                    val utc = clip.telemetryUtc(positionMs) ?: return@Canvas
                    val track = activity?.track ?: return@Canvas
                    val frame = sampler?.frameAt(utc) ?: return@Canvas
                    val zone = track.utcOffsetSeconds
                        ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
                    val data = DashboardData(track, frame, utc, zone)
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, style, data) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (playing) exoPlayer.pause() else exoPlayer.play() }) {
                Text(if (playing) "Pause" else "Play")
            }
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { exoPlayer.seekTo(it.toLong()) },
                valueRange = 0f..clip.meta.durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                " ${Formats.duration(positionMs)} / ${Formats.duration(clip.meta.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Drag widgets on the picture to move them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
