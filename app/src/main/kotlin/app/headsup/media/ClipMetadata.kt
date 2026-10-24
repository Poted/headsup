package app.headsup.media

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.headsup.core.sync.ClipInfo
import app.headsup.core.sync.ClockReading
import app.headsup.core.sync.ExifTimeParser
import app.headsup.core.sync.FilenameTimeParser
import app.headsup.core.sync.Mp4DateParser
import app.headsup.core.sync.TimeAnchor
import app.headsup.core.sync.TimeEvidence
import app.headsup.core.sync.TimeSource
import java.util.Locale
import kotlin.math.abs

/** Codec and colour description of the video track, as stored in the file. */
data class VideoFormatInfo(
    val mime: String,
    val tenBit: Boolean,
    /** "SDR", "HLG", "PQ" or "unknown". Log footage such as D-Log M is usually tagged SDR. */
    val transfer: String,
    val primaries: String,
) {
    val isHdr: Boolean get() = transfer == "HLG" || transfer == "PQ"

    override fun toString() =
        "${mime.removePrefix("video/").uppercase(Locale.US)} ${if (tenBit) "10-bit" else "8-bit"} $transfer $primaries"

    companion object {
        fun from(format: MediaFormat): VideoFormatInfo {
            fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val profile = int(MediaFormat.KEY_PROFILE)
            val tenBit = when (mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> profile == CodecProfileLevel.HEVCProfileMain10 ||
                    profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
                MediaFormat.MIMETYPE_VIDEO_AVC -> profile == CodecProfileLevel.AVCProfileHigh10
                else -> false
            }
            val transfer = when (int(MediaFormat.KEY_COLOR_TRANSFER)) {
                MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
                MediaFormat.COLOR_TRANSFER_LINEAR -> "linear"
                else -> "unknown"
            }
            val primaries = when (int(MediaFormat.KEY_COLOR_STANDARD)) {
                MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
                MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
                MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601"
                else -> "unknown"
            }
            return VideoFormatInfo(mime, tenBit, transfer, primaries)
        }

        fun read(context: Context, uri: Uri): VideoFormatInfo? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?.let(::from)
            } catch (e: Exception) {
                null
            } finally {
                extractor.release()
            }
        }
    }
}

data class ClipMetadata(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    /** Display size, i.e. already swapped for rotated (portrait) recordings. */
    val width: Int,
    val height: Int,
    /** Container bit rate (video + audio), bits per second. */
    val bitrate: Int?,
    val frameRate: Float?,
    val format: VideoFormatInfo?,
    val info: ClipInfo,
    /** A still photo: shown for a chosen time on the timeline, exported as video. */
    val isPhoto: Boolean = false,
    /** A raw (DNG) photo, developed by the app; its size is the developed picture's. */
    val isRaw: Boolean = false,
    /** Size of the file in bytes, when known. */
    val bytes: Long? = null,
)

object ClipMetadataReader {
    private val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)

    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "dng")

    /** Longest a photo can stay on the timeline. */
    const val PHOTO_MAX_MS = 30_000L

    fun isPhoto(context: Context, uri: Uri, name: String): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true ||
            name.substringAfterLast('.', "").lowercase() in PHOTO_EXTENSIONS

    fun read(context: Context, uri: Uri): ClipMetadata {
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        if (isPhoto(context, uri, name)) return readPhoto(context, uri, name)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rotated = rotation == 90 || rotation == 270
            val frames = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val frameRate = if (frames != null && duration > 0) snapFrameRate(frames * 1000f / duration) else null

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
                bitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                frameRate = frameRate,
                format = VideoFormatInfo.read(context, uri),
                info = ClipInfo(duration, evidence),
            )
        } finally {
            retriever.release()
        }
    }

    /** Size (after EXIF rotation) and the moment it was taken, from EXIF or the file name. */
    private fun readPhoto(context: Context, uri: Uri, name: String): ClipMetadata {
        // A raw file is recognised by its content (pickers may name it anything); its size is the developed picture's.
        val rawSize = if (RawPhotos.looksRaw(context, uri)) RawPhotos.size(context, uri) else null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (rawSize == null) context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) } }.getOrNull()
        val orientation = exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 || orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270
        val evidence = listOfNotNull(
            ExifTimeParser.parse(
                exif?.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
                exif?.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            ),
            FilenameTimeParser.parse(name),
        )
        return ClipMetadata(
            uri = uri,
            name = name,
            durationMs = PHOTO_MAX_MS,
            width = rawSize?.first ?: if (rotated) bounds.outHeight else bounds.outWidth,
            height = rawSize?.second ?: if (rotated) bounds.outWidth else bounds.outHeight,
            bitrate = null,
            frameRate = null,
            format = null,
            // A photo is a moment: matched as a very short clip.
            info = ClipInfo(1_000L, evidence),
            isPhoto = true,
            isRaw = rawSize != null,
            bytes = fileSize(context, uri),
        )
    }

    /** Bytes of [uri]'s file: a local copy's length, or what its provider reports. */
    fun fileSize(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") uri.path?.let { java.io.File(it).length() }
        else context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()?.takeIf { it > 0 }

    /** Frame counts over a rounded duration give e.g. 29.94; report the nominal rate. */
    private fun snapFrameRate(measured: Float): Float =
        STANDARD_RATES.minBy { abs(it - measured) }.takeIf { abs(it - measured) / it < 0.02f } ?: measured

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
