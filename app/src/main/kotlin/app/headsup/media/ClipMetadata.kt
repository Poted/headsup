package app.headsup.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.headsup.core.sync.ClipInfo
import app.headsup.core.sync.ClockReading
import app.headsup.core.sync.FilenameTimeParser
import app.headsup.core.sync.Mp4DateParser
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeEvidence
import app.headsup.core.sync.TimeSource

data class ClipMetadata(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    /** Display size, i.e. already swapped for rotated (portrait) recordings. */
    val width: Int,
    val height: Int,
    val info: ClipInfo,
)

object ClipMetadataReader {
    fun read(context: Context, uri: Uri): ClipMetadata {
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rotated = rotation == 90 || rotation == 270

            val evidence = listOfNotNull(
                FilenameTimeParser.parse(name),
                Mp4DateParser.parse(key(MediaMetadataRetriever.METADATA_KEY_DATE))?.let {
                    TimeEvidence(TimeSource.MP4_CREATION_TIME, ClockReading.LabelledUtc(it), TimeAnchor.UNKNOWN)
                },
            )
            return ClipMetadata(
                uri = uri,
                name = name,
                durationMs = duration,
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                info = ClipInfo(duration, evidence),
            )
        } finally {
            retriever.release()
        }
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
