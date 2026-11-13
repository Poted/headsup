package app.headsup.ui

import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.pow

/** A small round button; [selected] marks the one chosen of several. */
@Composable
internal fun Chip(text: String, onClick: () -> Unit, selected: Boolean = false) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) HeadsupColors.Accent else HeadsupColors.SurfaceTop)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) HeadsupColors.OnAccent else HeadsupColors.Ink, style = MaterialTheme.typography.labelMedium,
    )
}

/** Something out of the camera's reach, in red. */
@Composable
internal fun Warning(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HeadsupColors.Bad.copy(alpha = 0.18f)).padding(10.dp),
        color = HeadsupColors.Bad, style = MaterialTheme.typography.bodyMedium,
    )
}

/** One of the exposure settings: held (🔒, with − and +) or the one that follows the light (Auto). */
@Composable
internal fun SettingTile(
    label: String, value: String, isAuto: Boolean, modifier: Modifier,
    onToggle: () -> Unit, onLess: () -> Unit, onMore: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isAuto) HeadsupColors.Accent else HeadsupColors.SurfaceHigh)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ink = if (isAuto) HeadsupColors.OnAccent else HeadsupColors.Ink
        Text(label + if (isAuto) " · auto" else " 🔒", color = ink, style = MaterialTheme.typography.labelSmall)
        Text(value, color = ink, style = MaterialTheme.typography.titleMedium)
        if (isAuto) {
            Text(" ", style = MaterialTheme.typography.labelSmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("−", onLess)
                Chip("+", onMore)
            }
            Chip("Auto", onToggle)
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

/** A value moved by [stops] of light along the usual scale: an f-number moves by √2 per stop, a shutter by 2×. */
internal fun stepStops(v: Double, stops: Double, aperture: Boolean): Double =
    if (aperture) (v * 2.0.pow(stops / 2)).coerceIn(1.0, 64.0) else (v * 2.0.pow(stops)).coerceIn(1.0 / 8000, 600.0)

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
