package app.headsup.export

import androidx.media3.common.MimeTypes

enum class VideoCodec(val label: String, val mimeType: String) {
    HEVC("HEVC", MimeTypes.VIDEO_H265),
    H264("H.264", MimeTypes.VIDEO_H264),
}

/** Video export choices from the export panel. The frame rate is the timeline's (a project setting). */
data class ExportSettings(
    /** One file for the whole timeline, or one per clip. */
    val wholeTimeline: Boolean = true,
    /** Output height in pixels (width follows the shape), or null for the source's. */
    val height: Int? = null,
    /** Bit rate relative to the source's, scaled to the output size. */
    val bitrateFactor: Float = 1f,
    val codec: VideoCodec = VideoCodec.HEVC,
) {
    companion object {
        val HEIGHTS: List<Pair<Int?, String>> = listOf(null to "Source", 2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p")
        val QUALITIES: List<Pair<Float, String>> = listOf(1f to "Like source", 1.5f to "High", 2f to "Max")
        val FRAME_RATES: List<Float> = listOf(24f, 25f, 30f, 50f, 60f)
    }
}

/** What one export job writes: frame size, frame rate (null = keep each clip's), codec, bit rate and file name. */
data class OutputSpec(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val codec: VideoCodec,
    val bitrate: Int,
    val fileName: String,
) {
    override fun toString() = "${width}×$height" + (frameRate?.let { " · ${formatFps(it)} fps" } ?: "") +
        " · ${bitrate / 1_000_000} Mb/s · ${codec.label}"

    companion object {
        fun formatFps(fps: Float) = if (fps % 1f == 0f) fps.toInt().toString() else String.format(java.util.Locale.US, "%.2f", fps)

        /** Even numbers, as encoders need. */
        fun even(v: Float) = (kotlin.math.round(v / 2f).toInt() * 2).coerceAtLeast(2)
    }
}

/**
 * One export to run: a video made of [parts] (several = joined into one file), or a PNG sequence
 * of one clip. [music] plays under the whole video.
 */
data class JobSpec(
    val kind: ExportKind,
    val title: String,
    val parts: List<ExportRequest>,
    val output: OutputSpec?,
    val music: MusicTrack? = null,
)
