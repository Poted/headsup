package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.headsup.core.color.MaskKey
import app.headsup.render.Formats
import kotlin.math.abs

/**
 * The selected mask's keys along the clip's cut, like keyframes under a DaVinci clip: diamonds at
 * their moments, the playhead as a line. Tap a diamond to go there and select it (then delete it
 * or move the mask to change it), tap elsewhere to jump; arrows step from key to key.
 */
@Composable
fun MaskKeyStrip(
    keys: List<MaskKey>,
    cutStartMs: Long,
    cutEndMs: Long,
    playhead: LongState,
    onSeek: (clipMs: Long) -> Unit,
    onAdd: () -> Unit,
    onDelete: (timeMs: Long) -> Unit,
    onClear: () -> Unit,
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    val length = (cutEndMs - cutStartMs).coerceAtLeast(1L)
    // The key under the playhead counts as selected, so stopping on one is enough to delete it.
    val atPlayhead = keys.firstOrNull { abs(it.timeMs - (cutStartMs + playhead.longValue)) <= 20 }?.timeMs
    val current = selected?.takeIf { t -> keys.any { it.timeMs == t } } ?: atPlayhead
    val accent = HeadsupColors.Accent
    val track = HeadsupColors.SurfaceHigh
    fun go(t: Long) {
        selected = t
        onSeek(t - cutStartMs)
    }
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(keys, cutStartMs, cutEndMs) {
                    detectTapGestures { p ->
                        val tapMs = cutStartMs + (p.x / size.width * length).toLong()
                        val hit = keys.minByOrNull { abs(it.timeMs - tapMs) }
                            ?.takeIf { abs(it.timeMs - tapMs).toFloat() / length * size.width < 14.dp.toPx() }
                        if (hit != null) go(hit.timeMs) else {
                            selected = null
                            onSeek(tapMs - cutStartMs)
                        }
                    }
                },
        ) {
            val mid = size.height / 2f
            drawRoundRect(track, Offset(0f, mid - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            fun x(t: Long) = (t - cutStartMs).toFloat() / length * size.width
            for (k in keys) {
                val cx = x(k.timeMs)
                val r = if (k.timeMs == current) 8.dp.toPx() else 6.dp.toPx()
                val diamond = Path().apply {
                    moveTo(cx, mid - r); lineTo(cx + r, mid); lineTo(cx, mid + r); lineTo(cx - r, mid); close()
                }
                drawPath(diamond, if (k.timeMs == current) Color.White else accent)
            }
            val px = x(cutStartMs + playhead.longValue)
            drawLine(Color.White, Offset(px, 2f), Offset(px, size.height - 2f), strokeWidth = 2.dp.toPx())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val here = cutStartMs + playhead.longValue
            val previous = keys.lastOrNull { it.timeMs < here - 20 }
            val next = keys.firstOrNull { it.timeMs > here + 20 }
            SmallAction("◀", { previous?.let { go(it.timeMs) } }, enabled = previous != null)
            SmallAction("▶", { next?.let { go(it.timeMs) } }, enabled = next != null)
            Text(
                current?.let { "Key at ${Formats.duration(it - cutStartMs)}" } ?: "${keys.size} keys",
                style = MaterialTheme.typography.labelMedium,
                color = HeadsupColors.TextMuted,
            )
            Spacer(Modifier.weight(1f))
            SmallAction("+ Key", onAdd)
            if (current != null) SmallAction("Delete key", {
                onDelete(current)
                selected = null
            })
            if (keys.isNotEmpty()) SmallAction("Clear", onClear)
        }
    }
}
