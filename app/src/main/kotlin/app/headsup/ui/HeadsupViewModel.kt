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
import app.headsup.core.edit.SpeedCurve
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
import app.headsup.export.ExportPreset
import app.headsup.export.HdrRoute
import app.headsup.core.edit.AudioEnvelope
import app.headsup.media.AudioAnalysis
import app.headsup.render.Formats
import app.headsup.media.TrackingFrames
import app.headsup.core.color.MaskKey
import app.headsup.core.color.MaskTracker
import app.headsup.export.JobSpec
import app.headsup.export.OutputSpec
import app.headsup.export.VideoCodec
import app.headsup.export.AudioClip
import app.headsup.export.AudioFile
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
import app.headsup.media.Proxies
import app.headsup.core.edit.TextItem
import app.headsup.core.edit.TextAnim
import app.headsup.core.raw.RawDevelop
import app.headsup.media.ProxyState
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
import app.headsup.project.ProjectKind
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
    /** Speed ramp over the cut on top of [speed] ([SpeedCurve]); empty = steady. */
    val speedCurve: List<Float> = emptyList(),
    /** Silence the sound while the speed is changed; otherwise it is stretched, keeping its pitch. */
    val muteChangedSpeed: Boolean = false,
    /** Fade from black at the start and to black at the end (picture and sound), timeline time. */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    /** Level of this clip's own sound, on top of the level for all clips. */
    val soundLevel: Float = 1f,
    /** Development of a raw (DNG) photo: exposure, highlights, shadows and white balance on the raw data. */
    val rawDevelop: RawDevelop = RawDevelop(),
    /** An album photo marked for export. */
    val picked: Boolean = false,
) {
    val look: ClipLook get() = ClipLook(grade, effects, lutName, masks)

    /** Time evidence used for matching: the manual start if set, otherwise the file's own. */
    val timeInfo: ClipInfo
        get() = manualStart?.let {
            ClipInfo(meta.durationMs, listOf(TimeEvidence(TimeSource.MANUAL, ClockReading.Local(it), TimeAnchor.START)))
        } ?: meta.info

    val durationMs: Long get() = trimEndMs - trimStartMs

    /** Length on the timeline, after the speed change (and ramp). */
    val timelineDurationMs: Long get() = timelineOfSource(durationMs)

    /** Timeline time [ms] of file time into the cut (photos: the same). */
    fun timelineOfSource(ms: Long): Long = if (meta.isPhoto) ms else SpeedCurve.timelineOf(ms, durationMs, speed, speedCurve)

    /** File time into the cut at timeline time [ms] into the clip. */
    fun sourceOfTimeline(ms: Long): Long = if (meta.isPhoto) ms else SpeedCurve.sourceOf(ms, durationMs, speed, speedCurve)

    /** Speed at file time [ms] into the cut. */
    fun speedAt(ms: Long): Float = if (speedCurve.isEmpty()) speed else SpeedCurve.speedAt(speed, speedCurve, ms.toFloat() / durationMs.coerceAtLeast(1L))

    /** The clip's speed on average (file time per timeline time). */
    val averageSpeed: Float get() = if (meta.isPhoto) 1f else durationMs.toFloat() / timelineDurationMs.coerceAtLeast(1L)

    /** Speed changed at all (steady or ramped). */
    val speedChanged: Boolean get() = speed != 1f || speedCurve.isNotEmpty()
    val isTrimmed: Boolean get() = trimStartMs > 0 || trimEndMs < meta.durationMs

    /** BT.2020 footage that isn't HDR (e.g. Samsung Log): shown in BT.709 unless a LUT takes it. */
    val wideGamut: Boolean get() = meta.format?.let { it.primaries == "BT.2020" && !it.isHdr } == true

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
data class MatchSession(
    val clipId: Long,
    val groupId: Long?,
    val base: ColorGrade,
    val delta: ColorGrade,
    val strength: Float,
    /** For a group: each clip's corrections before the match. */
    val memberBases: Map<Long, ColorGrade> = emptyMap(),
)

/** A full match often overshoots between different scenes, so it starts at this share. */
const val MATCH_STRENGTH = 0.6f

/** A highlight found in the watch data, placed in the clip that shows it. */
data class FoundHighlight(val highlight: Highlight, val clipId: Long, val clipName: String, val positionMs: Long)

/**
 * One step of undo history: the edit itself (files, cuts, colour, groups, effects, framing,
 * dashboard), not what is selected or on screen. [project] keeps histories of projects apart.
 */
/** A sound file's loudness (waveform) and rhythm (beat grid, accents). */
data class SoundAnalysis(val envelope: AudioEnvelope, val rhythm: AudioEnvelope.Rhythm)

private data class EditSnapshot(
    val activities: List<LoadedActivity>,
    val clips: List<ClipState>,
    val groups: List<ClipGroup>,
    val globalGrade: ColorGrade,
    val style: DashboardStyle,
    val timelineFps: Float?,
    val frameAspect: Float?,
    val exportSettings: ExportSettings,
    val audioFiles: List<AudioFile>,
    val audioClips: List<AudioClip>,
    val clipSound: Float,
    val texts: List<TextItem>,
    val routeMap: app.headsup.render.RouteMap,
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
    /** Effects change this clip only, or are set on every clip (as a sync). */
    val effectsOnAll: Boolean = false,
    val groups: List<ClipGroup> = emptyList(),
    val luts: List<String> = emptyList(),
    /** Grade shown in the preview instead of [grade] while comparing (one correction held off). */
    val compareGrade: ColorGrade? = null,
    /** Colour table for the selected clip's preview; null when its colours stay untouched. */
    val previewCube: Array<Array<IntArray>>? = null,
    /** Colour tables inside the selected clip's masks, and the previous clip's for a transition. */
    val previewMaskCubes: List<Array<Array<IntArray>>> = emptyList(),
    val previewFromCube: Array<Array<IntArray>>? = null,
    /** Looks of the clips before and after the selected one (null: untouched), for the preview at a cut. */
    val previewNeighbourCubes: Map<Long, Array<Array<IntArray>>?> = emptyMap(),
    /** Mask of the selected clip that the Masks tab edits. */
    val selectedMask: Int = 0,
    /** The brush for painted masks. */
    val brush: BrushSettings = BrushSettings(),
    /** An outline being drawn around an object (x, y pairs, fractions of the picture), until the finger lifts. */
    val lassoPath: List<Float> = emptyList(),
    /** A brush stroke being painted (x, y pairs, fractions of the picture), until the finger lifts. */
    val paintPath: List<Float> = emptyList(),
    /** The picture's height on screen in pixels, as the preview shows it (for the brush's real size). */
    val pictureHeightPx: Float = 0f,
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
    /** A safe point is set (a moment of the edit to come back to). */
    val hasSafePoint: Boolean = false,
    val exportSettings: ExportSettings = ExportSettings(),
    /** Frame rate of the timeline (exports and slow-motion maths); null = the first clip's. */
    val timelineFps: Float? = null,
    /** Shape of the timeline's frame (width / height); null = the first clip's own. */
    val frameAspect: Float? = null,
    /** The project's sound files (Files page) and the clips placed from them on the audio tracks. */
    val audioFiles: List<AudioFile> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    /** Level of the video clips' own sound, 0..1. */
    val clipSound: Float = 1f,
    /** The audio clip being edited on the Audio page. */
    val selectedAudio: Long? = null,
    /** Loudness and rhythm of each sound file, for the audio timeline (missing while analysed). */
    val audioAnalysis: Map<Uri, SoundAnalysis> = emptyMap(),
    /** Vectorscope and skin measurement of the last frame tapped in the preview. */
    val scope: ScopeResult? = null,
    /** Look copied with "Copy", waiting to be pasted. */
    val copiedLook: ClipLook? = null,
    /** Preview files of large clips, by source. */
    val proxies: Map<Uri, ProxyState> = emptyMap(),
    /** A film (timeline) or a photo album (each photo edited and exported on its own). */
    val projectKind: ProjectKind = ProjectKind.VIDEO,
    /** Texts on the timeline, and the one being edited on the Text page. */
    val texts: List<TextItem> = emptyList(),
    val selectedText: Long? = null,
    /** The activity's route drawn over the picture. */
    val routeMap: app.headsup.render.RouteMap = app.headsup.render.RouteMap(),
    /** Bumped when place names arrive, so texts showing `{place}` redraw. */
    val placesVersion: Int = 0,
) {
    /** What the preview plays for [c]: its light preview file when ready, else the clip itself. */
    fun previewSource(c: ClipState): Uri = (proxies[c.meta.uri] as? ProxyState.Ready)?.file ?: c.meta.uri

    val selected: ClipState? get() = selectedClip?.let { clips.getOrNull(it) }

    /** Only photos: the app works as a photo editor (no playback, sound, timing or text animation). */
    val photoProject: Boolean get() = projectKind == ProjectKind.PHOTOS

    /** The timeline's frame rate: chosen, or the first clip's, or 30. */
    val effectiveTimelineFps: Float get() = timelineFps ?: clips.firstOrNull()?.meta?.frameRate ?: 30f

    /** The timeline frame's shape: chosen, or the first clip's. */
    val timelineAspect: Float
        get() = frameAspect ?: clips.firstOrNull()?.meta?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)

    /** [c]'s framing in the timeline's frame (preview and export). */
    fun framing(c: ClipState): Reframe = if (photoProject) c.reframe else c.reframe.copy(aspect = timelineAspect)

    /** Corrections the colour page edits: the selected clip's, its mask's, or the global node's. */
    val grade: ColorGrade
        get() = when {
            // "All" and a group set their values on each clip: the clip's own are shown.
            target == GradeTarget.GLOBAL || target == GradeTarget.GROUP -> selected?.grade ?: defaultGrade
            else -> gradeOf(target)
        }

    private fun gradeOf(target: GradeTarget): ColorGrade =
        when (target) {
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
        // The "all" and group tabs edit the clip's own values.
        val target = if (target == GradeTarget.GLOBAL || target == GradeTarget.GROUP) GradeTarget.CLIP else target
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

            globalGrade = ColorGrade(),
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
    private val proxies = Proxies(app, viewModelScope)

    init {
        projectStore.lastProject?.takeIf(projectStore::exists)?.let(::openProject)
            ?: _state.update { it.copy(projectName = newProjectName()) }
        autosave()
        recordHistory()
        watchProxies()
    }

    /** Preview files for large clips: asked for as clips come in, their state kept in [UiState.proxies]. */
    private fun watchProxies() {
        viewModelScope.launch {
            _state.map { s -> s.clips.map { it.meta } }.distinctUntilChanged().collect { proxies.request(it) }
        }
        // Place names for photos with GPS and clips with watch positions, for texts showing {place}.
        viewModelScope.launch {
            _state.map { s -> s.clips.map { it.id } to s.activities.size }.distinctUntilChanged().collect {
                val s = _state.value
                val spots = s.clips.mapNotNull { c -> placeOf(s, c) }.distinct()
                for ((lat, lon) in spots) {
                    if (app.headsup.media.Places.known(lat, lon) != null) continue
                    if (app.headsup.media.Places.lookUp(getApplication(), lat, lon) != null) _state.update { it.copy(placesVersion = it.placesVersion + 1) }
                }
            }
        }
        viewModelScope.launch {
            proxies.states.collect { states -> _state.update { it.copy(proxies = states) } }
        }
    }

    /** Changes the raw development of clip [index] (a DNG photo). */
    fun updateRawDevelop(index: Int, transform: (RawDevelop) -> RawDevelop) {
        val now = _state.value
        if (now.target == GradeTarget.GLOBAL || now.target == GradeTarget.GROUP) {
            val clip = now.clips.getOrNull(index) ?: return
            val before = clip.rawDevelop
            val after = transform(before)
            _state.update { s -> s.copy(clips = s.clips.map { c -> if (c.meta.isRaw && s.syncsTo(c)) c.copy(rawDevelop = c.rawDevelop.synced(before, after)) else c }) }
            refreshPreview()
            return
        }
        updateClip(index) { it.copy(rawDevelop = transform(it.rawDevelop)) }
    }

    /** The clips an edit on "all" or a group reaches (those with colour on). */
    private fun UiState.syncsTo(c: ClipState): Boolean = c.colorGrade && when (target) {
        GradeTarget.GLOBAL -> true
        GradeTarget.GROUP -> selected?.groupId?.let { it == c.groupId } ?: (c.id == selected?.id)
        else -> c.id == selected?.id
    }

    /** Every clip the current tab reaches back to no corrections (and a raw as shot). */
    fun resetSyncedPhotos() {
        _state.update { s -> s.copy(clips = s.clips.map { c -> if (s.syncsTo(c)) c.copy(grade = ColorGrade(), rawDevelop = RawDevelop()) else c }) }
        refreshPreview()
    }

    /**
     * Every correction lives on the clips themselves: a group's and the global ones (from
     * projects saved before "all" and groups synced) are added into each clip's own.
     */
    private fun foldedIntoClips(clips: List<ClipState>, project: ProjectData): List<ClipState> = clips.map { c ->
        val group = project.groups.firstOrNull { it.id == c.groupId }
        c.copy(grade = c.grade + (group?.grade ?: ColorGrade()) + project.globalGrade, lutName = c.lutName ?: group?.lutName)
    }

    /** Marks or unmarks an album photo for export. */
    fun togglePicked(index: Int) = updateClip(index) { it.copy(picked = !it.picked) }

    // Texts

    private val samplers = java.util.WeakHashMap<app.headsup.core.track.ActivityTrack, app.headsup.core.track.TelemetrySampler>()

    private fun activityOf(s: UiState, c: ClipState) = (c.sync?.let { s.activities.getOrNull(it.trackIndex) } ?: s.activities.firstOrNull())?.track

    /** Where [c] was: the photo's GPS, or the watch's position at its start. */
    private suspend fun placeOf(s: UiState, c: ClipState): Pair<Double, Double>? {
        if (c.meta.isPhoto) {
            val info = withContext(Dispatchers.IO) { app.headsup.media.PhotoInfo.read(getApplication(), c.meta.uri) }
            if (info.latitude != null && info.longitude != null) return info.latitude to info.longitude
        }
        val track = activityOf(s, c) ?: return null
        val utc = c.telemetryUtc(0L) ?: return null
        val f = synchronized(samplers) { samplers.getOrPut(track) { app.headsup.core.track.TelemetrySampler(track) } }.frameAt(utc)
        val lat = f[app.headsup.core.track.Metric.LATITUDE] ?: return null
        val lon = f[app.headsup.core.track.Metric.LONGITUDE] ?: return null
        return lat to lon
    }

    /**
     * Values of the text fields for clip [c] at [positionMs] into it: the activity's summary, its
     * values at that moment and the photo's own data (which wins for place, date and time).
     */
    fun textValues(s: UiState, c: ClipState, positionMs: Long): Map<String, String> {
        val track = activityOf(s, c)
        val utc = c.telemetryUtc(positionMs)
        val frame = if (track != null && utc != null) synchronized(samplers) { samplers.getOrPut(track) { app.headsup.core.track.TelemetrySampler(track) } }.frameAt(utc) else null
        val offset = track?.utcOffsetSeconds ?: utc?.let { java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(it)).totalSeconds }
        val photo = if (c.meta.isPhoto) app.headsup.media.PhotoInfo.read(getApplication(), c.meta.uri) else null
        return app.headsup.render.TextValues.activity(track) + app.headsup.render.TextValues.moment(frame, utc, offset) + app.headsup.render.TextValues.photo(photo)
    }

    fun updateRouteMap(transform: (app.headsup.render.RouteMap) -> app.headsup.render.RouteMap) = _state.update { it.copy(routeMap = transform(it.routeMap)) }

    /** The activity whose route the map shows for clip [c]. */
    fun mapTrack(s: UiState, c: ClipState) = activityOf(s, c)

    /** Where clip [c] is on the route at [positionMs]: the photo's GPS, or the watch's position then. */
    fun mapMarker(s: UiState, c: ClipState, positionMs: Long): Pair<Double, Double>? {
        if (c.meta.isPhoto) {
            val info = app.headsup.media.PhotoInfo.read(getApplication(), c.meta.uri)
            if (info.latitude != null && info.longitude != null) return info.latitude to info.longitude
        }
        val track = activityOf(s, c) ?: return null
        val utc = c.telemetryUtc(positionMs) ?: return null
        val f = synchronized(samplers) { samplers.getOrPut(track) { app.headsup.core.track.TelemetrySampler(track) } }.frameAt(utc)
        val lat = f[app.headsup.core.track.Metric.LATITUDE] ?: return null
        val lon = f[app.headsup.core.track.Metric.LONGITUDE] ?: return null
        return lat to lon
    }

    /** The values that do not change within a clip (for the export, which adds the moment's). */
    private fun staticTextValues(s: UiState, c: ClipState): Map<String, String> {
        val photo = if (c.meta.isPhoto) app.headsup.media.PhotoInfo.read(getApplication(), c.meta.uri) else null
        return app.headsup.render.TextValues.activity(activityOf(s, c)) + app.headsup.render.TextValues.photo(photo)
    }

    /** Adds a text at timeline time [atMs] (3 s, or up to the video's end) and selects it. */
    fun addText(atMs: Long) = _state.update { s ->
        val end = s.clips.sumOf { it.timelineDurationMs }
        val start = atMs.coerceIn(0L, (end - 500L).coerceAtLeast(0L))
        val id = (s.texts.maxOfOrNull { it.id } ?: 0L) + 1
        // On photos a text simply stays: over the whole project, without animation.
        val item = if (s.photoProject) {
            // In an album a new text belongs to the photo it is added on.
            TextItem(
                id = id, text = "Text", startMs = 0L, lengthMs = end.coerceAtLeast(500L), animIn = TextAnim.NONE, animOut = TextAnim.NONE,
                onlyClip = s.selected?.id,
            )
        } else {
            TextItem(id = id, text = "Text", startMs = start, lengthMs = minOf(3_000L, (end - start).coerceAtLeast(500L)))
        }
        s.copy(texts = s.texts + item, selectedText = id)
    }

    fun selectText(id: Long?) = _state.update { it.copy(selectedText = id) }

    fun updateText(id: Long, transform: (TextItem) -> TextItem) =
        _state.update { s -> s.copy(texts = s.texts.map { if (it.id == id) transform(it) else it }) }

    fun removeText(id: Long) = _state.update { s -> s.copy(texts = s.texts.filter { it.id != id }, selectedText = null) }

    /** The preview plays: preview files wait, so their decoding does not take the player's decoder. */
    fun setPreviewPlaying(playing: Boolean) {
        proxies.held = playing
    }

    override fun onCleared() {
        proxies.release()
        super.onCleared()
    }

    // Undo and redo

    private val history = ArrayList<EditSnapshot>()

    /** The project as it was when opened: kept apart, as the history drops its oldest steps. */
    private var historyBase: EditSnapshot? = null
    private var historyStartsAgain = true
    private var historyIndex = -1

    private fun UiState.snapshot() = EditSnapshot(activities, clips, groups, globalGrade, style, timelineFps, frameAspect, exportSettings, audioFiles, audioClips, clipSound, texts, routeMap)

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
        if (current == null || historyStartsAgain || current.media().containsAll(snapshot.media()).not()) {
            // A project opened or created, or files added: history starts here (undo never takes
            // files out of the project; removing one is done on purpose). A rename or a highlight
            // reel stays in it, so they can be undone.
            historyStartsAgain = false
            history.clear()
            history += snapshot
            historyBase = snapshot
            historyIndex = 0
        } else {
            while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
            history += snapshot
            if (history.size > MAX_HISTORY) history.removeAt(0)
            historyIndex = history.lastIndex
        }
        updateHistoryFlags()
    }

    /** The files a snapshot uses. */
    private fun EditSnapshot.media(): Set<Any> = buildSet {
        clips.forEach { add(it.meta.uri) }
        audioFiles.forEach { add(it.uri) }
        activities.forEach { a -> a.uri?.let { add(it) } }
    }

    fun undo() {
        // An edit made just now may not be recorded yet; keep it, so redo can bring it back.
        record(_state.value.snapshot())
        if (historyIndex <= 0) return
        historyIndex--
        restore(history[historyIndex])
    }

    /**
     * Back to the project as it was when opened, however many steps ago. It is a step of its own,
     * so undo brings the changes back.
     */
    /** The edit as it was when the safe point was set (this session). */
    private var safePoint: EditSnapshot? = null

    /** Keeps the edit as it is now, to come back to with [backToSafePoint]. */
    fun setSafePoint() {
        safePoint = _state.value.snapshot()
        _state.update { it.copy(hasSafePoint = true, messages = it.messages + "Safe point set. Tap the flag again to come back here.") }
    }

    /** Back to the safe point, as a step of its own (undo returns to where you were). */
    fun backToSafePoint() {
        val point = safePoint ?: return
        record(_state.value.snapshot())
        restore(point)
        record(point)
    }

    fun undoAll() {
        val base = historyBase ?: return
        record(_state.value.snapshot())
        if (history.getOrNull(historyIndex) == base) return
        restore(base)
        record(base)
    }

    /**
     * Every picture edit gone, however long ago it was made (also in earlier sessions): colour,
     * masks, LUTs, framing and perspective, effects, speed, cuts, fades, blends, raw development and
     * the groups' and all clips' corrections. The files, their order, sound and texts stay; pieces
     * of one file cut apart become one clip again. A step of its own, so undo brings it all back.
     */
    fun resetAllEdits() {
        record(_state.value.snapshot())
        _state.update { s ->
            val fresh = mutableListOf<ClipState>()
            for (c in s.clips) {
                // The pieces of a cut-up file, side by side, are one clip again.
                if (fresh.lastOrNull()?.meta?.uri == c.meta.uri) continue
                fresh += ClipState(
                    meta = c.meta, sync = c.sync, id = c.id, manualOffsetMs = c.manualOffsetMs,
                    manualStart = c.manualStart, picked = c.picked, soundLevel = c.soundLevel,
                )
            }
            s.copy(
                clips = fresh,
                groups = s.groups.map { it.copy(grade = ColorGrade(), lutName = null) },
                globalGrade = ColorGrade(),
                selectedClip = s.selectedClip?.coerceAtMost(fresh.lastIndex)?.takeIf { it >= 0 },
                selectedMask = 0,
                compareGrade = null,
                cine = null,
                matchSession = null,
                scope = null,
            )
        }
        record(_state.value.snapshot())
        refreshPreview()
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
                frameAspect = snapshot.frameAspect,
                exportSettings = snapshot.exportSettings,
                audioFiles = snapshot.audioFiles,
                audioClips = snapshot.audioClips,
                clipSound = snapshot.clipSound,
                texts = snapshot.texts,
                routeMap = snapshot.routeMap,
                selectedText = s.selectedText?.takeIf { id -> snapshot.texts.any { it.id == id } },
                selectedClip = s.selectedClip?.coerceAtMost(snapshot.clips.lastIndex)?.takeIf { it >= 0 } ?: snapshot.clips.indices.firstOrNull(),
                compareGrade = null,
                cine = null,
                matchSession = null,
                scope = null,
            )
        }
        stylePrefs.save(snapshot.style)
        updateHistoryFlags()
        analyseAudio()
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
                it.fadeInMs, it.fadeOutMs, it.soundLevel, it.rawDevelop, it.picked, it.speedCurve,
            )
        },
        groups = groups.map { SavedGroup(it.id, it.name, it.grade, it.lutName) },
        globalGrade = globalGrade,
        timelineFps = timelineFps,
        frameAspect = frameAspect,
        audioFiles = audioFiles,
        audioClips = audioClips,
        clipSound = clipSound,
        texts = texts,
        kind = projectKind,
        routeMap = routeMap,
    )

    fun refreshProjects() = _state.update {
        it.copy(
            projects = projectStore.list().map { info ->
                val p = runCatching { projectStore.load(info.name) }.getOrNull()
                info.copy(bytes = p?.let { d -> library.sizeOf(d.mediaUris) } ?: 0L, kind = p?.kind ?: ProjectKind.VIDEO)
            },
        )
    }

    /** Opens a saved project; its files are read again, missing ones are reported. */
    fun openProject(name: String) {
        val project = projectStore.load(name) ?: return
        historyStartsAgain = true
        // A safe point belongs to the project it was set in.
        safePoint = null
        _state.update { it.copy(hasSafePoint = false) }
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
                    speedCurve = c.speedCurve,
                    muteChangedSpeed = c.muteChangedSpeed,
                    fadeInMs = c.fadeInMs,
                    fadeOutMs = c.fadeOutMs,
                    soundLevel = c.soundLevel,
                    rawDevelop = c.rawDevelop,
                    picked = c.picked,
                    groupId = c.groupId,
                )
            }
            scopeFrame = null
            // Older projects of only photos open as albums.
            val kind = project.kind ?: if (clips.isNotEmpty() && clips.all { it.meta.isPhoto }) ProjectKind.PHOTOS else ProjectKind.VIDEO
            _state.update { s ->
                resynced(
                    s.copy(
                        projectName = project.name,
                        projectKind = kind,
                        activities = activities.sortedBy { it.track.startMs },
                        clips = foldedIntoClips(clips, project),
                        groups = project.groups.map { ClipGroup(it.id, it.name, ColorGrade(), null) },
                        globalGrade = ColorGrade(),
                        timelineFps = project.timelineFps,
                        // Older projects kept the shape per clip: one shared by all becomes the frame's.
                        frameAspect = project.frameAspect ?: clips.map { it.reframe.aspect }.distinct().singleOrNull(),
                        audioFiles = project.audioFiles.distinctBy { it.uri },
                        audioClips = compactLanes(project.audioClips),
                        clipSound = project.clipSound,
                        texts = project.texts,
                        routeMap = project.routeMap,
                        selectedText = null,
                        selectedAudio = null,
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
            analyseAudio()
            refreshPreview()
        }
    }

    /** Starts an empty project; the current one stays saved. */
    fun newProject(kind: ProjectKind = ProjectKind.VIDEO) {
        historyStartsAgain = true
        // A safe point belongs to the project it was set in.
        safePoint = null
        _state.update { it.copy(hasSafePoint = false) }
        scopeFrame = null
        _state.update {
            it.copy(
                projectName = newProjectName(),
                projectKind = kind,
                activities = emptyList(),
                clips = emptyList(),
                groups = emptyList(),
                globalGrade = ColorGrade(),
                timelineFps = null,
                frameAspect = null,
                audioFiles = emptyList(),
                audioClips = emptyList(),
                texts = emptyList(),
                selectedText = null,
                routeMap = app.headsup.render.RouteMap(),
                clipSound = 1f,
                selectedAudio = null,
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
        get() = (clips.map { it.meta.uri } + activities.mapNotNull { it.uri } + audioFiles.map { it.uri }).distinct().filterNot(library::isInLibrary)

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
                    audioFiles = s.audioFiles.map { f -> copies[f.uri]?.let { f.copy(uri = it) } ?: f },
                    audioClips = s.audioClips.map { c -> copies[c.uri]?.let { c.copy(uri = it) } ?: c },
                    busy = false,
                    loadingMessage = null,
                    loadingProgress = null,
                    messages = s.messages + failed.map { "Could not copy: $it" },
                )
            }
            refreshPreview()
        }
    }

    // Sound

    /** Copies sound files into the app's folder and adds them to the project's library (Files page). */
    fun addAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.busy) return
        val context = getApplication<Application>()
        _state.update { it.copy(busy = true, loadingMessage = "Copying sound", loadingProgress = null) }
        loadJob = viewModelScope.launch {
            val added = mutableListOf<AudioFile>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val name = ClipMetadataReader.displayName(context, uri) ?: "Sound"
                val local = runCatching {
                    withContext(Dispatchers.IO) {
                        library.import(uri) { copied, total ->
                            _state.update { it.copy(loadingMessage = "Copying $name · ${copied / 1_000_000} MB", loadingProgress = if (total > 0) copied.toFloat() / total else null) }
                        }
                    }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                if (local == null) failed += name else added += AudioFile(local, name, durationOf(local))
            }
            _state.update { s ->
                s.copy(
                    audioFiles = (s.audioFiles + added).distinctBy { it.uri },
                    busy = false,
                    loadingMessage = null,
                    loadingProgress = null,
                    messages = s.messages + failed.map { "Could not add $it" },
                )
            }
            analyseAudio()
        }
    }

    /** Removes a sound from the library, and its clips from the tracks. */
    fun removeAudioFile(uri: Uri) = _state.update { s ->
        s.copy(audioFiles = s.audioFiles.filter { it.uri != uri }, audioClips = compactLanes(s.audioClips.filter { it.uri != uri }))
    }

    /**
     * Places [file] at [atMs] on the timeline, on the first track that is free there (a new
     * track if none), and selects it. A sound longer than the rest of the video is cut where the
     * video ends.
     */
    fun placeAudio(file: AudioFile, atMs: Long) {
        val s = _state.value
        val id = (s.audioClips.maxOfOrNull { it.id } ?: 0L) + 1
        val start = atMs.coerceAtLeast(0L)
        val room = s.clips.sumOf { it.timelineDurationMs } - start
        val end = if (room >= 1_000L && file.durationMs > room) room else 0L
        val draft = AudioClip(id, file.uri, file.name, sourceDurationMs = file.durationMs, startOnTimelineMs = start, sourceEndMs = end)
        val lane = freeLane(s.audioClips, draft.piece.startOnTimelineMs, draft.piece.endOnTimelineMs)
        _state.update { it.copy(audioClips = it.audioClips + draft.copy(lane = lane), selectedAudio = id) }
    }

    /** The first track with nothing between [fromMs] and [toMs] (ignoring clip [except]); the last one if all are taken. */
    private fun freeLane(clips: List<AudioClip>, fromMs: Long, toMs: Long, except: Long? = null): Int {
        var lane = 0
        while (lane < MAX_LANES - 1 && clips.any { it.id != except && it.lane == lane && it.piece.startOnTimelineMs < toMs && it.piece.endOnTimelineMs > fromMs }) lane++
        return lane
    }

    /** Tracks numbered from 0 without gaps, so no empty tracks are shown between used ones. */
    private fun compactLanes(clips: List<AudioClip>): List<AudioClip> {
        val used = clips.map { it.lane }.distinct().sorted()
        return clips.map { c -> c.copy(lane = used.indexOf(c.lane).coerceAtMost(MAX_LANES - 1)) }
    }

    fun tidyLanes() = _state.update { s -> s.copy(audioClips = compactLanes(s.audioClips)) }

    fun selectAudio(id: Long?) = _state.update { it.copy(selectedAudio = id) }

    fun updateAudio(id: Long, transform: (AudioClip) -> AudioClip) =
        _state.update { s -> s.copy(audioClips = s.audioClips.map { if (it.id == id) transform(it) else it }) }

    fun removeAudio(id: Long) = _state.update { s -> s.copy(audioClips = compactLanes(s.audioClips.filter { it.id != id }), selectedAudio = null) }

    /** Cuts a sound in two at [atMs] on the timeline; the second part is selected. */
    fun splitAudio(id: Long, atMs: Long) {
        val s = _state.value
        val c = s.audioClips.firstOrNull { it.id == id } ?: return
        val p = c.piece
        val at = p.sourceAt(atMs) ?: return
        if (atMs - p.startOnTimelineMs < 100 || p.endOnTimelineMs - atMs < 100) return
        val newId = (s.audioClips.maxOfOrNull { it.id } ?: 0L) + 1
        // The fade-in stays on the first part, the fade-out on the second.
        val first = c.copy(sourceEndMs = at, fadeOutMs = 0L)
        val second = c.copy(id = newId, startOnTimelineMs = atMs, sourceStartMs = at, fadeInMs = 0L)
        _state.update { st -> st.copy(audioClips = st.audioClips.map { if (it.id == id) first else it } + second, selectedAudio = newId) }
    }

    fun setSoundLevel(index: Int, level: Float) = updateClip(index) { it.copy(soundLevel = level.coerceIn(0f, 1f)) }

    fun setClipSound(level: Float) = _state.update { it.copy(clipSound = level.coerceIn(0f, 1f)) }

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

    private val analysing = mutableSetOf<Uri>()

    /** Analyses each sound file once (loudness and rhythm) for the timeline. */
    private fun analyseAudio() {
        val s = _state.value
        for (file in s.audioFiles) {
            if (file.uri in s.audioAnalysis || !analysing.add(file.uri)) continue
            viewModelScope.launch {
                val envelope = withContext(Dispatchers.IO) { AudioAnalysis.envelope(getApplication(), file.uri) }
                val rhythm = envelope?.let { withContext(Dispatchers.Default) { it.rhythm() } }
                analysing.remove(file.uri)
                if (envelope != null && rhythm != null) {
                    _state.update { it.copy(audioAnalysis = it.audioAnalysis + (file.uri to SoundAnalysis(envelope, rhythm))) }
                }
            }
        }
    }

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
            // The second part is shown from its start: the playhead stays at the cut.
            s.copy(clips = s.clips.toMutableList().apply { set(index, first); add(index + 1, second) }, selectedClip = index + 1)
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
            // The selection moves to the neighbouring clip, so the editor stays on the timeline.
            val selected = s.selectedClip?.let { sel -> if (sel >= index) (sel - 1).coerceAtLeast(0) else sel } ?: 0
            s.copy(clips = clips, selectedClip = selected.takeIf { clips.isNotEmpty() }?.coerceAtMost(clips.size - 1))
        }
        refreshPreview()
    }

    /** Removes the photos marked for export from the album. */
    fun removePicked() {
        _state.update { s ->
            val clips = s.clips.filter { !it.picked }
            val selected = s.selected?.takeIf { !it.picked }?.let { c -> clips.indexOfFirst { it.id == c.id } } ?: 0
            s.copy(clips = clips, selectedClip = selected.takeIf { clips.isNotEmpty() }?.coerceIn(0, clips.size - 1))
        }
        refreshPreview()
    }

    /** Removes a file from the project: every clip cut from it. */
    fun removeClipsOf(uri: Uri) {
        _state.update { s ->
            val clips = s.clips.filter { it.meta.uri != uri }
            val selected = s.selected?.takeIf { it.meta.uri != uri }?.let { c -> clips.indexOfFirst { it.id == c.id } } ?: 0
            s.copy(clips = clips, selectedClip = selected.takeIf { clips.isNotEmpty() }?.coerceIn(0, clips.size - 1))
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
            val before = s.effects
            val effects = transform(before)
            s.copy(
                defaultEffects = if (s.effectsOnAll) s.defaultEffects.synced(before, effects) else effects,
                clips = s.clips.mapIndexed { i, c ->
                    when {
                        i == s.selectedClip -> c.copy(effects = effects)
                        // On all clips: the changed values are set on each, the others kept.
                        s.effectsOnAll -> c.copy(effects = c.effects.synced(before, effects))
                        else -> c
                    }
                },
            )
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

    fun setEffectsOnAll(all: Boolean) = _state.update { it.copy(effectsOnAll = all) }

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
                groups = s.groups + ClipGroup(id, "Group $number"),
                clips = s.clips.mapIndexed { i, c -> if (i == index) c.copy(groupId = id) else c },
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

    /** Sets the LUT on each clip of the group. */
    fun setGroupLut(id: Long, lutName: String?) {
        _state.update { s -> s.copy(clips = s.clips.map { if (it.groupId == id) it.copy(lutName = lutName) else it }) }
        refreshPreview()
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
            val members = if (group == null) emptyMap() else s.clips.filter { it.groupId == group.id }.associate { it.id to it.grade }
            val session = MatchSession(clip.id, group?.id, clip.grade, delta, MATCH_STRENGTH, members)
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
        val d = m.delta
        fun matched(b: ColorGrade) = b.copy(
            exposure = (b.exposure + d.exposure * k).coerceIn(-3f, 3f),
            contrast = (b.contrast + d.contrast * k).coerceIn(-1f, 1f),
            temperature = (b.temperature + d.temperature * k).coerceIn(-1f, 1f),
            tint = (b.tint + d.tint * k).coerceIn(-1f, 1f),
            saturation = (b.saturation + d.saturation * k).coerceIn(-1f, 1f),
            skinHue = (b.skinHue + d.skinHue * k).coerceIn(-30f, 30f),
        )
        val grade = matched(m.base)
        if (m.groupId != null) {
            // The camera's character goes onto each clip of the group.
            _state.update { s -> s.copy(clips = s.clips.map { c -> m.memberBases[c.id]?.let { c.copy(grade = matched(it)) } ?: c }) }
            refreshPreview()
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
    /**
     * Social presets. Vertical: 9:16 at 1080×1920, 30 fps, HEVC 8-bit (platforms re-encode anyway;
     * all of them take HEVC). YouTube: the clips' own size, best quality, 10-bit when the clips are.
     */
    fun applyPreset(preset: ExportPreset) {
        val tenBitSource = _state.value.clips.any { it.meta.format?.tenBit == true }
        when (preset) {
            ExportPreset.VERTICAL -> {
                setFrameAspect(9f / 16f)
                _state.update {
                    it.copy(timelineFps = 30f, exportSettings = it.exportSettings.copy(wholeTimeline = true, height = 1080, bitrateFactor = 1.5f, codec = VideoCodec.HEVC, tenBit = false))
                }
            }
            ExportPreset.YOUTUBE -> _state.update {
                it.copy(exportSettings = it.exportSettings.copy(wholeTimeline = true, height = null, bitrateFactor = 2f, codec = VideoCodec.HEVC, tenBit = tenBitSource, hlg = false))
            }
        }
    }

    /** The preset the current settings match, if any. */
    fun activePreset(s: UiState): ExportPreset? {
        val e = s.exportSettings
        return when {
            s.clips.isNotEmpty() && s.frameAspect == 9f / 16f && e.height == 1080 && s.timelineFps == 30f &&
                e.codec == VideoCodec.HEVC && !e.tenBit && e.bitrateFactor == 1.5f -> ExportPreset.VERTICAL
            e.height == null && e.bitrateFactor == 2f && e.codec == VideoCodec.HEVC && !e.hlg -> ExportPreset.YOUTUBE
            else -> null
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

    /**
     * The timeline's frame shape (e.g. 9:16 for a reel; null = the first clip's): every clip is
     * shown and exported in it, filling it or fitting inside it, each keeping its own position.
     */
    fun setFrameAspect(aspect: Float?) {
        _state.update { s ->
            val next = s.copy(frameAspect = aspect)
            next.copy(clips = next.clips.map { c -> c.copy(reframe = c.reframe.clampedFor(c, next.timelineAspect)) })
        }
        refreshPreview()
    }

    private fun Reframe.clampedFor(c: ClipState, aspect: Float?): Reframe {
        val w = c.meta.width.toFloat().coerceAtLeast(1f)
        val h = c.meta.height.toFloat().coerceAtLeast(1f)
        val framed = copy(aspect = aspect)
        val (ow, oh) = framed.outputSize(w, h)
        return framed.copy(zoom = zoom.coerceIn(1f, 4f)).clamped(ow, oh, w, h)
    }

    /** Changes the selected clip's framing; kept inside the picture. */
    fun updateReframe(transform: (Reframe) -> Reframe) {
        val index = _state.value.selectedClip ?: return
        val s = _state.value
        // An album's photo keeps its own crop shape (null: the photo's); a film's clips take the timeline's.
        updateClip(index) { c -> transform(c.reframe).let { r -> c.copy(reframe = r.clampedFor(c, if (s.photoProject) r.aspect else s.timelineAspect)) } }
    }

    /** Fill or fit for every clip, e.g. after changing the frame's shape. */
    fun setFitForAll(fit: Boolean) {
        _state.update { s -> s.copy(clips = s.clips.map { c -> c.copy(reframe = c.reframe.copy(fit = fit).clampedFor(c, s.timelineAspect)) }) }
        refreshPreview()
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
    /**
     * Edits the selected mask. A mask that moves ([ColourMask.keys]) is edited as it is at the
     * playhead, and a change of its place becomes a key there.
     */
    fun updateMask(transform: (ColourMask) -> ColourMask) = editSelectedMask { mask ->
        if (mask.keys.isEmpty()) return@editSelectedMask transform(mask)
        val t = playheadFileMs
        val shown = mask.at(t)
        val edited = transform(shown)
        val kept = edited.copy(
            centerX = mask.centerX, centerY = mask.centerY, angleDegrees = mask.angleDegrees,
            sizeX = mask.sizeX, sizeY = mask.sizeY, keys = mask.keys,
        )
        val moved = edited.centerX != shown.centerX || edited.centerY != shown.centerY ||
            edited.angleDegrees != shown.angleDegrees || edited.sizeX != shown.sizeX || edited.sizeY != shown.sizeY
        if (moved) kept.placedAt(t, edited.centerX, edited.centerY, edited.angleDegrees, edited.sizeX, edited.sizeY) else kept
    }

    /**
     * Two fingers on the selected mask: moved by ([dx], [dy]) of the picture, scaled by [zoom]
     * (an oval's size, a line's soft edge) and turned by [degrees].
     */
    fun transformMask(dx: Float, dy: Float, zoom: Float, degrees: Float) = updateMask { m ->
        val turned = m.angleDegrees + degrees
        m.copy(
            centerX = (m.centerX + dx).coerceIn(-0.5f, 1.5f),
            centerY = (m.centerY + dy).coerceIn(-0.5f, 1.5f),
            // An oval turned by half a turn is the same oval; a line would swap its sides, so it stops.
            angleDegrees = if (m.kind == MaskKind.ELLIPSE) ((turned + 90f) % 180f + 180f) % 180f - 90f else turned.coerceIn(-90f, 90f),
            sizeX = if (m.kind != MaskKind.LINEAR) (m.sizeX * zoom).coerceIn(0.05f, if (m.kind == MaskKind.BRUSH) 5f else 2f) else m.sizeX,
            sizeY = if (m.kind != MaskKind.LINEAR) (m.sizeY * zoom).coerceIn(0.05f, if (m.kind == MaskKind.BRUSH) 5f else 2f) else m.sizeY,
            softness = if (m.kind == MaskKind.LINEAR) (m.softness * zoom).coerceIn(0.02f, 1f) else m.softness,
        )
    }

    fun updateBrush(transform: (BrushSettings) -> BrushSettings) = _state.update { it.copy(brush = transform(it.brush)) }

    fun notePictureHeight(px: Float) = _state.update { if (it.pictureHeightPx == px) it else it.copy(pictureHeightPx = px) }

    /**
     * Paints the selected (brush) mask at a point of the picture (fractions): [phase] 0 starts a
     * stroke, 1 continues it, 2 ends it. Points land where the mask shows now (it may have moved).
     * Only the strokes change, so no colour table is computed again.
     */
    fun brushStroke(phase: Int, x: Float, y: Float, grab: (Int) -> android.graphics.Bitmap?) {
        val s = _state.value
        val index = s.selectedClip ?: return
        val clip = s.selected ?: return
        val mask = clip.masks.getOrNull(s.selectedMask)?.takeIf { it.kind == MaskKind.BRUSH } ?: return
        val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
        val placed = mask.at(playheadFileMs)
        // 3: dropped (a second finger came: the view zooms instead).
        if (phase == 3) {
            _state.update { it.copy(paintPath = emptyList(), lassoPath = emptyList()) }
            return
        }
        if (s.brush.mode == BrushMode.SELECT) {
            selectStroke(phase, x, y, grab, clip, index, mask, placed, aspect)
            return
        }
        // While the finger paints, the stroke shows as drawn (on the picture, at once); the mask
        // itself is drawn again once, when the finger lifts.
        when (phase) {
            0 -> _state.update { it.copy(paintPath = listOf(x, y)) }
            1 -> _state.update { st ->
                val p = st.paintPath
                // Points closer than a fraction of the brush add nothing but work.
                if (p.size >= 2 && kotlin.math.hypot((x - p[p.size - 2]) * aspect, y - p.last()) < st.brush.radius * 0.15f) st else st.copy(paintPath = p + x + y)
            }
            else -> {
                val path = s.paintPath
                _state.update { it.copy(paintPath = emptyList()) }
                if (path.size < 2) return
                val b = s.brush
                val points = path.chunked(2).flatMap { (px, py) -> app.headsup.core.color.BrushMask.unmove(px, py, placed, aspect).toList() }
                // Radius in the strokes' own frame: as big as it looks on the mask now.
                val radius = b.radius / placed.sizeX.coerceAtLeast(0.05f)
                val stroke = app.headsup.core.color.BrushStroke(points, radius, b.flow, b.mode == BrushMode.ERASE)
                val m = s.selectedMask
                _state.update { st ->
                    st.copy(clips = st.clips.mapIndexed { i, c -> if (i == index) c.copy(masks = c.masks.mapIndexed { j, mm -> if (j == m) mm.copy(strokes = mm.strokes + stroke) else mm }) else c })
                }
            }
        }
    }

    /** The picture taken when an outline starts, for finding the object in it. */
    private var lassoReference: String? = null

    /**
     * Drawing an outline around an object: shown as drawn, and when the finger lifts the object
     * inside is found ([app.headsup.core.color.BrushMask.select]) and added to the mask.
     */
    private fun selectStroke(
        phase: Int, x: Float, y: Float, grab: (Int) -> android.graphics.Bitmap?,
        clip: ClipState, index: Int, mask: ColourMask, placed: ColourMask, aspect: Float,
    ) {
        when (phase) {
            0 -> {
                lassoReference = brushReference(clip, placed, grab)
                _state.update { it.copy(lassoPath = listOf(x, y)) }
            }
            1 -> _state.update { st ->
                val p = st.lassoPath
                if (p.size >= 2 && kotlin.math.hypot((x - p[p.size - 2]) * aspect, y - p.last()) < 0.004f) st else st.copy(lassoPath = p + x + y)
            }
            else -> {
                val path = _state.value.lassoPath
                _state.update { it.copy(lassoPath = emptyList()) }
                val xs = path.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = path.filterIndexed { i, _ -> i % 2 == 1 }
                val tap = path.size < 6 || maxOf(xs.max() - xs.min(), ys.max() - ys.min()) < 0.02f
                if (tap && path.size >= 2) {
                    selectObjectAt(path[0], path[1], grab, clip, index, placed)
                    return
                }
                if (path.size < 6) return
                val points = path.chunked(2).flatMap { (px, py) -> app.headsup.core.color.BrushMask.unmove(px, py, placed, aspect).toList() }
                val stroke = app.headsup.core.color.BrushStroke(points, radius = 0.01f, flow = 1f, reference = lassoReference, lasso = true)
                val m = _state.value.selectedMask
                _state.update { st ->
                    st.copy(clips = st.clips.mapIndexed { i, c -> if (i == index) c.copy(masks = c.masks.mapIndexed { j, mm -> if (j == m) mm.copy(strokes = mm.strokes + stroke) else mm }) else c })
                }
            }
        }
    }

    /**
     * A tap in Select: the object under the finger is found ([app.headsup.color.ObjectSelector]) in
     * the picture as it is now and added to the mask.
     */
    private fun selectObjectAt(x: Float, y: Float, grab: (Int) -> android.graphics.Bitmap?, clip: ClipState, index: Int, placed: ColourMask) {
        val picture = grab(512) ?: return
        val placement = MaskKey.of(placed, 0L)
        val m = _state.value.selectedMask
        viewModelScope.launch {
            _state.update { it.copy(busy = true, loadingMessage = "Finding the object", loadingProgress = null) }
            try {
                val found = withContext(Dispatchers.Default) {
                    runCatching { app.headsup.color.ObjectSelector.select(getApplication(), picture, x, y) }.getOrNull()
                }
                if (found == null) {
                    _state.update { it.copy(messages = it.messages + "No object found there: tap its middle, or draw round it.") }
                    return@launch
                }
                val id = app.headsup.color.BrushReferences.create(getApplication(), picture.width, picture.height, found, placement)
                val stroke = app.headsup.core.color.BrushStroke(listOf(x, y), radius = 0.01f, flow = 1f, reference = id, segment = true)
                _state.update { st ->
                    st.copy(clips = st.clips.mapIndexed { i, c -> if (i == index && c.id == clip.id) c.copy(masks = c.masks.mapIndexed { j, mm -> if (j == m) mm.copy(strokes = mm.strokes + stroke) else mm }) else c })
                }
            } finally {
                _state.update { it.copy(busy = false, loadingMessage = null) }
            }
        }
    }

    /** The picture edge-finding strokes are painted on: the last one while the frame and mask stay, else a new one. */
    private var lastReference: Triple<Long, Long, app.headsup.core.color.MaskKey>? = null
    private var lastReferenceId: String? = null

    private fun brushReference(clip: ClipState, placed: ColourMask, grab: (Int) -> android.graphics.Bitmap?): String? {
        val placement = MaskKey.of(placed, 0L)
        val key = Triple(clip.id, if (clip.meta.isPhoto) 0L else playheadFileMs, placement)
        if (key == lastReference) lastReferenceId?.let { return it }
        val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
        val bitmap = grab(app.headsup.color.BrushAtlas.slotSize(aspect).first) ?: return null
        // Not recycled: for a photo it can be the preview's own picture.
        val id = app.headsup.color.BrushReferences.create(getApplication(), bitmap, placement)
        lastReference = key
        lastReferenceId = id
        return id
    }

    /** Wipes the selected brush mask's strokes. */
    fun clearBrush() = editSelectedMask { it.copy(strokes = emptyList()) }

    private fun editSelectedMask(transform: (ColourMask) -> ColourMask) {
        val s = _state.value
        val index = s.selectedClip ?: return
        val m = s.selectedMask
        updateClip(index) { c -> c.copy(masks = c.masks.mapIndexed { i, mask -> if (i == m) transform(mask) else mask }) }
    }

    /** The playhead in the selected clip's file, where mask keys are set and tracking starts. */
    private var playheadFileMs = 0L

    /** Called with the preview's position in the clip (from its cut). */
    fun notePlayhead(clipMs: Long) {
        playheadFileMs = (_state.value.selected?.trimStartMs ?: 0L) + clipMs
    }

    /** [mask] as it is at the playhead (for the shape rulers). */
    fun maskAtPlayhead(mask: ColourMask): ColourMask = mask.at(playheadFileMs)

    /** A key at the playhead with the mask where it is now; the first one makes the mask move. */
    fun addMaskKey() = editSelectedMask { m -> m.copy(keys = MaskKey.upsert(m.keys, MaskKey.of(m.at(playheadFileMs), playheadFileMs))) }

    /** Removes the key at [timeMs] (file time); the last one leaves the mask where it was. */
    fun removeMaskKey(timeMs: Long) = editSelectedMask { m ->
        val key = m.keys.firstOrNull { it.timeMs == timeMs } ?: return@editSelectedMask m
        val rest = m.keys - key
        if (rest.isEmpty()) m.at(timeMs).copy(keys = emptyList()) else m.copy(keys = rest)
    }

    /** Stops the mask moving; it stays where it is at the playhead. */
    fun clearMaskKeys() = editSelectedMask { m -> m.at(playheadFileMs).copy(keys = emptyList()) }

    /** Which way [trackMask] follows the picture from the playhead. */
    enum class TrackDirection { BOTH, FORWARD, BACKWARD }

    /**
     * Tracks the selected mask from the playhead towards the end, the start or both ends of the
     * cut: frames of that span are read once (every 66 ms or so), then the mask follows the picture.
     * Keys found replace the old ones only in the tracked span, so a track can be continued from a
     * key placed by hand; where tracking is lost, the mask holds its last place.
     */
    fun trackMask(direction: TrackDirection = TrackDirection.BOTH) {
        val s = _state.value
        if (s.busy) return
        val clip = s.selected ?: return
        if (clip.meta.isPhoto) return
        val maskIndex = s.selectedMask
        val mask = clip.masks.getOrNull(maskIndex) ?: return
        val clipId = clip.id
        val t0 = playheadFileMs.coerceIn(clip.trimStartMs, clip.trimEndMs)
        val start = mask.at(t0)
        _state.update { it.copy(busy = true, loadingMessage = "Reading frames", loadingProgress = 0f) }
        loadJob = viewModelScope.launch {
            try {
                val from = if (direction == TrackDirection.FORWARD) t0 else clip.trimStartMs
                val to = if (direction == TrackDirection.BACKWARD) t0 else clip.trimEndMs
                val frames = withContext(Dispatchers.Default) {
                    TrackingFrames.read(getApplication(), _state.value.previewSource(clip), from, to, maxOf(66L, (to - from) / 200)) { p ->
                        _state.update { it.copy(loadingMessage = "Reading frames", loadingProgress = p * 0.6f) }
                    }
                }
                val result = withContext(Dispatchers.Default) {
                    val first = frames.indices.minByOrNull { kotlin.math.abs(frames[it].first - t0) } ?: return@withContext null
                    fun follow(range: IntProgression): Pair<List<MaskKey>, Long?> {
                        // A painted mask is followed where it is painted.
                        val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
                        val anchor = if (start.kind == MaskKind.BRUSH) app.headsup.core.color.BrushMask.centreOf(start.strokes, MaskKey.of(start, 0L), aspect) else null
                        val spread = if (start.kind == MaskKind.BRUSH) app.headsup.core.color.BrushMask.extentOf(start.strokes, MaskKey.of(start, 0L), aspect) else null
                        val tracker = MaskTracker(start, frames[first].second, frames[first].first, anchor, spread)
                        val keys = mutableListOf<MaskKey>()
                        for (i in range) {
                            val (ms, frame) = frames[i]
                            // Carried out of the picture with what it follows: not lost, just gone.
                            val key = tracker.next(frame, ms) ?: return keys to ms.takeIf { !tracker.leftFrame }
                            keys += key
                        }
                        return keys to null
                    }
                    _state.update { it.copy(loadingMessage = "Tracking", loadingProgress = 0.7f) }
                    val (ahead, lostAhead) = if (direction != TrackDirection.BACKWARD) follow(first + 1 until frames.size) else emptyList<MaskKey>() to null
                    _state.update { it.copy(loadingProgress = 0.85f) }
                    val (behind, lostBehind) = if (direction != TrackDirection.FORWARD) follow(first - 1 downTo 0) else emptyList<MaskKey>() to null
                    // Only the keys the path needs, so they can be adjusted one by one.
                    Triple(MaskKey.simplify(behind.reversed() + MaskKey.of(start, frames[first].first) + ahead), lostAhead, lostBehind)
                }
                if (result == null) {
                    _state.update { it.copy(messages = it.messages + "Could not read the clip's frames for tracking.") }
                } else {
                    val (keys, lostAhead, lostBehind) = result
                    val spanFrom = keys.first().timeMs
                    val spanTo = keys.last().timeMs
                    _state.update { st ->
                        st.copy(
                            clips = st.clips.map { c ->
                                if (c.id != clipId) c else c.copy(
                                    masks = c.masks.mapIndexed { i, m ->
                                        if (i != maskIndex) m else m.copy(keys = (m.keys.filter { it.timeMs < spanFrom || it.timeMs > spanTo } + keys).sortedBy { it.timeMs })
                                    },
                                )
                            },
                            messages = st.messages + listOfNotNull(
                                "Mask tracked ${Formats.duration(spanFrom - clip.trimStartMs)}–${Formats.duration(spanTo - clip.trimStartMs)} (${keys.size} keys).",
                                lostAhead?.let { "Lost at ${Formats.duration(it - clip.trimStartMs)}: place the mask there and Track ▶ to continue." },
                                lostBehind?.let { "Lost going back at ${Formats.duration(it - clip.trimStartMs)}: place the mask there and ◀ Track to continue." },
                            ),
                        )
                    }
                    refreshPreview()
                }
            } finally {
                _state.update { it.copy(busy = false, loadingMessage = null, loadingProgress = null) }
            }
        }
    }

    /** Blend from the previous clip's look over [lengthMs] at the start of the clip at [index]; 0 for a cut. */
    fun setTransition(index: Int, lengthMs: Long) = updateClip(index) { it.copy(transitionMs = lengthMs.coerceAtLeast(0L)) }

    /**
     * Changes the selected clip's corrections (they also become the default for clips added
     * later), or the global node's.
     */
    fun updateGrade(transform: (ColorGrade) -> ColorGrade) {
        val now = _state.value
        if (now.target == GradeTarget.GLOBAL || now.target == GradeTarget.GROUP) {
            // A sync: the values changed on the selected clip are set on every clip (or the group's),
            // so each clip shows what it really has. Changes for all clips also go to clips added later.
            val selected = now.selected ?: return
            val before = selected.grade
            val after = transform(before)
            _state.update { s ->
                s.copy(
                    clips = s.clips.map { c -> if (s.syncsTo(c)) c.copy(grade = c.grade.synced(before, after)) else c },
                    defaultGrade = if (s.target == GradeTarget.GLOBAL) s.defaultGrade.synced(before, after) else s.defaultGrade,
                )
            }
            gradeStore.saveGrade(_state.value.defaultGrade)
            refreshPreview()
            return
        }
        if (now.target == GradeTarget.MASK) {
            updateMask { it.copy(grade = transform(it.grade)) }
            return
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
                    val wide = clip?.wideGamut == true
                    val cube = if (graded != null) bake(s.lutFor(graded), s.appliedGrade(graded), wide) else if (wide) bake(null, ColorGrade(), true) else null
                    val maskCubes = graded?.let { c -> s.maskedGrades(c).map { bakeAlways(s.lutFor(c), it, wide) } }.orEmpty()
                    val fromCube = graded?.let(s::transitionSource)?.let { prev -> bakeLook(s, prev) }
                    val neighbours = s.selectedClip?.let { i -> listOfNotNull(s.clips.getOrNull(i - 1), s.clips.getOrNull(i + 1)) }.orEmpty()
                        .filter { !it.meta.isPhoto }
                        .associate { n -> n.id to neighbourLook(s, n) }
                    _state.update { it.copy(previewCube = cube, previewMaskCubes = maskCubes, previewFromCube = fromCube, previewNeighbourCubes = neighbours) }
                    analyseScope()
                }
            } finally {
                previewRunning = false
            }
        }
    }

    private val lookCache = HashMap<Triple<String?, ColorGrade, Boolean>, Array<Array<IntArray>>?>()

    /** [c]'s look as the preview shows it when selected (without masks), kept for reuse. */
    private suspend fun neighbourLook(s: UiState, c: ClipState): Array<Array<IntArray>>? {
        val key = if (c.colorGrade) Triple(s.lutFor(c), s.appliedGrade(c), c.wideGamut) else Triple(null, ColorGrade(), c.wideGamut)
        lookCache[key]?.let { return it }
        if (key in lookCache) return null
        if (lookCache.size > 32) lookCache.clear()
        return bake(key.first, key.second, key.third).also { lookCache[key] = it }
    }

    /** Null when nothing would change the colours. */
    private suspend fun bake(lutName: String?, grade: ColorGrade, wide: Boolean = false): Array<Array<IntArray>>? {
        val lut = loadLut(lutName)
        if (lut == null && grade.isNeutralColour && !wide) return null
        return withContext(Dispatchers.Default) { LutBaker.bake(lut, grade, wideGamut = wide) }
    }

    /** Table for [clip]'s whole look outside masks; untouched colours when its colour is off. */
    private suspend fun bakeLook(s: UiState, clip: ClipState): Array<Array<IntArray>> =
        if (clip.colorGrade) bakeAlways(s.lutFor(clip), s.appliedGrade(clip), clip.wideGamut) else bakeAlways(null, ColorGrade(), clip.wideGamut)

    private suspend fun bakeAlways(lutName: String?, grade: ColorGrade, wide: Boolean = false): Array<Array<IntArray>> {
        val lut = loadLut(lutName)
        return withContext(Dispatchers.Default) { LutBaker.bake(lut, grade, wideGamut = wide) }
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

    /** A speed ramp for the clip (empty: steady); sound is muted by default where it gets very slow. */
    fun setSpeedCurve(index: Int, curve: List<Float>) = updateClip(index) { c ->
        if (c.meta.isPhoto) c else c.copy(
            speedCurve = curve,
            muteChangedSpeed = if (curve.isEmpty()) c.speed < 0.5f else SpeedCurve.points(curve).any { it.second * c.speed < 0.5f },
        )
    }

    /** Fades, kept within the clip's length on the timeline. */
    fun setFades(index: Int, fadeInMs: Long, fadeOutMs: Long) = updateClip(index) {
        val max = it.timelineDurationMs
        it.copy(fadeInMs = fadeInMs.coerceIn(0L, max), fadeOutMs = fadeOutMs.coerceIn(0L, max))
    }

    fun exportVideos() = enqueue(ExportKind.VIDEO, state.value.clips.indices.toList())

    fun exportOverlays() = enqueue(ExportKind.PNG_SEQUENCE, state.value.clips.indices.toList())

    /** Each photo on the timeline as a JPEG with its colours, framing, effects, dashboard and texts. */
    /** Which photos an export takes. */
    enum class PhotoSet { ALL, CURRENT, PICKED }

    fun exportPhotos(which: PhotoSet = PhotoSet.ALL) {
        val s = state.value.copy(compareGrade = null)
        val photos = s.clips.withIndex().filter {
            it.value.meta.isPhoto && when (which) {
                PhotoSet.ALL -> true
                PhotoSet.CURRENT -> it.index == s.selectedClip
                PhotoSet.PICKED -> it.value.picked
            }
        }
        if (photos.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT < 33) {
            _state.update { it.copy(messages = it.messages + "Photo export needs Android 13 or newer.") }
            return
        }
        loadJob = viewModelScope.launch {
            val saved = mutableListOf<String>()
            val failed = mutableListOf<String>()
            try {
                for ((n, entry) in photos.withIndex()) {
                    val (i, clip) = entry
                    _state.update { it.copy(busy = true, loadingMessage = "Exporting photo ${n + 1} of ${photos.size}", loadingProgress = n.toFloat() / photos.size) }
                    val graded = clip.colorGrade
                    val lut = s.lutFor(clip)
                    val wide = clip.wideGamut
                    val cube = if (graded) bake(lut, s.appliedGrade(clip), wide) else if (wide) bake(null, ColorGrade(), true) else null
                    val maskCubes = if (graded) s.maskedGrades(clip).map { bakeAlways(lut, it, wide) } else emptyList()
                    val start = s.clips.take(i).sumOf { it.timelineDurationMs }
                    val dashboard = clip.telemetryUtc(0L)?.let { utc ->
                        val track = clip.sync?.let { s.activities.getOrNull(it.trackIndex) }?.track ?: return@let null
                        val frame = app.headsup.core.track.TelemetrySampler(track).frameAt(utc) ?: return@let null
                        val zone = track.utcOffsetSeconds ?: java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(utc)).totalSeconds
                        app.headsup.render.DashboardData(track, frame, utc, zone)
                    }
                    val request = app.headsup.export.PhotoRequest(
                        uri = clip.meta.uri, name = clip.meta.name, cube = cube,
                        masks = if (graded) clip.masks.map { it.at(clip.trimStartMs) } else emptyList(), maskCubes = maskCubes,
                        effects = clip.effects, vignette = if (graded) s.appliedGrade(clip).vignette else 0f,
                        presence = if (graded) s.appliedGrade(clip).presence else app.headsup.core.color.Presence.NONE,
                        reframe = s.framing(clip), style = s.style, dashboard = dashboard,
                        // Texts as they are in the middle of the photo's time on the timeline (after any fade in).
                        // Data fields filled in for this photo.
                        texts = withContext(Dispatchers.IO) { textValues(s, clip, 0L) }.let { values ->
                            s.texts.filter { it.showsOn(clip.id, clip.groupId) }.map { t -> t.copy(text = app.headsup.core.edit.TextFields.fill(t.text, values)) }
                        },
                        textMs = start + clip.timelineDurationMs / 2,
                        routeMap = s.routeMap,
                        mapTrack = if (s.routeMap.enabled) mapTrack(s, clip) else null,
                        mapMarker = if (s.routeMap.enabled) withContext(Dispatchers.IO) { mapMarker(s, clip, 0L) } else null,
                        longSide = s.exportSettings.photoLongSide, quality = s.exportSettings.photoQuality,
                        format = s.exportSettings.photoFormat,
                        raw = clip.meta.isRaw,
                        rawDevelop = clip.rawDevelop,
                    )
                    runCatching {
                        withContext(Dispatchers.Default) {
                            app.headsup.export.PhotoExporter.export(getApplication(), request) { p ->
                                _state.update { it.copy(loadingProgress = (n + p.coerceIn(0f, 1f)) / photos.size) }
                            }
                        }
                    }
                        .onSuccess { saved += clip.meta.name }
                        .onFailure { failed += "${clip.meta.name}: ${it.message ?: it.javaClass.simpleName}" }
                }
            } finally {
                val message = buildString {
                    if (saved.isNotEmpty()) append("Saved ${saved.size} photo(s) to ${app.headsup.export.MediaStoreOutput.VIDEO_FOLDER}.")
                    if (failed.isNotEmpty()) append(" Failed: " + failed.joinToString("; "))
                }
                _state.update { it.copy(busy = false, loadingMessage = null, loadingProgress = null, messages = it.messages + message.trim()) }
            }
        }
    }

    /** Why a 10-bit (HLG) export can't be made of this timeline, or null when it can. */
    fun tenBitBlocker(s: UiState = state.value): String? = when {
        !HdrRoute.deviceSupported -> "This phone's encoder can't do 10-bit HDR editing."
        s.clips.any { it.meta.isPhoto } -> "Photos can't go into a 10-bit export yet."
        s.clips.any { it.meta.format?.transfer == "PQ" } -> "HDR10/HDR10+ (PQ) clips aren't supported yet; HLG and log are."
        else -> null
    }


    fun cancelExports() = exportManager.cancelAll()

    fun clearFinishedExports() = exportManager.clearFinished()

    private fun enqueue(kind: ExportKind, indices: List<Int>) {
        val s = state.value.copy(compareGrade = null)
        viewModelScope.launch {
            val cubes = HashMap<Pair<ClipLook?, Boolean>, Array<Array<IntArray>>?>()
            val requests = indices.mapNotNull { i ->
                val clip = s.clips.getOrNull(i) ?: return@mapNotNull null
                clip to (exportRequest(kind, s, i, clip, cubes) ?: return@mapNotNull null)
            }.flatMap { (clip, r) -> rampPieces(clip, r) }
            if (requests.isEmpty()) {
                val why = if (kind == ExportKind.PNG_SEQUENCE) "the dashboard needs activity data, and no clip is matched to an activity." else "no clips."
                _state.update { it.copy(messages = it.messages + "Nothing to export: $why") }
            } else {
                exportManager.enqueue(jobsFor(kind, requests, s))
            }
        }
    }

    /**
     * A ramped clip as pieces of steady speed, one after another in the export (which changes speed
     * in steps): each its own cut, speed, place on the timeline and watch-data start; fades at the ends.
     */
    private fun rampPieces(clip: ClipState, r: ExportRequest): List<ExportRequest> {
        if (clip.speedCurve.isEmpty() || clip.meta.isPhoto || r.kind != ExportKind.VIDEO) return listOf(r)
        val pieces = SpeedCurve.pieces(clip.durationMs, clip.speed, clip.speedCurve)
        return pieces.mapIndexed { k, (from, to, speed) ->
            r.copy(
                durationMs = to - from,
                trimStartMs = clip.trimStartMs + from,
                trimEndMs = clip.trimStartMs + to,
                speed = speed,
                fadeInMs = if (k == 0) r.fadeInMs else 0L,
                fadeOutMs = if (k == pieces.lastIndex) r.fadeOutMs else 0L,
                timelineStartMs = r.timelineStartMs + clip.timelineOfSource(from),
                telemetry = r.telemetry?.let { Telemetry(it.track, it.startUtcMs + from) },
                grade = r.grade?.let { g ->
                    GradeSetup(g.cube, g.vignette, g.masks, g.maskCubes, g.fromCube, g.transitionMs, g.presence, g.warp, timeOffsetMs = from)
                },
                pieceOf = clip.id,
            )
        }
    }

    private suspend fun exportRequest(
        kind: ExportKind, s: UiState, i: Int, clip: ClipState,
        cubes: HashMap<Pair<ClipLook?, Boolean>, Array<Array<IntArray>>?>,
    ): ExportRequest? {
        return run {
                // The dashboard is one effect among others: without watch data the video still exports.
                val telemetry = clip.sync?.let { sync ->
                    s.activities.getOrNull(sync.trackIndex)?.let { Telemetry(it.track, sync.startUtcMs + clip.trimStartMs + clip.manualOffsetMs) }
                }
                if (kind == ExportKind.PNG_SEQUENCE && telemetry == null) return null
                val graded = kind != ExportKind.PNG_SEQUENCE && clip.colorGrade
                val applied = s.appliedGrade(clip)
                val lutName = s.lutFor(clip)
                val wide = clip.wideGamut
                val cube = if (graded) {
                    cubes.getOrPut(ClipLook(applied, EffectSettings(), lutName) to wide) { bake(lutName, applied, wide) }
                } else if (wide && kind != ExportKind.PNG_SEQUENCE) {
                    cubes.getOrPut(null to true) { bake(null, ColorGrade(), true) }
                } else null
                val masks = if (graded) clip.masks else emptyList()
                val maskCubes = if (graded) s.maskedGrades(clip).map { bakeAlways(lutName, it, wide) } else emptyList()
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
                    grade = s.framing(clip).warp.let { warp ->
                        if (graded) GradeSetup(cube, applied.vignette, masks, maskCubes, fromCube, clip.transitionMs, applied.presence, warp)
                        else if (cube != null || warp != null) GradeSetup(cube, 0f, warp = warp) else null
                    },
                    reframe = s.framing(clip),
                    speed = clip.speed,
                    isPhoto = clip.meta.isPhoto,
                    muteChangedSpeed = clip.muteChangedSpeed,
                    fadeInMs = clip.fadeInMs,
                    fadeOutMs = clip.fadeOutMs,
                    soundLevel = clip.soundLevel,
                    timelineStartMs = s.clips.take(i).sumOf { it.timelineDurationMs },
                    texts = if (kind == ExportKind.VIDEO) {
                        val start = s.clips.take(i).sumOf { it.timelineDurationMs }
                        s.texts.filter { it.startMs < start + clip.timelineDurationMs && it.endMs > start }
                    } else emptyList(),
                    textValues = if (kind == ExportKind.VIDEO && s.texts.isNotEmpty()) withContext(Dispatchers.IO) { staticTextValues(s, clip) } else emptyMap(),
                    routeMap = if (kind == ExportKind.VIDEO) s.routeMap else app.headsup.render.RouteMap(),
                    mapTrack = if (kind == ExportKind.VIDEO && s.routeMap.enabled) mapTrack(s, clip) else null,
                    // A photo's place is fixed; a video's follows the watch frame by frame.
                    mapMarker = if (kind == ExportKind.VIDEO && s.routeMap.enabled && clip.meta.isPhoto) withContext(Dispatchers.IO) { mapMarker(s, clip, 0L) } else null,
                )
        }
    }

    /** Whether the export will be 10-bit: chosen, or forced by HLG clips (where they are brought to SDR). */
    private fun exportsTenBit(s: UiState): Boolean =
        (s.exportSettings.tenBit || s.clips.any { it.meta.format?.transfer == "HLG" }) && tenBitBlocker(s) == null

    /** Output size, bit rate and format for a frame of [frameW]×[frameH] cut from a [srcW]×[srcH] source. */
    private fun outputSpec(s: UiState, frameW: Int, frameH: Int, srcW: Int, srcH: Int, srcBitrate: Int?, fileName: String): OutputSpec {
        val settings = s.exportSettings
        // The chosen resolution is the frame's short side (1080p: 1920×1080 or 1080×1920).
        val short = settings.height?.toFloat() ?: minOf(frameW, frameH).toFloat()
        val (w, h) = if (frameW >= frameH) short * frameW / frameH to short else short to short * frameH / frameW
        // Bit rate follows the source's per pixel, times the chosen quality.
        val sourceRate = (srcBitrate ?: 60_000_000).toDouble()
        val pixels = (w * h) / (srcW.toDouble() * srcH).coerceAtLeast(1.0)
        val bitrate = (sourceRate * pixels * settings.bitrateFactor).toLong().coerceIn(8_000_000L, 200_000_000L).toInt()
        return OutputSpec(
            OutputSpec.even(w), OutputSpec.even(h), s.effectiveTimelineFps, settings.codec, bitrate, fileName,
            exportsTenBit(s), settings.hlg, settings.hdrWhite,
        )
    }

    /** What the export would produce now (from the first clip), shown at the top of the export panel. */
    fun outputPreview(s: UiState): OutputSpec? {
        val c = s.clips.firstOrNull() ?: return null
        val (fw, fh) = s.framing(c).outputSize(c.meta.width.toFloat(), c.meta.height.toFloat())
        return outputSpec(s, fw.toInt() / 2 * 2, fh.toInt() / 2 * 2, c.meta.width, c.meta.height, c.meta.bitrate, "")
    }

    /**
     * Turns clips into export jobs: the whole timeline as one file (frame size and shape from the
     * first clip), or one file per clip; PNG overlays always per clip.
     */
    private fun jobsFor(kind: ExportKind, requests: List<ExportRequest>, s: UiState): List<JobSpec> {
        val settings = s.exportSettings
        fun spec(parts: List<ExportRequest>, fileName: String): OutputSpec {
            val first = parts.first()
            return outputSpec(s, first.frameWidth, first.frameHeight, first.width, first.height, first.sourceBitrate, fileName)
        }
        return when {
            kind == ExportKind.PNG_SEQUENCE -> requests.map { JobSpec(kind, it.name, listOf(it), null) }
            kind == ExportKind.VIDEO && settings.wholeTimeline -> {
                val name = s.projectName.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                listOf(JobSpec(kind, s.projectName, requests, spec(requests, "${name}_sweetspot.mp4"), s.audioClips, s.clipSound))
            }
            else -> {
                // One file per clip: a ramped clip's pieces go into the same one.
                val files = mutableListOf<MutableList<ExportRequest>>()
                for (r in requests) {
                    val last = files.lastOrNull()
                    if (r.pieceOf != null && last != null && last.first().pieceOf == r.pieceOf) last += r else files += mutableListOf(r)
                }
                files.map { parts -> JobSpec(kind, parts.first().name, parts, spec(parts, "${parts.first().baseName}_sweetspot.mp4"), clipSound = s.clipSound) }
            }
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

/** What a finger does on a painted mask: paint, erase, outline an object to select it, or move the mask. */
enum class BrushMode(val label: String) { PAINT("Paint"), ERASE("Erase"), SELECT("Select"), MOVE("Move") }

/** The brush: its radius (of the picture's height) and flow, 0..1. */
data class BrushSettings(
    val mode: BrushMode = BrushMode.PAINT,
    val radius: Float = 0.06f,
    val flow: Float = 1f,
)
