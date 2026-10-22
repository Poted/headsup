package app.headsup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Dispatchers
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
)

class HeadsupViewModel(app: Application) : AndroidViewModel(app) {
    private val stylePrefs = StylePrefs(app)
    private val _state = MutableStateFlow(UiState(style = stylePrefs.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

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
