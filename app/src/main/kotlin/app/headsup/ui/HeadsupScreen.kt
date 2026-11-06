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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.sp
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
import app.headsup.project.ProjectInfo
import app.headsup.core.edit.TextAlign
import app.headsup.core.edit.TextAnim
import app.headsup.core.edit.TextAnimation
import app.headsup.core.edit.TextFont
import app.headsup.core.edit.TextItem
import app.headsup.core.edit.TextField
import app.headsup.core.edit.FieldSource
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKind
import app.headsup.core.color.MaskJoin
import app.headsup.core.color.MaskRange
import app.headsup.core.color.LumaRange
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
    // What is drawn over the picture: texts, the route map, the dashboard, the graph.
    OVERLAYS("Overlays", HeadsupIcons.Dashboard),
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
    val editingTexts = tool == Tool.OVERLAYS && effectKind == EffectKind.TEXT
    // Set when a clip ended during playback, so the next one starts playing on its own.
    var continuePlaying by remember { mutableStateOf(false) }
    // Where to jump in the selected clip, e.g. after tapping a highlight.
    var seekRequest by remember { mutableStateOf<Long?>(null) }
    val measuring = tool == Tool.COLOUR && adjustment == Adjustment.SKIN && state.target != GradeTarget.MASK
    val editingMasks = tool == Tool.COLOUR && state.target == GradeTarget.MASK && state.selected?.colorGrade == true
    val editingBrush = state.selected?.masks?.getOrNull(state.selectedMask)?.kind == MaskKind.BRUSH
    // A range mask has no window to move; while a colour is being picked a tap picks it.
    val editingRange = state.selected?.masks?.getOrNull(state.selectedMask)?.kind == MaskKind.RANGE
    val pickingColour = editingMasks && state.pickingColour
    // Only while a mask's shape is being changed (Shape) does a finger on the picture change it;
    // with its corrections the picture is left alone (the outline still shows).
    var maskTools by rememberSaveable { mutableStateOf(false) }
    val shaping = editingMasks && maskTools
    // Read only where needed (cut controls), so playback does not recompose the whole screen.
    val playhead = remember { mutableLongStateOf(0L) }
    // File position of a cut handle being dragged; read only by the preview.
    val cutScrub = remember { mutableStateOf<Long?>(null) }
    // Grabs the paused preview frame, for the cine assistant.
    val frameSource = remember { mutableStateOf<(() -> Bitmap?)?>(null) }
    // Grabs the picture at a chosen width, for finding objects and the sky in masks.
    val pictureSource = remember { mutableStateOf<((Int) -> Bitmap?)?>(null) }
    val exporting = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    // The timeline: a clip selected for trimming, slipping sounds, and where it is being dragged to.
    var videoSelected by remember { mutableStateOf(false) }
    var slip by remember { mutableStateOf(false) }
    var addingSound by remember { mutableStateOf(false) }
    // Timeline position shown while dragged (and until the picture has caught up), and the clip position sent to the preview.
    val scrubT = remember { mutableStateOf<Long?>(null) }
    val scrubTo = remember { mutableStateOf<Long?>(null) }
    // When the finger left the timeline (or a jump was asked for): a timeline position held past
    // that is let go once the picture moves, so a later jump (a flag, a key) moves the timeline too.
    val scrubLeftAt = remember { mutableLongStateOf(0L) }
    // The preview plays (or was just asked to); read by the timeline while it glides.
    val previewPlaying = remember { mutableStateOf(false) }
    val clipStarts = state.clips.runningFold(0L) { a, c -> a + c.timelineDurationMs }
    fun timelineOf(clipMs: Long): Long {
        val i = state.selectedClip ?: return 0L
        val c = state.clips.getOrNull(i) ?: return 0L
        return clipStarts[i] + c.timelineOfSource(clipMs)
    }
    /** The clip under timeline position [t] and the position in it (file time from its cut). */
    fun locate(t: Long): Pair<Int, Long> {
        val i = clipStarts.indexOfLast { it <= t }.coerceIn(0, (state.clips.size - 1).coerceAtLeast(0))
        val c = state.clips.getOrNull(i) ?: return 0 to 0L
        val local = (t - clipStarts[i]).coerceIn(0L, c.timelineDurationMs)
        return i to c.sourceOfTimeline(local)
    }
    fun jumpTo(t: Long) {
        val (i, pos) = locate(t)
        if (i != state.selectedClip) vm.selectClip(i)
        scrubT.value = t
        scrubLeftAt.longValue = android.os.SystemClock.uptimeMillis()
        seekRequest = pos
    }
    /** Shows clip [i] at [clipMs] (file time from its cut), the timeline going there with the picture. */
    fun showInClip(i: Int, clipMs: Long) {
        val c = state.clips.getOrNull(i) ?: return
        if (i != state.selectedClip) vm.selectClip(i)
        scrubT.value = clipStarts[i] + c.timelineOfSource(clipMs)
        scrubLeftAt.longValue = android.os.SystemClock.uptimeMillis()
        seekRequest = clipMs
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
    var showOriginal by remember { mutableStateOf(false) }
    // A finger held on the picture while a mask's corrections are edited: the picture without that mask only.
    var hideMask by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }
    // Held sideways the picture's size is set by the width of its column (dragged left and right).
    // Sideways the screen is low: the picture leaves room under it for the transport and timeline.
    val share = if (wide) 0.48f else if (state.photoProject) albumShare else filmShare
    var wideShare by rememberSaveable { mutableStateOf(0.56f) }
    // The transport and timeline can fold away (play on the picture, the timeline a line), any way
    // the phone is held.
    var compact by rememberSaveable { mutableStateOf(false) }
    val folded = compact && !fullscreen && !state.photoProject
    // An album's photo with its info row and the album folded away (any way the phone is held).
    val photoFolded = compact && state.photoProject && !fullscreen && tool != Tool.FILES
    // Full screen keeps a line to scrub along under a film's picture, and play on it.
    val fullscreenFilm = fullscreen && !state.photoProject && state.clips.isNotEmpty()
    // The picture grows and shrinks smoothly when the transport folds or full screen changes.
    // While the handle under the picture is dragged the picture follows the finger at once (an
    // animation each step made it lag and catch up in jumps).
    var handleDragging by remember { mutableStateOf(false) }
    val previewMaxHeight by androidx.compose.animation.core.animateDpAsState(
        when {
            fullscreenFilm -> configuration.screenHeightDp.dp - 40.dp
            fullscreen -> configuration.screenHeightDp.dp
            folded && wide -> (configuration.screenHeightDp * 0.84f).dp
            // Upright the picture takes the room the transport and timeline leave.
            folded -> (configuration.screenHeightDp * share + 150).dp
            photoFolded && wide -> (configuration.screenHeightDp - 40).dp
            photoFolded -> (configuration.screenHeightDp * albumShare + 110).dp
            // Sideways in an album with the photos folded into their bar: the photo takes the height
            // left over by the info row and the bar.
            else -> (configuration.screenHeightDp * share).dp
        },
        if (handleDragging) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(220),
        label = "preview height",
    )
    // A small settle of the whole screen when full screen comes or goes.
    val settle = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(fullscreen) {
        settle.snapTo(0.96f)
        settle.animateTo(1f, androidx.compose.animation.core.tween(220))
    }
    // Swipes on what the picture's tools leave alone: they never fight a mask, framing or a text being moved.
    // A mask only takes the finger while its shape is worked on (Mask), not with its corrections (Adjust).
    val pictureFree = !shaping && !framing && !editingTexts && !measuring
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
                    // Every job shown here can be stopped (AI tracking keeps what it found); only
                    // opening a project runs to its end, and a stop already asked for.
                    onCancel = if (listOf("Opening", "Stopping").none { state.loadingMessage?.startsWith(it) == true }) vm::stopWork else null,
                )
            }
            // Work in the background: one slim line each, the app usable meanwhile.
            for (w in state.work) WorkLine(w) { vm.stopBackground(w.id) }

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
                        masks = if (selected.colorGrade && !showOriginal) (if (hideMask) withoutLayer(selected.masks, state.selectedMask) else selected.masks) else emptyList(),
                        maskCubes = state.previewMaskCubes,
                        fromCube = if (showOriginal) null else state.previewFromCube,
                        fromMasks = if (showOriginal) emptyList() else state.previewFromMasks,
                        fromMaskCubes = state.previewFromMaskCubes,
                        fromMaskMs = state.previewFromMaskMs,
                        transitionMs = selected.transitionMs,
                        guideMask = selected.masks.getOrNull(state.selectedMask).takeIf { editingMasks && !fullscreen },
                        onMoveMask = if (shaping && !fullscreen && !editingBrush && !editingRange && !pickingColour) { x, y -> vm.updateMask { it.copy(centerX = x, centerY = y) } } else null,
                        // A painted mask moves with two fingers only in Move; otherwise they zoom the view.
                        onTransformMask = if (shaping && !fullscreen && !editingRange && !pickingColour && (!editingBrush || state.brush.mode == BrushMode.MOVE)) vm::transformMask else null,
                        onMaskEnd = if (shaping && !fullscreen && !editingBrush && !editingRange && !pickingColour) vm::dragMaskEnd else null,
                        zoomForPainting = shaping && editingBrush && state.brush.mode != BrushMode.MOVE && !fullscreen,
                        // View: one finger moves the zoomed picture, nothing is painted.
                        panView = shaping && editingBrush && state.brush.mode == BrushMode.VIEW && !fullscreen,
                        maskOverlay = state.maskOverlay,
                        onBrush = if (shaping && !fullscreen && editingBrush && !pickingColour && state.brush.mode != BrushMode.MOVE && state.brush.mode != BrushMode.VIEW) vm::brushStroke else null,
                        guideIndex = state.selectedMask,
                        lassoPath = state.lassoPath,
                        onPictureHeight = vm::notePictureHeight,
                        paintPath = state.paintPath,
                        paintRadius = state.brush.radius,
                        paintStrength = state.brush.flow,
                        paintErase = state.brush.mode == BrushMode.ERASE,
                        brushRingAt = state.brushSizedAt,
                        brushSoftness = selected.masks.getOrNull(state.selectedMask)?.softness ?: 0.5f,
                        vignette = if (selected.colorGrade && !showOriginal) state.appliedGrade(selected).vignette else 0f,
                        presence = if (selected.colorGrade && !showOriginal) state.appliedGrade(selected).presence else app.headsup.core.color.Presence.NONE,
                        effects = if (showOriginal) app.headsup.render.EffectSettings(dashboard = false) else state.compareEffects ?: state.effects,
                        // Held on the picture: without this mask while a mask is adjusted, else the original.
                        // (Not while the finger shapes a mask or measures a colour.)
                        onHoldOriginal = if (tool == Tool.COLOUR && !measuring && !shaping && !pickingColour) { held ->
                            if (editingMasks) hideMask = held else showOriginal = held
                        } else null,
                        showingOriginal = showOriginal || hideMask,
                        originalLabel = if (hideMask) "Without mask ${state.selectedMask + 1}" else "Original",
                        onSample = when {
                            fullscreen -> null
                            measuring -> vm::sampleFrame
                            pickingColour -> vm::pickMaskColour
                            else -> null
                        },
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
                            if (target != null && scrubTo.value == null &&
                                (abs(timelineOf(it) - target) < 80 || android.os.SystemClock.uptimeMillis() - scrubLeftAt.longValue > 1500)
                            ) scrubT.value = null
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
                        neighbourFrom = state.previewNeighbourFrom,
                        cubesFor = state.previewCubesFor,
                        // In an album each photo shows only its own texts (or its group's, or all photos').
                        texts = if (state.photoProject) state.texts.filter { it.showsOn(selected.id, selected.groupId) } else state.texts,
                        photoMode = state.photoProject,
                        showTransport = fullscreenFilm || (!fullscreen && (!state.photoProject || tool == Tool.EDIT || tool == Tool.COLOUR)),
                        compactTransport = folded || fullscreenFilm || photoFolded,
                        onCompact = if (!fullscreen) { c -> compact = c } else null,
                        // Read here so arriving place names redraw the texts.
                        textValues = state.placesVersion.let { _ -> { pos -> vm.textValues(state, selected, pos) } },
                        routeOverlay = if (state.routeMap.enabled) routeOverlayFor(vm, state, selected) else null,
                        // A finger on the picture places the text being edited, or the route map.
                        onMoveText = if (fullscreen) null else state.selectedText?.takeIf { editingTexts }?.let { id -> { x, y -> vm.updateText(id) { snapText(it, x, y) } } }
                            ?: if (tool == Tool.OVERLAYS && effectKind == EffectKind.MAP && state.routeMap.enabled) { x: Float, y: Float -> vm.updateRouteMap { it.copy(x = x, y = y) } } else null,
                        cutScrub = cutScrub,
                        onFrameSource = { frameSource.value = it },
                        onPictureSource = { pictureSource.value = it },
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
                        // ⇤ at a clip's start, ⇥ at its end: to the start of the clip before or after.
                        onFlag = vm::toggleFlag,
                        startMs = vm.playheadInClip(),
                        onStepClip = { step ->
                            val i = (state.selectedClip ?: 0) + step
                            if (i in state.clips.indices) jumpTo(clipStarts[i])
                        },
                        onEnded = {
                            // The timeline plays through: the next clip follows.
                            val next = (state.selectedClip ?: -1) + 1
                            if (next in state.clips.indices) {
                                continuePlaying = true
                                vm.selectClip(next)
                            }
                        },
                    )
                    // What an undo or redo just changed, for a moment.
                    if (!fullscreen) UndoNotice(state.notice, state.noticeAt, Modifier.align(Alignment.TopCenter).padding(top = 52.dp))
                    // Undo/redo tucked into the picture's corner, only when there is something to undo.
                    if (!fullscreen) UndoRedo(state.canUndo, state.canRedo, vm::undo, vm::redo, vm::undoAll, vm::resetAllEdits, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 24.dp), canResetAll = state.clips.isNotEmpty(),
                        milestones = state.milestones, onAddMilestone = vm::addMilestone, onBackToMilestone = vm::backToMilestone, onRemoveMilestone = vm::removeMilestone,
                    )
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
                // A double tap on it folds the transport and the timeline (or the album) away and
                // brings them back.
                PreviewHandle(
                    onToggle = { if (!state.photoProject || tool != Tool.FILES) compact = !compact },
                    onDragging = { handleDragging = it },
                ) { dy ->
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
                if (photoFolded) {
                    // Folded: sideways a small arrow brings the info row and the album back (or a
                    // swipe up); upright a double tap on the handle above does.
                    if (wide) Box(Modifier.fillMaxWidth().clickable { compact = false }.padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Icon(HeadsupIcons.ChevronUp, "Show the album", Modifier.size(20.dp), tint = HeadsupColors.TextMuted)
                    }
                } else if (state.clips.isNotEmpty()) {
                    // The album's photos are always shown (also on Export, where the marked ones
                    // are exported); a double tap on the handle folds them away.
                    PhotoStrip(state, vm, onAdd = addPhotos, arrange = tool == Tool.FILES)
                }
            } else if (state.clips.isNotEmpty() && (folded || fullscreenFilm)) {
                // Folded: the timeline is a line to scrub along.
                ScrubLine(
                    totalMs = clipStarts.last(),
                    playheadMs = { scrubT.value ?: timelineOf(playhead.longValue) },
                    onScrub = { t ->
                        if (t == null) {
                            scrubTo.value = null
                            scrubLeftAt.longValue = android.os.SystemClock.uptimeMillis()
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
                            scrubLeftAt.longValue = android.os.SystemClock.uptimeMillis()
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
            val panelScroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(HeadsupColors.CardGradient)
                    .border(1.dp, HeadsupColors.Outline, RoundedCornerShape(28.dp))
                    .scrollBar(panelScroll)
                    .verticalScroll(panelScroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.messages.isNotEmpty()) Messages(state.messages, vm::dismissMessages)
                when (tool) {
                    Tool.FILES -> FilesPanel(
                        state, vm, onAddVideos = addVideos, onAddFit = addFit, onAddSound = { soundPicker.launch(arrayOf("audio/*")) },
                        onAddPhotos = addPhotos,
                        onShowClip = { i -> jumpTo(clipStarts[i]) },
                    )
                    Tool.EDIT -> EditPanel(
                        state, vm, playhead, editTab, { editTab = it }, onCutScrub = { cutScrub.value = it },
                        onJump = { id, ms ->
                            val i = state.clips.indexOfFirst { it.id == id }
                            if (i >= 0) showInClip(i, ms)
                        },
                    ) { f ->
                        val index = state.clips.indexOfFirst { it.id == f.clipId }
                        if (index >= 0) showInClip(index, f.positionMs)
                    }
                    Tool.AUDIO -> AudioPanel(
                        state, vm, videoSelected, slip, onSlip = { slip = it },
                        at = { scrubT.value ?: timelineOf(playhead.longValue) }, videoEnd = clipStarts.last(),
                        onAddSound = { addingSound = true },
                    )
                    Tool.COLOUR -> ColourPage(
                        state, vm, adjustment, { adjustment = it }, onAddLut = { lutPicker.launch(arrayOf("*/*")) }, frameSource.value,
                        playhead, onSeek = { ms -> state.selectedClip?.let { showInClip(it, ms) } ?: run { seekRequest = ms } }, pictureSource = pictureSource.value,
                        maskTools = maskTools, onMaskTools = { maskTools = it },
                    )
                    Tool.OVERLAYS -> {
                        // Saved from before the looks moved to Adjust: back to an overlay.
                        val kind = effectKind.takeIf { it in OVERLAY_KINDS } ?: EffectKind.DASHBOARD
                        if (kind == EffectKind.TEXT) {
                            EffectKinds(state, kind, OVERLAY_KINDS) { effectKind = it }
                            TextPanel(state, vm, at = { scrubT.value ?: timelineOf(playhead.longValue) })
                        } else {
                            EffectsPanel(state, vm, kind, playhead, OVERLAY_KINDS) { effectKind = it }
                        }
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
            // A dot on Adjust when it works on more than this clip (its scope row says what).
            BottomToolbar(
                tool, tools,
                labelOf = { t -> if (state.photoProject && t == Tool.EDIT) "Crop" else t.label },
                badgeOf = { t -> t == Tool.COLOUR && (state.target == GradeTarget.GROUP || state.target == GradeTarget.GLOBAL) },
            ) { tool = it }
    }

    // Swipes over the picture and timeline. Sideways: in the upper part up (or right) goes full
    // screen, in the lower part down folds the transport and timeline away and up brings them back.
    // Upright: up goes full screen; folding is by the arrows on the transport only.
    fun onScreenSwipe(swipe: Swipe, upper: Boolean) {
        if (!wide) {
            if (swipe == Swipe.UP) fullscreen = true
            return
        }
        when {
            upper && swipe == Swipe.UP && !(folded || photoFolded) -> fullscreen = true
            swipe == Swipe.RIGHT -> fullscreen = true
            swipe == Swipe.UP && (folded || photoFolded) -> compact = false
            !upper && swipe == Swipe.DOWN && (!state.photoProject || tool != Tool.FILES) -> compact = true
            else -> Unit
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
                    .freeSwipe(pictureFree) { if (it == Swipe.DOWN || (wide && it == Swipe.LEFT)) fullscreen = false },
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
                            .zonedSwipe(pictureFree, ::onScreenSwipe),
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
                Column(Modifier.zonedSwipe(pictureFree, ::onScreenSwipe), content = pictureAndTimeline)
                // In an album, a swipe down over the tools folds the photo's info row and the album
                // away (more room for the photo), up brings them back.
                Column(
                    Modifier.weight(1f).fillMaxWidth().freeSwipe(state.photoProject) {
                        if (it == Swipe.DOWN && tool != Tool.FILES) compact = true else if (it == Swipe.UP) compact = false
                    },
                ) { tools() }
            }
        }
        // The album over everything (the whole screen, either way the phone is held).
        if (state.albumOpen && state.photoProject) AlbumBrowser(state, vm) { vm.closeAlbum() }
    }
}

@Composable
private fun BottomToolbar(selected: Tool, tools: List<Tool>, labelOf: (Tool) -> String, badgeOf: (Tool) -> Boolean, onSelect: (Tool) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Only squeezed to the most (the tools beside the picture, the panel at its narrowest):
        // smaller icons and labels.
        val each = (maxWidth - 44.dp) / tools.size.coerceAtLeast(1)
        val narrow = each < 40.dp
        Row(
            Modifier
                .padding(horizontal = if (narrow) 8.dp else 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(HeadsupColors.Surface)
                .border(1.dp, HeadsupColors.Outline, shape)
                .padding(if (narrow) 4.dp else 6.dp),
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
                        .padding(vertical = if (narrow) 7.dp else 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(t.icon, contentDescription = labelOf(t), Modifier.size(if (narrow) 18.dp else 22.dp), tint = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted)
                        if (badgeOf(t)) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(7.dp).clip(CircleShape).background(HeadsupColors.Accent))
                    }
                    Spacer(Modifier.height(if (narrow) 2.dp else 0.dp))
                    Text(
                        labelOf(t),
                        style = MaterialTheme.typography.labelSmall.let { if (narrow) it.copy(fontSize = 10.sp, letterSpacing = 0.sp) else it },
                        color = if (on) HeadsupColors.OnHighlight else HeadsupColors.TextMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
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
private fun UndoRedo(
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onUndoAll: () -> Unit, onResetAll: () -> Unit,
    modifier: Modifier, canResetAll: Boolean = false,
    milestones: List<Milestone> = emptyList(), onAddMilestone: () -> Unit = {}, onBackToMilestone: (Long) -> Unit = {}, onRemoveMilestone: (Long) -> Unit = {},
) {
    // Shown while there is something to undo, or edits to remove (holding undo works even when a tap would not).
    if (!canUndo && !canRedo && !canResetAll) return
    var askAll by remember { mutableStateOf(false) }
    val hold = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    if (askAll) {
        AlertDialog(
            onDismissRequest = { askAll = false },
            title = { Text("Undo all changes?") },
            text = {
                Text(
                    "Since opened: back to how the project was when you opened it.\n\nEvery edit: colour, masks, " +
                        "framing, effects, speed and cuts removed from every clip, however long ago they were made; " +
                        "the files, sound and texts stay.\n\nEither way, undo brings the changes back.",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { askAll = false; onResetAll() }) { Text("Every edit") }
                    TextButton(onClick = { askAll = false; onUndoAll() }) { Text("Since opened") }
                }
            },
            dismissButton = { TextButton(onClick = { askAll = false }) { Text("Cancel") } },
        )
    }
    var showMilestones by remember { mutableStateOf(false) }
    if (showMilestones) {
        AlertDialog(
            onDismissRequest = { showMilestones = false },
            title = { Text("Milestones") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Moments of the edit to come back to. Going back is a step of its own: undo returns to where you are now. Kept while the project is open.",
                        style = MaterialTheme.typography.bodySmall, color = HeadsupColors.TextMuted,
                    )
                    for (m in milestones.asReversed()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓ Milestone ${m.number} · ${m.time}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { showMilestones = false; onBackToMilestone(m.id) }) { Text("Go back") }
                            Text(
                                "✕",
                                Modifier.clip(CircleShape).clickable { onRemoveMilestone(m.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = HeadsupColors.TextMuted,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onAddMilestone() }) { Text("Add milestone here") } },
            dismissButton = { TextButton(onClick = { showMilestones = false }) { Text("Close") } },
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Milestones: moments of the edit to come back to (a list: add, go back, remove).
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { showMilestones = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HeadsupIcons.Check, "Milestones", Modifier.size(18.dp), tint = if (milestones.isNotEmpty()) HeadsupColors.Accent else Color.White.copy(alpha = 0.85f))
        }
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
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    // The ring fills in the hold's real time, whatever the system's animation scale.
                                    val filling = scope.launch(RealTimeMotion) { hold.animateTo(1f, androidx.compose.animation.core.tween(UNDO_ALL_HOLD_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
                                    val up = withTimeoutOrNull(UNDO_ALL_HOLD_MS) { waitForUpOrCancellation() }
                                    filling.cancel()
                                    scope.launch { hold.snapTo(0f) }
                                    if (up != null) { if (enabled) action() } else askAll = true
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

private const val UNDO_ALL_HOLD_MS = 900L

/** Animations at their written length, whatever the system's animation scale. */
private object RealTimeMotion : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float = 1f
}

private enum class Swipe { LEFT, RIGHT, UP, DOWN }

/**
 * A thin bar at the right edge while there is more to scroll: where the view is and how much of it
 * shows. Drawn over the content (taking no room); a finger along the edge drags it.
 */
private fun Modifier.scrollBar(state: androidx.compose.foundation.ScrollState): Modifier = this
    .drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val h = size.height
        val inset = 18.dp.toPx()
        val track = h - 2 * inset
        val shown = h / (h + max)
        val barH = (track * shown).coerceAtLeast(24.dp.toPx())
        val y = inset + (track - barH) * state.value / max
        val x = size.width - 5.dp.toPx()
        drawLine(
            Color.White.copy(alpha = if (state.isScrollInProgress) 0.55f else 0.22f),
            Offset(x, y), Offset(x, y + barH),
            strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
    .pointerInput(state) {
        // Only a finger that starts on the very edge drags the bar; the rest of the panel scrolls as usual.
        val edge = 14.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (down.position.x < size.width - edge || state.maxValue <= 0) return@awaitEachGesture
            down.consume()
            val inset = 18.dp.toPx()
            val track = (size.height - 2 * inset).coerceAtLeast(1f)
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                val f = ((change.position.y - inset) / track).coerceIn(0f, 1f)
                state.dispatchRawDelta(f * state.maxValue - state.value)
            }
        }
    }

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

/**
 * [freeSwipe] told apart by where it starts: in the upper half or the lower. Swipes in the upper
 * half (over the picture) wait for [upperFree] (the picture's own tools leave it alone); in the
 * lower half (the transport, timeline, the picture's foot) they always count, as anything there
 * that uses the finger takes the moves first.
 */
private fun Modifier.zonedSwipe(upperFree: Boolean, onSwipe: (Swipe, Boolean) -> Unit): Modifier = pointerInput(upperFree) {
    val distance = 56.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
        val upper = down.position.y < size.height / 2f
        if (upper && !upperFree) return@awaitEachGesture
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
            if (swipe != null) onSwipe(swipe, upper)
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

/** A job running in the background: what it is, how far, and Stop. */
@Composable
private fun WorkLine(work: BackgroundWork, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(50)).background(HeadsupColors.Surface).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(50))
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            work.label + when {
                work.waiting -> " · waiting"
                work.progress != null -> String.format(Locale.US, " · %.0f %%", work.progress * 100)
                else -> ""
            },
            style = MaterialTheme.typography.labelMedium, maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        val p = work.progress
        if (p != null && !work.waiting) {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f).height(3.dp), color = HeadsupColors.Accent, trackColor = HeadsupColors.Outline, drawStopIndicator = {})
        } else {
            Spacer(Modifier.weight(1f))
        }
        SmallAction("Stop", onStop)
    }
}

/** A short line over the picture saying what an undo or redo changed; it fades after a moment. */
@Composable
private fun UndoNotice(text: String?, at: Long, modifier: Modifier) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(at) {
        if (text == null || at == 0L) return@LaunchedEffect
        shown = text
        kotlinx.coroutines.delay(1_800)
        shown = null
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = shown != null, modifier = modifier,
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
    ) {
        Text(
            shown.orEmpty(),
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 5.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Label(text: String) {
    // A section's name with a line after it, as everywhere in the panels.
    SectionCaption(text)
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
    ProjectHeader(state, vm, onBack = { browsing = true })
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
    /** Shows the clip [clipId] at [clipMs] (from its cut). */
    onJump: (clipId: Long, clipMs: Long) -> Unit,
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
                EditTab.SPEED -> clip != null && clip.speedChanged
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
        EditTab.CUT -> CutTool(state, vm, playhead, onCutScrub) { i, fileMs ->
            // A flag: its clip shown at that moment.
            state.clips.getOrNull(i)?.let { c -> onJump(c.id, fileMs - c.trimStartMs) }
        }
        EditTab.SPEED -> if (clip != null && index != null && !clip.meta.isPhoto) {
            SpeedPanel(clip, index, state.effectiveTimelineFps, vm, playhead)
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
        val speed = c.averageSpeed
        val name = "${i + 1}. ${c.meta.name} · ${OutputSpec.formatFps(rate)} fps" + if (speed != 1f) " at ${String.format(Locale.US, "%.3g", speed)}×" + (if (c.speedCurve.isNotEmpty()) " on average" else "") else ""
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
private fun CutTool(state: UiState, vm: HeadsupViewModel, playhead: LongState, onCutScrub: (Long?) -> Unit, onFlag: (Int, Long) -> Unit) {
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
        SectionCaption("Clip")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PanelAction("Delete clip", { vm.removeClip(index) }, Modifier.weight(1f), danger = true) }
    } else {
        CutControls(
            clip = clip,
            playhead = playhead,
            onTrim = { start, end -> vm.setTrim(index, start, end) },
            onSplit = { vm.splitClip(index, it) },
            onDelete = { vm.removeClip(index) },
            onScrub = onCutScrub,
        )
        ShotCut(state, vm, index, clip, playhead)
        // Backwards: a reversed copy of the cut is made once (it takes a while on long clips).
        SectionCaption("Direction")
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillAction("⇆", if (clip.reversed != null) "Reversed" else "Reverse", { vm.reverseClip(index) }, selected = clip.reversed != null, enabled = !state.busy)
            Spacer(Modifier.width(10.dp))
            Hint(
                if (clip.reversed != null) "Plays backwards, without its sound. Tap again to play it forwards."
                else "Plays the clip backwards (without its sound). Its look and flags stay.",
                Modifier.weight(1f),
            )
        }
        FlagList(state, vm, onFlag)
    }
}

/**
 * A cut inside one shot (the clip and its neighbour are pieces of the same file): moved to the
 * playhead with each piece keeping its edits, or the pieces joined into one clip again.
 */
@Composable
private fun ShotCut(state: UiState, vm: HeadsupViewModel, index: Int, clip: ClipState, playhead: LongState) {
    val before = index > 0 && vm.piecesOfOneShot(state.clips[index - 1], clip)
    val after = vm.piecesOfOneShot(clip, state.clips.getOrNull(index + 1))
    if (!before && !after) return
    // With pieces on both sides: the cut nearer the playhead.
    val playheadMs = clip.trimStartMs + playhead.longValue
    val left = when {
        before && after -> if (playheadMs - clip.trimStartMs < clip.trimEndMs - playheadMs) index - 1 else index
        before -> index - 1
        else -> index
    }
    var ask by remember { mutableStateOf(false) }
    SectionCaption("Cut inside the shot")
    Hint(
        if (left == index) "This clip and the next are pieces of one shot. Move the cut to the playhead (each piece keeps its edits), or join them."
        else "This clip and the one before are pieces of one shot. Move the cut to the playhead (each piece keeps its edits), or join them.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillAction("⇹", "Move cut here", { vm.moveCut(left, playheadMs) })
        PillAction("⊕", "Join", {
            if (vm.editedAlike(state.clips[left], state.clips[left + 1])) vm.joinWithNext(left, keepRight = false) else ask = true
        })
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Join the pieces") },
            text = { Text("The two pieces are edited differently (colour, effects, masks, framing or speed). The joined clip keeps one piece's edits; flags and fades of both stay. Undo brings both pieces back.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { ask = false; vm.joinWithNext(left, keepRight = false) }) { Text("Keep first") }
                    TextButton(onClick = { ask = false; vm.joinWithNext(left, keepRight = true) }) { Text("Keep second") }
                }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
        )
    }
}

/** The film's flags in timeline order: tap one to go there (cut there with Split). */
@Composable
private fun FlagList(state: UiState, vm: HeadsupViewModel, onFlag: (Int, Long) -> Unit) {
    val starts = state.clips.runningFold(0L) { a, c -> a + c.timelineDurationMs }
    val flags = state.clips.flatMapIndexed { i, c ->
        c.flags.filter { it >= c.trimStartMs && it <= c.trimEndMs }.map { f -> Triple(i, f, starts[i] + c.timelineOfSource(f - c.trimStartMs)) }
    }.sortedBy { it.third }
    SectionCaption("Flags") {
        Spacer(Modifier.width(8.dp))
        if (flags.isNotEmpty()) SmallAction("Clear all", { vm.clearFlags() })
    }
    if (flags.isEmpty()) {
        Hint("⚑ under the picture flags the moment at the playhead: a place to cut or to work on later. Lit, it is on a flag: tapped then, it takes the flag away.")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, f, t) in flags) {
            val label = String.format(Locale.US, "%d:%04.1f", t / 60_000, (t % 60_000) / 1000f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillAction("⚑", label, { onFlag(i, f) })
                Text(
                    "✕",
                    Modifier.clip(CircleShape).clickable { vm.removeFlag(i, f) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted,
                )
            }
        }
    }
    Hint("Tap a flag to go there (Split cuts there); ✕ takes it away.")
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
    onAddSound: () -> Unit,
) {
    // As on the Colour page: what is edited in a row of tools, one ruler under it.
    var tool by rememberSaveable { mutableStateOf("Level") }
    val clip = state.audioClips.firstOrNull { it.id == state.selectedAudio }
    val index = state.selectedClip
    val video = state.selected?.takeIf { clip == null && videoSelected && index != null }
    if (clip == null && video == null) {
        ValueHeader("All clips' own sound", String.format(Locale.US, "%.0f %%", state.clipSound * 100))
        RulerSlider(state.clipSound, 0f..1f, 0.02f, vm::setClipSound, onReset = { vm.setClipSound(1f) })
        Hint("Double-tap a clip or a sound on the timeline for its own level and fades.")
        AddSoundButton(onAddSound)
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
    AddSoundButton(onAddSound)
}

/** Adding a sound: the same as + on the timeline's sound track, here where it is looked for. */
@Composable
private fun AddSoundButton(onAddSound: () -> Unit) {
    AddTile(HeadsupIcons.Music, "Add sound", onAddSound, Modifier.fillMaxWidth())
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
private fun ProjectHeader(state: UiState, vm: HeadsupViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    // Its actions on a line of their own (back to the list, rename), the name and facts under them.
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction("‹ Projects", onBack)
        Spacer(Modifier.weight(1f))
        SmallAction("Rename", { renaming = true })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val album = state.photoProject
            Text(state.projectName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
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
        }
    }
    if (renaming) {
        RenameDialog(state.projectName, onDismiss = { renaming = false }, title = "Project name") { vm.renameProject(it); renaming = false }
    }
}

/** The header of the projects page: films or albums, each with how many there are. */
@Composable
private fun KindHeader(shown: ProjectKind, films: Int, albums: Int, onShow: (ProjectKind) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, HeadsupColors.Outline, shape)
            .padding(4.dp),
    ) {
        for ((kind, title, n) in listOf(Triple(ProjectKind.VIDEO, "Films", films), Triple(ProjectKind.PHOTOS, "Albums", albums))) {
            val on = kind == shown
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) HeadsupColors.Accent else Color.Transparent)
                    .clickable { onShow(kind) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (kind == ProjectKind.VIDEO) HeadsupIcons.Video else HeadsupIcons.Colour, null, Modifier.size(18.dp), tint = if (on) Color.Black else HeadsupColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (on) Color.Black else Color.White)
                Spacer(Modifier.width(6.dp))
                Text("$n", style = MaterialTheme.typography.labelMedium, color = if (on) Color.Black.copy(alpha = 0.6f) else HeadsupColors.TextMuted)
            }
        }
    }
}

/** The saved projects (films and albums) and new ones. */
@Composable
private fun ProjectRow(state: UiState, vm: HeadsupViewModel, onOpen: () -> Unit, onNew: (ProjectKind) -> Unit) {
    // Films and albums kept apart: a header to switch between them, each with its own New and list.
    var kindShown by rememberSaveable { mutableStateOf(if (state.photoProject) ProjectKind.PHOTOS else ProjectKind.VIDEO) }
    KindHeader(kindShown, films = state.projects.count { it.kind == ProjectKind.VIDEO }, albums = state.projects.count { it.kind == ProjectKind.PHOTOS }) { kindShown = it }
    // A project from the list being renamed, or about to be deleted.
    var renamingOther by remember { mutableStateOf<String?>(null) }
    var deletingOther by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshProjects() }
    val external = with(vm) { state.externalMedia }
    if (external.isNotEmpty() && !state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("${external.size} file(s) still come from the gallery or cloud; the project breaks if they move.", Modifier.weight(1f))
            SmallAction("Copy into project", vm::copyMediaIntoProject)
        }
    }
    // New: a film (timeline of clips and photos) or an album (photos edited one by one).
    if (kindShown == ProjectKind.VIDEO) {
        AddTile(HeadsupIcons.Video, "New film", { onNew(ProjectKind.VIDEO) }, Modifier.fillMaxWidth())
    } else {
        AddTile(HeadsupIcons.Colour, "New album", { onNew(ProjectKind.PHOTOS) }, Modifier.fillMaxWidth())
    }
    // In an order that does not change with what is open: starred first, then the latest changed.
    val listed = state.projects.filter { it.kind == kindShown }.sortedWith(compareByDescending<ProjectInfo> { it.starred }.thenByDescending { it.modifiedMs })
    if (listed.isEmpty()) Hint(if (kindShown == ProjectKind.PHOTOS) "No albums yet. An album is saved once it has photos." else "No films yet. A film is saved once it has files.")
    for (p in listed) {
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
            // The star: tap to mark the project (starred ones come first).
            Text(
                if (p.starred) "★" else "☆",
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { vm.setProjectStarred(p.name, !p.starred) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                color = if (p.starred) HeadsupColors.Accent else HeadsupColors.TextMuted,
            )
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Hint(
                    Instant.ofEpochMilli(p.modifiedMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        .withNano(0).toString().replace('T', ' ') +
                        if (p.bytes > 0) " · " + formatBytes(p.bytes) else "",
                )
            }
            // Rename and delete behind ⋮, so the name has the row's width.
            Box {
                var open by remember { mutableStateOf(false) }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { open = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(HeadsupIcons.More, "More", Modifier.size(20.dp), tint = HeadsupColors.TextMuted) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; renamingOther = p.name })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; deletingOther = p.name })
                }
            }
        }
    }
    if (state.projects.isNotEmpty()) Hint("Projects keep their own copies of their files. Deleting a project frees that space; the gallery is never touched.")
    deletingOther?.let { name ->
        AlertDialog(
            onDismissRequest = { deletingOther = null },
            title = { Text("Delete project") },
            text = { Text("\"$name\" and the copies of its files are deleted. The gallery is not touched.") },
            confirmButton = { TextButton(onClick = { deletingOther = null; vm.deleteProject(name) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingOther = null }) { Text("Cancel") } },
        )
    }
    renamingOther?.let { old ->
        RenameDialog(old, onDismiss = { renamingOther = null }, title = "Project name") { vm.renameProject(it, old); renamingOther = null }
    }
}

/**
 * A clip's speed, in one of two ways kept apart: one constant speed for the whole clip, or a
 * variable one (changing over the clip along a curve). Sound applies to both.
 */
@Composable
private fun SpeedPanel(clip: ClipState, index: Int, timelineFps: Float, vm: HeadsupViewModel, playhead: LongState) {
    var ramp by rememberSaveable(clip.id) { mutableStateOf(clip.speedCurve.isNotEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = !ramp, onClick = { ramp = false }, label = { Text("Constant") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = ramp, onClick = { ramp = true }, label = { Text("Variable") })
        Spacer(Modifier.weight(1f))
        Hint(Formats.duration(clip.timelineDurationMs) + " on the timeline")
    }
    if (ramp) {
        SpeedRamp(clip, timelineFps, playhead, onPreset = { vm.applySpeedRamp(index, it) }, onCurve = { vm.setSpeedCurve(index, it) })
    } else {
        SpeedControls(clip, timelineFps) { vm.setSteadySpeed(index, it) }
    }
    if (clip.speedChanged) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sound", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
            FilterChip(selected = !clip.muteChangedSpeed, onClick = { vm.setMuteChangedSpeed(index, false) }, label = { Text("Stretched") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = clip.muteChangedSpeed, onClick = { vm.setMuteChangedSpeed(index, true) }, label = { Text("Mute") })
        }
    }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

/**
 * One speed for the whole clip. Real slow motion needs footage shot at a high frame rate: the
 * clip's frames, spread out by the speed, should still cover the timeline's frame rate.
 */
@Composable
private fun SpeedControls(clip: ClipState, timelineFps: Float, onSpeed: (Float) -> Unit) {
    val steady = clip.speedCurve.isEmpty()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Every frame of high-frame-rate footage at the timeline's rate (e.g. 120 fps on a 24 fps timeline: 0.2×).
        val conform = clip.meta.frameRate?.takeIf { !clip.meta.isPhoto && it > timelineFps + 0.5f }?.let { timelineFps / it }
        if (conform != null) {
            FilterChip(
                selected = steady && kotlin.math.abs(clip.speed - conform) < 0.0005f,
                onClick = { onSpeed(conform) },
                label = { Text("All frames · ${String.format(Locale.US, "%.3g", conform)}×") },
            )
        }
        for (speed in SPEEDS) {
            val label = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×".replace("0.", ".")
            FilterChip(selected = steady && clip.speed == speed, onClick = { onSpeed(speed) }, label = { Text(label) })
        }
    }
    if (!steady) {
        Hint("This clip has a variable speed. Picking a speed here replaces it with one constant speed.")
        return
    }
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
}

/** A speed as text: 0.25×, 2×, 23.9×. */
private fun speedText(f: Float): String = when {
    f < 1f -> String.format(Locale.US, "%.2f×", f)
    kotlin.math.abs(f - kotlin.math.round(f)) < 0.005f -> String.format(Locale.US, "%.0f×", f)
    else -> String.format(Locale.US, "%.3g×", f)
}

/**
 * Variable speed over the clip: a ready-made curve, or points dragged on a graph. Across is the
 * recording (the clip's cut in file time), so the graph stays put while it is edited and the
 * timeline above changes with it; under it, the recording's seconds and, moving with the speed,
 * where the timeline's seconds fall. Up is faster, on a scale of ratios from 0.1× to 64×. Tap to
 * add a point, drag to move it, double-tap to remove it. The point being dragged shows its time
 * and speed above the graph, clear of the finger.
 */
@Composable
private fun SpeedRamp(clip: ClipState, timelineFps: Float, playhead: LongState, onPreset: (List<Float>) -> Unit, onCurve: (List<Float>) -> Unit) {
    val SC = app.headsup.core.edit.SpeedCurve
    val sourceFps = clip.meta.frameRate
    val base = clip.speed
    // The clip's flags as fractions of its cut in file time (where the curve's points are).
    val span = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L)
    val flagsU = clip.flags.filter { it > clip.trimStartMs && it < clip.trimEndMs }.map { (it - clip.trimStartMs).toFloat() / span }
    val flagsNow by rememberUpdatedState(flagsU)
    // Speeds a point is caught by: round ratios, and those at which the footage's frames come out
    // exactly at the timeline's rate (every frame, every second frame…), so 24 fps stays 24, not 23.5.
    val frameSpeeds = SC.smoothLimit(clip.meta.frameRate, timelineFps)?.let { l -> List(6) { k -> l * (1 shl k) } }.orEmpty()
    val snaps = (listOf(0.1f, 0.2f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 12f, 16f, 24f, 32f, 48f, 64f) + frameSpeeds).filter { it in SC.MIN..SC.MAX }
    val snapsNow by rememberUpdatedState(snaps)
    // The point being dragged: (file share u, speed).
    var dragged by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // How slow the presets' slow parts go: as slow as the footage stays smooth, or half that slow.
    val (slow, verySlow) = SC.slowSpeeds(sourceFps, timelineFps)
    var deep by rememberSaveable { mutableStateOf(true) }
    val depth = if (deep) verySlow else slow
    val presets = app.headsup.core.edit.SpeedCurve.Preset.entries
    // The preset the curve is, with which slow speed and which rush (how fast its fast parts go).
    val match = if (base != 1f) null else presets.firstNotNullOfOrNull { p ->
        listOf(slow, verySlow).firstNotNullOfOrNull { sl -> SC.RUSHES.firstOrNull { r -> clip.speedCurve == p.points(sl, r) }?.let { r -> Triple(p, sl, r) } }
    }
    // The ready-made curve last picked: its slow and fast rows stay while its points are moved,
    // so nothing above the graph jumps away under the finger.
    var picked by rememberSaveable(clip.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(match?.first) { match?.first?.let { picked = it.name } }
    LaunchedEffect(clip.speedCurve.isEmpty()) { if (clip.speedCurve.isEmpty()) picked = null }
    val chosen = presets.firstOrNull { it.name == picked }
    LaunchedEffect(match?.second) { match?.second?.let { deep = it == verySlow } }
    var rush by rememberSaveable { mutableStateOf(SC.DEFAULT_RUSH) }
    LaunchedEffect(match?.third) { match?.third?.let { rush = it } }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in presets) {
            FilterChip(selected = p == match?.first, onClick = { picked = p.name; onPreset(p.points(depth, rush)) }, label = { Text(p.label) })
        }
    }
    // The ready-made curve's slow parts and fast parts, each offered only when it has them.
    if (chosen != null && chosen.slows) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Slow parts", style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            Spacer(Modifier.width(8.dp))
            for ((isDeep, label, speed) in listOf(Triple(false, "Slow", slow), Triple(true, "Very slow", verySlow))) {
                FilterChip(
                    selected = match != null && deep == isDeep,
                    onClick = { deep = isDeep; onPreset(chosen.points(if (isDeep) verySlow else slow, rush)) },
                    label = { Text(label + " · " + speedText(speed)) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
    if (chosen != null && chosen.rushes) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Fast parts", style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            Spacer(Modifier.width(8.dp))
            for (r in SC.RUSHES) {
                FilterChip(
                    selected = match != null && rush == r,
                    onClick = { rush = r; onPreset(chosen.points(depth, r)) },
                    label = { Text(speedText(r)) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }

    val timelineMs = clip.timelineDurationMs
    val shares = remember(clip.speedCurve, base) { SC.timeShares(clip.speedCurve, base) }
    // The readout: the dragged point's moment (in the recording and on the timeline) and speed,
    // above the graph where the finger is not. Always the same height.
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        val d = dragged
        if (d != null) {
            val rec = d.first * span / 1000f
            val onTimeline = SC.shareAt(shares, d.first) * timelineMs / 1000f
            Text(
                speedText(d.second) + String.format(Locale.US, "   rec %.1f s  ·  timeline %.1f s", rec, onTimeline) +
                    if (frameSpeeds.isNotEmpty() && kotlin.math.abs(d.second - frameSpeeds[0]) < 1e-4f) "  ·  every frame" else "",
                style = MaterialTheme.typography.titleSmall,
                color = HeadsupColors.Accent,
                maxLines = 1,
            )
        } else {
            Hint(if (clip.speedCurve.isEmpty()) "Tap the graph to add a point, or pick a curve above." else "Tap: add a point · drag: move · double-tap: remove", Modifier.weight(1f))
            if (clip.speedCurve.isNotEmpty()) {
                TextButton(onClick = { onCurve(emptyList()) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("Clear") }
            }
        }
    }

    // Ratios on a log scale: 0.1× at the bottom, 64× at the top.
    val lo = kotlin.math.ln(SC.MIN)
    val hi = kotlin.math.ln(SC.MAX)
    fun yOf(f: Float) = 1f - (kotlin.math.ln(f.coerceIn(SC.MIN, SC.MAX)) - lo) / (hi - lo)
    fun fOf(y: Float) = kotlin.math.exp(lo + (1f - y.coerceIn(0f, 1f)) * (hi - lo))
    // Points as (file share u, factor); shown at their speed (clip speed × factor).
    val points = SC.points(clip.speedCurve).ifEmpty { listOf(0f to 1f, 1f to 1f) }
    val current by rememberUpdatedState(points)
    val baseNow by rememberUpdatedState(base)
    val store by rememberUpdatedState(onCurve)
    val axisH = 30.dp
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat() - axisH.toPx()
                    val b = baseNow
                    fun flat(l: List<Pair<Float, Float>>) = l.flatMap { listOf(it.first, it.second) }
                    fun at(p: Pair<Float, Float>) = Offset(p.first * w, yOf(b * p.second) * h)
                    val work = current.toMutableList()
                    var i = work.indices.minByOrNull { (at(work[it]) - down.position).getDistance() } ?: return@awaitEachGesture
                    val near = (at(work[i]) - down.position).getDistance() < 28.dp.toPx()
                    if (near && down.uptimeMillis - lastTap < 300 && work.size > 2) {
                        // A double tap on a point removes it.
                        work.removeAt(i)
                        store(flat(work))
                        lastTap = 0L
                        return@awaitEachGesture
                    }
                    lastTap = down.uptimeMillis
                    if (!near) {
                        val u = (down.position.x / w).coerceIn(0f, 1f)
                        work += u to (fOf(down.position.y / h) / b)
                        work.sortBy { it.first }
                        i = work.indexOfFirst { it.first == u }
                        store(flat(work))
                    }
                    dragged = work[i].first to b * work[i].second
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val lo2 = if (i == 0) 0f else work[i - 1].first + 0.01f
                        val hi2 = if (i == work.lastIndex) 1f else work[i + 1].first - 0.01f
                        var u = (change.position.x / w).coerceIn(lo2, hi2)
                        // A flag nearby catches the point: a ramp from one flagged moment to another.
                        flagsNow.filter { it in lo2..hi2 }.minByOrNull { kotlin.math.abs(it - u) }
                            ?.takeIf { kotlin.math.abs(it - u) * w < 12.dp.toPx() }?.let { u = it }
                        // A speed near a round one (or one showing every frame) is caught by it.
                        var speed = fOf(change.position.y / h)
                        snapsNow.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / speed)) }?.takeIf { kotlin.math.abs(kotlin.math.ln(it / speed)) < 0.07f }?.let { speed = it }
                        work[i] = u to speed / b
                        dragged = u to speed
                        store(flat(work))
                    }
                    dragged = null
                }
            },
    ) {
        val w = size.width
        val axisPx = axisH.toPx()
        val h = size.height - axisPx
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(150, 255, 255, 255); textSize = 10.dp.toPx() }
        // Below the speed where every frame of the footage is shown, frames repeat: stutter.
        val limit = SC.smoothLimit(sourceFps, timelineFps)
        if (limit != null && limit > SC.MIN) {
            val y = yOf(limit) * h
            drawRect(Color(0x26FF3B30), Offset(0f, y), androidx.compose.ui.geometry.Size(w, h - y))
        }
        drawIntoCanvas { c ->
            // Speed lines with their ratios on the left; 1× stronger.
            for (f in listOf(0.25f, 1f, 4f, 16f, 64f)) {
                val y = yOf(f) * h
                drawLine(Color.White.copy(alpha = if (f == 1f) 0.35f else 0.1f), Offset(0f, y), Offset(w, y))
                c.nativeCanvas.drawText(speedText(f), 6.dp.toPx(), (y - 3.dp.toPx()).coerceAtLeast(11.dp.toPx()), paint)
            }
            // The footage's frame rates shown each second, on the right: its own, half, quarter… down to the timeline's.
            if (sourceFps != null) {
                var fps = sourceFps
                var k = 0
                while (fps >= timelineFps * 0.99f && k < 6) {
                    val y = yOf(fps / sourceFps) * h
                    val every = kotlin.math.abs(fps - timelineFps) < timelineFps * 0.05f
                    val label = "${OutputSpec.formatFps(fps)} fps" + if (every) " · every frame" else ""
                    if (kotlin.math.abs(fps / sourceFps - 1f) > 0.01f) drawLine(Color.White.copy(alpha = 0.08f), Offset(w * 0.55f, y), Offset(w, y))
                    c.nativeCanvas.drawText(label, w - paint.measureText(label) - 6.dp.toPx(), y + 11.dp.toPx(), paint)
                    fps /= 2f
                    k++
                }
            }
            if (limit != null && limit > SC.MIN) {
                val p2 = android.graphics.Paint(paint).apply { color = android.graphics.Color.argb(170, 255, 120, 110) }
                c.nativeCanvas.drawText("stutters", w / 2f - p2.measureText("stutters") / 2f, h - 5.dp.toPx(), p2)
            }
            // Two scales under the graph: the recording's seconds (fixed), and where the timeline's
            // seconds fall (they move as the speed changes: bunched up where it is fast).
            drawRect(Color.Black.copy(alpha = 0.3f), Offset(0f, h), androidx.compose.ui.geometry.Size(w, axisPx))
            val row = axisPx / 2f
            fun ticks(seconds: Float, xOf: (Float) -> Float, top: Float, tag: String, colour: Int) {
                val step = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f).firstOrNull { seconds / it <= 6f } ?: 300f
                val fine = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f).lastOrNull { it < step && (step / it) % 1f < 1e-3f && seconds / it <= 80f }
                val tp = android.graphics.Paint(paint).apply { color = colour }
                if (fine != null) {
                    var s = fine
                    while (s < seconds) {
                        val x = xOf(s)
                        drawLine(Color(colour).copy(alpha = 0.35f), Offset(x, top), Offset(x, top + row * 0.3f))
                        s += fine
                    }
                }
                var s = 0f
                var lastRight = -1f
                while (s <= seconds + 1e-3f) {
                    val x = xOf(s)
                    drawLine(Color(colour).copy(alpha = 0.8f), Offset(x, top), Offset(x, top + row * 0.45f))
                    val label = (if (s == 0f) "$tag " else "") + if (step < 1f) String.format(Locale.US, "%.1f", s) else String.format(Locale.US, "%.0f s", s)
                    val lw = tp.measureText(label)
                    val lx = (x + 2.dp.toPx()).coerceAtMost(w - lw - 2.dp.toPx())
                    // Labels that would run into the one before (timeline seconds bunched up) are left out.
                    if (lx > lastRight + 4.dp.toPx()) {
                        c.nativeCanvas.drawText(label, lx, top + row - 2.dp.toPx(), tp)
                        lastRight = lx + lw
                    }
                    s += step
                }
            }
            ticks(span / 1000f, { s -> s * 1000f / span * w }, h, "rec", android.graphics.Color.argb(170, 255, 255, 255))
            val accent = HeadsupColors.AccentSoft
            val accentInt = android.graphics.Color.argb(230, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
            ticks(timelineMs / 1000f, { s -> SC.sourceAt(shares, s * 1000f / timelineMs.coerceAtLeast(1L)) * w }, h + row, "timeline", accentInt)
        }
        drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        // The clip's flags: where a ramp should start or end (points snap to them).
        for (u in flagsU) {
            val x = u * w
            drawLine(
                HeadsupColors.AccentSoft.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            val s = 6.dp.toPx()
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x, 0f); lineTo(x + s * 1.3f, s * 0.5f); lineTo(x, s); close() }, HeadsupColors.AccentSoft)
        }
        // Where the picture is now.
        val now = playhead.longValue.toFloat() / span
        if (now in 0f..1f) drawLine(Color.White.copy(alpha = 0.7f), Offset(now * w, 0f), Offset(now * w, h), strokeWidth = 1.5.dp.toPx())
        // The curve.
        val flat = points.flatMap { listOf(it.first, it.second) }
        val path = androidx.compose.ui.graphics.Path()
        for (k in 0..160) {
            val u = k / 160f
            val y = yOf(SC.speedAt(base, flat, u)) * h
            if (k == 0) path.moveTo(0f, y) else path.lineTo(u * w, y)
        }
        drawPath(path, HeadsupColors.Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        // Points: only where a curve was made (a steady clip has a flat line, no points).
        if (clip.speedCurve.isNotEmpty()) {
            for ((u, f) in points) {
                val o = Offset(u * w, yOf(base * f) * h)
                drawCircle(Color.White, 5.dp.toPx(), o)
                drawCircle(HeadsupColors.Accent, 2.5.dp.toPx(), o)
            }
        }
        // The dragged point: a ring and a line down to the time scales.
        dragged?.let { (u, f) ->
            val x = u * w
            val y = yOf(f) * h
            drawLine(HeadsupColors.Accent.copy(alpha = 0.6f), Offset(x, y), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawCircle(HeadsupColors.Accent, 11.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
    // The slowest point of the curve and how many frames of footage that leaves each second.
    val slowest = base * points.minOf { it.second }
    if (clip.speedCurve.isNotEmpty() && sourceFps != null && slowest < 1f) {
        val shown = sourceFps * slowest
        Hint(
            if (shown + 0.5f >= timelineFps) {
                String.format(Locale.US, "Slowest %s: %s fps footage gives %s fps, enough for the %s fps timeline: smooth.",
                    speedText(slowest), OutputSpec.formatFps(sourceFps), OutputSpec.formatFps(shown), OutputSpec.formatFps(timelineFps))
            } else {
                String.format(Locale.US, "Slowest %s: %s fps footage gives only %s fps on a %s fps timeline: it will stutter. For smooth slow motion record at %s fps or more.",
                    speedText(slowest), OutputSpec.formatFps(sourceFps), OutputSpec.formatFps(shown), OutputSpec.formatFps(timelineFps), OutputSpec.formatFps(timelineFps / slowest))
            },
        )
    }
}

/** A ramped clip's mark: a little graph (axes from the bottom left) with a wave, "speed" over it. */
@Composable
fun SpeedRampIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    androidx.compose.foundation.Canvas(modifier.size(width = 30.dp, height = 22.dp)) {
        val w = size.width
        val h = size.height
        val top = 8.dp.toPx()
        val sw = 1.2.dp.toPx()
        drawLine(tint.copy(alpha = 0.7f), Offset(sw, top), Offset(sw, h - sw), strokeWidth = sw)
        drawLine(tint.copy(alpha = 0.7f), Offset(sw, h - sw), Offset(w, h - sw), strokeWidth = sw)
        val wave = androidx.compose.ui.graphics.Path()
        for (k in 0..24) {
            val x = 3.dp.toPx() + (w - 4.dp.toPx()) * k / 24f
            val y = top + (h - top - 3.dp.toPx()) * (0.5f - 0.4f * kotlin.math.cos(k / 24f * 2f * Math.PI.toFloat()))
            if (k == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        drawPath(wave, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(200, 255, 255, 255)
                textSize = 7.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            c.nativeCanvas.drawText("speed", w / 2f, 6.5.dp.toPx(), paint)
        }
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
    SectionCaption("Cut") {
        Spacer(Modifier.width(10.dp))
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
    // "Here" is the playhead; the watch data follows every cut automatically. In the order they
    // act along the clip: its start, a cut, its end.
    SectionCaption("At the playhead")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelAction("Start here", { onTrim(clip.trimStartMs + playhead.longValue, clip.trimEndMs) }, Modifier.weight(1f))
        PanelAction("Split", { onSplit(playhead.longValue) }, Modifier.weight(1f))
        PanelAction("End here", { onTrim(clip.trimStartMs, clip.trimStartMs + playhead.longValue) }, Modifier.weight(1f))
    }
    SectionCaption("Clip")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelAction("Full clip", { onTrim(0L, clip.meta.durationMs) }, Modifier.weight(1f), enabled = clip.isTrimmed)
        PanelAction("Delete clip", onDelete, Modifier.weight(1f), danger = true)
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

/** Copy the selected clip's look (corrections, LUT, effects), paste it on another, or copy it to every clip; reset it. */
@Composable
private fun LookActions(state: UiState, vm: HeadsupViewModel) {
    val index = state.selectedClip ?: return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PillAction("⧉", "Copy", vm::copyLook)
        if (state.copiedLook != null) PillAction("⎘", "Paste", { vm.pasteLook(index) })
        if (state.clips.size > 1) PillAction("⇉", "Copy to all", vm::applyLookToAll)
        // Reset: no corrections, and none of the look's effects either.
        val e = state.selected?.effects
        val looked = e != null && (e.grain > 0f || e.glow > 0f || e.halation > 0f || e.lens || e.barsRatio != null)
        if (state.grade != ColorGrade() || looked) PillAction("↺", "Reset", vm::resetBase)
    }
}

/**
 * The top of Adjust: what it works on (this clip, its group, all clips), always in view; for this
 * clip its layers (the base and its masks) and the selected layer's actions. Returns true when
 * nothing else belongs under it (a new mask is being chosen).
 */
@Composable
private fun AdjustHeader(
    state: UiState,
    clip: ClipState,
    index: Int,
    vm: HeadsupViewModel,
    pictureSource: ((Int) -> Bitmap?)?,
    maskTools: Boolean,
    onMaskTools: (Boolean) -> Unit,
): Boolean {
    val album = state.photoProject
    val scope = when (state.target) {
        GradeTarget.GROUP -> 1
        GradeTarget.GLOBAL -> 2
        GradeTarget.CLIP, GradeTarget.MASK -> 0
    }
    Segmented(listOf(if (album) "This photo" else "This clip", "Group", if (album) "All photos" else "All clips"), scope, { i ->
        if (i != scope) {
            vm.setTarget(listOf(GradeTarget.CLIP, GradeTarget.GROUP, GradeTarget.GLOBAL)[i])
            // The looks (grain, glow…) follow the same choice: this clip's, or set on all.
            vm.setEffectsOnAll(i == 2)
        }
    })
    return when (scope) {
        1 -> {
            GroupControls(state, clip, index, state.groupOf(clip), vm)
            false
        }
        2 -> {
            // As everywhere on this page: what it does, then its actions in a row on the left.
            Hint(if (album) "A change is set on every photo: each shows it under This photo." else "A change is set on every clip: each shows it under This clip.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillAction("↺", "Reset all", { vm.resetSyncedPhotos() })
            }
            false
        }
        else -> Layers(state, clip, vm, pictureSource, maskTools, onMaskTools)
    }
}

/** Sections of the colour corrections, so the picker row stays short. */
private enum class ColourSection(val label: String) { LIGHT("Light"), PRESENCE("Presence"), COLOUR("Colour"), MIXER("Mixer"), FILM("Film"), EFFECTS("Effects"), LOOKS("Looks"), TOOLS("Tools") }

private val Adjustment.section: ColourSection
    get() = when (this) {
        Adjustment.EXPOSURE, Adjustment.BRIGHTNESS, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS,
        Adjustment.WHITES, Adjustment.BLACKS, Adjustment.ROLLOFF, Adjustment.CURVE -> ColourSection.LIGHT
        Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.SATURATION, Adjustment.VIBRANCE, Adjustment.DENSITY, Adjustment.SKIN -> ColourSection.COLOUR
        Adjustment.SPLIT, Adjustment.SHADOW_HUE, Adjustment.HIGHLIGHT_HUE, Adjustment.TAME, Adjustment.VIGNETTE, Adjustment.WHEELS -> ColourSection.FILM
        Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE, Adjustment.DEFRINGE -> ColourSection.PRESENCE
        Adjustment.CINE, Adjustment.LUT, Adjustment.MATCH, Adjustment.BLEND -> ColourSection.TOOLS
        Adjustment.MIXER, Adjustment.HUE_CURVES, Adjustment.CHANNELS, Adjustment.BW, Adjustment.CALIBRATION -> ColourSection.MIXER
        // Looks sit with the other tools that set a whole look (LUT, shot match).
        Adjustment.LOOK -> ColourSection.TOOLS
        Adjustment.GLOW, Adjustment.HALATION -> ColourSection.EFFECTS
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
    // Subtractive saturation: saturated colours deeper (+) or paler (−).
    DENSITY("Dn", "Density", -1f..1f, 0.02f, "%+.2f", { it.density }, { g, v -> g.copy(density = v) }),
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
    DEFRINGE("Df", "Defringe", 0f..1f, 0.02f, "%.2f", { it.defringe }, { g, v -> g.copy(defringe = v) }),
    CLARITY("Cl", "Clarity", -1f..1f, 0.02f, "%+.2f", { it.clarity }, { g, v -> g.copy(clarity = v) }),
    DEHAZE("Dh", "Dehaze", -1f..1f, 0.02f, "%+.2f", { it.dehaze }, { g, v -> g.copy(dehaze = v) }),
    // Masks only (a clip's glow and halation are in Effects): light from what is bright inside the mask.
    GLOW("✧", "Glow", 0f..1f, 0.02f, "%.2f", { it.glow }, { g, v -> g.copy(glow = v) }),
    HALATION("◌", "Halation", 0f..1f, 0.02f, "%.2f", { it.halation }, { g, v -> g.copy(halation = v) }),

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

    /** Not a single ruler: curves by hue and brightness (Hue vs Hue, Sat, Lum; Lum vs Sat). */
    HUE_CURVES("H∿", "Hue curves", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: a look (profile) under the corrections, with its amount. */
    LOOK("Look", "Looks", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: each output channel made from red, green and blue. */
    CHANNELS("RGB", "Channel mixer", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: black and white, with a brightness per hue band. */
    BW("B&W", "Black & white", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),

    /** Not a single ruler: hue and saturation of the red, green and blue primaries. */
    CALIBRATION("Cal", "Calibration", 0f..1f, 1f, "", { 0f }, { g, _ -> g }),
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
    pictureSource: ((Int) -> Bitmap?)? = null,
    /** Changing a mask's shape (its tools), where a finger on the picture shapes the mask; else its corrections. */
    maskTools: Boolean = true,
    onMaskTools: (Boolean) -> Unit = {},
) {
    val index = state.selectedClip
    val clip = state.selected
    val album = state.photoProject
    if (index == null || clip == null) {
        Hint(if (album) "Select a photo first." else "Select a clip first.")
        return
    }
    // What the page works on (this clip, its group, all) and the clip's layers stay at the top;
    // while a mask's shape is being changed only its tools show, while a new one is chosen only that.
    val target = state.target
    val group = state.groupOf(clip)
    val selectedMask = clip.masks.getOrNull(state.selectedMask)
    // The mask worked on is gone (deleted, undone): back to the base.
    LaunchedEffect(target, selectedMask == null) { if (target == GradeTarget.MASK && selectedMask == null) vm.setTarget(GradeTarget.CLIP) }
    // While something is removed from a photo only that shows; otherwise the scope and layers stay
    // at the top in both of a mask's modes (Mask and Adjust).
    if (!(target == GradeTarget.MASK && state.removing && selectedMask != null) && AdjustHeader(state, clip, index, vm, pictureSource, maskTools, onMaskTools)) return
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
    // Glow and halation are a mask's own (the clip's are in Effects).
    val maskOnly = setOf(Adjustment.GLOW, Adjustment.HALATION)
    val options = when (target) {
        // The Cine look is part of Looks (its Cinematic looks and the clip's analysis).
        // Blending in is from the clip before on the timeline: an album's photos have none.
        GradeTarget.CLIP -> (if (index > 0 && !album) Adjustment.entries else Adjustment.entries - Adjustment.BLEND) - maskOnly - Adjustment.CINE
        GradeTarget.GROUP -> Adjustment.entries - Adjustment.BLEND - Adjustment.CINE - Adjustment.MATCH - maskOnly
        GradeTarget.GLOBAL -> Adjustment.entries - Adjustment.LUT - Adjustment.BLEND - Adjustment.CINE - Adjustment.MATCH - maskOnly
        GradeTarget.MASK -> Adjustment.entries - Adjustment.LUT - Adjustment.SKIN - Adjustment.VIGNETTE - Adjustment.BLEND -
            Adjustment.CINE - Adjustment.MATCH - Adjustment.LOOK
    }
    val adjustment = if (adjustment in options) adjustment else Adjustment.EXPOSURE
    // On the Masks tab the ruler edits either a shape control or a correction.
    var shape by rememberSaveable { mutableStateOf<MaskShape?>(null) }
    // A new mask opens on its own tools (select, paint, shape).
    var maskCount by remember { mutableStateOf(clip.masks.size) }
    LaunchedEffect(clip.id, clip.masks.size) {
        if (clip.masks.size > maskCount) onMaskTools(true)
        maskCount = clip.masks.size
    }
    val shapes = mask?.takeIf { target == GradeTarget.MASK }?.let { m -> MaskShape.entries.filter { m.kind in it.kinds } }.orEmpty()
    val editingShape = if (target == GradeTarget.MASK && maskTools) shape?.takeIf { it in shapes } ?: shapes.firstOrNull() else null
    // Corrections are grouped into short sections (plus the mask's shape), so each row stays short.
    // A raw (DNG) photo is developed first (exposure, highlights, shadows, white balance on the raw data).
    // The raw development syncs like the rest from "all" and groups.
    val rawPhoto = clip.meta.isRaw && target != GradeTarget.MASK
    var onRaw by rememberSaveable { mutableStateOf(false) }
    // The base's looks over the picture (grain, glow, halation, bars) are its Effects: this clip's,
    // or set on all clips. A mask's Effects are its own glow and halation.
    val baseEffects = target == GradeTarget.CLIP || target == GradeTarget.GLOBAL
    var onEffects by rememberSaveable { mutableStateOf(false) }
    var lookKind by rememberSaveable { mutableStateOf(EffectKind.GRAIN) }
    // Shaping a mask is a mode of its own: only its tools show (and only then does a finger on the
    // picture change it); Done goes back to its corrections, which stay as they are.
    if (target == GradeTarget.MASK && maskTools && mask != null && (editingShape != null || mask.kind == MaskKind.RANGE)) {
        if (state.removing) {
            SectionCaption("Remove from the photo")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Select what goes.", Modifier.weight(1f))
                SmallAction("Cancel", { vm.cancelRemoval(); onMaskTools(false) })
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.removeMaskArea(); onMaskTools(false) }, enabled = mask.strokes.isNotEmpty() && !state.busy) { Text("Remove") }
            }
        } else if (maskNeedsTracking(clip, mask)) {
            // On video, a painted mask not tracked since it changed stays where it is drawn: tracking
            // is offered first, so it is not forgotten. It runs in the background.
            Button(
                onClick = { vm.trackMask(HeadsupViewModel.TrackDirection.BOTH) },
                enabled = !vm.isTracking(clip.id, state.selectedMask, state),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (mask.sequence != null) "Track again" else "Track") }
        }
        if (mask.kind == MaskKind.BRUSH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction(if (state.maskOverlay) "Overlay ✓" else "Overlay", { vm.toggleMaskOverlay() })
                SmallAction("Clear", { vm.clearBrush() })
            }
            BrushControls(state, vm, pictureSource)
            // However exact the selection, some edges should fade (a ragged shore): a soft band.
            SectionCaption("Feather")
            ValueHeader("Feather", if (mask.feather <= 0f) "Off" else String.format(Locale.US, "%.0f %%", mask.feather * 100f))
            RulerSlider(mask.feather, 0f..1f, 0.02f, { v -> vm.updateMask { it.copy(feather = v) } }, onReset = { vm.updateMask { it.copy(feather = 0f) } })
            Hint("Makes the mask's edge a gradual band instead of a sharp cut, for edges no selection follows exactly. Off: the edge follows the picture's.")
            // A wide transition into the picture, where it lies set apart from how wide it is.
            SectionCaption("Blend")
            ValueHeader("Blend", if (mask.blend <= 0f) "Off" else String.format(Locale.US, "%.0f %%", mask.blend * 100f))
            RulerSlider(mask.blend, 0f..1f, 0.01f, { v -> vm.updateMask { it.copy(blend = v) } }, onReset = { vm.updateMask { it.copy(blend = 0f) } })
            if (mask.blend > 0f) {
                ValueHeader("Position", when {
                    mask.blendPosition >= 0.99f -> "Outside"
                    mask.blendPosition <= -0.99f -> "Inside"
                    else -> String.format(Locale.US, "%+.0f %%", mask.blendPosition * 100f)
                })
                RulerSlider(mask.blendPosition, -1f..1f, 0.02f, { v -> vm.updateMask { it.copy(blendPosition = v) } }, onReset = { vm.updateMask { it.copy(blendPosition = 1f) } })
            }
            Hint("Blend: how far the mask's change fades into the picture around what was selected, up to a third of the frame. Position: Outside keeps the full change on the selection and fades it out around it; Inside fades it within the selection, leaving the rest untouched; between, across the outline. Used in place of Feather.")
        } else if (editingShape != null) {
            SmallAction(if (state.maskOverlay) "Overlay ✓" else "Overlay", { vm.toggleMaskOverlay() })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shapes) { m -> CircleOption(m.symbol, m.label, selected = m == editingShape, modified = false) { shape = m } }
            }
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
            Hint(if (mask.kind == MaskKind.LINEAR) "Drag the middle line to move the gradient, a dashed line to set where it starts or ends (the other stays); two fingers turn it." else "Drag the middle to move it, an end to stretch it (the other end stays); two fingers resize and turn it. Feather: how gradual it is, up to a gradient from the middle far out.")
        }
        // A line or an oval can be narrowed to a colour or a brightness; a range mask is only that.
        // A selected or painted mask is not (it follows what was selected; to narrow it, combine it
        // with a Colour or Light mask), so the three kinds stay apart.
        if (mask.kind != MaskKind.BRUSH) MaskRanges(state, mask, vm)
        if (state.selectedMask > 0) MaskJoinControls(clip, state.selectedMask, mask, vm)
        if (mask.kind != MaskKind.RANGE) MaskMotion(state, clip, mask, vm, playhead, onSeek)
        return
    }
    // A mask joined to the one before it changes that layer's coverage; the layer has the corrections.
    if (target == GradeTarget.MASK && mask != null && state.selectedMask > 0 && mask.join != MaskJoin.NEW) {
        val layer = MaskRange.layerOf(clip.masks, state.selectedMask)
        SectionCaption("${mask.join.label} · ${maskName(mask)}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Changes where mask ${layer + 1} (${maskName(clip.masks[layer])}) applies; its corrections are that mask's.", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillAction("→", "Mask ${layer + 1}", { vm.selectMask(layer) })
        }
        return
    }
    // Which layer the corrections below are for.
    SectionCaption(
        when (target) {
            GradeTarget.CLIP -> "Base"
            GradeTarget.MASK -> mask?.let { "${state.selectedMask + 1} · ${maskName(it)}" } ?: "Mask"
            GradeTarget.GROUP -> group?.name ?: "Group"
            GradeTarget.GLOBAL -> if (album) "All photos" else "All clips"
        },
    )
    val sections = listOfNotNull("RAW".takeIf { rawPhoto }) +
        ColourSection.entries.filter { s -> options.any { it.section == s } || (s == ColourSection.EFFECTS && baseEffects) }.map { it.label }
    val section = when {
        onRaw && rawPhoto -> "RAW"
        onEffects && baseEffects -> ColourSection.EFFECTS.label
        else -> adjustment.section.label
    }
    SectionTabs(sections, sections.indexOf(section).coerceAtLeast(0)) { i ->
        onRaw = sections[i] == "RAW"
        onEffects = baseEffects && sections[i] == ColourSection.EFFECTS.label
        if (onRaw || onEffects) return@SectionTabs
        options.firstOrNull { it.section.label == sections[i] }?.let(onAdjustment)
    }
    if (onRaw && rawPhoto) {
        RawControls(clip, index, vm)
        return
    }
    if (onEffects && baseEffects) {
        // Set on all clips exactly when Adjust works on all of them.
        LaunchedEffect(target) { vm.setEffectsOnAll(target == GradeTarget.GLOBAL) }
        EffectsPanel(state, vm, lookKind, playhead, LOOK_KINDS) { lookKind = it }
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(options.filter { it.section == adjustment.section }) { a ->
            val lut = if (target == GradeTarget.GROUP) group?.lutName else clip.lutName
            val modified = when (a) {
                Adjustment.LUT -> lut != null
                Adjustment.BLEND -> clip.transitionMs > 0
                Adjustment.CINE -> state.cine != null
                Adjustment.MATCH -> state.matchReference?.clipId == clip.id
                Adjustment.MIXER -> state.grade.mixer.any { it != 0f }
                Adjustment.CHANNELS -> state.grade.channelMix.any { it != 0f }
                Adjustment.BW -> app.headsup.core.color.BlackAndWhite.on(state.grade.bw)
                Adjustment.CALIBRATION -> state.grade.calibration.any { it != 0f }
                Adjustment.CURVE -> state.grade.curves.any { it.isNotEmpty() }
                Adjustment.HUE_CURVES -> state.grade.hueCurves.any { it.isNotEmpty() }
                Adjustment.WHEELS -> state.grade.wheels.any { it != 0f }
                Adjustment.LOOK -> state.grade.look != null
                else -> a.get(state.grade) != a.neutral
            }
            CircleOption(a.symbol, a.label, selected = a == adjustment, modified = modified) { onAdjustment(a) }
        }
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
                state, clip.lutName, if (inherited != null) "Group's (${app.headsup.core.color.CameraLog.labelOf(inherited)})" else "None", { vm.setClipLut(index, it) },
                if (state.clips.size > 1) vm::setLutForAllClips else null, vm, onAddLut,
            )
        }
        return
    }
    if (adjustment == Adjustment.MIXER) {
        MixerControls(state, vm)
        return
    }
    if (adjustment == Adjustment.CHANNELS) {
        ChannelMixerControls(state, vm)
        return
    }
    if (adjustment == Adjustment.BW) {
        BlackAndWhiteControls(state, vm)
        return
    }
    if (adjustment == Adjustment.CALIBRATION) {
        CalibrationControls(state, vm)
        return
    }
    if (adjustment == Adjustment.CURVE) {
        CurveControls(state, vm)
        return
    }
    if (adjustment == Adjustment.HUE_CURVES) {
        HueCurveControls(state, vm)
        return
    }
    if (adjustment == Adjustment.WHEELS) {
        WheelControls(state, vm)
        return
    }
    if (adjustment == Adjustment.LOOK) {
        LooksPanel(state, vm, frameSource)
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
    if (adjustment == Adjustment.DEFRINGE) Hint("Takes out the purple and green fringes a lens leaves along hard, contrasty edges (branches against the sky, window frames). Only those edges change; zoom in on one to see it.")
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
                .size(minOf(120.dp, maxWidth * 0.6f))
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
    // Cinematic contrast: lifted, flat shadows, contrast in the middle, a soft top (the points stay editable).
    if (channel == app.headsup.core.color.ToneCurve.Channel.RGB) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Cinematic", style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            Spacer(Modifier.width(8.dp))
            for ((label, amount) in listOf("Subtle" to 0.5f, "Film" to 1f, "Strong" to 1.4f)) {
                // Fitted to the clip's tones when it has been analysed (Looks > Analyse clip).
                val measured = state.lookStats?.takeIf { it.first == state.selected?.id }?.second
                val curve = app.headsup.core.color.ToneCurve.cinematic(amount, low = measured?.low ?: 0.18f, median = measured?.median ?: 0.5f)
                FilterChip(
                    selected = flat == curve,
                    onClick = { store(curve.chunked(2).map { it[0] to it[1] }) },
                    label = { Text(label) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
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

/**
 * Curves by hue (across: the colour wheel, red to red) or by brightness (dark to light): up gives
 * more (a hue turned one way, richer, lighter), down less. Touch to add a point, drag it, double-tap
 * to remove it.
 */
@Composable
private fun HueCurveControls(state: UiState, vm: HeadsupViewModel) {
    val HC = app.headsup.core.color.HueCurves
    var channel by rememberSaveable { mutableStateOf(app.headsup.core.color.HueCurves.Channel.HUE_SAT) }
    val c = channel.ordinal
    val flat = state.grade.hueCurves.getOrNull(c).orEmpty()
    fun store(points: List<Pair<Float, Float>>) = vm.updateGrade { g ->
        val none = points.all { kotlin.math.abs(it.second) < 0.005f }
        g.copy(hueCurves = List(4) { i -> if (i == c) (if (none) emptyList() else points.flatMap { listOf(it.first, it.second) }) else g.hueCurves.getOrNull(i).orEmpty() })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ch in app.headsup.core.color.HueCurves.Channel.entries) {
                val changed = state.grade.hueCurves.getOrNull(ch.ordinal).orEmpty().isNotEmpty()
                FilterChip(selected = ch == channel, onClick = { channel = ch }, label = { Text(ch.label + if (changed) " •" else "") })
            }
        }
        if (flat.isNotEmpty()) SmallAction("Reset", { store(emptyList()) })
    }
    val points = HC.points(flat)
    val current by rememberUpdatedState(points)
    val periodic = channel.periodic
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(channel) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun at(p: Pair<Float, Float>) = Offset(p.first * w, (0.5f - p.second / 2f) * h)
                    val work = current.toMutableList()
                    val nearest = work.indices.minByOrNull { (at(work[it]) - down.position).getDistance() }
                    val near = nearest != null && (at(work[nearest]) - down.position).getDistance() < 28.dp.toPx()
                    var i: Int
                    if (near && down.uptimeMillis - lastTap < 300) {
                        work.removeAt(nearest!!)
                        store(work.toList())
                        lastTap = 0L
                        return@awaitEachGesture
                    }
                    lastTap = down.uptimeMillis
                    if (near) {
                        i = nearest!!
                    } else {
                        // A new point on the curve where the finger is.
                        val x = (down.position.x / w).coerceIn(0f, 1f)
                        work += x to HC.valueAt(work, x, periodic)
                        work.sortBy { it.first }
                        i = work.indexOfFirst { it.first == x }
                        store(work.toList())
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val lo = if (i == 0) 0f else work[i - 1].first + 0.01f
                        val hi = if (i == work.lastIndex) 1f else work[i + 1].first - 0.01f
                        val x = (change.position.x / w).coerceIn(lo, hi)
                        val y = ((0.5f - change.position.y / h) * 2f).coerceIn(-1f, 1f)
                        work[i] = x to y
                        store(work.toList())
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // Across: the colours of the wheel (hue curves) or dark to light.
        val stops = if (periodic) {
            (0..12).map { k -> k / 12f to Color.hsv(k * 30f % 360f, 0.75f, 0.85f) }
        } else {
            listOf(0f to Color(0xFF111111), 1f to Color(0xFFEEEEEE))
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(*stops.toTypedArray()),
            topLeft = Offset(0f, h - 10.dp.toPx()), size = androidx.compose.ui.geometry.Size(w, 10.dp.toPx()),
        )
        for (k in 1..3) drawLine(Color.White.copy(alpha = 0.1f), Offset(w * k / 4, 0f), Offset(w * k / 4, h - 10.dp.toPx()))
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, h / 2f), Offset(w, h / 2f))
        val path = androidx.compose.ui.graphics.Path()
        for (k in 0..120) {
            val x = k / 120f
            val y = HC.valueAt(points, x, periodic)
            val py = (0.5f - y / 2f) * h
            if (k == 0) path.moveTo(0f, py) else path.lineTo(x * w, py)
        }
        drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        for ((x, y) in points) drawCircle(Color.White, 6.dp.toPx(), Offset(x * w, (0.5f - y / 2f) * h))
    }
    Hint(
        when (channel) {
            app.headsup.core.color.HueCurves.Channel.HUE_HUE -> "Turns one hue towards another: a point on a colour, up or down to shift it (e.g. greens towards yellow). Touch to add a point, drag it, double-tap to remove it."
            app.headsup.core.color.HueCurves.Channel.HUE_SAT -> "More or less colour in one hue only: e.g. a point on blue pulled down pales the sky alone."
            app.headsup.core.color.HueCurves.Channel.HUE_LUM -> "Lighter or darker in one hue only: e.g. orange up for skin, blue down for a deeper sky."
            app.headsup.core.color.HueCurves.Channel.LUM_SAT -> "More or less colour by brightness: pulling the dark end down takes colour out of the shadows, a filmic look."
        },
    )
}

private val RGB_NAMES = listOf("Red", "Green", "Blue")

/** Channel mixer: an output channel, and how much of red, green and blue makes it (100 % = itself). */
@Composable
private fun ChannelMixerControls(state: UiState, vm: HeadsupViewModel) {
    var output by rememberSaveable { mutableStateOf(0) }
    val mix = state.grade.channelMix
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RGB_NAMES.forEachIndexed { i, name ->
                val changed = (0..2).any { mix.getOrElse(i * 3 + it) { 0f } != 0f }
                FilterChip(selected = output == i, onClick = { output = i }, label = { Text(name + " out" + if (changed) " •" else "") })
            }
        }
        if (mix.any { it != 0f }) SmallAction("Reset", { vm.updateGrade { it.copy(channelMix = app.headsup.core.color.ChannelMixer.NEUTRAL) } })
    }
    for (input in 0..2) {
        val index = output * 3 + input
        val base = if (input == output) 1f else 0f
        val v = base + mix.getOrElse(index) { 0f }
        ValueHeader("from ${RGB_NAMES[input]}", String.format(Locale.US, "%+.0f %%", v * 100))
        RulerSlider(
            value = v, range = -2f..2f, step = 0.01f,
            onChange = { x -> vm.updateGrade { g -> g.copy(channelMix = g.channelMix.toMutableList().also { it[index] = x - base }) } },
            onReset = { vm.updateGrade { g -> g.copy(channelMix = g.channelMix.toMutableList().also { it[index] = 0f }) } },
        )
    }
    Hint("Each output channel made from the three inputs. Swapping red and blue, or red from green (as infrared film), gives strong looks.")
}

/** Black and white: on or off, and how light each hue band becomes in grey. */
@Composable
private fun BlackAndWhiteControls(state: UiState, vm: HeadsupViewModel) {
    val B = app.headsup.core.color.BlackAndWhite
    val bw = state.grade.bw
    var band by rememberSaveable { mutableStateOf(app.headsup.core.color.ColourMixer.Band.BLUE) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Black & white", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = B.on(bw), onCheckedChange = { on -> vm.updateGrade { g -> g.copy(bw = g.bw.toMutableList().also { it[0] = if (on) 1f else 0f }) } })
    }
    if (!B.on(bw)) {
        Hint("Turns the picture grey; then each colour can be made lighter or darker (a deep sky, bright skin).")
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(app.headsup.core.color.ColourMixer.Band.entries) { b ->
            val changed = bw.getOrElse(1 + b.ordinal) { 0f } != 0f
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(b.swatch))
                    .border(if (b == band) 3.dp else if (changed) 1.dp else 0.dp, Color.White, CircleShape)
                    .clickable { band = b },
            )
        }
    }
    val index = 1 + band.ordinal
    ValueHeader("${band.label} in grey", String.format(Locale.US, "%+.0f", bw.getOrElse(index) { 0f } * 100))
    RulerSlider(
        value = bw.getOrElse(index) { 0f }, range = -1f..1f, step = 0.02f,
        onChange = { v -> vm.updateGrade { g -> g.copy(bw = g.bw.toMutableList().also { it[index] = v }) } },
        onReset = { vm.updateGrade { g -> g.copy(bw = g.bw.toMutableList().also { it[index] = 0f }) } },
    )
}

/** Calibration: a primary (red, green, blue), and its hue and saturation. */
@Composable
private fun CalibrationControls(state: UiState, vm: HeadsupViewModel) {
    var primary by rememberSaveable { mutableStateOf(0) }
    val cal = state.grade.calibration
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RGB_NAMES.forEachIndexed { i, name ->
                val changed = cal.getOrElse(i * 2) { 0f } != 0f || cal.getOrElse(i * 2 + 1) { 0f } != 0f
                FilterChip(selected = primary == i, onClick = { primary = i }, label = { Text(name + " primary" + if (changed) " •" else "") })
            }
        }
        if (cal.any { it != 0f }) SmallAction("Reset", { vm.updateGrade { it.copy(calibration = app.headsup.core.color.Calibration.NEUTRAL) } })
    }
    for ((offset, label) in listOf(0 to "Hue", 1 to "Saturation")) {
        val index = primary * 2 + offset
        ValueHeader(label, String.format(Locale.US, "%+.0f", cal.getOrElse(index) { 0f } * 100))
        RulerSlider(
            value = cal.getOrElse(index) { 0f }, range = -1f..1f, step = 0.02f,
            onChange = { v -> vm.updateGrade { g -> g.copy(calibration = g.calibration.toMutableList().also { it[index] = v }) } },
            onReset = { vm.updateGrade { g -> g.copy(calibration = g.calibration.toMutableList().also { it[index] = 0f }) } },
        )
    }
    Hint("Shifts every colour made of this primary: e.g. the blue primary's hue towards cyan for teal skies, red's saturation down for calmer skin.")
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
/**
 * Looks that suit what was measured in the clip: night, skin-like tones, loud greens and blues, a
 * warm (sunset) cast; the best first.
 */
private fun suggestedLooks(s: app.headsup.core.color.FrameStats): List<app.headsup.color.FastLook> {
    val out = mutableListOf<app.headsup.color.FastLook>()
    if (s.median < 0.22f) out += app.headsup.color.FastLook.NIGHT
    if (s.skin > SKIN_SHARE) { out += app.headsup.color.FastLook.CLEAN; out += app.headsup.color.FastLook.WARM_FILM }
    if (s.foliage > 0.15f) { out += app.headsup.color.FastLook.TEAL_ORANGE; out += app.headsup.color.FastLook.MUTED }
    if (s.warmth > 0.03f) { out += app.headsup.color.FastLook.FILM_35; out += app.headsup.color.FastLook.VINTAGE }
    if (s.colourfulness > 0.12f) out += app.headsup.color.FastLook.MUTED
    out += listOf(app.headsup.color.FastLook.CLEAN, app.headsup.color.FastLook.FILM_35, app.headsup.color.FastLook.TEAL_ORANGE)
    return out.distinct().take(3)
}

/** Skin-like tones count when they are more than this share of the picture. */
private const val SKIN_SHARE = 0.08f

/** The user's saved looks: one tap puts it on the clip; the clip's own look can be saved by name. */
@Composable
private fun PresetControls(state: UiState, vm: HeadsupViewModel) {
    LaunchedEffect(Unit) { vm.loadPresets() }
    var naming by remember { mutableStateOf(false) }
    SectionCaption("My presets") {
        Spacer(Modifier.width(8.dp))
        SmallAction("Save this look", { naming = true })
    }
    if (state.presets.isEmpty()) {
        Hint("Save the look of a clip or photo you like (its corrections, LUT, grain, glow…) and put it on others in any project with one tap. Masks and framing stay each clip's own.")
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in state.presets) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillAction("◆", p.name, { vm.applyPreset(p) })
                    Text(
                        "✕",
                        Modifier.clip(CircleShape).clickable { vm.deletePreset(p.name) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted,
                    )
                }
            }
        }
        Hint("Tap a preset to put its look on this ${if (state.photoProject) "photo" else "clip"}; ✕ deletes it.")
    }
    if (naming) {
        RenameDialog("Preset ${state.presets.size + 1}", onDismiss = { naming = false }, title = "Preset name") { vm.savePreset(it); naming = false }
    }
}

/**
 * Looks, in one place: Cinematic (complete looks: colour, contrast, grain, light, lens) and
 * Profiles (a starting rendering under the corrections). Analyse clip measures frames across the
 * clip: it suggests looks that suit it, and every look then fits it (brightness when clearly off,
 * highlights, loud colours, blacks), keeping its own character. Groups and all clips have the
 * profiles only (the rest is per clip).
 */
@Composable
private fun LooksPanel(state: UiState, vm: HeadsupViewModel, frameSource: (() -> Bitmap?)?) {
    val clip = state.selected
    if (state.target != GradeTarget.CLIP || clip == null) {
        LookControls(state, vm)
        return
    }
    var category by rememberSaveable { mutableStateOf(0) }
    Segmented(listOf("Cinematic", "Profiles", "My presets"), category, { category = it })
    if (category == 1) {
        LookControls(state, vm)
        return
    }
    if (category == 2) {
        PresetControls(state, vm)
        return
    }
    val stats = state.lookStats?.takeIf { it.first == clip.id }?.second
    val fast = state.fast?.takeIf { it.clipId == clip.id }
    fun choose(look: app.headsup.color.FastLook) = vm.applyFastLook(look, fast?.strength ?: 1f)
    // The clip measured: what was found, and the looks that suit it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(
            if (stats == null) (if (clip.meta.isPhoto) "Analyse the photo" else "Analyse the clip") + ": looks that suit it are suggested, and every look is fitted to it."
            else String.format(
                Locale.US, "Mid-tones %.0f %% · %.1f %% clipped · %s%s",
                stats.median * 100, stats.clipped * 100,
                if (stats.foliage > 0.15f) "lots of green/blue" else "colours calm",
                if (stats.skin > SKIN_SHARE) " · skin-like tones" else "",
            ),
            Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        PillAction("⌕", if (stats == null) (if (clip.meta.isPhoto) "Analyse photo" else "Analyse clip") else "Again", { vm.analyseClip(frameSource) }, enabled = !state.busy)
    }
    if (stats != null) {
        SectionCaption("Suggested for this clip")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (look in suggestedLooks(stats)) FilterChip(selected = fast?.look == look, onClick = { choose(look) }, label = { Text(look.label) })
        }
    }
    SectionCaption("Cinematic")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (look in app.headsup.color.FastLook.entries) {
            FilterChip(selected = fast?.look == look, onClick = { choose(look) }, label = { Text(look.label) })
        }
    }
    // The look being tried: its strength, then keep it or put the clip back.
    if (fast != null) {
        ValueHeader("Strength", String.format(Locale.US, "%.0f %%", fast.strength * 100))
        RulerSlider(
            fast.strength, 0f..1f, 0.02f, { v -> vm.applyFastLook(fast.look, v) },
            onReset = { vm.applyFastLook(fast.look, 1f) }, onHold = vm::compareFast,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAction("Undo", { vm.closeFast(undo = true) })
            Spacer(Modifier.weight(1f))
            SmallAction("Done", { vm.closeFast(undo = false) })
        }
        Hint(
            (if (stats != null) "Fitted to this clip. " else "Not fitted yet: Analyse clip fits it. ") +
                "Hold the ruler to see the clip without it. Done keeps it as normal corrections and effects, adjustable afterwards.",
        )
    } else {
        Hint("A complete look: colour taken away rather than added, soft highlights, grain and light. Profiles: a starting rendering under your corrections.")
    }
}

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
    if (session == null) FastEdit(state, vm)
    if (session == null) {
        SectionCaption("Cine assistant")
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
 * Fast edit: complete looks in one tap (colour, contrast, grain, glow, halation, lens), with their
 * strength; Done keeps them as ordinary corrections and effects.
 */
@Composable
private fun FastEdit(state: UiState, vm: HeadsupViewModel) {
    val clip = state.selected ?: return
    val fast = state.fast?.takeIf { it.clipId == clip.id }
    SectionCaption("Fast edit")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (look in app.headsup.color.FastLook.entries) {
            FilterChip(selected = fast?.look == look, onClick = { vm.applyFastLook(look, fast?.strength ?: 1f) }, label = { Text(look.label) })
        }
    }
    if (fast == null) {
        Hint("A whole look at once: colour taken away rather than added, soft highlights, grain and light. Everything stays adjustable afterwards.")
        return
    }
    ValueHeader("Strength", String.format(Locale.US, "%.0f %%", fast.strength * 100))
    RulerSlider(fast.strength, 0f..1f, 0.02f, { v -> vm.applyFastLook(fast.look, v) }, onReset = { vm.applyFastLook(fast.look, 1f) }, onHold = vm::compareFast)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction("Undo", { vm.closeFast(undo = true) })
        Spacer(Modifier.weight(1f))
        SmallAction("Done", { vm.closeFast(undo = false) })
    }
    Hint("Hold the ruler to see the clip without it. Done keeps it as normal corrections (Density, Roll-off, Tame…) and effects (Grain, Glow, Lens…).")
}

/**
 * Shot match: pick a reference frame in one clip (e.g. the Osmo), then on another clip (drone,
 * phone) pause on a similar frame and match: exposure, contrast, white balance and saturation are
 * set so both look alike, and skin hue too when both frames show skin. The result is ordinary
 * corrections.
 */
@Composable
private fun ShotMatchPanel(state: UiState, clip: ClipState, vm: HeadsupViewModel, frameSource: (() -> Bitmap?)?) {
    if (state.photoProject) {
        // An album: the marked photos matched to the one shown, all at once.
        val marked = state.clips.count { it.picked && it.id != clip.id }
        Hint("Mark the photos to match in the album (hold a photo to mark it), then show the photo whose colours you like and match them to it: white balance, tint, saturation and skin; each keeps its own brightness.")
        Button(onClick = vm::matchMarkedTo, enabled = marked > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (marked == 0) "No photos marked" else "Match $marked marked photo${if (marked == 1) "" else "s"} to this one", maxLines = 1)
        }
        return
    }
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
private fun BrushControls(state: UiState, vm: HeadsupViewModel, pictureSource: ((Int) -> Bitmap?)?) {
    val mask = state.selected?.masks?.getOrNull(state.selectedMask) ?: return
    val brush = state.brush
    // What a finger on the picture does, as a palette.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(BrushMode.entries) { mode ->
            CircleOption(mode.symbol, mode.label, selected = brush.mode == mode, modified = false) { vm.updateBrush { it.copy(mode = mode) } }
        }
    }
    when (brush.mode) {
        BrushMode.SELECT -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !brush.subtract, onClick = { vm.updateBrush { it.copy(subtract = false) } }, label = { Text("Add") })
                FilterChip(selected = brush.subtract, onClick = { vm.updateBrush { it.copy(subtract = true) } }, label = { Text("Subtract") })
            }
            Hint("Tap an object to select it; tap other parts of it to take them in too, or with Subtract a part that is not it (the whole is found again each time). Tap the same place again for a smaller or larger part. You can also draw round it, or a line across to split the picture. Then fix what is wrong (Push), and finish below.")
        }
        BrushMode.PUSH -> {
            // Grab the outline and move it; then lay it onto the picture's edges if wanted.
            ValueHeader("Grip", String.format(Locale.US, "%.0f %%", brush.radius * 100f))
            RulerSlider(
                value = brush.radius, range = BrushSetting.SIZE.range, step = 0.005f,
                onChange = { v -> vm.updateBrush { it.copy(radius = v) }; vm.showBrush() },
                onReset = { vm.updateBrush { it.copy(radius = BrushSetting.SIZE.default) }; vm.showBrush() },
            )
            Hint("Put a finger on the outline where it is wrong and move it where it should be: that part follows, the rest stays. Grip: how much of the outline moves with the finger.")
        }
        BrushMode.PAINT, BrushMode.ERASE, BrushMode.SOFTEN -> {
            // Strength only for Soften (how soft); painting is always at full strength.
            val settings = if (brush.mode == BrushMode.SOFTEN) listOf(BrushSetting.SIZE, BrushSetting.FLOW) else listOf(BrushSetting.SIZE, BrushSetting.SOFTNESS)
            var chosen by rememberSaveable { mutableStateOf(BrushSetting.SIZE) }
            val setting = chosen.takeIf { it in settings } ?: settings.first()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(settings) { b ->
                    CircleOption(b.symbol, b.label, selected = b == setting, modified = false) { chosen = b }
                }
            }
            val value = when (setting) {
                BrushSetting.SIZE -> brush.radius
                BrushSetting.SOFTNESS -> brush.softness
                BrushSetting.FLOW -> brush.flow
            }
            fun set(v: Float) {
                when (setting) {
                    BrushSetting.SIZE -> vm.updateBrush { it.copy(radius = v) }
                    // The brush's edge, for the strokes from now on (those painted stay as they are).
                    BrushSetting.SOFTNESS -> vm.updateBrush { it.copy(softness = v) }
                    BrushSetting.FLOW -> vm.updateBrush { it.copy(flow = v) }
                }
                // The brush shows on the picture itself, at the picture's scale, for a moment.
                vm.showBrush()
            }
            ValueHeader(setting.label, String.format(Locale.US, "%.0f %%", value * 100f))
            RulerSlider(value = value, range = setting.range, step = 0.005f, onChange = { set(it) }, onReset = { set(setting.default) })
            Hint(
                if (brush.mode == BrushMode.SOFTEN) "Soften makes the mask's edge gradual where you paint along it; elsewhere the cut stays sharp. Size: how wide the soft band is; Strength: how soft. Two fingers zoom the picture; View moves around it."
                else if (brush.mode == BrushMode.ERASE) "Erase takes the mask's corrections away where you paint. Two fingers zoom the picture; View moves around it."
                else "Paint where the mask's corrections go. Softness: how gradual the brush's edge is, for the strokes you paint from now on (low for precise edges); what is painted stays as it is. Two fingers zoom the picture; View moves around it.",
            )
        }
        BrushMode.MOVE -> Hint("One finger moves the mask; two turn and scale it.")
        BrushMode.VIEW -> Hint("One finger moves around the zoomed picture, two zoom it, a double tap fits it again. Nothing is painted.")
    }
    // The last steps, in the order they are taken: Select, fix it (Push, Paint, Erase), then lay it
    // onto the picture's edges, then take a little in or out.
    if (mask.strokes.isNotEmpty() || mask.sequence != null) {
        SectionCaption("Finish")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1", style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            PillAction("⌁", "Fit to edges", { pictureSource?.let(vm::fitMaskToEdges) }, enabled = pictureSource != null && mask.strokes.isNotEmpty() && !state.busy)
            Hint("lays the outline onto the picture's edges close to it", Modifier.weight(1f))
        }
        val e = mask.edge
        ValueHeader(
            "2 · Edge",
            when {
                kotlin.math.abs(e) < 0.005f -> "As it is"
                e < 0f -> String.format(Locale.US, "Tighter %.0f %%", -e * 100f)
                else -> String.format(Locale.US, "Fuller %.0f %%", e * 100f)
            },
        )
        RulerSlider(value = e, range = -1f..1f, step = 0.05f, onChange = { v -> vm.setMaskEdge(v, pictureSource) }, onReset = { vm.setMaskEdge(0f, pictureSource) })
        Hint("Edge takes in a little more by the outline (a rim, a halo) or a little less, only what looks like it, so the outline keeps the picture's shape.")
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
    // How wide the gradient is: up to twice the picture's height, a gradient across all of it.
    SOFTNESS("◐", "Feather", setOf(MaskKind.LINEAR, MaskKind.ELLIPSE), 0.02f..2f, 0.02f, "%.0f %%", { it.softness }, { m, v -> m.copy(softness = v) }),
    // A colour or light range covers the picture wherever it matches: turning it changes nothing.
    ANGLE("∠", "Angle", setOf(MaskKind.LINEAR, MaskKind.ELLIPSE, MaskKind.BRUSH), -180f..180f, 0.5f, "%+.1f°", { it.angleDegrees }, { m, v -> m.copy(angleDegrees = v) }),
    ;

    /** Value as shown above the ruler (fractions as percent). */
    fun shown(mask: ColourMask): Float = if (this == ANGLE) get(mask) else get(mask) * 100f
}

/**
 * The clip's layers: its base (the corrections of the whole picture) and its masks, then one chip
 * to add a mask; under them the selected layer's actions. Returns true while a new mask is being
 * chosen (only that shows).
 */
@Composable
private fun Layers(
    state: UiState,
    clip: ClipState,
    vm: HeadsupViewModel,
    pictureSource: ((Int) -> Bitmap?)?,
    maskTools: Boolean,
    onMaskTools: (Boolean) -> Unit,
): Boolean {
    var adding by rememberSaveable { mutableStateOf(false) }
    val full = clip.masks.size >= ColourMask.MAX_PER_CLIP
    val onMask = state.target == GradeTarget.MASK
    SectionCaption("Layers")
    // What is there, then one chip to add: what exists and what can be made never mix.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item {
            LayerChip("▣", "Base", selected = !onMask && !adding, modified = clip.grade != ColorGrade()) {
                adding = false
                vm.setTarget(GradeTarget.CLIP)
            }
        }
        items(clip.masks.size) { i ->
            val m = clip.masks[i]
            LayerChip(maskSymbol(m), if (i > 0 && m.join != MaskJoin.NEW) "${m.join.symbol} ${maskName(m)}" else "${i + 1} ${maskName(m)}", selected = onMask && i == state.selectedMask && !adding, modified = m.grade != ColorGrade()) {
                adding = false
                vm.selectMask(i)
                vm.setTarget(GradeTarget.MASK)
            }
        }
        if (!full) item { LayerChip("+", if (clip.masks.isEmpty()) "Mask" else "", selected = adding, modified = false) { adding = !adding } }
    }
    // What was removed from the photo (Remove), taken back one by one or all at once.
    val removed = clip.retouch.map { it.hole }.distinct().size
    if (clip.meta.isPhoto && removed > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (removed == 1) "1 thing removed" else "$removed things removed", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
            SmallAction("Undo last", { vm.undoLastCleanup() }, enabled = !state.busy)
            SmallAction("Restore all", { vm.clearCleanup() }, enabled = !state.busy)
        }
    }
    if (adding) {
        if (full) {
            Hint("Up to ${ColourMask.MAX_PER_CLIP} masks.")
            return true
        }
        fun add(action: () -> Unit) {
            adding = false
            vm.setTarget(GradeTarget.MASK)
            // A new mask opens on its own tools (select, paint, shape), whichever was shown before:
            // removing from a photo has no switch to get to them.
            onMaskTools(true)
            action()
        }
        // What a new mask is: one list, what is found (an object, the sky) and what is drawn or picked.
        SectionCaption("New mask")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item { CircleOption("◎", "Object", selected = false, modified = false) { add { vm.addBrushMask(BrushMode.SELECT) } } }
            item {
                CircleOption(app.headsup.color.SceneKeys.symbol(app.headsup.color.Scene.SKY.name), "Sky", selected = false, modified = false) {
                    add { pictureSource?.let { grab -> vm.addSceneMask(app.headsup.color.Scene.SKY.name, grab) } }
                }
            }
            item { CircleOption("✎", "Brush", selected = false, modified = false) { add { vm.addBrushMask(BrushMode.PAINT) } } }
            item { CircleOption("⟋", "Linear", selected = false, modified = false) { add { vm.addMask(MaskKind.LINEAR) } } }
            item { CircleOption("◯", "Radial", selected = false, modified = false) { add { vm.addMask(MaskKind.ELLIPSE) } } }
            item { CircleOption("◈", "Colour", selected = false, modified = false) { add { vm.addRangeMask(colour = true) } } }
            item { CircleOption("☼", "Light", selected = false, modified = false) { add { vm.addRangeMask(colour = false) } } }
            // Photos: select something as a mask is selected, and it is removed (filled from around it).
            if (clip.meta.isPhoto) item { CircleOption("⌫", "Remove", selected = false, modified = false) { add { vm.startRemoval() } } }
        }
        Hint(
            "Object: tap what to correct (tap more of it to take it in, with Subtract what is not it), draw round it or split the picture with a line. Sky: the sky found at once (on video followed when tracked). " +
                "Brush: paint it. Linear: a gradient across the picture (drag its dashed lines to set where it starts and ends). Radial: a gradient round a point (a face, a light, a vignette of your own). Colour: everything of a colour you tap. Light: a range of brightness." +
                if (clip.meta.isPhoto) " Remove: select something and it is taken out of the photo, filled from what is around it." else "",
        )
        return true
    }
    if (!onMask) {
        LookActions(state, vm)
        return false
    }
    val mask = clip.masks.getOrNull(state.selectedMask) ?: return false
    // The selected mask's own actions, always in the same place.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PillAction("◑", "Invert", { vm.updateMask { it.copy(inverted = !it.inverted) } }, selected = mask.inverted)
        // A copy, or its opposite (the sky's mask as the land's), with its tracking.
        if (clip.masks.size < ColourMask.MAX_PER_CLIP) {
            PillAction("⧉", "Copy", { vm.duplicateMask(inverted = false) })
            PillAction("◐", "Opposite", { vm.duplicateMask(inverted = true) })
        }
        if (mask.grade != ColorGrade()) PillAction("↺", "Reset", { vm.updateGrade { ColorGrade() } })
        PillAction("✕", "Delete", { vm.removeMask(state.selectedMask) })
    }
    // The mask itself (where it is: select, paint, shape, track) or what it changes in the picture.
    // Only while the mask is worked on does a finger on the picture change it.
    if (!state.removing) Segmented(listOf("Mask", "Adjust"), if (maskTools) 0 else 1, { onMaskTools(it == 0) })
    return false
}

/**
 * The colour and luminance ranges of [mask]: everything of a colour picked on the picture, and/or
 * of a range of brightness. On a range mask they are the mask; on another they narrow it.
 */
@Composable
private fun MaskRanges(state: UiState, mask: ColourMask, vm: HeadsupViewModel) {
    val colour = mask.colour
    SectionCaption("Colour range") {
        if (colour != null) SmallAction("Remove", { vm.updateMask { it.copy(colour = null) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (colour != null) {
            Box(
                Modifier.size(28.dp).clip(CircleShape)
                    .background(Color(colour.r, colour.g, colour.b))
                    .border(1.dp, HeadsupColors.Outline, CircleShape),
            )
        }
        PillAction("◈", if (colour == null) "Pick a colour" else "Pick again", { vm.setPickingColour(!state.pickingColour) }, selected = state.pickingColour)
    }
    if (state.pickingColour) Hint("Tap the colour on the picture (hold and slide to aim).")
    if (colour == null && !state.pickingColour) {
        Hint(
            if (mask.kind == MaskKind.RANGE) "The mask is every part of the picture in one colour, wherever it is: pick it on the picture (e.g. the sky's blue, a red jacket), then set how close a colour must be to count. Its corrections (Adjust) change only that colour."
            else "Narrows this mask to one colour inside it: pick the colour on the picture; only that colour within the mask gets its corrections.",
        )
    }
    if (colour != null) {
        Hint("Range: how far from the picked colour still counts. Low: only that very colour; high: its neighbours too. Hold a finger on the picture to see it without this mask.")
        ValueHeader("Range", String.format(Locale.US, "%.0f %%", colour.tolerance * 100))
        RulerSlider(
            value = colour.tolerance,
            range = 0f..1f,
            step = 0.01f,
            onChange = { v -> vm.updateMask { m -> m.copy(colour = m.colour?.copy(tolerance = v)) } },
            onReset = { vm.updateMask { m -> m.copy(colour = m.colour?.copy(tolerance = 0.35f)) } },
        )
    }
    val luma = mask.luma
    SectionCaption("Luminance range") {
        if (luma != null) SmallAction("Remove", { vm.updateMask { it.copy(luma = null) } })
    }
    if (luma == null) {
        PillAction("☼", "Limit by brightness", { vm.updateMask { it.copy(luma = LumaRange()) } })
        return
    }
    var part by rememberSaveable { mutableStateOf(0) }
    Segmented(listOf("From", "To", "Softness"), part, { part = it })
    val value = when (part) { 0 -> luma.low; 1 -> luma.high; else -> luma.soft }
    ValueHeader(listOf("From", "To", "Softness")[part], String.format(Locale.US, "%.0f %%", value * 100))
    RulerSlider(
        value = value,
        range = if (part == 2) 0f..0.5f else 0f..1f,
        step = 0.01f,
        onChange = { v ->
            vm.updateMask { m ->
                val l = m.luma ?: return@updateMask m
                m.copy(
                    luma = when (part) {
                        0 -> l.copy(low = v.coerceAtMost(l.high))
                        1 -> l.copy(high = v.coerceAtLeast(l.low))
                        else -> l.copy(soft = v)
                    },
                )
            }
        },
        onReset = {
            val d = LumaRange()
            vm.updateMask { m -> m.copy(luma = m.luma?.let { l -> when (part) { 0 -> l.copy(low = d.low); 1 -> l.copy(high = d.high); else -> l.copy(soft = d.soft) } }) }
        },
    )
}

/** How mask [index] works with the one before it: its own layer, or added to, taken from, or crossed with it. */
@Composable
private fun MaskJoinControls(clip: ClipState, index: Int, mask: ColourMask, vm: HeadsupViewModel) {
    val layer = MaskRange.layerOf(clip.masks.take(index), index - 1)
    SectionCaption("Combine")
    Segmented(MaskJoin.entries.map { if (it == MaskJoin.NEW) "Own" else it.label }, mask.join.ordinal, { vm.setMaskJoin(MaskJoin.entries[it]) })
    Hint(
        if (mask.join == MaskJoin.NEW) "Its own corrections. Add, Subtract or Intersect make it change where mask ${layer + 1} applies instead."
        else "${mask.join.label} with mask ${layer + 1} (${maskName(clip.masks[layer])}), which keeps the corrections.",
    )
}

/**
 * [masks] with the layer of mask [index] (it and the masks joined to it) taking no part: the
 * picture as it is without that mask's corrections.
 */
private fun withoutLayer(masks: List<ColourMask>, index: Int): List<ColourMask> {
    if (index !in masks.indices) return masks
    val head = MaskRange.layerOf(masks, index)
    var end = head
    while (end + 1 < masks.size && masks[end + 1].join != MaskJoin.NEW) end++
    // A range mask with no range, inverted: covers nothing.
    return masks.mapIndexed { k, m -> if (k in head..end) ColourMask(MaskKind.RANGE, inverted = true) else m }
}

/** A painted mask on video that is not tracked, or changed since it was. */
private fun maskNeedsTracking(clip: ClipState, mask: ColourMask): Boolean =
    !clip.meta.isPhoto && mask.kind == MaskKind.BRUSH && mask.strokes.isNotEmpty() &&
        (mask.sequence == null || mask.strokes.size > mask.sequenceStrokes)

/** What a mask is, as its list names it. */
private fun maskName(m: ColourMask): String = when (m.kind) {
    MaskKind.LINEAR -> "Linear"
    MaskKind.ELLIPSE -> "Radial"
    MaskKind.RANGE -> when {
        m.colour != null && m.luma == null -> "Colour"
        m.luma != null && m.colour == null -> "Light"
        else -> "Range"
    }
    MaskKind.BRUSH -> {
        val adds = m.strokes.filter { !it.erase && !it.soften }
        val scene = adds.firstOrNull()?.scene
        when {
            scene != null && adds.all { it.scene == scene } -> app.headsup.color.SceneKeys.label(scene)
            adds.isNotEmpty() && adds.all { it.sky } -> "Sky"
            adds.any { it.segment || it.lasso } -> "Object"
            else -> "Brush"
        }
    }
}

private fun maskSymbol(m: ColourMask): String = m.strokes.filter { !it.erase && !it.soften }.let { adds ->
    val scene = adds.firstOrNull()?.scene
    if (m.kind == MaskKind.BRUSH && scene != null && adds.all { it.scene == scene }) app.headsup.color.SceneKeys.symbol(scene) else null
} ?: when (maskName(m)) {
    "Linear" -> "⟋"
    "Radial" -> "◯"
    "Sky" -> "☁"
    "Object" -> "◎"
    "Colour", "Range" -> "◈"
    "Light" -> "☼"
    else -> "✎"
}

/**
 * How the selected mask follows movement in a clip: tracked with AI (a painted mask, found again on
 * each frame), or tracked by its shape and keys (a line or oval).
 */
@Composable
private fun MaskMotion(state: UiState, clip: ClipState, mask: ColourMask, vm: HeadsupViewModel, playhead: LongState, onSeek: (Long) -> Unit) {
    if (clip.meta.isPhoto) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        val stale = maskNeedsTracking(clip, mask)
        Text(
            when {
                stale && mask.sequence != null -> "Changed since tracking"
                stale -> "Not tracked: stays as drawn"
                mask.sequence != null -> "Tracked on each frame"
                mask.keys.isEmpty() -> "Stays put"
                else -> "Moves with the shot"
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            // What still needs doing stands out.
            color = if (stale) HeadsupColors.Accent else HeadsupColors.TextMuted,
        )
        if (mask.sequence != null) {
            // Tracked with AI: from a fix at the playhead both ways (up to the frames fixed before), or back to the strokes.
            SmallAction("Track", { vm.trackMask(HeadsupViewModel.TrackDirection.BOTH) }, enabled = !state.busy)
            SmallAction("Untrack", { vm.clearMaskSequence() }, enabled = !state.busy)
        } else if (mask.keys.isEmpty()) {
            SmallAction("Track", { vm.trackMask(HeadsupViewModel.TrackDirection.BOTH) }, enabled = !state.busy)
        } else {
            // Continuing from a key placed by hand keeps what was already tracked.
            SmallAction("◀ Track", { vm.trackMask(HeadsupViewModel.TrackDirection.BACKWARD) }, enabled = !state.busy)
            SmallAction("Track ▶", { vm.trackMask(HeadsupViewModel.TrackDirection.FORWARD) }, enabled = !state.busy)
        }
    }
    if (mask.sequence == null) {
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
        when {
            mask.kind == MaskKind.BRUSH && mask.sequence != null -> "Found again on each frame by AI. Where it slips, go there, fix it (Select, Paint, Erase) and Track: the fix moves with the picture onto the frames before and after, up to the frames fixed earlier (${mask.anchors.size} so far), which stay as they are."
            mask.kind == MaskKind.BRUSH -> "Track finds the mask again on each frame with AI (about a second a frame on a phone; Stop keeps what was found): it keeps the object's shape and ends when the object leaves the picture."
            mask.keys.isEmpty() -> "Place the mask, then Track (it follows the picture both ways from here), or pause, + Key, and move the mask at a few moments."
            else -> "Pause on a key (or tap its diamond) and move the mask to change it; between keys it glides."
        },
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
    val CL = app.headsup.core.color.CameraLog
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current == null, onClick = { onPick(null) }, label = { Text(noneLabel) })
        for (name in state.luts) {
            FilterChip(selected = current == name, onClick = { onPick(name) }, label = { Text(name) })
        }
        FilterChip(selected = false, onClick = onAddLut, label = { Text("+ .cube") })
    }
    // Built in: the cameras' log curves to Rec.709, from what their makers publish.
    SectionCaption("Camera log → Rec.709")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in app.headsup.core.color.CameraLog.Profile.entries) {
            val name = CL.nameOf(p)
            FilterChip(selected = current == name, onClick = { onPick(if (current == name) null else name) }, label = { Text(p.label) })
        }
    }
    Hint("Pick the camera's log profile and gamut as set when shooting. Made from the makers' published curves; for a profile without one (DJI D-Log M), load the maker's .cube.")
    Row {
        if (onAll != null) SmallAction(if (state.photoProject) "Use for all photos" else "Use for all clips", { onAll(current) })
        Spacer(Modifier.weight(1f))
        current?.takeIf { CL.profileOf(it) == null }?.let { name -> SmallAction("Delete $name", { vm.deleteLut(name) }) }
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
        // One look across the group: no clip greener or warmer than the others.
        SectionCaption("Make the group alike")
        val session = state.matchSession?.takeIf { it.groupId == group.id && it.memberDeltas.isNotEmpty() }
        val running = state.work.any { it.clipId == clip.id && it.maskIndex == null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(
                "Matches every clip of the group in colour to this one (white balance, tint, saturation, skin), measured across each clip. Each keeps its own brightness.",
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PillAction("≈", "Match to this", { vm.matchGroupTo() }, enabled = !running)
        }
        if (session != null) {
            // What is matched.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (part in MatchPart.entries) {
                    val on = part in session.parts
                    FilterChip(selected = on, onClick = { vm.setMatchParts(if (on) session.parts - part else session.parts + part) }, label = { Text(part.label) })
                }
            }
            ValueHeader("Match strength", String.format(Locale.US, "%.0f %%", session.strength * 100))
            RulerSlider(session.strength, 0f..1f, 0.02f, vm::setMatchStrength, onReset = { vm.setMatchStrength(1f) })
            // Each clip: in or out, how much of its change, and (selected) its own ruler.
            SectionCaption("Clips matched")
            for ((id, delta) in session.memberDeltas) {
                val i = state.clips.indexOfFirst { it.id == id }
                val c = state.clips.getOrNull(i) ?: continue
                val share = session.memberStrength[id] ?: 1f
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.selectClip(i) }
                        .background(if (c.id == clip.id) HeadsupColors.SurfaceHigh else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = share > 0f, onCheckedChange = { vm.setMemberMatch(id, if (it) 1f else 0f) })
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${c.meta.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Hint(String.format(Locale.US, "warmth %+.2f · tint %+.2f · saturation %+.2f%s", delta.temperature, delta.tint, delta.saturation, if (delta.skinHue != 0f) String.format(Locale.US, " · skin %+.0f°", delta.skinHue) else ""))
                    }
                    Text(String.format(Locale.US, "%.0f %%", share * 100), style = MaterialTheme.typography.labelLarge, color = if (share > 0f) Color.White else HeadsupColors.TextMuted)
                }
            }
            session.memberDeltas[clip.id]?.let {
                val share = session.memberStrength[clip.id] ?: 1f
                ValueHeader("This clip", String.format(Locale.US, "%.0f %%", share * 100))
                RulerSlider(share, 0f..1f, 0.02f, { v -> vm.setMemberMatch(clip.id, v) }, onReset = { vm.setMemberMatch(clip.id, 1f) }, onHold = vm::compareMatchMember)
                Hint("Hold the ruler to see this clip before the match. Tap a clip in the list to show it.")
            } ?: Hint("Tap a clip in the list to show it and set how much of the match it takes.")
            Row {
                Spacer(Modifier.weight(1f))
                SmallAction("Undo match", vm::undoMatch)
            }
        }
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
    if (r.fit) {
        // What fills the bars: black, or the picture itself blurred behind it (as social apps do).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bars", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelLarge, color = HeadsupColors.TextMuted)
            // For the whole film: one look for every fitted clip.
            FilterChip(selected = !r.blur, onClick = { vm.setBlurBars(false) }, label = { Text("Black") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = r.blur, onClick = { vm.setBlurBars(true) }, label = { Text("Blur") })
        }
    }
    Hint(
        if (r.fit && r.blur) "The whole picture inside the frame, the bars filled with it blurred (every fitted clip of the film); zoom in to fill more."
        else if (r.fit) "The whole picture inside the frame, black bars where the shapes differ; zoom in to fill more."
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
            SmallAction(if (r.fit) "Fit all clips" else "Fill all clips", { vm.setFitForAll(r.fit, r.blur) })
        }
    }
}

// Effects page

/** The row of effects; texts (for the whole timeline) are one of them. */
@Composable
private fun EffectKinds(state: UiState, kind: EffectKind, kinds: List<EffectKind>, onKind: (EffectKind) -> Unit) {
    val effects = state.effects
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(kinds) { e ->
            val active = when (e) {
                EffectKind.TEXT -> state.texts.isNotEmpty()
                EffectKind.MAP -> state.routeMap.enabled
                EffectKind.DASHBOARD -> state.style.enabled && state.effects.dashboard && state.activities.isNotEmpty()
                EffectKind.GRAPH -> effects.graph != null
                EffectKind.GRAIN -> effects.grain > 0f
                EffectKind.GLOW -> effects.glow > 0f
                EffectKind.HALATION -> effects.halation > 0f
                EffectKind.LENS -> effects.lens
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
    LENS("Lens", "Lens"),
}

/** Drawn over the picture: the Overlays tool. */
private val OVERLAY_KINDS = listOf(EffectKind.TEXT, EffectKind.MAP, EffectKind.DASHBOARD, EffectKind.GRAPH)

/** Part of the picture's look: the base layer's Effects in Adjust. */
private val LOOK_KINDS = listOf(EffectKind.GRAIN, EffectKind.GLOW, EffectKind.HALATION, EffectKind.LENS, EffectKind.BARS)

/** Effects over the picture, picked from round options like the colour corrections. */
@Composable
private fun EffectsPanel(state: UiState, vm: HeadsupViewModel, kind: EffectKind, playhead: LongState, kinds: List<EffectKind>, onKind: (EffectKind) -> Unit) {
    val style = state.style
    val effects = state.effects
    val activities = state.activities
    val updateStyle = vm::updateStyle
    val updateEffects = vm::updateEffects
    EffectKinds(state, kind, kinds, onKind)
    // The dashboard and the graph are set per clip, or on all clips at once (only what is changed).
    if ((kind == EffectKind.DASHBOARD && activities.isNotEmpty()) || kind == EffectKind.GRAPH) {
        val album = state.photoProject
        Segmented(
            listOf(if (album) "This photo" else "This clip", if (album) "All photos" else "All clips"),
            if (state.effectsOnAll) 1 else 0, { vm.setEffectsOnAll(it == 1) },
        )
    }
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
            // Film stocks as starting points: grain size and how much of it is in colour.
            val stocks = listOf(Triple("Fine", 0.7f, 0.15f), Triple("35 mm", 1f, 0.3f), Triple("16 mm", 1.8f, 0.4f), Triple("Super 8", 2.6f, 0.5f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stocks) { (label, size, colour) ->
                    FilterChip(
                        selected = effects.grainSize == size && effects.grainColour == colour,
                        onClick = { updateEffects { it.copy(grainSize = size, grainColour = colour, grain = if (it.grain == 0f) 0.35f else it.grain) } },
                        label = { Text(label) },
                    )
                }
            }
            EffectRuler(
                listOf(
                    EffectParam("Amount", effects.grain, 0f..1f, 0.02f, 0f, percent = true) { v -> updateEffects { it.copy(grain = v) } },
                    EffectParam("Size", effects.grainSize, 0.5f..3f, 0.1f, 1f, format = "%.1f") { v -> updateEffects { it.copy(grainSize = v) } },
                    EffectParam("Colour", effects.grainColour, 0f..1f, 0.02f, 0.3f, percent = true) { v -> updateEffects { it.copy(grainColour = v) } },
                ),
            )
            Hint("Clumps of grain, strongest in the midtones as on film; it also softens hard digital steps in gradients.")
        }
        EffectKind.GLOW -> {
            EffectRuler(
                listOf(
                    EffectParam("Glow", effects.glow, 0f..1f, 0.02f, 0f, percent = true) { v -> updateEffects { it.copy(glow = v) } },
                    EffectParam("Threshold", effects.glowThreshold, 0.4f..0.98f, 0.02f, 0.75f, percent = true) { v -> updateEffects { it.copy(glowThreshold = v) } },
                    EffectParam("Spread", effects.glowSpread, 0.5f..2.5f, 0.05f, 1f, format = "× %.2f") { v -> updateEffects { it.copy(glowSpread = v) } },
                    EffectParam("Colour", effects.glowWarmth, -1f..1f, 0.02f, 0f, format = "%+.2f") { v -> updateEffects { it.copy(glowWarmth = v) } },
                ),
            )
            Hint("Light around what is brighter than the threshold. A small spread keeps it subtle; Colour: cool (−) to warm (+).")
        }
        EffectKind.HALATION -> {
            EffectRuler(
                listOf(
                    EffectParam("Halation", effects.halation, 0f..1f, 0.02f, 0f, percent = true) { v -> updateEffects { it.copy(halation = v) } },
                    EffectParam("Threshold", effects.glowThreshold, 0.4f..0.98f, 0.02f, 0.75f, percent = true) { v -> updateEffects { it.copy(glowThreshold = v) } },
                    EffectParam("Spread", effects.glowSpread, 0.5f..2.5f, 0.05f, 1f, format = "× %.2f") { v -> updateEffects { it.copy(glowSpread = v) } },
                ),
            )
            Hint("Red-orange bleed around bright highlights, as on motion picture film. Threshold and spread are the glow's.")
        }
        EffectKind.LENS -> {
            EffectRuler(
                listOf(
                    EffectParam("Fringes", effects.lensAberration, 0f..1f, 0.02f, 0f, percent = true) { v -> updateEffects { it.copy(lensAberration = v) } },
                    EffectParam("Soft corners", effects.lensSoftness, 0f..1f, 0.02f, 0f, percent = true) { v -> updateEffects { it.copy(lensSoftness = v) } },
                ),
            )
            Hint("An older lens: colour fringes and softness that grow towards the corners; the middle stays sharp. A little goes a long way.")
        }
        EffectKind.BARS -> {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = effects.barsRatio == null, onClick = { updateEffects { it.copy(barsRatio = null) } }, label = { Text("Off") }) }
                items(listOf(2.39f to "2.39:1", 2.0f to "2:1", 1.85f to "1.85:1")) { (ratio, label) ->
                    FilterChip(selected = effects.barsRatio == ratio, onClick = { updateEffects { it.copy(barsRatio = ratio) } }, label = { Text(label) })
                }
            }
            // Sliding in and out is in time: a photo has none.
            if (effects.barsRatio != null && !state.photoProject && state.selected?.meta?.isPhoto != true) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (m in app.headsup.render.BarsMotion.entries) {
                        FilterChip(selected = effects.barsMotion == m, onClick = { updateEffects { it.copy(barsMotion = m) } }, label = { Text(m.label) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            if (state.timelineAspect < 16f / 9f - 0.01f) Hint("On this frame the bars take as much of the height as they would on 16:9, for the same look.")
        }
    }
}

/** One value an effect's ruler can edit. */
private class EffectParam(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val neutral: Float,
    val format: String = "%.2f",
    val percent: Boolean = false,
    val set: (Float) -> Unit,
)

/** An effect's values: which one the ruler edits (when there are several), its value, the ruler. */
@Composable
private fun EffectRuler(params: List<EffectParam>) {
    var index by rememberSaveable(params.map { it.label }.joinToString()) { mutableStateOf(0) }
    val p = params[index.coerceIn(0, params.size - 1)]
    if (params.size > 1) Segmented(params.map { it.label }, index, { index = it })
    ValueHeader(p.label, if (p.percent) String.format(Locale.US, "%.0f %%", p.value * 100) else String.format(Locale.US, p.format, p.value))
    RulerSlider(p.value, p.range, p.step, { v -> p.set(v) }, onReset = { p.set(p.neutral) })
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
    SectionCaption("Picture")
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
    SectionCaption("Encoding")
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
    SectionCaption("Export")
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
        SectionCaption("Exports") {
            Spacer(Modifier.width(8.dp))
            if (jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) {
                SmallAction("Cancel all", vm::cancelExports)
            } else {
                SmallAction("Clear list", vm::clearFinishedExports)
            }
        }
        jobs.forEach { JobRow(it) }
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
            Hint("Mark photos for export: hold a thumbnail (double-tap a marked one for its menu), or Mark under the photos.")
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
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        // A plain bar: no gap and no end dot (it read as part of the progress).
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
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
            Hint(
                if (state.photoProject) "Adds a text to the photo. Drag it on the picture to place it."
                else "Adds a text at the playhead. Drag it on the picture to place it; its bar on the timeline moves it in time.",
            )
        } else {
            Hint(if (state.photoProject) "Tap a text below to edit it." else "Tap a text's bar on the timeline to edit it.")
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
    // Only the set chosen in the album (Edit these): 20 starred photos, not all 500.
    val inScope = state.clips.indices.filter { state.albumScope.has(state.clips[it]) }
    if (state.albumScope != AlbumScope.ALL) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint("Editing ${state.albumScope.label}: ${inScope.size} of ${state.clips.size}", Modifier.weight(1f))
            SmallAction("Change", { vm.openAlbum() })
            SmallAction("All", { vm.setAlbumScope(AlbumScope.ALL) })
        }
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(inScope.size) { k ->
            val i = inScope[k]
            val c = state.clips[i]
            val selected = i == state.selectedClip
            // Held: marked (for export, matching, moving and deleting together). Double-tapped:
            // the menu for the marked photos (this one alone when it is not marked).
            var menu by remember { mutableStateOf(false) }
            Box {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HeadsupColors.Surface)
                    .border(if (selected) 2.dp else 1.dp, if (selected) HeadsupColors.Accent else HeadsupColors.Outline, RoundedCornerShape(10.dp))
                    .combinedClickable(onLongClick = { vm.togglePicked(i) }, onDoubleClick = { onSelect(i); menu = true }) { onSelect(i) },
            ) {
                // Read here, so this tile (not only the row) shows its picture when it arrives.
                thumbs.version.intValue
                GradedThumb(vm, c, thumbs.frame(c.meta.uri, 0L, 1L, photo = true), Modifier.matchParentSize())
                if (c.picked) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(HeadsupColors.Highlight),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.OnHighlight) }
                }
                if (c.rating > 0) {
                    Text(
                        "${if (c.suggested) "✩" else "★"}${c.rating}",
                        Modifier.align(Alignment.BottomStart).padding(3.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.suggested) HeadsupColors.AccentSoft else HeadsupColors.Accent,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val many = c.picked
                val n = if (many) state.clips.count { it.picked } else 1
                val what = if (n == 1) "photo" else "$n photos"
                DropdownMenuItem(text = { Text("Export $what") }, onClick = {
                    menu = false
                    vm.exportPhotos(if (many) HeadsupViewModel.PhotoSet.PICKED else HeadsupViewModel.PhotoSet.CURRENT)
                })
                if (many) {
                    if (state.clips.take(state.clips.indexOfFirst { it.picked }).any { !it.picked }) DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; vm.movePicked(-1) })
                    if (state.clips.drop(state.clips.indexOfLast { it.picked } + 1).any { !it.picked }) DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; vm.movePicked(1) })
                    DropdownMenuItem(text = { Text("Unmark") }, onClick = { menu = false; state.clips.indices.filter { state.clips[it].picked }.forEach(vm::togglePicked) })
                    DropdownMenuItem(text = { Text("Delete $what") }, onClick = { menu = false; vm.removePicked() })
                } else {
                    if (i > 0) DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; vm.moveClip(i, -1) })
                    if (i < state.clips.lastIndex) DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; vm.moveClip(i, 1) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.removeClip(i) })
                }
            }
            }
        }
        item {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(10.dp)).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(HeadsupIcons.Add, contentDescription = "Add", tint = HeadsupColors.Highlight) }
        }
        item {
            // The album large: rate, Auto pick, filter.
            Column(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(10.dp)).clickable { vm.openAlbum() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("⊞", style = MaterialTheme.typography.titleMedium, color = HeadsupColors.Highlight)
                Text("Album", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted)
            }
        }
    }
    // The selected photo's stars. Marking, moving, exporting and deleting are in its menu (a
    // double tap on it; held, a photo is marked), so they are not repeated here.
    val i = state.selectedClip
    if (arrange && i != null && i in state.clips.indices) {
        StarRow(state.clips[i].rating, state.clips[i].suggested) { stars -> vm.setRating(i, stars) }
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
    // The lens: its distortion and its darker corners (a raw photo's lens profile is applied already).
    SectionCaption("Lens")
    ValueHeader("Distortion", String.format(Locale.US, "%+.0f", r.distortion * 100))
    RulerSlider(r.distortion, -1f..1f, 0.01f, { v -> vm.updateReframe { it.copy(distortion = v) } }, onReset = { vm.updateReframe { it.copy(distortion = 0f) } })
    ValueHeader("Lens vignette", String.format(Locale.US, "%+.0f", r.lensVignette * 100))
    RulerSlider(r.lensVignette, -1f..1f, 0.01f, { v -> vm.updateReframe { it.copy(lensVignette = v) } }, onReset = { vm.updateReframe { it.copy(lensVignette = 0f) } })
    Hint("Distortion: + pulls in a wide lens's bulge (straight lines bowing out, a drone's horizon), − pushes out a pinch. Lens vignette: + brightens the corners a lens darkens. Raw photos already get their lens's own correction.")
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
private fun PreviewHandle(
    onToggle: () -> Unit,
    onDragging: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
) {
    val state = androidx.compose.foundation.gestures.rememberDraggableState(onDrag)
    val toggle by rememberUpdatedState(onToggle)
    // A tall touch area around the small bar, so it is easy to catch: dragged it sizes the picture,
    // double-tapped it folds what is under the picture away and back.
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .draggable(
                state, Orientation.Vertical,
                onDragStarted = { onDragging(true) },
                onDragStopped = { onDragging(false) },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggle() }) },
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

