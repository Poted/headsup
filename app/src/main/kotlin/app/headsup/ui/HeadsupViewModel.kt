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
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
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
import app.headsup.project.SavedReverse
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId

data class LoadedActivity(val name: String, val track: ActivityTrack, val uri: Uri? = null)

/** Where a reversed clip came from: the file and the part of it ([startMs]..[endMs]) played backwards. */
data class ReversedSource(val original: ClipMetadata, val startMs: Long, val endMs: Long)

/**
 * The description of a reversed copy ([uri], [durationMs] long) of part of [original]: the
 * original's own (its name, shape, colours and times), so the clip is still that shot.
 */
internal fun reversedMetaOf(original: ClipMetadata, uri: Uri, durationMs: Long): ClipMetadata =
    original.copy(uri = uri, durationMs = durationMs, info = original.info.copy(durationMs = durationMs), bytes = null)

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
    /** A photo's Clean up: patches over what was removed, in the order made. */
    val retouch: List<app.headsup.core.retouch.RetouchPatch> = emptyList(),
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
    /** Moments flagged in the file (file time): to find again, cut at, work on. */
    val flags: List<Long> = emptyList(),
    /** Played backwards: [meta] is then the reversed copy of [ReversedSource.original]'s cut. */
    val reversed: ReversedSource? = null,
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
/** A moment of the edit kept to come back to: its number and when it was set. */
data class Milestone(val id: Long, val number: Int, val time: String)

/** A long job running in the background: what it is, how far it is, or waiting for the one before. */
data class BackgroundWork(
    val id: Long,
    val label: String,
    val clipId: Long? = null,
    /** The mask it tracks; null for work on the whole clip. */
    val maskIndex: Int? = null,
    val progress: Float? = null,
    val waiting: Boolean = true,
)

/** A Fast edit look being tried on clip [clipId]: what it had before, the look and its strength. */
data class FastSession(
    val clipId: Long,
    val baseGrade: ColorGrade,
    val baseEffects: EffectSettings,
    val look: app.headsup.color.FastLook,
    val strength: Float = 1f,
)

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
    /** For a group made alike: each clip's own change (else every clip takes [delta]). */
    val memberDeltas: Map<Long, ColorGrade> = emptyMap(),
    /** For a group made alike: how much of its change each clip takes (0 = left out), on top of [strength]. */
    val memberStrength: Map<Long, Float> = emptyMap(),
    /** What is matched: white balance, saturation, skin. */
    val parts: Set<MatchPart> = MatchPart.entries.toSet(),
)

/** What a shot match changes. */
enum class MatchPart(val label: String) { WHITE_BALANCE("White balance"), SATURATION("Saturation"), SKIN("Skin") }

/** Clips of one group are alike in colour once matched: most of the change applies. */
private const val GROUP_MATCH_STRENGTH = 0.9f

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
    /** The masks of the clip blended in from (as at its end, [previewFromMaskMs]) and their tables. */
    val previewFromMasks: List<ColourMask> = emptyList(),
    val previewFromMaskCubes: List<Array<Array<IntArray>>> = emptyList(),
    val previewFromMaskMs: Long = 0L,
    /** Looks of the clips before and after the selected one (null: untouched), for the preview at a cut. */
    val previewNeighbourCubes: Map<Long, Array<Array<IntArray>>?> = emptyMap(),
    /** The neighbours' blends in: the look of the clip before each (null when it has none). */
    val previewNeighbourFrom: Map<Long, Array<Array<IntArray>>?> = emptyMap(),
    /** The clip the preview's tables (cube, masks, blend in) were made for. */
    val previewCubesFor: Long? = null,
    /** Mask of the selected clip that the Masks tab edits. */
    val selectedMask: Int = 0,
    /** The brush for painted masks. */
    val brush: BrushSettings = BrushSettings(),
    /** The selected mask shown filled in red (otherwise only its outline). */
    val maskOverlay: Boolean = false,
    /** A mask is being made to remove what it covers from the photo ([HeadsupViewModel.startRemoval]). */
    val removing: Boolean = false,
    /** The next tap on the picture picks the selected mask's colour ([HeadsupViewModel.pickMaskColour]). */
    val pickingColour: Boolean = false,
    /** When the brush's size (softness, strength) was last set: it shows on the picture for a moment. */
    val brushSizedAt: Long = 0L,
    /** The clip measured for looks ([HeadsupViewModel.analyseClip]): its id and what was measured. */
    val lookStats: Pair<Long, app.headsup.core.color.FrameStats>? = null,
    /** A Fast edit look being tried ([HeadsupViewModel.applyFastLook]). */
    val fast: FastSession? = null,
    /** Long jobs running in the background (tracking with AI, reversing). */
    val work: List<BackgroundWork> = emptyList(),
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
    /** What the last undo or redo changed, shown for a moment over the picture, and when. */
    val notice: String? = null,
    val noticeAt: Long = 0L,
    /** Moments of the edit kept to come back to (this session). */
    val milestones: List<Milestone> = emptyList(),
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

    // Declared before init: opening the last project at start clears them.
    /** The edit as it was at each milestone (this session, this project). */
    private val milestoneEdits = HashMap<Long, EditSnapshot>()
    private var nextMilestone = 1L

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
            refreshCleanupLater()
            return
        }
        updateClip(index) { it.copy(rawDevelop = transform(it.rawDevelop)) }
        refreshCleanupLater()
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
        saveHistorySoon()
    }

    private var historySave: kotlinx.coroutines.Job? = null

    /** Keeps the undo history on disk (a moment after it changes), so undo works after the app is closed. */
    private fun saveHistorySoon() {
        historySave?.cancel()
        val s = _state.value
        val name = s.projectName
        val steps = history.toList()
        val index = historyIndex
        val base = historyBase
        historySave = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            withContext(Dispatchers.IO) {
                runCatching {
                    projectStore.saveHistory(name, steps.map { snap -> snap.asState(s).toProject() }, index, base?.asState(s)?.toProject())
                }
            }
        }
    }

    /** [s] with the edit of this snapshot. */
    private fun EditSnapshot.asState(s: UiState) = s.copy(
        activities = activities, clips = clips, groups = groups, globalGrade = globalGrade, style = style, timelineFps = timelineFps,
        frameAspect = frameAspect, exportSettings = exportSettings, audioFiles = audioFiles, audioClips = audioClips, clipSound = clipSound,
        texts = texts, routeMap = routeMap,
    )

    /**
     * The undo history saved with project [name], read again onto the opened project ([opened]):
     * each step built as the project is when opened. The step the edit was at is the project as it
     * opened, so nothing is recorded twice. Files a step uses that are gone leave it out.
     */
    private suspend fun restoreHistory(name: String, opened: UiState, metas: Map<Uri, ClipMetadata?>) {
        val saved = withContext(Dispatchers.IO) { projectStore.loadHistory(name) } ?: return
        if (saved.steps.isEmpty() || saved.index !in saved.steps.indices) return
        val context = getApplication<Application>()
        // Files the history uses that the project no longer does.
        val more = withContext(Dispatchers.IO) {
            (saved.steps + listOfNotNull(saved.base)).flatMap { p -> p.clips.map { it.uri } + p.clips.mapNotNull { it.reversed?.originalUri } }
                .distinct().filter { it !in metas }
                .associateWith { uri -> runCatching { ClipMetadataReader.read(context, uri) }.getOrNull() }
        }
        val all = metas + more
        fun snapshotOf(p: ProjectData): EditSnapshot {
            val activities = opened.activities.filter { a -> a.uri in p.activities }
            return resynced(editOf(opened, p, clipsOf(p, all), activities)).snapshot()
        }
        val steps = withContext(Dispatchers.Default) { saved.steps.map(::snapshotOf).toMutableList() }
        steps[saved.index] = opened.snapshot()
        history.clear()
        history += steps
        historyIndex = saved.index
        historyBase = saved.base?.let { withContext(Dispatchers.Default) { snapshotOf(it) } } ?: steps.first()
        historyStartsAgain = false
        updateHistoryFlags()
        // A step recorded while this was read would otherwise be saved over it.
        saveHistorySoon()
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
        val from = history[historyIndex]
        historyIndex--
        restore(history[historyIndex])
        note("Undone", from, history[historyIndex])
        saveHistorySoon()
    }

    /** Says for a moment what an undo or redo changed. */
    private fun note(verb: String, from: EditSnapshot, to: EditSnapshot) {
        val what = changeBetween(from, to) ?: return
        _state.update { it.copy(notice = "$verb: $what", noticeAt = android.os.SystemClock.uptimeMillis()) }
    }

    /** What differs between two steps of the edit, in a few words (the clip by its place). */
    private fun changeBetween(a: EditSnapshot, b: EditSnapshot): String? {
        if (a.clips.size != b.clips.size) {
            val sameFiles = a.clips.map { it.meta.uri }.toSet() == b.clips.map { it.meta.uri }.toSet()
            return when {
                sameFiles && b.clips.size > a.clips.size -> "split"
                sameFiles -> "join"
                b.clips.size > a.clips.size -> "clip added"
                else -> "clip removed"
            }
        }
        val parts = LinkedHashSet<String>()
        var where: Int? = null
        for (i in a.clips.indices) {
            val x = a.clips[i]
            val y = b.clips[i]
            val before = parts.size
            if (x.meta.uri != y.meta.uri) parts += "order"
            if (x.trimStartMs != y.trimStartMs || x.trimEndMs != y.trimEndMs) parts += "cut"
            if (x.grade != y.grade || x.lutName != y.lutName || x.colorGrade != y.colorGrade || x.rawDevelop != y.rawDevelop) parts += "colour"
            if (x.masks != y.masks) parts += "masks"
            if (x.effects != y.effects) parts += "effects"
            if (x.speed != y.speed || x.speedCurve != y.speedCurve || x.muteChangedSpeed != y.muteChangedSpeed) parts += "speed"
            if (x.reframe != y.reframe) parts += "framing"
            if (x.fadeInMs != y.fadeInMs || x.fadeOutMs != y.fadeOutMs || x.transitionMs != y.transitionMs) parts += "fades"
            if (x.flags != y.flags) parts += "flags"
            if (x.reversed != y.reversed) parts += "reverse"
            if (x.retouch != y.retouch) parts += "removal"
            if (x.groupId != y.groupId) parts += "group"
            if (x.soundLevel != y.soundLevel) parts += "sound"
            if (parts.size > before) where = if (where == null) i else -1
        }
        if (a.groups != b.groups) parts += "groups"
        if (a.texts != b.texts) parts += "texts"
        if (a.audioClips != b.audioClips || a.audioFiles != b.audioFiles || a.clipSound != b.clipSound) parts += "sound"
        if (a.style != b.style) parts += "dashboard"
        if (a.routeMap != b.routeMap) parts += "map"
        if (a.timelineFps != b.timelineFps || a.frameAspect != b.frameAspect) parts += "timeline"
        if (parts.isEmpty()) return null
        val list = parts.take(2).joinToString(", ") + if (parts.size > 2) "…" else ""
        return when (val w = where) {
            null, -1 -> list
            else -> "$list · clip ${w + 1}"
        }
    }

    /**
     * Back to the project as it was when opened, however many steps ago. It is a step of its own,
     * so undo brings the changes back.
     */
    /** Keeps the edit as it is now as a milestone, to come back to with [backToMilestone]. */
    fun addMilestone() {
        val id = nextMilestone++
        milestoneEdits[id] = _state.value.snapshot()
        val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val number = (_state.value.milestones.maxOfOrNull { it.number } ?: 0) + 1
        _state.update { it.copy(milestones = it.milestones + Milestone(id, number, time)) }
    }

    /** Back to milestone [id], as a step of its own (undo returns to where you were). */
    fun backToMilestone(id: Long) {
        val point = milestoneEdits[id] ?: return
        record(_state.value.snapshot())
        restore(point)
        record(point)
    }

    fun removeMilestone(id: Long) {
        milestoneEdits.remove(id)
        _state.update { s -> s.copy(milestones = s.milestones.filter { it.id != id }) }
    }

    /** Milestones belong to the project they were set in. */
    private fun clearMilestones() {
        milestoneEdits.clear()
        _state.update { it.copy(milestones = emptyList()) }
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
                // A reversed clip plays forwards again: the file it came from.
                val meta = c.reversed?.original ?: c.meta
                if (fresh.lastOrNull()?.meta?.uri == meta.uri) continue
                fresh += ClipState(
                    meta = meta, sync = c.sync, id = c.id, manualOffsetMs = c.manualOffsetMs,
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
                fast = null,
                matchSession = null,
                scope = null,
            )
        }
        record(_state.value.snapshot())
        refreshPreview()
    }

    fun redo() {
        if (historyIndex >= history.lastIndex) return
        val from = history[historyIndex]
        historyIndex++
        restore(history[historyIndex])
        note("Redone", from, history[historyIndex])
        saveHistorySoon()
    }

    /**
     * After undo or redo, the clip to show: the one holding the moment of the file the playhead is
     * on (a split undone, a trim brought back), so the picture stays where it was; else the same
     * clip, else the one at the same place.
     */
    private fun keptSelection(s: UiState, clips: List<ClipState>): Int? {
        if (clips.isEmpty()) return null
        val current = s.selected
        if (current != null) {
            val f = playheadFileMs
            val holding = clips.indices.filter { clips[it].meta.uri == current.meta.uri && f >= clips[it].trimStartMs && f < clips[it].trimEndMs }
            holding.firstOrNull { clips[it].id == current.id }?.let { return it }
            holding.firstOrNull()?.let { return it }
            clips.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }?.let { return it }
        }
        return s.selectedClip?.coerceIn(0, clips.lastIndex) ?: 0
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
                selectedClip = keptSelection(s, snapshot.clips),
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
                it.fadeInMs, it.fadeOutMs, it.soundLevel, it.rawDevelop, it.picked, it.speedCurve, it.retouch,
                flags = it.flags,
                reversed = it.reversed?.let { r -> SavedReverse(r.original.uri, r.startMs, r.endMs) },
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

    /** A star on a project (or taken off), to find it first in the list. */
    fun setProjectStarred(name: String, on: Boolean) {
        projectStore.setStarred(name, on)
        _state.update { s -> s.copy(projects = s.projects.map { if (it.name == name) it.copy(starred = on) else it }) }
    }

    /** Opens a saved project; its files are read again, missing ones are reported. */
    fun openProject(name: String) {
        val project = projectStore.load(name) ?: return
        historyStartsAgain = true
        clearMilestones()
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
            // A reversed clip's copy is the app's own (made again if it is gone); its original is the file.
            val copies = project.clips.mapNotNull { c -> c.reversed?.let { c.uri } }.toSet()
            val metas = withContext(Dispatchers.IO) {
                (project.clips.map { it.uri } + project.clips.mapNotNull { it.reversed?.originalUri }).distinct().associateWith { uri ->
                    runCatching { ClipMetadataReader.read(context, uri) }.onFailure { if (uri !in copies) missing += uri.lastPathSegment.orEmpty() }.getOrNull()
                }
            }
            val clips = clipsOf(project, metas)
            scopeFrame = null
            // Older projects of only photos open as albums.
            val kind = project.kind ?: if (clips.isNotEmpty() && clips.all { it.meta.isPhoto }) ProjectKind.PHOTOS else ProjectKind.VIDEO
            _state.update { s ->
                resynced(
                    editOf(s, project, clips, activities).copy(
                        projectName = project.name,
                        projectKind = kind,
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
            // Undo goes on where it was, even after the app was closed.
            runCatching { restoreHistory(project.name, _state.value, metas) }
            analyseAudio()
            refreshPreview()
        }
    }

    /** [s] with the edit of a saved [project] (its [clips] and [activities] already read). */
    private fun editOf(s: UiState, project: ProjectData, clips: List<ClipState>, activities: List<LoadedActivity>): UiState = s.copy(
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
    )

    /** The clips of a saved [project], their files described by [metas] (missing ones left out). */
    private fun clipsOf(project: ProjectData, metas: Map<Uri, ClipMetadata?>): List<ClipState> = project.clips.mapNotNull { saved ->
        // A reversed clip whose copy is gone plays forwards again (Reverse makes it anew).
        val original = saved.reversed?.let { metas[it.originalUri] }
        val reversedMeta = if (saved.reversed != null && original != null) metas[saved.uri]?.let { reversedMetaOf(original, it.uri, it.durationMs) } else null
        val c = if (saved.reversed != null && reversedMeta == null) saved.copy(uri = saved.reversed.originalUri, trimStartMs = saved.reversed.startMs, trimEndMs = saved.reversed.endMs, reversed = null) else saved
        val meta = reversedMeta ?: metas[c.uri] ?: return@mapNotNull null
        val start = c.trimStartMs.coerceIn(0L, (meta.durationMs - MIN_CLIP_MS).coerceAtLeast(0L))
        ClipState(
            meta, sync = null,
            flags = c.flags,
            reversed = if (reversedMeta != null && original != null && c.reversed != null) ReversedSource(original, c.reversed.startMs, c.reversed.endMs) else null,
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
            retouch = c.retouch,
            muteChangedSpeed = c.muteChangedSpeed,
            fadeInMs = c.fadeInMs,
            fadeOutMs = c.fadeOutMs,
            soundLevel = c.soundLevel,
            rawDevelop = c.rawDevelop,
            picked = c.picked,
            groupId = c.groupId,
        )
    }

    /** Starts an empty project; the current one stays saved. */
    fun newProject(kind: ProjectKind = ProjectKind.VIDEO) {
        historyStartsAgain = true
        clearMilestones()
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
            s.copy(projectName = if (s.projectName == old) newName else s.projectName, projects = s.projects.map { if (it.name == old) it.copy(name = newName) else it })
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

    /** Asks AI tracking to stop after the frame it is on (what was tracked is kept). */
    @Volatile private var stopRequested = false

    /**
     * The Stop of a long job: AI tracking stops after its frame and keeps what it found; other
     * jobs (copying, Clean up) are cancelled.
     */
    // Work in the background

    /** Long jobs (tracking with AI, reversing) run one at a time, each after the one before. */
    private val workLock = kotlinx.coroutines.sync.Mutex()
    private val workStops = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()
    private val workJobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private var nextWorkId = 1L

    private fun updateWork(id: Long, change: (BackgroundWork) -> BackgroundWork) {
        _state.update { s -> s.copy(work = s.work.map { if (it.id == id) change(it) else it }) }
        app.headsup.work.WorkService.show(_state.value.work)
    }

    /**
     * Runs [block] in the background (in turn with other such jobs), shown as a line with its
     * progress and Stop; the app stays usable and keeps running with the screen off.
     * [block] reports progress (0..1) and asks whether to stop.
     */
    private fun runInBackground(label: String, clipId: Long?, maskIndex: Int?, block: suspend (report: (Float) -> Unit, stopped: () -> Boolean) -> Unit) {
        val id = nextWorkId++
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        workStops[id] = stop
        _state.update { it.copy(work = it.work + BackgroundWork(id, label, clipId, maskIndex)) }
        app.headsup.work.WorkService.show(_state.value.work)
        app.headsup.work.WorkService.start(getApplication())
        workJobs[id] = viewModelScope.launch {
            try {
                workLock.lock()
                try {
                    updateWork(id) { it.copy(waiting = false, progress = 0f) }
                    var last = 0f
                    block({ p -> if (kotlin.math.abs(p - last) >= 0.005f || p >= 1f) { last = p; updateWork(id) { it.copy(progress = p) } } }, { stop.get() })
                } finally {
                    workLock.unlock()
                }
            } finally {
                workStops.remove(id)
                workJobs.remove(id)
                _state.update { s -> s.copy(work = s.work.filter { it.id != id }) }
                app.headsup.work.WorkService.show(_state.value.work)
            }
        }
    }

    /** Stops background job [id]: what it found so far is kept where it can be (tracking), else it ends. */
    fun stopBackground(id: Long) {
        val w = _state.value.work.firstOrNull { it.id == id } ?: return
        workStops[id]?.set(true)
        // Waiting, or work that cannot stop part way (reversing): cancelled.
        if (w.waiting || w.maskIndex == null) workJobs[id]?.cancel()
        updateWork(id) { it.copy(label = "Stopping…") }
    }

    /** Whether mask [maskIndex] of clip [clipId] is being tracked (or waits to be). */
    fun isTracking(clipId: Long, maskIndex: Int, s: UiState = _state.value): Boolean = s.work.any { it.clipId == clipId && it.maskIndex == maskIndex }

    // Flags

    /** A flag at [fileMs] of the selected clip, or the one there taken away. */
    fun toggleFlag(fileMs: Long) {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { c ->
            // The nearest flag within reach is taken away (a playhead dragged by eye is rarely exact).
            val near = c.flags.filter { kotlin.math.abs(it - fileMs) <= FLAG_REACH_MS }.minByOrNull { kotlin.math.abs(it - fileMs) }
            c.copy(flags = if (near != null) c.flags - near else (c.flags + fileMs.coerceIn(c.trimStartMs, c.trimEndMs)).sorted())
        }
    }

    /** Takes away the flag at [fileMs] of clip [index]. */
    fun removeFlag(index: Int, fileMs: Long) = updateClip(index) { c -> c.copy(flags = c.flags - fileMs) }

    /** Takes away every flag of the film. */
    fun clearFlags() = _state.update { s -> s.copy(clips = s.clips.map { it.copy(flags = emptyList()) }) }

    // Reverse

    /**
     * Plays clip [index] backwards ([app.headsup.media.Reverser]: a reversed copy of its cut is made,
     * which takes a while), or forwards again when it is reversed. Its look and flags stay; masks
     * stay where they are (their tracking, made forwards, is dropped).
     */
    fun reverseClip(index: Int) {
        val s = _state.value
        val clip = s.clips.getOrNull(index)?.takeIf { !it.meta.isPhoto } ?: return
        val back = clip.reversed
        if (back != null) {
            updateClip(index) { c ->
                val len = c.meta.durationMs
                c.copy(
                    meta = back.original, trimStartMs = back.startMs, trimEndMs = back.endMs, reversed = null,
                    flags = c.flags.map { back.startMs + (len - it) }.filter { it in back.startMs..back.endMs }.sorted(),
                    masks = c.masks.map(::untracked),
                )
            }
            return
        }
        val context: Application = getApplication()
        if (_state.value.work.any { it.clipId == clip.id && it.maskIndex == null }) return
        runInBackground("Reversing clip ${index + 1}", clip.id, null) { report, _ ->
            try {
                val file = app.headsup.media.Reverser.reverse(context, clip.meta, clip.trimStartMs, clip.trimEndMs) { p -> report(p.coerceIn(0f, 1f)) }
                val read = withContext(Dispatchers.IO) { ClipMetadataReader.read(context, Uri.fromFile(file)) }
                val meta = reversedMetaOf(clip.meta, read.uri, read.durationMs)
                val len = meta.durationMs
                _state.update { st ->
                    st.copy(
                        clips = st.clips.map { c ->
                            // Cut again meanwhile, or already reversed: left as it is now.
                            if (c.id != clip.id || c.reversed != null || c.trimStartMs != clip.trimStartMs || c.trimEndMs != clip.trimEndMs) c else c.copy(
                                meta = meta, trimStartMs = 0L, trimEndMs = len,
                                reversed = ReversedSource(clip.meta, clip.trimStartMs, clip.trimEndMs),
                                flags = c.flags.filter { it in clip.trimStartMs..clip.trimEndMs }.map { (clip.trimEndMs - it).coerceIn(0L, len) }.sorted(),
                                masks = c.masks.map(::untracked),
                            )
                        },
                    )
                }
                refreshPreview()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.headsup.media.Reverser.NotIntraException) {
                _state.update { it.copy(messages = it.messages + "This phone's video encoder could not make the reversed copy.") }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(messages = it.messages + "Could not reverse this clip: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** [m] without what was tracked in one direction of time (keys, maps over time). */
    private fun untracked(m: ColourMask): ColourMask = m.copy(keys = emptyList(), sequence = null, sequenceStrokes = 0, anchors = emptyList())

    fun stopWork() {
        if (_state.value.loadingMessage?.startsWith("Tracking with AI") == true) {
            stopRequested = true
            _state.update { it.copy(loadingMessage = "Stopping…") }
        } else {
            cancelLoading()
        }
    }

    /** Stops copying files in; what was not finished is removed. */
    fun cancelLoading() {
        loadJob?.cancel()
        _state.update { it.copy(busy = false, loadingMessage = null, loadingProgress = null) }
    }

    fun selectClip(index: Int) {
        scopeFrame = null
        _state.update { it.copy(selectedClip = index, scope = null, selectedMask = 0, compareGrade = null, cine = null, fast = null) }
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
            // The ramp is split where the cut is (each piece plays as the whole did there); fades
            // and the blend in stay at the outer ends.
            val (left, right) = SpeedCurve.split(clip.speedCurve, (cut - clip.trimStartMs).toFloat() / (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L))
            // A piece that keeps one speed all through becomes a clip at that steady speed.
            fun steady(c: ClipState): ClipState = SpeedCurve.steadyFactor(c.speedCurve)
                ?.let { f -> c.copy(speed = (c.speed * f).coerceIn(SpeedCurve.MIN, SpeedCurve.MAX), speedCurve = emptyList()) } ?: c
            val first = steady(clip.copy(trimEndMs = cut, fadeOutMs = 0L, speedCurve = left))
            val second = steady(clip.copy(id = nextClipId(), trimStartMs = cut, fadeInMs = 0L, transitionMs = 0L, speedCurve = right))
            // The second part is shown from its start: the playhead stays at the cut.
            s.copy(clips = s.clips.toMutableList().apply { set(index, first); add(index + 1, second) }, selectedClip = index + 1)
        }
    }

    /** Whether [a] and [b] (in that order) are pieces of one shot cut apart: the same file, [b] starting where [a] ends. */
    fun piecesOfOneShot(a: ClipState?, b: ClipState?): Boolean =
        a != null && b != null && a.meta.uri == b.meta.uri && !a.meta.isPhoto && a.reversed == null && b.reversed == null && a.trimEndMs == b.trimStartMs

    /** Whether two pieces are edited alike (joining them then loses nothing). */
    fun editedAlike(a: ClipState, b: ClipState): Boolean =
        a.grade == b.grade && a.lutName == b.lutName && a.colorGrade == b.colorGrade && a.effects == b.effects && a.masks == b.masks &&
            a.reframe == b.reframe && a.groupId == b.groupId && a.speed == b.speed && a.speedCurve == b.speedCurve &&
            a.muteChangedSpeed == b.muteChangedSpeed && a.soundLevel == b.soundLevel && a.rawDevelop == b.rawDevelop

    /**
     * Moves the cut between clip [left] and the next one (pieces of one shot) to [fileMs] of the
     * file: one piece gets longer, the other shorter, each keeping its own edits.
     */
    fun moveCut(left: Int, fileMs: Long) {
        _state.update { s ->
            val a = s.clips.getOrNull(left)
            val b = s.clips.getOrNull(left + 1)
            if (!piecesOfOneShot(a, b) || a == null || b == null) return@update s
            val at = fileMs.coerceIn(a.trimStartMs + MIN_CLIP_MS, b.trimEndMs - MIN_CLIP_MS)
            if (at == a.trimEndMs) return@update s
            val clips = s.clips.toMutableList()
            clips[left] = a.copy(trimEndMs = at)
            clips[left + 1] = b.copy(trimStartMs = at)
            s.copy(clips = clips, selectedClip = left + 1)
        }
        refreshPreview()
    }

    /**
     * Joins clip [left] and the next one (pieces of one shot) into one clip, with the edits of the
     * left piece or ([keepRight]) the right one; the start's fade in, the end's fade out and every
     * flag stay. A mask tracked on only one piece holds still on the other until tracked again.
     */
    fun joinWithNext(left: Int, keepRight: Boolean) {
        val s0 = _state.value
        val a = s0.clips.getOrNull(left)
        val b = s0.clips.getOrNull(left + 1)
        if (!piecesOfOneShot(a, b) || a == null || b == null) return
        val base = if (keepRight) b else a
        val share = (a.trimEndMs - a.trimStartMs).toFloat() / (b.trimEndMs - a.trimStartMs).coerceAtLeast(1L)
        val joined = base.copy(
            trimStartMs = a.trimStartMs, trimEndMs = b.trimEndMs,
            // Ramps put back together (at one steady speed for both, else the kept piece's).
            speedCurve = if (a.speed == b.speed) SpeedCurve.join(a.speedCurve, share, b.speedCurve) else base.speedCurve,
            fadeInMs = a.fadeInMs, fadeOutMs = b.fadeOutMs, transitionMs = a.transitionMs,
            flags = (a.flags + b.flags).distinct().sorted(),
        )
        val tracked = base.masks.withIndex().filter { it.value.sequence != null }.map { it.index + 1 }
        _state.update { s ->
            val clips = s.clips.toMutableList()
            clips[left] = joined
            clips.removeAt(left + 1)
            s.copy(
                clips = clips, selectedClip = left,
                messages = s.messages + listOfNotNull(
                    tracked.takeIf { it.isNotEmpty() && !editedAlike(a, b) }?.let { "Mask ${it.joinToString()} was tracked on one piece only: Track it again for the whole clip." },
                ),
            )
        }
        refreshPreview()
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
        fun matched(b: ColorGrade, change: ColorGrade = m.delta, k: Float = m.strength): ColorGrade {
            // Only the parts chosen.
            val d = change.copy(
                temperature = if (MatchPart.WHITE_BALANCE in m.parts) change.temperature else 0f,
                tint = if (MatchPart.WHITE_BALANCE in m.parts) change.tint else 0f,
                saturation = if (MatchPart.SATURATION in m.parts) change.saturation else 0f,
                skinHue = if (MatchPart.SKIN in m.parts) change.skinHue else 0f,
            )
            return b.copy(
            exposure = (b.exposure + d.exposure * k).coerceIn(-3f, 3f),
            contrast = (b.contrast + d.contrast * k).coerceIn(-1f, 1f),
            temperature = (b.temperature + d.temperature * k).coerceIn(-1f, 1f),
            tint = (b.tint + d.tint * k).coerceIn(-1f, 1f),
            saturation = (b.saturation + d.saturation * k).coerceIn(-1f, 1f),
            skinHue = (b.skinHue + d.skinHue * k).coerceIn(-30f, 30f),
        )
        }
        val grade = matched(m.base)
        if (m.groupId != null) {
            // The camera's character goes onto each clip of the group.
            _state.update { s ->
                s.copy(clips = s.clips.map { c -> m.memberBases[c.id]?.let { c.copy(grade = matched(it, m.memberDeltas[c.id] ?: m.delta, m.strength * (m.memberStrength[c.id] ?: 1f))) } ?: c })
            }
            refreshPreview()
        } else {
            val index = _state.value.clips.indexOfFirst { it.id == m.clipId }
            if (index >= 0) updateClip(index) { it.copy(grade = grade) }
        }
    }

    /**
     * Makes the clips of the selected clip's group alike in colour: each is matched to this clip
     * (frames across both clips, through their whole colour paths), its own change being only the
     * colour character: white balance, tint, saturation and skin. Brightness and contrast stay each
     * clip's. In the background; the strength can be scaled after, or undone.
     */
    fun matchGroupTo() {
        val s = _state.value
        val ref = s.selected ?: return
        val group = s.groupOf(ref) ?: return
        val members = s.clips.filter { it.groupId == group.id && it.id != ref.id }
        if (members.isEmpty()) {
            _state.update { it.copy(messages = it.messages + "The group has no other clip to match.") }
            return
        }
        val context: Application = getApplication()
        runInBackground("Matching ${group.name}", ref.id, null) { report, stopped ->
            val refPixels = withContext(Dispatchers.IO) { framesOf(context, s, ref) }
            if (refPixels == null) {
                _state.update { it.copy(messages = it.messages + "Could not read frames of the reference clip.") }
                return@runInBackground
            }
            val refLut = loadLut(s.lutFor(ref))
            val refApplied = s.appliedGrade(ref)
            val reference = withContext(Dispatchers.Default) {
                val rgb = FloatArray(refPixels.size * 3)
                val c = FloatArray(3)
                for (i in refPixels.indices) {
                    val p = refPixels[i]
                    c[0] = ((p shr 16) and 0xFF) / 255f; c[1] = ((p shr 8) and 0xFF) / 255f; c[2] = (p and 0xFF) / 255f
                    LutBaker.apply(refLut, refApplied, c)
                    rgb[3 * i] = c[0]; rgb[3 * i + 1] = c[1]; rgb[3 * i + 2] = c[2]
                }
                MatchStats.of(rgb)
            }
            val deltas = HashMap<Long, ColorGrade>()
            var skipped = 0
            for ((k, c) in members.withIndex()) {
                if (stopped()) break
                val pixels = withContext(Dispatchers.IO) { framesOf(context, s, c) }
                if (pixels == null) { skipped++; continue }
                val lut = loadLut(s.lutFor(c))
                val applied = s.appliedGrade(c)
                val matched = withContext(Dispatchers.Default) { ShotMatch.match(pixels, lut, applied, reference) }
                // Colour only: each clip keeps its brightness and contrast.
                deltas[c.id] = ColorGrade(
                    temperature = matched.temperature - applied.temperature,
                    tint = matched.tint - applied.tint,
                    saturation = matched.saturation - applied.saturation,
                    skinHue = matched.skinHue - applied.skinHue,
                )
                report((k + 1f) / members.size)
            }
            if (deltas.isEmpty()) return@runInBackground
            val now = _state.value
            val bases = now.clips.filter { it.id in deltas }.associate { it.id to it.grade }
            val session = MatchSession(ref.id, group.id, ref.grade, ColorGrade(), GROUP_MATCH_STRENGTH, bases, deltas)
            _state.update {
                it.copy(
                    matchSession = session,
                    messages = it.messages + listOfNotNull(
                        "${deltas.size} clip${if (deltas.size == 1) "" else "s"} of ${group.name} matched in colour to ${ref.meta.name}.",
                        if (skipped > 0) "$skipped could not be read." else null,
                    ),
                )
            }
            applyMatch(session)
        }
    }

    /**
     * Source pixels of [clip], small: five frames across its cut (a photo: the picture), before
     * any colour work. Null when they cannot be read (a raw photo is developed elsewhere).
     */
    private fun framesOf(context: android.content.Context, s: UiState, clip: ClipState, width: Int = 160): IntArray? = runCatching {
        val bitmaps = if (clip.meta.isPhoto) {
            if (clip.meta.isRaw) return null
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = (clip.meta.width / (width * 2)).coerceAtLeast(1) }
            listOfNotNull(context.contentResolver.openInputStream(clip.meta.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) })
        } else {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, s.previewSource(clip))
                val span = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L)
                val h = if (clip.meta.width > 0) (width * clip.meta.height / clip.meta.width).coerceAtLeast(1) else width * 9 / 16
                (0 until 5).mapNotNull { k ->
                    runCatching { r.getScaledFrameAtTime((clip.trimStartMs + span * (2 * k + 1) / 10) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, h) }.getOrNull()
                }
            } finally {
                r.release()
            }
        }
        if (bitmaps.isEmpty()) return null
        bitmaps.flatMap { b ->
            val small = if (b.width > width * 2) Bitmap.createScaledBitmap(b, width, (width * b.height / b.width).coerceAtLeast(1), true) else b
            IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }.asList()
        }.toIntArray()
    }.getOrNull()

    /** How much of its change clip [clipId] takes in the group made alike (0 leaves it out). */
    fun setMemberMatch(clipId: Long, strength: Float) {
        val session = _state.value.matchSession ?: return
        val next = session.copy(memberStrength = session.memberStrength + (clipId to strength.coerceIn(0f, 1f)))
        _state.update { it.copy(matchSession = next) }
        applyMatch(next)
    }

    /** What the match changes (at least one part). */
    fun setMatchParts(parts: Set<MatchPart>) {
        if (parts.isEmpty()) return
        val session = _state.value.matchSession ?: return
        val next = session.copy(parts = parts)
        _state.update { it.copy(matchSession = next) }
        applyMatch(next)
    }

    /** Held: the selected clip as it was before the match. */
    fun compareMatchMember(held: Boolean) {
        val s = _state.value
        val clip = s.selected ?: return
        setCompareGrade(if (held) s.matchSession?.memberBases?.get(clip.id) else null)
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
    /**
     * Measures the selected clip for looks: five frames across its cut (a photo: the picture),
     * through its current colours, as the Cine assistant measures one frame. The suggestions and
     * the adaptive styles then fit the whole clip, not one moment of it.
     */
    fun analyseClip(frameSource: (() -> Bitmap?)?) {
        val s = _state.value
        val clip = s.selected ?: return
        val photoFrame = if (clip.meta.isPhoto) frameSource?.invoke() ?: return else null
        val uri = s.previewSource(clip)
        val cube = s.previewCube
        val context: Application = getApplication()
        _state.update { it.copy(busy = true, loadingMessage = "Analysing the clip", loadingProgress = null) }
        viewModelScope.launch {
            try {
                val stats = withContext(Dispatchers.Default) {
                    val frames = if (photoFrame != null) listOf(photoFrame) else {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            val span = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L)
                            (0 until 5).mapNotNull { k ->
                                val t = clip.trimStartMs + span * (2 * k + 1) / 10
                                val w = 256
                                val h = if (clip.meta.width > 0) (w * clip.meta.height / clip.meta.width).coerceAtLeast(1) else 144
                                runCatching { r.getScaledFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) }.getOrNull()
                            }
                        } finally {
                            r.release()
                        }
                    }
                    if (frames.isEmpty()) return@withContext null
                    val pixels = frames.flatMap { f ->
                        val small = if (f.width > 320) Bitmap.createScaledBitmap(f, 256, (256 * f.height / f.width).coerceAtLeast(1), true) else f
                        IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }.asList()
                    }.toIntArray()
                    graded(pixels, cube)
                    CineAssistant.analyse(pixels)
                }
                if (stats == null) {
                    _state.update { it.copy(messages = it.messages + "Could not read frames of this clip.") }
                    return@launch
                }
                _state.update { it.copy(lookStats = clip.id to stats) }
                // A look being tried is fitted to what was just measured.
                _state.value.fast?.takeIf { it.clipId == clip.id }?.let { applyFastLook(it.look, it.strength) }
            } finally {
                _state.update { it.copy(busy = false, loadingMessage = null) }
            }
        }
    }

    /** [pixels] (ARGB) through the clip's colour table [cube], in place, as the picture shows them. */
    private fun graded(pixels: IntArray, cube: Array<Array<IntArray>>?) {
        if (cube == null) return
        val rgb = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            LutBaker.sampleBaked(cube, ((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, rgb)
            pixels[i] = (0xFF shl 24) or ((rgb[0] * 255).toInt() shl 16) or ((rgb[1] * 255).toInt() shl 8) or (rgb[2] * 255).toInt()
        }
    }

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
    /**
     * Fast edit: [look] on the selected clip at [strength], on top of what it had when the first
     * look was tried (trying another look replaces it, not adds to it).
     */
    fun applyFastLook(look: app.headsup.color.FastLook, strength: Float = 1f) {
        // An adaptive style being tried gives way to it.
        if (_state.value.cine?.let { it.style != null && it.clipId == _state.value.selected?.id } == true) closeCine(undo = true)
        val s = _state.value
        val index = s.selectedClip ?: return
        val clip = s.selected ?: return
        val session = s.fast?.takeIf { it.clipId == clip.id } ?: FastSession(clip.id, clip.grade, clip.effects, look)
        val k = strength.coerceIn(0f, 1f)
        // Measured: the look's character, plus what this clip needs to suit it.
        val stats = s.lookStats?.takeIf { it.first == clip.id }?.second
        val fit = stats?.let { CineAssistant.fit(it, keepDark = look == app.headsup.color.FastLook.NIGHT) }
        val grade = if (fit != null) look.grade + fit else look.grade
        // Contrast from the cinematic curve, fitted to the clip's tones once measured (a dark clip
        // keeps its shadow detail), and taken down with the strength.
        val curve = app.headsup.core.color.ToneCurve.cinematic(look.contrast, low = stats?.low ?: 0.18f, median = stats?.median ?: 0.5f)
        val scaled = (grade * k).copy(curves = listOf(app.headsup.core.color.ToneCurve.scaled(curve, k), emptyList(), emptyList(), emptyList()))
        _state.update { it.copy(fast = session.copy(look = look, strength = k)) }
        updateClip(index) { it.copy(grade = session.baseGrade + scaled, effects = look.effectsOn(session.baseEffects, k)) }
    }

    /** Hold to see the clip as it was before the Fast edit look. */
    fun compareFast(held: Boolean) {
        val session = _state.value.fast?.takeIf { held }
        _state.update { it.copy(compareGrade = session?.baseGrade, compareEffects = session?.baseEffects) }
        refreshPreview()
    }

    /** Keeps the look as ordinary corrections and effects, or with [undo] puts the clip back. */
    fun closeFast(undo: Boolean) {
        val session = _state.value.fast ?: return
        if (undo) {
            val index = _state.value.clips.indexOfFirst { it.id == session.clipId }
            if (index >= 0) updateClip(index) { it.copy(grade = session.baseGrade, effects = session.baseEffects) }
        }
        _state.update { it.copy(fast = null) }
    }

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
    /** Bars of fitted clips filled with the picture blurred, or black: for the whole film at once. */
    fun setBlurBars(blur: Boolean) {
        _state.update { s -> s.copy(clips = s.clips.map { c -> c.copy(reframe = c.reframe.copy(blur = blur)) }) }
    }

    fun setFitForAll(fit: Boolean, blur: Boolean = false) {
        _state.update { s -> s.copy(clips = s.clips.map { c -> c.copy(reframe = c.reframe.copy(fit = fit, blur = blur).clampedFor(c, s.timelineAspect)) }) }
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

    /** A new painted mask, with the finger set to what it is for: [mode] (Select for an object, Paint to paint). */
    fun addBrushMask(mode: BrushMode) {
        addMask(MaskKind.BRUSH)
        _state.update { it.copy(brush = it.brush.copy(mode = mode, subtract = false)) }
    }

    /** A new mask that is what [key] names in the picture on screen (the subject, people, the sky…). */
    fun addSceneMask(key: String, grab: (Int) -> android.graphics.Bitmap?) {
        addBrushMask(BrushMode.SELECT)
        selectScene(key, grab)
    }

    /**
     * A new mask of everything of a colour (the next tap on the picture picks it) or of a range of
     * brightness (the highlights to start with).
     */
    fun addRangeMask(colour: Boolean) {
        addMask(MaskKind.RANGE)
        if (colour) {
            _state.update { it.copy(pickingColour = true) }
        } else {
            updateMask { it.copy(luma = app.headsup.core.color.LumaRange()) }
        }
    }

    /** The next tap on the picture picks the selected mask's colour (or stops waiting for it). */
    fun setPickingColour(on: Boolean) = _state.update { it.copy(pickingColour = on) }

    /** The selected mask keeps the colour around ([x], [y]) (fractions) of [frame], the picture as it is. */
    fun pickMaskColour(frame: Bitmap, x: Float, y: Float) {
        val cx = (x * frame.width).toInt().coerceIn(0, frame.width - 1)
        val cy = (y * frame.height).toInt().coerceIn(0, frame.height - 1)
        // A small patch, so one noisy pixel does not decide.
        val r = maxOf(1, frame.width / 200)
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var n = 0
        for (yy in (cy - r)..(cy + r)) for (xx in (cx - r)..(cx + r)) {
            if (xx !in 0 until frame.width || yy !in 0 until frame.height) continue
            val c = frame.getPixel(xx, yy)
            sr += (c shr 16 and 0xFF) / 255f
            sg += (c shr 8 and 0xFF) / 255f
            sb += (c and 0xFF) / 255f
            n++
        }
        frame.recycle()
        if (n == 0) return
        updateMask { it.copy(colour = app.headsup.core.color.ColourRange(sr / n, sg / n, sb / n, it.colour?.tolerance ?: 0.35f)) }
        _state.update { it.copy(pickingColour = false) }
    }

    /** How the selected mask works with the one before it: its own layer, or added, subtracted, intersected. */
    fun setMaskJoin(join: app.headsup.core.color.MaskJoin) {
        if (_state.value.selectedMask == 0) return
        updateMask { it.copy(join = join) }
    }

    fun toggleMaskOverlay() = _state.update { it.copy(maskOverlay = !it.maskOverlay) }

    /**
     * A copy of the selected mask (shape, tracking and corrections) as a new mask, [inverted] or
     * not: e.g. the sky's mask turned into the land's.
     */
    fun duplicateMask(inverted: Boolean) {
        val index = _state.value.selectedClip ?: return
        val clip = _state.value.selected ?: return
        val mask = clip.masks.getOrNull(_state.value.selectedMask) ?: return
        if (clip.masks.size >= ColourMask.MAX_PER_CLIP) return
        updateClip(index) { it.copy(masks = it.masks + mask.copy(inverted = if (inverted) !mask.inverted else mask.inverted, grade = if (inverted) ColorGrade() else mask.grade)) }
        _state.update { it.copy(selectedMask = clip.masks.size) }
    }

    fun removeMask(maskIndex: Int) {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { c ->
            val rest = c.masks.filterIndexed { i, _ -> i != maskIndex }
            // The first mask is always a layer of its own.
            c.copy(masks = rest.mapIndexed { i, m -> if (i == 0 && m.join != app.headsup.core.color.MaskJoin.NEW) m.copy(join = app.headsup.core.color.MaskJoin.NEW) else m })
        }
        _state.update { it.copy(pickingColour = false) }
        _state.update { it.copy(selectedMask = (it.selectedMask - if (it.selectedMask >= maskIndex) 1 else 0).coerceAtLeast(0)) }
    }

    fun selectMask(maskIndex: Int) {
        _state.update { it.copy(selectedMask = maskIndex, compareGrade = null, removing = false, pickingColour = false) }
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

    /** The base layer back to no corrections and none of the look's effects (grain, glow, halation, lens, bars). */
    fun resetBase() {
        val index = _state.value.selectedClip ?: return
        updateClip(index) {
            it.copy(
                grade = ColorGrade(),
                effects = it.effects.copy(grain = 0f, glow = 0f, halation = 0f, lensAberration = 0f, lensSoftness = 0f, barsRatio = null),
            )
        }
    }

    fun updateBrush(transform: (BrushSettings) -> BrushSettings) = _state.update { it.copy(brush = transform(it.brush)) }

    /** Shows the brush on the picture for a moment (its size is being set). */
    fun showBrush() = _state.update { it.copy(brushSizedAt = android.os.SystemClock.uptimeMillis()) }

    fun notePictureHeight(px: Float) = _state.update { if (it.pictureHeightPx == px) it else it.copy(pictureHeightPx = px) }

    /**
     * Paints the selected (brush) mask at a point of the picture (fractions): [phase] 0 starts a
     * stroke, 1 continues it, 2 ends it. Points land where the mask shows now (it may have moved).
     * Only the strokes change, so no colour table is computed again.
     */
    /** A tap's action waits a moment ([TAP_WAIT_MS]): a second tap (which fits the view again) takes it back. */
    private var pendingTap: kotlinx.coroutines.Job? = null

    private fun afterTap(action: () -> Unit) {
        pendingTap?.cancel()
        pendingTap = viewModelScope.launch {
            kotlinx.coroutines.delay(TAP_WAIT_MS)
            action()
        }
    }

    /** A double tap on the picture: it only fits the view again, so the first tap does nothing. */
    private fun dropTap() {
        pendingTap?.cancel()
        _state.update { it.copy(paintPath = emptyList(), lassoPath = emptyList()) }
    }

    /**
     * A finger on a painted mask. [phase]: 0 down, 1 moved, 2 lifted, 3 dropped (a second finger
     * came: the view zooms instead), 4 a double tap (the view fits again; the tap before it is
     * taken back).
     */
    fun brushStroke(phase: Int, x: Float, y: Float, grab: (Int) -> android.graphics.Bitmap?) {
        if (phase == 4) return dropTap()
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
        if (s.brush.mode == BrushMode.PUSH) {
            pushStroke(phase, x, y, clip, index, placed, aspect)
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
                // Full strength (a weaker stroke only made the mask uneven); the brush's own softness,
                // so changing it never changes what was painted before. Soften keeps its strength.
                val stroke = app.headsup.core.color.BrushStroke(
                    points, radius, if (b.mode == BrushMode.SOFTEN) b.flow else 1f, b.mode == BrushMode.ERASE,
                    soften = b.mode == BrushMode.SOFTEN, softness = if (b.mode == BrushMode.SOFTEN) null else b.softness,
                )
                val m = s.selectedMask
                val add = {
                    _state.update { st ->
                        st.copy(clips = st.clips.mapIndexed { i, c -> if (i == index) c.copy(masks = c.masks.mapIndexed { j, mm -> if (j == m) mm.copy(strokes = mm.strokes + stroke) else mm }) else c })
                    }
                }
                // A dab (a tap) may be the start of a double tap.
                if (path.size <= 2) afterTap(add) else add()
            }
        }
    }

    /** The picture taken when an outline starts, for a line's split by colour. */
    private var lassoReference: String? = null

    /**
     * Drawing in Select: shown as drawn, and when the finger lifts, a tap selects the object under
     * it, a loop the object inside it (MobileSAM, given the loop's box) and a line splits the
     * picture along it ([app.headsup.core.color.BrushMask.select]). A wrong bit of the outline is
     * fixed with Fix instead (a brush that keeps to the picture's edges). The picture is read as the
     * finger comes down, so the answer is quick when it lifts.
     */
    private fun selectStroke(
        phase: Int, x: Float, y: Float, grab: (Int) -> android.graphics.Bitmap?,
        clip: ClipState, index: Int, mask: ColourMask, placed: ColourMask, aspect: Float,
    ) {
        when (phase) {
            0 -> {
                lassoReference = brushReference(clip, placed, grab)
                // Only Select finds objects: the picture is read for it as the finger comes down.
                if (_state.value.brush.mode == BrushMode.SELECT) segmentPicture(clip, grab)
                _state.update { it.copy(lassoPath = listOf(x, y)) }
            }
            1 -> _state.update { st ->
                val p = st.lassoPath
                if (p.size >= 2 && kotlin.math.hypot((x - p[p.size - 2]) * aspect, y - p.last()) < 0.004f) st else st.copy(lassoPath = p + x + y)
            }
            else -> {
                val path = _state.value.lassoPath
                _state.update { it.copy(lassoPath = emptyList()) }
                if (path.size < 2) return
                val xs = path.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = path.filterIndexed { i, _ -> i % 2 == 1 }
                val tap = path.size < 6 || maxOf(xs.max() - xs.min(), ys.max() - ys.min()) < 0.02f
                if (tap) {
                    afterTap { selectObject(path[0], path[1], null, grab, clip, index, placed) }
                    return
                }
                val outline = path.chunked(2).map { (px, py) -> px to py }
                if (app.headsup.core.color.BrushMask.isLoop(outline)) {
                    selectObject(
                        (xs.min() + xs.max()) / 2f, (ys.min() + ys.max()) / 2f,
                        floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()), grab, clip, index, placed,
                    )
                    return
                }
                val points = path.chunked(2).flatMap { (px, py) -> app.headsup.core.color.BrushMask.unmove(px, py, placed, aspect).toList() }
                val stroke = app.headsup.core.color.BrushStroke(points, radius = 0.01f, flow = 1f, reference = lassoReference, lasso = true, erase = _state.value.brush.subtract)
                addStroke(index, clip, stroke)
            }
        }
    }

    /** The push stroke being made while the finger moves (replaced at each move, so it shows at once). */
    private var pushing: app.headsup.core.color.BrushStroke? = null

    /**
     * Push: the finger grabs the outline and moves it; the stroke grows with the finger and the
     * mask shows it as it goes.
     */
    private fun pushStroke(phase: Int, x: Float, y: Float, clip: ClipState, index: Int, placed: ColourMask, aspect: Float) {
        val (px, py) = app.headsup.core.color.BrushMask.unmove(x, y, placed, aspect)
        when (phase) {
            0 -> {
                val radius = _state.value.brush.radius / placed.sizeX.coerceAtLeast(0.05f)
                pushing = app.headsup.core.color.BrushStroke(listOf(px, py), radius, push = true)
            }
            1 -> {
                val p = pushing ?: return
                val last = p.points
                if (last.size >= 2 && kotlin.math.hypot(px - last[last.size - 2], py - last.last()) < 0.003f) return
                val grown = p.copy(points = last + px + py)
                // The first move adds the stroke; later ones replace it.
                addStroke(index, clip, grown, replacing = p.takeIf { last.size > 2 })
                pushing = grown
            }
            else -> pushing = null
        }
    }

    /**
     * The selected brush mask's whole edge laid onto the picture's edges near it
     * ([app.headsup.core.color.BrushStroke.fit]), e.g. after it was pushed or painted by hand.
     */
    fun fitMaskToEdges(grab: (Int) -> android.graphics.Bitmap?) {
        val s = _state.value
        val index = s.selectedClip ?: return
        val clip = s.selected ?: return
        val mask = clip.masks.getOrNull(s.selectedMask)?.takeIf { it.kind == MaskKind.BRUSH && it.strokes.isNotEmpty() } ?: return
        val reference = brushReference(clip, mask.at(playheadFileMs), grab) ?: return
        addStroke(index, clip, app.headsup.core.color.BrushStroke(listOf(0.5f, 0.5f), radius = 0.01f, reference = reference, fit = true))
        // Edge then follows the same picture.
        updateMask { it.copy(edgePicture = reference) }
    }

    /**
     * The selected mask's Edge: moved by [edge], following the picture on screen (kept with the
     * mask the first time) for a painted or found mask.
     */
    fun setMaskEdge(edge: Float, grab: ((Int) -> android.graphics.Bitmap?)?) {
        val s = _state.value
        val clip = s.selected ?: return
        val mask = clip.masks.getOrNull(s.selectedMask) ?: return
        val picture = mask.edgePicture
            ?: if (mask.kind == MaskKind.BRUSH && mask.sequence == null && grab != null) brushReference(clip, mask.at(playheadFileMs), grab) else null
        updateMask { it.copy(edge = edge, edgePicture = picture) }
    }

    private fun addStroke(index: Int, clip: ClipState, stroke: app.headsup.core.color.BrushStroke, replacing: app.headsup.core.color.BrushStroke? = null) {
        val m = _state.value.selectedMask
        _state.update { st ->
            st.copy(
                clips = st.clips.mapIndexed { i, c ->
                    if (i != index || c.id != clip.id) c else c.copy(
                        masks = c.masks.mapIndexed { j, mm ->
                            if (j != m) mm
                            else if (replacing != null && mm.strokes.lastOrNull() == replacing) mm.copy(strokes = mm.strokes.dropLast(1) + stroke)
                            else mm.copy(strokes = mm.strokes + stroke)
                        },
                    )
                },
            )
        }
    }

    /** A picture MobileSAM has read, with the same picture small (as the mask is kept) to lay its answers onto. */
    private class SegmentPicture(val reading: app.headsup.color.Segmenter.Reading, val guide: FloatArray, val width: Int, val height: Int)

    /** The picture read last: by clip and frame (file time; 0 for a photo). */
    private var segmentReading: Pair<Pair<Long, Long>, kotlinx.coroutines.Deferred<SegmentPicture?>>? = null

    /** The picture on screen as MobileSAM reads it; started at once, the same one again while the frame stays. */
    private fun segmentPicture(clip: ClipState, grab: (Int) -> android.graphics.Bitmap?): kotlinx.coroutines.Deferred<SegmentPicture?>? {
        // A photo's picture changes with Clean up (and undoing it); a video's with the frame.
        val key = clip.id to (if (clip.meta.isPhoto) clip.retouch.hashCode().toLong() else playheadFileMs)
        segmentReading?.let { (k, d) -> if (k == key) return d }
        val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
        val side = app.headsup.color.Segmenter.INPUT
        val bitmap = grab(if (aspect >= 1f) side else (side * aspect).roundToInt().coerceAtLeast(1)) ?: return null
        val d = viewModelScope.async(Dispatchers.Default) {
            runCatching {
                val reading = app.headsup.color.Segmenter.read(getApplication(), bitmap)
                val (w, h) = app.headsup.color.BrushAtlas.slotSize(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
                SegmentPicture(reading, app.headsup.color.Segmenter.rgb(small), w, h)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else it.printStackTrace() }.getOrNull()
        }
        segmentReading = key to d
        return d
    }

    /**
     * The last object selected, while it can still be changed: tapping the same place again shows
     * the next of what MobileSAM offered there (a part, the object, more), smallest to largest.
     */
    private class Pick(val clipId: Long, val x: Float, val y: Float, val candidates: List<FloatArray>, val shown: Int, val stroke: app.headsup.core.color.BrushStroke)

    private var lastPick: Pick? = null

    /**
     * The object at ([x], [y]) (fractions of the picture), or inside [box] (x0, y0, x1, y1) when
     * one was drawn, found by MobileSAM, laid onto the picture's edges and added to the mask (or
     * taken from it, with Subtract).
     */
    private fun selectObject(
        x: Float, y: Float, box: FloatArray?, grab: (Int) -> android.graphics.Bitmap?,
        clip: ClipState, index: Int, placed: ColourMask,
    ) {
        val placement = MaskKey.of(placed, 0L)
        val subtract = _state.value.brush.subtract
        val previous = lastPick
        val mask = _state.value.selected?.masks?.getOrNull(_state.value.selectedMask)
        val again = box == null && previous != null && previous.clipId == clip.id &&
            mask?.strokes?.lastOrNull() == previous.stroke && kotlin.math.hypot(x - previous.x, y - previous.y) < 0.03f
        val picture = segmentPicture(clip, grab) ?: return
        viewModelScope.launch {
            val waiting = !picture.isCompleted
            if (waiting) _state.update { it.copy(busy = true, loadingMessage = "Reading the picture", loadingProgress = null) }
            try {
                val seg = picture.await()
                if (seg == null) {
                    segmentReading = null
                    _state.update { it.copy(messages = it.messages + "Could not read the picture to find objects.") }
                    return@launch
                }
                val pick = withContext(Dispatchers.Default) {
                    val candidates: List<FloatArray>
                    val shown: Int
                    if (again && previous != null) {
                        candidates = previous.candidates
                        shown = (previous.shown + 1) % candidates.size
                    } else {
                        val prompts = if (box != null) {
                            listOf(
                                app.headsup.color.Segmenter.Prompt(box[0], box[1], app.headsup.color.Segmenter.Prompt.Label.BOX_START),
                                app.headsup.color.Segmenter.Prompt(box[2], box[3], app.headsup.color.Segmenter.Prompt.Label.BOX_END),
                            )
                        } else {
                            listOf(app.headsup.color.Segmenter.Prompt(x, y, app.headsup.color.Segmenter.Prompt.Label.IN))
                        }
                        val found = app.headsup.color.Segmenter.objects(getApplication(), seg.reading, prompts, seg.width, seg.height)
                        if (found.isEmpty()) return@withContext null
                        // Smallest to largest (what the next taps step through), starting at the one the model likes best.
                        candidates = found.map { it.first }.sortedBy { m -> m.count { it > 0.5f } }
                        shown = candidates.indexOf(found.first().first)
                    }
                    val refined = app.headsup.color.Segmenter.refine(seg.guide, candidates[shown], seg.width, seg.height)
                    val id = app.headsup.color.BrushReferences.create(getApplication(), seg.width, seg.height, refined, placement)
                    val stroke = app.headsup.core.color.BrushStroke(listOf(x, y), radius = 0.01f, flow = 1f, erase = subtract, reference = id, segment = true)
                    Pick(clip.id, x, y, candidates, shown, stroke)
                }
                if (pick == null) {
                    _state.update { it.copy(messages = it.messages + if (box != null) "Nothing found inside: draw round the whole object." else "No object found there: tap its middle, or draw round it.") }
                    return@launch
                }
                addStroke(index, clip, pick.stroke, replacing = if (again) previous?.stroke else null)
                lastPick = pick
                // What was found shows over the picture, so what to fix can be seen.
                if (!_state.value.maskOverlay) _state.update { it.copy(maskOverlay = true) }
            } finally {
                if (waiting) _state.update { it.copy(busy = false, loadingMessage = null) }
            }
        }
    }

    /** The sky of the picture on screen added to the selected brush mask, or taken from it with Subtract. */
    fun selectSky(grab: (Int) -> android.graphics.Bitmap?) = selectScene(app.headsup.color.Scene.SKY.name, grab)

    /**
     * What [key] names ([app.headsup.color.SceneKeys]: an area or things of the scene, the subject,
     * the background) in the picture on screen, added to the selected brush mask or taken from it
     * with Subtract. Areas are laid onto the picture's edges; things are made exact by MobileSAM.
     */
    fun selectScene(key: String, grab: (Int) -> android.graphics.Bitmap?) {
        val s = _state.value
        val index = s.selectedClip ?: return
        val clip = s.selected ?: return
        val mask = clip.masks.getOrNull(s.selectedMask)?.takeIf { it.kind == MaskKind.BRUSH } ?: return
        val placement = MaskKey.of(mask.at(playheadFileMs), 0L)
        val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
        val (w, h) = app.headsup.color.BrushAtlas.slotSize(aspect)
        val bitmap = grab(w) ?: return
        val keys = app.headsup.color.SceneKeys
        val scene = keys.scene(key)
        val things = scene?.thing ?: true
        // Things are made exact on the picture as large as MobileSAM reads it.
        val large = if (things) grab(app.headsup.color.Segmenter.INPUT) else null
        val subtract = s.brush.subtract
        val label = keys.label(key)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, loadingMessage = "Finding ${label.lowercase(java.util.Locale.ROOT)}", loadingProgress = null) }
            try {
                val stroke = withContext(Dispatchers.Default) {
                    runCatching {
                        val context: Application = getApplication()
                        val seg = app.headsup.color.Segmenter
                        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
                        val guide = seg.rgb(small)
                        val groups = seg.scene(context, small, w, h)
                        fun enough(m: FloatArray) = m.count { it > 0.5f } >= w * h / 500
                        val found: FloatArray = when {
                            scene != null && !scene.thing -> groups[scene.ordinal]
                            scene != null -> {
                                val coarse = groups[scene.ordinal]
                                if (!enough(coarse)) return@runCatching null
                                seg.exact(context, large ?: small, coarse, w, h)
                            }
                            else -> {
                                // The subject: the people, animals and vehicles the scene model finds,
                                // else what MobileSAM finds in the middle of the picture.
                                val coarse = FloatArray(w * h) { i -> app.headsup.color.Scene.entries.filter { it.thing }.maxOf { groups[it.ordinal][i] } }
                                val subject = if (enough(coarse)) seg.exact(context, large ?: small, coarse, w, h) else {
                                    val reading = seg.read(context, large ?: small)
                                    seg.objects(context, reading, listOf(app.headsup.color.Segmenter.Prompt(0.5f, 0.5f, app.headsup.color.Segmenter.Prompt.Label.IN)), w, h)
                                        .map { it.first }
                                        .firstOrNull { m -> app.headsup.core.color.MaskFollow.area(m) in 0.01f..0.6f }
                                        ?: return@runCatching null
                                }
                                if (key == keys.BACKGROUND) FloatArray(subject.size) { 1f - subject[it] } else subject
                            }
                        }
                        if (!enough(found)) return@runCatching null
                        val refined = seg.refine(guide, found, w, h)
                        val id = app.headsup.color.BrushReferences.create(context, w, h, refined, placement)
                        app.headsup.core.color.BrushStroke(
                            listOf(0.5f, 0.1f), radius = 0.01f, flow = 1f, erase = subtract, reference = id, segment = true,
                            // Areas are found again by the scene model on video; things and the background by following.
                            sky = scene != null && !scene.thing, scene = key,
                        )
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else it.printStackTrace() }.getOrNull()
                }
                if (stroke == null) {
                    _state.update { it.copy(messages = it.messages + "No ${label.lowercase(java.util.Locale.ROOT)} found in this picture.") }
                    return@launch
                }
                addStroke(index, clip, stroke)
                lastPick = null
            } finally {
                _state.update { it.copy(busy = false, loadingMessage = null) }
            }
        }
    }

    /**
     * Removing something from a photo (Adjust > Masks > Remove): a painted mask made for it, in
     * Select, where what is to go is selected as any mask is; [removeMaskArea] then fills it.
     */
    fun startRemoval() {
        addBrushMask(BrushMode.SELECT)
        _state.update { it.copy(removing = true) }
    }

    /** Leaves removing without removing: the mask made for it goes. */
    fun cancelRemoval() {
        val m = _state.value.selectedMask
        _state.update { it.copy(removing = false) }
        if (_state.value.selected?.masks?.getOrNull(m)?.kind == MaskKind.BRUSH) removeMask(m)
    }

    /** Removes from the photo what the selected mask covers (filled from around it), and the mask with it. */
    fun removeMaskArea() {
        val s = _state.value
        val index = s.selectedClip ?: return
        val clip = s.selected?.takeIf { it.meta.isPhoto } ?: return
        val maskIndex = s.selectedMask
        val mask = clip.masks.getOrNull(maskIndex)?.takeIf { it.kind == MaskKind.BRUSH && it.strokes.isNotEmpty() } ?: return
        _state.update { it.copy(removing = false) }
        removeMask(maskIndex)
        val context: Application = getApplication()
        cleanup(index, clip) { w, h ->
            val refs = mask.strokes.mapNotNull { it.reference }.distinct()
                .mapNotNull { id -> app.headsup.color.BrushReferences.get(context, id)?.let { id to it } }.toMap()
            val unmoved = app.headsup.core.color.BrushMask.rasterize(mask.strokes, w, h, mask.softness, refs)
            // Edge moves the whole outline, as shown.
            val drawn = app.headsup.color.BrushAtlas.moveEdge(context, mask, unmoved, w, h)
            val placed = app.headsup.core.color.BrushMask.placed(drawn, w, h, MaskKey.of(mask.at(0L), 0L))
            if (mask.inverted) FloatArray(placed.size) { 1f - placed[it] } else placed
        }
    }

    /**
     * Removes from the photo at [index] what [holeOf] gives (0..1 over the picture, at the size it
     * is asked for), as new patches over it.
     */
    private fun cleanup(index: Int, clip: ClipState, holeOf: suspend (width: Int, height: Int) -> FloatArray?) {
        val context: Application = getApplication()
        val clipId = clip.id
        _state.update { it.copy(busy = true, loadingMessage = "Removing", loadingProgress = null) }
        loadJob = viewModelScope.launch {
            try {
                val made = withContext(Dispatchers.Default) {
                    val photo = app.headsup.retouch.Cleaner.source(context, clip.meta.uri, clip.meta.isRaw, clip.rawDevelop) ?: return@withContext null
                    val side = app.headsup.color.Segmenter.INPUT
                    val hw = if (photo.width >= photo.height) side else (side * photo.width / photo.height).coerceAtLeast(1)
                    val hh = if (photo.width >= photo.height) (side * photo.height / photo.width).coerceAtLeast(1) else side
                    val hole = holeOf(hw, hh)?.takeIf { m -> m.any { it > 0.5f } } ?: return@withContext emptyList()
                    val holeId = app.headsup.color.BrushReferences.create(context, hw, hh, hole, app.headsup.core.color.BrushMask.BASE)
                    val develop = if (clip.meta.isRaw) app.headsup.retouch.Cleaner.developKey(clip.rawDevelop) else null
                    val base = app.headsup.retouch.Cleaner.applyTo(context, photo, clip.retouch)
                    app.headsup.retouch.Cleaner.remove(context, base, hole, hw, hh, holeId, develop) { p ->
                        // Stop takes effect between areas (one fill cannot be interrupted).
                        ensureActive()
                        _state.update { it.copy(loadingProgress = p) }
                    }
                }
                when {
                    made == null -> _state.update { it.copy(messages = it.messages + "Could not read the photo to clean it up.") }
                    made.isEmpty() -> _state.update { it.copy(messages = it.messages + "Nothing found there to remove: tap the object's middle, or paint over it.") }
                    else -> {
                        // The picture changed: read again for the next tap.
                        segmentReading = null
                        _state.update { st -> st.copy(clips = st.clips.map { c -> if (c.id == clipId) c.copy(retouch = c.retouch + made) else c }) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                _state.update { it.copy(messages = it.messages + "Clean up failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = false, loadingMessage = null, loadingProgress = null) }
            }
        }
    }

    /** Takes away all of the selected photo's Clean up (one step to undo). */
    fun clearCleanup() {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { it.copy(retouch = emptyList()) }
    }

    /** Takes away the last thing removed from the selected photo. */
    fun undoLastCleanup() {
        val index = _state.value.selectedClip ?: return
        updateClip(index) { c ->
            val last = c.retouch.lastOrNull()?.hole
            c.copy(retouch = if (last == null) c.retouch.dropLast(1) else c.retouch.filter { it.hole != last })
        }
    }

    private var cleanupRefresh: kotlinx.coroutines.Job? = null

    /**
     * A raw photo developed differently needs its Clean up made again (the patches were made from
     * the old development): done in the background, a moment after the last change.
     */
    private fun refreshCleanupLater() {
        cleanupRefresh?.cancel()
        cleanupRefresh = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val context: Application = getApplication()
            for (clip in _state.value.clips) {
                if (!clip.meta.isRaw || clip.retouch.isEmpty()) continue
                val key = app.headsup.retouch.Cleaner.developKey(clip.rawDevelop)
                if (clip.retouch.all { it.develop == key }) continue
                val redone = withContext(Dispatchers.Default) {
                    var photo = app.headsup.retouch.Cleaner.source(context, clip.meta.uri, true, clip.rawDevelop) ?: return@withContext null
                    val out = ArrayList<app.headsup.core.retouch.RetouchPatch>()
                    // In the order made: each was made on the photo with the ones before.
                    for (holeId in clip.retouch.map { it.hole }.distinct()) {
                        ensureActive()
                        val ref = holeId?.let { app.headsup.color.BrushReferences.get(context, it) } ?: continue
                        val hole = FloatArray(ref.width * ref.height) { ref.rgb[it * 3] }
                        val made = app.headsup.retouch.Cleaner.remove(context, photo, hole, ref.width, ref.height, holeId, key)
                        photo = app.headsup.retouch.Cleaner.applyTo(context, photo, made)
                        out += made
                    }
                    out
                } ?: continue
                _state.update { st ->
                    st.copy(
                        clips = st.clips.map { c ->
                            // Only if nothing changed meanwhile.
                            if (c.id == clip.id && c.retouch == clip.retouch && app.headsup.retouch.Cleaner.developKey(c.rawDevelop) == key) c.copy(retouch = redone) else c
                        },
                    )
                }
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
    fun clearBrush() = editSelectedMask { it.copy(strokes = emptyList(), sequence = null, sequenceStrokes = 0, anchors = emptyList()) }

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

    /** The playhead in the selected clip, from its cut (0 when it is elsewhere): where a new preview starts. */
    fun playheadInClip(): Long {
        val c = _state.value.selected ?: return 0L
        return if (playheadFileMs in c.trimStartMs..c.trimEndMs) playheadFileMs - c.trimStartMs else 0L
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
     * Tracking a brush mask with AI: the mask is found again on frames of the clip (12 a second),
     * so it takes the object's shape as it moves, turns and changes ([app.headsup.color.MaskFollower]),
     * and disappears when the object leaves the picture. From the playhead forward and/or back to
     * the cut's ends; maps already tracked elsewhere are kept.
     */
    private fun trackBrushAI(direction: TrackDirection) {
        val s = _state.value
        val clip = s.selected ?: return
        val maskIndex = s.selectedMask
        val mask = clip.masks.getOrNull(maskIndex)?.takeIf { it.kind == MaskKind.BRUSH } ?: return
        val clipId = clip.id
        val t0 = playheadFileMs.coerceIn(clip.trimStartMs, clip.trimEndMs)
        val uri = s.previewSource(clip)
        val adds = mask.strokes.filter { !it.erase && !it.soften }
        val sky = adds.isNotEmpty() && adds.all { it.sky }
        // An area (sky, plants…) is found again by the scene model; the background by following the subject.
        val scene = if (sky) app.headsup.color.SceneKeys.scene(adds.first().scene) ?: app.headsup.color.Scene.SKY else null
        val invert = adds.isNotEmpty() && adds.all { it.scene == app.headsup.color.SceneKeys.BACKGROUND }
        val context: Application = getApplication()
        // The sky model is larger and the sky changes slowly: 4 maps a second, faded between.
        val step = if (sky) AI_TRACK_SKY_STEP_MS else AI_TRACK_STEP_MS
        if (isTracking(clipId, maskIndex, s)) return
        val strokesAtStart = mask.strokes
        // In the background: the rest of the app can be used meanwhile (another track waits its turn).
        runInBackground("Tracking ${maskIndex + 1}", clipId, maskIndex) { report, stopped ->
            run {
                val result = withContext(Dispatchers.Default) {
                    val aspect = if (clip.meta.width > 0 && clip.meta.height > 0) clip.meta.width.toFloat() / clip.meta.height else 16f / 9f
                    val (w, h) = app.headsup.color.BrushAtlas.slotSize(aspect)
                    val old = mask.sequence?.let { app.headsup.color.MaskSequences.get(context, it) }?.takeIf { it.width == w && it.height == h }
                    // The mask as it shows at the playhead: what was tracked there, with strokes added since on top.
                    val refs = mask.strokes.mapNotNull { it.reference }.distinct()
                        .mapNotNull { id -> app.headsup.color.BrushReferences.get(context, id)?.let { id to it } }.toMap()
                    val start = if (old != null) {
                        app.headsup.core.color.BrushMask.rasterize(mask.strokes.drop(mask.sequenceStrokes), w, h, mask.softness, refs, start = old.at(t0, w, h))
                    } else {
                        // Where the mask shows now: moved, turned or scaled after it was made, it starts from there.
                        // Without the Edge move: that is made when the mask is shown, on each frame.
                        val drawn = app.headsup.core.color.BrushMask.rasterize(mask.strokes, w, h, mask.softness, refs)
                        app.headsup.core.color.BrushMask.placed(drawn, w, h, MaskKey.of(mask.at(t0), 0L))
                    }
                    if (!sky && app.headsup.core.color.MaskFollow.area(start) == 0f) return@withContext null
                    val forward = direction != TrackDirection.BACKWARD
                    val backward = direction != TrackDirection.FORWARD
                    // Frames fixed and tracked from before (keys) bound this run: what was set there stays.
                    val endAhead = mask.anchors.filter { it > t0 + step / 2 }.minOrNull()?.let { it - step / 2 }?.coerceAtMost(clip.trimEndMs) ?: clip.trimEndMs
                    val endBehind = mask.anchors.filter { it < t0 - step / 2 }.maxOrNull()?.let { it + step / 2 }?.coerceAtLeast(clip.trimStartMs) ?: clip.trimStartMs
                    val total = ((if (forward) endAhead - t0 else 0L) + (if (backward) t0 - endBehind else 0L)).coerceAtLeast(1L)
                    var done = 0L
                    fun progress(more: Long) {
                        done += more
                        report((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    val times = ArrayList<Long>()
                    val maps = ArrayList<ByteArray>()
                    var lostAhead: Long? = null
                    var lostBehind: Long? = null
                    var goneAhead: Long? = null
                    var goneBehind: Long? = null
                    if (forward) {
                        val follower = app.headsup.color.MaskFollower(context, w, h, scene, invert, start)
                        var first = true
                        var ahead = 0
                        var last = t0
                        TrackingFrames.forEachPicture(context, uri, t0, endAhead, step, app.headsup.color.Segmenter.INPUT) { ms, picture ->
                            if (stopped()) return@forEachPicture false
                            progress(ms - last)
                            last = ms
                            if (first) {
                                // The frame the mask was made (or fixed) on: it is the start, and what the
                                // model does not find there (the fixes) is carried on.
                                first = false
                                follower.begin(picture)
                                times += ms; maps += app.headsup.core.color.PackedFrames.pack(app.headsup.core.color.MaskSequence.bytes(start))
                                return@forEachPicture true
                            }
                            // Things: the model reads every other frame, the ones between follow the picture.
                            ahead++
                            val map = if (scene == null && ahead % 2 == 1) follower.between(picture) else follower.next(picture)
                            if (map == null) {
                                // Nothing from here: the mask disappears.
                                times += ms; maps += app.headsup.core.color.PackedFrames.pack(ByteArray(w * h))
                                if (follower.lost) lostAhead = ms else goneAhead = ms
                                return@forEachPicture false
                            }
                            times += ms; maps += app.headsup.core.color.PackedFrames.pack(app.headsup.core.color.MaskSequence.bytes(map))
                            true
                        }
                    }
                    if (backward && t0 - endBehind > step / 2 && !stopped()) {
                        // Frames come only forward: kept small (as JPEG) and followed from the playhead
                        // back; the playhead's own frame last, to start from.
                        val kept = ArrayList<Pair<Long, ByteArray>>()
                        TrackingFrames.forEachPicture(context, uri, endBehind, t0 + step / 2, step, app.headsup.color.Segmenter.INPUT) { ms, picture ->
                            if (stopped()) return@forEachPicture false
                            val out = java.io.ByteArrayOutputStream()
                            picture.compress(Bitmap.CompressFormat.JPEG, 92, out)
                            picture.recycle()
                            kept += ms to out.toByteArray()
                            true
                        }
                        val follower = app.headsup.color.MaskFollower(context, w, h, scene, invert, start)
                        var last = t0
                        var behind = 0
                        if (times.isEmpty()) {
                            times += t0; maps += app.headsup.core.color.PackedFrames.pack(app.headsup.core.color.MaskSequence.bytes(start))
                        }
                        // The frame at the playhead starts the run back (it is not stored again).
                        val startFrame = kept.lastOrNull()?.takeIf { kotlin.math.abs(it.first - t0) <= step / 2 }
                        startFrame?.let { (_, jpeg) ->
                            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { follower.begin(it); it.recycle() }
                        }
                        for ((ms, jpeg) in (if (startFrame != null) kept.dropLast(1) else kept).asReversed()) {
                            coroutineContext.ensureActive()
                            if (stopped()) break
                            progress(last - ms)
                            last = ms
                            val picture = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                            behind++
                            val map = if (scene == null && behind % 2 == 1) follower.between(picture) else follower.next(picture)
                            picture.recycle()
                            if (map == null) {
                                times += ms; maps += app.headsup.core.color.PackedFrames.pack(ByteArray(w * h))
                                if (follower.lost) lostBehind = ms else goneBehind = ms
                                break
                            }
                            times += ms; maps += app.headsup.core.color.PackedFrames.pack(app.headsup.core.color.MaskSequence.bytes(map))
                        }
                    }
                    if (times.isEmpty()) return@withContext null
                    val order = times.indices.sortedBy { times[it] }
                    // Kept packed as they come: a long clip's maps would not fit in memory open.
                    val tracked = app.headsup.core.color.MaskSequence(w, h, LongArray(order.size) { times[order[it]] }, app.headsup.core.color.PackedFrames(order.map { maps[it] }, w * h))
                    val merged = old?.merged(tracked) ?: tracked
                    val id = app.headsup.color.MaskSequences.create(context, merged)
                    TrackedAi(id, tracked.timesMs.first(), tracked.timesMs.last(), tracked.timesMs.size, lostAhead, lostBehind, goneAhead, goneBehind, stopped())
                }
                if (result == null) {
                    _state.update { it.copy(messages = it.messages + "Nothing to track: select something in the mask first (Select, or paint it).") }
                    return@runInBackground
                }
                // Fixed (or changed) while it was tracked: what was found is for the mask as it was.
                val now = _state.value.clips.firstOrNull { it.id == clipId }?.masks?.getOrNull(maskIndex)
                if (now == null || now.strokes != strokesAtStart) {
                    _state.update { it.copy(messages = it.messages + "Mask ${maskIndex + 1} changed while it was tracked: track it again.") }
                    return@runInBackground
                }
                fun at(ms: Long) = Formats.duration(ms - clip.trimStartMs)
                _state.update { st ->
                    st.copy(
                        clips = st.clips.map { c ->
                            if (c.id != clipId) c else c.copy(masks = c.masks.mapIndexed { i, m -> if (i != maskIndex) m else m.copy(sequence = result.id, sequenceStrokes = m.strokes.size, keys = emptyList(), anchors = (m.anchors + t0).distinct().sorted()) })
                        },
                        messages = st.messages + listOfNotNull(
                            "Mask found on ${result.frames} frames, ${at(result.fromMs)}–${at(result.toMs)}." +
                                if (result.stopped) " Stopped: what was tracked is kept; Track ▶ / ◀ Track continues from the playhead." else "",
                            result.goneAhead?.let { "Left the picture at ${at(it)}: the mask ends there." },
                            result.goneBehind?.let { "Going back, it left the picture at ${at(it)}." },
                            result.lostAhead?.let { "Lost at ${at(it)}: go there, select it again and Track ▶ to continue." },
                            result.lostBehind?.let { "Lost going back at ${at(it)}: go there, select it again and ◀ Track to continue." },
                        ),
                    )
                }
                refreshPreview()
            }
        }
    }

    private class TrackedAi(
        val id: String, val fromMs: Long, val toMs: Long, val frames: Int,
        val lostAhead: Long?, val lostBehind: Long?, val goneAhead: Long?, val goneBehind: Long?,
        val stopped: Boolean,
    )

    /** Ends the selected mask's tracking with AI: it is drawn from its strokes again, where they were made. */
    fun clearMaskSequence() = editSelectedMask { it.copy(sequence = null, sequenceStrokes = 0, anchors = emptyList()) }

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
        if (mask.kind == MaskKind.BRUSH) {
            trackBrushAI(direction)
            return
        }
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
                    val (fromMasks, fromMaskCubes) = graded?.let { fromMasksOf(s, it) } ?: (emptyList<ColourMask>() to emptyList())
                    val fromMaskMs = graded?.let(s::transitionSource)?.let { it.trimEndMs - 1 } ?: 0L
                    val neighbours = s.selectedClip?.let { i -> listOfNotNull(s.clips.getOrNull(i - 1), s.clips.getOrNull(i + 1)) }.orEmpty()
                        .filter { !it.meta.isPhoto }
                        .associate { n -> n.id to neighbourLook(s, n) }
                    // Their blends in too: the clip after the selected one starts with its own (from the selected's look).
                    val neighbourFrom = s.selectedClip?.let { i -> listOfNotNull(s.clips.getOrNull(i - 1), s.clips.getOrNull(i + 1)) }.orEmpty()
                        .filter { !it.meta.isPhoto && it.colorGrade }
                        .associate { n -> n.id to s.transitionSource(n)?.let { prev -> bakeLook(s, prev) } }
                    _state.update {
                        it.copy(
                            previewCube = cube, previewMaskCubes = maskCubes, previewFromCube = fromCube, previewNeighbourCubes = neighbours,
                            previewNeighbourFrom = neighbourFrom, previewCubesFor = clip?.id,
                            previewFromMasks = fromMasks, previewFromMaskCubes = fromMaskCubes, previewFromMaskMs = fromMaskMs,
                        )
                    }
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

    /**
     * The masks of the clip [clip] blends in from, and the tables inside them: the blend starts
     * from how that clip looked at its end, masks and all, not from its look outside them only.
     */
    private suspend fun fromMasksOf(s: UiState, clip: ClipState): Pair<List<ColourMask>, List<Array<Array<IntArray>>>> {
        val prev = s.transitionSource(clip)?.takeIf { it.colorGrade && it.masks.isNotEmpty() } ?: return emptyList<ColourMask>() to emptyList()
        return prev.masks to s.maskedGrades(prev).map { bakeAlways(s.lutFor(prev), it, prev.wideGamut) }
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

    /** One steady speed for the whole clip: any speed ramp goes. */
    fun setSteadySpeed(index: Int, speed: Float) = updateClip(index) {
        if (it.meta.isPhoto) it else it.copy(speed = speed, speedCurve = emptyList(), muteChangedSpeed = speed < 0.5f)
    }

    /** A ready-made ramp: its speeds are the clip's speeds, so the clip's own speed goes back to 1×. */
    fun applySpeedRamp(index: Int, curve: List<Float>) = updateClip(index) { c ->
        if (c.meta.isPhoto) c else c.copy(
            speed = 1f,
            speedCurve = curve,
            muteChangedSpeed = SpeedCurve.points(curve).any { it.second < 0.5f },
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
                        retouch = clip.retouch,
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
                    GradeSetup(g.cube, g.vignette, g.masks, g.maskCubes, g.fromCube, g.transitionMs, g.presence, g.warp, timeOffsetMs = from, fromMasks = g.fromMasks, fromMaskCubes = g.fromMaskCubes, fromMaskMs = g.fromMaskMs)
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
                val (fromMasks, fromMaskCubes) = if (graded && fromCube != null) fromMasksOf(s, clip) else (emptyList<ColourMask>() to emptyList())
                val fromMaskMs = s.transitionSource(clip)?.let { it.trimEndMs - 1 } ?: 0L
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
                        if (graded) GradeSetup(cube, applied.vignette, masks, maskCubes, fromCube, clip.transitionMs, applied.presence, warp, fromMasks = fromMasks, fromMaskCubes = fromMaskCubes, fromMaskMs = fromMaskMs)
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
                    clipLengthMs = clip.timelineDurationMs,
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
        /** How long a tap waits for a second one (a double tap only fits the view). */
        private const val TAP_WAIT_MS = 280L
        /** Between the maps of a mask tracked with AI: 12 a second. */
        private const val AI_TRACK_STEP_MS = 83L

        /** How near the playhead a flag counts as being on it (⚑ lit; tapped, it takes the flag away). */
        const val FLAG_REACH_MS = 400L
        private const val AI_TRACK_SKY_STEP_MS = 250L
        private const val GLOBAL = "global_"
    }
}

/**
 * What a finger does on a painted mask: select an object (tap, outline, line), paint, erase, move
 * the mask, or only look (VIEW: one finger moves the zoomed picture, nothing is painted).
 */
enum class BrushMode(val symbol: String, val label: String) {
    // View first: looking closer comes before working on the mask.
    VIEW("✋", "View"), SELECT("◎", "Select"), PUSH("↝", "Push"), PAINT("✎", "Paint"), ERASE("⌫", "Erase"), SOFTEN("≈", "Soften"), MOVE("✥", "Move"),
}

/** The brush: its radius (of the picture's height) and flow, 0..1. */
data class BrushSettings(
    val mode: BrushMode = BrushMode.PAINT,
    val radius: Float = 0.06f,
    val flow: Float = 1f,
    /** How soft the brush's edge is for the strokes painted from now on (each keeps its own). */
    val softness: Float = 0.5f,
    /** Select takes what it finds away from the mask instead of adding it. */
    val subtract: Boolean = false,
)
