package app.headsup.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small frames of the clips for the timeline's video track. Frames are asked for by file time
 * rounded to a step that suits the zoom; missing ones are loaded in the background, newest
 * request first (what is on screen now), and until then the nearest frame already loaded stands
 * in. [version] changes whenever a frame arrives, so the timeline redraws.
 */
class Filmstrip(private val context: Context, private val scope: CoroutineScope) {
    val version = mutableIntStateOf(0)
    private val frames = HashMap<Uri, TreeMap<Long, ImageBitmap>>()
    private val pending = ArrayDeque<Pair<Uri, Long>>()
    private val queued = HashSet<Pair<Uri, Long>>()
    private val retrievers = HashMap<Uri, MediaMetadataRetriever>()
    private var running = false
    private var count = 0

    /** While set (during playback) frames are only queued: decoding them would slow the player down. */
    var held = false
        set(value) {
            field = value
            if (!value) start()
        }

    /** The frame of [uri] at [fileMs] (rounded to [stepMs]), or the nearest one loaded so far. */
    fun frame(uri: Uri, fileMs: Long, stepMs: Long, photo: Boolean): ImageBitmap? {
        val key = if (photo) 0L else (fileMs / stepMs) * stepMs
        val loaded = frames[uri]
        loaded?.get(key)?.let { return it }
        request(uri, key, photo)
        if (loaded == null || loaded.isEmpty()) return null
        val below = loaded.floorEntry(key)
        val above = loaded.ceilingEntry(key)
        return when {
            below == null -> above?.value
            above == null -> below.value
            key - below.key <= above.key - key -> below.value
            else -> above.value
        }
    }

    private fun request(uri: Uri, key: Long, photo: Boolean) {
        val item = uri to (if (photo) PHOTO else key)
        if (!queued.add(item)) return
        pending.addLast(item)
        // Only the latest requests matter: the timeline asks again for what stays on screen.
        while (pending.size > 64) queued.remove(pending.removeFirst())
        start()
    }

    private fun start() {
        if (running || held || pending.isEmpty()) return
        running = true
        scope.launch {
            try {
                while (!held) {
                    val (u, k) = pending.removeLastOrNull() ?: break
                    queued.remove(u to k)
                    val bitmap = withContext(Dispatchers.IO) { runCatching { load(u, k) }.getOrNull() }
                    if (bitmap != null) {
                        frames.getOrPut(u) { TreeMap() }[if (k == PHOTO) 0L else k] = bitmap
                        if (++count > LIMIT) trim()
                        version.intValue++
                    }
                }
            } finally {
                running = false
            }
        }
    }

    private fun load(uri: Uri, key: Long): ImageBitmap? {
        if (key == PHOTO) {
            // A raw file: the camera's own small preview, rather than decoding the sensor data.
            if (app.headsup.media.RawPhotos.looksRaw(context, uri)) {
                app.headsup.media.Exif.thumbnail(context, uri)?.let { return it.asImageBitmap() }
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
        }
        val r = retrievers.getOrPut(uri) { MediaMetadataRetriever().apply { setDataSource(context, uri) } }
        return r.getScaledFrameAtTime(key * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)?.asImageBitmap()
    }

    /** Keeps memory bounded: drops every other frame of the busiest clip. */
    private fun trim() {
        val busiest = frames.values.maxByOrNull { it.size } ?: return
        busiest.keys.filterIndexed { i, _ -> i % 2 == 1 }.forEach { busiest.remove(it) }
        count = frames.values.sumOf { it.size }
    }

    fun release() {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }

    private companion object {
        const val PHOTO = -1L
        const val LIMIT = 400
    }
}
