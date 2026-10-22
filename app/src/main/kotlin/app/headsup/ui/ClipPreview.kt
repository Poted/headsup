package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.headsup.core.track.TelemetrySampler
import app.headsup.render.DashboardData
import app.headsup.render.DashboardRenderer
import app.headsup.render.DashboardStyle
import java.time.Instant
import java.time.ZoneId

/** Plays the clip with the dashboard drawn on top by the same renderer the export uses. */
@Composable
fun ClipPreview(clip: ClipState, activity: LoadedActivity?, style: DashboardStyle) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    LaunchedEffect(clip.meta.uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(clip.meta.uri))
        exoPlayer.prepare()
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exoPlayer) {
        while (true) withFrameMillis { positionMs = exoPlayer.currentPosition }
    }

    val sampler = remember(activity) { activity?.let { TelemetrySampler(it.track) } }
    val renderer = remember { DashboardRenderer() }
    val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(max = 560.dp).aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
            AndroidView(
                factory = { PlayerView(it).apply { player = exoPlayer } },
                modifier = Modifier.matchParentSize(),
            )
            Canvas(Modifier.matchParentSize()) {
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
}
