@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.headsup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.headsup.core.color.ColorGrade
import app.headsup.core.sync.Confidence
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeSource
import app.headsup.core.sync.ZoneReason
import app.headsup.export.ExportJob
import app.headsup.export.ExportKind
import app.headsup.export.JobStatus
import app.headsup.render.DashboardFont
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.LayoutPreset
import app.headsup.render.Layouts
import app.headsup.render.TextEffect
import app.headsup.render.Widget
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

private enum class Page(val label: String) { CLIPS("Clips"), COLOUR("Colour"), DASHBOARD("Dashboard"), EXPORT("Export") }

@Composable
fun HeadsupScreen(vm: HeadsupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.exportJobs.collectAsStateWithLifecycle()
    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addActivities(it) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addClips(it) }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLut) }

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

    var page by rememberSaveable { mutableStateOf(Page.CLIPS) }
    val exporting = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    val previewMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    TenBitResultDialog(jobs)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Headsup", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { fitPicker.launch(arrayOf("*/*")) }) { Text("+ FIT") }
                TextButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("+ Videos") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // The picture stays on screen while any page below is being edited.
            val selected = state.selected
            if (selected != null) {
                if (exporting) {
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Text("Preview paused while exporting") }
                } else {
                    ClipPreview(
                        clip = selected,
                        activity = selected.sync?.let { state.activities.getOrNull(it.trackIndex) },
                        style = state.style,
                        gradeCube = state.previewCube,
                        vignette = if (selected.colorGrade) state.grade.vignette else 0f,
                        maxHeight = previewMaxHeight,
                        onMoveWidget = vm::moveWidget,
                        onMoveFinished = vm::persistStyle,
                    )
                }
            }

            TabRow(selectedTabIndex = page.ordinal) {
                for (p in Page.entries) Tab(selected = page == p, onClick = { page = p }, text = { Text(p.label) })
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.messages.isNotEmpty()) Messages(state.messages, vm::dismissMessages)
                when (page) {
                    Page.CLIPS -> ClipsPage(state, vm)
                    Page.COLOUR -> ColourPage(state, vm, onAddLut = { lutPicker.launch(arrayOf("*/*")) })
                    Page.DASHBOARD -> StyleControls(state.style, state.activities, vm::updateStyle)
                    Page.EXPORT -> ExportControls(
                        bitrateFactor = state.bitrateFactor,
                        onBitrateFactor = vm::setBitrateFactor,
                        canExport = state.clips.any { it.sync != null },
                        canTest = state.selected?.sync != null,
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
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Messages(messages: List<String>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = messages.joinToString("\n\n")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer { Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

// Clips page

@Composable
private fun ClipsPage(state: UiState, vm: HeadsupViewModel) {
    Label("Activities")
    if (state.activities.isEmpty()) Hint("Add the .FIT file(s) from your watch with + FIT.")
    state.activities.forEachIndexed { i, a -> ActivityCard(a, onRemove = { vm.removeActivity(i) }) }

    Label("Clips")
    if (state.clips.isEmpty()) Hint("Add videos with + Videos. Each one is matched to the activity that covers it.")
    state.clips.forEachIndexed { i, clip ->
        ClipCard(
            clip = clip,
            activity = clip.sync?.let { state.activities.getOrNull(it.trackIndex) },
            selected = i == state.selectedClip,
            onClick = { vm.selectClip(i) },
            onRemove = { vm.removeClip(i) },
        )
    }

    val index = state.selectedClip
    val clip = state.selected
    if (index != null && clip != null) {
        if (clip.sync == null) Hint("No activity covers the selected clip, so there is nothing to show.")
        OffsetControls(clip.manualOffsetMs) { vm.setManualOffset(index, it) }
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
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (zone != null) "Watch time zone ${Formats.utcOffset(zone)}" else "No time zone in file, using the phone's",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        m.format?.toString(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
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
            if (clip.meta.info.evidence.isEmpty()) "No recording time in the file (edited copy?)" else "No activity covers this clip",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
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
        Text("Starts ${Formats.clock(sync.startUtcMs, zone)} · ${activity.track.sportName ?: activity.name}", style = MaterialTheme.typography.bodySmall)
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
        Text(String.format(Locale.US, "Activity covers %.0f%% of the clip", sync.coverage * 100), style = MaterialTheme.typography.bodySmall)
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
        Label(String.format(Locale.US, "Data offset for this clip: %+.1f s", offsetMs / 1000.0))
        Slider(value = offsetMs / 1000f, onValueChange = { onChange((it * 10).roundToLong() * 100) }, valueRange = -60f..60f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((delta, label) in listOf(-1000L to "−1 s", -100L to "−0.1 s", 100L to "+0.1 s", 1000L to "+1 s")) {
                OutlinedButton(onClick = { onChange(offsetMs + delta) }) { Text(label) }
            }
            if (offsetMs != 0L) TextButton(onClick = { onChange(0L) }) { Text("Automatic") }
        }
    }
}

// Colour page

private enum class Adjustment(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val format: String,
    val get: (ColorGrade) -> Float,
    val set: (ColorGrade, Float) -> ColorGrade,
) {
    EXPOSURE("Exposure", -2f..2f, "%+.1f", { it.exposure }, { g, v -> g.copy(exposure = v) }),
    BRIGHTNESS("Brightness", -1f..1f, "%+.2f", { it.brightness }, { g, v -> g.copy(brightness = v) }),
    CONTRAST("Contrast", -1f..1f, "%+.2f", { it.contrast }, { g, v -> g.copy(contrast = v) }),
    SHADOWS("Shadows", -1f..1f, "%+.2f", { it.shadows }, { g, v -> g.copy(shadows = v) }),
    HIGHLIGHTS("Highlights", -1f..1f, "%+.2f", { it.highlights }, { g, v -> g.copy(highlights = v) }),
    SATURATION("Saturation", -1f..1f, "%+.2f", { it.saturation }, { g, v -> g.copy(saturation = v) }),
    TEMPERATURE("Temperature", -1f..1f, "%+.2f", { it.temperature }, { g, v -> g.copy(temperature = v) }),
    VIGNETTE("Vignette", 0f..1f, "%.2f", { it.vignette }, { g, v -> g.copy(vignette = v) }),
}

@Composable
private fun ColourPage(state: UiState, vm: HeadsupViewModel, onAddLut: () -> Unit) {
    val index = state.selectedClip
    val clip = state.selected
    if (index == null || clip == null) {
        Hint("Select a clip on the Clips page.")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Apply colour to this clip", Modifier.weight(1f))
        Switch(checked = clip.colorGrade, onCheckedChange = { vm.setClipGrade(index, it) })
    }
    if (!clip.colorGrade) {
        Hint("Off: the clip keeps its own colours (useful for files that are already graded, e.g. Mimo exports).")
        return
    }

    Label("LUT")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = clip.lutName == null, onClick = { vm.setClipLut(index, null) }, label = { Text("None") })
        for (name in state.luts) {
            FilterChip(selected = clip.lutName == name, onClick = { vm.setClipLut(index, name) }, label = { Text(name) })
        }
        OutlinedButton(onClick = onAddLut) { Text("+ .cube") }
    }
    Row {
        if (state.clips.size > 1) TextButton(onClick = { vm.setLutForAllClips(clip.lutName) }) { Text("Use for all clips") }
        clip.lutName?.let { name -> TextButton(onClick = { vm.deleteLut(name) }) { Text("Delete $name") } }
    }

    // One slider at a time, picked from the chips, keeps the page short.
    var adjustment by rememberSaveable { mutableStateOf(Adjustment.EXPOSURE) }
    Label("Corrections (all clips)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in Adjustment.entries) {
            val value = a.get(state.grade)
            val text = if (value == 0f) a.label else "${a.label} ${String.format(Locale.US, a.format, value)}"
            FilterChip(selected = adjustment == a, onClick = { adjustment = a }, label = { Text(text) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(adjustment.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(String.format(Locale.US, adjustment.format, adjustment.get(state.grade)), style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = adjustment.get(state.grade),
        onValueChange = { v -> vm.updateGrade { adjustment.set(it, v) } },
        valueRange = adjustment.range,
    )
    Row {
        TextButton(onClick = { vm.updateGrade { adjustment.set(it, 0f) } }) { Text("Reset ${adjustment.label.lowercase()}") }
        if (state.grade != ColorGrade()) TextButton(onClick = { vm.updateGrade { ColorGrade() } }) { Text("Reset all") }
    }
}

// Dashboard page

@Composable
private fun StyleControls(style: DashboardStyle, activities: List<LoadedActivity>, update: ((DashboardStyle) -> DashboardStyle) -> Unit) {
    val available = Widget.entries.filter { w -> activities.isEmpty() || activities.any { w.isAvailableIn(it.track) } }
    Label("Widgets")
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
    Label("Layout")
    Hint("Drag widgets on the picture to place them by hand.")
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
    if (style.placements.isNotEmpty()) Hint("${style.placements.size} widget(s) placed by hand. Pick a layout to reset them.")

    Label("Font")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (font in DashboardFont.entries) {
            FilterChip(selected = style.font == font, onClick = { update { it.copy(font = font) } }, label = { Text(font.label) })
        }
    }

    ColorRow("Values", style.valueColor) { c -> update { it.copy(valueColor = c) } }
    ColorRow("Labels", style.labelColor) { c -> update { it.copy(labelColor = c) } }
    ColorRow("Units", style.unitColor) { c -> update { it.copy(unitColor = c) } }

    Label("Text edge")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (effect in TextEffect.entries) {
            FilterChip(selected = style.effect == effect, onClick = { update { it.copy(effect = effect) } }, label = { Text(effect.label) })
        }
    }

    Label(String.format(Locale.US, "Size: %.0f%%", style.scale * 100))
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

// Export page

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
    Label("Video bit rate")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((factor, label) in listOf(1f to "Like source", 1.5f to "1.5×", 2f to "2×")) {
            FilterChip(selected = bitrateFactor == factor, onClick = { onBitrateFactor(factor) }, label = { Text(label) })
        }
    }
    Button(onClick = onExportVideos, enabled = canExport, modifier = Modifier.fillMaxWidth()) { Text("Export videos with dashboard") }
    Hint("Colour + dashboard, HEVC, source resolution and frame rate. Saved to DCIM/TelemetryOverlay, for editing in DJI Mimo.")
    OutlinedButton(onClick = onExportOverlays, enabled = canExport, modifier = Modifier.fillMaxWidth()) { Text("Export dashboard only (PNG)") }
    Hint("Transparent PNG frames in a ZIP for DaVinci Resolve. Saved to Download/TelemetryOverlay.")
    OutlinedButton(onClick = onTenBitTest, enabled = canTest, modifier = Modifier.fillMaxWidth()) { Text("Test 10-bit export") }
    Hint("Exports the first 3 s of the selected clip and shows the result in a window; nothing is saved.")

    if (jobs.isNotEmpty()) {
        jobs.forEach { JobRow(it) }
        if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
            TextButton(onClick = onCancel) { Text("Cancel all") }
        } else {
            TextButton(onClick = onClear) { Text("Clear list") }
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
                JobStatus.QUEUED -> Hint("Waiting")
                JobStatus.RUNNING -> {
                    LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Hint("${job.progress}%")
                }
                JobStatus.DONE -> Text("Done", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
                JobStatus.FAILED -> Text("Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                JobStatus.CANCELLED -> Hint("Cancelled")
            }
            job.message?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

/** Shows each finished 10-bit test once, with a button to copy the report. */
@Composable
private fun TenBitResultDialog(jobs: List<ExportJob>) {
    var seen by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val job = jobs.lastOrNull {
        it.request.kind == ExportKind.TEN_BIT_TEST && (it.status == JobStatus.DONE || it.status == JobStatus.FAILED) && it.id !in seen
    } ?: return
    val clipboard = LocalClipboardManager.current
    val report = job.message ?: "No details."
    AlertDialog(
        onDismissRequest = { seen = seen + job.id },
        title = { Text(if (job.status == JobStatus.DONE) "10-bit test" else "10-bit test failed") },
        text = { SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { seen = seen + job.id }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") } },
    )
}
