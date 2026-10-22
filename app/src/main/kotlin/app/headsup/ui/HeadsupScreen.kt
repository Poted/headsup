@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.headsup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.headsup.core.sync.Confidence
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeSource
import app.headsup.core.sync.ZoneReason
import app.headsup.render.Corner
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.Widget
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun HeadsupScreen(vm: HeadsupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addActivities(it) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addClips(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Headsup", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { fitPicker.launch(arrayOf("*/*")) }) { Text("Add FIT files") }
                Button(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Add videos") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.messages.isNotEmpty()) Messages(state.messages, vm::dismissMessages)

            Section("Activities") {
                if (state.activities.isEmpty()) Hint("Add the .FIT file(s) from your watch.")
                state.activities.forEachIndexed { i, a -> ActivityCard(a, onRemove = { vm.removeActivity(i) }) }
            }

            Section("Clips") {
                if (state.clips.isEmpty()) Hint("Add videos. Each one is matched to the activity that covers it.")
                state.clips.forEachIndexed { i, clip ->
                    ClipCard(
                        clip = clip,
                        activity = clip.sync?.let { state.activities.getOrNull(it.trackIndex) },
                        selected = i == state.selectedClip,
                        onClick = { vm.selectClip(i) },
                        onRemove = { vm.removeClip(i) },
                    )
                }
            }

            val selected = state.selectedClip?.let { index -> state.clips.getOrNull(index)?.let { index to it } }
            if (selected != null) {
                val (index, clip) = selected
                Section("Preview") {
                    val activity = clip.sync?.let { state.activities.getOrNull(it.trackIndex) }
                    ClipPreview(clip, activity, state.style)
                    if (clip.sync == null) Hint("No activity covers this clip, so there is nothing to show.")
                    OffsetControls(clip.manualOffsetMs) { vm.setManualOffset(index, it) }
                }
            }

            Section("Dashboard") {
                StyleControls(state.style, state.activities, vm::updateStyle)
            }
            Hint("Export comes in the next stage.")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Messages(messages: List<String>, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            messages.forEach { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ActivityCard(activity: LoadedActivity, onRemove: () -> Unit) {
    val t = activity.track
    val zone = t.utcOffsetSeconds
    val shownZone = zone ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(t.startMs)).totalSeconds
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.sportName ?: t.sport.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Formats.date(t.startMs, shownZone)}  ${Formats.clock(t.startMs, shownZone)}–${Formats.clock(t.endMs, shownZone)}" +
                        "  (${Formats.duration(t.endMs - t.startMs)})",
                )
                Text(
                    if (zone != null) "Watch time zone ${Formats.utcOffset(zone)}" else "No time zone in file, using the phone's",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(activity.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun ClipCard(clip: ClipState, activity: LoadedActivity?, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val m = clip.meta
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                Text("${m.width}×${m.height} · ${Formats.duration(m.durationMs)}", style = MaterialTheme.typography.bodySmall)
                SyncSummary(clip, activity)
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun SyncSummary(clip: ClipState, activity: LoadedActivity?) {
    val sync = clip.sync
    if (sync == null || activity == null) {
        Text(
            if (clip.meta.info.evidence.isEmpty()) "No recording time found in the file" else "No activity covers this clip",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val zone = activity.track.utcOffsetSeconds ?: sync.primary.zoneOffsetSeconds
    val p = sync.primary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sync.confidence.name,
            color = when (sync.confidence) {
                Confidence.HIGH -> Color(0xFF4CAF50)
                Confidence.MEDIUM -> Color(0xFFFFB300)
                Confidence.LOW -> MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Text("Starts ${Formats.clock(sync.startUtcMs, zone)} · ${activity.track.sportName ?: activity.name}")
    }
    Text(
        "${sourceLabel(p.evidence.source)}, ${zoneLabel(p.zoneReason, p.zoneOffsetSeconds)}, " +
            if (p.anchor == TimeAnchor.END) "end of recording" else "start of recording",
        style = MaterialTheme.typography.bodySmall,
    )
    if (sync.agreeing.isNotEmpty()) {
        Text("Confirmed by ${sync.agreeing.joinToString { sourceLabel(it.evidence.source) }}", style = MaterialTheme.typography.bodySmall)
    }
    if (sync.coverage < 1.0) {
        Text(
            String.format(Locale.US, "Activity covers %.0f%% of the clip", sync.coverage * 100),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (sync.alternatives > 0 && sync.confidence != Confidence.HIGH) {
        Text("Other interpretations also fit – check the preview", style = MaterialTheme.typography.bodySmall)
    }
}

private fun sourceLabel(source: TimeSource) = when (source) {
    TimeSource.DJI_FILENAME -> "DJI file name"
    TimeSource.PHONE_FILENAME -> "phone file name"
    TimeSource.MP4_CREATION_TIME -> "MP4 creation time"
    TimeSource.MEDIASTORE_DATE_TAKEN -> "gallery date"
}

private fun zoneLabel(reason: ZoneReason, offset: Int) = when (reason) {
    ZoneReason.AS_UTC -> "UTC"
    ZoneReason.ACTIVITY_ZONE -> "watch time zone (${Formats.utcOffset(offset)})"
    ZoneReason.DEVICE_ZONE -> "phone time zone (${Formats.utcOffset(offset)})"
    ZoneReason.SEARCHED -> "guessed time zone (${Formats.utcOffset(offset)})"
}

@Composable
private fun OffsetControls(offsetMs: Long, onChange: (Long) -> Unit) {
    Column {
        Text(String.format(Locale.US, "Manual correction: %+.1f s", offsetMs / 1000.0))
        Slider(
            value = offsetMs / 1000f,
            onValueChange = { onChange((it * 10).roundToLong() * 100) },
            valueRange = -60f..60f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((delta, label) in listOf(-1000L to "−1 s", -100L to "−0.1 s", 100L to "+0.1 s", 1000L to "+1 s")) {
                OutlinedButton(onClick = { onChange(offsetMs + delta) }) { Text(label) }
            }
        }
        if (offsetMs != 0L) TextButton(onClick = { onChange(0L) }) { Text("Reset to automatic") }
    }
}

@Composable
private fun StyleControls(style: DashboardStyle, activities: List<LoadedActivity>, update: ((DashboardStyle) -> DashboardStyle) -> Unit) {
    val available = Widget.entries.filter { w -> activities.isEmpty() || activities.any { w.isAvailableIn(it.track) } }
    Text("Widgets", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (widget in available) {
            val on = widget in style.widgets
            FilterChip(
                selected = on,
                onClick = { update { s -> s.copy(widgets = if (on) s.widgets - widget else s.widgets + widget) } },
                label = { Text(widget.label) },
            )
        }
    }
    Text("Position", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (corner in Corner.entries) {
            FilterChip(
                selected = style.corner == corner,
                onClick = { update { it.copy(corner = corner) } },
                label = { Text(corner.label) },
            )
        }
    }
    Text(String.format(Locale.US, "Size: %.0f%%", style.scale * 100), style = MaterialTheme.typography.labelLarge)
    Slider(value = style.scale, onValueChange = { v -> update { it.copy(scale = v) } }, valueRange = 0.5f..2f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Colour heart rate by zone", Modifier.weight(1f))
        Switch(checked = style.hrZoneColors, onCheckedChange = { v -> update { it.copy(hrZoneColors = v) } })
    }
}
