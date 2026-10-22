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
import app.headsup.media.ClipMetadata
import app.headsup.media.ClipMetadataReader
import app.headsup.render.DashboardStyle
import app.headsup.render.Placement
import app.headsup.render.StylePrefs
import app.headsup.render.Widget
import app.headsup.export.ExportKind
import app.headsup.export.ExportRequest
import app.headsup.export.GradeSetup
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
    val lutName: String? = null,
    /** LUT and corrections baked for preview and export; null when colours stay untouched. */
    val gradeCube: Array<Array<IntArray>>? = null,
    val bitrateFactor: Float = 1f,
)

class HeadsupViewModel(app: Application) : AndroidViewModel(app) {
    private val stylePrefs = StylePrefs(app)
    private val gradeStore = GradeStore(app)
    private val _state = MutableStateFlow(UiState(style = stylePrefs.load(), grade = gradeStore.loadGrade(), lutName = gradeStore.lutName))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val exportManager = (app as HeadsupApp).exportManager
    val exportJobs = exportManager.jobs

    private var lut: CubeLut? = null
    private var bakeJob: Job? = null

    init {
        viewModelScope.launch {
            lut = withContext(Dispatchers.IO) { gradeStore.loadLut() }
            rebake(delayMs = 0)
        }
    }

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

    fun selectClip(index: Int) = _state.update { it.copy(selectedClip = index) }

    fun removeClip(index: Int) = _state.update { s ->
        val clips = s.clips.filterIndexed { i, _ -> i != index }
        s.copy(clips = clips, selectedClip = s.selectedClip?.let { sel -> if (sel == index) null else if (sel > index) sel - 1 else sel })
    }

    fun removeActivity(index: Int) = _state.update { s ->
        resynced(s.copy(activities = s.activities.filterIndexed { i, _ -> i != index }))
    }

    fun setManualOffset(index: Int, offsetMs: Long) = _state.update { s ->
        s.copy(clips = s.clips.mapIndexed { i, c -> if (i == index) c.copy(manualOffsetMs = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)) else c })
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

    fun loadLut(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val name = ClipMetadataReader.displayName(context, uri) ?: "LUT"
            runCatching { withContext(Dispatchers.IO) { gradeStore.importLut(uri, name) } }
                .onSuccess { loaded ->
                    lut = loaded
                    _state.update { it.copy(lutName = name) }
                    rebake(delayMs = 0)
                }
                .onFailure { e -> _state.update { it.copy(messages = it.messages + "Could not load LUT: ${e.message}") } }
        }
    }

    fun removeLut() {
        gradeStore.removeLut()
        lut = null
        _state.update { it.copy(lutName = null) }
        rebake(delayMs = 0)
    }

    fun updateGrade(transform: (ColorGrade) -> ColorGrade) {
        _state.update { it.copy(grade = transform(it.grade)) }
        gradeStore.saveGrade(_state.value.grade)
        rebake()
    }

    fun setClipGrade(index: Int, enabled: Boolean) = _state.update { s ->
        s.copy(clips = s.clips.mapIndexed { i, c -> if (i == index) c.copy(colorGrade = enabled) else c })
    }

    fun setBitrateFactor(factor: Float) = _state.update { it.copy(bitrateFactor = factor) }

    /** Recomputes the colour table shortly after the last slider change. */
    private fun rebake(delayMs: Long = 150) {
        bakeJob?.cancel()
        bakeJob = viewModelScope.launch {
            delay(delayMs)
            val grade = _state.value.grade
            val currentLut = lut
            val cube = if (currentLut == null && grade.isNeutralColour) null
            else withContext(Dispatchers.Default) { LutBaker.bake(currentLut, grade) }
            _state.update { it.copy(gradeCube = cube) }
        }
    }

    fun exportVideos() = enqueue(ExportKind.VIDEO, state.value.clips.indices.toList())

    fun exportOverlays() = enqueue(ExportKind.PNG_SEQUENCE, state.value.clips.indices.toList())

    fun runTenBitTest() = enqueue(ExportKind.TEN_BIT_TEST, listOfNotNull(state.value.selectedClip))

    fun cancelExports() = exportManager.cancelAll()

    fun clearFinishedExports() = exportManager.clearFinished()

    private fun enqueue(kind: ExportKind, indices: List<Int>) {
        val s = state.value
        val requests = indices.mapNotNull { i ->
            val clip = s.clips.getOrNull(i) ?: return@mapNotNull null
            val sync = clip.sync ?: return@mapNotNull null
            val activity = s.activities.getOrNull(sync.trackIndex) ?: return@mapNotNull null
            val graded = kind != ExportKind.PNG_SEQUENCE && clip.colorGrade
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
                grade = if (graded) GradeSetup(s.gradeCube, s.grade.vignette) else null,
                bitrateFactor = s.bitrateFactor,
            )
        }
        if (requests.isEmpty()) {
            _state.update { it.copy(messages = it.messages + "Nothing to export: no clip is matched to an activity.") }
            return
        }
        exportManager.enqueue(requests)
    }

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
            _state.update { s ->
                val activities = (s.activities + loaded.filterIsInstance<Loaded.Activity>().map { it.activity })
                    .distinctBy { it.track.startMs to it.track.endMs }
                    .sortedBy { it.track.startMs }
                val newClips = loaded.filterIsInstance<Loaded.Clip>().map { ClipState(it.meta, sync = null) }
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
