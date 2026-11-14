package app.headsup.ui

import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * A small action as a pill, as everywhere in the app (48 dp to touch, 32 dp drawn); [selected]
 * marks the one chosen of several, light as the app's chosen states.
 */
@Composable
internal fun Chip(text: String, onClick: () -> Unit, selected: Boolean = false) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .padding(horizontal = 2.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) HeadsupColors.Highlight else HeadsupColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) HeadsupColors.OnHighlight else HeadsupColors.Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Something out of the camera's reach: a card in the theme's warning colours, said plainly. */
@Composable
internal fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HeadsupColors.Bad.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", color = HeadsupColors.Bad, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp))
        Text(text, color = HeadsupColors.Ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/**
 * One exposure setting as a tile: its name, its value, and what it is: Auto (it follows the
 * light, in the accent), held (set by you), or fixed by the mode (a video's shutter). [selected]
 * marks the one the dial below changes.
 */
@Composable
internal fun ExposureTile(label: String, value: String, state: String, auto: Boolean, selected: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) HeadsupColors.SurfaceHigh else HeadsupColors.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) HeadsupColors.AccentInk else HeadsupColors.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, color = if (auto) HeadsupColors.AccentInk else HeadsupColors.Ink, style = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMonoFamily), maxLines = 1)
        Text(state, color = if (auto) HeadsupColors.AccentInk else HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** The day of the plan: ◀ the date ▶, and Today when it is another day. */
@Composable
internal fun DayPicker(st: PlannerState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Chip("◀", { st.day = st.day.minusDays(1) })
        Text(
            st.day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)),
            color = HeadsupColors.Ink, style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f),
        )
        Chip("▶", { st.day = st.day.plusDays(1) })
        if (st.day != java.time.LocalDate.now()) Chip("Today", { st.day = java.time.LocalDate.now() })
    }
}

/** The planner's views at the bottom, as the editor's tools: Sun · Live · Exposure. */
@Composable
internal fun PlannerBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(Triple("Sky", HeadsupIcons.Sun, 0), Triple("Exposure", HeadsupIcons.Aperture, 1))
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.Surface)
            .border(1.dp, HeadsupColors.Outline, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((label, icon, i) in items) {
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) HeadsupColors.Highlight else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = label, Modifier.size(22.dp), tint = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted, maxLines = 1)
            }
        }
    }
}

/** A number typed in, kept as typed until it parses; [reload] changing sets it again from [value] (a place set from outside). */
@Composable
internal fun NumberField(label: String, value: Double, reload: Int, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember(reload) { mutableStateOf(String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

internal fun hhmm(minute: Float): String = String.format(Locale.US, "%02d:%02d", (minute / 60).toInt(), (minute % 60).toInt())

internal fun clock(millis: Long?, zone: ZoneOffset): String =
    if (millis == null) "–" else ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }

internal fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

/** The phone's last known place (latitude, longitude, height), if it has one. */
@Suppress("MissingPermission")
internal fun lastKnown(context: Context): Triple<Double, Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
    return Triple(best.latitude, best.longitude, if (best.hasAltitude()) best.altitude else 0.0)
}

private fun sunColour(altitude: Double): androidx.compose.ui.graphics.Color {
    val night = androidx.compose.ui.graphics.Color(0xFF0D1B4F)
    val blue = androidx.compose.ui.graphics.Color(0xFF3949AB)
    val red = androidx.compose.ui.graphics.Color(0xFFE64A19)
    val orange = androidx.compose.ui.graphics.Color(0xFFFF9800)
    val yellow = androidx.compose.ui.graphics.Color(0xFFFFD54F)
    fun mix(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, f: Double) =
        androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0.0, 1.0).toFloat())
    return when {
        altitude > 10 -> yellow
        altitude > 0 -> mix(orange, yellow, altitude / 10)
        altitude > -6 -> mix(red, orange, (altitude + 6) / 6)
        altitude > -12 -> mix(blue, red, (altitude + 12) / 6)
        altitude > -18 -> mix(night, blue, (altitude + 18) / 6)
        else -> night
    }
}

/**
 * The day as a bar coloured by the sun's height (dark blue night, blue and red twilights, orange
 * and yellow day) with the chosen hour as a round thumb: tap or drag to move the hour.
 */
@Composable
internal fun DayBar(st: PlannerState, modifier: Modifier = Modifier) {
    val place = st.place
    val colours = remember(place, st.day, st.offsetHours) {
        (0 until 96).map { i -> sunColour(app.headsup.core.planner.Sky.sun(place, st.dayStart + i * 15 * 60_000L).altitude) }
    }
    val accent = HeadsupColors.Accent
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTapGestures { st.minute = (it.x / size.width * 1439f).coerceIn(0f, 1439f) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    st.minute = (change.position.x / size.width * 1439f).coerceIn(0f, 1439f)
                }
            },
    ) {
        val cell = size.width / colours.size
        colours.forEachIndexed { i, c ->
            drawRect(c, androidx.compose.ui.geometry.Offset(i * cell, 0f), androidx.compose.ui.geometry.Size(cell + 1f, size.height))
        }
        // Every six hours a faint tick and its label's place.
        for (h in 0..24 step 6) {
            val x = h / 24f * size.width
            drawLine(androidx.compose.ui.graphics.Color(0x66FFFFFF), androidx.compose.ui.geometry.Offset(x, size.height * 0.7f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        }
        val x = st.minute / 1439f * size.width
        val centre = androidx.compose.ui.geometry.Offset(x, size.height / 2)
        drawCircle(androidx.compose.ui.graphics.Color.White, 15.dp.toPx(), centre)
        drawCircle(accent, 11.dp.toPx(), centre)
    }
}
