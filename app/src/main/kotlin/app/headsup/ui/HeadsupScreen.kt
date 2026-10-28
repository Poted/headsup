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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
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
import app.headsup.project.ProjectKind
import app.headsup.core.edit.TextAlign
import app.headsup.core.edit.TextAnim
import app.headsup.core.edit.TextAnimation
import app.headsup.core.edit.TextFont
import app.headsup.core.edit.TextItem
import app.headsup.core.edit.TextField
import app.headsup.core.edit.FieldSource
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
import kotlin.math.abs
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
import app.headsup.export.ExportPreset
import app.headsup.export.OutputSpec
import app.headsup.export.VideoCodec
import app.headsup.export.HdrRoute
import app.headsup.export.AudioFile
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import app.headsup.render.DashboardFont
import app.headsup.render.EffectSettings
import app.headsup.render.GraphMetric
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.media.ProxyState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.rememberUpdatedState
import app.headsup.core.track.ActivityTrack

private val DOWNLOADS_FOLDER: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

/** File picker that opens in [initial] instead of the last used folder. */
private class OpenDocumentsIn(private val initial: Uri) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
}

private enum class Tool(val label: String, val icon: ImageVector) {
    // The project holds the files: it comes first and is named for it.
    FILES("Project", HeadsupIcons.Video),
    EDIT("Edit", HeadsupIcons.Cut),
    // Light, colour, mixer, looks… : adjustments, not only colour.
    COLOUR("Adjust", HeadsupIcons.Colour),
    EFFECTS("Effects", HeadsupIcons.Dashboard),
    AUDIO("Audio", HeadsupIcons.Music),
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
    // Sound files usually come from Downloads (the picker can go to Music from there).
    val soundPicker = rememberLauncherForActivityResult(OpenDocumentsIn(DOWNLOADS_FOLDER)) { vm.addAudioFiles(it) }
    val addFit = { fitPicker.launch(arrayOf("*/*")) }
    val addVideos = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    // One way in, the gallery: an album takes photos only. A raw (DNG) file is recognised by its content.
    val addPhotos = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

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
    var editTab by rememberSaveable { mutableStateOf(EditTab.CUT) }
    // An album's Edit page is its crop; a film's Frame tool.
    val framing = tool == Tool.EDIT && (editTab == EditTab.FRAME || state.photoProject)
    // Texts are edited among the effects.
    val editingTexts = tool == Tool.EFFECTS && effectKind == EffectKind.TEXT
    // Set when a clip ended during playback, so the next one starts playing on its own.
    var continuePlaying by remember { mutableStateOf(false) }
    // Where to jump in the selected clip, e.g. after tapping a highlight.
    var seekRequest by remember { mutableStateOf<Long?>(null) }
    val measuring = tool == Tool.COLOUR && adjustment == Adjustment.SKIN && state.target != GradeTarget.MASK
    val editingMasks = tool == Tool.COLOUR && state.target == GradeTarget.MASK && state.selected?.colorGrade == true
    val editingBrush = state.selected?.masks?.getOrNull(state.selectedMask)?.kind == MaskKind.BRUSH
    // Read only where needed (cut controls), so playback does not recompose the whole screen.
    val playhead = remember { mutableLongStateOf(0L) }
    // File position of a cut handle being dragged; read only by the preview.
    val cutScrub = remember { mutableStateOf<Long?>(null) }
    // Grabs the paused preview frame, for the cine assistant.
    val frameSource = remember { mutableStateOf<(() -> Bitmap?)?>(null) }
    val exporting = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    // The timeline: a clip selected for trimming, slipping sounds, and where it is being dragged to.
    var videoSelected by remember { mutableStateOf(false) }
    var slip by remember { mutableStateOf(false) }
    var addingSound by remember { mutableStateOf(false) }
    // Timeline position shown while dragged (and until the picture has caught up), and the clip position sent to the preview.
    val scrubT = remember { mutableStateOf<Long?>(null) }
    val scrubTo = remember { mutableStateOf<Long?>(null) }
    // The preview plays (or was just asked to); read by the timeline while it glides.
    val previewPlaying = remember { mutableStateOf(false) }
    val clipStarts = state.clips.runningFold(0L) { a, c -> a + c.timelineDurationMs }
    fun timelineOf(clipMs: Long): Long {
        val i = state.selectedClip ?: return 0L
        val c = state.clips.getOrNull(i) ?: return 0L
        return clipStarts[i] + if (c.meta.isPhoto) clipMs else (clipMs / c.speed).toLong()
    }
    /** The clip under timeline position [t] and the position in it (file time from its cut). */
    fun locate(t: Long): Pair<Int, Long> {
        val i = clipStarts.indexOfLast { it <= t }.coerceIn(0, (state.clips.size - 1).coerceAtLeast(0))
        val c = state.clips.getOrNull(i) ?: return 0 to 0L
        val local = (t - clipStarts[i]).coerceIn(0L, c.timelineDurationMs)
        return i to if (c.meta.isPhoto) local else (local * c.speed).toLong()
    }
    fun jumpTo(t: Long) {
        val (i, pos) = locate(t)
        if (i != state.selectedClip) vm.selectClip(i)
        scrubT.value = t
        seekRequest = pos
    }
    // Held sideways, the picture and timeline take the left and the tools the right.
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    // Room for the timeline and the tools under (or beside) the picture.
    // The picture's share of the screen, set by dragging the handle under it (an album's photo
    // starts larger: no timeline under it); full screen hides everything else.
    var filmShare by rememberSaveable { mutableStateOf(0.32f) }
    var albumShare by rememberSaveable { mutableStateOf(0.5f) }
    // The album's thumbnails while editing a photo: folded by default.
    var albumOpen by rememberSaveable { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    // The menu over the Adjust/Effects button.
    var scopeOpen by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }
    // Held sideways the picture's size is set by the width of its column (dragged left and right).
    // Sideways the screen is low: the picture leaves room under it for the transport and timeline.
    val share = if (wide) 0.48f else if (state.photoProject) albumShare else filmShare
    var wideShare by rememberSaveable { mutableStateOf(0.56f) }
    // Sideways the transport and timeline can fold away (play on the picture, the timeline a line).
    var compact by rememberSaveable { mutableStateOf(false) }
    val folded = compact && wide && !fullscreen && !state.photoProject
    // Full screen keeps a line to scrub along under a film's picture, and play on it.
    val fullscreenFilm = fullscreen && !state.photoProject && state.clips.isNotEmpty()
    // The picture grows and shrinks smoothly when the transport folds or full screen changes.
    val previewMaxHeight by androidx.compose.animation.core.animateDpAsState(
        when {
            fullscreenFilm -> configuration.screenHeightDp.dp - 40.dp
            fullscreen -> configuration.screenHeightDp.dp
            folded -> (configuration.screenHeightDp * 0.84f).dp
            else -> (configuration.screenHeightDp * share).dp
        },
        androidx.compose.animation.core.tween(220),
        label = "preview height",
    )
    // A small settle of the whole screen when full screen comes or goes.
    val settle = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(fullscreen) {
        settle.snapTo(0.96f)
        settle.animateTo(1f, androidx.compose.animation.core.tween(220))
    }
    // Swipes on what the picture's tools leave alone: they never fight a mask, framing or a text being moved.
    val pictureFree = !(tool == Tool.COLOUR && state.target == GradeTarget.MASK) && !framing && !editingTexts && !measuring
    // Full screen hides the status and navigation bars too (a swipe from the edge shows them for a moment).
    val activityWindow = (LocalContext.current as? android.app.Activity)?.window
    val rootView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(fullscreen) {
        val controller = activityWindow?.let { androidx.core.view.WindowCompat.getInsetsController(it, rootView) }
        if (fullscreen) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    // The picture, its transport and the timeline.
    val pictureAndTimeline: @Composable ColumnScope.() -> Unit = {
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
                selected == null -> EmptyState(state.photoProject)
                exporting -> Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(HeadsupColors.Surface),
                    contentAlignment = Alignment.Center,
                ) { Hint("Preview paused while exporting") }
                else -> Box {
                    ClipPreview(
                        clip = selected,
                        activity = selected.sync?.let { state.activities.getOrNull(it.trackIndex) },
                        style = state.style,
                        // A finger held on the picture shows it without the corrections.
                        gradeCube = if (showOriginal) null else state.previewCube,
                        masks = if (selected.colorGrade && !showOriginal) selected.masks else emptyList(),
                        maskCubes = state.previewMaskCubes,
                        fromCube = if (showOriginal) null else state.previewFromCube,
                        transitionMs = selected.transitionMs,
                        guideMask = selected.masks.getOrNull(state.selectedMask).takeIf { editingMasks && !fullscreen },
                        onMoveMask = if (editingMasks && !fullscreen && !editingBrush) { x, y -> vm.updateMask { it.copy(centerX = x, centerY = y) } } else null,
                        onTransformMask = if (editingMasks && !fullscreen) vm::transformMask else null,
                        onBrush = if (editingMasks && !fullscreen && editingBrush && state.brush.mode != BrushMode.MOVE) vm::brushStroke else null,
                        guideIndex = state.selectedMask,
                        lassoPath = state.lassoPath,
                        paintPath = state.paintPath,
                        paintRadius = state.brush.radius,
                        paintStrength = state.brush.flow,
                        paintErase = state.brush.mode == BrushMode.ERASE,
                        brushCursor = state.brush.radius.takeIf { state.brushSizeShown && editingBrush && editingMasks },
                        vignette = if (selected.colorGrade && !showOriginal) state.appliedGrade(selected).vignette else 0f,
                        presence = if (selected.colorGrade && !showOriginal) state.appliedGrade(selected).presence else app.headsup.core.color.Presence.NONE,
                        effects = if (showOriginal) app.headsup.render.EffectSettings(dashboard = false) else state.compareEffects ?: state.effects,
                        onHoldOriginal = if (tool == Tool.COLOUR && !editingMasks && !measuring) { held -> showOriginal = held } else null,
                        showingOriginal = showOriginal,
                        onSample = if (measuring && !fullscreen) vm::sampleFrame else null,
                        marker = if (measuring) state.scope?.let { Offset(it.markerX, it.markerY) } else null,
                        maxHeight = previewMaxHeight,
                        edgeToEdge = fullscreen,
                        onMoveWidget = vm::moveWidget,
                        onMoveFinished = vm::persistStyle,
                        onPosition = {
                            playhead.longValue = it
                            vm.notePlayhead(it)
                            // The picture has caught up with where the timeline was left.
                            val target = scrubT.value
                            if (target != null && scrubTo.value == null && abs(timelineOf(it) - target) < 80) scrubT.value = null
                        },
                        scrubTo = scrubTo,
                        onPlaying = {
                            previewPlaying.value = it
                            vm.setPreviewPlaying(it)
                            if (it) scrubT.value = null
                        },
                        playlist = state.clips,
                        playlistIndex = state.selectedClip ?: 0,
                        // Played on into the next clip: it becomes the clip shown and edited.
                        onAdvance = { i -> if (i != state.selectedClip && i in state.clips.indices) vm.selectClip(i) },
                        framingOf = { state.framing(it) },
                        sourceOf = { state.previewSource(it) },
                        neighbourCubes = state.previewNeighbourCubes,
                        // In an album each photo shows only its own texts (or its group's, or all photos').
                        texts = if (state.photoProject) state.texts.filter { it.showsOn(selected.id, selected.groupId) } else state.texts,
                        photoMode = state.photoProject,
                        showTransport = fullscreenFilm || (!fullscreen && (!state.photoProject || tool == Tool.EDIT || tool == Tool.COLOUR)),
                        compactTransport = folded || fullscreenFilm,
                        onCompact = if (wide && !fullscreen && !state.photoProject) { c -> compact = c } else null,
                        // Read here so arriving place names redraw the texts.
                        textValues = state.placesVersion.let { _ -> { pos -> vm.textValues(state, selected, pos) } },
                        routeOverlay = if (state.routeMap.enabled) routeOverlayFor(vm, state, selected) else null,
                        // A finger on the picture places the text being edited, or the route map.
                        onMoveText = if (fullscreen) null else state.selectedText?.takeIf { editingTexts }?.let { id -> { x, y -> vm.updateText(id) { snapText(it, x, y) } } }
                            ?: if (tool == Tool.EFFECTS && effectKind == EffectKind.MAP && state.routeMap.enabled) { x: Float, y: Float -> vm.updateRouteMap { it.copy(x = x, y = y) } } else null,
                        cutScrub = cutScrub,
                        onFrameSource = { frameSource.value = it },
                        reframe = state.framing(selected),
                        // Full screen is for looking: no framing (pinch zooms the view instead), no grid.
                        onReframe = if (framing && !fullscreen) { r -> vm.updateReframe { r } } else null,
                        showGrid = framing && !fullscreen,
                        autoPlay = continuePlaying,
                        seekRequest = seekRequest,
                        onSeekDone = { seekRequest = null },
                        audio = state.audioClips,
                        clipSound = state.clipSound,
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
                    if (!fullscreen) UndoRedo(state.canUndo, state.canRedo, vm::undo, vm::redo, vm::undoAll, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 24.dp))
                    // Full screen: the picture alone, to see it as it will look.
                    Box(
                        Modifier
                            .align(if (fullscreen) Alignment.TopEnd else Alignment.TopStart)
                            .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { fullscreen = !fullscreen },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (fullscreen) "✕" else "⤢", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                }
            }
            if (!fullscreen && !wide && state.clips.isNotEmpty()) {
                // Dragging this moves the line between the picture and the tools.
                PreviewHandle { dy ->
                    val d = dy / (configuration.screenHeightDp * density.density)
                    if (state.photoProject) albumShare = (albumShare + d).coerceIn(0.2f, 0.75f) else filmShare = (filmShare + d).coerceIn(0.18f, 0.6f)
                }
            }
            if (fullscreen && !fullscreenFilm) {
                // Nothing under the picture.
            } else if (state.photoProject) {
                // Photos only: no timeline; the album's photos are arranged on the Edit page.
                // The album's photos stay under the picture on every page (to move between them); arranging
                // them (mark, move, remove) is on the Project page.
                // Elsewhere it folds into a slim bar (previous, next) to leave room for the picture.
                if (state.clips.isNotEmpty() && tool != Tool.EXPORT) {
                    if (tool == Tool.FILES || albumOpen) {
                        PhotoStrip(state, vm, onAdd = addPhotos, arrange = tool == Tool.FILES, onFold = if (tool == Tool.FILES) null else ({ albumOpen = false }))
                    } else {
                        AlbumBar(state, vm) { albumOpen = true }
                    }
                }
            } else if (state.clips.isNotEmpty() && (folded || fullscreenFilm)) {
                // Folded: the timeline is a line to scrub along.
                ScrubLine(
                    totalMs = clipStarts.last(),
                    playheadMs = { scrubT.value ?: timelineOf(playhead.longValue) },
                    onScrub = { t ->
                        if (t == null) {
                            scrubTo.value = null
                        } else {
                            scrubT.value = t
                            val (i, pos) = locate(t)
                            if (i != state.selectedClip) {
                                videoSelected = false
                                vm.selectClip(i)
                            }
                            scrubTo.value = pos
                        }
                    },
                )
            } else if (state.clips.isNotEmpty()) {
                Timeline(
                    state = state,
                    vm = vm,
                    videoSelected = videoSelected,
                    slip = slip,
                    playheadMs = { scrubT.value ?: timelineOf(playhead.longValue) },
                    maskKeys = if (editingMasks) state.selected?.masks?.getOrNull(state.selectedMask)?.keys?.map { it.timeMs }.orEmpty() else emptyList(),
                    onScrub = { t ->
                        if (t == null) {
                            scrubTo.value = null
                        } else {
                            scrubT.value = t
                            val (i, pos) = locate(t)
                            if (i != state.selectedClip) {
                                // Crossing into another clip shows it; a clip selected for trimming stays behind.
                                videoSelected = false
                                vm.selectClip(i)
                            }
                            scrubTo.value = pos
                        }
                    },
                    onTrimPreview = { cutScrub.value = it },
                    onSelectVideo = { i ->
                        videoSelected = true
                        vm.selectAudio(null)
                        // The preview shows the clip being edited.
                        if (i != state.selectedClip) jumpTo(clipStarts[i])
                    },
                    onSelectAudio = { id ->
                        videoSelected = false
                        vm.selectAudio(id)
                        tool = Tool.AUDIO
                    },
                    onDeselect = {
                        videoSelected = false
                        vm.selectAudio(null)
                    },
                    onAdd = addVideos,
                    onAddSound = { addingSound = true },
                    texts = if (editingTexts) state.texts else emptyList(),
                    onSelectText = vm::selectText,
                    onMoveText = { id, start -> vm.updateText(id) { it.copy(startMs = start) } },
                    showAudio = tool == Tool.AUDIO,
                    playing = { previewPlaying.value },
                ) {}
            }
            if (addingSound) {
                AddSoundDialog(
                    state,
                    onPick = { f ->
                        addingSound = false
                        vm.placeAudio(f, scrubT.value ?: timelineOf(playhead.longValue))
                        videoSelected = false
                        tool = Tool.AUDIO
                    },
                    onImport = {
                        addingSound = false
                        soundPicker.launch(arrayOf("audio/*"))
                    },
                    onDismiss = { addingSound = false },
                )
            }

    }
    // The tool's panel and the tools.
    val tools: @Composable ColumnScope.() -> Unit = tools@{
            if (fullscreen) return@tools
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
                // Adjust's and Effects' menu takes the panel's place while it is open.
                if (scopeOpen && (tool == Tool.COLOUR || tool == Tool.EFFECTS)) {
                    ScopeMenu(state, vm, adjust = tool == Tool.COLOUR) { scopeOpen = false }
                } else when (tool) {
                    Tool.FILES -> FilesPanel(
                        state, vm, onAddVideos = addVideos, onAddFit = addFit, onAddSound = { soundPicker.launch(arrayOf("audio/*")) },
                        onAddPhotos = addPhotos,
                        onShowClip = { i -> jumpTo(clipStarts[i]) },
                    )
                    Tool.EDIT -> EditPanel(state, vm, playhead, editTab, { editTab = it }, onCutScrub = { cutScrub.value = it }) { f ->
                        val index = state.clips.indexOfFirst { it.id == f.clipId }
                        if (index >= 0) {
                            if (index != state.selectedClip) vm.selectClip(index)
                            seekRequest = f.positionMs
                        }
                    }
                    Tool.AUDIO -> AudioPanel(
                        state, vm, videoSelected, slip, onSlip = { slip = it },
                        at = { scrubT.value ?: timelineOf(playhead.longValue) }, videoEnd = clipStarts.last(),
                    )
                    Tool.COLOUR -> ColourPage(
                        state, vm, adjustment, { adjustment = it }, onAddLut = { lutPicker.launch(arrayOf("*/*")) }, frameSource.value,
                        playhead, onSeek = { seekRequest = it },
                    )
                    Tool.EFFECTS -> if (effectKind == EffectKind.TEXT) {
                        EffectKinds(state, effectKind) { effectKind = it }
                        TextPanel(state, vm, at = { scrubT.value ?: timelineOf(playhead.longValue) })
                    } else {
                        EffectsPanel(state, vm, effectKind, playhead) { effectKind = it }
                    }
                    Tool.EXPORT -> ExportControls(
                        state = state,
                        vm = vm,
                        jobs = jobs,
                        onExportVideos = { startExport(vm::exportVideos) },
                        onExportOverlays = { startExport(vm::exportOverlays) },
                    )
                }
            }
            // Photos have no sound.
            val tools = Tool.entries.filter { !(state.photoProject && it == Tool.AUDIO) }
            LaunchedEffect(state.photoProject) { if (tool !in tools) tool = Tool.COLOUR }
            // In an album the Edit page only crops: it is named so.
            // Adjust and Effects say what they work on unless it is this clip, and that they open a menu.
            // A dot on Adjust or Effects when they work on more than this clip (the menu says what).
            BottomToolbar(
                tool, tools,
                labelOf = { t -> if (state.photoProject && t == Tool.EDIT) "Crop" else t.label },
                badgeOf = { t -> (t == Tool.COLOUR && state.target != GradeTarget.CLIP) || (t == Tool.EFFECTS && state.effectsOnAll) },
            ) {
                // Tapping Adjust or Effects again opens its menu.
                if (it == tool && (it == Tool.COLOUR || it == Tool.EFFECTS)) scopeOpen = !scopeOpen else { tool = it; scopeOpen = false }
            }
    }

    Box(Modifier.fillMaxSize().background(HeadsupColors.BackgroundGradient)) {
        // No title bar: the picture starts at the top, adding files lives in the Project tool.
        if (fullscreen) {
            // The picture alone on black, centred, whichever way the phone is held; a swipe down leaves.
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .graphicsLayer { scaleX = settle.value; scaleY = settle.value; alpha = 0.4f + 0.6f * ((settle.value - 0.96f) / 0.04f) }
                    .freeSwipe(pictureFree) { if (it == Swipe.DOWN) fullscreen = false },
                verticalArrangement = Arrangement.Center,
                content = pictureAndTimeline,
            )
        } else if (wide) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(top = 8.dp)) {
                val total = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                // The picture takes at most three quarters, and leaves the tools room for three of their circles.
                val minTools = with(density) { MIN_TOOLS_WIDTH.toPx() }
                val maxShare = minOf(0.75f, 1f - minTools / total).coerceAtLeast(0.36f)
                val pictureShare = wideShare.coerceIn(0.35f, maxShare)
                Row(Modifier.fillMaxSize()) {
                    // Clipped: the timeline scrolls inside its column, never under the tools.
                    // A swipe down anywhere here folds the transport and timeline, up unfolds them.
                    Column(
                        Modifier
                            .weight(pictureShare)
                            .fillMaxHeight()
                            .clipToBounds()
                            .graphicsLayer { scaleX = settle.value; scaleY = settle.value }
                            .freeSwipe(pictureFree) {
                                // Down folds the transport and timeline; up unfolds them, or when
                                // unfolded goes full screen.
                                when {
                                    it == Swipe.DOWN && !state.photoProject -> compact = true
                                    it == Swipe.UP && folded -> compact = false
                                    it == Swipe.UP -> fullscreen = true
                                    else -> Unit
                                }
                            },
                        content = pictureAndTimeline,
                    )
                    // Dragging this moves the line between the picture and the tools, left and right.
                    SideHandle { dx -> wideShare = (pictureShare + dx / total).coerceIn(0.35f, maxShare) }
                    // A swipe to the right over the tools (where nothing scrolls or slides) goes full screen.
                    Column(
                        Modifier
                            .weight(1f - pictureShare)
                            .fillMaxHeight()
                            .freeSwipe(true) { if (it == Swipe.RIGHT) fullscreen = true },
                        content = tools,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 8.dp)) {
                // Upright, a swipe up on the picture goes full screen (down in full screen leaves it).
                Column(Modifier.freeSwipe(pictureFree) { if (it == Swipe.UP) fullscreen = true }, content = pictureAndTimeline)
                tools()
            }
        }
    }
}

@Composable
private fun BottomToolbar(selected: Tool, tools: List<Tool>, labelOf: (Tool) -> String, badgeOf: (Tool) -> Boolean, onSelect: (Tool) -> Unit) {
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
        for (t in tools) {
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
                Box {
                    Icon(t.icon, contentDescription = labelOf(t), Modifier.size(22.dp), tint = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
                    if (badgeOf(t)) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(7.dp).clip(CircleShape).background(HeadsupColors.Accent))
                }
                Text(labelOf(t), style = MaterialTheme.typography.labelSmall, color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted, maxLines = 1, softWrap = false)
            }
        }
    }
}
@Composable
private fun EmptyState(photoProject: Boolean) {
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(HeadsupColors.Surface).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Hint(if (photoProject) "Add photos below to start the album." else "Add videos or photos below to start the film.")
    }
}

/**
 * Two small, quiet arrows over the picture; hidden until there is something to undo. Holding
 * undo for [UNDO_ALL_HOLD_MS] (a ring fills meanwhile) asks whether to undo every change.
 */
@Composable
private fun UndoRedo(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onUndoAll: () -> Unit, modifier: Modifier) {
    if (!canUndo && !canRedo) return
    var askAll by remember { mutableStateOf(false) }
    val hold = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    if (askAll) {
        AlertDialog(
            onDismissRequest = { askAll = false },
            title = { Text("Undo all changes?") },
            text = { Text("The project goes back to how it was when you opened it. Undo brings the changes back.") },
            confirmButton = { TextButton(onClick = { askAll = false; onUndoAll() }) { Text("Undo all") } },
            dismissButton = { TextButton(onClick = { askAll = false }) { Text("Cancel") } },
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((icon, enabled, action, label) in listOf(
            Quad(HeadsupIcons.Undo, canUndo, onUndo, "Undo"),
            Quad(HeadsupIcons.Redo, canRedo, onRedo, "Redo"),
        )) {
            val undo = label == "Undo"
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .then(
                        if (!undo) {
                            Modifier.clickable(enabled = enabled, onClick = action)
                        } else {
                            Modifier.pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    val filling = scope.launch { hold.animateTo(1f, androidx.compose.animation.core.tween(UNDO_ALL_HOLD_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
                                    val up = withTimeoutOrNull(UNDO_ALL_HOLD_MS) { waitForUpOrCancellation() }
                                    filling.cancel()
                                    scope.launch { hold.snapTo(0f) }
                                    if (up != null) action() else askAll = true
                                }
                            }
                        },
                    )
                    .drawWithContent {
                        drawContent()
                        if (undo && hold.value > 0f) {
                            drawArc(HeadsupColors.Accent, -90f, 360f * hold.value, false, style = Stroke(2.dp.toPx()))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, Modifier.size(16.dp), tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f))
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private const val UNDO_ALL_HOLD_MS = 3_000L

private enum class Swipe { LEFT, RIGHT, UP, DOWN }

/**
 * A clear swipe (one finger, mostly one way, at least 56 dp) that nothing inside used: sliders,
 * scrolling, the timeline and the picture's own tools consume their moves, and then this stays out.
 */
private fun Modifier.freeSwipe(enabled: Boolean, onSwipe: (Swipe) -> Unit): Modifier = if (!enabled) this else pointerInput(Unit) {
    val distance = 56.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
        // A button takes the touch but not the moves: only moves used inside spoil the swipe.
        var spoilt = false
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
            if (event.changes.count { it.pressed } > 1) spoilt = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.isConsumed) spoilt = true
            if (spoilt) continue
            val d = change.position - down.position
            if (d.getDistance() < distance) continue
            val swipe = when {
                abs(d.x) > 2 * abs(d.y) -> if (d.x > 0) Swipe.RIGHT else Swipe.LEFT
                abs(d.y) > 2 * abs(d.x) -> if (d.y > 0) Swipe.DOWN else Swipe.UP
                else -> null
            }
            if (swipe != null) onSwipe(swipe)
            break
        }
    }
}

/** The tools' narrowest, held sideways: three circles of a tool row, with the panel's margins. */
private val MIN_TOOLS_WIDTH = 248.dp

/** The timeline folded to a line: the playhead's place in it, dragged or tapped to move it. */
@Composable
private fun ScrubLine(totalMs: Long, playheadMs: () -> Long, onScrub: (Long?) -> Unit) {
    val accent = HeadsupColors.Accent
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 24.dp)
            .pointerInput(totalMs) {
                fun at(x: Float) = (x / size.width.coerceAtLeast(1) * totalMs).toLong().coerceIn(0L, totalMs)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // A tap moves the playhead there; a drag sideways scrubs; up or down is left to the
                    // screen's swipes (fold, full screen), so it never scrubs on the way.
                    var scrubbing = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!scrubbing) { onScrub(at(down.position.x)); onScrub(null) }
                            break
                        }
                        val d = change.position - down.position
                        if (!scrubbing) {
                            if (d.getDistance() < slop) continue
                            if (abs(d.y) > abs(d.x)) return@awaitEachGesture
                            scrubbing = true
                        }
                        change.consume()
                        onScrub(at(change.position.x))
                    }
                    if (scrubbing) onScrub(null)
                }
            }
            .drawBehind {
                val y = size.height / 2
                val f = if (totalMs > 0) (playheadMs().toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(accent, Offset(0f, y), Offset(size.width * f, y), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * f, y))
            },
    )
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
    onAddVideos: () -> Unit,
    onAddFit: () -> Unit,
    onAddSound: () -> Unit,
    onShowClip: (Int) -> Unit,
    onAddPhotos: () -> Unit,
) {
    // Two levels: all projects, and the open project with its files (pictures, sound, activity),
    // going back and forth between them. Everything the project uses is copied in here.
    var browsing by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Video") }
    if (browsing) {
        ProjectRow(
            state, vm,
            onOpen = { browsing = false },
            onNew = { kind ->
                // A new project goes straight to its files, and to the picker.
                vm.newProject(kind)
                browsing = false
                section = "Video"
                if (kind == ProjectKind.PHOTOS) onAddPhotos() else onAddVideos()
            },
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction("‹ Projects", { browsing = true })
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { ProjectHeader(state, vm) }
    }
    // The project's files, by kind.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        item { CircleOption("▶", if (state.photoProject) "Photos" else "Video", selected = section == "Video", modified = state.clips.isNotEmpty()) { section = "Video" } }
        if (!state.photoProject) item { CircleOption("♪", "Sound", selected = section == "Sound", modified = state.audioFiles.isNotEmpty()) { section = "Sound" } }
        item { CircleOption("FIT", "Activity", selected = section == "FIT", modified = state.activities.isNotEmpty()) { section = "FIT" } }
    }
    when (section) {
        "Sound" -> {
            AddTile(HeadsupIcons.Music, "Sound", onAddSound, Modifier.fillMaxWidth())
            SoundLibrary(state, vm)
        }
        "FIT" -> {
            AddTile(HeadsupIcons.Watch, "FIT file", onAddFit, Modifier.fillMaxWidth())
            if (state.activities.isEmpty()) Hint("The .FIT file(s) of the activity, from a watch, a band or a chest strap: for the dashboard, the data texts and the map.")
            state.activities.forEachIndexed { i, a -> ActivityRow(a, onRemove = { vm.removeActivity(i) }) }
        }
        else -> VideoFiles(state, vm, if (state.photoProject) onAddPhotos else onAddVideos, onShowClip)
    }
}

@Composable
private fun VideoFiles(state: UiState, vm: HeadsupViewModel, onAdd: () -> Unit, onShowClip: (Int) -> Unit) {
    AddTile(HeadsupIcons.Video, if (state.photoProject) "Photos" else "Videos and photos", onAdd, Modifier.fillMaxWidth())
    run {
        if (state.clips.isEmpty()) Hint("From the gallery or the cloud; copied into the project.")
        // One row per file, however many times it is cut on the timeline.
        val files = state.clips.withIndex().groupBy { it.value.meta.uri }
        for ((uri, uses) in files) {
            val first = uses.first()
            val m = first.value.meta
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onShowClip(first.index) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Hint(
                        listOfNotNull(
                            if (m.isRaw) "RAW photo" else if (m.isPhoto) "photo" else Formats.duration(m.durationMs),
                            "${m.width}×${m.height}",
                            m.frameRate?.takeIf { !m.isPhoto }?.let { String.format(Locale.US, "%.4g fps", it) },
                            m.format?.toString(),
                            if (uses.size > 1) "on the timeline ${uses.size}×" else null,
                        ).joinToString(" · "),
                    )
                    // Large clips play from a light copy in the preview; the export uses the original.
                    when (val p = state.proxies[uri]) {
                        is ProxyState.Making -> Hint("Preview file ${(p.progress * 100).toInt()}%")
                        ProxyState.Waiting -> Hint("Preview file waiting (made while the preview is paused)")
                        ProxyState.Failed -> Hint("Preview file failed: the preview plays the original")
                        is ProxyState.Ready -> Hint("Preview file ready (1080p)")
                        null -> Unit
                    }
                }
                SmallAction("Remove", { vm.removeClipsOf(uri) })
            }
        }
    }
}

/** Edit tools, as on the Colour page: one row of tools, one tool's controls under it. */
private enum class EditTab(val symbol: String, val label: String) {
    CUT("✂", "Cut"),
    SPEED("×", "Speed"),
    FADE("◐", "Fades"),
    FRAME("▭", "Frame"),
    RATE("fps", "Frame rate"),
    MOMENTS("★", "Moments"),
}

/** Editing the timeline: the selected clip (cut, speed, fades, framing), the frame rate, moments. */
@Composable
private fun EditPanel(
    state: UiState,
    vm: HeadsupViewModel,
    playhead: LongState,
    tab: EditTab,
    onTab: (EditTab) -> Unit,
    onCutScrub: (Long?) -> Unit,
    onShowHighlight: (FoundHighlight) -> Unit,
) {
    val clip = state.selected
    // An album's Edit page: the photos (above) and the selected one's crop.
    if (state.photoProject) {
        if (clip != null) PhotoCrop(state, clip, vm) else Hint("Add photos in Project → Files.")
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(EditTab.entries.filter { t -> if (state.photoProject) t == EditTab.FRAME else !(t == EditTab.SPEED && clip?.meta?.isPhoto == true) }) { t ->
            val modified = when (t) {
                EditTab.CUT -> clip?.isTrimmed == true
                EditTab.SPEED -> clip != null && clip.speed != 1f
                EditTab.FADE -> clip != null && (clip.fadeInMs > 0 || clip.fadeOutMs > 0)
                EditTab.RATE -> state.timelineFps != null
                else -> false
            }
            CircleOption(t.symbol, t.label, selected = t == tab, modified = modified) { onTab(t) }
        }
    }
    val index = state.selectedClip
    // Photos only: the frame (crop, shape, straightening) is what editing means.
    val tab = if (state.photoProject) EditTab.FRAME else tab
    val needsClip = tab == EditTab.CUT || tab == EditTab.SPEED || tab == EditTab.FADE
    if (needsClip && (index == null || clip == null)) {
        Hint("Select a clip.")
        return
    }
    when (tab) {
        EditTab.CUT -> CutTool(state, vm, playhead, onCutScrub)
        EditTab.SPEED -> if (clip != null && index != null && !clip.meta.isPhoto) {
            SpeedControls(clip, state.effectiveTimelineFps, onSpeed = { vm.setSpeed(index, it) }, onMute = { vm.setMuteChangedSpeed(index, it) })
        }
        EditTab.FADE -> if (clip != null && index != null) FadeTool(clip, index, vm)
        EditTab.FRAME -> FramePanel(state, vm)
        EditTab.RATE -> TimelineRate(state, vm)
        EditTab.MOMENTS -> HighlightControls(state, vm, onShowHighlight)
    }
}

/**
 * The timeline's frame rate, like a project setting in DaVinci: every clip is exported at it.
 * Clips with more frames are thinned out (or played slower); clips with fewer keep their own.
 */
@Composable
private fun TimelineRate(state: UiState, vm: HeadsupViewModel) {
    val fps = state.effectiveTimelineFps
    ValueHeader("Timeline frame rate", "${OutputSpec.formatFps(fps)} fps")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val auto = state.clips.firstOrNull()?.meta?.frameRate
        FilterChip(
            selected = state.timelineFps == null,
            onClick = { vm.setTimelineFps(null) },
            label = { Text("Auto" + (auto?.let { " (${OutputSpec.formatFps(it)})" } ?: "")) },
        )
        for (f in ExportSettings.FRAME_RATES) {
            FilterChip(selected = state.timelineFps == f, onClick = { vm.setTimelineFps(f) }, label = { Text(OutputSpec.formatFps(f)) })
        }
    }
    RateNotes(state, fps)
}

/** How each clip's frames fit [fps]: by its frame rate after its speed change. */
@Composable
private fun RateNotes(state: UiState, fps: Float) {
    // One line per clip, by its place on the timeline and file name.
    val notes = state.clips.withIndex().filter { !it.value.meta.isPhoto && it.value.meta.frameRate != null }.map { (i, c) ->
        val rate = c.meta.frameRate ?: fps
        val speed = c.speed
        val name = "${i + 1}. ${c.meta.name} · ${OutputSpec.formatFps(rate)} fps" + if (speed != 1f) " at ${String.format(Locale.US, "%.3g", speed)}×" else ""
        val r = rate * speed / fps
        when {
            kotlin.math.abs(r - 1f) < 0.02f -> "$name: every frame used"
            r > 1f -> "$name: every ${String.format(Locale.US, "%.3g", r)}th frame kept (\"All frames\" speed: ${String.format(Locale.US, "%.3g", speed / r)}× slow motion)"
            else -> "$name: ${OutputSpec.formatFps(rate * speed)} frames a second, each held longer"
        }
    }
    if (notes.isNotEmpty()) Hint(notes.joinToString("\n"))
}

/** The selected clip's cut (or a photo's time on screen), with split and delete. */
@Composable
private fun CutTool(state: UiState, vm: HeadsupViewModel, playhead: LongState, onCutScrub: (Long?) -> Unit) {
    val index = state.selectedClip ?: return
    val clip = state.selected ?: return
    if (clip.meta.isPhoto) {
        // A photo has no cut: its length on the timeline is chosen here.
        ValueHeader("Photo on screen", String.format(Locale.US, "%.1f s", clip.durationMs / 1000f))
        RulerSlider(
            clip.durationMs / 1000f, 1f..(ClipMetadataReader.PHOTO_MAX_MS / 1000f), 0.5f,
            { v -> vm.setTrim(index, 0L, (v * 1000).toLong()) },
            onReset = { vm.setTrim(index, 0L, HeadsupViewModel.PHOTO_DEFAULT_MS) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SmallAction("Delete clip", { vm.removeClip(index) }) }
    } else {
        CutControls(
            clip = clip,
            playhead = playhead,
            onTrim = { start, end -> vm.setTrim(index, start, end) },
            onSplit = { vm.splitClip(index, it) },
            onDelete = { vm.removeClip(index) },
            onScrub = onCutScrub,
        )
    }
}

/** Fades of the selected clip: picture, dashboard and sound together. */
@Composable
private fun FadeTool(clip: ClipState, index: Int, vm: HeadsupViewModel) {
    val maxS = (clip.timelineDurationMs / 1000f).coerceIn(0.5f, 10f)
    ValueHeader("Fade in from black", fadeLabel(clip.fadeInMs))
    RulerSlider(clip.fadeInMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, (v * 1000).toLong(), clip.fadeOutMs) }, onReset = { vm.setFades(index, 0L, clip.fadeOutMs) })
    ValueHeader("Fade out to black", fadeLabel(clip.fadeOutMs))
    RulerSlider(clip.fadeOutMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, clip.fadeInMs, (v * 1000).toLong()) }, onReset = { vm.setFades(index, clip.fadeInMs, 0L) })
}

/** Watch data for the selected clip: its start time, the match graph and a fine offset. */
@Composable
private fun WatchSyncControls(state: UiState, vm: HeadsupViewModel, playhead: LongState) {
    val index = state.selectedClip
    val clip = state.selected
    if (index == null || clip == null) {
        Hint("Select a clip.")
        return
    }
    SyncSummary(clip, clip.sync?.let { state.activities.getOrNull(it.trackIndex) })
    // A photo can take any moment of an activity, e.g. one taken on another day, or a summit.
    if (clip.meta.isPhoto) state.activities.firstOrNull()?.let { a -> PhotoMoment(clip, a.track) { vm.setManualStart(index, it) } }
    StartTimeControls(clip, state.activities) { vm.setManualStart(index, it) }
    val activity = clip.sync?.let { state.activities.getOrNull(it.trackIndex) }
    if (activity != null) SyncGraph(clip, activity.track, playhead) { vm.setManualOffset(index, clip.manualOffsetMs + it) }
    OffsetControls(clip.manualOffsetMs) { vm.setManualOffset(index, it) }
}

/** The project's sound files: music, ambience, effects. Placed on the audio tracks on the Audio page. */
@Composable
private fun SoundLibrary(state: UiState, vm: HeadsupViewModel) {
    if (state.audioFiles.isEmpty()) Hint("Music, ambience, effects (from Downloads or Music): copied into the project, then placed on the Audio page.")
    for (f in state.audioFiles) {
        val uses = state.audioClips.count { it.uri == f.uri }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Hint(listOfNotNull(f.durationMs.takeIf { it > 0 }?.let { Formats.duration(it) }, state.audioAnalysis[f.uri]?.rhythm?.bpm?.let { String.format(Locale.US, "≈ %.0f BPM", it) }, if (uses > 0) "used $uses×" else null).joinToString(" · "))
            }
            SmallAction("Remove", { vm.removeAudioFile(f.uri) })
        }
    }
}

/**
 * Sound: what is selected on the audio tracks (level, fades), adding a sound from the project's
 * library, and the level of the video clips' own sound. The tracks themselves are on the timeline.
 */
@Composable
private fun AudioPanel(
    state: UiState,
    vm: HeadsupViewModel,
    videoSelected: Boolean,
    slip: Boolean,
    onSlip: (Boolean) -> Unit,
    at: () -> Long,
    videoEnd: Long,
) {
    // As on the Colour page: what is edited in a row of tools, one ruler under it.
    var tool by rememberSaveable { mutableStateOf("Level") }
    val clip = state.audioClips.firstOrNull { it.id == state.selectedAudio }
    val index = state.selectedClip
    val video = state.selected?.takeIf { clip == null && videoSelected && index != null }
    if (clip == null && video == null) {
        ValueHeader("All clips' own sound", String.format(Locale.US, "%.0f %%", state.clipSound * 100))
        RulerSlider(state.clipSound, 0f..1f, 0.02f, vm::setClipSound, onReset = { vm.setClipSound(1f) })
        Hint("Tap a clip or a sound on the timeline for its own level and fades; + on the sound track adds a sound.")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (clip != null) {
            val p = clip.piece
            Column(Modifier.weight(1f)) {
                Text(clip.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Hint(listOfNotNull("${Formats.duration(p.startOnTimelineMs)}–${Formats.duration(p.endOnTimelineMs)}", state.audioAnalysis[clip.uri]?.rhythm?.bpm?.let { String.format(Locale.US, "≈ %.0f BPM", it) }).joinToString(" · "))
            }
        } else if (video != null) {
            Column(Modifier.weight(1f)) {
                Text(video.meta.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Hint("This clip's own sound")
            }
        }
    }
    if (clip != null) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallAction("Split", { vm.splitAudio(clip.id, at()) })
            SmallAction(if (slip) "Slip ✓" else "Slip", { onSlip(!slip) })
            if (clip.piece.endOnTimelineMs > videoEnd + 50) {
                SmallAction("Cut at end", { vm.updateAudio(clip.id) { it.with(it.piece.trimEnd(videoEnd - it.piece.endOnTimelineMs)) } })
            }
            SmallAction("Delete", { vm.removeAudio(clip.id) })
        }
    }
    val level = clip?.volume ?: video?.soundLevel ?: 1f
    val fadeIn = clip?.piece?.fadeIn ?: video?.fadeInMs ?: 0L
    val fadeOut = clip?.piece?.fadeOut ?: video?.fadeOutMs ?: 0L
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        item { CircleOption("Lv", "Level", selected = tool == "Level", modified = level != (if (clip != null) 0.8f else 1f)) { tool = "Level" } }
        item { CircleOption("In", "Fade in", selected = tool == "In", modified = fadeIn > 0) { tool = "In" } }
        item { CircleOption("Out", "Fade out", selected = tool == "Out", modified = fadeOut > 0) { tool = "Out" } }
    }
    if (clip != null) {
        val p = clip.piece
        val maxFade = (p.lengthMs / 1000f).coerceIn(0.5f, 20f)
        when (tool) {
            "In" -> {
                ValueHeader("Fade in", fadeLabel(p.fadeIn))
                RulerSlider(p.fadeIn / 1000f, 0f..maxFade, 0.1f, { v -> vm.updateAudio(clip.id) { it.copy(fadeInMs = (v * 1000).toLong().coerceAtMost(p.lengthMs - p.fadeOut)) } }, onReset = { vm.updateAudio(clip.id) { it.copy(fadeInMs = 0L) } })
            }
            "Out" -> {
                ValueHeader("Fade out", fadeLabel(p.fadeOut))
                RulerSlider(p.fadeOut / 1000f, 0f..maxFade, 0.1f, { v -> vm.updateAudio(clip.id) { it.copy(fadeOutMs = (v * 1000).toLong().coerceAtMost(p.lengthMs - p.fadeIn)) } }, onReset = { vm.updateAudio(clip.id) { it.copy(fadeOutMs = 0L) } })
            }
            else -> {
                ValueHeader("Level", String.format(Locale.US, "%.0f %%", clip.volume * 100))
                RulerSlider(clip.volume, 0f..1f, 0.02f, { v -> vm.updateAudio(clip.id) { it.copy(volume = v) } }, onReset = { vm.updateAudio(clip.id) { it.copy(volume = 0.8f) } })
            }
        }
    } else if (video != null && index != null) {
        val maxS = (video.timelineDurationMs / 1000f).coerceIn(0.5f, 10f)
        when (tool) {
            "In" -> {
                ValueHeader("Fade in", fadeLabel(video.fadeInMs))
                RulerSlider(video.fadeInMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, (v * 1000).toLong(), video.fadeOutMs) }, onReset = { vm.setFades(index, 0L, video.fadeOutMs) })
            }
            "Out" -> {
                ValueHeader("Fade out", fadeLabel(video.fadeOutMs))
                RulerSlider(video.fadeOutMs / 1000f, 0f..maxS, 0.1f, { v -> vm.setFades(index, video.fadeInMs, (v * 1000).toLong()) }, onReset = { vm.setFades(index, video.fadeInMs, 0L) })
            }
            else -> {
                ValueHeader("Level", String.format(Locale.US, "%.0f %%", video.soundLevel * 100))
                RulerSlider(video.soundLevel, 0f..1f, 0.02f, { v -> vm.setSoundLevel(index, v) }, onReset = { vm.setSoundLevel(index, 1f) })
            }
        }
    }
}

/** Picks a sound from the project's library and places it at the playhead, on a free track. */
@Composable
private fun AddSoundDialog(state: UiState, onPick: (AudioFile) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add sound") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.audioFiles.isEmpty()) Hint("The project has no sounds yet. Add them on the Files page (or Import here); they are copied into the project.")
                for (f in state.audioFiles) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(f) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Text(f.name, Modifier.weight(1f), maxLines = 1)
                        Text(if (f.durationMs > 0) Formats.duration(f.durationMs) else "", color = HeadsupColors.TextMuted)
                    }
                }
                Hint("Placed at the playhead on a free track, cut where the video ends.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onImport) { Text("Import…") } },
    )
}

private fun fadeLabel(ms: Long) = if (ms <= 0) "Off" else String.format(Locale.US, "%.1f s", ms / 1000f)


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
        Hint("Finds the best moments from your activity data (speed, descents, climbs, peaks) in the clips.")
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
private fun ProjectHeader(state: UiState, vm: HeadsupViewModel) {
    var renaming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val album = state.photoProject
            Text(
                (if (album) "Photo album" else "Film") + " · saved automatically" + when {
                    state.clips.isEmpty() -> ""
                    album -> " · ${state.clips.size} photos"
                    else -> {
                        // On the timeline: after speed changes, at the timeline's frame rate.
                        val total = state.clips.sumOf { it.timelineDurationMs }
                        " · ${state.clips.size} clips, ${Formats.duration(total)}, ${frameCount(total, state.effectiveTimelineFps) ?: 0L} frames"
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = HeadsupColors.TextMuted,
            )
            Text(state.projectName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        SmallAction("Rename", { renaming = true })
    }
    if (renaming) {
        RenameDialog(state.projectName, onDismiss = { renaming = false }, title = "Project name") { vm.renameProject(it); renaming = false }
    }
}

/** The saved projects (films and albums) and new ones. */
@Composable
private fun ProjectRow(state: UiState, vm: HeadsupViewModel, onOpen: () -> Unit, onNew: (ProjectKind) -> Unit) {
    Label("Projects")
    // A project from the list being renamed.
    var renamingOther by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshProjects() }
    val external = with(vm) { state.externalMedia }
    if (external.isNotEmpty() && !state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("${external.size} file(s) still come from the gallery or cloud; the project breaks if they move.", Modifier.weight(1f))
            SmallAction("Copy into project", vm::copyMediaIntoProject)
        }
    }
    // New: a film (timeline of clips and photos) or an album (photos edited one by one).
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AddTile(HeadsupIcons.Video, "New film", { onNew(ProjectKind.VIDEO) }, Modifier.weight(1f))
        AddTile(HeadsupIcons.Colour, "New album", { onNew(ProjectKind.PHOTOS) }, Modifier.weight(1f))
    }
    if (state.projects.isEmpty()) Hint("No saved projects yet. A project is saved once it has files.")
    // Films and albums listed apart, the kind open now first.
    val first = if (state.photoProject) ProjectKind.PHOTOS else ProjectKind.VIDEO
    val listed = state.projects.sortedBy { it.kind != first }
    for ((i, p) in listed.withIndex()) {
        if (i == 0 || listed[i - 1].kind != p.kind) Label(if (p.kind == ProjectKind.PHOTOS) "Albums" else "Films")
        val current = p.name == state.projectName
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (current) HeadsupColors.SurfaceHigh else Color.Transparent)
                // Opening one (or tapping the open one) goes on to its files.
                .clickable {
                    if (!current) vm.openProject(p.name)
                    onOpen()
                }
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
    if (state.projects.isNotEmpty()) Hint("Projects keep their own copies of their files. Deleting a project frees that space; the gallery is never touched.")
    renamingOther?.let { old ->
        RenameDialog(old, onDismiss = { renamingOther = null }, title = "Project name") { vm.renameProject(it, old); renamingOther = null }
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
        // Every frame of high-frame-rate footage at the timeline's rate (e.g. 120 fps on a 24 fps timeline: 0.2×).
        val conform = clip.meta.frameRate?.takeIf { !clip.meta.isPhoto && it > timelineFps + 0.5f }?.let { timelineFps / it }
        if (conform != null) {
            FilterChip(
                selected = kotlin.math.abs(clip.speed - conform) < 0.0005f,
                onClick = { onSpeed(conform) },
                label = { Text("All frames · ${String.format(Locale.US, "%.3g", conform)}×") },
            )
        }
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
    onDelete: () -> Unit,
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
        OutlinedButton(onClick = onDelete) { Text("Delete") }
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
    ZoneReason.ACTIVITY_ZONE -> "activity time zone (${Formats.utcOffset(offset)})"
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

/**
 * The menu over the Adjust (and Effects) button: what the page works on, copying, resetting.
 * Opened by tapping the button again; it takes the panel's place until Done or a choice.
 */
@Composable
private fun ScopeMenu(state: UiState, vm: HeadsupViewModel, adjust: Boolean, onDone: () -> Unit) {
    if (state.selected == null) return
    val album = state.photoProject
    val labels = if (album) listOf("This photo", "Group", "Masks", "All photos") else listOf("This clip", "Group", "Masks", "All clips")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (adjust) "Adjust" else "Effects", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        SmallAction("Done", onDone)
    }
    if (adjust) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                FilterChip(selected = state.target.ordinal == i, onClick = { vm.setTarget(GradeTarget.entries[i]); onDone() }, label = { Text(label) })
            }
        }
        if (state.target == GradeTarget.GLOBAL) Hint(if (album) "A change is set on every photo: each shows it under This photo." else "A change is set on every clip: each shows it under This clip.")
    } else {
        // Effects: this clip, or set on all clips (only what is changed, as a sync).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.effectsOnAll, onClick = { vm.setEffectsOnAll(false); onDone() }, label = { Text(labels[0]) })
            FilterChip(selected = state.effectsOnAll, onClick = { vm.setEffectsOnAll(true); onDone() }, label = { Text(labels[3]) })
        }
        if (state.effectsOnAll) Hint(if (album) "A change is set on every photo." else "A change is set on every clip.")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LookActions(state, vm)
        Spacer(Modifier.weight(1f))
        if (adjust) when (state.target) {
            GradeTarget.CLIP -> if (state.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
            GradeTarget.GLOBAL, GradeTarget.GROUP -> SmallAction("Reset all", { vm.resetSyncedPhotos() })
            GradeTarget.MASK -> Unit
        }
    }
}

/** Sections of the colour corrections, so the picker row stays short. */
private enum class ColourSection(val label: String) { LIGHT("Light"), PRESENCE("Presence"), COLOUR("Colour"), MIXER("Mixer"), FILM("Film"), LOOKS("Looks"), TOOLS("Tools") }

private val Adjustment.section: ColourSection
    get() = when (this) {
        Adjustment.EXPOSURE, Adjustment.BRIGHTNESS, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS,
        Adjustment.WHITES, Adjustment.BLACKS, Adjustment.ROLLOFF, Adjustment.CURVE -> ColourSection.LIGHT
        Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.SATURATION, Adjustment.VIBRANCE, Adjustment.SKIN -> ColourSection.COLOUR
        Adjustment.SPLIT, Adjustment.SHADOW_HUE, Adjustment.HIGHLIGHT_HUE, Adjustment.TAME, Adjustment.VIGNETTE, Adjustment.WHEELS -> ColourSection.FILM
        Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE -> ColourSection.PRESENCE
        Adjustment.CINE, Adjustment.LUT, Adjustment.MATCH, Adjustment.BLEND -> ColourSection.TOOLS
        Adjustment.MIXER -> ColourSection.MIXER
        Adjustment.LOOK -> ColourSection.LOOKS
    }

/** A row of text tabs: the selected one white and underlined, the others muted. */
@Composable
private fun SectionTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // Scrolls sideways when the column is too narrow for all of them (each stays on one line).
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) HeadsupColors.Highlight else HeadsupColors.TextMuted, maxLines = 1, softWrap = false)
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
    TEXTURE("Tx", "Texture", -1f..1f, 0.02f, "%+.2f", { it.texture }, { g, v -> g.copy(texture = v) }),
    CLARITY("Cl", "Clarity", -1f..1f, 0.02f, "%+.2f", { it.clarity }, { g, v -> g.copy(clarity = v) }),
    DEHAZE("Dh", "Dehaze", -1f..1f, 0.02f, "%+.2f", { it.dehaze }, { g, v -> g.copy(dehaze = v) }),

    /** Not a ruler value: opens the LUT library (the LUT is applied last, after all corrections). */
    LUT("LUT", "LUT", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a ruler value: matches the clip's colour to a reference frame from another clip. */
    MATCH("≈", "Shot match", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a ruler value: how the clip blends in from the previous clip's look. */
    BLEND("⇢", "Blend in", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: colour wheels for shadows, midtones and highlights. */
    WHEELS("◎", "Grading", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: point tone curves (RGB and per channel). */
    CURVE("∿", "Curve", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: hue, saturation and luminance per hue band. */
    MIXER("HSL", "Colour mixer", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: a look (profile) under the corrections, with its amount. */
    LOOK("Look", "Looks", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),
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
    playhead: LongState,
    onSeek: (clipMs: Long) -> Unit,
) {
    val index = state.selectedClip
    val clip = state.selected
    val album = state.photoProject
    if (index == null || clip == null) {
        Hint(if (album) "Select a photo first." else "Select a clip first.")
        return
    }
    // What the page works on (this clip, its group, masks, all) is chosen in the menu above the
    // Adjust button; only the group's and the masks' own controls stay here.
    val target = state.target
    val group = state.groupOf(clip)
    when (target) {
        GradeTarget.GROUP -> GroupControls(state, clip, index, group, vm)
        GradeTarget.MASK -> MaskList(state, clip, vm, playhead, onSeek)
        else -> Unit
    }
    if (target != GradeTarget.GLOBAL && !clip.colorGrade) {
        // HDR files (already graded, e.g. Mimo exports) start with their own colours.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(if (album) "This photo keeps its own colours." else "This clip keeps its own colours (an already graded HDR file).", Modifier.weight(1f))
            SmallAction("Adjust it", { vm.setClipGrade(index, true) })
        }
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
            Adjustment.CINE - Adjustment.MATCH - Adjustment.LOOK
    }
    val adjustment = if (adjustment in options) adjustment else Adjustment.EXPOSURE
    // On the Masks tab the ruler edits either a shape control or a correction.
    var shape by rememberSaveable { mutableStateOf<MaskShape?>(null) }
    var onShapes by rememberSaveable { mutableStateOf(true) }
    val shapes = mask?.takeIf { target == GradeTarget.MASK }?.let { m -> MaskShape.entries.filter { m.kind in it.kinds } }.orEmpty()
    val editingShape = if (target == GradeTarget.MASK && onShapes) shape?.takeIf { it in shapes } ?: shapes.firstOrNull() else null
    // Corrections are grouped into short sections (plus the mask's shape), so each row stays short.
    // A raw (DNG) photo is developed first (exposure, highlights, shadows, white balance on the raw data).
    // The raw development syncs like the rest from "all" and groups.
    val rawPhoto = clip.meta.isRaw && target != GradeTarget.MASK
    var onRaw by rememberSaveable { mutableStateOf(false) }
    val sections = listOfNotNull("RAW".takeIf { rawPhoto }, "Shape".takeIf { shapes.isNotEmpty() }) +
        ColourSection.entries.filter { s -> options.any { it.section == s } }.map { it.label }
    val section = if (onRaw && rawPhoto) "RAW" else if (editingShape != null) "Shape" else adjustment.section.label
    SectionTabs(sections, sections.indexOf(section).coerceAtLeast(0)) { i ->
        onRaw = sections[i] == "RAW"
        if (sections[i] == "RAW") return@SectionTabs
        if (sections[i] == "Shape") {
            onShapes = true
        } else {
            onShapes = false
            options.firstOrNull { it.section.label == sections[i] }?.let(onAdjustment)
        }
    }
    if (onRaw && rawPhoto) {
        RawControls(clip, index, vm)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (editingShape != null) {
            // A painted mask has its brush controls instead.
            items(if (mask?.kind == MaskKind.BRUSH) emptyList() else shapes) { m ->
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
                Adjustment.MIXER -> state.grade.mixer.any { it != 0f }
                Adjustment.CURVE -> state.grade.curves.any { it.isNotEmpty() }
                Adjustment.WHEELS -> state.grade.wheels.any { it != 0f }
                Adjustment.LOOK -> state.grade.look != null
                else -> a.get(state.grade) != a.neutral
            }
            CircleOption(a.symbol, a.label, selected = editingShape == null && a == adjustment, modified = modified) {
                onShapes = false
                onAdjustment(a)
            }
        }
    }
    if (editingShape != null && mask?.kind == MaskKind.BRUSH) {
        BrushControls(state, vm)
        return
    }
    if (editingShape != null && mask != null) {
        // A moving mask is shown and edited as it is at the playhead.
        val here = vm.maskAtPlayhead(mask)
        ValueHeader(editingShape.label, String.format(Locale.US, editingShape.format, editingShape.shown(here)))
        RulerSlider(
            value = editingShape.get(here),
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
            LutControls(state, clip.lutName, "None", { vm.setGroupLut(group.id, it) }, null, vm, onAddLut)
        } else {
            val inherited = group?.lutName
            LutControls(
                state, clip.lutName, if (inherited != null) "Group's ($inherited)" else "None", { vm.setClipLut(index, it) },
                if (state.clips.size > 1) vm::setLutForAllClips else null, vm, onAddLut,
            )
        }
        return
    }
    if (adjustment == Adjustment.MIXER) {
        MixerControls(state, vm)
        return
    }
    if (adjustment == Adjustment.CURVE) {
        CurveControls(state, vm)
        return
    }
    if (adjustment == Adjustment.WHEELS) {
        WheelControls(state, vm)
        return
    }
    if (adjustment == Adjustment.LOOK) {
        LookControls(state, vm)
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

/** Colour grading: a wheel per range (drag to the hue, outwards for more), its brightness, and the balance. */
@Composable
private fun WheelControls(state: UiState, vm: HeadsupViewModel) {
    val W = app.headsup.core.color.ColourWheels
    var range by rememberSaveable { mutableStateOf(app.headsup.core.color.ColourWheels.Range.MIDTONES) }
    val wheels = state.grade.wheels
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rg in app.headsup.core.color.ColourWheels.Range.entries) {
            val changed = W.saturation(wheels, rg) != 0f || W.luminance(wheels, rg) != 0f
            FilterChip(selected = rg == range, onClick = { range = rg }, label = { Text(rg.label + if (changed) " •" else "") })
        }
    }
    val hue = W.hue(wheels, range)
    val sat = W.saturation(wheels, range)
    fun set(index: Int, v: Float) = vm.updateGrade { it.copy(wheels = W.with(it.wheels, index, v)) }
    val hueIndex = range.ordinal * 3
    // The range's values above, the wheel under them, not wider than the panel allows.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(String.format(Locale.US, "%s: %.0f°, %.0f %%", range.label, hue, sat * 100), Modifier.weight(1f))
        if (sat != 0f || W.luminance(wheels, range) != 0f) {
            SmallAction("Reset", { vm.updateGrade { g -> g.copy(wheels = W.with(W.with(W.with(g.wheels, hueIndex, 0f), hueIndex + 1, 0f), hueIndex + 2, 0f)) } })
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            Modifier
                .size(minOf(150.dp, maxWidth * 0.75f))
                .pointerInput(range) {
                    fun pick(pos: Offset) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = pos.x - cx
                        val dy = cy - pos.y
                        val a = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat().let { if (it < 0) it + 360f else it }
                        val r = (kotlin.math.sqrt(dx * dx + dy * dy) / (minOf(cx, cy))).coerceIn(0f, 1f)
                        vm.updateGrade { g -> g.copy(wheels = W.with(W.with(g.wheels, hueIndex, a), hueIndex + 1, r)) }
                    }
                    detectDragGestures(onDragStart = { pick(it) }) { change, _ -> change.consume(); pick(change.position) }
                },
        ) {
            val radius = size.minDimension / 2f
            val colours = List(13) { i -> W.swatch(i * 30f).let { (r, g, b) -> Color(r, g, b) } }
            // The hue goes round anticlockwise from the right (as the angle is measured).
            drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(colours.reversed(), center), radius)
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color.Transparent), center, radius), radius)
            val a = Math.toRadians(hue.toDouble())
            val dot = Offset(center.x + (kotlin.math.cos(a) * sat * radius).toFloat(), center.y - (kotlin.math.sin(a) * sat * radius).toFloat())
            drawCircle(Color.White, 7.dp.toPx(), dot, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
    ValueHeader("${range.label} brightness", String.format(Locale.US, "%+.0f", W.luminance(wheels, range) * 100))
    RulerSlider(
        value = W.luminance(wheels, range), range = -1f..1f, step = 0.02f,
        onChange = { v -> set(hueIndex + 2, v) }, onReset = { set(hueIndex + 2, 0f) },
    )
    ValueHeader("Balance", String.format(Locale.US, "%+.0f", W.balance(wheels) * 100))
    RulerSlider(
        value = W.balance(wheels), range = -1f..1f, step = 0.02f,
        onChange = { v -> set(9, v) }, onReset = { set(9, 0f) },
    )
}

/**
 * Point tone curve: drag a point (or anywhere, which adds one) to bend the curve, double-tap a
 * point to remove it; RGB or one channel at a time.
 */
@Composable
private fun CurveControls(state: UiState, vm: HeadsupViewModel) {
    var channel by rememberSaveable { mutableStateOf(app.headsup.core.color.ToneCurve.Channel.RGB) }
    val c = channel.ordinal
    val flat = state.grade.curves.getOrNull(c).orEmpty()
    val colour = when (channel) {
        app.headsup.core.color.ToneCurve.Channel.RGB -> Color.White
        app.headsup.core.color.ToneCurve.Channel.RED -> Color(0xFFE0453A)
        app.headsup.core.color.ToneCurve.Channel.GREEN -> Color(0xFF4CB848)
        app.headsup.core.color.ToneCurve.Channel.BLUE -> Color(0xFF3A6FE0)
    }
    fun store(points: List<Pair<Float, Float>>) = vm.updateGrade { g ->
        val identity = points.size == 2 && points[0] == (0f to 0f) && points[1] == (1f to 1f)
        g.copy(curves = List(4) { i -> if (i == c) (if (identity) emptyList() else points.flatMap { listOf(it.first, it.second) }) else g.curves.getOrNull(i).orEmpty() })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ch in app.headsup.core.color.ToneCurve.Channel.entries) {
                val changed = state.grade.curves.getOrNull(ch.ordinal).orEmpty().isNotEmpty()
                FilterChip(selected = ch == channel, onClick = { channel = ch }, label = { Text(ch.label + if (changed) " •" else "") })
            }
        }
        if (flat.isNotEmpty()) SmallAction("Reset", { store(listOf(0f to 0f, 1f to 1f)) })
    }
    val points = app.headsup.core.color.ToneCurve.points(flat)
    val current by rememberUpdatedState(points)
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            // One handler for touching, dragging and double-tapping, working on its own copy of the
            // points (the stored ones arrive a frame later) and taking every move, so the page
            // does not scroll instead.
            .pointerInput(channel) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun at(p: Pair<Float, Float>) = Offset(p.first * w, (1f - p.second) * h)
                    val work = current.toMutableList()
                    var i = work.indices.minByOrNull { (at(work[it]) - down.position).getDistance() } ?: return@awaitEachGesture
                    val near = (at(work[i]) - down.position).getDistance() < 28.dp.toPx()
                    if (near && down.uptimeMillis - lastTap < 300 && i != 0 && i != work.lastIndex) {
                        // A double tap on a point removes it.
                        work.removeAt(i)
                        store(work.toList())
                        lastTap = 0L
                        return@awaitEachGesture
                    }
                    lastTap = down.uptimeMillis
                    if (!near) {
                        // A new point on the curve where the finger is.
                        val x = (down.position.x / w).coerceIn(0.01f, 0.99f)
                        work += x to app.headsup.core.color.ToneCurve.through(work, x)
                        work.sortBy { it.first }
                        i = work.indexOfFirst { it.first == x }
                        store(work.toList())
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        // A point stays between its neighbours; the ends keep their x.
                        val end = i == 0 || i == work.lastIndex
                        val lo = if (i == 0) 0f else work[i - 1].first + 0.01f
                        val hi = if (i == work.lastIndex) 1f else work[i + 1].first - 0.01f
                        val x = if (end) work[i].first else (change.position.x / w).coerceIn(lo, hi)
                        val y = (1f - change.position.y / h).coerceIn(0f, 1f)
                        work[i] = x to y
                        store(work.toList())
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // Quarters, and the straight line for reference.
        for (k in 1..3) {
            drawLine(Color.White.copy(alpha = 0.12f), Offset(w * k / 4, 0f), Offset(w * k / 4, h))
            drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, h * k / 4), Offset(w, h * k / 4))
        }
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, h), Offset(w, 0f))
        val path = androidx.compose.ui.graphics.Path()
        for (k in 0..100) {
            val x = k / 100f
            val y = app.headsup.core.color.ToneCurve.through(points, x)
            if (k == 0) path.moveTo(0f, (1 - y) * h) else path.lineTo(x * w, (1 - y) * h)
        }
        drawPath(path, colour, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        for ((x, y) in points) drawCircle(colour, 6.dp.toPx(), Offset(x * w, (1 - y) * h))
    }
    Hint("Touch the curve to add a point and drag it; double-tap a point to remove it.")
}

/** Colour mixer: a hue band, which of its hue, saturation and luminance, and a ruler for it. */
@Composable
private fun MixerControls(state: UiState, vm: HeadsupViewModel) {
    var band by rememberSaveable { mutableStateOf(app.headsup.core.color.ColourMixer.Band.RED) }
    var property by rememberSaveable { mutableStateOf(app.headsup.core.color.ColourMixer.Property.SATURATION) }
    val mixer = state.grade.mixer
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in app.headsup.core.color.ColourMixer.Property.entries) {
            FilterChip(selected = p == property, onClick = { property = p }, label = { Text(p.label) })
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(app.headsup.core.color.ColourMixer.Band.entries) { b ->
            val changed = app.headsup.core.color.ColourMixer.Property.entries.any { mixer.getOrElse(app.headsup.core.color.ColourMixer.index(b, it)) { 0f } != 0f }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(b.swatch))
                    .border(if (b == band) 3.dp else 1.dp, if (b == band) Color.White else HeadsupColors.Outline, CircleShape)
                    .clickable { band = b },
                contentAlignment = Alignment.Center,
            ) { if (changed) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White)) }
        }
    }
    val i = app.headsup.core.color.ColourMixer.index(band, property)
    val value = mixer.getOrElse(i) { 0f }
    fun set(g: ColorGrade, v: Float) = g.copy(mixer = g.mixer.toMutableList().also { m -> while (m.size < app.headsup.core.color.ColourMixer.SIZE) m += 0f; m[i] = v })
    ValueHeader("${band.label} · ${property.label.lowercase()}", String.format(Locale.US, "%+.0f", value * 100))
    RulerSlider(
        value = value,
        range = -1f..1f,
        step = 0.02f,
        onChange = { v -> vm.updateGrade { set(it, v) } },
        onReset = { vm.updateGrade { set(it, 0f) } },
        onHold = { held -> vm.setCompareGrade(if (held) set(state.grade, 0f) else null) },
    )
    if (mixer.any { it != 0f }) {
        Row {
            Spacer(Modifier.weight(1f))
            SmallAction("Reset mixer", { vm.updateGrade { it.copy(mixer = app.headsup.core.color.ColourMixer.NEUTRAL) } })
        }
    }
}

/** Looks (profiles): a starting rendering under the corrections, and how strongly. */
@Composable
private fun LookControls(state: UiState, vm: HeadsupViewModel) {
    val current = app.headsup.core.color.Look.of(state.grade.look)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current == null, onClick = { vm.updateGrade { it.copy(look = null) } }, label = { Text("None") })
        for (look in app.headsup.core.color.Look.entries) {
            FilterChip(selected = look == current, onClick = { vm.updateGrade { it.copy(look = look.name) } }, label = { Text(look.label) })
        }
    }
    if (current != null) {
        ValueHeader("Amount", String.format(Locale.US, "%.0f %%", state.grade.lookAmount * 100))
        RulerSlider(
            value = state.grade.lookAmount,
            range = 0f..1.5f,
            step = 0.05f,
            onChange = { v -> vm.updateGrade { it.copy(lookAmount = v) } },
            onReset = { vm.updateGrade { it.copy(lookAmount = 1f) } },
            onHold = { held -> vm.setCompareGrade(if (held) state.grade.copy(look = null) else null) },
        )
    } else {
        Hint("A look is the starting point; your corrections go on top of it.")
    }
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
            reference == null && state.photoProject -> "1. Select the photo whose colours you like and set it as the reference."
            reference == null -> "1. Pause on a good frame of the clip whose colours you like and set it as the reference."
            reference.clipId == clip.id && state.photoProject -> "Reference set. Now select another photo and match."
            reference.clipId == clip.id -> "Reference set. Now select another clip, pause on a similar frame and match."
            state.photoProject -> "Reference: ${reference.clipName}. Match this photo to it."
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
            FilterChip(selected = !wholeGroup, onClick = { wholeGroup = false }, label = { Text(if (state.photoProject) "This photo" else "This clip") })
        }
        Hint(
            if (wholeGroup) {
                "Matches the camera's colour (white balance, tint, saturation, skin) for every ${if (state.photoProject) "photo" else "clip"} in ${group.name}; each keeps its own brightness."
            } else {
                "Matches this ${if (state.photoProject) "photo" else "clip"} completely, brightness included."
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

/** The brush's settings (and the painted mask's softness), picked like the corrections and set with the same ruler. */
private enum class BrushSetting(val symbol: String, val label: String, val range: ClosedFloatingPointRange<Float>, val default: Float) {
    SIZE("●", "Size", 0.005f..0.3f, BrushSettings().radius),
    SOFTNESS("◐", "Softness", 0f..1f, ColourMask(MaskKind.BRUSH).softness),
    FLOW("≋", "Strength", 0.05f..1f, BrushSettings().flow),
}

/**
 * A painted mask: paint or erase with a finger, or move it (one finger drags, two also turn and
 * scale it); the brush's size, softness and flow.
 */
@Composable
private fun BrushControls(state: UiState, vm: HeadsupViewModel) {
    val mask = state.selected?.masks?.getOrNull(state.selectedMask) ?: return
    val brush = state.brush
    // Always the same settings (Size and Strength are for painting; Select uses only Softness).
    val settings = BrushSetting.entries
    var chosen by rememberSaveable { mutableStateOf(BrushSetting.SIZE) }
    val setting = chosen.takeIf { it in settings } ?: settings.first()
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in BrushMode.entries) {
                FilterChip(selected = brush.mode == mode, onClick = { vm.updateBrush { it.copy(mode = mode) } }, label = { Text(mode.label) })
            }
        }
        SmallAction("Clear", { vm.clearBrush() })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(settings) { b ->
            CircleOption(b.symbol, b.label, selected = b == setting, modified = false) { chosen = b }
        }
    }
    // Choosing Size shows the brush on the picture, as big as it paints.
    LaunchedEffect(setting) { vm.showBrushSize(setting == BrushSetting.SIZE) }
    DisposableEffect(Unit) { onDispose { vm.showBrushSize(false) } }
    val value = when (setting) {
        BrushSetting.SIZE -> brush.radius
        BrushSetting.SOFTNESS -> mask.softness
        BrushSetting.FLOW -> brush.flow
    }
    fun set(v: Float) = when (setting) {
        BrushSetting.SIZE -> vm.updateBrush { it.copy(radius = v) }
        // The whole painting's edge, at once.
        BrushSetting.SOFTNESS -> vm.updateMask { it.copy(softness = v) }
        BrushSetting.FLOW -> vm.updateBrush { it.copy(flow = v) }
    }
    ValueHeader(setting.label, String.format(Locale.US, "%.0f %%", value * 100f))
    RulerSlider(value = value, range = setting.range, step = 0.005f, onChange = { set(it) }, onReset = { set(setting.default) })
    Hint(
        if (brush.mode == BrushMode.SELECT) "Tap an object to select it (found on the phone by a small AI model). Or draw round an object and lift the finger: the object inside is found by its colours (as an oval: Invert for the outside). Or draw a line across (e.g. along the horizon, round a tree): the side above it is selected and the split follows the edges near the line (as a line mask: Invert for the other side). Touch up with Paint and Erase (Size and Strength are theirs; Softness also softens what Select finds)."
        else "Paint where the mask's corrections go; Erase takes them away. Strength: how much one stroke adds (at 50 % a stroke gives half the effect, going over again adds more). Softness: the painting's edge. Two fingers move, turn and scale the painting.",
    )
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
    SOFTNESS("◐", "Softness", setOf(MaskKind.LINEAR, MaskKind.ELLIPSE), 0.02f..1f, 0.02f, "%.0f %%", { it.softness }, { m, v -> m.copy(softness = v) }),
    ANGLE("∠", "Angle", MaskKind.entries.toSet(), -90f..90f, 0.5f, "%+.1f°", { it.angleDegrees }, { m, v -> m.copy(angleDegrees = v) }),
    ;

    /** Value as shown above the ruler (fractions as percent). */
    fun shown(mask: ColourMask): Float = if (this == ANGLE) get(mask) else get(mask) * 100f
}

/** The selected clip's masks: pick one to edit, add a line or an oval, invert or remove. */
@Composable
private fun MaskList(state: UiState, clip: ClipState, vm: HeadsupViewModel, playhead: LongState, onSeek: (Long) -> Unit) {
    if (clip.masks.isEmpty()) Hint("Windows with their own corrections: a line for the sky, an oval for a face, or paint where they go. Drag on the picture to move one; two fingers resize and turn it.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        clip.masks.forEachIndexed { i, m ->
            FilterChip(
                selected = i == state.selectedMask,
                onClick = { vm.selectMask(i) },
                label = {
                    Text("${i + 1} · " + when (m.kind) { MaskKind.LINEAR -> "Line"; MaskKind.ELLIPSE -> "Oval"; MaskKind.BRUSH -> "Brush" })
                },
            )
        }
        if (clip.masks.size < ColourMask.MAX_PER_CLIP) {
            FilterChip(selected = false, onClick = { vm.addMask(MaskKind.LINEAR) }, label = { Text("+ Line") })
            FilterChip(selected = false, onClick = { vm.addMask(MaskKind.ELLIPSE) }, label = { Text("+ Oval") })
            FilterChip(selected = false, onClick = { vm.addMask(MaskKind.BRUSH) }, label = { Text("+ Brush") })
        }
    }
    val mask = clip.masks.getOrNull(state.selectedMask) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction(if (mask.inverted) "Inverted ✓" else "Invert", { vm.updateMask { it.copy(inverted = !it.inverted) } })
        Spacer(Modifier.weight(1f))
        if (mask.grade != ColorGrade()) SmallAction("Reset", { vm.updateGrade { ColorGrade() } })
        SmallAction("Remove", { vm.removeMask(state.selectedMask) })
    }
    // Following movement in the shot: keys at moments of the clip, or tracked automatically.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (mask.keys.isEmpty()) "Stays put" else "Moves with the shot",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = HeadsupColors.TextMuted,
        )
        if (!clip.meta.isPhoto) {
            if (mask.keys.isEmpty()) {
                SmallAction("Track", { vm.trackMask(HeadsupViewModel.TrackDirection.BOTH) }, enabled = !state.busy)
            } else {
                // Continuing from a key placed by hand keeps what was already tracked.
                SmallAction("◀ Track", { vm.trackMask(HeadsupViewModel.TrackDirection.BACKWARD) }, enabled = !state.busy)
                SmallAction("Track ▶", { vm.trackMask(HeadsupViewModel.TrackDirection.FORWARD) }, enabled = !state.busy)
            }
        }
    }
    if (!clip.meta.isPhoto) {
        MaskKeyStrip(
            keys = mask.keys,
            cutStartMs = clip.trimStartMs,
            cutEndMs = clip.trimEndMs,
            playhead = playhead,
            onSeek = onSeek,
            onAdd = vm::addMaskKey,
            onDelete = vm::removeMaskKey,
            onClear = vm::clearMaskKeys,
        )
    }
    Hint(
        if (mask.keys.isEmpty()) "To follow movement: place the mask, then Track (it follows the picture both ways from here), or pause, + Key, and move the mask at a few moments."
        else "Pause on a key (or tap its diamond) and move the mask to change it; between keys it glides. Where tracking was lost, place the mask by hand there and Track ▶ to continue; only the part after it changes.",
    )
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
        if (onAll != null) SmallAction(if (state.photoProject) "Use for all photos" else "Use for all clips", { onAll(current) })
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
    if (group == null) {
        Hint(
            if (state.photoProject) "Photos shot the same way (e.g. drone) get the same LUT and corrections: a change here is set on each photo of the group."
            else "Clips shot the same way (e.g. drone) get the same LUT and corrections: a change here is set on each clip of the group.",
        )
    }
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
            SmallAction("Reset group", { vm.resetSyncedPhotos() })
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
        Hint(if (state.photoProject) "Select a photo first." else "Select a clip first.")
        return
    }
    if (state.photoProject) {
        PhotoCrop(state, clip, vm)
        return
    }
    // The timeline's frame: one shape for the whole project, like a DaVinci timeline's resolution.
    Label("Timeline frame")
    val first = state.clips.firstOrNull()?.meta
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((aspect, label) in Reframe.PRESETS) {
            val text = if (aspect == null) "First clip" + (first?.let { " (${it.width}×${it.height})" } ?: "") else label
            FilterChip(selected = state.frameAspect == aspect, onClick = { vm.setFrameAspect(aspect) }, label = { Text(text) })
        }
    }
    vm.outputPreview(state)?.let { Hint("Exported at ${it.width}×${it.height}.") }

    Label("This clip in the frame")
    val r = state.framing(clip)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = !r.fit, onClick = { vm.updateReframe { it.copy(fit = false) } }, label = { Text("Fill") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = r.fit, onClick = { vm.updateReframe { it.copy(fit = true) } }, label = { Text("Fit") })
        Spacer(Modifier.weight(1f))
        if (clip.reframe != Reframe(aspect = clip.reframe.aspect, fit = r.fit)) SmallAction("Reset", { vm.updateReframe { Reframe(fit = it.fit) } })
    }
    Hint(
        if (r.fit) "The whole picture inside the frame, black bars where the shapes differ; zoom in to fill more."
        else "The picture fills the frame; drag it to choose what stays, pinch to zoom. The grid shows the thirds.",
    )
    ValueHeader("Zoom", String.format(Locale.US, "%.2f×", r.zoom))
    RulerSlider(r.zoom, 1f..4f, 0.02f, { v -> vm.updateReframe { it.copy(zoom = v) } }, onReset = { vm.updateReframe { it.copy(zoom = 1f) } })
    ValueHeader("Straighten", String.format(Locale.US, "%+.1f°", r.angleDegrees))
    RulerSlider(
        r.angleDegrees, -15f..15f, 0.1f, { v -> vm.updateReframe { it.copy(angleDegrees = v) } },
        onReset = { vm.updateReframe { it.copy(angleDegrees = 0f) } },
    )
    PerspectiveRulers(r, vm)
    if (state.clips.size > 1) {
        Row {
            Spacer(Modifier.weight(1f))
            SmallAction(if (r.fit) "Fit all clips" else "Fill all clips", { vm.setFitForAll(r.fit) })
        }
    }
}

// Effects page

/** The row of effects; texts (for the whole timeline) are one of them. */
@Composable
private fun EffectKinds(state: UiState, kind: EffectKind, onKind: (EffectKind) -> Unit) {
    val effects = state.effects
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(EffectKind.entries) { e ->
            val active = when (e) {
                EffectKind.TEXT -> state.texts.isNotEmpty()
                EffectKind.MAP -> state.routeMap.enabled
                EffectKind.DASHBOARD -> state.style.enabled && state.effects.dashboard && state.activities.isNotEmpty()
                EffectKind.GRAPH -> effects.graph != null
                EffectKind.GRAIN -> effects.grain > 0f
                EffectKind.GLOW -> effects.glow > 0f
                EffectKind.HALATION -> effects.halation > 0f
                EffectKind.BARS -> effects.barsRatio != null
            }
            CircleOption(e.symbol, e.label, selected = e == kind, modified = active) { onKind(e) }
        }
    }
}

private enum class EffectKind(val symbol: String, val label: String) {
    TEXT("T", "Text"),
    MAP("Map", "Route map"),
    DASHBOARD("HUD", "Dashboard"),
    GRAPH("Gr", "Graph"),
    GRAIN("Gn", "Grain"),
    GLOW("Gl", "Glow"),
    HALATION("Ha", "Halation"),
    BARS("Cin", "Bars"),
}

/** Effects over the picture, picked from round options like the colour corrections. */
@Composable
private fun EffectsPanel(state: UiState, vm: HeadsupViewModel, kind: EffectKind, playhead: LongState, onKind: (EffectKind) -> Unit) {
    val style = state.style
    val effects = state.effects
    val activities = state.activities
    val updateStyle = vm::updateStyle
    val updateEffects = vm::updateEffects
    EffectKinds(state, kind, onKind)
    when (kind) {
        EffectKind.TEXT -> Unit
        EffectKind.MAP -> RouteMapControls(state, vm)
        EffectKind.DASHBOARD -> {
            // The dashboard shows the activity's data: without a FIT file there is nothing to set up.
            if (activities.isEmpty()) {
                Hint(if (state.photoProject) "Add the activity's FIT file (Project → Activity) to show its data on the photos." else "Add the activity's FIT file (Project → Activity) to show its data on the clips.")
                return
            }
            // On or off per clip (or photo); the layout and widgets are shared by the project.
            val on = style.enabled && effects.dashboard
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.photoProject) "Dashboard on this photo" else "Dashboard on this clip", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Switch(checked = on, onCheckedChange = { v ->
                    if (v && !style.enabled) updateStyle { it.copy(enabled = true) }
                    updateEffects { it.copy(dashboard = v) }
                })
            }
            if (on) {
                var tab by rememberSaveable { mutableStateOf(0) }
                PillTabs(listOf("Widgets", "Layout", "Text", "Sync"), tab) { tab = it }
                when (tab) {
                    0 -> WidgetControls(style, activities, updateStyle)
                    1 -> LayoutControls(style, updateStyle)
                    2 -> TextControls(style, updateStyle)
                    else -> WatchSyncControls(state, vm, playhead)
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
            if (state.timelineAspect < 16f / 9f - 0.01f) Hint("On this frame the bars take as much of the height as they would on 16:9, for the same look.")
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
) {
    val settings = state.exportSettings
    if (state.photoProject) {
        PhotoExport(state, vm)
        return
    }
    Label("Video")
    val active = vm.activePreset(state)
    SettingRow("Quick presets") {
        for (p in ExportPreset.entries) {
            FilterChip(selected = active == p, onClick = { vm.applyPreset(p) }, label = { Text(p.label) })
        }
    }
    // What will come out, so a preset's effect shows without scrolling through the options.
    vm.outputPreview(state)?.let { out ->
        Text(
            out.toString() + (if (state.exportSettings.wholeTimeline) "" else " · each clip"),
            style = MaterialTheme.typography.titleSmall,
            color = HeadsupColors.Accent,
        )
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
        FilterChip(selected = !tenBit, onClick = { vm.updateExportSettings { it.copy(tenBit = false) } }, label = { Text("8-bit") })
        FilterChip(
            selected = tenBit && !settings.hlg,
            onClick = { vm.updateExportSettings { it.copy(tenBit = true, hlg = false) } },
            enabled = tenBitBlocker == null,
            label = { Text("10-bit") },
        )
        FilterChip(
            selected = tenBit && settings.hlg,
            onClick = { vm.updateExportSettings { it.copy(tenBit = true, hlg = true) } },
            enabled = tenBitBlocker == null,
            label = { Text("10-bit HDR (HLG)") },
        )
    }
    if (tenBit && settings.hlg) {
        SettingRow("White in HDR") {
            for ((level, label) in HdrRoute.WHITE_LEVELS) {
                FilterChip(selected = settings.hdrWhite == level, onClick = { vm.updateExportSettings { it.copy(hdrWhite = level) } }, label = { Text(label) })
            }
        }
        Hint("For HDR screens. Phone galleries and editors show HLG darker than the 8-bit export, less so at higher white levels; while it plays the phone may brighten the screen.")
    }
    Hint(
        when {
                tenBit && settings.hlg -> "10-bit HLG: HEVC Main10, shown as HDR on HDR screens."
                tenBit -> "10-bit: HEVC Main10 Rec.709 like DJI Mimo's 10-bit export; looks like 8-bit, smoother gradients, more room for later grading."
                settings.codec == VideoCodec.HEVC -> "HEVC: smaller files."
                else -> "H.264: plays everywhere, larger files."
            } + (tenBitBlocker?.let { " 10-bit: $it" } ?: "") +
            (if (!tenBit && tenBitBlocker == null && state.clips.any { it.meta.format?.tenBit == true }) " Your clips are 10-bit: the 10-bit export keeps that." else "") +
            (if (!tenBit && tenBitBlocker == null && state.clips.any { it.meta.format?.transfer == "HLG" }) " HDR (HLG) clips are always exported in 10-bit." else ""),
    )
    Button(onClick = onExportVideos, enabled = state.clips.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (settings.wholeTimeline) "Export timeline" else "Export ${state.clips.size} clips")
    }
    Hint("Colour, effects, framing, speed; the dashboard where there is activity data. → ${MediaStoreOutput.VIDEO_FOLDER}")

    Label("Dashboard")
    val canOverlay = state.clips.any { it.sync != null }
    OutlinedButton(onClick = onExportOverlays, enabled = canOverlay, modifier = Modifier.fillMaxWidth()) { Text("Export dashboard only (PNG)") }
    Hint(
        if (canOverlay) "Transparent PNG frames (ZIP) per clip, for DaVinci → ${MediaStoreOutput.OVERLAY_FOLDER}"
        else "Needs a FIT file matched to the clips.",
    )
    if (state.clips.any { it.meta.isPhoto }) PhotoExport(state, vm)

    if (jobs.isNotEmpty()) {
        jobs.forEach { JobRow(it) }
        if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
            SmallAction("Cancel all", vm::cancelExports)
        } else {
            SmallAction("Clear list", vm::clearFinishedExports)
        }
    }
}

/** Photos on the timeline, each as a JPEG at its own resolution in the timeline's frame. */
@Composable
private fun PhotoExport(state: UiState, vm: HeadsupViewModel) {
    val settings = state.exportSettings
    val photoCount = state.clips.count { it.meta.isPhoto }
    Label("Photos")
    run {
        SettingRow("Photo size") {
            for ((side, label) in ExportSettings.PHOTO_SIZES) {
                FilterChip(selected = settings.photoLongSide == side, onClick = { vm.updateExportSettings { it.copy(photoLongSide = side) } }, label = { Text(label) })
            }
        }
        SettingRow("Format") {
            // Ultra HDR needs Android 14.
            for (f in app.headsup.export.PhotoFormat.entries.filter { it != app.headsup.export.PhotoFormat.ULTRA_HDR || android.os.Build.VERSION.SDK_INT >= 34 }) {
                FilterChip(selected = settings.photoFormat == f, onClick = { vm.updateExportSettings { it.copy(photoFormat = f) } }, label = { Text(f.label) })
            }
        }
        if (settings.photoFormat == app.headsup.export.PhotoFormat.ULTRA_HDR) {
            Hint("A JPEG with a gain map: on HDR screens (and in Google Photos) the brightest parts shine up to 2 stops brighter; elsewhere it looks like the JPEG.")
        }
        if (settings.photoFormat != app.headsup.export.PhotoFormat.TIFF16) {
            SettingRow("JPEG") {
                for ((q, label) in ExportSettings.PHOTO_QUALITIES) {
                    FilterChip(selected = settings.photoQuality == q, onClick = { vm.updateExportSettings { it.copy(photoQuality = q) } }, label = { Text(label) })
                }
            }
        } else {
            Hint("16 bits per colour, uncompressed: for further editing (Lightroom, Photoshop, DaVinci). Large files.")
        }
        val selectedPhoto = state.selected?.meta?.isPhoto == true
        val picked = state.clips.count { it.meta.isPhoto && it.picked }
        if (picked > 0) {
            Button(onClick = { vm.exportPhotos(HeadsupViewModel.PhotoSet.PICKED) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Export the marked photos ($picked)")
            }
        } else if (photoCount > 1) {
            Hint("Mark photos for export in Edit: hold a thumbnail, or Mark under the photos.")
        }
        if (selectedPhoto && photoCount > 1) {
            OutlinedButton(onClick = { vm.exportPhotos(HeadsupViewModel.PhotoSet.CURRENT) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export this photo (JPEG)") }
        }
        OutlinedButton(onClick = { vm.exportPhotos() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (photoCount == 1) "Export the photo (JPEG)" else "Export all $photoCount photos (JPEG)")
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


/** Tools of the Text page, as on the Colour page. */
private enum class TextTool(val symbol: String, val label: String) {
    LOOK("Aa", "Look"),
    DATA("{ }", "Data"),
    FONT("F", "Font"),
    COLOUR("●", "Colour"),
    SIZE("Sz", "Size"),
    GLOW("Gl", "Glow"),
    IN("In", "In"),
    OUT("Out", "Out"),
    TIME("⏱", "Time"),
}

/** Ready looks: one tap sets font, colour, glow, placement and animations. */
private enum class TextLook(val label: String, val apply: (TextItem) -> TextItem) {
    TITLE("Title", { it.copy(font = TextFont.SANS, colour = 0xFFFFFFFF.toInt(), size = 0.09f, glow = 0f, shadow = true, align = TextAlign.CENTER, x = 0.5f, y = 0.5f, animIn = TextAnim.POP, animOut = TextAnim.FADE) }),
    SUBTITLE("Subtitle", { it.copy(font = TextFont.SANS, colour = 0xFFFFFFFF.toInt(), size = 0.045f, glow = 0f, shadow = true, align = TextAlign.CENTER, x = 0.5f, y = 0.85f, animIn = TextAnim.FADE, animOut = TextAnim.FADE) }),
    LOWER_THIRD("Lower third", { it.copy(font = TextFont.SANS, colour = 0xFFFFFFFF.toInt(), size = 0.05f, glow = 0f, shadow = true, align = TextAlign.LEFT, x = 0.08f, y = 0.8f, animIn = TextAnim.RISE, animOut = TextAnim.FADE) }),
    HUD("HUD", { it.copy(font = TextFont.MONO, colour = 0xFF00FF66.toInt(), size = 0.07f, glow = 0.6f, shadow = false, align = TextAlign.LEFT, x = 0.08f, y = 0.78f, animIn = TextAnim.HUD, animOut = TextAnim.HUD) }),
    TYPED("Typed", { it.copy(font = TextFont.MONO, colour = 0xFFFFFFFF.toInt(), size = 0.055f, glow = 0f, shadow = true, align = TextAlign.CENTER, x = 0.5f, y = 0.5f, animIn = TextAnim.TYPE, animOut = TextAnim.FADE) }),
    HAND("Note", { it.copy(font = TextFont.HAND, colour = 0xFFFFE08A.toInt(), size = 0.08f, glow = 0.2f, shadow = true, align = TextAlign.CENTER, x = 0.5f, y = 0.35f, animIn = TextAnim.RISE, animOut = TextAnim.FADE) }),
}

private val TEXT_COLOURS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(),
    0xFF00FF66.toInt(), 0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(),
)

/**
 * Texts over the video: add one at the playhead, then its words, a ready look, font, colour,
 * size, glow, how it comes in and goes out, and its time. Drag it on the picture to place it;
 * on the timeline its bar moves it in time.
 */
@Composable
private fun TextPanel(state: UiState, vm: HeadsupViewModel, at: () -> Long) {
    var tool by rememberSaveable { mutableStateOf(TextTool.LOOK) }
    val item = state.texts.firstOrNull { it.id == state.selectedText }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallAction("+ Text", { vm.addText(at()) })
        if (item != null) {
            SmallAction("Delete", { vm.removeText(item.id) })
            SmallAction("Done", { vm.selectText(null) })
        }
    }
    if (item == null) {
        if (state.texts.isEmpty()) {
            Hint("Adds a text at the playhead. Drag it on the picture to place it; its bar on the timeline moves it in time.")
        } else {
            Hint("Tap a text's bar on the timeline to edit it.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val photoNow = state.selected
                val shown = if (state.photoProject && photoNow != null) state.texts.filter { it.showsOn(photoNow.id, photoNow.groupId) } else state.texts
                for (t in shown) FilterChip(selected = false, onClick = { vm.selectText(t.id) }, label = { Text(t.text.replace('\n', ' ').take(18)) })
            }
        }
        return
    }
    val update: ((TextItem) -> TextItem) -> Unit = { vm.updateText(item.id, it) }
    // In an album: which photos the text is on.
    val photo = state.selected
    if (state.photoProject && photo != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On", style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
            FilterChip(selected = item.onlyClip != null, onClick = { update { it.copy(onlyClip = photo.id, onlyGroup = null) } }, label = { Text("This photo") })
            FilterChip(selected = item.onlyClip == null && item.onlyGroup == null, onClick = { update { it.copy(onlyClip = null, onlyGroup = null) } }, label = { Text("All photos") })
        }
    }
    OutlinedTextField(
        value = item.text,
        onValueChange = { v -> update { it.copy(text = v) } },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(TextTool.entries.filter { !state.photoProject || it !in setOf(TextTool.IN, TextTool.OUT, TextTool.TIME) }) { t ->
            val modified = when (t) {
                TextTool.GLOW -> item.glow > 0f
                TextTool.IN -> item.animIn != TextAnim.NONE
                TextTool.OUT -> item.animOut != TextAnim.NONE
                else -> false
            }
            CircleOption(t.symbol, t.label, selected = t == tool, modified = modified) { tool = t }
        }
    }
    when (tool) {
        TextTool.LOOK -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // On photos the looks keep the text still.
            for (look in TextLook.entries) {
                FilterChip(
                    selected = false,
                    onClick = { update { look.apply(it).let { l -> if (state.photoProject) l.copy(animIn = TextAnim.NONE, animOut = TextAnim.NONE) else l } } },
                    label = { Text(look.label) },
                )
            }
        }
        TextTool.DATA -> {
            // Values from the watch and the photo, written into the text as {field}; tap to add one.
            val clip = state.selected
            val values = clip?.let { vm.textValues(state, it, 0L) }.orEmpty()
            for (source in FieldSource.entries) {
                val fields = TextField.entries.filter { it.source == source }
                Text(source.label, style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in fields) {
                        val v = values[f.key]
                        FilterChip(
                            selected = item.text.contains("{${f.key}}"),
                            onClick = { update { it.copy(text = if (it.text == "Text") "{${f.key}}" else it.text + " {${f.key}}") } },
                            label = { Text(if (v != null) "${f.label}: $v" else f.label, maxLines = 1) },
                            enabled = v != null,
                        )
                    }
                }
            }
            Hint("Fields show as {name} in the text; type around them, e.g. \"↑ {ascent} · {distance}\". Values come from the selected clip or photo.")
        }
        TextTool.FONT -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in TextFont.entries) FilterChip(selected = item.font == f, onClick = { update { it.copy(font = f) } }, label = { Text(f.label) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Left starts at the frame's left margin, centre sits in the middle.
                FilterChip(selected = item.align == TextAlign.LEFT, onClick = { update { it.copy(align = TextAlign.LEFT, x = 0.08f) } }, label = { Text("Left") })
                FilterChip(selected = item.align == TextAlign.CENTER, onClick = { update { it.copy(align = TextAlign.CENTER, x = 0.5f) } }, label = { Text("Centre") })
            }
        }
        TextTool.COLOUR -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in TEXT_COLOURS) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(if (item.colour == c) 3.dp else 1.dp, if (item.colour == c) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape)
                        .clickable { update { it.copy(colour = c) } },
                )
            }
        }
        TextTool.SIZE -> {
            ValueHeader("Size", String.format(Locale.US, "%.0f %%", item.size * 100))
            RulerSlider(item.size, 0.02f..0.25f, 0.005f, { v -> update { it.copy(size = v) } }, onReset = { update { it.copy(size = 0.07f) } })
        }
        TextTool.GLOW -> {
            ValueHeader("Glow", String.format(Locale.US, "%.2f", item.glow))
            RulerSlider(item.glow, 0f..1f, 0.02f, { v -> update { it.copy(glow = v) } }, onReset = { update { it.copy(glow = 0f) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shadow", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = item.shadow, onCheckedChange = { on -> update { it.copy(shadow = on) } })
            }
        }
        TextTool.IN, TextTool.OUT -> {
            val current = if (tool == TextTool.IN) item.animIn else item.animOut
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in TextAnim.entries) {
                    FilterChip(
                        selected = current == a,
                        onClick = { update { if (tool == TextTool.IN) it.copy(animIn = a) else it.copy(animOut = a) } },
                        label = { Text(a.label) },
                    )
                }
            }
            if (current == TextAnim.FADE || current == TextAnim.RISE || current == TextAnim.POP) {
                ValueHeader("Animation", String.format(Locale.US, "%.1f s", item.animMs / 1000f))
                RulerSlider(item.animMs / 1000f, 0.1f..3f, 0.1f, { v -> update { it.copy(animMs = (v * 1000).toLong()) } }, onReset = { update { it.copy(animMs = 500L) } })
            } else if (current == TextAnim.TYPE || current == TextAnim.HUD) {
                Hint("Types one letter every ${TextAnimation.CHAR_MS} ms.")
            }
        }
        TextTool.TIME -> {
            val end = state.clips.sumOf { it.timelineDurationMs }
            ValueHeader("On screen", String.format(Locale.US, "%.1f s", item.lengthMs / 1000f))
            RulerSlider(
                item.lengthMs / 1000f, 0.5f..(((end - item.startMs) / 1000f).coerceAtLeast(0.5f)), 0.1f,
                { v -> update { it.copy(lengthMs = (v * 1000).toLong()) } },
                onReset = { update { it.copy(lengthMs = 3_000L.coerceAtMost((end - it.startMs).coerceAtLeast(500L))) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("Start here", { val t = at(); update { it.copy(startMs = t.coerceIn(0L, (end - it.lengthMs).coerceAtLeast(0L))) } })
                SmallAction("End here", { val t = at(); update { it.copy(lengthMs = (t - it.startMs).coerceAtLeast(500L)) } })
            }
            Hint("From ${Formats.duration(item.startMs)} to ${Formats.duration(item.endMs)}.")
        }
    }
}

/** Development of a raw photo, before the colour corrections: one ruler at a time. */
private enum class RawControl(val symbol: String, val label: String, val range: ClosedFloatingPointRange<Float>, val step: Float, val format: String,
    val get: (app.headsup.core.raw.RawDevelop) -> Float, val set: (app.headsup.core.raw.RawDevelop, Float) -> app.headsup.core.raw.RawDevelop,
    val neutral: Float = 0f) {
    EXPOSURE("EV", "Exposure", -3f..3f, 0.05f, "%+.2f EV", { it.exposure }, { d, v -> d.copy(exposure = v) }),
    HIGHLIGHTS("Hl", "Highlights", -1f..1f, 0.02f, "%+.2f", { it.highlights }, { d, v -> d.copy(highlights = v) }),
    SHADOWS("Sh", "Shadows", -1f..1f, 0.02f, "%+.2f", { it.shadows }, { d, v -> d.copy(shadows = v) }),
    TEMPERATURE("K", "Temperature", -1f..1f, 0.02f, "%+.2f", { it.temperature }, { d, v -> d.copy(temperature = v) }),
    TINT("Tn", "Tint", -1f..1f, 0.02f, "%+.2f", { it.tint }, { d, v -> d.copy(tint = v) }),
    SHARPEN("Sh▲", "Sharpen", 0f..1f, 0.02f, "%.2f", { it.sharpen }, { d, v -> d.copy(sharpen = v) }, app.headsup.core.raw.RawDevelop().sharpen),
    NOISE("NR", "Colour noise", 0f..1f, 0.02f, "%.2f", { it.colourNoise }, { d, v -> d.copy(colourNoise = v) }, app.headsup.core.raw.RawDevelop().colourNoise),
}

@Composable
private fun RawControls(clip: ClipState, index: Int, vm: HeadsupViewModel) {
    var control by rememberSaveable { mutableStateOf(RawControl.EXPOSURE) }
    val develop = clip.rawDevelop
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(RawControl.entries) { c ->
            CircleOption(c.symbol, c.label, selected = c == control, modified = c.get(develop) != c.neutral) { control = c }
        }
    }
    ValueHeader(control.label, String.format(Locale.US, control.format, control.get(develop)))
    RulerSlider(
        value = control.get(develop),
        range = control.range,
        step = control.step,
        onChange = { v -> vm.updateRawDevelop(index) { control.set(it, v) } },
        onReset = { vm.updateRawDevelop(index) { control.set(it, control.neutral) } },
    )
    if (control == RawControl.SHARPEN || control == RawControl.NOISE) Hint("Judge these zoomed in: the export applies them at the photo's full size.")
    app.headsup.media.RawPhotos.lastError?.let { Hint("Developed by Android instead: $it") }
        ?: app.headsup.media.RawPhotos.infoOf(clip.meta.uri)?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
}

/** The photos of a photo project as thumbnails: tap one to edit it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumBar(state: UiState, vm: HeadsupViewModel, onOpen: () -> Unit) {
    val i = state.selectedClip ?: 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallAction("‹", { vm.selectClip(i - 1) }, enabled = i > 0)
        Hint("${i + 1} of ${state.clips.size}", Modifier.padding(horizontal = 6.dp))
        SmallAction("›", { vm.selectClip(i + 1) }, enabled = i < state.clips.lastIndex)
        Spacer(Modifier.weight(1f))
        SmallAction("Album ▾", onOpen)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoStrip(state: UiState, vm: HeadsupViewModel, onAdd: () -> Unit, arrange: Boolean, onFold: (() -> Unit)? = null) {
    if (onFold != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.weight(1f))
            SmallAction("Album ▴", onFold)
        }
    }
    val onSelect: (Int) -> Unit = vm::selectClip
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbs = remember { Filmstrip(context, scope) }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(state.clips.size) { i ->
            val c = state.clips[i]
            val selected = i == state.selectedClip
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HeadsupColors.Surface)
                    .border(if (selected) 2.dp else 1.dp, if (selected) HeadsupColors.Accent else HeadsupColors.Outline, RoundedCornerShape(10.dp))
                    .combinedClickable(onLongClick = { vm.togglePicked(i) }) { onSelect(i) },
            ) {
                // Read here, so this tile (not only the row) shows its picture when it arrives.
                thumbs.version.intValue
                thumbs.frame(c.meta.uri, 0L, 1L, photo = true)?.let { img ->
                    androidx.compose.foundation.Image(img, contentDescription = c.meta.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                if (c.picked) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(HeadsupColors.Highlight),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.OnHighlight) }
                }
            }
        }
        item {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(10.dp)).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(HeadsupIcons.Add, contentDescription = "Add", tint = HeadsupColors.Highlight) }
        }
    }
    // The selected photo: its place in the album, or out of it.
    val i = state.selectedClip
    if (arrange && i != null && i in state.clips.indices) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint("${i + 1} of ${state.clips.size}" + state.clips.count { it.picked }.let { if (it > 0) " · $it marked" else "" }, Modifier.weight(1f).padding(start = 4.dp))
            SmallAction(if (state.clips[i].picked) "✓ Marked" else "Mark", { vm.togglePicked(i) })
            SmallAction("◀ Move", { vm.moveClip(i, -1) }, enabled = i > 0)
            SmallAction("Move ▶", { vm.moveClip(i, 1) }, enabled = i < state.clips.lastIndex)
            SmallAction("Remove", { vm.removeClip(i) })
        }
        // Several at once: the marked photos (undo brings them back).
        val marked = state.clips.count { it.picked }
        if (marked > 1) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                SmallAction("Remove marked ($marked)", vm::removePicked)
            }
        }
    }
}

/** An album photo's crop: its shape (or its own), zoom and straightening; drag the picture to place it. */
@Composable
private fun PhotoCrop(state: UiState, clip: ClipState, vm: HeadsupViewModel) {
    val r = clip.reframe
    Label("Crop")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = r.aspect == null, onClick = { vm.updateReframe { it.copy(aspect = null) } }, label = { Text("Original") })
        for ((aspect, label) in Reframe.PRESETS) {
            if (aspect == null) continue
            FilterChip(selected = r.aspect == aspect, onClick = { vm.updateReframe { it.copy(aspect = aspect) } }, label = { Text(label) })
        }
    }
    Hint("Drag the picture to choose what stays, pinch to zoom.")
    ValueHeader("Zoom", String.format(Locale.US, "%.2f×", r.zoom))
    RulerSlider(r.zoom, 1f..4f, 0.02f, { v -> vm.updateReframe { it.copy(zoom = v) } }, onReset = { vm.updateReframe { it.copy(zoom = 1f) } })
    ValueHeader("Straighten", String.format(Locale.US, "%+.1f°", r.angleDegrees))
    RulerSlider(r.angleDegrees, -15f..15f, 0.1f, { v -> vm.updateReframe { it.copy(angleDegrees = v) } }, onReset = { vm.updateReframe { it.copy(angleDegrees = 0f) } })
    PerspectiveRulers(r, vm)
}

/** Perspective correction: lines leaning together (a building shot from below) set straight. */
@Composable
private fun PerspectiveRulers(r: Reframe, vm: HeadsupViewModel) {
    ValueHeader("Vertical", String.format(Locale.US, "%+.0f", r.vertical * 100))
    RulerSlider(r.vertical, -1f..1f, 0.01f, { v -> vm.updateReframe { it.copy(vertical = v) } }, onReset = { vm.updateReframe { it.copy(vertical = 0f) } })
    ValueHeader("Horizontal", String.format(Locale.US, "%+.0f", r.horizontal * 100))
    RulerSlider(r.horizontal, -1f..1f, 0.01f, { v -> vm.updateReframe { it.copy(horizontal = v) } }, onReset = { vm.updateReframe { it.copy(horizontal = 0f) } })
    Hint("Vertical straightens a building's sides when the camera looked up or down; Horizontal the same sideways. The picture zooms in just enough to hide the empty corners.")
}

/** The activity's route over the picture: on or off, size, colour, background and the "you are here" dot. */
@Composable
private fun RouteMapControls(state: UiState, vm: HeadsupViewModel) {
    val map = state.routeMap
    val hasRoute = state.activities.any { it.track.has(app.headsup.core.track.Metric.LATITUDE) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Route map", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = map.enabled, onCheckedChange = { on -> vm.updateRouteMap { it.copy(enabled = on) } }, enabled = hasRoute)
    }
    if (!hasRoute) {
        Hint("Needs a FIT file with GPS (Project → Activity).")
        return
    }
    if (!map.enabled) return
    ValueHeader("Size", String.format(Locale.US, "%.0f %%", map.size * 100))
    RulerSlider(map.size, 0.12f..0.6f, 0.01f, { v -> vm.updateRouteMap { it.copy(size = v) } }, onReset = { vm.updateRouteMap { it.copy(size = 0.3f) } })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in TEXT_COLOURS) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (map.colour == c) 3.dp else 1.dp, if (map.colour == c) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape)
                    .clickable { vm.updateRouteMap { it.copy(colour = c) } },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = map.background, onClick = { vm.updateRouteMap { it.copy(background = !it.background) } }, label = { Text("Background") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = map.marker, onClick = { vm.updateRouteMap { it.copy(marker = !it.marker) } }, label = { Text("Where it is") })
    }
    Hint("Drag on the picture to place it. The dot shows where the clip or photo is: the photo's GPS, or the activity's position at that moment.")
}

/** Draws the route map for [clip] at a position in it (the marker follows the clip). */
private fun routeOverlayFor(vm: HeadsupViewModel, state: UiState, clip: ClipState): (android.graphics.Canvas, Float, Float, Long) -> Unit {
    val track = vm.mapTrack(state, clip)
    val map = state.routeMap
    return { canvas, w, h, pos ->
        val at = vm.mapMarker(state, clip, pos)
        app.headsup.render.RouteMapRenderer.draw(canvas, w, h, map, track, at?.first, at?.second)
    }
}

/** A grip under the picture: dragging it up or down gives the picture or the tools more room. */
@Composable
private fun SideHandle(onDrag: (Float) -> Unit) {
    val state = androidx.compose.foundation.gestures.rememberDraggableState(onDrag)
    Box(
        Modifier
            .fillMaxHeight()
            .width(28.dp)
            .draggable(state, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(HeadsupColors.Outline))
    }
}

@Composable
private fun PreviewHandle(onDrag: (Float) -> Unit) {
    val state = androidx.compose.foundation.gestures.rememberDraggableState(onDrag)
    // A tall touch area around the small bar, so it is easy to catch.
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .draggable(state, Orientation.Vertical),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(HeadsupColors.Outline))
    }
}

/**
 * A text placed at [x], [y] (0..1), pulled to the middle and to the margins when close, so it
 * lines up without fiddling: the centre of the frame, or a margin of 6 % from an edge.
 */
private fun snapText(t: TextItem, x: Float, y: Float): TextItem {
    val pull = 0.025f
    fun snap(v: Float, targets: List<Float>) = targets.minByOrNull { kotlin.math.abs(it - v) }?.takeIf { kotlin.math.abs(it - v) < pull } ?: v
    val xs = if (t.align == TextAlign.LEFT) listOf(0.06f, 0.5f) else listOf(0.5f)
    return t.copy(x = snap(x, xs), y = snap(y, listOf(0.1f, 0.5f, 0.9f)))
}

/** The moment of [track] a photo shows the watch data of: a ruler along the activity. */
@Composable
private fun PhotoMoment(clip: ClipState, track: app.headsup.core.track.ActivityTrack, onSet: (LocalDateTime?) -> Unit) {
    val offset = track.utcOffsetSeconds ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(track.startMs)).totalSeconds
    val zone = java.time.ZoneOffset.ofTotalSeconds(offset)
    val totalMin = ((track.endMs - track.startMs) / 60_000f).coerceAtLeast(1f)
    // Where the photo is now (its matched moment), or the start.
    val atMs = clip.telemetryUtc(0L)?.takeIf { it in track.startMs..track.endMs } ?: track.startMs
    val minutes = (atMs - track.startMs) / 60_000f
    val sample = app.headsup.core.track.TelemetrySampler(track).frameAt(atMs)
    val clock = Formats.clock(atMs, offset).take(5)
    val distance = sample[app.headsup.core.track.Metric.DISTANCE]?.let { String.format(Locale.US, " · %.2f km", it / 1000) }.orEmpty()
    val altitude = sample[app.headsup.core.track.Metric.ALTITUDE]?.let { " · ${it.toInt()} m" }.orEmpty()
    ValueHeader("Moment in the activity", clock + distance + altitude)
    RulerSlider(
        minutes, 0f..totalMin, (totalMin / 400f).coerceAtLeast(0.05f),
        { v -> onSet(LocalDateTime.ofInstant(Instant.ofEpochMilli(track.startMs + (v * 60_000).toLong()), zone)) },
        onReset = { onSet(null) },
    )
    Hint("Drag to pick the moment whose data the photo shows (dashboard, text fields, the dot on the map). Double-tap the ruler to go back to the photo's own time.")
}

