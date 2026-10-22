@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.headsup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.headsup.core.color.ColorGrade
import app.headsup.export.ExportJob
import app.headsup.export.ExportKind
import app.headsup.export.JobStatus
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import app.headsup.render.DashboardFont
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.Layouts
import app.headsup.render.LayoutPreset
import app.headsup.render.TextEffect
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
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadLut) }
    val jobs by vm.exportJobs.collectAsStateWithLifecycle()
    // The progress notification needs this on Android 13+; export works without it.
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingExport?.invoke()
        pendingExport = null
    }
    val context = LocalContext.current
    fun startExport(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingExport = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                    ClipPreview(
                        clip, activity, state.style,
                        gradeCube = if (clip.colorGrade) state.gradeCube else null,
                        vignette = if (clip.colorGrade) state.grade.vignette else 0f,
                        onMoveWidget = vm::moveWidget,
                        onMoveFinished = vm::persistStyle,
                    )
                    if (clip.sync == null) Hint("No activity covers this clip, so there is nothing to show.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Apply colour to this clip", Modifier.weight(1f))
                        Switch(checked = clip.colorGrade, onCheckedChange = { vm.setClipGrade(index, it) })
                    }
                    OffsetControls(clip.manualOffsetMs) { vm.setManualOffset(index, it) }
                }
            }

            Section("Colour") {
                ColorControls(
                    grade = state.grade,
                    lutName = state.lutName,
                    onLoadLut = { lutPicker.launch(arrayOf("*/*")) },
                    onRemoveLut = vm::removeLut,
                    update = vm::updateGrade,
                )
            }

            Section("Dashboard") {
                StyleControls(state.style, state.activities, vm::updateStyle)
            }

            Section("Export") {
                ExportControls(
                    bitrateFactor = state.bitrateFactor,
                    onBitrateFactor = vm::setBitrateFactor,
                    canExport = state.clips.any { it.sync != null },
                    canTest = state.selectedClip?.let { state.clips.getOrNull(it)?.sync } != null,
                    jobs = jobs,
                    onExportVideos = { startExport(vm::exportVideos) },
                    onExportOverlays = { startExport(vm::exportOverlays) },
                    onTenBitTest = { startExport(vm::runTenBitTest) },
                    onCancel = vm::cancelExports,
                    onClear = vm::clearFinishedExports,
                )
            }
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
                Text(
                    listOfNotNull(
                        "${m.width}×${m.height}",
                        m.frameRate?.let { String.format(Locale.US, "%.4g fps", it) },
                        Formats.duration(m.durationMs),
                        m.bitrate?.let { "${it / 1_000_000} Mb/s" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                m.format?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
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
    Text("Layout", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (preset in LayoutPreset.entries) {
            FilterChip(
                // Picking a layout puts every widget back into it, including dragged ones.
                selected = style.preset == preset && style.placements.isEmpty(),
                onClick = { update { it.copy(preset = preset, placements = emptyMap()) } },
                label = { Text(preset.label) },
            )
        }
    }
    if (style.placements.isNotEmpty()) {
        Hint("${style.placements.size} widget(s) placed by hand. Pick a layout to reset them.")
    }

    Text("Font", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (font in DashboardFont.entries) {
            FilterChip(selected = style.font == font, onClick = { update { it.copy(font = font) } }, label = { Text(font.label) })
        }
    }

    ColorRow("Values", style.valueColor) { c -> update { it.copy(valueColor = c) } }
    ColorRow("Labels", style.labelColor) { c -> update { it.copy(labelColor = c) } }
    ColorRow("Units", style.unitColor) { c -> update { it.copy(unitColor = c) } }

    Text("Text edge", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (effect in TextEffect.entries) {
            FilterChip(selected = style.effect == effect, onClick = { update { it.copy(effect = effect) } }, label = { Text(effect.label) })
        }
    }

    Text(String.format(Locale.US, "Size: %.0f%%", style.scale * 100), style = MaterialTheme.typography.labelLarge)
    Slider(value = style.scale, onValueChange = { v -> update { it.copy(scale = v) } }, valueRange = 0.5f..2f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Colour heart rate by zone", Modifier.weight(1f))
        Switch(checked = style.hrZoneColors, onCheckedChange = { v -> update { it.copy(hrZoneColors = v) } })
    }
}

private val SWATCHES = listOf(
    android.graphics.Color.WHITE,
    Layouts.ACCENT,
    android.graphics.Color.rgb(255, 159, 10),
    android.graphics.Color.rgb(255, 69, 58),
    android.graphics.Color.rgb(48, 209, 88),
    android.graphics.Color.rgb(100, 210, 255),
    android.graphics.Color.rgb(10, 132, 255),
    android.graphics.Color.rgb(191, 90, 242),
    android.graphics.Color.BLACK,
)

@Composable
private fun ColorRow(title: String, selected: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        for (c in SWATCHES) {
            Box(
                Modifier
                    .size(28.dp)
                    .border(
                        width = if (c == selected) 3.dp else 1.dp,
                        color = if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .background(Color(c), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun ColorControls(
    grade: ColorGrade,
    lutName: String?,
    onLoadLut: () -> Unit,
    onRemoveLut: () -> Unit,
    update: ((ColorGrade) -> ColorGrade) -> Unit,
) {
    Hint("Applied to clips with \"Apply colour\" on (log footage by default). The dashboard is drawn on top and is not affected.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(lutName ?: "No camera LUT", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onLoadLut) { Text(if (lutName == null) "Load .cube" else "Change") }
        if (lutName != null) TextButton(onClick = onRemoveLut) { Text("Remove") }
    }
    GradeSlider("Exposure", grade.exposure, -2f..2f, "%+.1f EV") { v -> update { it.copy(exposure = v) } }
    GradeSlider("Brightness", grade.brightness, -1f..1f) { v -> update { it.copy(brightness = v) } }
    GradeSlider("Contrast", grade.contrast, -1f..1f) { v -> update { it.copy(contrast = v) } }
    GradeSlider("Shadows", grade.shadows, -1f..1f) { v -> update { it.copy(shadows = v) } }
    GradeSlider("Highlights", grade.highlights, -1f..1f) { v -> update { it.copy(highlights = v) } }
    GradeSlider("Saturation", grade.saturation, -1f..1f) { v -> update { it.copy(saturation = v) } }
    GradeSlider("Temperature", grade.temperature, -1f..1f) { v -> update { it.copy(temperature = v) } }
    GradeSlider("Vignette", grade.vignette, 0f..1f) { v -> update { it.copy(vignette = v) } }
    if (grade != ColorGrade()) TextButton(onClick = { update { ColorGrade() } }) { Text("Reset corrections") }
}

@Composable
private fun GradeSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String = "%+.2f", onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(String.format(Locale.US, format, value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ExportControls(
    bitrateFactor: Float,
    onBitrateFactor: (Float) -> Unit,
    canExport: Boolean,
    canTest: Boolean,
    jobs: List<ExportJob>,
    onExportVideos: () -> Unit,
    onExportOverlays: () -> Unit,
    onTenBitTest: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Text("Video bit rate", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((factor, label) in listOf(1f to "Like source", 1.5f to "1.5×", 2f to "2×")) {
            FilterChip(selected = bitrateFactor == factor, onClick = { onBitrateFactor(factor) }, label = { Text(label) })
        }
    }
    Button(onClick = onExportVideos, enabled = canExport, modifier = Modifier.fillMaxWidth()) {
        Text("Export videos with dashboard")
    }
    Hint("HEVC, source resolution and frame rate, saved to DCIM/TelemetryOverlay. For editing in DJI Mimo.")
    OutlinedButton(onClick = onExportOverlays, enabled = canExport, modifier = Modifier.fillMaxWidth()) {
        Text("Export dashboard only (PNG for DaVinci)")
    }
    Hint("Transparent PNG frames in a ZIP, saved to Download/TelemetryOverlay. Colour settings are not applied.")
    OutlinedButton(onClick = onTenBitTest, enabled = canTest, modifier = Modifier.fillMaxWidth()) {
        Text("Test 10-bit export (first 3 s of selected clip)")
    }

    if (jobs.isNotEmpty()) {
        jobs.forEach { JobRow(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
                TextButton(onClick = onCancel) { Text("Cancel all") }
            } else {
                TextButton(onClick = onClear) { Text("Clear list") }
            }
        }
    }
}

@Composable
private fun JobRow(job: ExportJob) {
    val kind = when (job.request.kind) {
        ExportKind.VIDEO -> "Video"
        ExportKind.PNG_SEQUENCE -> "PNG"
        ExportKind.TEN_BIT_TEST -> "10-bit test"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$kind · ${job.request.name}", style = MaterialTheme.typography.titleSmall)
            when (job.status) {
                JobStatus.QUEUED -> Text("Waiting", style = MaterialTheme.typography.bodySmall)
                JobStatus.RUNNING -> {
                    LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${job.progress}%", style = MaterialTheme.typography.bodySmall)
                }
                JobStatus.DONE -> Text("Done", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
                JobStatus.FAILED -> Text("Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                JobStatus.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
            }
            job.message?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
