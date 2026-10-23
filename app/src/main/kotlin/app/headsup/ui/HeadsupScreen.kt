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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKind
import app.headsup.core.frame.Reframe
import app.headsup.media.ClipMetadataReader
import app.headsup.core.highlight.HighlightFinder
import app.headsup.core.highlight.HighlightKind
import app.headsup.core.track.Metric
import app.headsup.core.color.CineStyle
import android.graphics.Bitmap
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
import app.headsup.export.MediaStoreOutput
import app.headsup.export.ExportSettings
import app.headsup.export.OutputSpec
import app.headsup.export.VideoCodec
import app.headsup.export.MusicTrack
import app.headsup.export.HdrRoute
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import app.headsup.render.DashboardFont
import app.headsup.render.EffectSettings
import app.headsup.render.GraphMetric
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.Reading
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
    AUDIO("Audio", HeadsupIcons.Music),
    COLOUR("Colour", HeadsupIcons.Colour),
    EFFECTS("Effects", HeadsupIcons.Dashboard),
    FRAME("Frame", HeadsupIcons.Frame),
    MOMENTS("Moments", HeadsupIcons.Star),
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
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::setMusic) }
    val addFit = { fitPicker.launch(arrayOf("*/*")) }
    val addVideos = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

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
    var effectKind by rememberSaveable { mutableStateOf(EffectKind.DASHBOARD) }
    val framing = tool == Tool.FRAME
    // Set when a clip ended during playback, so the next one starts playing on its own.
    var continuePlaying by remember { mutableStateOf(false) }
    // Where to jump in the selected clip, e.g. after tapping a highlight.
    var seekRequest by remember { mutableStateOf<Long?>(null) }
    val measuring = tool == Tool.COLOUR && adjustment == Adjustment.SKIN && state.target != GradeTarget.MASK
    val editingMasks = tool == Tool.COLOUR && state.target == GradeTarget.MASK && state.selected?.colorGrade == true
    // Read only where needed (cut controls), so playback does not recompose the whole screen.
    val playhead = remember { mutableLongStateOf(0L) }
    // File position of a cut handle being dragged; read only by the preview.
    val cutScrub = remember { mutableStateOf<Long?>(null) }
    // Grabs the paused preview frame, for the cine assistant.
    val frameSource = remember { mutableStateOf<(() -> Bitmap?)?>(null) }
    val exporting = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    val previewMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    TenBitResultDialog(jobs)

    Box(Modifier.fillMaxSize().background(HeadsupColors.BackgroundGradient)) {
        // No title bar: the picture starts at the top, adding files lives in the Files tool.
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 8.dp)) {
            if (state.busy) {
                CineLoader(
                    state.loadingMessage ?: "Working…", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    progress = state.loadingProgress,
                    onCancel = if (state.loadingMessage?.startsWith("Copying") == true) vm::cancelLoading else null,
                )
            }

            // The picture stays on screen while any tool below is being used.
            val selected = state.selected
            when {
                selected == null -> EmptyState(hasActivities = state.activities.isNotEmpty(), onAddFit = addFit, onAddVideos = addVideos)
                exporting -> Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(HeadsupColors.Surface),
                    contentAlignment = Alignment.Center,
                ) { Hint("Preview paused while exporting") }
                else -> Box {
                    ClipPreview(
                        clip = selected,
                        activity = selected.sync?.let { state.activities.getOrNull(it.trackIndex) },
                        style = state.style,
                        gradeCube = state.previewCube,
                        masks = if (selected.colorGrade) selected.masks else emptyList(),
                        maskCubes = state.previewMaskCubes,
                        fromCube = state.previewFromCube,
                        transitionMs = selected.transitionMs,
                        guideMask = selected.masks.getOrNull(state.selectedMask).takeIf { editingMasks },
                        onMoveMask = if (editingMasks) { x, y -> vm.updateMask { it.copy(centerX = x, centerY = y) } } else null,
                        vignette = if (selected.colorGrade) state.appliedGrade(selected).vignette else 0f,
                        effects = state.compareEffects ?: state.effects,
                        onSample = if (measuring) vm::sampleFrame else null,
                        marker = if (measuring) state.scope?.let { Offset(it.markerX, it.markerY) } else null,
                        maxHeight = previewMaxHeight,
                        onMoveWidget = vm::moveWidget,
                        onMoveFinished = vm::persistStyle,
                        onPosition = { playhead.longValue = it },
                        cutScrub = cutScrub,
                        onFrameSource = { frameSource.value = it },
                        reframe = selected.reframe,
                        onReframe = if (framing) { r -> vm.updateReframe { r } } else null,
                        showGrid = framing,
                        autoPlay = continuePlaying,
                        seekRequest = seekRequest,
                        onSeekDone = { seekRequest = null },
                        music = state.music,
                        timelineOffsetMs = state.clips.take(state.selectedClip ?: 0).sumOf { it.timelineDurationMs },
                        timelineTotalMs = state.clips.sumOf { it.timelineDurationMs },
                        onAutoPlayStarted = { continuePlaying = false },
                        onEnded = {
                            // The timeline plays through: the next clip follows.
                            val next = (state.selectedClip ?: -1) + 1
                            if (next in state.clips.indices) {
                                continuePlaying = true
                                vm.selectClip(next)
                            }
                        },
                    )
                    // Undo/redo tucked into the picture's corner, only when there is something to undo.
                    UndoRedo(state.canUndo, state.canRedo, vm::undo, vm::redo, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 24.dp))
                }
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
                    Tool.FILES -> FilesPanel(
                        state, vm, playhead, onCutScrub = { cutScrub.value = it }, onAddVideos = addVideos, onAddFit = addFit,
                        onAddMusic = { musicPicker.launch(arrayOf("audio/*")) },
                    )
                    Tool.AUDIO -> AudioPanel(state, vm, onAddMusic = { musicPicker.launch(arrayOf("audio/*")) })
                    Tool.MOMENTS -> HighlightControls(state, vm) { f ->
                        val index = state.clips.indexOfFirst { it.id == f.clipId }
                        if (index >= 0) {
                            if (index != state.selectedClip) vm.selectClip(index)
                            seekRequest = f.positionMs
                        }
                    }
                    Tool.COLOUR -> ColourPage(state, vm, adjustment, { adjustment = it }, onAddLut = { lutPicker.launch(arrayOf("*/*")) }, frameSource.value)
                    Tool.EFFECTS -> EffectsPanel(state, vm, effectKind) { effectKind = it }
                    Tool.FRAME -> FramePanel(state, vm)
                    Tool.EXPORT -> ExportControls(
                        state = state,
                        vm = vm,
                        jobs = jobs,
                        onExportVideos = { startExport(vm::exportVideos) },
                        onExportOverlays = { startExport(vm::exportOverlays) },
                        onTenBitTest = { startExport(vm::runTenBitTest) },
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
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted, maxLines = 1, softWrap = false)
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

/** Two small, quiet arrows over the picture; hidden until there is something to undo. */
@Composable
private fun UndoRedo(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, modifier: Modifier) {
    if (!canUndo && !canRedo) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((icon, enabled, action, label) in listOf(
            Quad(HeadsupIcons.Undo, canUndo, onUndo, "Undo"),
            Quad(HeadsupIcons.Redo, canRedo, onRedo, "Redo"),
        )) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(enabled = enabled, onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, Modifier.size(16.dp), tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f))
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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
    onAddMusic: () -> Unit,
) {
    ProjectRow(state, vm)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AddTile(HeadsupIcons.Video, "Media", onAddVideos, Modifier.weight(1f))
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
                            frameCount(clip.durationMs, m.frameRate)?.let { "$it frames" },
                            m.bitrate?.let { "${it / 1_000_000} Mb/s" },
                            m.format?.toString(),
                        ).joinToString(" · "),
                    )
                }
                SmallAction("Remove", { vm.removeClip(index) })
            }
            SyncSummary(clip, clip.sync?.let { state.activities.getOrNull(it.trackIndex) })
        }
        if (clip.meta.isPhoto) {
            Group {
                // A photo has no cut: its length on the timeline is chosen here.
                ValueHeader("Photo on screen", String.format(Locale.US, "%.1f s", clip.durationMs / 1000f))
                RulerSlider(
                    clip.durationMs / 1000f, 1f..(ClipMetadataReader.PHOTO_MAX_MS / 1000f), 0.5f,
                    { v -> vm.setTrim(index, 0L, (v * 1000).toLong()) },
                    onReset = { vm.setTrim(index, 0L, HeadsupViewModel.PHOTO_DEFAULT_MS) },
                )
                Hint(if (clip.sync != null) "The dashboard shows the moment it was taken." else "No time found in the photo; set it below to show watch data.")
            }
        } else Group {
            CutControls(
                clip = clip,
                playhead = playhead,
                onTrim = { start, end -> vm.setTrim(index, start, end) },
                onSplit = { vm.splitClip(index, it) },
                onScrub = onCutScrub,
            )
        }
        if (!clip.meta.isPhoto) Group { SpeedControls(clip, state.effectiveTimelineFps, onSpeed = { vm.setSpeed(index, it) }, onMute = { vm.setMuteChangedSpeed(index, it) }) }
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
    Group { MusicFile(state, vm, onAddMusic) }
}

/** The music file itself: added, changed or removed here; what it does is on the Audio page. */
@Composable
private fun MusicFile(state: UiState, vm: HeadsupViewModel, onAdd: () -> Unit) {
    val music = state.music
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Music")
            Hint(music?.let { it.name + (if (it.durationMs > 0) " · " + Formats.duration(it.durationMs) else "") } ?: "A track under the timeline; placed and mixed on the Audio page.")
        }
        if (music != null) SmallAction("Remove", vm::removeMusic)
        SmallAction(if (music == null) "+ Add" else "Change", onAdd, enabled = !state.busy)
    }
}

/**
 * Sound: the music's place on the timeline (where it starts, which part of the song, repeating),
 * fades only where asked, and the levels of the music and the clips' own sound.
 */
@Composable
private fun AudioPanel(state: UiState, vm: HeadsupViewModel, onAddMusic: () -> Unit) {
    val music = state.music
    Group { MusicFile(state, vm, onAddMusic) }
    if (music == null) return
    val totalMs = state.clips.sumOf { it.timelineDurationMs }
    Group {
        Label("On the timeline")
        MusicTimeline(music, totalMs)
        val totalS = (totalMs / 1000f).coerceAtLeast(1f)
        ValueHeader("Starts at", Formats.duration(music.startOnTimelineMs))
        RulerSlider(
            music.startOnTimelineMs / 1000f, 0f..totalS, 0.1f,
            { v -> vm.updateMusic { it.copy(startOnTimelineMs = (v * 1000).toLong()) } },
            onReset = { vm.updateMusic { it.copy(startOnTimelineMs = 0L) } },
        )
        if (music.durationMs > 0) {
            ValueHeader("From the song at", Formats.duration(music.songStartMs))
            RulerSlider(
                music.songStartMs / 1000f, 0f..((music.durationMs - 1000) / 1000f).coerceAtLeast(0.1f), 0.1f,
                { v -> vm.updateMusic { it.copy(songStartMs = (v * 1000).toLong()) } },
                onReset = { vm.updateMusic { it.copy(songStartMs = 0L) } },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Repeat", style = MaterialTheme.typography.bodyMedium)
                Hint(if (music.loop) "Starts again when the song ends." else "Stops when the song ends.")
            }
            Switch(checked = music.loop, onCheckedChange = { v -> vm.updateMusic { it.copy(loop = v) } })
        }
        Hint("Music longer than the timeline is cut where the timeline ends, at full level unless a fade is set.")
    }
    Group {
        Label("Fades")
        ValueHeader("Fade in", fadeLabel(music.fadeInMs))
        RulerSlider(music.fadeInMs / 1000f, 0f..10f, 0.1f, { v -> vm.updateMusic { it.copy(fadeInMs = (v * 1000).toLong()) } }, onReset = { vm.updateMusic { it.copy(fadeInMs = 0L) } })
        ValueHeader("Fade out", fadeLabel(music.fadeOutMs))
        RulerSlider(music.fadeOutMs / 1000f, 0f..10f, 0.1f, { v -> vm.updateMusic { it.copy(fadeOutMs = (v * 1000).toLong()) } }, onReset = { vm.updateMusic { it.copy(fadeOutMs = 0L) } })
    }
    Group {
        Label("Levels")
        ValueHeader("Music", String.format(Locale.US, "%.0f %%", music.volume * 100))
        RulerSlider(music.volume, 0f..1f, 0.02f, { v -> vm.updateMusic { it.copy(volume = v) } }, onReset = { vm.updateMusic { it.copy(volume = 0.8f) } })
        ValueHeader("Clip sound", String.format(Locale.US, "%.0f %%", music.originalVolume * 100))
        RulerSlider(
            music.originalVolume, 0f..1f, 0.02f, { v -> vm.updateMusic { it.copy(originalVolume = v) } },
            onReset = { vm.updateMusic { it.copy(originalVolume = 0.4f) } },
        )
    }
}

private fun fadeLabel(ms: Long) = if (ms <= 0) "Off" else String.format(Locale.US, "%.1f s", ms / 1000f)

/** The timeline as a bar with the clips, and the music under it: each repeat of the song, fades as ramps. */
@Composable
private fun MusicTimeline(music: MusicTrack, totalMs: Long) {
    val segments = remember(music, totalMs) { music.placement.segments(totalMs) }
    val clipColor = HeadsupColors.SurfaceHigh
    val musicColor = HeadsupColors.Accent
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        if (totalMs <= 0) return@Canvas
        val w = size.width
        fun x(ms: Long) = ms.toFloat() / totalMs * w
        drawRoundRect(clipColor, Offset(0f, 0f), Size(w, 14.dp.toPx()), CornerRadius(4.dp.toPx()))
        val top = 20.dp.toPx()
        val h = size.height - top
        for (seg in segments) {
            val left = x(seg.timelineStartMs)
            val right = x(seg.timelineStartMs + seg.lengthMs)
            drawRoundRect(musicColor.copy(alpha = 0.35f), Offset(left, top), Size((right - left - 2f).coerceAtLeast(1f), h), CornerRadius(4.dp.toPx()))
        }
        val placement = music.placement
        // Level line: full height = full level, ramps where it fades.
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val t = totalMs * i / steps
            val y = top + h * (1f - placement.gainAt(t, totalMs))
            if (i == 0) path.moveTo(x(t), y) else path.lineTo(x(t), y)
        }
        drawPath(path, musicColor, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * Highlights: the best moments found in the watch data (top speed, sprints, descents, climbs,
 * summit, heart-rate and power peaks) where there is video. Tap one to see it; pick some and make
 * a highlight reel (a new project of short clips around them).
 */
@Composable
private fun HighlightControls(state: UiState, vm: HeadsupViewModel, onShow: (FoundHighlight) -> Unit) {
    val found = state.highlights
    var picked by remember(found) { mutableStateOf(found.orEmpty().take(6).toSet()) }
    var lengthMs by rememberSaveable { mutableStateOf(8_000L) }
    // Search by one metric (e.g. only heart-rate peaks) or all of them.
    var metric by rememberSaveable { mutableStateOf<Metric?>(null) }
    val metrics = HighlightKind.entries.map { it.metric }.distinct().filter { m -> state.activities.any { it.track.has(m) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Highlights")
        Spacer(Modifier.weight(1f))
        SmallAction(
            if (found == null) "Find" else "Search again", { vm.findHighlights(metric) },
            enabled = state.clips.any { it.sync != null } && !state.busy,
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = metric == null, onClick = { metric = null; vm.findHighlights(null) }, label = { Text("All") }) }
        items(metrics) { m ->
            FilterChip(selected = metric == m, onClick = { metric = m; vm.findHighlights(m) }, label = { Text(metricLabel(m)) })
        }
    }
    if (found == null) {
        Hint("Finds the best moments from your watch data (speed, descents, climbs, peaks) in the clips.")
        return
    }
    if (found.isEmpty()) {
        Hint("Nothing stands out in the parts with video.")
        return
    }
    for (f in found) {
        val on = f in picked
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onShow(f) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (on) HeadsupColors.Accent else Color.Transparent)
                    .border(1.dp, if (on) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape)
                    .clickable { picked = if (on) picked - f else picked + f },
                contentAlignment = Alignment.Center,
            ) { if (on) Text("✓", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.OnAccent) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${f.highlight.kind.label} · ${highlightValue(f)}", style = MaterialTheme.typography.bodyMedium)
                Hint("${f.clipName} · ${Formats.duration(f.positionMs)}")
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((ms, label) in listOf(5_000L to "5 s", 8_000L to "8 s", 12_000L to "12 s")) {
            FilterChip(selected = lengthMs == ms, onClick = { lengthMs = ms }, label = { Text(label) })
        }
    }
    Button(
        onClick = { vm.makeHighlightReel(found.filter { it in picked }, lengthMs) },
        enabled = picked.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Make highlight reel (${picked.size})") }
    Hint("Creates a new project with these moments as clips, ready to export; this project stays saved.")
}

private fun metricLabel(m: Metric) = when (m) {
    Metric.SPEED -> "Speed"
    Metric.HEART_RATE -> "Heart rate"
    Metric.ALTITUDE -> "Altitude"
    Metric.POWER -> "Power"
    else -> m.name.lowercase().replaceFirstChar { it.uppercase() }
}

private fun highlightValue(f: FoundHighlight): String {
    val h = f.highlight
    fun Reading.text() = "$value $unit"
    return when (h.kind) {
        HighlightKind.TOP_SPEED -> Formats.speed(h.value).text()
        HighlightKind.ACCELERATION -> String.format(Locale.US, "+%.0f km/h in %d s", h.value * 3.6, HighlightFinder.ACCEL_WINDOW_S)
        HighlightKind.FAST_DESCENT, HighlightKind.STEEP_CLIMB -> String.format(Locale.US, "%.1f m/s vertical", h.value)
        HighlightKind.SUMMIT -> Formats.altitude(h.value).text()
        HighlightKind.PEAK_HEART_RATE -> Formats.heartRate(h.value).text()
        HighlightKind.POWER_PEAK -> String.format(Locale.US, "%.0f W", h.value)
    }
}

/** Frames in [durationMs] at [frameRate], or null when the rate is unknown. */
private fun frameCount(durationMs: Long, frameRate: Float?): Long? = frameRate?.let { kotlin.math.round(durationMs / 1000.0 * it).toLong() }

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(Locale.US, "%.1f GB", bytes / 1e9) else String.format(Locale.US, "%.0f MB", bytes / 1e6)

/** The open project (saved automatically) and the list of saved ones. */
@Composable
private fun ProjectRow(state: UiState, vm: HeadsupViewModel) {
    var listOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // A project from the list being renamed.
    var renamingOther by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // On the timeline: after speed changes, at the timeline's frame rate.
            val total = state.clips.sumOf { it.timelineDurationMs }
            val frames = frameCount(total, state.effectiveTimelineFps) ?: 0L
            Text(
                "Project · saved automatically" +
                    if (state.clips.isEmpty()) "" else " · ${state.clips.size} clips, ${Formats.duration(total)}, $frames frames",
                style = MaterialTheme.typography.labelSmall,
                color = HeadsupColors.TextMuted,
            )
            Text(state.projectName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        SmallAction("Rename", { renaming = true })
        SmallAction("Projects", { vm.refreshProjects(); listOpen = true })
    }
    val external = with(vm) { state.externalMedia }
    if (external.isNotEmpty() && !state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("${external.size} file(s) still come from the gallery or cloud; the project breaks if they move.", Modifier.weight(1f))
            SmallAction("Copy into project", vm::copyMediaIntoProject)
        }
    }
    if (renaming) {
        RenameDialog(state.projectName, onDismiss = { renaming = false }, title = "Project name") { vm.renameProject(it); renaming = false }
    }
    renamingOther?.let { old ->
        RenameDialog(old, onDismiss = { renamingOther = null }, title = "Project name") { vm.renameProject(it, old); renamingOther = null }
    }
    if (listOpen) {
        AlertDialog(
            onDismissRequest = { listOpen = false },
            title = { Text("Projects") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.projects.isEmpty()) Hint("No saved projects yet. A project is saved once it has files.")
                    else Hint("Projects keep their own copies of their videos. Deleting a project frees that space; the gallery is never touched.")
                    for (p in state.projects) {
                        val current = p.name == state.projectName
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (current) HeadsupColors.SurfaceHigh else Color.Transparent)
                                .clickable { listOpen = false; if (!current) vm.openProject(p.name) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Hint(
                                    Instant.ofEpochMilli(p.modifiedMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                                        .withNano(0).toString().replace('T', ' ') +
                                        if (p.bytes > 0) " · " + formatBytes(p.bytes) else "",
                                )
                            }
                            SmallAction("Rename", { renamingOther = p.name })
                            SmallAction("Delete", { vm.deleteProject(p.name) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listOpen = false; vm.newProject() }) { Text("New project") } },
            dismissButton = { TextButton(onClick = { listOpen = false }) { Text("Close") } },
        )
    }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)

/**
 * Speed of a clip. Real slow motion needs footage shot at a high frame rate: the clip's frames,
 * spread out by the speed, should still cover the timeline's frame rate.
 */
@Composable
private fun SpeedControls(clip: ClipState, timelineFps: Float, onSpeed: (Float) -> Unit, onMute: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Speed", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Hint("${Formats.duration(clip.timelineDurationMs)} on the timeline")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (speed in SPEEDS) {
            val label = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×".replace("0.", ".")
            FilterChip(selected = clip.speed == speed, onClick = { onSpeed(speed) }, label = { Text(label) })
        }
    }
    if (clip.speed == 1f) return
    val sourceFps = clip.meta.frameRate
    if (sourceFps != null && clip.speed < 1f) {
        val effective = sourceFps * clip.speed
        Hint(
            if (effective + 0.5f >= timelineFps) {
                String.format(Locale.US, "Smooth: %s fps footage at %s× still gives %s fps (timeline %s).",
                    OutputSpec.formatFps(sourceFps), clip.speed, OutputSpec.formatFps(effective), OutputSpec.formatFps(timelineFps))
            } else {
                String.format(Locale.US, "Will stutter: only %s fps at %s× for a %s fps timeline. Shoot at %s fps for this speed.",
                    OutputSpec.formatFps(effective), clip.speed, OutputSpec.formatFps(timelineFps), OutputSpec.formatFps(timelineFps / clip.speed))
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sound", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
        FilterChip(selected = !clip.muteChangedSpeed, onClick = { onMute(false) }, label = { Text("Stretched") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = clip.muteChangedSpeed, onClick = { onMute(true) }, label = { Text("Mute") })
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
    TimeSource.PHOTO_EXIF -> "photo EXIF"
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

/** Sections of the colour corrections, so the picker row stays short. */
private enum class ColourSection(val label: String) { LIGHT("Light"), COLOUR("Colour"), FILM("Film"), TOOLS("Tools") }

private val Adjustment.section: ColourSection
    get() = when (this) {
        Adjustment.EXPOSURE, Adjustment.BRIGHTNESS, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS,
        Adjustment.WHITES, Adjustment.BLACKS, Adjustment.ROLLOFF -> ColourSection.LIGHT
        Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.SATURATION, Adjustment.VIBRANCE, Adjustment.SKIN -> ColourSection.COLOUR
        Adjustment.SPLIT, Adjustment.SHADOW_HUE, Adjustment.HIGHLIGHT_HUE, Adjustment.TAME, Adjustment.VIGNETTE -> ColourSection.FILM
        Adjustment.CINE, Adjustment.LUT, Adjustment.MATCH, Adjustment.BLEND -> ColourSection.TOOLS
    }

/** A row of text tabs: the selected one white and underlined, the others muted. */
@Composable
private fun SectionTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) HeadsupColors.Highlight else HeadsupColors.TextMuted)
                Box(Modifier.padding(top = 3.dp).width(18.dp).height(2.dp).background(if (on) HeadsupColors.Highlight else Color.Transparent))
            }
        }
    }
}

internal enum class Adjustment(
    val symbol: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: String,
    val get: (ColorGrade) -> Float,
    val set: (ColorGrade, Float) -> ColorGrade,
    /** Value meaning "no correction" (0 except for the toning hues). */
    val neutral: Float = 0f,
) {
    /** Not a ruler value: the cine assistant, which analyses the frame and suggests a film grade. */
    CINE("Cine", "Cine look", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),
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
    ROLLOFF("Rl", "Roll-off", 0f..1f, 0.02f, "%.2f", { it.rolloff }, { g, v -> g.copy(rolloff = v) }),
    SPLIT("Sp", "Split tone", 0f..1f, 0.02f, "%.2f", { it.toneAmount }, { g, v -> g.copy(toneAmount = v) }),
    SHADOW_HUE(
        "S°", "Shadow hue", 0f..360f, 1f, "%.0f°", { it.shadowHue }, { g, v -> g.copy(shadowHue = v) }, ColorGrade.DEFAULT_SHADOW_HUE,
    ),
    HIGHLIGHT_HUE(
        "H°", "Light hue", 0f..360f, 1f, "%.0f°", { it.highlightHue }, { g, v -> g.copy(highlightHue = v) }, ColorGrade.DEFAULT_HIGHLIGHT_HUE,
    ),
    TAME("Tm", "Tame colours", 0f..1f, 0.02f, "%.2f", { it.tame }, { g, v -> g.copy(tame = v) }),

    /** Not a ruler value: opens the LUT library (the LUT is applied last, after all corrections). */
    LUT("LUT", "LUT", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a ruler value: matches the clip's colour to a reference frame from another clip. */
    MATCH("≈", "Shot match", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a ruler value: how the clip blends in from the previous clip's look. */
    BLEND("⇢", "Blend in", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),
}

private val BLEND_LENGTHS = listOf(0L to "Cut", 500L to "0.5 s", 1000L to "1 s", 2000L to "2 s", 4000L to "4 s", 8000L to "8 s")

@Composable
private fun ColourPage(
    state: UiState,
    vm: HeadsupViewModel,
    adjustment: Adjustment,
    onAdjustment: (Adjustment) -> Unit,
    onAddLut: () -> Unit,
    frameSource: (() -> Bitmap?)?,
) {
    val index = state.selectedClip
    val clip = state.selected
    if (index == null || clip == null) {
        Hint("Select a clip first.")
        return
    }
    val target = state.target
    PillTabs(listOf("This clip", "Group", "Masks", "All clips"), target.ordinal) { vm.setTarget(GradeTarget.entries[it]) }
    val group = state.groupOf(clip)
    when (target) {
        GradeTarget.CLIP -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (clip.colorGrade) LookActions(state, vm)
            Spacer(Modifier.weight(1f))
            if (clip.colorGrade && state.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
            Switch(checked = clip.colorGrade, onCheckedChange = { vm.setClipGrade(index, it) })
        }
        GradeTarget.GROUP -> GroupControls(state, clip, index, group, vm)
        GradeTarget.MASK -> MaskList(state, clip, vm)
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
    val mask = clip.masks.getOrNull(state.selectedMask)
    if (target == GradeTarget.MASK && mask == null) return

    // The ruler sits right under the correction picker, so nothing needs scrolling.
    // The LUT belongs to the clip (it depends on how the clip was shot); the mask has only tonal
    // and colour corrections, with its shape in front.
    // Blending in from the previous clip is a property of the clip, so it is only on its own tab.
    val options = when (target) {
        GradeTarget.CLIP -> if (index > 0) Adjustment.entries else Adjustment.entries - Adjustment.BLEND
        GradeTarget.GROUP -> Adjustment.entries - Adjustment.BLEND - Adjustment.CINE - Adjustment.MATCH
        GradeTarget.GLOBAL -> Adjustment.entries - Adjustment.LUT - Adjustment.BLEND - Adjustment.CINE - Adjustment.MATCH
        GradeTarget.MASK -> Adjustment.entries - Adjustment.LUT - Adjustment.SKIN - Adjustment.VIGNETTE - Adjustment.BLEND -
            Adjustment.CINE - Adjustment.MATCH
    }
    val adjustment = if (adjustment in options) adjustment else Adjustment.EXPOSURE
    // On the Masks tab the ruler edits either a shape control or a correction.
    var shape by rememberSaveable { mutableStateOf<MaskShape?>(null) }
    var onShapes by rememberSaveable { mutableStateOf(true) }
    val shapes = mask?.let { m -> MaskShape.entries.filter { m.kind in it.kinds } }.orEmpty()
    val editingShape = if (target == GradeTarget.MASK && onShapes) shape?.takeIf { it in shapes } ?: shapes.firstOrNull() else null
    // Corrections are grouped into short sections (plus the mask's shape), so each row stays short.
    val sections = listOfNotNull("Shape".takeIf { shapes.isNotEmpty() }) + ColourSection.entries.filter { s -> options.any { it.section == s } }.map { it.label }
    val section = if (editingShape != null) "Shape" else adjustment.section.label
    SectionTabs(sections, sections.indexOf(section).coerceAtLeast(0)) { i ->
        if (sections[i] == "Shape") {
            onShapes = true
        } else {
            onShapes = false
            options.firstOrNull { it.section.label == sections[i] }?.let(onAdjustment)
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (editingShape != null) {
            items(shapes) { m ->
                CircleOption(m.symbol, m.label, selected = m == editingShape, modified = false) { shape = m; onShapes = true }
            }
        }
        items(if (editingShape != null) emptyList() else options.filter { it.section == adjustment.section }) { a ->
            val lut = if (target == GradeTarget.GROUP) group?.lutName else clip.lutName
            val modified = when (a) {
                Adjustment.LUT -> lut != null
                Adjustment.BLEND -> clip.transitionMs > 0
                Adjustment.CINE -> state.cine != null
                Adjustment.MATCH -> state.matchReference?.clipId == clip.id
                else -> a.get(state.grade) != a.neutral
            }
            CircleOption(a.symbol, a.label, selected = editingShape == null && a == adjustment, modified = modified) {
                onShapes = false
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
            onChange = { v -> vm.updateMask { editingShape.set(it, v) } },
            onReset = { vm.updateMask { editingShape.set(it, editingShape.get(ColourMask(it.kind))) } },
        )
        return
    }
    if (adjustment == Adjustment.CINE) {
        CinePanel(state, vm, frameSource)
        return
    }
    if (adjustment == Adjustment.MATCH) {
        ShotMatchPanel(state, clip, vm, frameSource)
        return
    }
    if (adjustment == Adjustment.BLEND) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((ms, label) in BLEND_LENGTHS) {
                FilterChip(selected = clip.transitionMs == ms, onClick = { vm.setTransition(index, ms) }, label = { Text(label) })
            }
        }
        Hint("Starts with the previous clip's colours and changes to this clip's over the chosen time, e.g. after a split where the light changes.")
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
        onReset = { vm.updateGrade { adjustment.set(it, adjustment.neutral) } },
        onHold = { held -> vm.setCompareGrade(if (held) adjustment.set(state.grade, adjustment.neutral) else null) },
    )
}

/**
 * Cine assistant: measures the paused frame (no AI, just the picture's statistics) and suggests a
 * film grade in a few styles. The result is ordinary corrections, adjustable afterwards.
 */
@Composable
private fun CinePanel(state: UiState, vm: HeadsupViewModel, frameSource: (() -> Bitmap?)?) {
    val session = state.cine
    if (session == null) {
        Hint("Pause on a typical frame. The assistant measures its tones and colours and suggests a film grade.")
        Button(
            onClick = { frameSource?.invoke()?.let(vm::analyseForCine) },
            enabled = frameSource != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Analyse this frame") }
        return
    }
    val s = session.stats
    Hint(
        String.format(
            Locale.US, "Mid-tones %.0f %% · %.1f %% clipped · %s%s",
            s.median * 100, s.clipped * 100,
            if (s.foliage > 0.15f) "lots of green/blue" else "colours calm",
            if (s.skin > 0.03f) " · skin found" else "",
        ),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (style in CineStyle.entries) {
            FilterChip(selected = session.style == style, onClick = { vm.applyCine(style, session.strength) }, label = { Text(style.label) })
        }
    }
    val style = session.style
    if (style != null) {
        ValueHeader("Strength", String.format(Locale.US, "%.0f %%", session.strength * 100))
        RulerSlider(
            session.strength, 0f..1f, 0.02f, { v -> vm.applyCine(style, v) },
            onReset = { vm.applyCine(style, 1f) },
            onHold = vm::compareCine,
        )
        Hint("Hold the ruler to see the picture without the cine look.")
    }
    Row {
        SmallAction("Undo", { vm.closeCine(undo = true) })
        Spacer(Modifier.weight(1f))
        SmallAction("Done", { vm.closeCine(undo = false) }, enabled = style != null)
    }
    if (style != null) Hint("Done keeps it as normal corrections: roll-off, split tone, tame colours and the rest stay adjustable.")
}

/**
 * Shot match: pick a reference frame in one clip (e.g. the Osmo), then on another clip (drone,
 * phone) pause on a similar frame and match: exposure, contrast, white balance and saturation are
 * set so both look alike, and skin hue too when both frames show skin. The result is ordinary
 * corrections.
 */
@Composable
private fun ShotMatchPanel(state: UiState, clip: ClipState, vm: HeadsupViewModel, frameSource: (() -> Bitmap?)?) {
    val reference = state.matchReference
    val group = state.groupOf(clip)
    // A camera group is matched as a whole by default: colour character only, brightness per shot.
    var wholeGroup by rememberSaveable(group?.id) { mutableStateOf(group != null) }
    Hint(
        when {
            reference == null -> "1. Pause on a good frame of the clip whose colours you like and set it as the reference."
            reference.clipId == clip.id -> "Reference set. Now select another clip, pause on a similar frame and match."
            else -> "Reference: ${reference.clipName}. Pause on a similar frame and match this clip to it."
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { frameSource?.invoke()?.let(vm::setMatchReference) },
            enabled = frameSource != null,
            modifier = Modifier.weight(1f),
        ) { Text(if (reference?.clipId == clip.id) "Reference ✓" else "Set as reference", maxLines = 1) }
        Button(
            onClick = { frameSource?.invoke()?.let { vm.matchToReference(it, wholeGroup && group != null) } },
            enabled = frameSource != null && reference != null && reference.clipId != clip.id && !state.matching,
            modifier = Modifier.weight(1f),
        ) { Text(if (state.matching) "Matching…" else "Match", maxLines = 1) }
    }
    if (group != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = wholeGroup, onClick = { wholeGroup = true }, label = { Text("Group: ${group.name}") })
            FilterChip(selected = !wholeGroup, onClick = { wholeGroup = false }, label = { Text("This clip") })
        }
        Hint(
            if (wholeGroup) {
                "Matches the camera's colour (white balance, tint, saturation, skin) for every clip in ${group.name}; each keeps its own brightness."
            } else {
                "Matches this clip completely, brightness included."
            },
        )
    }
    val session = state.matchSession
    if (session != null && (session.clipId == clip.id || (session.groupId != null && session.groupId == clip.groupId))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(
                when {
                    session.groupId != null -> "Matched the group: temperature, tint and saturation"
                    else -> "Matched: exposure, contrast, temperature, tint and saturation"
                } + if (state.matchedSkin) ", and skin hue." else ".",
                Modifier.weight(1f),
            )
            SmallAction("Undo", vm::undoMatch)
        }
        ValueHeader("Match strength", String.format(Locale.US, "%.0f %%", session.strength * 100))
        RulerSlider(
            session.strength, 0f..1f, 0.02f, vm::setMatchStrength,
            onReset = { vm.setMatchStrength(MATCH_STRENGTH) },
            onHold = if (session.groupId == null) vm::compareMatch else null,
        )
    }
}

/** Shape controls of a mask, edited with the same ruler as the corrections. */
private enum class MaskShape(
    val symbol: String,
    val label: String,
    val kinds: Set<MaskKind>,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: String,
    val get: (ColourMask) -> Float,
    val set: (ColourMask, Float) -> ColourMask,
) {
    POSITION("↕", "Position", setOf(MaskKind.LINEAR), 0f..1f, 0.01f, "%.0f %%", { it.centerY }, { m, v -> m.copy(centerY = v) }),
    WIDTH("↔", "Width", setOf(MaskKind.ELLIPSE), 0.05f..2f, 0.01f, "%.0f %%", { it.sizeX }, { m, v -> m.copy(sizeX = v) }),
    HEIGHT("↕", "Height", setOf(MaskKind.ELLIPSE), 0.05f..2f, 0.01f, "%.0f %%", { it.sizeY }, { m, v -> m.copy(sizeY = v) }),
    SOFTNESS("◐", "Softness", MaskKind.entries.toSet(), 0.02f..1f, 0.02f, "%.0f %%", { it.softness }, { m, v -> m.copy(softness = v) }),
    ANGLE("∠", "Angle", MaskKind.entries.toSet(), -90f..90f, 0.5f, "%+.1f°", { it.angleDegrees }, { m, v -> m.copy(angleDegrees = v) }),
    ;

    /** Value as shown above the ruler (fractions as percent). */
    fun shown(mask: ColourMask): Float = if (this == ANGLE) get(mask) else get(mask) * 100f
}

/** The selected clip's masks: pick one to edit, add a line or an oval, invert or remove. */
@Composable
private fun MaskList(state: UiState, clip: ClipState, vm: HeadsupViewModel) {
    if (clip.masks.isEmpty()) Hint("Windows with their own corrections: a line for the sky, an oval for a face. Drag on the picture to move one.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        clip.masks.forEachIndexed { i, m ->
            FilterChip(
                selected = i == state.selectedMask,
                onClick = { vm.selectMask(i) },
                label = { Text("${i + 1} · " + if (m.kind == MaskKind.LINEAR) "Line" else "Oval") },
            )
        }
        if (clip.masks.size < ColourMask.MAX_PER_CLIP) {
            FilterChip(selected = false, onClick = { vm.addMask(MaskKind.LINEAR) }, label = { Text("+ Line") })
            FilterChip(selected = false, onClick = { vm.addMask(MaskKind.ELLIPSE) }, label = { Text("+ Oval") })
        }
    }
    val mask = clip.masks.getOrNull(state.selectedMask) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction(if (mask.inverted) "Inverted ✓" else "Invert", { vm.updateMask { it.copy(inverted = !it.inverted) } })
        Spacer(Modifier.weight(1f))
        if (mask.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
        SmallAction("Remove", { vm.removeMask(state.selectedMask) })
    }
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
private fun RenameDialog(initial: String, onDismiss: () -> Unit, title: String = "Group name", onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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

// Frame page

/** Framing of the selected clip: output shape, zoom, position (drag on the picture) and straightening. */
@Composable
private fun FramePanel(state: UiState, vm: HeadsupViewModel) {
    val clip = state.selected
    if (clip == null) {
        Hint("Select a clip first.")
        return
    }
    val r = clip.reframe
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint("Drag the picture to move it, pinch to zoom. The grid shows the thirds.", Modifier.weight(1f))
        if (!r.isIdentity) SmallAction("Reset", { vm.updateReframe { Reframe() } })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((aspect, label) in Reframe.PRESETS) {
            FilterChip(selected = r.aspect == aspect, onClick = { vm.updateReframe { it.copy(aspect = aspect) } }, label = { Text(label) })
        }
    }
    val (w, h) = r.outputSize(clip.meta.width.toFloat(), clip.meta.height.toFloat())
    if (r.aspect != null) Hint("Exported at ${w.toInt() / 2 * 2}×${h.toInt() / 2 * 2}.")
    ValueHeader("Zoom", String.format(Locale.US, "%.2f×", r.zoom))
    RulerSlider(r.zoom, 1f..4f, 0.02f, { v -> vm.updateReframe { it.copy(zoom = v) } }, onReset = { vm.updateReframe { it.copy(zoom = 1f) } })
    ValueHeader("Straighten", String.format(Locale.US, "%+.1f°", r.angleDegrees))
    RulerSlider(
        r.angleDegrees, -15f..15f, 0.1f, { v -> vm.updateReframe { it.copy(angleDegrees = v) } },
        onReset = { vm.updateReframe { it.copy(angleDegrees = 0f) } },
    )
    if (state.clips.size > 1) {
        Row {
            Spacer(Modifier.weight(1f))
            SmallAction("Same shape for all clips", { vm.setAspectForAll(r.aspect) })
        }
    }
}

// Effects page

private enum class EffectKind(val symbol: String, val label: String) {
    DASHBOARD("HUD", "Dashboard"),
    GRAPH("Gr", "Graph"),
    GRAIN("Gn", "Grain"),
    GLOW("Gl", "Glow"),
    HALATION("Ha", "Halation"),
    BARS("Cin", "Bars"),
    FADE("Fd", "Fade"),
}

/** Effects over the picture, picked from round options like the colour corrections. */
@Composable
private fun EffectsPanel(state: UiState, vm: HeadsupViewModel, kind: EffectKind, onKind: (EffectKind) -> Unit) {
    val style = state.style
    val effects = state.effects
    val activities = state.activities
    val updateStyle = vm::updateStyle
    val updateEffects = vm::updateEffects
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
                EffectKind.HALATION -> effects.halation > 0f
                EffectKind.BARS -> effects.barsRatio != null
                EffectKind.FADE -> state.selected?.let { it.fadeInMs > 0 || it.fadeOutMs > 0 } == true
            }
            CircleOption(e.symbol, e.label, selected = e == kind, modified = active) { onKind(e) }
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
        EffectKind.HALATION -> {
            ValueHeader("Halation", String.format(Locale.US, "%.0f %%", effects.halation * 100))
            RulerSlider(effects.halation, 0f..1f, 0.02f, { v -> updateEffects { it.copy(halation = v) } }, onReset = { updateEffects { it.copy(halation = 0f) } })
            ValueHeader("Threshold", String.format(Locale.US, "%.0f %%", effects.glowThreshold * 100))
            RulerSlider(effects.glowThreshold, 0.4f..0.98f, 0.02f, { v -> updateEffects { it.copy(glowThreshold = v) } }, onReset = { updateEffects { it.copy(glowThreshold = 0.75f) } })
            Hint("Red-orange bleed around bright highlights, as on motion picture film.")
        }
        EffectKind.BARS -> {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = effects.barsRatio == null, onClick = { updateEffects { it.copy(barsRatio = null) } }, label = { Text("Off") }) }
                items(listOf(2.39f to "2.39:1", 2.0f to "2:1", 1.85f to "1.85:1")) { (ratio, label) ->
                    FilterChip(selected = effects.barsRatio == ratio, onClick = { updateEffects { it.copy(barsRatio = ratio) } }, label = { Text(label) })
                }
            }
        }
        EffectKind.FADE -> {
            val index = state.selectedClip
            val clip = state.selected
            if (index == null || clip == null) {
                Hint("Select a clip.")
            } else {
                val maxS = (clip.timelineDurationMs / 1000f).coerceIn(0.5f, 10f)
                ValueHeader("Fade in from black", fadeLabel(clip.fadeInMs))
                RulerSlider(clip.fadeInMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, (v * 1000).toLong(), clip.fadeOutMs) }, onReset = { vm.setFades(index, 0L, clip.fadeOutMs) })
                ValueHeader("Fade out to black", fadeLabel(clip.fadeOutMs))
                RulerSlider(clip.fadeOutMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, clip.fadeInMs, (v * 1000).toLong()) }, onReset = { vm.setFades(index, clip.fadeInMs, 0L) })
                Hint("This clip's picture, dashboard and sound fade together. Music has its own fades on the Audio page.")
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

/**
 * Export: the video (whole timeline as one file by default, or one per clip) with resolution,
 * frame rate, quality and codec, like other editors; the dashboard as PNGs for DaVinci is
 * separate, as it is only one of the effects.
 */
@Composable
private fun ExportControls(
    state: UiState,
    vm: HeadsupViewModel,
    jobs: List<ExportJob>,
    onExportVideos: () -> Unit,
    onExportOverlays: () -> Unit,
    onTenBitTest: () -> Unit,
) {
    val settings = state.exportSettings
    val first = state.clips.firstOrNull()
    Label("Video")
    SettingRow("Quick presets") {
        AssistChip(onClick = { vm.applyPreset(vertical = true) }, label = { Text("Reels · TikTok · Shorts") })
        AssistChip(onClick = { vm.applyPreset(vertical = false) }, label = { Text("YouTube") })
    }
    SettingRow("Export") {
        FilterChip(selected = settings.wholeTimeline, onClick = { vm.updateExportSettings { it.copy(wholeTimeline = true) } }, label = { Text("Whole timeline") })
        FilterChip(selected = !settings.wholeTimeline, onClick = { vm.updateExportSettings { it.copy(wholeTimeline = false) } }, label = { Text("Each clip") })
    }
    SettingRow("Resolution") {
        for ((height, label) in ExportSettings.HEIGHTS) {
            FilterChip(selected = settings.height == height, onClick = { vm.updateExportSettings { it.copy(height = height) } }, label = { Text(label) })
        }
    }
    SettingRow("Frame rate") {
        val auto = first?.meta?.frameRate
        FilterChip(
            selected = state.timelineFps == null,
            onClick = { vm.setTimelineFps(null) },
            label = { Text("Auto" + (auto?.let { " (${OutputSpec.formatFps(it)})" } ?: "")) },
        )
        for (fps in ExportSettings.FRAME_RATES) {
            FilterChip(selected = state.timelineFps == fps, onClick = { vm.setTimelineFps(fps) }, label = { Text(OutputSpec.formatFps(fps)) })
        }
    }
    SettingRow("Quality") {
        for ((factor, label) in ExportSettings.QUALITIES) {
            FilterChip(selected = settings.bitrateFactor == factor, onClick = { vm.updateExportSettings { it.copy(bitrateFactor = factor) } }, label = { Text(label) })
        }
    }
    val tenBitBlocker = remember(state.clips) { vm.tenBitBlocker(state) }
    val tenBit = settings.tenBit && tenBitBlocker == null
    SettingRow("Codec") {
        for (codec in VideoCodec.entries) {
            FilterChip(
                selected = settings.codec == codec || (tenBit && codec == VideoCodec.HEVC),
                onClick = { vm.updateExportSettings { it.copy(codec = codec) } },
                enabled = !tenBit,
                label = { Text(codec.label) },
            )
        }
    }
    SettingRow("Colour depth") {
        FilterChip(selected = !tenBit, onClick = { vm.updateExportSettings { it.copy(tenBit = false) } }, label = { Text("8-bit SDR") })
        FilterChip(
            selected = tenBit,
            onClick = { vm.updateExportSettings { it.copy(tenBit = true) } },
            enabled = tenBitBlocker == null,
            label = { Text("10-bit HDR (HLG) · experimental") },
        )
    }
    if (tenBit) {
        SettingRow("White in HDR") {
            for ((level, label) in HdrRoute.WHITE_LEVELS) {
                FilterChip(selected = settings.hdrWhite == level, onClick = { vm.updateExportSettings { it.copy(hdrWhite = level) } }, label = { Text(label) })
            }
        }
        Hint("Where the picture's white sits in the HLG signal. At 93 % phone galleries and editors show it like the 8-bit export; 75 % is the broadcast standard, which they show much darker. While HDR plays, the phone may brighten the screen over the first seconds; that is the display, not the file.")
    }
    Hint(
        "Frame rate is the timeline's: clips with more frames are thinned out, none are invented. " +
            when {
                tenBit -> "10-bit: HEVC Main10 HLG, like DJI Mimo's 10-bit; smoother gradients, shows as HDR on HDR screens."
                settings.codec == VideoCodec.HEVC -> "HEVC: smaller files."
                else -> "H.264: plays everywhere, larger files."
            } + (tenBitBlocker?.let { " 10-bit: $it" } ?: ""),
    )
    Button(onClick = onExportVideos, enabled = state.clips.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (settings.wholeTimeline) "Export timeline" else "Export ${state.clips.size} clips")
    }
    Hint("Colour, effects, framing, speed; the dashboard where there is watch data. → ${MediaStoreOutput.VIDEO_FOLDER}")

    Label("Dashboard")
    val canOverlay = state.clips.any { it.sync != null }
    OutlinedButton(onClick = onExportOverlays, enabled = canOverlay, modifier = Modifier.fillMaxWidth()) { Text("Export dashboard only (PNG)") }
    Hint(
        if (canOverlay) "Transparent PNG frames (ZIP) per clip, for DaVinci → ${MediaStoreOutput.OVERLAY_FOLDER}"
        else "Needs a FIT file matched to the clips.",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint("10-bit check: first 3 s of the selected clip through the HDR route, nothing saved.", Modifier.weight(1f))
        SmallAction("Test", onTenBitTest, enabled = state.selected != null)
    }

    if (jobs.isNotEmpty()) {
        jobs.forEach { JobRow(it) }
        if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
            SmallAction("Cancel all", vm::cancelExports)
        } else {
            SmallAction("Clear list", vm::clearFinishedExports)
        }
    }
}

/** A label and a scrolling row of choices. */
@Composable
private fun SettingRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun JobRow(job: ExportJob) {
    val context = LocalContext.current
    val kind = when (job.spec.kind) {
        ExportKind.VIDEO -> "Video"
        ExportKind.PNG_SEQUENCE -> "PNG"
        ExportKind.TEN_BIT_TEST -> "10-bit test"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$kind · ${job.spec.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                job.resultUri?.let { uri -> SmallAction("Share", { shareVideo(context, uri) }) }
            }
            job.spec.output?.takeIf { job.spec.kind == ExportKind.VIDEO }?.let { Hint("$it" + if (job.spec.parts.size > 1) " · ${job.spec.parts.size} clips joined" else "") }
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

/** Opens Android's share sheet (YouTube, Instagram, TikTok, messengers…) for an exported video. */
private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Shows each finished 10-bit test once, with a button to copy the report. */
@Composable
private fun TenBitResultDialog(jobs: List<ExportJob>) {
    var seen by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val job = jobs.lastOrNull {
        it.spec.kind == ExportKind.TEN_BIT_TEST && (it.status == JobStatus.DONE || it.status == JobStatus.FAILED) && it.id !in seen
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
