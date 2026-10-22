package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A ruler with tick marks that slides under a fixed centre marker, as on camera dials.
 * Dragging moves it one [step] per tick; double-tap calls [onReset]; holding a finger still on it
 * calls [onHold] with true, and with false on release (used to compare before/after).
 */
@Composable
fun RulerSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    onHold: ((Boolean) -> Unit)? = null,
) {
    val current by rememberUpdatedState(value)
    val hold by rememberUpdatedState(onHold)
    val emit by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    val tickSpacing = 9.dp
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(range, step) {
                val px = tickSpacing.toPx()
                var dragValue = 0f
                var travelled = 0f
                detectHorizontalDragGestures(onDragStart = { dragValue = current; travelled = 0f }) { change, dx ->
                    change.consume()
                    // Moving the finger left brings higher values under the marker.
                    travelled -= dx
                    val ticks = (travelled / px).toInt()
                    if (ticks != 0) {
                        travelled -= ticks * px
                        dragValue = snap(dragValue + ticks * step)
                        emit(dragValue)
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset?.invoke() }) }
            .pointerInput(Unit) {
                // Observes without consuming, so dragging and double-tap keep working.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // True if the finger lifted or started dragging before the long-press time.
                    val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        true
                    }
                    if (endedEarly == null) {
                        hold?.invoke(true)
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            hold?.invoke(false)
                        }
                    }
                }
            },
    ) {
        val px = tickSpacing.toPx()
        val cx = size.width / 2
        val total = ((range.endInclusive - range.start) / step).roundToInt()
        val position = (value - range.start) / step
        val zero = ((0f - range.start) / step).roundToInt()
        val first = max(0, floor(position - cx / px).toInt())
        val last = min(total, ceil(position + cx / px).toInt())
        val bottom = size.height - 6.dp.toPx()
        for (i in first..last) {
            val x = cx + (i - position) * px
            val fade = (1f - abs(x - cx) / cx).coerceIn(0.1f, 1f)
            val major = (i - zero) % 5 == 0
            val h = (if (i == zero) 24.dp else if (major) 16.dp else 9.dp).toPx()
            val color = if (i == zero) HeadsupColors.AccentSoft else Color.White
            drawLine(
                color.copy(alpha = fade * if (major) 0.85f else 0.45f),
                Offset(x, bottom - h),
                Offset(x, bottom),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, HeadsupColors.Accent.copy(alpha = 0.7f), Color.Transparent)),
            Offset(0f, bottom + 3.dp.toPx()),
            Offset(size.width, bottom + 3.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        val marker = Path().apply {
            moveTo(cx - 5.dp.toPx(), 0f)
            lineTo(cx + 5.dp.toPx(), 0f)
            lineTo(cx, 7.dp.toPx())
            close()
        }
        drawPath(marker, HeadsupColors.Accent)
        drawLine(HeadsupColors.Accent, Offset(cx, 10.dp.toPx()), Offset(cx, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** "Label  value" header shown above a ruler, e.g. "Exposure  +0.3 EV". */
@Composable
fun ValueHeader(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Text("$label  ", style = MaterialTheme.typography.bodyMedium, color = HeadsupColors.TextMuted)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light))
    }
}

/** Round, outlined option with a short caption, used to pick which correction the ruler edits. */
@Composable
fun CircleOption(symbol: String, caption: String, selected: Boolean, modified: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(60.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) HeadsupColors.SurfaceHigh else Color.Transparent)
                .border(1.dp, if (selected) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, style = MaterialTheme.typography.labelLarge, color = if (selected) HeadsupColors.Accent else Color.White)
            if (modified) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(6.dp).background(HeadsupColors.Accent, CircleShape))
            }
        }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Round outlined icon button, as in the top bar. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, badge: Boolean = false) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(HeadsupColors.Surface)
            .border(1.dp, HeadsupColors.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, Modifier.size(20.dp), tint = Color.White)
        if (badge) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp).clip(CircleShape).background(HeadsupColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(HeadsupIcons.Add, contentDescription = null, Modifier.size(10.dp), tint = Color.Black) }
        }
    }
}

/** Small pill tabs used inside a panel. */
@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(HeadsupColors.SurfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) HeadsupColors.Highlight else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
            }
        }
    }
}
