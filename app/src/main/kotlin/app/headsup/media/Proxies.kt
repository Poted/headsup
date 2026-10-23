package app.headsup.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far the preview file of a clip is. */
sealed interface ProxyState {
    data class Making(val progress: Float) : ProxyState
    data class Ready(val file: Uri) : ProxyState
    data object Waiting : ProxyState
    data object Failed : ProxyState
}

/**
 * Light copies of large clips (above 1080p) for the preview only: 1080p HEVC with the same
 * length, colours and sound, made one at a time in the background. The phone decodes one 8K
 * stream at a time, so with the originals two clips can never be ready at a cut; with these the
 * next clip waits decoded in a second player and cuts play without a pause. Export always reads
 * the originals. Files are kept by the source's address, so a project opened again finds them.
 */
@OptIn(UnstableApi::class)
class Proxies(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "proxies").apply { mkdirs() }
    private val _states = MutableStateFlow<Map<Uri, ProxyState>>(emptyMap())
    val states: StateFlow<Map<Uri, ProxyState>> = _states.asStateFlow()

    private val queue = ArrayDeque<Uri>()
    private val attempts = HashMap<Uri, Int>()
    private var transformer: Transformer? = null
    private var current: Uri? = null
    private var watcher: Job? = null
    private var resume: Job? = null

    /**
     * While set (the preview plays), no preview file is made: decoding a large clip for it would
     * take the decoder the player needs. The one being made starts again once playback stops.
     */
    var held = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                resume?.cancel()
                current?.let { uri ->
                    cancelRunning()
                    queue.addFirst(uri)
                    _states.update { it + (uri to ProxyState.Waiting) }
                }
            } else {
                resume = scope.launch {
                    delay(RESUME_DELAY_MS)
                    next()
                }
            }
        }

    /** Whether [meta] gets a preview file. */
    fun wanted(meta: ClipMetadata) = !meta.isPhoto && minOf(meta.width, meta.height) > SHORT_SIDE

    /** Asks for preview files of [clips]; ready ones are found on disk, others are queued. */
    fun request(clips: List<ClipMetadata>) {
        for (meta in clips.distinctBy { it.uri }) {
            if (!wanted(meta) || meta.uri in _states.value) continue
            val file = fileFor(meta.uri)
            if (file.exists()) {
                _states.update { it + (meta.uri to ProxyState.Ready(Uri.fromFile(file))) }
            } else {
                queue.addLast(meta.uri)
                _states.update { it + (meta.uri to ProxyState.Waiting) }
            }
        }
        if (!held) next()
    }

    private fun fileFor(uri: Uri): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.mp4")
    }

    private fun next() {
        if (transformer != null || held) return
        val uri = queue.removeFirstOrNull() ?: return
        val target = fileFor(uri)
        val partial = File(dir, target.name + ".part").apply { delete() }
        current = uri
        _states.update { it + (uri to ProxyState.Making(0f)) }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(SHORT_SIDE))))
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            // HEVC keeps 10-bit HDR (HLG) clips as they are, so the preview's colours match.
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (transformer == null) return
                    val ok = partial.renameTo(target)
                    finished(uri, if (ok) ProxyState.Ready(Uri.fromFile(target)) else ProxyState.Failed)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (transformer == null) return
                    partial.delete()
                    // Often a decoder still busy elsewhere: try again a little later, a few times.
                    val tries = (attempts[uri] ?: 0) + 1
                    attempts[uri] = tries
                    if (tries < MAX_TRIES) {
                        queue.addLast(uri)
                        finished(uri, ProxyState.Waiting, retryDelayMs = RETRY_DELAY_MS)
                    } else {
                        finished(uri, ProxyState.Failed)
                    }
                }
            })
            .build()
        transformer = t
        t.start(item, partial.absolutePath)
        watcher = scope.launch {
            val holder = ProgressHolder()
            while (transformer === t) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _states.update { it + (uri to ProxyState.Making(holder.progress / 100f)) }
                }
                delay(500)
            }
        }
    }

    private fun finished(uri: Uri, state: ProxyState, retryDelayMs: Long = 0L) {
        transformer = null
        current = null
        watcher?.cancel()
        _states.update { it + (uri to state) }
        scope.launch {
            delay(retryDelayMs)
            next()
        }
    }

    private fun cancelRunning() {
        val t = transformer ?: return
        transformer = null
        watcher?.cancel()
        t.cancel()
        current?.let { File(dir, fileFor(it).name + ".part").delete() }
        current = null
    }

    fun release() = cancelRunning()

    private companion object {
        const val SHORT_SIDE = 1080
        const val BITRATE = 12_000_000
        const val MAX_TRIES = 3
        const val RETRY_DELAY_MS = 4_000L
        const val RESUME_DELAY_MS = 1_500L
    }
}
