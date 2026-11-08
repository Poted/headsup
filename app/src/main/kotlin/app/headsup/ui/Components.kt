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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // A light click on each step, a firmer one on reaching zero, like a camera's dial (Settings › Ruler clicks).
    val view = androidx.compose.ui.platform.LocalView.current
    fun click(atZero: Boolean) {
        if (!AppSettings.haptics) return
        view.performHapticFeedback(
            if (atZero) (if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            else android.view.HapticFeedbackConstants.CLOCK_TICK,
        )
    }

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
                        val before = dragValue
                        dragValue = snap(dragValue + ticks * step)
                        if (dragValue != before) click(atZero = dragValue == 0f)
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
            val color = if (i == zero) HeadsupColors.ZeroTick else HeadsupColors.Ink
            drawLine(
                color.copy(alpha = fade * if (major) 0.85f else 0.45f),
                Offset(x, bottom - h),
                Offset(x, bottom),
                strokeWidth = 1.dp.toPx() * HeadsupColors.StrokeScale,
                cap = StrokeCap.Round,
            )
        }
        val marker = Path().apply {
            moveTo(cx - 5.dp.toPx(), 0f)
            lineTo(cx + 5.dp.toPx(), 0f)
            lineTo(cx, 7.dp.toPx())
            close()
        }
        drawPath(marker, HeadsupColors.AccentInk)
        drawLine(HeadsupColors.AccentInk, Offset(cx, 10.dp.toPx()), Offset(cx, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** "Label  value" header shown above a ruler, e.g. "Exposure  +0.3 EV". */
@Composable
fun ValueHeader(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        if (label.isNotEmpty()) Text("$label  ", style = MaterialTheme.typography.bodyMedium, color = HeadsupColors.TextMuted)
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
                .border(1.dp, if (selected) HeadsupColors.AccentInk else HeadsupColors.ControlEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, style = MaterialTheme.typography.labelLarge, color = if (selected) HeadsupColors.AccentInk else HeadsupColors.Ink)
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
            .border(1.dp, HeadsupColors.ControlEdge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, Modifier.size(20.dp), tint = HeadsupColors.Ink)
        if (badge) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp).clip(CircleShape).background(HeadsupColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(HeadsupIcons.Add, contentDescription = null, Modifier.size(10.dp), tint = HeadsupColors.OnAccent) }
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
                Text(
                    label,
                    style = if (options.size > 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A small caption over a part of a panel, with a hairline after it (and optional actions at its
 * end): what gives a panel its order, so its rows do not run together.
 */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
            color = HeadsupColors.TextMuted,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(HeadsupColors.Outline))
        trailing()
    }
}

/** A compact segmented choice (what a page works on): one line, the chosen part light. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).border(1.dp, HeadsupColors.Outline, CircleShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) HeadsupColors.Highlight else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted, maxLines = 1)
            }
        }
    }
}

/** A layer (the picture's base or a mask) as a chip: symbol and name, a dot when it changes the picture. */
@Composable
fun LayerChip(symbol: String, label: String, selected: Boolean, modified: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(if (selected) HeadsupColors.SurfaceHigh else Color.Transparent)
            .border(1.dp, if (selected) HeadsupColors.AccentInk else HeadsupColors.ControlEdge, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, style = MaterialTheme.typography.labelLarge, color = if (selected) HeadsupColors.AccentInk else HeadsupColors.Ink)
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) HeadsupColors.Ink else HeadsupColors.TextMuted, maxLines = 1)
        }
        if (modified) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(5.dp).background(HeadsupColors.Accent, CircleShape))
        }
    }
}

/** A small action as a pill (symbol and word), for the actions of what is selected. */
@Composable
fun PillAction(symbol: String, text: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    val content = when {
        !enabled -> HeadsupColors.TextMuted.copy(alpha = 0.5f)
        selected -> HeadsupColors.OnHighlight
        else -> HeadsupColors.Highlight
    }
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (selected) HeadsupColors.Highlight else HeadsupColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, style = MaterialTheme.typography.labelMedium, color = content)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * A small action (Rename, Remove, Reset…): a quiet pill of its own, so an action never reads as
 * plain text, without a TextButton's 48 dp touch padding.
 */
@Composable
fun SmallAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            // 48 dp to touch (as Material's chips), 32 dp drawn.
            .minimumInteractiveComponentSize()
            // A little room around it, so pills side by side never touch.
            .padding(horizontal = 2.dp)
            .height(32.dp)
            .clip(CircleShape)
            .background(HeadsupColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) HeadsupColors.Highlight else HeadsupColors.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * A main action of a panel (Start here, Split…): buttons of one row share its width equally, so
 * rows line up and leave no gaps. [danger] marks one that takes something away.
 */
@Composable
fun PanelAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(HeadsupColors.SurfaceHigh)
            .border(1.dp, if (danger) HeadsupColors.Bad.copy(alpha = 0.45f) else HeadsupColors.Outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> HeadsupColors.TextMuted.copy(alpha = 0.5f)
                danger -> HeadsupColors.Bad
                else -> HeadsupColors.Ink
            },
            maxLines = 1,
        )
    }
}

/**
 * Loading indicator in the spirit of a camera: lens aperture blades turning and opening, a
 * blinking REC dot and a running timecode, with [message] beside it. With [progress] (0..1) the
 * percentage and a thin bar show instead of the timecode.
 */
@Composable
fun CineLoader(message: String, modifier: Modifier = Modifier, progress: Float? = null, onCancel: (() -> Unit)? = null) {
    val transition = rememberInfiniteTransition(label = "loader")
    val turn by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "turn",
    )
    val open by transition.animateFloat(
        0.25f, 0.75f,
        infiniteRepeatable(
            tween(1200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "open",
    )
    val blink by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "blink",
    )
    // Running timecode, frames at 25 fps, from when the loader appeared.
    var startMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var nowMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameMillis { t -> if (startMs == 0L) startMs = t; nowMs = t - startMs }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.CardGradient)
            .border(1.dp, HeadsupColors.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val r = size.minDimension / 2f
            val c = center
            val lens = r * 0.86f
            // An iris: dark blades round a six-sided opening that opens and closes as it turns,
            // all inside the lens's ring (nothing reaches past it).
            drawCircle(Color(0xFF0B0B0B), radius = r, center = c)
            val inner = lens * (0.25f + 0.5f * open)
            fun corner(k: Int): Offset {
                val a = Math.toRadians((turn + k * 60f).toDouble())
                return Offset(c.x + (inner * kotlin.math.cos(a)).toFloat(), c.y + (inner * kotlin.math.sin(a)).toFloat())
            }
            val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, lens)) }
            clipPath(clip) {
                drawCircle(Color(0xFF2B231D), radius = lens, center = c)
                val hole = androidx.compose.ui.graphics.Path().apply {
                    moveTo(corner(0).x, corner(0).y)
                    for (k in 1 until 6) lineTo(corner(k).x, corner(k).y)
                    close()
                }
                drawPath(hole, Color(0xFF0B0B0B))
                // Each blade's edge: from a corner of the opening on along its side, out to the ring.
                for (k in 0 until 6) {
                    val p0 = corner(k)
                    val p1 = corner(k + 1)
                    val d = (p1 - p0) / (p1 - p0).getDistance().coerceAtLeast(0.001f)
                    drawLine(HeadsupColors.Accent.copy(alpha = 0.9f), p0, p0 + d * (2f * lens), strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            drawCircle(HeadsupColors.Outline, radius = (r + lens) / 2f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(r - lens))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val total = nowMs / 40
            Text(
                if (progress != null) String.format(java.util.Locale.US, "%.0f%%", progress * 100)
                else String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", total / 90000, total / 1500 % 60, total / 25 % 60, total % 25),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMonoFamily),
                color = HeadsupColors.TextMuted,
            )
            if (progress != null) {
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(HeadsupColors.Outline)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(HeadsupColors.Accent))
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF3B30).copy(alpha = if (blink < 0.6f) 1f else 0.15f)))
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text("REC", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted)
        if (onCancel != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            SmallAction("Cancel", onCancel)
        }
    }
}

/**
 * A section of a panel as a card: its [title] top left, then (top right, always in these places)
 * ⓘ when it has an [info] text and Reset when [onReset] is given, and its controls under them.
 * Every panel is built of these, in the same order (scope, picker, sections, panel actions), so a
 * new panel reads like the ones already known. Reset resets everything in the card.
 */
@Composable
fun ToolSection(
    title: String,
    modifier: Modifier = Modifier,
    info: String? = null,
    onReset: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var showInfo by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.CardGradient)
            .border(1.dp, HeadsupColors.Outline, shape)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
                color = HeadsupColors.TextMuted,
                maxLines = 1,
            )
            if (info != null) InfoButton(showInfo) { showInfo = !showInfo }
            if (onReset != null) SmallAction("Reset", onReset, Modifier.padding(start = 4.dp))
        }
        androidx.compose.animation.AnimatedVisibility(visible = showInfo && info != null) {
            Text(
                info.orEmpty(),
                Modifier.padding(end = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = HeadsupColors.TextMuted,
            )
        }
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** ⓘ: shows or hides a card's explanation; filled while it shows. 48 dp to touch, 22 dp drawn. */
@Composable
fun InfoButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = if (open) "Hide explanation" else "Explain", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (open) HeadsupColors.Highlight else Color.Transparent)
                .border(1.dp, if (open) HeadsupColors.Highlight else HeadsupColors.ControlEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "i",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (open) HeadsupColors.OnHighlight else HeadsupColors.Ink,
            )
        }
    }
}

/**
 * A choice of a few options as a grid of equal cells across the whole width, in rows of equal
 * length (6 options: 3 + 3, never 5 + 1); the chosen one light. [selected] may be none (-1).
 * [glyph] draws a small mark before an option's text (e.g. the shape of a crop).
 */
@Composable
fun ChoiceGrid(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minCell: androidx.compose.ui.unit.Dp = 76.dp,
    glyph: (@Composable (index: Int, on: Boolean) -> Unit)? = null,
    enabled: (Int) -> Boolean = { true },
    outlined: (Int) -> Boolean = { false },
) = ChoiceGrid(options, { it == selected }, onSelect, modifier, minCell, glyph, enabled, outlined)

/** As above, for a choice of several at once: [isOn] says which are on; a tap toggles one. */
@Composable
fun ChoiceGrid(
    options: List<String>,
    isOn: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minCell: androidx.compose.ui.unit.Dp = 76.dp,
    glyph: (@Composable (index: Int, on: Boolean) -> Unit)? = null,
    enabled: (Int) -> Boolean = { true },
    /** Cells to point out (suggested ones): an accent outline, chosen or not. */
    outlined: (Int) -> Boolean = { false },
) {
    if (options.isEmpty()) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 6.dp
        val fit = ((maxWidth + gap) / (minCell + gap)).toInt().coerceIn(1, options.size)
        val rows = (options.size + fit - 1) / fit
        val columns = (options.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (c in 0 until columns) {
                        val i = r * columns + c
                        if (i >= options.size) {
                            Spacer(Modifier.weight(1f))
                            continue
                        }
                        val on = isOn(i)
                        val usable = enabled(i)
                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(if (on) HeadsupColors.Highlight else HeadsupColors.SurfaceHigh)
                                .then(if (outlined(i)) Modifier.border(1.5.dp, HeadsupColors.AccentInk, MaterialTheme.shapes.small) else Modifier)
                                .clickable(enabled = usable) { onSelect(i) }
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (glyph != null) {
                                glyph(i, on)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                options[i],
                                style = MaterialTheme.typography.labelLarge,
                                color = (if (on) HeadsupColors.OnHighlight else HeadsupColors.Ink).copy(alpha = if (usable) 1f else 0.4f),
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A crop's shape as a small outlined rectangle ([aspect] width / height; null: the picture's own, drawn dashed). */
@Composable
fun AspectGlyph(aspect: Float?, on: Boolean) {
    val ink = if (on) HeadsupColors.OnHighlight else HeadsupColors.Ink
    Canvas(Modifier.size(16.dp)) {
        val a = aspect ?: 1.5f
        val w = if (a >= 1f) size.width else size.height * a
        val h = if (a >= 1f) size.width / a else size.height
        val stroke = 1.3.dp.toPx()
        drawRoundRect(
            ink,
            Offset((size.width - w) / 2f + stroke / 2f, (size.height - h) / 2f + stroke / 2f),
            androidx.compose.ui.geometry.Size(w - stroke, h - stroke),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                stroke,
                pathEffect = if (aspect == null) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx())) else null,
            ),
        )
    }
}

/** A named choice inside a section: its name (muted) over its grid, as "Resolution" over Source · 4K · 1080p. */
@Composable
fun LabeledChoice(
    label: String,
    options: List<String>,
    isOn: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    minCell: androidx.compose.ui.unit.Dp = 76.dp,
    enabled: (Int) -> Boolean = { true },
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
        ChoiceGrid(options, isOn, onSelect, minCell = minCell, enabled = enabled)
    }
}

/**
 * A sheet that slides up from the bottom for what a page needs only now and then (what it works
 * on, its keys, copy and paste), as Settings does: the page itself keeps to what is used all the time.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HeadsupColors.Background,
        contentColor = HeadsupColors.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/**
 * A line that sums up what a sheet holds ("12 clips · 2 photos", "1920×1080 · HEVC"), tapped to
 * open it: the page keeps the summary, the sheet the whole list or every setting.
 */
@Composable
fun SheetLine(summary: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(HeadsupColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = HeadsupColors.AccentInk, maxLines = 2)
        Spacer(Modifier.width(8.dp))
        Text("$action ▾", style = MaterialTheme.typography.labelLarge, color = HeadsupColors.Ink)
    }
}
