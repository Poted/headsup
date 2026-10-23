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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.headsup.core.edit.AudioEnvelope
import app.headsup.export.MusicTrack
import app.headsup.render.Formats
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The timeline with the clips on top and the music under them, edited like in DaVinci: drag the
 * music to move it, its edges to trim it, the dots at its top corners to fade, hold and drag to
 * slide the song inside it; tap anywhere to jump there. The waveform shows the song's loudness
 * and the ticks its accents, which also run faintly up through the clips to line cuts up with.
 */
@Composable
fun MusicTimeline(
    clipLengthsMs: List<Long>,
    music: MusicTrack,
    envelope: AudioEnvelope?,
    accents: List<AudioEnvelope.Accent>,
    playheadMs: () -> Long,
    onChange: (MusicTrack) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = clipLengthsMs.sum().coerceAtLeast(1L)
    var zoom by remember { mutableFloatStateOf(1f) }
    var scrollMs by remember { mutableLongStateOf(0L) }
    val visibleMs = (totalMs / zoom).toLong().coerceAtLeast(500L)
    scrollMs = scrollMs.coerceIn(0L, (totalMs - visibleMs).coerceAtLeast(0L))

    val current by rememberUpdatedState(music)
    val change by rememberUpdatedState(onChange)
    val seek by rememberUpdatedState(onSeek)
    val haptics = LocalHapticFeedback.current
    val boundaries = remember(clipLengthsMs) { clipLengthsMs.runningFold(0L) { a, b -> a + b } }

    val accent = HeadsupColors.Accent
    val clipA = HeadsupColors.SurfaceHigh
    val clipB = HeadsupColors.Outline
    val track = HeadsupColors.Surface
    val muted = HeadsupColors.TextMuted

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Formats.duration(scrollMs)} – ${Formats.duration(scrollMs + visibleMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("−", { zoom = (zoom / 2f).coerceAtLeast(1f) }, enabled = zoom > 1f)
            Text(String.format(Locale.US, "%.0f×", zoom), style = MaterialTheme.typography.labelSmall, color = muted)
            SmallAction("+", { zoom = (zoom * 2f).coerceAtMost(32f) }, enabled = zoom < 32f)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(totalMs, visibleMs) {
                    val rulerH = 16.dp.toPx()
                    val clipsH = 30.dp.toPx()
                    val musicTop = rulerH + clipsH + 8.dp.toPx()
                    val edge = 18.dp.toPx()
                    fun msAt(x: Float) = scrollMs + (x / size.width * visibleMs).roundToLong()
                    fun xAt(ms: Long) = (ms - scrollMs).toFloat() / visibleMs * size.width
                    val msPerPx = visibleMs.toFloat() / size.width
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val m0 = current
                        val p0 = m0.placement
                        val start = m0.startOnTimelineMs
                        val end = p0.endMs(totalMs)
                        val pieceEnd = minOf(start + p0.playable, totalMs)
                        val y = down.position.y
                        val x = down.position.x
                        val inMusic = y >= musicTop
                        val mode = when {
                            !inMusic -> Mode.PAN
                            y < musicTop + edge && abs(x - xAt(start + m0.fadeInMs)) < edge -> Mode.FADE_IN
                            y < musicTop + edge && abs(x - xAt(end - m0.fadeOutMs)) < edge -> Mode.FADE_OUT
                            abs(x - xAt(start)) < edge -> Mode.TRIM_START
                            abs(x - xAt(pieceEnd)) < edge -> Mode.TRIM_END
                            x > xAt(start) && x < xAt(end) -> Mode.MOVE
                            else -> Mode.PAN
                        }
                        // Holding still on the music first slides the song inside it.
                        val early = withTimeoutOrNull(400L) { Moved(awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }) }
                        val chosen = if (early == null && mode == Mode.MOVE) Mode.SLIP else mode
                        if (chosen == Mode.SLIP) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val slop = if (early != null) early.change else awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                        val dragging = slop ?: run {
                            // A tap: jump there.
                            if (chosen != Mode.SLIP) seek(msAt(x).coerceIn(0L, totalMs - 1))
                            return@awaitEachGesture
                        }
                        val scroll0 = scrollMs
                        var dx = dragging.position.x - x
                        drag(dragging.id) { c ->
                            dx += c.positionChange().x
                            c.consume()
                            val d = (dx * msPerPx).roundToLong()
                            val snap = 10.dp.toPx() * msPerPx
                            fun snapped(ms: Long): Long = boundaries.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) < snap } ?: ms
                            val duration = m0.durationMs.coerceAtLeast(p0.songStopMs)
                            when (chosen) {
                                Mode.PAN -> scrollMs = (scroll0 - d).coerceIn(0L, (totalMs - visibleMs).coerceAtLeast(0L))
                                Mode.MOVE -> change(m0.copy(startOnTimelineMs = snapped(start + d).coerceIn(0L, totalMs - 200)))
                                Mode.SLIP -> {
                                    val songStart = (m0.songStartMs - d).coerceIn(0L, (duration - p0.playable).coerceAtLeast(0L))
                                    change(m0.copy(songStartMs = songStart, songEndMs = if (m0.songEndMs > 0) songStart + p0.playable else 0L))
                                }
                                Mode.TRIM_START -> {
                                    val shift = (snapped(start + d) - start).coerceIn(-minOf(start, m0.songStartMs), p0.playable - 500)
                                    change(m0.copy(startOnTimelineMs = start + shift, songStartMs = m0.songStartMs + shift))
                                }
                                Mode.TRIM_END -> {
                                    val newEnd = snapped(pieceEnd + d)
                                    val songEnd = (m0.songStartMs + (newEnd - start)).coerceIn(m0.songStartMs + 500, duration)
                                    change(m0.copy(songEndMs = if (songEnd >= duration) 0L else songEnd))
                                }
                                Mode.FADE_IN -> change(m0.copy(fadeInMs = (m0.fadeInMs + d).coerceIn(0L, (end - start) / 2)))
                                Mode.FADE_OUT -> change(m0.copy(fadeOutMs = (m0.fadeOutMs - d).coerceIn(0L, (end - start) / 2)))
                            }
                        }
                    }
                },
        ) {
            val rulerH = 16.dp.toPx()
            val clipsH = 30.dp.toPx()
            val musicTop = rulerH + clipsH + 8.dp.toPx()
            val musicH = size.height - musicTop
            fun xAt(ms: Long) = (ms - scrollMs).toFloat() / visibleMs * size.width

            drawRuler(scrollMs, visibleMs, rulerH, muted)

            // Clips, alternating shades, with their number.
            for (i in clipLengthsMs.indices) {
                val l = xAt(boundaries[i])
                val r = xAt(boundaries[i + 1])
                if (r < 0 || l > size.width) continue
                drawRoundRect(if (i % 2 == 0) clipA else clipB, Offset(l + 1f, rulerH), Size((r - l - 2f).coerceAtLeast(1f), clipsH), CornerRadius(4.dp.toPx()))
            }

            // Music lane.
            drawRoundRect(track, Offset(0f, musicTop), Size(size.width, musicH), CornerRadius(6.dp.toPx()))
            val placement = music.placement
            val segments = placement.segments(totalMs)
            val peakMax = envelope?.levels?.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val msPerPx = visibleMs.toFloat() / size.width
            for (seg in segments) {
                val l = xAt(seg.timelineStartMs)
                val r = xAt(seg.timelineStartMs + seg.lengthMs)
                if (r < 0 || l > size.width) continue
                drawRoundRect(accent.copy(alpha = 0.18f), Offset(l, musicTop), Size((r - l - 1f).coerceAtLeast(1f), musicH), CornerRadius(5.dp.toPx()))
                // Waveform, scaled by the fades so they show in the shape.
                val mid = musicTop + musicH / 2f
                var px = l.coerceAtLeast(0f)
                while (px < r.coerceAtMost(size.width)) {
                    val t = scrollMs + (px * msPerPx).toLong()
                    val songMs = seg.songFromMs + (t - seg.timelineStartMs)
                    val level = envelope?.peak(songMs, songMs + msPerPx.toLong() + 1)?.div(peakMax) ?: 0.15f
                    val h = (musicH / 2f - 3f) * level * placement.gainAt(t, totalMs)
                    drawLine(accent.copy(alpha = 0.75f), Offset(px, mid - h), Offset(px, mid + h), strokeWidth = 1.5f)
                    px += 2f
                }
            }
            // Accents: ticks in the music, strong ones faintly up through the clips.
            for (seg in segments) for (a in accents) {
                if (a.ms < seg.songFromMs || a.ms >= seg.songToMs) continue
                val x = xAt(seg.timelineStartMs + a.ms - seg.songFromMs)
                if (x < 0 || x > size.width) continue
                drawLine(Color.White.copy(alpha = 0.25f + 0.6f * a.strength), Offset(x, musicTop), Offset(x, musicTop + 8.dp.toPx() * (0.4f + a.strength)), strokeWidth = 2f)
                if (a.strength > 0.6f) drawLine(Color.White.copy(alpha = 0.18f), Offset(x, rulerH), Offset(x, rulerH + clipsH), strokeWidth = 1f)
            }
            // Handles: edges of the first piece, fade dots, and the fade ramps.
            if (segments.isNotEmpty()) {
                val start = music.startOnTimelineMs
                val end = placement.endMs(totalMs)
                val pieceEnd = minOf(start + placement.playable, totalMs)
                for (ms in listOf(start, pieceEnd)) {
                    val x = xAt(ms)
                    drawRoundRect(Color.White, Offset(x - 2.dp.toPx(), musicTop + musicH * 0.25f), Size(4.dp.toPx(), musicH * 0.5f), CornerRadius(2.dp.toPx()))
                }
                val top = musicTop + 4.dp.toPx()
                val fadeInX = xAt(start + music.fadeInMs)
                val fadeOutX = xAt(end - music.fadeOutMs)
                val ramp = Path().apply {
                    moveTo(xAt(start), musicTop + musicH)
                    lineTo(fadeInX, top)
                    lineTo(fadeOutX, top)
                    lineTo(xAt(end), musicTop + musicH)
                }
                drawPath(ramp, Color.White.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                drawCircle(Color.White, 5.dp.toPx(), Offset(fadeInX, top))
                drawCircle(Color.White, 5.dp.toPx(), Offset(fadeOutX, top))
            }
            // Playhead.
            val x = xAt(playheadMs())
            if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Result of waiting for a drag: the change that started it, or null when the finger lifted. */
private class Moved(val change: androidx.compose.ui.input.pointer.PointerInputChange?)

private enum class Mode { PAN, MOVE, SLIP, TRIM_START, TRIM_END, FADE_IN, FADE_OUT }

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
