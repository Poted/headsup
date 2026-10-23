@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.headsup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import app.headsup.color.ScopeResult
import app.headsup.color.Vectorscope
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.GradientMask
import app.headsup.core.color.SkinTone
import kotlin.math.cos
import kotlin.math.sin
import app.headsup.core.sync.Confidence
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeSource
import app.headsup.core.sync.ZoneReason
import app.headsup.export.ExportJob
import app.headsup.export.ExportKind
import app.headsup.export.JobStatus
import app.headsup.render.DashboardFont
import app.headsup.render.EffectSettings
import app.headsup.render.GraphMetric
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.LayoutPreset
import app.headsup.render.Layouts
import app.headsup.render.TextEffect
import app.headsup.render.Widget
import androidx.compose.material3.OutlinedTextField
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToLong
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import app.headsup.core.track.ActivityTrack

private val DOWNLOADS_FOLDER: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

/** File picker that opens in [initial] instead of the last used folder. */
private class OpenDocumentsIn(private val initial: Uri) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
}

private enum class Tool(val label: String, val icon: ImageVector) {
    FILES("Files", HeadsupIcons.Video),
    COLOUR("Colour", HeadsupIcons.Colour),
    EFFECTS("Effects", HeadsupIcons.Dashboard),
    EXPORT("Export", HeadsupIcons.Export),
}

@Composable
fun HeadsupScreen(vm: HeadsupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.exportJobs.collectAsStateWithLifecycle()
    // FIT files usually land in Downloads; videos are picked from the gallery.
    val fitPicker = rememberLauncherForActivityResult(OpenDocumentsIn(DOWNLOADS_FOLDER)) { vm.addActivities(it) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addClips(it) }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLut) }
    val addFit = { fitPicker.launch(arrayOf("*/*")) }
    val addVideos = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

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

    var tool by rememberSaveable { mutableStateOf(Tool.FILES) }
    var adjustment by rememberSaveable { mutableStateOf(Adjustment.EXPOSURE) }
    val measuring = tool == Tool.COLOUR && adjustment == Adjustment.SKIN
    // Read only where needed (cut controls), so playback does not recompose the whole screen.
    val playhead = remember { mutableLongStateOf(0L) }
    // File position of a cut handle being dragged; read only by the preview.
    val cutScrub = remember { mutableStateOf<Long?>(null) }
    val exporting = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    val previewMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    TenBitResultDialog(jobs)

    Box(Modifier.fillMaxSize().background(HeadsupColors.BackgroundGradient)) {
        // No title bar: the picture starts at the top, adding files lives in the Files tool.
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 8.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            // The picture stays on screen while any tool below is being used.
            val selected = state.selected
            when {
                selected == null -> EmptyState(hasActivities = state.activities.isNotEmpty(), onAddFit = addFit, onAddVideos = addVideos)
                exporting -> Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(HeadsupColors.Surface),
                    contentAlignment = Alignment.Center,
                ) { Hint("Preview paused while exporting") }
                else -> ClipPreview(
                    clip = selected,
                    activity = selected.sync?.let { state.activities.getOrNull(it.trackIndex) },
                    style = state.style,
                    gradeCube = state.previewCube,
                    mask = selected.mask?.takeIf { selected.colorGrade },
                    maskCube = state.previewMaskCube,
                    maskGuide = tool == Tool.COLOUR && state.target == GradeTarget.MASK,
                    vignette = if (selected.colorGrade) state.appliedGrade(selected).vignette else 0f,
                    effects = state.effects,
                    onSample = if (measuring) vm::sampleFrame else null,
                    marker = if (measuring) state.scope?.let { Offset(it.markerX, it.markerY) } else null,
                    maxHeight = previewMaxHeight,
                    onMoveWidget = vm::moveWidget,
                    onMoveFinished = vm::persistStyle,
                    onPosition = { playhead.longValue = it },
                    cutScrub = cutScrub,
                )
            }
            if (state.clips.isNotEmpty()) {
                ClipStrip(
                    state.clips, state.selectedClip, vm::selectClip, addVideos,
                    onMove = vm::moveClip, onDelete = vm::removeClip,
                    groups = state.groups, onGroup = vm::setClipGroup, onNewGroup = vm::createGroup,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(HeadsupColors.CardGradient)
                    .border(1.dp, HeadsupColors.Outline, RoundedCornerShape(28.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.messages.isNotEmpty()) Messages(state.messages, vm::dismissMessages)
                when (tool) {
                    Tool.FILES -> FilesPanel(state, vm, playhead, onCutScrub = { cutScrub.value = it }, onAddVideos = addVideos, onAddFit = addFit)
                    Tool.COLOUR -> ColourPage(state, vm, adjustment, { adjustment = it }, onAddLut = { lutPicker.launch(arrayOf("*/*")) })
                    Tool.EFFECTS -> EffectsPanel(state, vm)
                    Tool.EXPORT -> ExportControls(
                        bitrateFactor = state.bitrateFactor,
                        sourceBitrate = state.selected?.meta?.bitrate,
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
            BottomToolbar(tool) { tool = it }
        }
    }
}

@Composable
private fun BottomToolbar(selected: Tool, onSelect: (Tool) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.Surface)
            .border(1.dp, HeadsupColors.Outline, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in Tool.entries) {
            val on = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) HeadsupColors.Highlight else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = t.label, Modifier.size(22.dp), tint = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
            }
        }
    }
}
@Composable
private fun EmptyState(hasActivities: Boolean, onAddFit: () -> Unit, onAddVideos: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(HeadsupColors.Surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Start a project", style = MaterialTheme.typography.titleMedium)
        Hint("Clips are matched to your watch data automatically by their recording time.")
        Button(onClick = onAddFit, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasActivities) "1. Activity added ✓  (add another)" else "1. Add the FIT file from your watch")
        }
        Button(onClick = onAddVideos, modifier = Modifier.fillMaxWidth()) { Text("2. Add videos") }
    }
}

/** Rounded block used to group related controls inside a panel. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HeadsupColors.SurfaceHigh).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// Files panel

/** Adding videos and watch files, plus how the selected clip is matched to the watch data. */
@Composable
private fun FilesPanel(
    state: UiState,
    vm: HeadsupViewModel,
    playhead: LongState,
    onCutScrub: (Long?) -> Unit,
    onAddVideos: () -> Unit,
    onAddFit: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AddTile(HeadsupIcons.Video, "Videos", onAddVideos, Modifier.weight(1f))
        AddTile(HeadsupIcons.Watch, "FIT", onAddFit, Modifier.weight(1f))
    }
    val index = state.selectedClip
    val clip = state.selected
    if (index != null && clip != null) {
        val m = clip.meta
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                    Hint(
                        listOfNotNull(
                            "${m.width}×${m.height}",
                            m.frameRate?.let { String.format(Locale.US, "%.4g fps", it) },
                            m.bitrate?.let { "${it / 1_000_000} Mb/s" },
                            m.format?.toString(),
                        ).joinToString(" · "),
                    )
                }
                SmallAction("Remove", { vm.removeClip(index) })
            }
            SyncSummary(clip, clip.sync?.let { state.activities.getOrNull(it.trackIndex) })
        }
        Group {
            CutControls(
                clip = clip,
                playhead = playhead,
                onTrim = { start, end -> vm.setTrim(index, start, end) },
                onSplit = { vm.splitClip(index, it) },
                onScrub = onCutScrub,
            )
        }
        Group { StartTimeControls(clip, state.activities) { vm.setManualStart(index, it) } }
        val activity = clip.sync?.let { state.activities.getOrNull(it.trackIndex) }
        if (activity != null) {
            Group { SyncGraph(clip, activity.track, playhead) { vm.setManualOffset(index, clip.manualOffsetMs + it) } }
        }
        Group { OffsetControls(clip.manualOffsetMs) { vm.setManualOffset(index, it) } }
    }
    Group {
        Label("Activities")
        if (state.activities.isEmpty()) Hint("Add the .FIT file(s) from your watch.")
        state.activities.forEachIndexed { i, a -> ActivityRow(a, onRemove = { vm.removeActivity(i) }) }
    }
}

/** Trimming and splitting; the watch data follows every cut because the app knows where it was made. */
@Composable
private fun CutControls(
    clip: ClipState,
    playhead: LongState,
    onTrim: (Long, Long) -> Unit,
    onSplit: (Long) -> Unit,
    onScrub: (Long?) -> Unit,
) {
    val full = clip.meta.durationMs.coerceAtLeast(1L).toFloat()
    var range by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
        mutableStateOf(clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Cut", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Hint("${Formats.duration((range.endInclusive - range.start).toLong())} of ${Formats.duration(clip.meta.durationMs)}")
    }
    RangeSlider(
        value = range,
        onValueChange = {
            // The picture follows whichever handle moved.
            onScrub((if (it.start != range.start) it.start else it.endInclusive).toLong())
            range = it
        },
        valueRange = 0f..full,
        onValueChangeFinished = {
            onTrim(range.start.toLong(), range.endInclusive.toLong())
            onScrub(null)
        },
    )
    Row {
        Hint(Formats.duration(range.start.toLong()))
        Spacer(Modifier.weight(1f))
        Hint(Formats.duration(range.endInclusive.toLong()))
    }
    // "Here" is the playhead; the watch data follows every cut automatically.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onTrim(clip.trimStartMs + playhead.longValue, clip.trimEndMs) }) { Text("Start here") }
        OutlinedButton(onClick = { onTrim(clip.trimStartMs, clip.trimStartMs + playhead.longValue) }) { Text("End here") }
        OutlinedButton(onClick = { onSplit(playhead.longValue) }) { Text("Split") }
        if (clip.isTrimmed) SmallAction("Full clip", { onTrim(0L, clip.meta.durationMs) }, Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun AddTile(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(HeadsupColors.CardGradient).border(1.dp, HeadsupColors.Outline, shape).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(HeadsupColors.Highlight), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = HeadsupColors.OnHighlight)
        }
        Spacer(Modifier.width(10.dp))
        Text("+ $title", style = MaterialTheme.typography.labelLarge)
    }
}

/** Lets the user give the recording start of a clip whose file lost it, e.g. an excerpt or an edited copy. */
@Composable
private fun StartTimeControls(clip: ClipState, activities: List<LoadedActivity>, onSet: (LocalDateTime?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val activity = clip.sync?.let { activities.getOrNull(it.trackIndex) }
    val zone = activity?.track?.utcOffsetSeconds ?: activities.firstOrNull()?.track?.utcOffsetSeconds
        ?: ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Recording start")
            Hint(
                when {
                    clip.manualStart != null -> "Entered by hand: ${clip.manualStart.toString().replace('T', ' ')}"
                    clip.sync != null -> "From the file: ${Formats.date(clip.sync.startUtcMs, zone)} ${Formats.clock(clip.sync.startUtcMs, zone)}"
                    else -> "Unknown"
                },
            )
        }
        if (clip.manualStart != null) SmallAction("From file", { onSet(null) })
        SmallAction("Set", { editing = true })
    }
    if (editing) {
        val initial = clip.manualStart
            ?: clip.sync?.let { LocalDateTime.ofEpochSecond(it.startUtcMs / 1000, 0, ZoneOffset.ofTotalSeconds(zone)) }
            ?: activities.firstOrNull()?.track?.let { LocalDateTime.ofEpochSecond(it.startMs / 1000, 0, ZoneOffset.ofTotalSeconds(zone)) }
            ?: LocalDateTime.now().withNano(0)
        StartTimeDialog(initial, onDismiss = { editing = false }, onConfirm = { editing = false; onSet(it) })
    }
}

@Composable
private fun StartTimeDialog(initial: LocalDateTime, onDismiss: () -> Unit, onConfirm: (LocalDateTime) -> Unit) {
    var date by remember { mutableStateOf(initial.toLocalDate().toString()) }
    var time by remember { mutableStateOf(initial.toLocalTime().withNano(0).toString().let { if (it.length == 5) "$it:00" else it }) }
    val parsed = runCatching { LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim())) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording start") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Local time on the camera's clock when this clip begins. Fine-tune afterwards with the offset slider.")
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Time (HH:MM:SS)") },
                    singleLine = true,
                    isError = parsed == null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


@Composable
private fun ActivityRow(activity: LoadedActivity, onRemove: () -> Unit) {
    val t = activity.track
    val zone = t.utcOffsetSeconds
    val shownZone = zone ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(t.startMs)).totalSeconds
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.sportName ?: t.sport.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
            Hint(
                "${Formats.date(t.startMs, shownZone)}  ${Formats.clock(t.startMs, shownZone)}–${Formats.clock(t.endMs, shownZone)}" +
                    " · " + (if (zone != null) Formats.utcOffset(zone) else "phone time zone"),
            )
        }
        SmallAction("Remove", onRemove)
    }
}

@Composable
private fun SyncSummary(clip: ClipState, activity: LoadedActivity?) {
    val sync = clip.sync
    if (sync == null || activity == null) {
        Text(
            if (clip.timeInfo.evidence.isEmpty()) "No recording time in the file – set the start time by hand" else "No activity covers this clip",
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
    TimeSource.MANUAL -> "time entered by hand"
}

private fun zoneLabel(reason: ZoneReason, offset: Int) = when (reason) {
    ZoneReason.AS_UTC -> "UTC"
    ZoneReason.ACTIVITY_ZONE -> "watch time zone (${Formats.utcOffset(offset)})"
    ZoneReason.DEVICE_ZONE -> "phone time zone (${Formats.utcOffset(offset)})"
    ZoneReason.SEARCHED -> "guessed time zone (${Formats.utcOffset(offset)})"
}

/**
 * Sync by picking a moment: the graph shows the data around what the playhead is matched to now
 * (centre line); tapping the same moment on the graph (a stop, a sprint, the top of a climb) and
 * applying shifts the data by the difference.
 */
@Composable
private fun SyncGraph(clip: ClipState, track: ActivityTrack, playhead: LongState, onShift: (Long) -> Unit) {
    val metrics = GraphMetric.entries.filter { track.has(it.metric) }
    if (metrics.isEmpty()) return
    var chosen by rememberSaveable { mutableStateOf(metrics.first()) }
    val metric = chosen.takeIf { it in metrics } ?: metrics.first()
    var windowMs by rememberSaveable { mutableStateOf(30_000L) }
    // Picked moment relative to the centre, in ms.
    var picked by remember(clip.id, clip.manualOffsetMs) { mutableStateOf<Long?>(null) }
    val centre = clip.telemetryUtc(playhead.longValue) ?: return
    val series = track.series.getValue(metric.metric)

    Label("Sync from the graph")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(metrics) { m -> FilterChip(selected = m == metric, onClick = { chosen = m }, label = { Text(m.label) }) }
        item { FilterChip(selected = windowMs == 30_000L, onClick = { windowMs = 30_000L; picked = null }, label = { Text("±30 s") }) }
        item { FilterChip(selected = windowMs == 120_000L, onClick = { windowMs = 120_000L; picked = null }, label = { Text("±2 min") }) }
    }
    val from = centre - windowMs
    val to = centre + windowMs
    var lo = java.util.Arrays.binarySearch(series.timesMs, from).let { if (it < 0) -it - 1 else it }
    lo = (lo - 1).coerceAtLeast(0)
    var hi = java.util.Arrays.binarySearch(series.timesMs, to).let { if (it < 0) -it - 1 else it }
    hi = (hi + 1).coerceAtMost(series.size)
    val accent = HeadsupColors.Accent
    val pick = { x: Float, width: Float -> picked = ((x / width - 0.5f) * 2 * windowMs).toLong() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(HeadsupColors.Surface)
            .pointerInput(windowMs) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(windowMs) {
                detectHorizontalDragGestures { change, _ -> change.consume(); pick(change.position.x, size.width.toFloat()) }
            },
    ) {
        fun xOf(t: Long) = (t - from).toFloat() / (to - from) * size.width
        if (hi - lo >= 2) {
            var min = Double.MAX_VALUE
            var max = -Double.MAX_VALUE
            for (i in lo until hi) {
                val v = series.values[i]
                if (v < min) min = v
                if (v > max) max = v
            }
            val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
            val pad = 8.dp.toPx()
            val path = Path()
            for (i in lo until hi) {
                val x = xOf(series.timesMs[i])
                val y = pad + (1f - ((series.values[i] - min) / span).toFloat()) * (size.height - 2 * pad)
                if (i == lo) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
        }
        drawLine(Color.White.copy(alpha = 0.4f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        picked?.let { d ->
            val x = xOf(centre + d)
            drawLine(accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(
            picked?.let { String.format(Locale.US, "Shift %+.1f s", it / 1000.0) }
                ?: "Pause on a clear moment, tap the same one on the graph.",
            Modifier.weight(1f),
        )
        picked?.let { d -> SmallAction("Apply", { onShift(d); picked = null }) }
    }
}

@Composable
private fun OffsetControls(offsetMs: Long, onChange: (Long) -> Unit) {
    ValueHeader("Data offset", String.format(Locale.US, "%+.1f s", offsetMs / 1000.0))
    RulerSlider(
        value = offsetMs / 1000f,
        range = -600f..600f,
        step = 0.1f,
        onChange = { onChange((it * 10).roundToLong() * 100) },
        onReset = { onChange(0L) },
    )
}

// Colour page

/** Copy the selected clip's look (corrections, LUT, effects), paste it on another, or copy it to every clip. */
@Composable
private fun LookActions(state: UiState, vm: HeadsupViewModel) {
    val index = state.selectedClip ?: return
    SmallAction("Copy", vm::copyLook)
    if (state.copiedLook != null) SmallAction("Paste", { vm.pasteLook(index) })
    if (state.clips.size > 1) SmallAction("Copy to all", vm::applyLookToAll)
}

internal enum class Adjustment(
    val symbol: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: String,
    val get: (ColorGrade) -> Float,
    val set: (ColorGrade, Float) -> ColorGrade,
) {
    EXPOSURE("EV", "Exposure", -2f..2f, 0.1f, "%+.1f EV", { it.exposure }, { g, v -> g.copy(exposure = v) }),
    BRIGHTNESS("Br", "Brightness", -1f..1f, 0.02f, "%+.2f", { it.brightness }, { g, v -> g.copy(brightness = v) }),
    CONTRAST("Ct", "Contrast", -1f..1f, 0.02f, "%+.2f", { it.contrast }, { g, v -> g.copy(contrast = v) }),
    HIGHLIGHTS("Hl", "Highlights", -1f..1f, 0.02f, "%+.2f", { it.highlights }, { g, v -> g.copy(highlights = v) }),
    SHADOWS("Sh", "Shadows", -1f..1f, 0.02f, "%+.2f", { it.shadows }, { g, v -> g.copy(shadows = v) }),
    WHITES("Wh", "Whites", -1f..1f, 0.02f, "%+.2f", { it.whites }, { g, v -> g.copy(whites = v) }),
    BLACKS("Bk", "Blacks", -1f..1f, 0.02f, "%+.2f", { it.blacks }, { g, v -> g.copy(blacks = v) }),
    SATURATION("Sat", "Saturation", -1f..1f, 0.02f, "%+.2f", { it.saturation }, { g, v -> g.copy(saturation = v) }),
    VIBRANCE("Vib", "Vibrance", -1f..1f, 0.02f, "%+.2f", { it.vibrance }, { g, v -> g.copy(vibrance = v) }),
    TEMPERATURE("K", "Temperature", -1f..1f, 0.02f, "%+.2f", { it.temperature }, { g, v -> g.copy(temperature = v) }),
    TINT("Tn", "Tint", -1f..1f, 0.02f, "%+.2f", { it.tint }, { g, v -> g.copy(tint = v) }),
    SKIN("Sk", "Skin tone", -30f..30f, 0.5f, "%+.1f°", { it.skinHue }, { g, v -> g.copy(skinHue = v) }),
    VIGNETTE("Vig", "Vignette", 0f..1f, 0.02f, "%.2f", { it.vignette }, { g, v -> g.copy(vignette = v) }),

    /** Not a ruler value: opens the LUT library (the LUT is applied last, after all corrections). */
    LUT("LUT", "LUT", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),
}

@Composable
private fun ColourPage(
    state: UiState,
    vm: HeadsupViewModel,
    adjustment: Adjustment,
    onAdjustment: (Adjustment) -> Unit,
    onAddLut: () -> Unit,
) {
    val index = state.selectedClip
    val clip = state.selected
    if (index == null || clip == null) {
        Hint("Select a clip first.")
        return
    }
    val target = state.target
    PillTabs(listOf("This clip", "Group", "Sky mask", "All clips"), target.ordinal) { vm.setTarget(GradeTarget.entries[it]) }
    val group = state.groupOf(clip)
    when (target) {
        GradeTarget.CLIP -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (clip.colorGrade) LookActions(state, vm)
            Spacer(Modifier.weight(1f))
            if (clip.colorGrade && state.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
            Switch(checked = clip.colorGrade, onCheckedChange = { vm.setClipGrade(index, it) })
        }
        GradeTarget.GROUP -> GroupControls(state, clip, index, group, vm)
        GradeTarget.MASK -> Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Graduated filter: own corrections above the line.", Modifier.weight(1f))
            if (clip.mask != null) SmallAction("Remove", { vm.updateMask { null } })
        }
        GradeTarget.GLOBAL -> Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Added on top of every clip's own corrections.", Modifier.weight(1f))
            if (state.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
        }
    }
    if (target != GradeTarget.GLOBAL && !clip.colorGrade) {
        Hint("Colour off: the clip keeps its own colours (for already graded files, e.g. Mimo exports).")
        return
    }
    if (target == GradeTarget.GROUP && group == null) return
    if (target == GradeTarget.MASK && clip.mask == null) {
        Button(onClick = { vm.updateMask { GradientMask() } }, modifier = Modifier.fillMaxWidth()) { Text("Add graduated mask") }
        return
    }

    // The ruler sits right under the correction picker, so nothing needs scrolling.
    // The LUT belongs to the clip (it depends on how the clip was shot); the mask has only tonal
    // and colour corrections, with its shape in front.
    val options = when (target) {
        GradeTarget.CLIP, GradeTarget.GROUP -> Adjustment.entries
        GradeTarget.GLOBAL -> Adjustment.entries - Adjustment.LUT
        GradeTarget.MASK -> Adjustment.entries - Adjustment.LUT - Adjustment.SKIN - Adjustment.VIGNETTE
    }
    val adjustment = if (adjustment in options) adjustment else Adjustment.EXPOSURE
    var shape by rememberSaveable { mutableStateOf<MaskShape?>(MaskShape.POSITION) }
    val mask = clip.mask
    val editingShape = if (target == GradeTarget.MASK && mask != null) shape else null
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (target == GradeTarget.MASK) {
            items(MaskShape.entries) { m ->
                CircleOption(m.symbol, m.label, selected = m == editingShape, modified = false) { shape = m }
            }
        }
        items(options) { a ->
            val lut = if (target == GradeTarget.GROUP) group?.lutName else clip.lutName
            val modified = if (a == Adjustment.LUT) lut != null else a.get(state.grade) != 0f
            CircleOption(a.symbol, a.label, selected = editingShape == null && a == adjustment, modified = modified) {
                shape = null
                onAdjustment(a)
            }
        }
    }
    if (editingShape != null && mask != null) {
        ValueHeader(editingShape.label, String.format(Locale.US, editingShape.format, editingShape.shown(mask)))
        RulerSlider(
            value = editingShape.get(mask),
            range = editingShape.range,
            step = editingShape.step,
            onChange = { v -> vm.updateMask { m -> m?.let { editingShape.set(it, v) } } },
            onReset = { vm.updateMask { m -> m?.let { editingShape.set(it, editingShape.get(GradientMask())) } } },
        )
        return
    }
    if (adjustment == Adjustment.LUT) {
        if (target == GradeTarget.GROUP && group != null) {
            LutControls(state, group.lutName, "None", { vm.setGroupLut(group.id, it) }, null, vm, onAddLut)
        } else {
            val inherited = group?.lutName
            LutControls(
                state, clip.lutName, if (inherited != null) "Group's ($inherited)" else "None", { vm.setClipLut(index, it) },
                if (state.clips.size > 1) vm::setLutForAllClips else null, vm, onAddLut,
            )
        }
        return
    }
    if (adjustment == Adjustment.SKIN) SkinToneTool(state.scope, onMatch = vm::matchSkinLine)
    // Double-tap the ruler to reset, hold it to see the picture without this correction.
    ValueHeader(adjustment.label, String.format(Locale.US, adjustment.format, adjustment.get(state.grade)))
    RulerSlider(
        value = adjustment.get(state.grade),
        range = adjustment.range,
        step = adjustment.step,
        onChange = { v -> vm.updateGrade { adjustment.set(it, v) } },
        onReset = { vm.updateGrade { adjustment.set(it, 0f) } },
        onHold = { held -> vm.setCompareGrade(if (held) adjustment.set(state.grade, 0f) else null) },
    )
}

/** Shape controls of the graduated mask, edited with the same ruler as the corrections. */
private enum class MaskShape(
    val symbol: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: String,
    val get: (GradientMask) -> Float,
    val set: (GradientMask, Float) -> GradientMask,
) {
    POSITION("↕", "Position", 0f..1f, 0.01f, "%.0f %%", { it.position }, { m, v -> m.copy(position = v) }),
    SOFTNESS("◐", "Softness", 0.02f..1f, 0.02f, "%.0f %%", { it.softness }, { m, v -> m.copy(softness = v) }),
    ANGLE("∠", "Angle", -45f..45f, 0.5f, "%+.1f°", { it.angleDegrees }, { m, v -> m.copy(angleDegrees = v) }),
    ;

    /** Value as shown above the ruler (fractions as percent). */
    fun shown(mask: GradientMask): Float = if (this == ANGLE) get(mask) else get(mask) * 100f
}

/** LUT library: the camera LUT is the last step, after all corrections. */
@Composable
private fun LutControls(
    state: UiState,
    current: String?,
    noneLabel: String,
    onPick: (String?) -> Unit,
    onAll: ((String?) -> Unit)?,
    vm: HeadsupViewModel,
    onAddLut: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current == null, onClick = { onPick(null) }, label = { Text(noneLabel) })
        for (name in state.luts) {
            FilterChip(selected = current == name, onClick = { onPick(name) }, label = { Text(name) })
        }
        FilterChip(selected = false, onClick = onAddLut, label = { Text("+ .cube") })
    }
    Row {
        if (onAll != null) SmallAction("Use for all clips", { onAll(current) })
        Spacer(Modifier.weight(1f))
        current?.let { name -> SmallAction("Delete $name", { vm.deleteLut(name) }) }
    }
}

/**
 * The selected clip's group and the list of groups: tap one to move the clip there. A clip is in
 * at most one group, so its colours always come from clip → group → all clips.
 */
@Composable
private fun GroupControls(state: UiState, clip: ClipState, index: Int, group: ClipGroup?, vm: HeadsupViewModel) {
    var renaming by remember { mutableStateOf(false) }
    if (group == null) Hint("Clips shot the same way (e.g. drone) share a LUT and corrections. Long-press a clip to add it.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in state.groups) {
            val count = state.clips.count { it.groupId == g.id }
            FilterChip(
                selected = g.id == clip.groupId,
                onClick = { vm.setClipGroup(index, if (g.id == clip.groupId) null else g.id) },
                label = { Text("${g.name} · $count") },
            )
        }
        FilterChip(selected = false, onClick = { vm.createGroup(index) }, label = { Text("+ New group") })
    }
    if (group != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAction("Rename", { renaming = true })
            SmallAction("Leave", { vm.setClipGroup(index, null) })
            SmallAction("Delete group", { vm.deleteGroup(group.id) })
            Spacer(Modifier.weight(1f))
            if (group.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
        }
        if (renaming) RenameDialog(group.name, onDismiss = { renaming = false }) { vm.renameGroup(group.id, it); renaming = false }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group name") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Vectorscope with the skin line, as in DaVinci Resolve, and the measurement of the tapped point. */
@Composable
private fun SkinToneTool(scope: ScopeResult?, onMatch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VectorscopeView(scope, Modifier.size(140.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val measured = scope?.measured
            if (measured == null) {
                Hint(if (scope == null) "Tap a face on the picture to measure skin tone." else "That point is too grey to measure; tap skin.")
            } else {
                val off = SkinTone.angleDifference(measured.angleDegrees, SkinTone.LINE_DEGREES)
                Text(String.format(Locale.US, "%.0f°", measured.angleDegrees), style = MaterialTheme.typography.headlineSmall)
                Hint(
                    String.format(Locale.US, "%+.0f° from the skin line (%s)", off, if (off > 0) "towards yellow/green" else "towards red/magenta"),
                )
            }
            Button(onClick = onMatch, enabled = measured != null) { Text("Match line") }
        }
    }
}

@Composable
private fun VectorscopeView(scope: ScopeResult?, modifier: Modifier) {
    val image = remember(scope) { scope?.image?.asImageBitmap() }
    Canvas(modifier.clip(CircleShape).background(Color(0xFF0D0B0A)).border(1.dp, HeadsupColors.Outline, CircleShape)) {
        val r = size.minDimension / 2
        val c = center
        image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) }
        // Graticule.
        for (ring in listOf(0.5f, 1f)) drawCircle(Color.White.copy(alpha = 0.12f), radius = r * ring - 1, center = c, style = Stroke(1f))
        drawLine(Color.White.copy(alpha = 0.10f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(Color.White.copy(alpha = 0.10f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        // Skin line at 123° (counter-clockwise from +Cb, screen y points down).
        val a = Math.toRadians(SkinTone.LINE_DEGREES)
        drawLine(HeadsupColors.Accent, c, Offset(c.x + (cos(a) * r).toFloat(), c.y - (sin(a) * r).toFloat()), strokeWidth = 2f)
        scope?.measured?.let { m ->
            val k = r / Vectorscope.RANGE.toFloat()
            val p = Offset(c.x + (m.cb * k).toFloat(), c.y - (m.cr * k).toFloat())
            drawCircle(Color.White, radius = 5.dp.toPx(), center = p, style = Stroke(2f))
        }
    }
}

// Effects page

private enum class EffectKind(val symbol: String, val label: String) {
    DASHBOARD("HUD", "Dashboard"),
    GRAPH("Gr", "Graph"),
    GRAIN("Gn", "Grain"),
    GLOW("Gl", "Glow"),
    BARS("Cin", "Bars"),
}

/** Effects over the picture, picked from round options like the colour corrections. */
@Composable
private fun EffectsPanel(state: UiState, vm: HeadsupViewModel) {
    val style = state.style
    val effects = state.effects
    val activities = state.activities
    val updateStyle = vm::updateStyle
    val updateEffects = vm::updateEffects
    var kind by rememberSaveable { mutableStateOf(EffectKind.DASHBOARD) }
    if (state.selected != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Effects of this clip", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            LookActions(state, vm)
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(EffectKind.entries) { e ->
            val active = when (e) {
                EffectKind.DASHBOARD -> style.enabled
                EffectKind.GRAPH -> effects.graph != null
                EffectKind.GRAIN -> effects.grain > 0f
                EffectKind.GLOW -> effects.glow > 0f
                EffectKind.BARS -> effects.barsRatio != null
            }
            CircleOption(e.symbol, e.label, selected = e == kind, modified = active) { kind = e }
        }
    }
    when (kind) {
        EffectKind.DASHBOARD -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dashboard", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Switch(checked = style.enabled, onCheckedChange = { v -> updateStyle { it.copy(enabled = v) } })
            }
            if (style.enabled) {
                var tab by rememberSaveable { mutableStateOf(0) }
                PillTabs(listOf("Widgets", "Layout", "Text"), tab) { tab = it }
                when (tab) {
                    0 -> WidgetControls(style, activities, updateStyle)
                    1 -> LayoutControls(style, updateStyle)
                    else -> TextControls(style, updateStyle)
                }
            }
        }
        EffectKind.GRAPH -> {
            Hint("Activity profile along the bottom, with a moving marker.")
            val available = GraphMetric.entries.filter { m -> activities.isEmpty() || activities.any { it.track.has(m.metric) } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = effects.graph == null, onClick = { updateEffects { it.copy(graph = null) } }, label = { Text("Off") }) }
                items(available) { m ->
                    FilterChip(selected = effects.graph == m, onClick = { updateEffects { it.copy(graph = m) } }, label = { Text(m.label) })
                }
            }
        }
        EffectKind.GRAIN -> {
            ValueHeader("Grain", String.format(Locale.US, "%.0f %%", effects.grain * 100))
            RulerSlider(effects.grain, 0f..1f, 0.02f, { v -> updateEffects { it.copy(grain = v) } }, onReset = { updateEffects { it.copy(grain = 0f) } })
            ValueHeader("Size", String.format(Locale.US, "%.1f", effects.grainSize))
            RulerSlider(effects.grainSize, 0.5f..3f, 0.1f, { v -> updateEffects { it.copy(grainSize = v) } }, onReset = { updateEffects { it.copy(grainSize = 1f) } })
        }
        EffectKind.GLOW -> {
            ValueHeader("Glow", String.format(Locale.US, "%.0f %%", effects.glow * 100))
            RulerSlider(effects.glow, 0f..1f, 0.02f, { v -> updateEffects { it.copy(glow = v) } }, onReset = { updateEffects { it.copy(glow = 0f) } })
            ValueHeader("Threshold", String.format(Locale.US, "%.0f %%", effects.glowThreshold * 100))
            RulerSlider(effects.glowThreshold, 0.4f..0.98f, 0.02f, { v -> updateEffects { it.copy(glowThreshold = v) } }, onReset = { updateEffects { it.copy(glowThreshold = 0.75f) } })
        }
        EffectKind.BARS -> {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = effects.barsRatio == null, onClick = { updateEffects { it.copy(barsRatio = null) } }, label = { Text("Off") }) }
                items(listOf(2.39f to "2.39:1", 2.0f to "2:1", 1.85f to "1.85:1")) { (ratio, label) ->
                    FilterChip(selected = effects.barsRatio == ratio, onClick = { updateEffects { it.copy(barsRatio = ratio) } }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun WidgetControls(style: DashboardStyle, activities: List<LoadedActivity>, update: ((DashboardStyle) -> DashboardStyle) -> Unit) {
    val available = Widget.entries.filter { w -> activities.isEmpty() || activities.any { w.isAvailableIn(it.track) } }
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
}

@Composable
private fun LayoutControls(style: DashboardStyle, update: ((DashboardStyle) -> DashboardStyle) -> Unit) {
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
    if (style.placements.isNotEmpty()) Hint("${style.placements.size} placed by hand; pick a layout to reset.")
    ValueHeader("Size", String.format(Locale.US, "%.0f %%", style.scale * 100))
    RulerSlider(
        value = style.scale,
        range = 0.5f..2f,
        step = 0.02f,
        onChange = { v -> update { it.copy(scale = v) } },
        onReset = { update { it.copy(scale = 1f) } },
    )
}

@Composable
private fun TextControls(style: DashboardStyle, update: ((DashboardStyle) -> DashboardStyle) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(DashboardFont.entries) { font ->
            FilterChip(selected = style.font == font, onClick = { update { it.copy(font = font) } }, label = { Text(font.label) })
        }
    }
    ColorRow("Values", style.valueColor) { c -> update { it.copy(valueColor = c) } }
    ColorRow("Labels", style.labelColor) { c -> update { it.copy(labelColor = c) } }
    ColorRow("Units", style.unitColor) { c -> update { it.copy(unitColor = c) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Edge", style = MaterialTheme.typography.labelLarge)
        for (effect in TextEffect.entries) {
            FilterChip(selected = style.effect == effect, onClick = { update { it.copy(effect = effect) } }, label = { Text(effect.label) })
        }
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
    sourceBitrate: Int?,
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
        for ((factor, name) in listOf(1f to "Same as source", 1.5f to "1.5×", 2f to "2×")) {
            // Mirrors ExportRequest.targetBitrate for the selected clip.
            val mbps = sourceBitrate?.let { (it * factor / 1_000_000).toInt().coerceIn(10, 200) }
            FilterChip(
                selected = bitrateFactor == factor,
                onClick = { onBitrateFactor(factor) },
                label = { Text(if (mbps != null) "$name · $mbps Mb/s" else name) },
            )
        }
    }
    Button(onClick = onExportVideos, enabled = canExport, modifier = Modifier.fillMaxWidth()) { Text("Export videos with dashboard") }
    Hint("HEVC, source resolution and frame rate → DCIM/TelemetryOverlay")
    OutlinedButton(onClick = onExportOverlays, enabled = canExport, modifier = Modifier.fillMaxWidth()) { Text("Export dashboard only (PNG)") }
    Hint("Transparent PNG frames (ZIP) for DaVinci → Download/TelemetryOverlay")
    OutlinedButton(onClick = onTenBitTest, enabled = canTest, modifier = Modifier.fillMaxWidth()) { Text("Test 10-bit export") }
    Hint("First 3 s of the selected clip; nothing is saved.")

    if (jobs.isNotEmpty()) {
        jobs.forEach { JobRow(it) }
        if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
            SmallAction("Cancel all", onCancel)
        } else {
            SmallAction("Clear list", onClear)
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
