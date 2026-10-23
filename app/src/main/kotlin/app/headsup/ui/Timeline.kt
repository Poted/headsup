package app.headsup.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.core.sync.Confidence
import app.headsup.export.AudioClip
import app.headsup.render.Formats
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The project's timeline, as in a video editor: the playhead stays in the middle and the
 * timeline moves under it. Drag to scrub (it keeps gliding after a flick), pinch to zoom. The
 * video track shows the clips as frames; the audio tracks under it the sounds as waveforms, with
 * their beat grid and accents.
 *
 * Tap a clip or a sound to select it. A selected clip has handles at its ends to trim it (the
 * clips after it follow); a selected sound can be dragged (also to another track), trimmed at its
 * handles, faded with the dots on its top corners, or slid inside ([slip]). Long-press a clip for
 * its menu (move, delete, group). With [maskKeys] set, the selected clip shows the keys of the
 * mask being edited.
 */
@Composable
fun Timeline(
    state: UiState,
    vm: HeadsupViewModel,
    videoSelected: Boolean,
    slip: Boolean,
    /** Where the playhead is on the timeline (read while drawing, so playback redraws only this). */
    playheadMs: () -> Long,
    /** File times of the selected mask's keys in the selected clip. */
    maskKeys: List<Long>,
    /** Timeline position while the timeline is dragged; null when the finger (and the glide) ends. */
    onScrub: (Long?) -> Unit,
    /** File time of a clip's edge while it is trimmed, for the preview; null when done. */
    onTrimPreview: (Long?) -> Unit,
    onSelectVideo: (Int) -> Unit,
    onSelectAudio: (Long) -> Unit,
    onDeselect: () -> Unit,
    onAdd: () -> Unit,
    /** Actions for the selection, shown on the right above the tracks. */
    actions: @Composable RowScope.() -> Unit,
) {
    val clips = state.clips
    val audio = state.audioClips
    val lengths = clips.map { it.timelineDurationMs }
    val starts = lengths.runningFold(0L) { a, b -> a + b }
    val videoEnd = starts.last()
    // Sounds may run past the video's end; zooming out reaches them.
    val contentEnd = maxOf(videoEnd, audio.maxOfOrNull { it.piece.endOnTimelineMs } ?: 0L)
    val lanes = (audio.maxOfOrNull { it.lane } ?: -1) + 2

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filmstrip = remember { Filmstrip(context, scope) }
    DisposableEffect(filmstrip) { onDispose { filmstrip.release() } }
    val text = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val localDensity = LocalDensity.current

    var widthPx by remember { mutableFloatStateOf(0f) }
    // Zoom in pixels per millisecond; 0 until the width is known, then the project fits.
    var zoom by remember { mutableFloatStateOf(0f) }
    fun fitZoom(spanMs: Long, share: Float) = (widthPx * share / spanMs.coerceAtLeast(500L)).coerceIn(minZoom(widthPx, contentEnd), MAX_ZOOM_DP * localDensity.density)
    // While a clip's start is trimmed, it is drawn moved by this much; the rest ripples on release.
    var ghost by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var menu by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    var fling by remember { mutableStateOf<Job?>(null) }

    val clipsNow by rememberUpdatedState(clips)
    val audioNow by rememberUpdatedState(audio)
    val startsNow by rememberUpdatedState(starts)
    val selectedNow by rememberUpdatedState(state.selectedClip)
    val videoSelectedNow by rememberUpdatedState(videoSelected)
    val audioSelectedNow by rememberUpdatedState(state.selectedAudio)
    val slipNow by rememberUpdatedState(slip)
    val playhead by rememberUpdatedState(playheadMs)
    val scrub by rememberUpdatedState(onScrub)
    val trimPreview by rememberUpdatedState(onTrimPreview)
    val selectVideo by rememberUpdatedState(onSelectVideo)
    val selectAudio by rememberUpdatedState(onSelectAudio)
    val deselect by rememberUpdatedState(onDeselect)
    val add by rememberUpdatedState(onAdd)
    val lanesNow by rememberUpdatedState(lanes)
    val videoEndNow by rememberUpdatedState(videoEnd)
    val contentEndNow by rememberUpdatedState(contentEnd)

    val accent = HeadsupColors.Accent
    val muted = HeadsupColors.TextMuted
    val laneColour = HeadsupColors.Surface
    val clipColour = HeadsupColors.SurfaceHigh

    Column {
        // Scrolls sideways when the selection has many actions.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Fit the whole project, or the clip under the playhead (for masks, colour and cuts).
            SmallAction("Project", { if (widthPx > 0) zoom = fitZoom(videoEnd, 0.9f) })
            SmallAction("Clip", {
                val i = state.selectedClip ?: return@SmallAction
                if (widthPx > 0) zoom = fitZoom(lengths.getOrElse(i) { 1000L }, 0.8f)
            })
            Spacer(Modifier.width(16.dp))
            actions()
        }
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(RULER + VIDEO + GAP + LANE * lanes + 4.dp)
                    .onSizeChanged {
                        widthPx = it.width.toFloat()
                        if (zoom == 0f) zoom = fitZoom(videoEnd, 0.9f)
                    }
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        val longPress = viewConfiguration.longPressTimeoutMillis
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fling?.cancel()
                            val g = Geometry(
                                width = size.width.toFloat(), t = playhead(), zoom = zoom, starts = startsNow, clips = clipsNow,
                                audio = audioNow, lanes = lanesNow, d = this,
                            )
                            val hit = g.hit(down.position, selectedNow.takeIf { videoSelectedNow }, audioSelectedNow)
                            val t0 = System.currentTimeMillis()
                            var held = false
                            var start: PointerInputChange? = null
                            var pinch = false
                            // A tap, a long press, a drag or a pinch?
                            while (start == null && !pinch) {
                                val remaining = longPress - (System.currentTimeMillis() - t0)
                                val event = if (held || remaining <= 0) awaitPointerEvent() else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                if (event == null) {
                                    held = true
                                    when (hit) {
                                        is Hit.Clip -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectVideo(hit.index)
                                            menu = hit.index to down.position.x
                                        }
                                        is Hit.Sound -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectAudio(hit.id)
                                        }
                                        else -> Unit
                                    }
                                    continue
                                }
                                if (event.changes.count { it.pressed } >= 2) {
                                    pinch = true
                                    break
                                }
                                val c = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                if (!c.pressed) {
                                    if (!held) when (hit) {
                                        is Hit.Clip -> selectVideo(hit.index)
                                        is Hit.Sound -> selectAudio(hit.id)
                                        Hit.Add -> add()
                                        Hit.Empty -> deselect()
                                        else -> Unit
                                    }
                                    return@awaitEachGesture
                                }
                                if ((c.position - down.position).getDistance() > slop) start = c
                            }
                            if (pinch) {
                                var previous = -1f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size < 2) break
                                    val d = (pressed[0].position - pressed[1].position).getDistance()
                                    if (previous > 0f && d > 0f) zoom = (zoom * d / previous).coerceIn(minZoom(g.width, contentEndNow), MAX_ZOOM_DP * density)
                                    previous = d
                                    event.changes.forEach { it.consume() }
                                }
                                return@awaitEachGesture
                            }
                            val first = start ?: return@awaitEachGesture
                            val mode = when (hit) {
                                is Hit.ClipStart -> Mode.TRIM_CLIP_START
                                is Hit.ClipEnd -> Mode.TRIM_CLIP_END
                                is Hit.SoundStart -> Mode.TRIM_SOUND_START
                                is Hit.SoundEnd -> Mode.TRIM_SOUND_END
                                is Hit.FadeIn -> Mode.FADE_IN
                                is Hit.FadeOut -> Mode.FADE_OUT
                                is Hit.Sound -> if (hit.id == audioSelectedNow || held) (if (slipNow) Mode.SLIP else Mode.MOVE) else Mode.SCROLL
                                else -> if (held) Mode.NONE else Mode.SCROLL
                            }
                            val zoomPx = g.zoom
                            val t = g.t
                            val tracker = VelocityTracker()
                            tracker.addPosition(first.uptimeMillis, first.position)
                            var dx = first.position.x - down.position.x
                            var y = first.position.y
                            // The edited clip or sound as it was when the drag began.
                            val clipIndex = (hit as? Hit.ClipStart)?.index ?: (hit as? Hit.ClipEnd)?.index
                            val clip0 = clipIndex?.let { clipsNow.getOrNull(it) }
                            val soundId = when (hit) {
                                is Hit.Sound -> hit.id
                                is Hit.SoundStart -> hit.id
                                is Hit.SoundEnd -> hit.id
                                is Hit.FadeIn -> hit.id
                                is Hit.FadeOut -> hit.id
                                else -> null
                            }
                            val sound0 = soundId?.let { id -> audioNow.firstOrNull { it.id == id } }
                            val others = audioNow.filter { it.id != soundId }
                            val snaps = startsNow + others.flatMap { listOf(it.piece.startOnTimelineMs, it.piece.endOnTimelineMs) } + t
                            var leftTrim: Long? = null
                            drag(first.id) { c ->
                                dx += c.positionChange().x
                                y = c.position.y
                                tracker.addPosition(c.uptimeMillis, c.position)
                                c.consume()
                                val d = (dx / zoomPx).roundToLong()
                                val snapMs = 10.dp.toPx() / zoomPx
                                fun snapped(ms: Long) = snaps.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) < snapMs } ?: ms
                                fun free(lane: Int, from: Long, to: Long) =
                                    others.none { it.lane == lane && it.piece.startOnTimelineMs < to && it.piece.endOnTimelineMs > from }
                                when (mode) {
                                    Mode.SCROLL -> scrub((t - d).coerceIn(0L, videoEndNow))
                                    Mode.TRIM_CLIP_END -> {
                                        val c0 = clip0 ?: return@drag
                                        val i = clipIndex ?: return@drag
                                        val length = (c0.timelineDurationMs + d).coerceAtLeast(MIN_MS)
                                        if (c0.meta.isPhoto) {
                                            vm.setTrim(i, 0L, length)
                                        } else {
                                            val end = (c0.trimStartMs + (length * c0.speed).toLong()).coerceAtMost(c0.meta.durationMs)
                                            vm.setTrim(i, c0.trimStartMs, end)
                                            trimPreview(end)
                                        }
                                    }
                                    Mode.TRIM_CLIP_START -> {
                                        val c0 = clip0 ?: return@drag
                                        val i = clipIndex ?: return@drag
                                        if (c0.meta.isPhoto) {
                                            val length = (c0.timelineDurationMs - d).coerceIn(MIN_MS, c0.meta.durationMs)
                                            ghost = i to (c0.timelineDurationMs - length)
                                            leftTrim = length
                                        } else {
                                            val newStart = (c0.trimStartMs + (d * c0.speed).toLong()).coerceIn(0L, c0.trimEndMs - (MIN_MS * c0.speed).toLong())
                                            ghost = i to ((newStart - c0.trimStartMs) / c0.speed).toLong()
                                            leftTrim = newStart
                                            trimPreview(newStart)
                                        }
                                    }
                                    Mode.MOVE -> {
                                        val s0 = sound0 ?: return@drag
                                        val p = s0.piece
                                        var startMs = (p.startOnTimelineMs + d).coerceAtLeast(0L)
                                        val a = snapped(startMs)
                                        val b = snapped(startMs + p.lengthMs) - p.lengthMs
                                        startMs = if (a != startMs) a else if (b != startMs) b.coerceAtLeast(0L) else startMs
                                        val toLane = if (y >= g.lanesTop) g.laneAt(y) else s0.lane
                                        val lane = if (free(toLane, startMs, startMs + p.lengthMs)) toLane else s0.lane
                                        if (free(lane, startMs, startMs + p.lengthMs)) vm.updateAudio(s0.id) { s0.copy(lane = lane, startOnTimelineMs = startMs) }
                                    }
                                    Mode.SLIP -> {
                                        val s0 = sound0 ?: return@drag
                                        vm.updateAudio(s0.id) { s0.with(s0.piece.slip(-d)) }
                                    }
                                    Mode.TRIM_SOUND_START -> {
                                        val s0 = sound0 ?: return@drag
                                        val p = s0.piece
                                        val moved = p.trimStart(snapped(p.startOnTimelineMs + d) - p.startOnTimelineMs)
                                        if (free(s0.lane, moved.startOnTimelineMs, moved.endOnTimelineMs)) vm.updateAudio(s0.id) { s0.with(moved) }
                                    }
                                    Mode.TRIM_SOUND_END -> {
                                        val s0 = sound0 ?: return@drag
                                        val p = s0.piece
                                        val moved = p.trimEnd(snapped(p.endOnTimelineMs + d) - p.endOnTimelineMs)
                                        if (free(s0.lane, moved.startOnTimelineMs, moved.endOnTimelineMs)) vm.updateAudio(s0.id) { s0.with(moved) }
                                    }
                                    Mode.FADE_IN -> {
                                        val s0 = sound0 ?: return@drag
                                        val p = s0.piece
                                        vm.updateAudio(s0.id) { s0.copy(fadeInMs = (p.fadeIn + d).coerceIn(0L, p.lengthMs - p.fadeOut)) }
                                    }
                                    Mode.FADE_OUT -> {
                                        val s0 = sound0 ?: return@drag
                                        val p = s0.piece
                                        vm.updateAudio(s0.id) { s0.copy(fadeOutMs = (p.fadeOut - d).coerceIn(0L, p.lengthMs - p.fadeIn)) }
                                    }
                                    Mode.NONE -> Unit
                                }
                            }
                            when (mode) {
                                Mode.SCROLL -> {
                                    // Keeps gliding after a flick, slowing down.
                                    val v = -tracker.calculateVelocity().x / zoomPx * 1000f
                                    val from = (t - (dx / zoomPx).roundToLong()).coerceIn(0L, videoEndNow)
                                    if (abs(v) < 200f) {
                                        scrub(null)
                                    } else {
                                        fling = scope.launch {
                                            val anim = Animatable(from.toFloat())
                                            anim.updateBounds(0f, videoEndNow.toFloat())
                                            try {
                                                anim.animateDecay(v, exponentialDecay(frictionMultiplier = 2f)) { scrub(value.toLong()) }
                                            } finally {
                                                scrub(null)
                                            }
                                        }
                                    }
                                }
                                Mode.TRIM_CLIP_END -> trimPreview(null)
                                Mode.TRIM_CLIP_START -> {
                                    val c0 = clip0
                                    val value = leftTrim
                                    if (c0 != null && clipIndex != null && value != null) {
                                        if (c0.meta.isPhoto) vm.setTrim(clipIndex, 0L, value) else vm.setTrim(clipIndex, value, c0.trimEndMs)
                                    }
                                    ghost = null
                                    trimPreview(null)
                                }
                                else -> Unit
                            }
                        }
                    },
            ) {
                val tNow = playhead()
                filmstrip.version.intValue
                val g = Geometry(size.width, tNow, zoom.takeIf { it > 0f } ?: 0.05f, starts, clips, audio, lanes, this)
                drawRuler(g, muted, text)
                drawVideo(g, state, ghost, filmstrip, text, maskKeys, videoSelected)
                drawAudio(g, state, text, accent, laneColour)
                // Strong accents of the selected sound run up through the clips, for cutting on them.
                state.audioClips.firstOrNull { it.id == state.selectedAudio }?.let { s ->
                    val p = s.piece
                    state.audioAnalysis[s.uri]?.rhythm?.accents?.filter { it.strength > 0.6f }?.forEach { a ->
                        val tl = p.startOnTimelineMs + (a.ms - p.sourceStartMs)
                        if (tl in p.startOnTimelineMs until p.endOnTimelineMs) {
                            val x = g.x(tl)
                            drawLine(Color.White.copy(alpha = 0.25f), Offset(x, g.videoTop), Offset(x, g.videoTop + g.videoH), strokeWidth = 1f)
                        }
                    }
                }
                // Past the end of the video nothing is exported.
                val endX = g.x(videoEnd)
                if (endX < size.width) drawRect(Color.Black.copy(alpha = 0.35f), Offset(endX.coerceAtLeast(0f), g.lanesTop), Size(size.width - endX.coerceAtLeast(0f), size.height - g.lanesTop))
                // The playhead.
                val mid = size.width / 2f
                drawLine(Color.White, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2.dp.toPx())
                drawPath(Path().apply { moveTo(mid - 6.dp.toPx(), 0f); lineTo(mid + 6.dp.toPx(), 0f); lineTo(mid, 7.dp.toPx()); close() }, Color.White)
            }
            // Long-press menu of a clip.
            menu?.let { (index, x) ->
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }, offset = DpOffset(with(localDensity) { x.toDp() }, 0.dp)) {
                    val clip = clips.getOrNull(index)
                    if (index > 0) DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = null; vm.moveClip(index, -1) })
                    if (index < clips.lastIndex) DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = null; vm.moveClip(index, 1) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = null; vm.removeClip(index) })
                    HorizontalDivider()
                    // A clip is in at most one group, so picking another one moves it.
                    for (gr in state.groups) {
                        DropdownMenuItem(
                            text = { Text((if (gr.id == clip?.groupId) "✓ " else "") + gr.name) },
                            onClick = { menu = null; vm.setClipGroup(index, if (gr.id == clip?.groupId) null else gr.id) },
                        )
                    }
                    DropdownMenuItem(text = { Text("New group") }, onClick = { menu = null; vm.createGroup(index) })
                }
            }
        }
    }
}

private val RULER = 18.dp
private val VIDEO = 52.dp
private val GAP = 6.dp
private val LANE = 40.dp
private val HANDLE = 14.dp
private const val MIN_MS = 300L
private const val MAX_ZOOM_DP = 0.4f

/** Zoomed out at most to the whole content across half the width (the playhead can be at its end). */
private fun minZoom(width: Float, videoEnd: Long) = (width * 0.5f / videoEnd.coerceAtLeast(1000L)).coerceAtMost(0.002f)

private enum class Mode { SCROLL, NONE, TRIM_CLIP_START, TRIM_CLIP_END, MOVE, SLIP, TRIM_SOUND_START, TRIM_SOUND_END, FADE_IN, FADE_OUT }

/** What a finger landed on. */
private sealed interface Hit {
    data object Empty : Hit
    data object Add : Hit
    data class Clip(val index: Int) : Hit
    data class ClipStart(val index: Int) : Hit
    data class ClipEnd(val index: Int) : Hit
    data class Sound(val id: Long) : Hit
    data class SoundStart(val id: Long) : Hit
    data class SoundEnd(val id: Long) : Hit
    data class FadeIn(val id: Long) : Hit
    data class FadeOut(val id: Long) : Hit
}

/** Where things are on screen for playhead [t] (in the middle) and [zoom] (px per ms). */
private class Geometry(
    val width: Float,
    val t: Long,
    val zoom: Float,
    val starts: List<Long>,
    val clips: List<ClipState>,
    val audio: List<AudioClip>,
    val lanes: Int,
    d: androidx.compose.ui.unit.Density,
) {
    val rulerH = with(d) { RULER.toPx() }
    val videoTop = rulerH
    val videoH = with(d) { VIDEO.toPx() }
    val lanesTop = videoTop + videoH + with(d) { GAP.toPx() }
    val laneH = with(d) { LANE.toPx() }
    val handle = with(d) { HANDLE.toPx() }
    val dot = with(d) { 14.dp.toPx() }

    fun x(ms: Long) = width / 2f + (ms - t) * zoom
    fun ms(x: Float) = t + ((x - width / 2f) / zoom).toLong()
    fun laneAt(y: Float) = ((y - lanesTop) / laneH).toInt().coerceIn(0, lanes - 1)
    fun laneTop(lane: Int) = lanesTop + lane * laneH

    fun hit(p: Offset, selectedClip: Int?, selectedSound: Long?): Hit {
        // Handles of the selection first: they sit outside its ends, so short clips stay editable.
        if (selectedClip != null && selectedClip in clips.indices && p.y in videoTop..(videoTop + videoH)) {
            val l = x(starts[selectedClip])
            val r = x(starts[selectedClip + 1])
            if (p.x in (l - handle)..(l + handle * 0.3f)) return Hit.ClipStart(selectedClip)
            if (p.x in (r - handle * 0.3f)..(r + handle)) return Hit.ClipEnd(selectedClip)
        }
        val sel = audio.firstOrNull { it.id == selectedSound }
        if (sel != null) {
            val top = laneTop(sel.lane)
            if (p.y in top..(top + laneH)) {
                val pc = sel.piece
                val l = x(pc.startOnTimelineMs)
                val r = x(pc.endOnTimelineMs)
                if (p.x in (l - handle)..l) return Hit.SoundStart(sel.id)
                if (p.x in r..(r + handle)) return Hit.SoundEnd(sel.id)
                if (p.y < top + dot * 1.4f) {
                    if (abs(p.x - x(pc.startOnTimelineMs + pc.fadeIn)) < dot) return Hit.FadeIn(sel.id)
                    if (abs(p.x - x(pc.endOnTimelineMs - pc.fadeOut)) < dot) return Hit.FadeOut(sel.id)
                }
            }
        }
        if (p.y in videoTop..(videoTop + videoH)) {
            val at = ms(p.x)
            val i = starts.indexOfLast { it <= at }
            if (i in clips.indices && at < starts.last()) return Hit.Clip(i)
            val addL = x(starts.last()) + handle
            if (p.x in addL..(addL + videoH)) return Hit.Add
            return Hit.Empty
        }
        if (p.y >= lanesTop) {
            val lane = laneAt(p.y)
            val at = ms(p.x)
            audio.firstOrNull { it.lane == lane && at >= it.piece.startOnTimelineMs && at < it.piece.endOnTimelineMs }?.let { return Hit.Sound(it.id) }
        }
        return Hit.Empty
    }
}

/** Time along the top, with labels at a step that suits the zoom. */
private fun DrawScope.drawRuler(g: Geometry, colour: Color, text: androidx.compose.ui.text.TextMeasurer) {
    val visible = (size.width / g.zoom).toLong()
    val step = listOf(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L).firstOrNull { visible / it <= 10 } ?: 300_000L
    val from = (g.ms(0f) / step - 1).coerceAtLeast(0) * step
    var t = from
    while (g.x(t) < size.width) {
        val x = g.x(t)
        val major = (t / step) % 2 == 0L
        drawLine(colour.copy(alpha = 0.6f), Offset(x, g.rulerH * if (major) 0.35f else 0.65f), Offset(x, g.rulerH), strokeWidth = 1f)
        if (major) {
            val label = if (step < 1000) String.format(java.util.Locale.US, "%.1f", t / 1000f) else Formats.duration(t)
            drawText(text, label, Offset(x + 3.dp.toPx(), 0f), TextStyle(color = colour, fontSize = 9.sp))
        }
        t += step
    }
}

/** The clips as strips of frames, with group, speed and match marks; the selected one with handles. */
private fun DrawScope.drawVideo(
    g: Geometry,
    state: UiState,
    ghost: Pair<Int, Long>?,
    filmstrip: Filmstrip,
    text: androidx.compose.ui.text.TextMeasurer,
    maskKeys: List<Long>,
    videoSelected: Boolean,
) {
    val top = g.videoTop
    val h = g.videoH
    val radius = CornerRadius(6.dp.toPx())
    state.clips.forEachIndexed { i, c ->
        val shift = ghost?.takeIf { it.first == i }?.second ?: 0L
        val l = g.x(g.starts[i] + shift)
        val r = g.x(g.starts[i + 1])
        if (r < 0f || l > size.width || r - l < 1f) return@forEachIndexed
        drawRoundRect(HeadsupColors.SurfaceHigh, Offset(l, top), Size(r - l, h), radius)
        // Frames: tiles of the clip's shape, each showing the moment at its left edge.
        val aspect = if (c.meta.width > 0 && c.meta.height > 0) (c.meta.width.toFloat() / c.meta.height).coerceIn(0.5f, 2f) else 16f / 9f
        val tileW = h * aspect
        val tileMs = tileW / g.zoom * c.speed
        val stepMs = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L).firstOrNull { it >= tileMs } ?: 120_000L
        clipRect(l.coerceAtLeast(0f), top, r.coerceAtMost(size.width), top + h) {
            var x = l
            while (x < r) {
                if (x + tileW > 0f && x < size.width) {
                    val tl = g.starts[i] + shift + ((x - l) / g.zoom).toLong()
                    val fileMs = c.trimStartMs + ((tl - g.starts[i]) * c.speed).toLong()
                    filmstrip.frame(c.meta.uri, fileMs, stepMs, c.meta.isPhoto)?.let { img ->
                        // Centre crop of the frame into the tile.
                        val srcAspect = img.width.toFloat() / img.height
                        val (sw, sh) = if (srcAspect > aspect) (img.height * aspect).toInt() to img.height else img.width to (img.width / aspect).toInt()
                        drawImage(
                            img,
                            srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                            srcSize = IntSize(sw.coerceAtLeast(1), sh.coerceAtLeast(1)),
                            dstOffset = IntOffset(x.toInt(), top.toInt()),
                            dstSize = IntSize(tileW.toInt() + 1, h.toInt()),
                        )
                    }
                }
                x += tileW
            }
            // Duration and speed, the group, how well it matches the watch data.
            val label = Formats.duration(c.timelineDurationMs) + if (c.speed != 1f) " · ${if (c.speed % 1f == 0f) c.speed.toInt() else c.speed}×" else ""
            drawText(text, label, Offset(l + 5.dp.toPx(), top + h - 15.dp.toPx()), TextStyle(color = Color.White, fontSize = 10.sp, background = Color.Black.copy(alpha = 0.5f)))
            state.groups.firstOrNull { it.id == c.groupId }?.let {
                drawText(text, it.name, Offset(l + 5.dp.toPx(), top + 3.dp.toPx()), TextStyle(color = HeadsupColors.OnHighlight, fontSize = 10.sp, background = HeadsupColors.Highlight.copy(alpha = 0.9f)))
            }
            val dotColour = when (c.sync?.confidence) {
                Confidence.HIGH -> HeadsupColors.Good
                Confidence.MEDIUM -> HeadsupColors.Warn
                else -> HeadsupColors.Bad
            }
            drawCircle(dotColour, 3.5f.dp.toPx(), Offset(r - 8.dp.toPx(), top + 8.dp.toPx()))
        }
        // The clip under the playhead is outlined; the selected one gets handles.
        val current = i == state.selectedClip
        if (current) {
            val selected = videoSelected
            drawRoundRect(if (selected) Color.White else HeadsupColors.Accent, Offset(l, top), Size(r - l, h), radius, style = Stroke((if (selected) 2.5f else 1.5f).dp.toPx()))
            if (selected) {
                for ((x, left) in listOf(l to true, r to false)) {
                    val hx = if (left) x - g.handle else x
                    drawRoundRect(Color.White, Offset(hx, top), Size(g.handle, h), CornerRadius(4.dp.toPx()))
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(hx + g.handle / 2f, top + h * 0.3f), Offset(hx + g.handle / 2f, top + h * 0.7f), strokeWidth = 2.dp.toPx())
                }
            }
            // Keys of the mask being edited.
            for (k in maskKeys) {
                val x = g.x(g.starts[i] + ((k - c.trimStartMs) / c.speed).toLong())
                if (x < l || x > r) continue
                val y = top + h - 6.dp.toPx()
                val s = 5.dp.toPx()
                drawPath(Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close() }, Color.White)
            }
        }
    }
    // Adding media, after the last clip.
    val addL = g.x(g.starts.last()) + g.handle
    if (addL < size.width) {
        drawRoundRect(HeadsupColors.Outline, Offset(addL, top), Size(h, h), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
        val c = Offset(addL + h / 2f, top + h / 2f)
        val arm = h * 0.18f
        drawLine(HeadsupColors.Highlight, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 2.dp.toPx())
        drawLine(HeadsupColors.Highlight, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 2.dp.toPx())
    }
}

/** The audio tracks: sounds as waveforms with their beat grid and accents; the selected one with handles and fades. */
private fun DrawScope.drawAudio(g: Geometry, state: UiState, text: androidx.compose.ui.text.TextMeasurer, accent: Color, laneColour: Color) {
    for (lane in 0 until g.lanes) drawRoundRect(laneColour, Offset(0f, g.laneTop(lane) + 2f), Size(size.width, g.laneH - 4f), CornerRadius(6.dp.toPx()))
    for (c in state.audioClips) {
        val p = c.piece
        val l = g.x(p.startOnTimelineMs)
        val r = g.x(p.endOnTimelineMs)
        val top = g.laneTop(c.lane) + 2f
        val h = g.laneH - 4f
        if (r < -g.handle || l > size.width + g.handle) continue
        val selected = c.id == state.selectedAudio
        val analysis = state.audioAnalysis[c.uri]
        drawRoundRect(accent.copy(alpha = if (selected) 0.34f else 0.18f), Offset(l, top), Size((r - l).coerceAtLeast(2f), h), CornerRadius(5.dp.toPx()))
        clipRect(l.coerceAtLeast(0f), top, r.coerceAtMost(size.width), top + h) {
            val env = analysis?.envelope
            val peak = env?.levels?.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val mid = top + h / 2f
            val msPerPx = 1f / g.zoom
            var px = l.coerceAtLeast(0f)
            while (px < r.coerceAtMost(size.width)) {
                val t = g.ms(px)
                p.sourceAt(t)?.let { src ->
                    val level = env?.peak(src, src + msPerPx.toLong() + 1)?.div(peak) ?: 0.15f
                    val amp = (h / 2f - 3f) * level * p.gainAt(t)
                    drawLine(accent.copy(alpha = if (selected) 0.95f else 0.65f), Offset(px, mid - amp), Offset(px, mid + amp), strokeWidth = 1.5f)
                }
                px += 2f
            }
            analysis?.rhythm?.let { rh ->
                for (b in rh.beats) {
                    val x = g.x(p.startOnTimelineMs + (b - p.sourceStartMs))
                    if (x in l..r) drawLine(Color.White.copy(alpha = 0.2f), Offset(x, top + h - 5.dp.toPx()), Offset(x, top + h), strokeWidth = 1f)
                }
                for (a in rh.accents) {
                    val x = g.x(p.startOnTimelineMs + (a.ms - p.sourceStartMs))
                    if (x in l..r) drawLine(Color.White.copy(alpha = 0.3f + 0.6f * a.strength), Offset(x, top), Offset(x, top + (5 + 6 * a.strength).dp.toPx()), strokeWidth = 2f)
                }
            }
            drawText(text, c.name, Offset(l.coerceAtLeast(0f) + 5.dp.toPx(), top + 2.dp.toPx()), TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp), maxLines = 1)
        }
        if (selected) {
            drawRoundRect(Color.White, Offset(l, top), Size((r - l).coerceAtLeast(2f), h), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
            for ((x, left) in listOf(l to true, r to false)) {
                val hx = if (left) x - g.handle else x
                drawRoundRect(Color.White, Offset(hx, top), Size(g.handle, h), CornerRadius(4.dp.toPx()))
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(hx + g.handle / 2f, top + h * 0.3f), Offset(hx + g.handle / 2f, top + h * 0.7f), strokeWidth = 2.dp.toPx())
            }
            val dotY = top + 5.dp.toPx()
            val inX = g.x(p.startOnTimelineMs + p.fadeIn)
            val outX = g.x(p.endOnTimelineMs - p.fadeOut)
            drawPath(Path().apply { moveTo(l, top + h); lineTo(inX, dotY); lineTo(outX, dotY); lineTo(r, top + h) }, Color.White.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, 5.dp.toPx(), Offset(inX, dotY))
            drawCircle(Color.White, 5.dp.toPx(), Offset(outX, dotY))
        }
    }
}
