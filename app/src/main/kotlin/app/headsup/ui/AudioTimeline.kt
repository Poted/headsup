package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.export.AudioClip
import app.headsup.render.Formats
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The timeline as in DaVinci: the video clips (with their own sound) on top, audio tracks under
 * them. Tap a sound to select it; drag it to move it (also to another track), its edges to trim
 * it, the dots on its top corners to fade; hold, then drag, to slide the source inside it. Tap
 * empty space to jump there. The waveform shows the loudness; thin ticks are the beat grid of
 * music with a steady pulse, brighter ticks the clear accents (strong ones also run up through the
 * video clips, for cutting on them).
 */
@Composable
fun AudioTimeline(
    clipLengthsMs: List<Long>,
    clips: List<AudioClip>,
    analysis: Map<android.net.Uri, SoundAnalysis>,
    selected: Long?,
    playheadMs: () -> Long,
    onSelect: (Long?) -> Unit,
    onChange: (AudioClip) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = clipLengthsMs.sum().coerceAtLeast(1L)
    var zoom by remember { mutableFloatStateOf(1f) }
    var scrollMs by remember { mutableLongStateOf(0L) }
    val visibleMs = (totalMs / zoom).toLong().coerceAtLeast(500L)
    scrollMs = scrollMs.coerceIn(0L, (totalMs - visibleMs).coerceAtLeast(0L))
    val lanes = (clips.maxOfOrNull { it.lane } ?: -1) + 2
    val laneDp = 46.dp

    val current by rememberUpdatedState(clips)
    val selectedNow by rememberUpdatedState(selected)
    val change by rememberUpdatedState(onChange)
    val select by rememberUpdatedState(onSelect)
    val seek by rememberUpdatedState(onSeek)
    val haptics = LocalHapticFeedback.current
    val boundaries = remember(clipLengthsMs) { clipLengthsMs.runningFold(0L) { a, b -> a + b } }
    val text = rememberTextMeasurer()

    val accent = HeadsupColors.Accent
    val clipA = HeadsupColors.SurfaceHigh
    val clipB = HeadsupColors.Outline
    val track = HeadsupColors.Surface
    val muted = HeadsupColors.TextMuted

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Formats.duration(scrollMs)} – ${Formats.duration(scrollMs + visibleMs)}", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            SmallAction("−", { zoom = (zoom / 2f).coerceAtLeast(1f) }, enabled = zoom > 1f)
            Text(String.format(Locale.US, "%.0f×", zoom), style = MaterialTheme.typography.labelSmall, color = muted)
            SmallAction("+", { zoom = (zoom * 2f).coerceAtMost(32f) }, enabled = zoom < 32f)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp + 30.dp + 6.dp + laneDp * lanes)
                .pointerInput(totalMs, visibleMs, lanes) {
                    val rulerH = 16.dp.toPx()
                    val videoH = 30.dp.toPx()
                    val lanesTop = rulerH + videoH + 6.dp.toPx()
                    val laneH = laneDp.toPx()
                    val edge = 16.dp.toPx()
                    fun msAt(x: Float) = scrollMs + (x / size.width * visibleMs).roundToLong()
                    fun xAt(ms: Long) = (ms - scrollMs).toFloat() / visibleMs * size.width
                    fun laneAt(y: Float) = ((y - lanesTop) / laneH).toInt().coerceIn(0, lanes - 1)
                    val msPerPx = visibleMs.toFloat() / size.width
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val x = down.position.x
                        val y = down.position.y
                        val lane = if (y >= lanesTop) laneAt(y) else -1
                        val t = msAt(x)
                        val hit = current.firstOrNull { it.lane == lane && t >= it.piece.startOnTimelineMs - (edge * msPerPx).toLong() && t <= it.piece.endOnTimelineMs + (edge * msPerPx).toLong() }
                        val c0 = hit
                        val p0 = hit?.piece
                        val laneTop = lanesTop + lane * laneH
                        val mode = when {
                            hit == null || p0 == null -> if (y < lanesTop) Edit.PAN else Edit.EMPTY
                            hit.id != selectedNow -> Edit.MOVE
                            y < laneTop + edge && abs(x - xAt(p0.startOnTimelineMs + p0.fadeIn)) < edge -> Edit.FADE_IN
                            y < laneTop + edge && abs(x - xAt(p0.endOnTimelineMs - p0.fadeOut)) < edge -> Edit.FADE_OUT
                            abs(x - xAt(p0.startOnTimelineMs)) < edge -> Edit.TRIM_START
                            abs(x - xAt(p0.endOnTimelineMs)) < edge -> Edit.TRIM_END
                            else -> Edit.MOVE
                        }
                        // Holding still on a sound first slides its source inside it.
                        val early = withTimeoutOrNull(400L) { Dragged(awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }) }
                        val chosen = if (early == null && mode == Edit.MOVE) Edit.SLIP else mode
                        if (chosen == Edit.SLIP) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val slop = if (early != null) early.change else awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                        if (hit != null) select(hit.id)
                        val dragging = slop ?: run {
                            // A tap: on a sound it selects it, elsewhere it jumps there.
                            if (hit == null) {
                                select(null)
                                seek(t.coerceIn(0L, totalMs - 1))
                            }
                            return@awaitEachGesture
                        }
                        val scroll0 = scrollMs
                        var dx = dragging.position.x - x
                        var py = dragging.position.y
                        val others = current.filter { it.id != hit?.id }
                        // Cuts, other sounds' edges and the playhead pull the dragged edge in.
                        val snaps = boundaries + others.flatMap { listOf(it.piece.startOnTimelineMs, it.piece.endOnTimelineMs) } + playheadMs()
                        drag(dragging.id) { ch ->
                            dx += ch.positionChange().x
                            py = ch.position.y
                            ch.consume()
                            val d = (dx * msPerPx).roundToLong()
                            val snap = 10.dp.toPx() * msPerPx
                            fun snapped(ms: Long): Long = snaps.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) < snap } ?: ms
                            if (chosen == Edit.PAN || chosen == Edit.EMPTY) {
                                scrollMs = (scroll0 - d).coerceIn(0L, (totalMs - visibleMs).coerceAtLeast(0L))
                                return@drag
                            }
                            val c = c0 ?: return@drag
                            val p = p0 ?: return@drag
                            fun free(lane: Int, from: Long, to: Long) = others.none { it.lane == lane && it.piece.startOnTimelineMs < to && it.piece.endOnTimelineMs > from }
                            when (chosen) {
                                Edit.MOVE -> {
                                    var start = (p.startOnTimelineMs + d).coerceIn(0L, totalMs - 100)
                                    // Snap whichever end is near something.
                                    val s1 = snapped(start)
                                    val s2 = snapped(start + p.lengthMs) - p.lengthMs
                                    start = if (s1 != start) s1 else if (s2 != start) s2.coerceAtLeast(0L) else start
                                    val toLane = if (py >= lanesTop) laneAt(py) else c.lane
                                    val lane = if (free(toLane, start, start + p.lengthMs)) toLane else c.lane
                                    if (free(lane, start, start + p.lengthMs)) change(c.copy(lane = lane, startOnTimelineMs = start))
                                }
                                Edit.SLIP -> change(c.with(p.slip(-d)))
                                Edit.TRIM_START -> {
                                    val moved = p.trimStart(snapped(p.startOnTimelineMs + d) - p.startOnTimelineMs)
                                    if (free(c.lane, moved.startOnTimelineMs, moved.endOnTimelineMs)) change(c.with(moved))
                                }
                                Edit.TRIM_END -> {
                                    val moved = p.trimEnd(snapped(p.endOnTimelineMs + d) - p.endOnTimelineMs)
                                    if (free(c.lane, moved.startOnTimelineMs, moved.endOnTimelineMs)) change(c.with(moved))
                                }
                                Edit.FADE_IN -> change(c.copy(fadeInMs = (p.fadeIn + d).coerceIn(0L, p.lengthMs - p.fadeOut)))
                                Edit.FADE_OUT -> change(c.copy(fadeOutMs = (p.fadeOut - d).coerceIn(0L, p.lengthMs - p.fadeIn)))
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            val rulerH = 16.dp.toPx()
            val videoH = 30.dp.toPx()
            val lanesTop = rulerH + videoH + 6.dp.toPx()
            val laneH = laneDp.toPx()
            fun xAt(ms: Long) = (ms - scrollMs).toFloat() / visibleMs * size.width
            val msPerPx = visibleMs.toFloat() / size.width

            drawRuler(scrollMs, visibleMs, rulerH, muted)
            // Video clips (their own sound plays with them).
            for (i in clipLengthsMs.indices) {
                val l = xAt(boundaries[i])
                val r = xAt(boundaries[i + 1])
                if (r < 0 || l > size.width) continue
                drawRoundRect(if (i % 2 == 0) clipA else clipB, Offset(l + 1f, rulerH), Size((r - l - 2f).coerceAtLeast(1f), videoH), CornerRadius(4.dp.toPx()))
            }
            for (lane in 0 until lanes) {
                drawRoundRect(track, Offset(0f, lanesTop + lane * laneH + 2f), Size(size.width, laneH - 4f), CornerRadius(6.dp.toPx()))
            }
            // Sounds.
            for (c in clips) {
                val p = c.piece
                val l = xAt(p.startOnTimelineMs)
                val r = xAt(p.endOnTimelineMs)
                if (r < 0 || l > size.width) continue
                val top = lanesTop + c.lane * laneH + 2f
                val h = laneH - 4f
                val isSelected = c.id == selected
                val a = analysis[c.uri]
                drawRoundRect(accent.copy(alpha = if (isSelected) 0.32f else 0.16f), Offset(l, top), Size((r - l).coerceAtLeast(2f), h), CornerRadius(5.dp.toPx()))
                clipRect(l, top, r, top + h) {
                    // Waveform, shaped by the fades.
                    val env = a?.envelope
                    val peakMax = env?.levels?.maxOrNull()?.takeIf { it > 0f } ?: 1f
                    val mid = top + h / 2f
                    var px = l.coerceAtLeast(0f)
                    while (px < r.coerceAtMost(size.width)) {
                        val t = scrollMs + (px * msPerPx).toLong()
                        val src = p.sourceAt(t)
                        if (src != null) {
                            val level = env?.peak(src, src + msPerPx.toLong() + 1)?.div(peakMax) ?: 0.15f
                            val amp = (h / 2f - 3f) * level * p.gainAt(t)
                            drawLine(accent.copy(alpha = if (isSelected) 0.9f else 0.6f), Offset(px, mid - amp), Offset(px, mid + amp), strokeWidth = 1.5f)
                        }
                        px += 2f
                    }
                    // Beat grid and accents.
                    a?.rhythm?.let { rh ->
                        for (b in rh.beats) {
                            val tl = p.startOnTimelineMs + (b - p.sourceStartMs)
                            if (tl < p.startOnTimelineMs || tl >= p.endOnTimelineMs) continue
                            val bx = xAt(tl)
                            drawLine(Color.White.copy(alpha = 0.18f), Offset(bx, top + h - 6.dp.toPx()), Offset(bx, top + h), strokeWidth = 1f)
                        }
                        for (ac in rh.accents) {
                            val tl = p.startOnTimelineMs + (ac.ms - p.sourceStartMs)
                            if (tl < p.startOnTimelineMs || tl >= p.endOnTimelineMs) continue
                            val ax = xAt(tl)
                            drawLine(Color.White.copy(alpha = 0.3f + 0.6f * ac.strength), Offset(ax, top), Offset(ax, top + 6.dp.toPx() + 6.dp.toPx() * ac.strength), strokeWidth = 2f)
                        }
                    }
                    drawText(text, c.name, Offset(l + 6.dp.toPx(), top + 2.dp.toPx()), TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp), maxLines = 1)
                }
                if (isSelected) {
                    // Strong accents up through the video, edges, fade ramps and dots.
                    a?.rhythm?.accents?.filter { it.strength > 0.6f }?.forEach { ac ->
                        val tl = p.startOnTimelineMs + (ac.ms - p.sourceStartMs)
                        if (tl in p.startOnTimelineMs until p.endOnTimelineMs) {
                            val ax = xAt(tl)
                            drawLine(Color.White.copy(alpha = 0.22f), Offset(ax, rulerH), Offset(ax, rulerH + videoH), strokeWidth = 1f)
                        }
                    }
                    drawRoundRect(Color.White, Offset(l, top), Size((r - l).coerceAtLeast(2f), h), CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    for (ex in listOf(l, r)) drawRoundRect(Color.White, Offset(ex - 2.dp.toPx(), top + h * 0.25f), Size(4.dp.toPx(), h * 0.5f), CornerRadius(2.dp.toPx()))
                    val dotY = top + 5.dp.toPx()
                    val inX = xAt(p.startOnTimelineMs + p.fadeIn)
                    val outX = xAt(p.endOnTimelineMs - p.fadeOut)
                    val ramp = Path().apply { moveTo(l, top + h); lineTo(inX, dotY); lineTo(outX, dotY); lineTo(r, top + h) }
                    drawPath(ramp, Color.White.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                    drawCircle(Color.White, 5.dp.toPx(), Offset(inX, dotY))
                    drawCircle(Color.White, 5.dp.toPx(), Offset(outX, dotY))
                }
            }
            // Where the video ends: sound after it is not exported.
            val endX = xAt(totalMs)
            if (endX < size.width) drawRect(Color.Black.copy(alpha = 0.4f), Offset(endX, lanesTop), Size(size.width - endX, size.height - lanesTop))
            val x = xAt(playheadMs())
            if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Result of waiting for a drag: the change that started it, or null when the finger lifted. */
private class Dragged(val change: PointerInputChange?)

private enum class Edit { PAN, EMPTY, MOVE, SLIP, TRIM_START, TRIM_END, FADE_IN, FADE_OUT }

/** Seconds along the top, with a step that suits the zoom. */
private fun DrawScope.drawRuler(fromMs: Long, spanMs: Long, height: Float, color: Color) {
    val step = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L).firstOrNull { spanMs / it <= 8 } ?: 120_000L
    var t = (fromMs / step) * step
    while (t <= fromMs + spanMs) {
        val x = (t - fromMs).toFloat() / spanMs * size.width
        drawLine(color.copy(alpha = 0.6f), Offset(x, height * 0.45f), Offset(x, height), strokeWidth = 1f)
        t += step
    }
}
