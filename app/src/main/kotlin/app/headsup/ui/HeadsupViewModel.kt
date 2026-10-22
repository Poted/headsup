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
import java.time.ZoneId

data class LoadedActivity(val name: String, val track: ActivityTrack)

data class ClipState(
    val meta: ClipMetadata,
    val sync: SyncResult?,
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
) {
    /** Time evidence used for matching: the manual start if set, otherwise the file's own. */
    val timeInfo: ClipInfo
        get() = manualStart?.let {
            ClipInfo(meta.durationMs, listOf(TimeEvidence(TimeSource.MANUAL, ClockReading.Local(it), TimeAnchor.START)))
        } ?: meta.info

    /** UTC time of the telemetry shown at [positionMs] into the clip. */
    fun telemetryUtc(positionMs: Long): Long? = sync?.let { it.startUtcMs + positionMs + manualOffsetMs }
}

data class UiState(
    val activities: List<LoadedActivity> = emptyList(),
    val clips: List<ClipState> = emptyList(),
    val selectedClip: Int? = null,
    val style: DashboardStyle = DashboardStyle(),
    val busy: Boolean = false,
    val messages: List<String> = emptyList(),
    val grade: ColorGrade = ColorGrade(),
    val luts: List<String> = emptyList(),
    /** Grade shown in the preview instead of [grade] while comparing (one correction held off). */
    val compareGrade: ColorGrade? = null,
    /** Colour table for the selected clip's preview; null when its colours stay untouched. */
    val previewCube: Array<Array<IntArray>>? = null,
    val bitrateFactor: Float = 1f,
    /** Vectorscope and skin measurement of the last frame tapped in the preview. */
    val scope: ScopeResult? = null,
) {
    val selected: ClipState? get() = selectedClip?.let { clips.getOrNull(it) }
}

class HeadsupViewModel(app: Application) : AndroidViewModel(app) {
    private val stylePrefs = StylePrefs(app)
    private val gradeStore = GradeStore(app)
    private val _state = MutableStateFlow(
        UiState(
            style = stylePrefs.load(),
            grade = gradeStore.loadGrade(),
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

    fun addActivities(uris: List<Uri>) = load(uris) { uri ->
        val context = getApplication<Application>()
        val name = ClipMetadataReader.displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val track = context.contentResolver.openInputStream(uri)?.use { FitActivityReader.read(it) }
            ?: error("Cannot open $name")
        Loaded.Activity(LoadedActivity(name, track))
    }

    fun addClips(uris: List<Uri>) = load(uris) { uri ->
        Loaded.Clip(ClipMetadataReader.read(getApplication<Application>(), uri))
    }

    fun selectClip(index: Int) {
        scopeFrame = null
        _state.update { it.copy(selectedClip = index, scope = null) }
        refreshPreview()
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
            s.copy(luts = gradeStore.names(), clips = s.clips.map { if (it.lutName == name) it.copy(lutName = null) else it })
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

    fun updateGrade(transform: (ColorGrade) -> ColorGrade) {
        _state.update { it.copy(grade = transform(it.grade)) }
        gradeStore.saveGrade(_state.value.grade)
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
                    val cube = if (clip == null || !clip.colorGrade) null else bake(clip.lutName, s.compareGrade ?: s.grade)
                    _state.update { it.copy(previewCube = cube) }
                    analyseScope()
                }
            } finally {
                previewRunning = false
            }
        }
    }

    /** Null when nothing would change the colours. */
    private suspend fun bake(lutName: String?, grade: ColorGrade): Array<Array<IntArray>>? {
        val lut = lutName?.let { name ->
            lutCache[name] ?: withContext(Dispatchers.IO) { gradeStore.load(name) }?.also { lutCache[name] = it }
        }
        if (lut == null && grade.isNeutralColour) return null
        return withContext(Dispatchers.Default) { LutBaker.bake(lut, grade) }
    }

    // Export

    fun setBitrateFactor(factor: Float) = _state.update { it.copy(bitrateFactor = factor) }

    fun exportVideos() = enqueue(ExportKind.VIDEO, state.value.clips.indices.toList())

    fun exportOverlays() = enqueue(ExportKind.PNG_SEQUENCE, state.value.clips.indices.toList())

    fun runTenBitTest() = enqueue(ExportKind.TEN_BIT_TEST, listOfNotNull(state.value.selectedClip))

    fun cancelExports() = exportManager.cancelAll()

    fun clearFinishedExports() = exportManager.clearFinished()

    private fun enqueue(kind: ExportKind, indices: List<Int>) {
        val s = state.value
        viewModelScope.launch {
            val cubes = HashMap<String?, Array<Array<IntArray>>?>()
            val requests = indices.mapNotNull { i ->
                val clip = s.clips.getOrNull(i) ?: return@mapNotNull null
                val sync = clip.sync ?: return@mapNotNull null
                val activity = s.activities.getOrNull(sync.trackIndex) ?: return@mapNotNull null
                val graded = kind != ExportKind.PNG_SEQUENCE && clip.colorGrade
                val cube = if (graded) cubes.getOrPut(clip.lutName) { bake(clip.lutName, s.grade) } else null
                ExportRequest(
                    kind = kind,
                    uri = clip.meta.uri,
                    name = clip.meta.name,
                    durationMs = clip.meta.durationMs,
                    width = clip.meta.width,
                    height = clip.meta.height,
                    frameRate = clip.meta.frameRate,
                    sourceBitrate = clip.meta.bitrate,
                    sourceFormat = clip.meta.format,
                    track = activity.track,
                    telemetryStartUtcMs = sync.startUtcMs + clip.manualOffsetMs,
                    style = s.style,
                    grade = if (graded) GradeSetup(cube, s.grade.vignette) else null,
                    bitrateFactor = s.bitrateFactor,
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
                val newClips = loaded.filterIsInstance<Loaded.Clip>().map { ClipState(it.meta, sync = null, lutName = defaultLut) }
                val clips = (s.clips + newClips).distinctBy { it.meta.uri }
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
        const val MAX_OFFSET_MS = 60_000L
    }
}
