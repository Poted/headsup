package app.headsup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.headsup.HeadsupApp
import app.headsup.color.GradeStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
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
    /** Colour table for the selected clip's preview; null when its colours stay untouched. */
    val previewCube: Array<Array<IntArray>>? = null,
    val bitrateFactor: Float = 1f,
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
    private var previewJob: Job? = null

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
        _state.update { it.copy(selectedClip = index) }
        refreshPreview(delayMs = 0)
    }

    fun removeClip(index: Int) {
        _state.update { s ->
            val clips = s.clips.filterIndexed { i, _ -> i != index }
            s.copy(clips = clips, selectedClip = s.selectedClip?.let { sel -> if (sel == index) null else if (sel > index) sel - 1 else sel })
        }
        refreshPreview(delayMs = 0)
    }

    fun removeActivity(index: Int) = _state.update { s ->
        resynced(s.copy(activities = s.activities.filterIndexed { i, _ -> i != index }))
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
        refreshPreview(delayMs = 0)
    }

    fun setClipLut(index: Int, name: String?) {
        if (name != null) gradeStore.defaultLut = name
        updateClip(index) { it.copy(lutName = name) }
    }

    fun setLutForAllClips(name: String?) {
        if (name != null) gradeStore.defaultLut = name
        _state.update { s -> s.copy(clips = s.clips.map { it.copy(lutName = name) }) }
        refreshPreview(delayMs = 0)
    }

    fun setClipGrade(index: Int, enabled: Boolean) = updateClip(index) { it.copy(colorGrade = enabled) }

    fun updateGrade(transform: (ColorGrade) -> ColorGrade) {
        _state.update { it.copy(grade = transform(it.grade)) }
        gradeStore.saveGrade(_state.value.grade)
        refreshPreview()
    }

    private fun updateClip(index: Int, transform: (ClipState) -> ClipState) {
        _state.update { s -> s.copy(clips = s.clips.mapIndexed { i, c -> if (i == index) transform(c) else c }) }
        if (index == _state.value.selectedClip) refreshPreview(delayMs = 0)
    }

    /** Recomputes the selected clip's colour table, shortly after the last slider change. */
    private fun refreshPreview(delayMs: Long = 150) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(delayMs)
            val s = _state.value
            val clip = s.selected
            val cube = if (clip == null || !clip.colorGrade) null else bake(clip.lutName, s.grade)
            _state.update { it.copy(previewCube = cube) }
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
            refreshPreview(delayMs = 0)
        }
    }

    /** Matches every clip again, e.g. after another FIT file arrived. Manual offsets are kept. */
    private fun resynced(s: UiState): UiState {
        val resolver = SyncResolver(ZoneId.systemDefault())
        val tracks = s.activities.map { it.track }
        return s.copy(clips = s.clips.map { it.copy(sync = resolver.resolve(it.meta.info, tracks)) })
    }

    companion object {
        const val MAX_OFFSET_MS = 60_000L
    }
}
