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
import app.headsup.core.track.Metric
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
import app.headsup.export.Telemetry
import app.headsup.export.ExportSettings
import app.headsup.export.HdrRoute
import app.headsup.export.JobSpec
import app.headsup.export.OutputSpec
import app.headsup.export.VideoCodec
import app.headsup.export.MusicTrack
import app.headsup.media.ClipMetadata
import app.headsup.media.ClipMetadataReader
import app.headsup.media.MediaLibrary
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
    /** Playback speed: 0.5 = half speed (slow motion), 2 = double. */
    val speed: Float = 1f,
    /** Silence the sound while the speed is changed; otherwise it is stretched, keeping its pitch. */
    val muteChangedSpeed: Boolean = false,
    /** Fade from black at the start and to black at the end (picture and sound), timeline time. */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    val look: ClipLook get() = ClipLook(grade, effects, lutName, masks)

    /** Time evidence used for matching: the manual start if set, otherwise the file's own. */
    val timeInfo: ClipInfo
        get() = manualStart?.let {
            ClipInfo(meta.durationMs, listOf(TimeEvidence(TimeSource.MANUAL, ClockReading.Local(it), TimeAnchor.START)))
        } ?: meta.info

    val durationMs: Long get() = trimEndMs - trimStartMs

    /** Length on the timeline, after the speed change. */
    val timelineDurationMs: Long get() = (durationMs / speed).toLong()
    val isTrimmed: Boolean get() = trimStartMs > 0 || trimEndMs < meta.durationMs

    /** UTC time of the telemetry shown at [positionMs] into the (trimmed) clip. */
    fun telemetryUtc(positionMs: Long): Long? = sync?.let {
        // A photo is one moment: its dashboard shows the data from when it was taken.
        if (meta.isPhoto) it.startUtcMs + manualOffsetMs else it.startUtcMs + trimStartMs + positionMs + manualOffsetMs
    }
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

/**
 * The last shot match: what it was applied to (the clip, or its whole group), the corrections
 * before it, the change it found and how much of that is applied.
 */
data class MatchSession(val clipId: Long, val groupId: Long?, val base: ColorGrade, val delta: ColorGrade, val strength: Float)

/** A full match often overshoots between different scenes, so it starts at this share. */
const val MATCH_STRENGTH = 0.6f

/** A highlight found in the watch data, placed in the clip that shows it. */
data class FoundHighlight(val highlight: Highlight, val clipId: Long, val clipName: String, val positionMs: Long)

/**
 * One step of undo history: the edit itself (files, cuts, colour, groups, effects, framing,
 * dashboard), not what is selected or on screen. [project] keeps histories of projects apart.
 */
private data class EditSnapshot(
    val project: String,
    val activities: List<LoadedActivity>,
    val clips: List<ClipState>,
    val groups: List<ClipGroup>,
    val globalGrade: ColorGrade,
    val style: DashboardStyle,
    val timelineFps: Float?,
    val exportSettings: ExportSettings,
    val music: MusicTrack?,
)

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
    /** Corrections for new clips: the last ones edited in this project. */
    val defaultGrade: ColorGrade = ColorGrade(),
    /** LUT for new clips: the last one picked in this project. */
    val defaultLut: String? = null,
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
    val matchSession: MatchSession? = null,
    /** Whether the last match also turned skin (both frames showed skin). */
    val matchedSkin: Boolean = false,
    val matching: Boolean = false,
    val canUndo: Boolean = false,
    /** Effects shown instead of the clip's while a ruler is held to compare (cine strength). */
    val compareEffects: EffectSettings? = null,
    /** What is being loaded, shown with the progress bar. */
    val loadingMessage: String? = null,
    /** 0..1 while copying files in, null when the length is unknown or nothing is copied. */
    val loadingProgress: Float? = null,
    val canRedo: Boolean = false,
    val exportSettings: ExportSettings = ExportSettings(),
    /** Frame rate of the timeline (exports and slow-motion maths); null = the first clip's. */
    val timelineFps: Float? = null,
    /** Music under the whole timeline. */
    val music: MusicTrack? = null,
    /** Vectorscope and skin measurement of the last frame tapped in the preview. */
    val scope: ScopeResult? = null,
    /** Look copied with "Copy", waiting to be pasted. */
    val copiedLook: ClipLook? = null,
) {
    val selected: ClipState? get() = selectedClip?.let { clips.getOrNull(it) }

    /** The timeline's frame rate: chosen, or the first clip's, or 30. */
    val effectiveTimelineFps: Float get() = timelineFps ?: clips.firstOrNull()?.meta?.frameRate ?: 30f

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
    private val library = MediaLibrary(app)
    private var loadJob: kotlinx.coroutines.Job? = null
    private val _state = MutableStateFlow(
        UiState(
            style = stylePrefs.load(),

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
        recordHistory()
    }

    // Undo and redo

    private val history = ArrayList<EditSnapshot>()
    private var historyIndex = -1

    private fun UiState.snapshot() = EditSnapshot(projectName, activities, clips, groups, globalGrade, style, timelineFps, exportSettings, music)

    /**
     * Records a step a moment after the edit settles, so a slider drag or a widget move is one
     * step, not hundreds.
     */
    @OptIn(FlowPreview::class)
    private fun recordHistory() {
        viewModelScope.launch {
            _state
                .map { s -> s.snapshot().takeIf { !s.busy } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { it?.let(::record) }
        }
    }

    private fun record(snapshot: EditSnapshot) {
        val current = history.getOrNull(historyIndex)
        if (snapshot == current) return
        if (current == null || current.project != snapshot.project) {
            // Another project opened: its history starts here.
            history.clear()
            history += snapshot
            historyIndex = 0
        } else {
            while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
            history += snapshot
            if (history.size > MAX_HISTORY) history.removeAt(0)
            historyIndex = history.lastIndex
        }
        updateHistoryFlags()
    }

    fun undo() {
        // An edit made just now may not be recorded yet; keep it, so redo can bring it back.
        record(_state.value.snapshot())
        if (historyIndex <= 0) return
        historyIndex--
        restore(history[historyIndex])
    }

    fun redo() {
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        restore(history[historyIndex])
    }

    private fun restore(snapshot: EditSnapshot) {
        scopeFrame = null
        _state.update { s ->
            s.copy(
                activities = snapshot.activities,
                clips = snapshot.clips,
                groups = snapshot.groups,
                globalGrade = snapshot.globalGrade,
                style = snapshot.style,
                timelineFps = snapshot.timelineFps,
                exportSettings = snapshot.exportSettings,
                music = snapshot.music,
                selectedClip = s.selectedClip?.coerceAtMost(snapshot.clips.lastIndex)?.takeIf { it >= 0 } ?: snapshot.clips.indices.firstOrNull(),
                compareGrade = null,
                cine = null,
                matchSession = null,
                scope = null,
            )
        }
        stylePrefs.save(snapshot.style)
        updateHistoryFlags()
        refreshPreview()
    }

    private fun updateHistoryFlags() =
        _state.update { it.copy(canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex) }

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
                it.manualStart, it.grade, it.effects, it.masks, it.groupId, it.transitionMs, it.reframe, it.speed, it.muteChangedSpeed,
                it.fadeInMs, it.fadeOutMs,
            )
        },
        groups = groups.map { SavedGroup(it.id, it.name, it.grade, it.lutName) },
        globalGrade = globalGrade,
        timelineFps = timelineFps,
        music = music,
    )

    fun refreshProjects() = _state.update {
        it.copy(
            projects = projectStore.list().map { info ->
                val p = runCatching { projectStore.load(info.name) }.getOrNull()
                info.copy(bytes = p?.let { d -> library.sizeOf(d.mediaUris) } ?: 0L)
            },
        )
    }

    /** Opens a saved project; its files are read again, missing ones are reported. */
    fun openProject(name: String) {
        val project = projectStore.load(name) ?: return
        _state.update { it.copy(busy = true, loadingMessage = "Opening ${project.name}…") }
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
                    speed = c.speed,
                    muteChangedSpeed = c.muteChangedSpeed,
                    fadeInMs = c.fadeInMs,
                    fadeOutMs = c.fadeOutMs,
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
                        timelineFps = project.timelineFps,
                        music = project.music,
                        selectedClip = clips.indices.firstOrNull(),
                        highlights = null,
                        defaultGrade = ColorGrade(),
                        defaultEffects = EffectSettings(),
                        defaultLut = null,
                        copiedLook = null,
                        matchReference = null,
                        matchSession = null,
                        cine = null,
                        selectedMask = 0,
                        target = GradeTarget.CLIP,
                        compareGrade = null,
                        scope = null,
                        busy = false,
                        loadingMessage = null,
                        messages = s.messages + missing.distinct().map { "Missing file: $it" },
                    ),
                )
            }
            projectStore.lastProject = project.name
            measureMusicIfNeeded()
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
                timelineFps = null,
                music = null,
                selectedClip = null,
                highlights = null,
                defaultGrade = ColorGrade(),
                defaultEffects = EffectSettings(),
                defaultLut = null,
                copiedLook = null,
                matchReference = null,
                matchSession = null,
                cine = null,
                selectedMask = 0,
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

    /**
     * Deletes the project, and the app's copies of its media that no other project uses. Files in
     * the gallery are never touched.
     */
    fun deleteProject(name: String) {
        projectStore.delete(name)
        if (name == _state.value.projectName) newProject()
        if (!_state.value.busy) {
            val current = _state.value.toProject()
            val inUse = (projectStore.all() + current).flatMap { it.mediaUris }.toSet()
            runCatching { library.deleteUnused(inUse) }
        }
        refreshProjects()
    }

    private fun newProjectName(): String =
        uniqueProjectName("Project " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")))

    private fun uniqueProjectName(base: String): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { !projectStore.exists(it) }

    /** FIT files are copied into the app too, so the project keeps them whatever happens to the originals. */
    fun addActivities(uris: List<Uri>) = load(uris) { uri, progress ->
        val local = library.import(uri, progress)
        val context = getApplication<Application>()
        val name = local.lastPathSegment.orEmpty()
        val track = context.contentResolver.openInputStream(local)?.use { FitActivityReader.read(it) }
            ?: error("Cannot open $name")
        Loaded.Activity(LoadedActivity(name, track, local))
    }

    /**
     * Videos are copied into the app's own storage first (with progress), so moving or deleting
     * the original in the gallery or the cloud never breaks the project.
     */
    fun addClips(uris: List<Uri>) = load(uris) { uri, progress ->
        Loaded.Clip(ClipMetadataReader.read(getApplication<Application>(), library.import(uri, progress)))
    }

    /** Media of the open project that is still only referenced from the gallery or the cloud. */
    val UiState.externalMedia: List<Uri>
        get() = (clips.map { it.meta.uri } + activities.mapNotNull { it.uri } + listOfNotNull(music?.uri)).distinct().filterNot(library::isInLibrary)

    /**
     * Copies media the project still takes from the gallery or the cloud (projects made before
     * copies existed) into the app, and switches the project to the copies.
     */
    fun copyMediaIntoProject() {
        val pending = _state.value.externalMedia
        if (pending.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, loadingMessage = "Copying…", loadingProgress = null) }
        loadJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val copies = HashMap<Uri, Uri>()
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                pending.forEachIndexed { i, uri ->
                    val name = ClipMetadataReader.displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
                    runCatching {
                        copies[uri] = library.import(uri) { copied, total ->
                            _state.update {
                                it.copy(
                                    loadingMessage = "Copying $name (${i + 1}/${pending.size}) · ${copied / 1_000_000} MB",
                                    loadingProgress = if (total > 0) copied.toFloat() / total else null,
                                )
                            }
                        }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed += name }
                }
            }
            _state.update { s ->
                s.copy(
                    clips = s.clips.map { c -> copies[c.meta.uri]?.let { c.copy(meta = c.meta.copy(uri = it)) } ?: c },
                    activities = s.activities.map { a -> a.uri?.let(copies::get)?.let { a.copy(uri = it) } ?: a },
                    music = s.music?.let { m -> copies[m.uri]?.let { m.copy(uri = it) } ?: m },
                    busy = false,
                    loadingMessage = null,
                    loadingProgress = null,
                    messages = s.messages + failed.map { "Could not copy: $it" },
                )
            }
            refreshPreview()
        }
    }

    // Music

    /** Copies [uri] into the app and puts it under the whole timeline. */
    fun setMusic(uri: Uri) {
        if (_state.value.busy) return
        val context = getApplication<Application>()
        val name = ClipMetadataReader.displayName(context, uri) ?: "Music"
        _state.update { it.copy(busy = true, loadingMessage = "Copying $name", loadingProgress = null) }
        loadJob = viewModelScope.launch {
            val local = runCatching {
                withContext(Dispatchers.IO) {
                    library.import(uri) { copied, total ->
                        _state.update { it.copy(loadingMessage = "Copying $name · ${copied / 1_000_000} MB", loadingProgress = if (total > 0) copied.toFloat() / total else null) }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            val songMs = local?.let { durationOf(it) } ?: 0L
            _state.update { s ->
                s.copy(
                    music = local?.let { MusicTrack(it, name, s.music?.volume ?: 0.8f, s.music?.originalVolume ?: 0.4f, durationMs = songMs) } ?: s.music,
                    busy = false,
                    loadingMessage = null,
                    loadingProgress = null,
                    messages = if (local == null) s.messages + "Could not add $name" else s.messages,
                )
            }
        }
    }

    private suspend fun durationOf(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(getApplication<Application>(), uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }.getOrDefault(0L)
    }

    /** Projects saved before the song's length was stored get it measured once. */
    private fun measureMusicIfNeeded() {
        val music = _state.value.music ?: return
        if (music.durationMs > 0) return
        viewModelScope.launch {
            val d = durationOf(music.uri)
            if (d > 0) _state.update { s -> s.copy(music = s.music?.takeIf { it.uri == music.uri }?.copy(durationMs = d) ?: s.music) }
        }
    }

    fun updateMusic(transform: (MusicTrack) -> MusicTrack) = _state.update { s -> s.copy(music = s.music?.let(transform)) }

    fun removeMusic() = _state.update { it.copy(music = null) }

    /** Stops copying files in; what was not finished is removed. */
    fun cancelLoading() {
        loadJob?.cancel()
        _state.update { it.copy(busy = false, loadingMessage = null, loadingProgress = null) }
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
        if (name != null) _state.update { it.copy(defaultLut = name) }
        updateClip(index) { it.copy(lutName = name) }
    }

    fun setLutForAllClips(name: String?) {
        if (name != null) _state.update { it.copy(defaultLut = name) }
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
     * Matches the selected clip to the reference: solved through the clip's whole colour path
     * (its corrections, group, global node and LUT); the difference goes into the clip's own
     * corrections. With [wholeGroup], only the camera's character (white balance, tint,
     * saturation, skin) is kept and written to the clip's group, so every shot of that camera
     * follows and keeps its own brightness. The result starts at [MATCH_STRENGTH] and can be
     * scaled with [setMatchStrength].
     */
    fun matchToReference(frame: Bitmap, wholeGroup: Boolean = false) {
        val s = _state.value
        val clip = s.selected ?: return frame.recycle()
        val reference = s.matchReference ?: return frame.recycle()
        val group = s.groupOf(clip)?.takeIf { wholeGroup }
        val pixels = pixelsOf(frame)
        val applied = s.appliedGrade(clip)
        _state.update { it.copy(matching = true) }
        viewModelScope.launch {
            val lut = loadLut(s.lutFor(clip))
            val matched = withContext(Dispatchers.Default) { ShotMatch.match(pixels, lut, applied, reference.stats) }
            // Only the change, and for a group only the camera's character.
            val delta = ColorGrade(
                exposure = if (group == null) matched.exposure - applied.exposure else 0f,
                contrast = if (group == null) matched.contrast - applied.contrast else 0f,
                temperature = matched.temperature - applied.temperature,
                tint = matched.tint - applied.tint,
                saturation = matched.saturation - applied.saturation,
                skinHue = matched.skinHue - applied.skinHue,
            )
            val session = MatchSession(clip.id, group?.id, group?.grade ?: clip.grade, delta, MATCH_STRENGTH)
            _state.update { it.copy(matching = false, matchSession = session, matchedSkin = delta.skinHue != 0f) }
            applyMatch(session)
        }
    }

    /** Scales the last match: 0 = as before, 1 = full match. */
    fun setMatchStrength(strength: Float) {
        val session = _state.value.matchSession?.copy(strength = strength.coerceIn(0f, 1f)) ?: return
        _state.update { it.copy(matchSession = session) }
        applyMatch(session)
    }

    private fun applyMatch(m: MatchSession) {
        val k = m.strength
        val b = m.base
        val d = m.delta
        val grade = b.copy(
            exposure = (b.exposure + d.exposure * k).coerceIn(-3f, 3f),
            contrast = (b.contrast + d.contrast * k).coerceIn(-1f, 1f),
            temperature = (b.temperature + d.temperature * k).coerceIn(-1f, 1f),
            tint = (b.tint + d.tint * k).coerceIn(-1f, 1f),
            saturation = (b.saturation + d.saturation * k).coerceIn(-1f, 1f),
            skinHue = (b.skinHue + d.skinHue * k).coerceIn(-30f, 30f),
        )
        if (m.groupId != null) {
            updateGroup(m.groupId) { it.copy(grade = grade) }
        } else {
            val index = _state.value.clips.indexOfFirst { it.id == m.clipId }
            if (index >= 0) updateClip(index) { it.copy(grade = grade) }
        }
    }

    fun undoMatch() {
        val m = _state.value.matchSession ?: return
        applyMatch(m.copy(strength = 0f))
        _state.update { it.copy(matchSession = null) }
    }

    private fun pixelsOf(frame: Bitmap): IntArray {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        frame.recycle()
        return pixels
    }

    // Highlights

    /** Looks for the best moments in the watch data, only where there is video. */
    fun findHighlights(metric: Metric? = null) {
        val s = _state.value
        _state.update { it.copy(busy = true, loadingMessage = "Finding highlights…") }
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                s.activities.indices.flatMap { trackIndex ->
                    val clips = s.clips.filter { it.sync?.trackIndex == trackIndex && !it.meta.isPhoto }
                    val covered = clips.mapNotNull { c ->
                        val start = c.telemetryUtc(0) ?: return@mapNotNull null
                        TimeRange(start, start + c.durationMs)
                    }
                    HighlightFinder.find(s.activities[trackIndex].track, covered, metric).mapNotNull { h ->
                        // The first clip on the timeline that shows the moment.
                        clips.firstNotNullOfOrNull { c ->
                            val start = c.telemetryUtc(0) ?: return@firstNotNullOfOrNull null
                            val position = h.timeMs - start
                            if (position in 0..c.durationMs) FoundHighlight(h, c.id, c.meta.name, position) else null
                        }
                    }
                }.sortedByDescending { it.highlight.score }
            }
            _state.update { it.copy(busy = false, loadingMessage = null, highlights = found) }
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

    /** While the strength ruler is held: the picture as it was before the cine look. */
    fun compareCine(held: Boolean) {
        val session = _state.value.cine?.takeIf { held }
        _state.update { it.copy(compareGrade = session?.baseGrade, compareEffects = session?.baseEffects) }
        refreshPreview()
    }

    /** While the match strength ruler is held: the clip as it was before the match (clip matches only). */
    fun compareMatch(held: Boolean) {
        val session = _state.value.matchSession?.takeIf { held && it.groupId == null }
        _state.update { it.copy(compareGrade = session?.base) }
        refreshPreview()
    }

    /**
     * Quick settings for where the video goes: vertical for Reels/TikTok/Shorts (9:16 for every
     * clip, 1080×1920, 30 fps, H.264), or YouTube (source size and shape, best quality, HEVC).
     */
    fun applyPreset(vertical: Boolean) {
        if (vertical) {
            setAspectForAll(9f / 16f)
            _state.update {
                it.copy(
                    timelineFps = 30f,
                    exportSettings = ExportSettings(wholeTimeline = true, height = 1920, bitrateFactor = 1.5f, codec = VideoCodec.H264),
                )
            }
        } else {
            _state.update { it.copy(exportSettings = ExportSettings(wholeTimeline = true, height = null, bitrateFactor = 2f, codec = VideoCodec.HEVC)) }
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

    /** Gives every clip the same output shape (e.g. 9:16 for a reel), keeping each clip's own position. */
    fun setAspectForAll(aspect: Float?) {
        _state.update { s ->
            s.copy(clips = s.clips.map { c -> c.copy(reframe = c.reframe.copy(aspect = aspect).clampedFor(c)) })
        }
        refreshPreview()
    }

    private fun Reframe.clampedFor(c: ClipState): Reframe {
        val w = c.meta.width.toFloat().coerceAtLeast(1f)
        val h = c.meta.height.toFloat().coerceAtLeast(1f)
        val (ow, oh) = outputSize(w, h)
        return copy(zoom = zoom.coerceIn(1f, 4f)).clamped(ow, oh, w, h)
    }

    /** Changes the selected clip's framing; kept inside the picture. */
    fun updateReframe(transform: (Reframe) -> Reframe) {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { c -> c.copy(reframe = transform(c.reframe).clampedFor(c)) }
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

    fun updateExportSettings(transform: (ExportSettings) -> ExportSettings) = _state.update { it.copy(exportSettings = transform(it.exportSettings)) }

    fun setTimelineFps(fps: Float?) = _state.update { it.copy(timelineFps = fps) }

    /** Slow motion (or fast): the sound is muted by default below half speed, where stretching sounds metallic. */
    fun setSpeed(index: Int, speed: Float) = updateClip(index) { if (it.meta.isPhoto) it else it.copy(speed = speed, muteChangedSpeed = speed < 0.5f) }

    fun setMuteChangedSpeed(index: Int, mute: Boolean) = updateClip(index) { it.copy(muteChangedSpeed = mute) }

    /** Fades, kept within the clip's length on the timeline. */
    fun setFades(index: Int, fadeInMs: Long, fadeOutMs: Long) = updateClip(index) {
        val max = it.timelineDurationMs
        it.copy(fadeInMs = fadeInMs.coerceIn(0L, max), fadeOutMs = fadeOutMs.coerceIn(0L, max))
    }

    fun exportVideos() = enqueue(ExportKind.VIDEO, state.value.clips.indices.toList())

    fun exportOverlays() = enqueue(ExportKind.PNG_SEQUENCE, state.value.clips.indices.toList())

    /** Why a 10-bit (HLG) export can't be made of this timeline, or null when it can. */
    fun tenBitBlocker(s: UiState = state.value): String? = when {
        !HdrRoute.deviceSupported -> "This phone's encoder can't do 10-bit HDR editing."
        s.clips.any { it.meta.isPhoto } -> "Photos can't go into a 10-bit export yet."
        s.clips.any { it.meta.format?.transfer == "PQ" } -> "HDR10/HDR10+ (PQ) clips aren't supported yet; HLG and log are."
        else -> null
    }

    fun runTenBitTest() = enqueue(ExportKind.TEN_BIT_TEST, listOfNotNull(state.value.selectedClip))

    fun cancelExports() = exportManager.cancelAll()

    fun clearFinishedExports() = exportManager.clearFinished()

    private fun enqueue(kind: ExportKind, indices: List<Int>) {
        val s = state.value.copy(compareGrade = null)
        viewModelScope.launch {
            val cubes = HashMap<ClipLook, Array<Array<IntArray>>?>()
            val requests = indices.mapNotNull { i ->
                val clip = s.clips.getOrNull(i) ?: return@mapNotNull null
                // The dashboard is one effect among others: without watch data the video still exports.
                val telemetry = clip.sync?.let { sync ->
                    s.activities.getOrNull(sync.trackIndex)?.let { Telemetry(it.track, sync.startUtcMs + clip.trimStartMs + clip.manualOffsetMs) }
                }
                if (kind == ExportKind.PNG_SEQUENCE && telemetry == null) return@mapNotNull null
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
                    telemetry = telemetry,
                    style = s.style,
                    effects = clip.effects,
                    grade = if (graded) GradeSetup(cube, applied.vignette, masks, maskCubes, fromCube, clip.transitionMs) else null,
                    reframe = clip.reframe,
                    speed = clip.speed,
                    isPhoto = clip.meta.isPhoto,
                    muteChangedSpeed = clip.muteChangedSpeed,
                    fadeInMs = clip.fadeInMs,
                    fadeOutMs = clip.fadeOutMs,
                )
            }
            if (requests.isEmpty()) {
                val why = if (kind == ExportKind.PNG_SEQUENCE) "the dashboard needs watch data, and no clip is matched to an activity." else "no clips."
                _state.update { it.copy(messages = it.messages + "Nothing to export: $why") }
            } else {
                exportManager.enqueue(jobsFor(kind, requests, s))
            }
        }
    }

    /**
     * Turns clips into export jobs: the whole timeline as one file (frame size and shape from the
     * first clip), or one file per clip; PNG overlays always per clip.
     */
    private fun jobsFor(kind: ExportKind, requests: List<ExportRequest>, s: UiState): List<JobSpec> {
        val settings = s.exportSettings
        val fps = s.effectiveTimelineFps
        val tenBit = (kind == ExportKind.TEN_BIT_TEST || settings.tenBit) && tenBitBlocker(s) == null
        fun spec(parts: List<ExportRequest>, fileName: String): OutputSpec {
            val first = parts.first()
            val sourceH = first.frameHeight.toFloat()
            val aspect = first.frameWidth.toFloat() / sourceH
            val h = settings.height?.toFloat() ?: sourceH
            val w = h * aspect
            // Bit rate follows the source's per pixel, times the chosen quality.
            val sourceRate = (first.sourceBitrate ?: 60_000_000).toDouble()
            val pixels = (w * h) / (first.width.toDouble() * first.height).coerceAtLeast(1.0)
            val bitrate = (sourceRate * pixels * settings.bitrateFactor).toLong().coerceIn(8_000_000L, 200_000_000L).toInt()
            return OutputSpec(OutputSpec.even(w), OutputSpec.even(h), fps, settings.codec, bitrate, fileName, tenBit)
        }
        return when {
            kind == ExportKind.PNG_SEQUENCE -> requests.map { JobSpec(kind, it.name, listOf(it), null) }
            kind == ExportKind.VIDEO && settings.wholeTimeline -> {
                val name = s.projectName.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                listOf(JobSpec(kind, s.projectName, requests, spec(requests, "${name}_sweetspot.mp4"), s.music))
            }
            else -> requests.map { JobSpec(kind, it.name, listOf(it), spec(listOf(it), "${it.baseName}_sweetspot.mp4")) }
        }
    }

    // Loading

    private sealed interface Loaded {
        data class Activity(val activity: LoadedActivity) : Loaded
        data class Clip(val meta: ClipMetadata) : Loaded
    }

    private fun load(uris: List<Uri>, read: suspend (Uri, (Long, Long) -> Unit) -> Loaded) {
        if (uris.isEmpty()) return
        val context = getApplication<Application>()
        // A file already in the project (same name) is not copied again.
        val known = _state.value.let { s -> s.clips.map { it.meta.name } + s.activities.map { it.name } }.toSet()
        val skipped = uris.filter { (ClipMetadataReader.displayName(context, it) ?: it.lastPathSegment) in known }
        val todo = uris - skipped.toSet()
        if (skipped.isNotEmpty()) {
            _state.update { it.copy(messages = it.messages + "Already in the project: ${skipped.joinToString { u -> ClipMetadataReader.displayName(context, u) ?: "file" }}") }
        }
        if (todo.isEmpty()) return
        _state.update { it.copy(busy = true, loadingMessage = "Copying…", loadingProgress = null) }
        loadJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                todo.mapIndexed { i, uri ->
                    val name = ClipMetadataReader.displayName(context, uri) ?: "file"
                    val count = if (todo.size > 1) " (${i + 1}/${todo.size})" else ""
                    var lastShown = -1L
                    runCatching {
                        read(uri) { copied, total ->
                            // A few updates a second are plenty.
                            val now = System.currentTimeMillis()
                            if (now - lastShown > 150 || copied == total) {
                                lastShown = now
                                val mb = if (total > 0) " · ${copied / 1_000_000} / ${total / 1_000_000} MB" else " · ${copied / 1_000_000} MB"
                                _state.update {
                                    it.copy(
                                        loadingMessage = "Copying $name$count$mb",
                                        loadingProgress = if (total > 0) copied.toFloat() / total else null,
                                    )
                                }
                            }
                        }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else it.printStackTrace() }
                }
            }
            val errors = results.mapNotNull { r -> r.exceptionOrNull()?.let { "Could not read file: ${it.message ?: it.javaClass.simpleName}" } }
            val loaded = results.mapNotNull { it.getOrNull() }
            _state.update { s ->
                val activities = (s.activities + loaded.filterIsInstance<Loaded.Activity>().map { it.activity })
                    .distinctBy { it.track.startMs to it.track.endMs }
                    .sortedBy { it.track.startMs }
                val newClips = loaded.filterIsInstance<Loaded.Clip>().map {
                    ClipState(
                        it.meta, sync = null, lutName = s.defaultLut, grade = s.defaultGrade, effects = s.defaultEffects,
                        // Photos stay on screen for a few seconds; the cut range is their length.
                        trimEndMs = if (it.meta.isPhoto) PHOTO_DEFAULT_MS else it.meta.durationMs,
                    )
                }
                // The same file may already be on the timeline as several (split) clips; only skip re-adds.
                val known = s.clips.map { it.meta.uri }.toSet()
                val clips = s.clips + newClips.filter { it.meta.uri !in known }.distinctBy { it.meta.uri }
                resynced(
                    s.copy(
                        activities = activities,
                        clips = clips,
                        selectedClip = s.selectedClip ?: clips.indices.firstOrNull(),
                        busy = false,
                        loadingMessage = null,
                        loadingProgress = null,
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
        const val PHOTO_DEFAULT_MS = 4_000L
        private const val MAX_HISTORY = 60
        private const val GLOBAL = "global_"
    }
}
