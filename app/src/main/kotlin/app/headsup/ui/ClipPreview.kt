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
import app.headsup.core.color.ColourMask
import app.headsup.core.color.ColourTransition
import app.headsup.core.color.MaskKind
import app.headsup.core.frame.Reframe
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.withTransform
import app.headsup.color.GradeTables
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Masks and their colour tables (Android 13+ preview only). */
    masks: List<ColourMask> = emptyList(),
    maskCubes: List<Array<Array<IntArray>>> = emptyList(),
    /** Previous clip's colour table and how long the clip blends in from it. */
    fromCube: Array<Array<IntArray>>? = null,
    transitionMs: Long = 0L,
    /** Mask being edited: its outline is drawn, and dragging on the picture moves it. */
    guideMask: ColourMask? = null,
    onMoveMask: ((Float, Float) -> Unit)? = null,
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
    /**
     * Position in file time while a cut handle is dragged: the whole file is loaded so the
     * picture can follow the handle, also outside the current cut.
     */
    cutScrub: State<Long?> = NO_SCRUB,
    /** Receives a function that grabs the current frame (before colour), e.g. for analysis. */
    onFrameSource: ((() -> Bitmap?) -> Unit)? = null,
    /** Zoom, position and straightening; [onReframe] set means a finger on the picture pans and pinches it. */
    reframe: Reframe = Reframe(),
    onReframe: ((Reframe) -> Unit)? = null,
    /** Rule-of-thirds grid over the picture. */
    showGrid: Boolean = false,
    /** Start playing as soon as the clip is loaded (continuing from the previous clip). */
    autoPlay: Boolean = false,
    onAutoPlayStarted: () -> Unit = {},
    /** Playback reached the end of the clip. */
    onEnded: () -> Unit = {},
    /** Position in the clip to jump to once (e.g. a highlight), then [onSeekDone]. */
    seekRequest: Long? = null,
    onSeekDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    // Android 13+: colours are applied by a GPU shader on the displayed picture, so slider changes
    // are immediate. Older versions fall back to a Media3 effect inside the player.
    val shaderPreview = Build.VERSION.SDK_INT >= 33
    val liveLut = remember { LiveColorLut() }
    val scope = rememberCoroutineScope()
    val scrubber = remember { Scrubber(exoPlayer, scope) }
    val scrubMs = cutScrub.value
    val scrubbing = scrubMs != null
    LaunchedEffect(clip.meta.uri, clip.trimStartMs, clip.trimEndMs, scrubbing) {
        if (!shaderPreview) exoPlayer.setVideoEffects(listOf<Effect>(liveLut))
        if (scrubbing) {
            exoPlayer.pause()
            exoPlayer.setMediaItem(MediaItem.fromUri(clip.meta.uri), cutScrub.value ?: 0L)
        } else {
            // Only the used part of the file plays; positions count from the cut.
            exoPlayer.setMediaItem(
                MediaItem.Builder()
                    .setUri(clip.meta.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.trimStartMs).setEndPositionMs(clip.trimEndMs).build(),
                    )
                    .build(),
            )
        }
        exoPlayer.prepare()
        if (autoPlay && !scrubbing) {
            exoPlayer.play()
            onAutoPlayStarted()
        }
    }
    val ended by rememberUpdatedState(onEnded)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && exoPlayer.playWhenReady) ended()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    LaunchedEffect(scrubMs) { scrubMs?.let(scrubber::seek) }
    // Declared after the media item is set, so it runs after it for a newly selected clip.
    LaunchedEffect(seekRequest, clip.id) {
        val target = seekRequest ?: return@LaunchedEffect
        exoPlayer.pause()
        exoPlayer.seekTo(target.coerceIn(0L, clip.durationMs))
        onSeekDone()
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
    LaunchedEffect(gradeCube, maskCubes, fromCube) {
        previewShader?.setTables(GradeTables(gradeCube, maskCubes, fromCube))
        tableVersion++
    }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val sample by rememberUpdatedState(onSample)
    val maskMover by rememberUpdatedState(onMoveMask)
    val reframer by rememberUpdatedState(onReframe)
    val currentReframe by rememberUpdatedState(reframe)
    LaunchedEffect(textureView) {
        val tv = textureView ?: return@LaunchedEffect
        onFrameSource?.invoke {
            val w = ANALYSIS_WIDTH
            tv.getBitmap(w, (w * tv.height / tv.width.coerceAtLeast(1)).coerceAtLeast(1))
        }
    }

    LaunchedEffect(histogramOn, gradeCube) {
        while (histogramOn) {
            val tv = textureView
            val frame = tv?.let { v -> v.getBitmap(160, (160 * v.height / v.width.coerceAtLeast(1)).coerceAtLeast(1)) }
            if (frame != null) histogram = withContext(Dispatchers.Default) { luminanceHistogram(frame, gradeCube) }
            delay(250)
        }
    }

    // Player position; in file time while a cut handle is dragged, otherwise within the cut.
    var playerMs by remember { mutableLongStateOf(0L) }
    val reportPosition by rememberUpdatedState(onPosition)
    val fileTime by rememberUpdatedState(scrubbing)
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(exoPlayer) {
        while (true) withFrameMillis {
            if (playerMs != exoPlayer.currentPosition) {
                playerMs = exoPlayer.currentPosition
                if (!fileTime) reportPosition(playerMs)
            }
            playing = exoPlayer.isPlaying
        }
    }
    // Position within the clip, as the dashboard and timeline count it (negative before the cut).
    val positionMs = if (scrubbing) playerMs - clip.trimStartMs else playerMs

    val sampler = remember(activity) { activity?.let { TelemetrySampler(it.track) } }
    val renderer = remember { DashboardRenderer(context.assets) }
    val currentStyle by rememberUpdatedState(style)
    val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.heightIn(max = maxHeight).aspectRatio(aspect, matchHeightConstraintsFirst = true).clipToBounds()) {
                // A TextureView (unlike PlayerView's default SurfaceView) is drawn by the UI, so the
                // colour shader can be applied to it.
                AndroidView(
                    factory = { TextureView(it).also { tv -> exoPlayer.setVideoTextureView(tv); textureView = tv } },
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        // Reading these here re-applies the shader when they change, without recomposing.
                        tableVersion
                        val frame = if (effects.grain > 0f) (positionMs / 33).toFloat() else 0f
                        val fromWeight = if (fromCube != null) ColourTransition.fromWeight(positionMs, transitionMs) else 0f
                        renderEffect = previewShader?.effect(effects, zebra, frame, size.width, size.height, masks, fromWeight)
                        // Reframing moves the graded picture, as in the export (colour first, then framing).
                        val t = reframeTransform(reframe, size.width, size.height)
                        scaleX = t.zoom
                        scaleY = t.zoom
                        rotationZ = reframe.angleDegrees
                        translationX = t.translation.x
                        translationY = t.translation.y
                    },
                )
                Canvas(
                    Modifier
                        .matchParentSize()
                        .then(
                            if (onReframe == null) {
                                Modifier
                            } else {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        val r = currentReframe
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val z = r.clamped(w, h).effectiveZoom(w, h)
                                        // A finger moving right shows more of the left: undo rotation and zoom.
                                        val a = Math.toRadians(r.angleDegrees.toDouble())
                                        val dx = (pan.x * kotlin.math.cos(a) + pan.y * kotlin.math.sin(a)).toFloat() / z
                                        val dy = (-pan.x * kotlin.math.sin(a) + pan.y * kotlin.math.cos(a)).toFloat() / z
                                        reframer?.invoke(
                                            r.copy(zoom = r.zoom * zoomChange, centerX = r.centerX - dx / w, centerY = r.centerY - dy / h),
                                        )
                                    }
                                }
                            },
                        )
                        .pointerInput(renderer) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // While a mask is edited, a finger on the picture moves it (widgets stay put).
                                if (reframer != null) return@awaitEachGesture
                                val moveMask = maskMover
                                if (moveMask != null) {
                                    // Masks live in the picture, so map the finger back through the framing.
                                    fun move(p: Offset) {
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val (sx, sy) = currentReframe.sourceOf(p.x, p.y, w, h)
                                        moveMask(sx / w, sy / h)
                                    }
                                    down.consume()
                                    move(down.position)
                                    drag(down.id) { change ->
                                        change.consume()
                                        move(change.position)
                                    }
                                    return@awaitEachGesture
                                }
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
                    guideMask?.let { m ->
                        val t = reframeTransform(reframe, size.width, size.height)
                        val o = Offset(size.width / 2f, size.height / 2f)
                        withTransform({
                            translate(t.translation.x, t.translation.y)
                            rotate(reframe.angleDegrees, o)
                            scale(t.zoom, t.zoom, o)
                        }) { drawMaskGuide(m) }
                    }
                    marker?.let { m ->
                        val c = Offset(m.x * size.width, m.y * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 14.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 14.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 2.dp.toPx(), center = c)
                    }
                    val utc = clip.telemetryUtc(positionMs)
                    val track = activity?.track
                    val frame = utc?.let { sampler?.frameAt(it) }
                    if (utc == null || track == null || frame == null) {
                        // No watch data here: the picture effects still show.
                        drawIntoCanvas { renderer.drawBars(it.nativeCanvas, size.width, size.height, effects) }
                        return@Canvas
                    }
                    val zone = track.utcOffsetSeconds
                        ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
                    val data = DashboardData(track, frame, utc, zone)
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, style, data, effects) }
                }
                if (showGrid) Canvas(Modifier.matchParentSize()) { drawThirds() }
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
                "  ${timecode(if (scrubbing) playerMs else positionMs, clip.meta.frameRate)}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            )
            // The thumb follows the finger; the picture follows as fast as the decoder can seek.
            var dragMs by remember { mutableStateOf<Float?>(null) }
            val endMs = clip.durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = (dragMs ?: positionMs.toFloat()).coerceIn(0f, endMs),
                onValueChange = { dragMs = it; scrubber.seek(it.toLong()) },
                onValueChangeFinished = { dragMs = null },
                enabled = !scrubbing,
                valueRange = 0f..endMs,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            ToggleDot("H", histogramOn) { histogramOn = !histogramOn }
            Spacer(Modifier.width(4.dp))
            if (Build.VERSION.SDK_INT >= 33) ToggleDot("Z", zebra) { zebra = !zebra }
        }
    }
}

private const val SAMPLE_WIDTH = 192

private class ReframeTransform(val zoom: Float, val translation: Offset)

/**
 * Layer transform (around the centre) that shows the picture as [reframe] describes: the source
 * point at the reframe centre lands in the middle of the frame.
 */
private fun reframeTransform(reframe: Reframe, width: Float, height: Float): ReframeTransform {
    if (reframe.isIdentity || width <= 0f || height <= 0f) return ReframeTransform(1f, Offset.Zero)
    val r = reframe.clamped(width, height)
    val z = r.effectiveZoom(width, height)
    val a = Math.toRadians(r.angleDegrees.toDouble())
    // T = z·R(θ)·(O − C), clockwise rotation with y down.
    val vx = width / 2f - r.centerX * width
    val vy = height / 2f - r.centerY * height
    val tx = z * (vx * kotlin.math.cos(a) - vy * kotlin.math.sin(a)).toFloat()
    val ty = z * (vx * kotlin.math.sin(a) + vy * kotlin.math.cos(a)).toFloat()
    return ReframeTransform(z, Offset(tx, ty))
}

/** Rule-of-thirds lines, with a faint shadow so they show on bright and dark pictures. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThirds() {
    for (i in 1..2) {
        val x = size.width * i / 3f
        val y = size.height * i / 3f
        for ((colour, width) in listOf(Color.Black.copy(alpha = 0.35f) to 2.5f, Color.White.copy(alpha = 0.75f) to 1f)) {
            drawLine(colour, Offset(x, 0f), Offset(x, size.height), strokeWidth = width * density)
            drawLine(colour, Offset(0f, y), Offset(size.width, y), strokeWidth = width * density)
        }
    }
}
private const val ANALYSIS_WIDTH = 256

/** The mask's edge (solid) and the ends of its soft blend (dashed), as in DaVinci's window controls. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaskGuide(mask: ColourMask) {
    val centre = Offset(mask.centerX * size.width, mask.centerY * size.height)
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density))
    val solid = Color.White.copy(alpha = 0.9f)
    val faint = Color.White.copy(alpha = 0.6f)
    rotate(mask.angleDegrees, pivot = centre) {
        when (mask.kind) {
            MaskKind.LINEAR -> {
                val reach = size.width + size.height
                for ((offset, dashed) in listOf(0f to false, -mask.softness / 2f to true, mask.softness / 2f to true)) {
                    val y = centre.y + offset * size.height
                    drawLine(
                        if (dashed) faint else solid, Offset(centre.x - reach, y), Offset(centre.x + reach, y),
                        strokeWidth = (if (dashed) 1f else 1.5f) * density, pathEffect = if (dashed) dash else null,
                    )
                }
            }
            MaskKind.ELLIPSE -> {
                for ((scale, dashed) in listOf(1f to false, 1f - mask.softness / 2f to true, 1f + mask.softness / 2f to true)) {
                    if (scale <= 0f) continue
                    val rx = mask.sizeX / 2f * size.height * scale
                    val ry = mask.sizeY / 2f * size.height * scale
                    drawOval(
                        if (dashed) faint else solid, topLeft = Offset(centre.x - rx, centre.y - ry), size = Size(2 * rx, 2 * ry),
                        style = Stroke((if (dashed) 1f else 1.5f) * density, pathEffect = if (dashed) dash else null),
                    )
                }
            }
        }
    }
    drawCircle(solid, radius = 4f * density, center = centre)
}

private val NO_SCRUB: State<Long?> = mutableStateOf(null)

/**
 * Seeking for a dragged finger: one seek at a time, always to the latest position, so the
 * picture keeps up instead of the player working through a queue of stale seeks.
 */
private class Scrubber(private val player: ExoPlayer, private val scope: CoroutineScope) {
    private var target = 0L
    private var running = false

    fun seek(positionMs: Long) {
        target = positionMs.coerceAtLeast(0L)
        if (running) return
        running = true
        scope.launch {
            try {
                var done = -1L
                while (target != done) {
                    done = target
                    player.seekTo(done)
                    // Until the frame at the new position is ready (bounded, in case it never is).
                    withTimeoutOrNull(400) {
                        while (player.playbackState == Player.STATE_BUFFERING) delay(8)
                    }
                }
            } finally {
                running = false
            }
        }
    }
}

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
