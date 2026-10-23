package app.headsup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.headsup.HeadsupApp
import android.graphics.Bitmap
import app.headsup.color.GradeStore
import app.headsup.color.ScopeResult
import app.headsup.color.Vectorscope
import app.headsup.core.color.SkinTone
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKind
import app.headsup.core.frame.Reframe
import app.headsup.core.highlight.Highlight
import app.headsup.core.highlight.HighlightFinder
import app.headsup.core.track.TimeRange
import app.headsup.core.color.CineAssistant
import app.headsup.core.color.CineStyle
import app.headsup.core.color.FrameStats
import app.headsup.core.color.MatchStats
import app.headsup.core.color.ShotMatch
import app.headsup.core.color.CubeLut
import app.headsup.core.color.LutBaker
import app.headsup.core.fit.FitActivityReader
import app.headsup.core.sync.SyncResolver
import app.headsup.core.sync.SyncResult
import app.headsup.core.track.ActivityTrack
import app.headsup.export.ExportKind
import app.headsup.export.ExportRequest
import app.headsup.export.GradeSetup
import app.headsup.media.ClipMetadata
import app.headsup.media.ClipMetadataReader
import app.headsup.render.DashboardStyle
import app.headsup.render.EffectPrefs
import app.headsup.render.EffectSettings
import app.headsup.render.Placement
import app.headsup.render.StylePrefs
import app.headsup.render.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.headsup.core.sync.ClipInfo
import app.headsup.core.sync.ClockReading
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeEvidence
import app.headsup.core.sync.TimeSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.content.Intent
import app.headsup.project.ProjectData
import app.headsup.project.ProjectInfo
import app.headsup.project.ProjectStore
import app.headsup.project.SavedClip
import app.headsup.project.SavedGroup
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId

data class LoadedActivity(val name: String, val track: ActivityTrack, val uri: Uri? = null)

data class ClipState(
    val meta: ClipMetadata,
    val sync: SyncResult?,
    /** Identifies the clip on the timeline; one file can yield several clips after splitting. */
    val id: Long = nextClipId(),
    /** Part of the file used, in file time. The data follows the cut automatically. */
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = meta.durationMs,
    /** Manual correction on top of the automatic match; positive shows later data. */
    val manualOffsetMs: Long = 0L,
    /**
     * Whether the LUT and corrections apply to this clip. On by default for log/SDR footage,
     * off for HDR files (e.g. Mimo exports), which already have their final colours.
     */
    val colorGrade: Boolean = meta.format?.isHdr != true,
    /** LUT from the library used for this clip, or null for none. */
    val lutName: String? = null,
    /** Start time typed in by the user (wall clock), replacing what the file says. */
    val manualStart: LocalDateTime? = null,
    /** Colour corrections of this clip (applied before the LUT). */
    val grade: ColorGrade = ColorGrade(),
    /** Picture effects of this clip. */
    val effects: EffectSettings = EffectSettings(),
    /** Windows with their own corrections (graduated lines, ovals), at most [ColourMask.MAX_PER_CLIP]. */
    val masks: List<ColourMask> = emptyList(),
    /** Length of the blend from the previous clip's look at the start of this one; 0 = a plain cut. */
    val transitionMs: Long = 0L,
    /** Zoom, position and straightening of the picture (not part of the copied look: it is per shot). */
    val reframe: Reframe = Reframe(),
    /** Group whose corrections (and LUT, if the clip has none) apply too; at most one. */
    val groupId: Long? = null,
) {
    val look: ClipLook get() = ClipLook(grade, effects, lutName, masks)

    /** Time evidence used for matching: the manual start if set, otherwise the file's own. */
    val timeInfo: ClipInfo
        get() = manualStart?.let {
            ClipInfo(meta.durationMs, listOf(TimeEvidence(TimeSource.MANUAL, ClockReading.Local(it), TimeAnchor.START)))
        } ?: meta.info

    val durationMs: Long get() = trimEndMs - trimStartMs
    val isTrimmed: Boolean get() = trimStartMs > 0 || trimEndMs < meta.durationMs

    /** UTC time of the telemetry shown at [positionMs] into the (trimmed) clip. */
    fun telemetryUtc(positionMs: Long): Long? = sync?.let { it.startUtcMs + trimStartMs + positionMs + manualOffsetMs }
}

/** What "Copy" takes from a clip and "Paste" gives to another: corrections, effects and LUT. */
data class ClipLook(val grade: ColorGrade, val effects: EffectSettings, val lutName: String?, val masks: List<ColourMask> = emptyList())

/**
 * Clips shot the same way (e.g. "Drone"): shared corrections between the clip's and the global
 * ones, and a LUT for members without their own.
 */
data class ClipGroup(val id: Long, val name: String, val grade: ColorGrade = ColorGrade(), val lutName: String? = null)

/**
 * The cine assistant's state for one clip: what it measured, the clip's grade and effects before
 * the suggestion (so it can be scaled or undone), and the chosen style and strength.
 */
data class CineSession(
    val clipId: Long,
    val stats: FrameStats,
    val baseGrade: ColorGrade,
    val baseEffects: EffectSettings,
    val style: CineStyle? = null,
    val strength: Float = 1f,
)

/** The frame other clips are matched to: how it looks on screen, and where it is from. */
data class MatchReference(val clipId: Long, val clipName: String, val stats: MatchStats)

/** A highlight found in the watch data, placed in the clip that shows it. */
data class FoundHighlight(val highlight: Highlight, val clipId: Long, val clipName: String, val positionMs: Long)

/** What the colour page edits; the order is that of its tabs. */
enum class GradeTarget { CLIP, GROUP, MASK, GLOBAL }

private var lastClipId = 0L
private fun nextClipId() = ++lastClipId

/** Shortest piece a trim or split may leave. */
const val MIN_CLIP_MS = 500L

data class UiState(
    /** Name of the open project, saved automatically as it changes. */
    val projectName: String = "",
    /** Saved projects, refreshed when the project list opens. */
    val projects: List<ProjectInfo> = emptyList(),
    val activities: List<LoadedActivity> = emptyList(),
    val clips: List<ClipState> = emptyList(),
    val selectedClip: Int? = null,
    val style: DashboardStyle = DashboardStyle(),
    /** Effects for new clips: the last ones edited. */
    val defaultEffects: EffectSettings = EffectSettings(),
    val busy: Boolean = false,
    val messages: List<String> = emptyList(),
    /** Corrections for new clips: the last ones edited. */
    val defaultGrade: ColorGrade = ColorGrade(),
    /** Global node: corrections applied on top of every clip's own (e.g. one vignette for all). */
    val globalGrade: ColorGrade = ColorGrade(),
    /** Which corrections the colour page edits. */
    val target: GradeTarget = GradeTarget.CLIP,
    val groups: List<ClipGroup> = emptyList(),
    val luts: List<String> = emptyList(),
    /** Grade shown in the preview instead of [grade] while comparing (one correction held off). */
    val compareGrade: ColorGrade? = null,
    /** Colour table for the selected clip's preview; null when its colours stay untouched. */
    val previewCube: Array<Array<IntArray>>? = null,
    /** Colour tables inside the selected clip's masks, and the previous clip's for a transition. */
    val previewMaskCubes: List<Array<Array<IntArray>>> = emptyList(),
    val previewFromCube: Array<Array<IntArray>>? = null,
    /** Mask of the selected clip that the Masks tab edits. */
    val selectedMask: Int = 0,
    /** Cine assistant for the selected clip, while it is open. */
    val cine: CineSession? = null,
    /** Result of the last highlight search; null until searched. */
    val highlights: List<FoundHighlight>? = null,
    val matchReference: MatchReference? = null,
    /** Clip and its corrections before the last shot match, for undo. */
    val matchUndo: Pair<Long, ColorGrade>? = null,
    /** Whether the last match also turned skin (both frames showed skin). */
    val matchedSkin: Boolean = false,
    val matching: Boolean = false,
    val bitrateFactor: Float = 1f,
    /** Vectorscope and skin measurement of the last frame tapped in the preview. */
    val scope: ScopeResult? = null,
    /** Look copied with "Copy", waiting to be pasted. */
    val copiedLook: ClipLook? = null,
) {
    val selected: ClipState? get() = selectedClip?.let { clips.getOrNull(it) }

    /** Corrections the colour page edits: the selected clip's, its mask's, or the global node's. */
    val grade: ColorGrade
        get() = when (target) {
            GradeTarget.GLOBAL -> globalGrade
            GradeTarget.GROUP -> selected?.let(::groupOf)?.grade ?: ColorGrade()
            GradeTarget.MASK -> selected?.masks?.getOrNull(selectedMask)?.grade ?: ColorGrade()
            GradeTarget.CLIP -> selected?.grade ?: defaultGrade
        }

    fun groupOf(clip: ClipState): ClipGroup? = clip.groupId?.let { id -> groups.firstOrNull { it.id == id } }

    /** The clip's own LUT, or else its group's. */
    fun lutFor(clip: ClipState): String? = clip.lutName ?: groupOf(clip)?.lutName

    /** [edited] unless it is the grade being compared (held ruler), then [compareGrade]. */
    private fun shown(edited: ColorGrade, which: GradeTarget, clip: ClipState): ColorGrade {
        if (target != which || compareGrade == null) return edited
        val affected = when (which) {
            GradeTarget.GLOBAL -> true
            GradeTarget.GROUP -> clip.groupId != null && clip.groupId == selected?.groupId
            else -> clip === selected
        }
        return if (affected) compareGrade else edited
    }

    /** What is applied to [clip] outside its mask: its own corrections, its group's and the global node. */
    fun appliedGrade(clip: ClipState): ColorGrade =
        shown(clip.grade, GradeTarget.CLIP, clip) +
            shown(groupOf(clip)?.grade ?: ColorGrade(), GradeTarget.GROUP, clip) +
            shown(globalGrade, GradeTarget.GLOBAL, clip)

    /** What is applied inside each of [clip]'s masks. */
    fun maskedGrades(clip: ClipState): List<ColorGrade> {
        val outside = appliedGrade(clip)
        return clip.masks.mapIndexed { i, m ->
            outside + if (i == selectedMask) shown(m.grade, GradeTarget.MASK, clip) else m.grade
        }
    }

    /** The clip before [clip] on the timeline when [clip] blends in from it. */
    fun transitionSource(clip: ClipState): ClipState? =
        clip.takeIf { it.transitionMs > 0 }?.let { c -> clips.getOrNull(clips.indexOfFirst { it.id == c.id } - 1) }

    /** Effects of the selected clip (what the effects page edits). */
    val effects: EffectSettings get() = selected?.effects ?: defaultEffects
}

class HeadsupViewModel(app: Application) : AndroidViewModel(app) {
    private val stylePrefs = StylePrefs(app)
    private val gradeStore = GradeStore(app)
    private val effectPrefs = EffectPrefs(app)
    private val projectStore = ProjectStore(app)
    private val _state = MutableStateFlow(
        UiState(
            style = stylePrefs.load(),
            defaultEffects = effectPrefs.load(),
            defaultGrade = gradeStore.loadGrade(),
            globalGrade = gradeStore.loadGrade(GLOBAL),
            luts = gradeStore.names(),
            messages = listOfNotNull((app as HeadsupApp).takeCrashReport()),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val exportManager = (app as HeadsupApp).exportManager
    val exportJobs = exportManager.jobs

    private val lutCache = HashMap<String, CubeLut>()
    private var scopeFrame: IntArray? = null
    private var scopeWidth = 0
    private var scopeHeight = 0
    private var scopeMarker = 0.5f to 0.5f
    private var previewRunning = false
    private var previewDirty = false

    init {
        projectStore.lastProject?.takeIf(projectStore::exists)?.let(::openProject)
            ?: _state.update { it.copy(projectName = newProjectName()) }
        autosave()
    }

    // Projects

    /** Saves the project a moment after anything worth keeping changes. */
    @OptIn(FlowPreview::class)
    private fun autosave() {
        viewModelScope.launch {
            _state
                .map { s -> s.toProject().takeIf { !s.busy && (it.clips.isNotEmpty() || it.activities.isNotEmpty()) } }
                .distinctUntilChanged()
                .debounce(800)
                .collect { project ->
                    if (project == null) return@collect
                    withContext(Dispatchers.IO) { runCatching { projectStore.save(project) } }
                    projectStore.lastProject = project.name
                }
        }
    }

    private fun UiState.toProject() = ProjectData(
        name = projectName,
        activities = activities.mapNotNull { it.uri },
        clips = clips.map {
            SavedClip(
                it.meta.uri, it.trimStartMs, it.trimEndMs, it.manualOffsetMs, it.colorGrade, it.lutName,
                it.manualStart, it.grade, it.effects, it.masks, it.groupId, it.transitionMs, it.reframe,
            )
        },
        groups = groups.map { SavedGroup(it.id, it.name, it.grade, it.lutName) },
        globalGrade = globalGrade,
    )

    fun refreshProjects() = _state.update { it.copy(projects = projectStore.list()) }

    /** Opens a saved project; its files are read again, missing ones are reported. */
    fun openProject(name: String) {
        val project = projectStore.load(name) ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val missing = mutableListOf<String>()
            val activities = withContext(Dispatchers.IO) {
                project.activities.mapNotNull { uri ->
                    runCatching {
                        val track = context.contentResolver.openInputStream(uri)!!.use { FitActivityReader.read(it) }
                        LoadedActivity(ClipMetadataReader.displayName(context, uri) ?: uri.lastPathSegment.orEmpty(), track, uri)
                    }.onFailure { missing += uri.lastPathSegment.orEmpty() }.getOrNull()
                }
            }
            val metas = withContext(Dispatchers.IO) {
                project.clips.map { it.uri }.distinct().associateWith { uri ->
                    runCatching { ClipMetadataReader.read(context, uri) }.onFailure { missing += uri.lastPathSegment.orEmpty() }.getOrNull()
                }
            }
            val clips = project.clips.mapNotNull { c ->
                val meta = metas[c.uri] ?: return@mapNotNull null
                val start = c.trimStartMs.coerceIn(0L, (meta.durationMs - MIN_CLIP_MS).coerceAtLeast(0L))
                ClipState(
                    meta, sync = null,
                    trimStartMs = start,
                    trimEndMs = c.trimEndMs.coerceIn(start, meta.durationMs),
                    manualOffsetMs = c.manualOffsetMs,
                    colorGrade = c.colorGrade,
                    lutName = c.lutName,
                    manualStart = c.manualStart,
                    grade = c.grade,
                    effects = c.effects,
                    masks = c.masks,
                    transitionMs = c.transitionMs,
                    reframe = c.reframe,
                    groupId = c.groupId,
                )
            }
            scopeFrame = null
            _state.update { s ->
                resynced(
                    s.copy(
                        projectName = project.name,
                        activities = activities.sortedBy { it.track.startMs },
                        clips = clips,
                        groups = project.groups.map { ClipGroup(it.id, it.name, it.grade, it.lutName) },
                        globalGrade = project.globalGrade,
                        selectedClip = clips.indices.firstOrNull(),
                        highlights = null,
                        target = GradeTarget.CLIP,
                        compareGrade = null,
                        scope = null,
                        copiedLook = null,
                        busy = false,
                        messages = s.messages + missing.distinct().map { "Missing file: $it" },
                    ),
                )
            }
            projectStore.lastProject = project.name
            refreshPreview()
        }
    }

    /** Starts an empty project; the current one stays saved. */
    fun newProject() {
        scopeFrame = null
        _state.update {
            it.copy(
                projectName = newProjectName(),
                activities = emptyList(),
                clips = emptyList(),
                groups = emptyList(),
                globalGrade = ColorGrade(),
                selectedClip = null,
                highlights = null,
                target = GradeTarget.CLIP,
                compareGrade = null,
                scope = null,
            )
        }
        refreshPreview()
    }

    /** Renames project [old] (the open one by default) to [name]. */
    fun renameProject(name: String, old: String = _state.value.projectName) {
        val newName = name.trim().takeIf { it.isNotEmpty() } ?: return
        if (newName == old) return
        if (!projectStore.rename(old, newName)) {
            _state.update { it.copy(messages = it.messages + "A project called \"$newName\" already exists.") }
            return
        }
        _state.update { s ->
            s.copy(projectName = if (s.projectName == old) newName else s.projectName, projects = projectStore.list())
        }
    }

    fun deleteProject(name: String) {
        projectStore.delete(name)
        if (name == _state.value.projectName) newProject()
        refreshProjects()
    }

    private fun newProjectName(): String =
        uniqueProjectName("Project " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")))

    private fun uniqueProjectName(base: String): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { !projectStore.exists(it) }

    /** Keeps access to a picked file after restarts, so projects can open it again. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun addActivities(uris: List<Uri>) = load(uris) { uri ->
        keepAccess(uri)
        val context = getApplication<Application>()
        val name = ClipMetadataReader.displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val track = context.contentResolver.openInputStream(uri)?.use { FitActivityReader.read(it) }
            ?: error("Cannot open $name")
        Loaded.Activity(LoadedActivity(name, track, uri))
    }

    fun addClips(uris: List<Uri>) = load(uris) { uri ->
        keepAccess(uri)
        Loaded.Clip(ClipMetadataReader.read(getApplication<Application>(), uri))
    }

    fun selectClip(index: Int) {
        scopeFrame = null
        _state.update { it.copy(selectedClip = index, scope = null, selectedMask = 0, compareGrade = null, cine = null) }
        refreshPreview()
    }

    /** Sets the used part of a clip, in file time. */
    fun setTrim(index: Int, startMs: Long, endMs: Long) = updateClip(index) { c ->
        val start = startMs.coerceIn(0L, c.meta.durationMs - MIN_CLIP_MS)
        c.copy(trimStartMs = start, trimEndMs = endMs.coerceIn(start + MIN_CLIP_MS, c.meta.durationMs))
    }

    /** Cuts a clip in two at [positionMs] (within the clip); both halves keep all settings. */
    fun splitClip(index: Int, positionMs: Long) {
        _state.update { s ->
            val clip = s.clips.getOrNull(index) ?: return@update s
            val cut = clip.trimStartMs + positionMs
            if (cut - clip.trimStartMs < MIN_CLIP_MS || clip.trimEndMs - cut < MIN_CLIP_MS) return@update s
            val first = clip.copy(trimEndMs = cut)
            val second = clip.copy(id = nextClipId(), trimStartMs = cut)
            s.copy(clips = s.clips.toMutableList().apply { set(index, first); add(index + 1, second) })
        }
    }

    /** Moves a clip one place left (−1) or right (+1) on the timeline. */
    fun moveClip(index: Int, delta: Int) = _state.update { s ->
        val target = index + delta
        if (target !in s.clips.indices) return@update s
        val clips = s.clips.toMutableList().apply { add(target, removeAt(index)) }
        s.copy(clips = clips, selectedClip = if (s.selectedClip == index) target else s.selectedClip)
    }

    fun removeClip(index: Int) {
        _state.update { s ->
            val clips = s.clips.filterIndexed { i, _ -> i != index }
            s.copy(clips = clips, selectedClip = s.selectedClip?.let { sel -> if (sel == index) null else if (sel > index) sel - 1 else sel })
        }
        refreshPreview()
    }

    fun removeActivity(index: Int) = _state.update { s ->
        resynced(s.copy(activities = s.activities.filterIndexed { i, _ -> i != index }))
    }

    /** Sets (or with null clears) the wall-clock start of a clip and matches it again. */
    fun setManualStart(index: Int, start: LocalDateTime?) = _state.update { s ->
        resynced(s.copy(clips = s.clips.mapIndexed { i, c -> if (i == index) c.copy(manualStart = start, manualOffsetMs = 0L) else c }))
    }

    fun setManualOffset(index: Int, offsetMs: Long) = updateClip(index) {
        it.copy(manualOffsetMs = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS))
    }

    fun updateStyle(transform: (DashboardStyle) -> DashboardStyle) {
        _state.update { it.copy(style = transform(it.style)) }
        stylePrefs.save(_state.value.style)
    }

    /** Called continuously while a widget is dragged; saved by [persistStyle] when the drag ends. */
    fun moveWidget(widget: Widget, placement: Placement) = _state.update {
        it.copy(style = it.style.copy(placements = it.style.placements + (widget to placement)))
    }

    fun persistStyle() = stylePrefs.save(_state.value.style)

    /** Changes the selected clip's effects; they also become the default for clips added later. */
    fun updateEffects(transform: (EffectSettings) -> EffectSettings) {
        _state.update { s ->
            val effects = transform(s.effects)
            s.copy(defaultEffects = effects, clips = s.clips.mapIndexed { i, c -> if (i == s.selectedClip) c.copy(effects = effects) else c })
        }
        effectPrefs.save(_state.value.defaultEffects)
    }

    // Look: corrections, effects and LUT of one clip, copied to others

    fun copyLook() = _state.update { s -> s.copy(copiedLook = s.selected?.look) }

    fun pasteLook(index: Int) {
        val look = _state.value.copiedLook ?: return
        updateClip(index) { it.withLook(look) }
    }

    fun applyLookToAll() {
        _state.update { s ->
            val look = s.selected?.look ?: return@update s
            s.copy(clips = s.clips.map { it.withLook(look) })
        }
        refreshPreview()
    }

    private fun ClipState.withLook(look: ClipLook) = copy(grade = look.grade, effects = look.effects, lutName = look.lutName, masks = look.masks)

    fun dismissMessages() = _state.update { it.copy(messages = emptyList()) }

    // Colour

    /** Adds a `.cube` file to the library and uses it for the selected clip. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val displayName = ClipMetadataReader.displayName(context, uri) ?: "LUT"
            runCatching { withContext(Dispatchers.IO) { gradeStore.import(uri, displayName) } }
                .onSuccess { name ->
                    lutCache.remove(name)
                    _state.update { it.copy(luts = gradeStore.names()) }
                    _state.value.selectedClip?.let { setClipLut(it, name) }
                }
                .onFailure { e -> _state.update { it.copy(messages = it.messages + "Could not load LUT: ${e.message}") } }
        }
    }

    fun deleteLut(name: String) {
        gradeStore.delete(name)
        lutCache.remove(name)
        _state.update { s ->
            s.copy(
                luts = gradeStore.names(),
                clips = s.clips.map { if (it.lutName == name) it.copy(lutName = null) else it },
                groups = s.groups.map { if (it.lutName == name) it.copy(lutName = null) else it },
            )
        }
        refreshPreview()
    }

    fun setClipLut(index: Int, name: String?) {
        if (name != null) gradeStore.defaultLut = name
        updateClip(index) { it.copy(lutName = name) }
    }

    fun setLutForAllClips(name: String?) {
        if (name != null) gradeStore.defaultLut = name
        _state.update { s -> s.copy(clips = s.clips.map { it.copy(lutName = name) }) }
        refreshPreview()
    }

    /** A small grab of the current (ungraded) preview frame and the tapped point, for the vectorscope. */
    fun sampleFrame(frame: Bitmap, x: Float, y: Float) {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        scopeFrame = pixels
        scopeWidth = frame.width
        scopeHeight = frame.height
        scopeMarker = x to y
        frame.recycle()
        viewModelScope.launch { analyseScope() }
    }

    /** Sets the skin correction so the measured colour lands on the skin line. */
    fun matchSkinLine() {
        val measured = _state.value.scope?.measured ?: return
        val delta = SkinTone.angleDifference(SkinTone.LINE_DEGREES, measured.angleDegrees).toFloat()
        updateGrade { it.copy(skinHue = (it.skinHue + delta).coerceIn(-30f, 30f)) }
    }

    private suspend fun analyseScope() {
        val frame = scopeFrame ?: return
        val cube = _state.value.previewCube
        val (x, y) = scopeMarker
        val result = withContext(Dispatchers.Default) { Vectorscope.analyse(frame, scopeWidth, scopeHeight, cube, x, y) }
        if (frame === scopeFrame) _state.update { it.copy(scope = result) }
    }

    /** Shows the preview with [compare] instead of the real grade until called with null. */
    fun setCompareGrade(compare: ColorGrade?) {
        _state.update { it.copy(compareGrade = compare) }
        refreshPreview()
    }

    fun setClipGrade(index: Int, enabled: Boolean) = updateClip(index) { it.copy(colorGrade = enabled) }

    fun setTarget(target: GradeTarget) {
        _state.update { it.copy(target = target, compareGrade = null) }
        refreshPreview()
    }

    // Groups

    /** Makes a new group (named "Group N") with the clip at [index] in it; the group takes over its LUT. */
    fun createGroup(index: Int) {
        _state.update { s ->
            val clip = s.clips.getOrNull(index) ?: return@update s
            val id = (s.groups.maxOfOrNull { it.id } ?: 0L) + 1
            val number = generateSequence(s.groups.size + 1) { it + 1 }.first { n -> s.groups.none { it.name == "Group $n" } }
            s.copy(
                groups = s.groups + ClipGroup(id, "Group $number", lutName = clip.lutName),
                clips = s.clips.mapIndexed { i, c -> if (i == index) c.copy(groupId = id, lutName = null) else c },
            )
        }
        refreshPreview()
    }

    /**
     * Puts the clip at [index] in group [groupId] (with null takes it out). A clip joining a group
     * that has a LUT uses the group's instead of its own.
     */
    fun setClipGroup(index: Int, groupId: Long?) {
        val groupLut = _state.value.groups.firstOrNull { it.id == groupId }?.lutName
        updateClip(index) { it.copy(groupId = groupId, lutName = if (groupLut != null) null else it.lutName) }
    }

    fun renameGroup(id: Long, name: String) = updateGroup(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun setGroupLut(id: Long, lutName: String?) {
        if (lutName != null) gradeStore.defaultLut = lutName
        updateGroup(id) { it.copy(lutName = lutName) }
    }

    /** Removes the group; its clips keep their own settings. */
    fun deleteGroup(id: Long) {
        _state.update { s ->
            s.copy(groups = s.groups.filter { it.id != id }, clips = s.clips.map { if (it.groupId == id) it.copy(groupId = null) else it })
        }
        refreshPreview()
    }

    private fun updateGroup(id: Long, transform: (ClipGroup) -> ClipGroup) {
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == id) transform(it) else it }) }
        refreshPreview()
    }

    // Shot match

    /** Remembers how [frame] of the selected clip looks now (through its colour) as the match reference. */
    fun setMatchReference(frame: Bitmap) {
        val s = _state.value
        val clip = s.selected ?: return frame.recycle()
        val pixels = pixelsOf(frame)
        val cube = s.previewCube
        viewModelScope.launch {
            val stats = withContext(Dispatchers.Default) {
                val rgb = FloatArray(pixels.size * 3)
                val c = FloatArray(3)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    c[0] = ((p shr 16) and 0xFF) / 255f
                    c[1] = ((p shr 8) and 0xFF) / 255f
                    c[2] = (p and 0xFF) / 255f
                    if (cube != null) LutBaker.sampleBaked(cube, c[0], c[1], c[2], c)
                    rgb[3 * i] = c[0]
                    rgb[3 * i + 1] = c[1]
                    rgb[3 * i + 2] = c[2]
                }
                MatchStats.of(rgb)
            }
            _state.update { it.copy(matchReference = MatchReference(clip.id, clip.meta.name, stats)) }
        }
    }

    /**
     * Changes the selected clip's exposure, contrast, white balance and saturation so [frame]
     * looks like the reference; solved through the clip's whole colour path (its corrections,
     * group, global node and LUT), the difference lands in the clip's own corrections.
     */
    fun matchToReference(frame: Bitmap) {
        val s = _state.value
        val clip = s.selected ?: return frame.recycle()
        val reference = s.matchReference ?: return frame.recycle()
        val pixels = pixelsOf(frame)
        val applied = s.appliedGrade(clip)
        _state.update { it.copy(matching = true) }
        viewModelScope.launch {
            val lut = loadLut(s.lutFor(clip))
            val matched = withContext(Dispatchers.Default) { ShotMatch.match(pixels, lut, applied, reference.stats) }
            val index = _state.value.clips.indexOfFirst { it.id == clip.id }
            _state.update {
                it.copy(matching = false, matchUndo = clip.id to clip.grade, matchedSkin = matched.skinHue != applied.skinHue)
            }
            if (index < 0) return@launch
            updateClip(index) { c ->
                c.copy(
                    grade = c.grade.copy(
                        exposure = c.grade.exposure + matched.exposure - applied.exposure,
                        contrast = (c.grade.contrast + matched.contrast - applied.contrast).coerceIn(-1f, 1f),
                        temperature = (c.grade.temperature + matched.temperature - applied.temperature).coerceIn(-1f, 1f),
                        tint = (c.grade.tint + matched.tint - applied.tint).coerceIn(-1f, 1f),
                        saturation = (c.grade.saturation + matched.saturation - applied.saturation).coerceIn(-1f, 1f),
                        skinHue = (c.grade.skinHue + matched.skinHue - applied.skinHue).coerceIn(-30f, 30f),
                    ),
                )
            }
        }
    }

    fun undoMatch() {
        val (clipId, grade) = _state.value.matchUndo ?: return
        val index = _state.value.clips.indexOfFirst { it.id == clipId }
        if (index >= 0) updateClip(index) { it.copy(grade = grade) }
        _state.update { it.copy(matchUndo = null) }
    }

    private fun pixelsOf(frame: Bitmap): IntArray {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        frame.recycle()
        return pixels
    }

    // Highlights

    /** Looks for the best moments in the watch data, only where there is video. */
    fun findHighlights() {
        val s = _state.value
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                s.activities.indices.flatMap { trackIndex ->
                    val clips = s.clips.filter { it.sync?.trackIndex == trackIndex }
                    val covered = clips.mapNotNull { c ->
                        val start = c.telemetryUtc(0) ?: return@mapNotNull null
                        TimeRange(start, start + c.durationMs)
                    }
                    HighlightFinder.find(s.activities[trackIndex].track, covered).mapNotNull { h ->
                        // The first clip on the timeline that shows the moment.
                        clips.firstNotNullOfOrNull { c ->
                            val start = c.telemetryUtc(0) ?: return@firstNotNullOfOrNull null
                            val position = h.timeMs - start
                            if (position in 0..c.durationMs) FoundHighlight(h, c.id, c.meta.name, position) else null
                        }
                    }
                }.sortedByDescending { it.highlight.score }
            }
            _state.update { it.copy(busy = false, highlights = found) }
        }
    }

    /**
     * Starts a new project with one short clip of [lengthMs] around each of [chosen] (the moment a
     * little after the middle, so the build-up shows), with the source clips' colour and effects.
     * The current project stays saved.
     */
    fun makeHighlightReel(chosen: List<FoundHighlight>, lengthMs: Long) {
        val s = _state.value
        val reel = chosen.sortedBy { it.highlight.timeMs }.mapNotNull { f ->
            val clip = s.clips.firstOrNull { it.id == f.clipId } ?: return@mapNotNull null
            val moment = clip.trimStartMs + f.positionMs
            val start = (moment - lengthMs * 6 / 10).coerceIn(clip.trimStartMs, (clip.trimEndMs - MIN_CLIP_MS).coerceAtLeast(clip.trimStartMs))
            val end = (start + lengthMs).coerceAtMost(clip.trimEndMs)
            clip.copy(id = nextClipId(), trimStartMs = start, trimEndMs = end, transitionMs = 0L)
        }
        if (reel.isEmpty()) return
        scopeFrame = null
        _state.update {
            it.copy(
                projectName = uniqueProjectName("${it.projectName} – highlights"),
                clips = reel,
                selectedClip = 0,
                highlights = null,
                cine = null,
                compareGrade = null,
                scope = null,
            )
        }
        refreshPreview()
    }

    // Cine assistant

    /** Measures [frame] (the paused preview, before colour) as graded now, and starts a suggestion session. */
    fun analyseForCine(frame: Bitmap) {
        val s = _state.value
        val clip = s.selected ?: return frame.recycle()
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        frame.recycle()
        val cube = s.previewCube
        viewModelScope.launch {
            val stats = withContext(Dispatchers.Default) {
                if (cube != null) {
                    val rgb = FloatArray(3)
                    for (i in pixels.indices) {
                        val p = pixels[i]
                        LutBaker.sampleBaked(cube, ((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, rgb)
                        pixels[i] = (0xFF shl 24) or ((rgb[0] * 255).toInt() shl 16) or ((rgb[1] * 255).toInt() shl 8) or (rgb[2] * 255).toInt()
                    }
                }
                CineAssistant.analyse(pixels)
            }
            _state.update { it.copy(cine = CineSession(clip.id, stats, clip.grade, clip.effects)) }
        }
    }

    /** Applies [style] at [strength] on top of the grade the clip had when the frame was measured. */
    fun applyCine(style: CineStyle, strength: Float) {
        val s = _state.value
        val session = s.cine ?: return
        val index = s.clips.indexOfFirst { it.id == session.clipId }.takeIf { it >= 0 } ?: return
        val suggestion = CineAssistant.suggest(session.stats, style)
        val k = strength.coerceIn(0f, 1f)
        _state.update { it.copy(cine = session.copy(style = style, strength = k)) }
        updateClip(index) {
            it.copy(
                grade = session.baseGrade + suggestion.grade * k,
                effects = session.baseEffects.copy(
                    grain = maxOf(session.baseEffects.grain, suggestion.grain * k),
                    halation = maxOf(session.baseEffects.halation, suggestion.halation * k),
                ),
            )
        }
    }

    /** Keeps the suggestion as ordinary corrections ([undo] = false) or restores the clip as it was. */
    fun closeCine(undo: Boolean) {
        val session = _state.value.cine ?: return
        if (undo) {
            val index = _state.value.clips.indexOfFirst { it.id == session.clipId }
            if (index >= 0) updateClip(index) { it.copy(grade = session.baseGrade, effects = session.baseEffects) }
        }
        _state.update { it.copy(cine = null) }
    }

    /** Changes the selected clip's framing; kept inside the picture. */
    fun updateReframe(transform: (Reframe) -> Reframe) {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { c ->
            val w = c.meta.width.toFloat().coerceAtLeast(1f)
            val h = c.meta.height.toFloat().coerceAtLeast(1f)
            c.copy(reframe = transform(c.reframe).let { it.copy(zoom = it.zoom.coerceIn(1f, 4f)) }.clamped(w, h))
        }
    }

    // Masks and transitions

    fun addMask(kind: MaskKind) {
        val index = _state.value.selectedClip ?: return
        val count = _state.value.selected?.masks?.size ?: return
        if (count >= ColourMask.MAX_PER_CLIP) return
        updateClip(index) { it.copy(masks = it.masks + ColourMask(kind)) }
        _state.update { it.copy(selectedMask = count) }
    }

    fun removeMask(maskIndex: Int) {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { it.copy(masks = it.masks.filterIndexed { i, _ -> i != maskIndex }) }
        _state.update { it.copy(selectedMask = (it.selectedMask - if (it.selectedMask >= maskIndex) 1 else 0).coerceAtLeast(0)) }
    }

    fun selectMask(maskIndex: Int) {
        _state.update { it.copy(selectedMask = maskIndex, compareGrade = null) }
        refreshPreview()
    }

    /** Reshapes the selected mask of the selected clip. */
    fun updateMask(transform: (ColourMask) -> ColourMask) {
        val s = _state.value
        val index = s.selectedClip ?: return
        val m = s.selectedMask
        updateClip(index) { c -> c.copy(masks = c.masks.mapIndexed { i, mask -> if (i == m) transform(mask) else mask }) }
    }

    /** Blend from the previous clip's look over [lengthMs] at the start of the clip at [index]; 0 for a cut. */
    fun setTransition(index: Int, lengthMs: Long) = updateClip(index) { it.copy(transitionMs = lengthMs.coerceAtLeast(0L)) }

    /**
     * Changes the selected clip's corrections (they also become the default for clips added
     * later), or the global node's.
     */
    fun updateGrade(transform: (ColorGrade) -> ColorGrade) {
        when (_state.value.target) {
            GradeTarget.GLOBAL -> {
                _state.update { it.copy(globalGrade = transform(it.globalGrade)) }
                gradeStore.saveGrade(_state.value.globalGrade, GLOBAL)
                refreshPreview()
                return
            }
            GradeTarget.MASK -> {
                updateMask { it.copy(grade = transform(it.grade)) }
                return
            }
            GradeTarget.GROUP -> {
                val id = _state.value.selected?.groupId ?: return
                updateGroup(id) { it.copy(grade = transform(it.grade)) }
                return
            }
            GradeTarget.CLIP -> Unit
        }
        _state.update { s ->
            val grade = transform(s.grade)
            s.copy(defaultGrade = grade, clips = s.clips.mapIndexed { i, c -> if (i == s.selectedClip) c.copy(grade = grade) else c })
        }
        gradeStore.saveGrade(_state.value.defaultGrade)
        refreshPreview()
    }

    private fun updateClip(index: Int, transform: (ClipState) -> ClipState) {
        _state.update { s -> s.copy(clips = s.clips.mapIndexed { i, c -> if (i == index) transform(c) else c }) }
        if (index == _state.value.selectedClip) refreshPreview()
    }

    /**
     * Recomputes the selected clip's colour table. While a slider moves, tables are computed back
     * to back, each from the latest values, so the picture follows the finger instead of waiting
     * for it to stop (cancelling on every event would starve the computation).
     */
    private fun refreshPreview() {
        previewDirty = true
        if (previewRunning) return
        previewRunning = true
        viewModelScope.launch {
            try {
                while (previewDirty) {
                    previewDirty = false
                    val s = _state.value
                    val clip = s.selected
                    val graded = clip?.takeIf { it.colorGrade }
                    val cube = graded?.let { bake(s.lutFor(it), s.appliedGrade(it)) }
                    val maskCubes = graded?.let { c -> s.maskedGrades(c).map { bakeAlways(s.lutFor(c), it) } }.orEmpty()
                    val fromCube = graded?.let(s::transitionSource)?.let { prev -> bakeLook(s, prev) }
                    _state.update { it.copy(previewCube = cube, previewMaskCubes = maskCubes, previewFromCube = fromCube) }
                    analyseScope()
                }
            } finally {
                previewRunning = false
            }
        }
    }

    /** Null when nothing would change the colours. */
    private suspend fun bake(lutName: String?, grade: ColorGrade): Array<Array<IntArray>>? {
        val lut = loadLut(lutName)
        if (lut == null && grade.isNeutralColour) return null
        return withContext(Dispatchers.Default) { LutBaker.bake(lut, grade) }
    }

    /** Table for [clip]'s whole look outside masks; untouched colours when its colour is off. */
    private suspend fun bakeLook(s: UiState, clip: ClipState): Array<Array<IntArray>> =
        if (clip.colorGrade) bakeAlways(s.lutFor(clip), s.appliedGrade(clip)) else bakeAlways(null, ColorGrade())

    private suspend fun bakeAlways(lutName: String?, grade: ColorGrade): Array<Array<IntArray>> {
        val lut = loadLut(lutName)
        return withContext(Dispatchers.Default) { LutBaker.bake(lut, grade) }
    }

    private suspend fun loadLut(lutName: String?): CubeLut? = lutName?.let { name ->
        lutCache[name] ?: withContext(Dispatchers.IO) { gradeStore.load(name) }?.also { lutCache[name] = it }
    }

    // Export

    fun setBitrateFactor(factor: Float) = _state.update { it.copy(bitrateFactor = factor) }

    fun exportVideos() = enqueue(ExportKind.VIDEO, state.value.clips.indices.toList())

    fun exportOverlays() = enqueue(ExportKind.PNG_SEQUENCE, state.value.clips.indices.toList())

    fun runTenBitTest() = enqueue(ExportKind.TEN_BIT_TEST, listOfNotNull(state.value.selectedClip))

    fun cancelExports() = exportManager.cancelAll()

    fun clearFinishedExports() = exportManager.clearFinished()

    private fun enqueue(kind: ExportKind, indices: List<Int>) {
        val s = state.value.copy(compareGrade = null)
        viewModelScope.launch {
            val cubes = HashMap<ClipLook, Array<Array<IntArray>>?>()
            val requests = indices.mapNotNull { i ->
                val clip = s.clips.getOrNull(i) ?: return@mapNotNull null
                val sync = clip.sync ?: return@mapNotNull null
                val activity = s.activities.getOrNull(sync.trackIndex) ?: return@mapNotNull null
                val graded = kind != ExportKind.PNG_SEQUENCE && clip.colorGrade
                val applied = s.appliedGrade(clip)
                val lutName = s.lutFor(clip)
                val cube = if (graded) cubes.getOrPut(ClipLook(applied, EffectSettings(), lutName)) { bake(lutName, applied) } else null
                val masks = if (graded) clip.masks else emptyList()
                val maskCubes = if (graded) s.maskedGrades(clip).map { bakeAlways(lutName, it) } else emptyList()
                val fromCube = if (graded) s.transitionSource(clip)?.let { bakeLook(s, it) } else null
                ExportRequest(
                    kind = kind,
                    uri = clip.meta.uri,
                    name = clip.meta.name,
                    durationMs = clip.durationMs,
                    trimStartMs = clip.trimStartMs,
                    trimEndMs = clip.trimEndMs,
                    width = clip.meta.width,
                    height = clip.meta.height,
                    frameRate = clip.meta.frameRate,
                    sourceBitrate = clip.meta.bitrate,
                    sourceFormat = clip.meta.format,
                    track = activity.track,
                    telemetryStartUtcMs = sync.startUtcMs + clip.trimStartMs + clip.manualOffsetMs,
                    style = s.style,
                    effects = clip.effects,
                    grade = if (graded) GradeSetup(cube, applied.vignette, masks, maskCubes, fromCube, clip.transitionMs) else null,
                    bitrateFactor = s.bitrateFactor,
                    reframe = clip.reframe,
                )
            }
            if (requests.isEmpty()) {
                _state.update { it.copy(messages = it.messages + "Nothing to export: no clip is matched to an activity.") }
            } else {
                exportManager.enqueue(requests)
            }
        }
    }

    // Loading

    private sealed interface Loaded {
        data class Activity(val activity: LoadedActivity) : Loaded
        data class Clip(val meta: ClipMetadata) : Loaded
    }

    private fun load(uris: List<Uri>, read: (Uri) -> Loaded) {
        if (uris.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> runCatching { read(uri) }.onFailure { it.printStackTrace() } }
            }
            val errors = results.mapNotNull { r -> r.exceptionOrNull()?.let { "Could not read file: ${it.message ?: it.javaClass.simpleName}" } }
            val loaded = results.mapNotNull { it.getOrNull() }
            val defaultLut = gradeStore.defaultLut
            _state.update { s ->
                val activities = (s.activities + loaded.filterIsInstance<Loaded.Activity>().map { it.activity })
                    .distinctBy { it.track.startMs to it.track.endMs }
                    .sortedBy { it.track.startMs }
                val newClips = loaded.filterIsInstance<Loaded.Clip>().map { ClipState(it.meta, sync = null, lutName = defaultLut, grade = s.defaultGrade, effects = s.defaultEffects) }
                // The same file may already be on the timeline as several (split) clips; only skip re-adds.
                val known = s.clips.map { it.meta.uri }.toSet()
                val clips = s.clips + newClips.filter { it.meta.uri !in known }.distinctBy { it.meta.uri }
                resynced(
                    s.copy(
                        activities = activities,
                        clips = clips,
                        selectedClip = s.selectedClip ?: clips.indices.firstOrNull(),
                        busy = false,
                        messages = s.messages + errors,
                    ),
                )
            }
            refreshPreview()
        }
    }

    /** Matches every clip again, e.g. after another FIT file arrived. Manual offsets are kept. */
    private fun resynced(s: UiState): UiState {
        val resolver = SyncResolver(ZoneId.systemDefault())
        val tracks = s.activities.map { it.track }
        return s.copy(clips = s.clips.map { it.copy(sync = resolver.resolve(it.timeInfo, tracks)) })
    }

    companion object {
        /** Large enough to fix a camera clock that is minutes off. */
        const val MAX_OFFSET_MS = 600_000L
        private const val GLOBAL = "global_"
    }
}
