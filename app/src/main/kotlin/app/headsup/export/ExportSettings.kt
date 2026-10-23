package app.headsup.export

import androidx.media3.common.MimeTypes

enum class VideoCodec(val label: String, val mimeType: String) {
    HEVC("HEVC", MimeTypes.VIDEO_H265),
    H264("H.264", MimeTypes.VIDEO_H264),
}

enum class ExportPreset(val label: String) { VERTICAL("Reels · TikTok · Shorts"), YOUTUBE("YouTube") }

/** Video export choices from the export panel. The frame rate is the timeline's (a project setting). */
data class ExportSettings(
    /** One file for the whole timeline, or one per clip. */
    val wholeTimeline: Boolean = true,
    /** Output height in pixels (width follows the shape), or null for the source's. */
    val height: Int? = null,
    /** Bit rate relative to the source's, scaled to the output size. */
    val bitrateFactor: Float = 1f,
    val codec: VideoCodec = VideoCodec.HEVC,
    /** HEVC Main10 through [HdrRoute] (experimental): BT.709 SDR like DJI Mimo, or HLG if [hlg]. */
    val tenBit: Boolean = false,
    val hlg: Boolean = false,
    /** HLG signal level where the picture's white goes in an HLG export. */
    val hdrWhite: Float = HdrRoute.DEFAULT_WHITE,
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
    /** 10-bit through [HdrRoute]; always HEVC. BT.709 SDR, or HLG with white at [hdrWhite] if [hlg]. */
    val tenBit: Boolean = false,
    val hlg: Boolean = false,
    val hdrWhite: Float = HdrRoute.DEFAULT_WHITE,
) {
    override fun toString() = "${width}×$height" + (frameRate?.let { " · ${formatFps(it)} fps" } ?: "") +
        " · ${bitrate / 1_000_000} Mb/s · ${codec.label}" + (if (tenBit) if (hlg) " · 10-bit HLG" else " · 10-bit" else "")

    companion object {
        fun formatFps(fps: Float) = if (fps % 1f == 0f) fps.toInt().toString() else String.format(java.util.Locale.US, "%.2f", fps)

        /** Even numbers, as encoders need. */
        fun even(v: Float) = (kotlin.math.round(v / 2f).toInt() * 2).coerceAtLeast(2)
    }
}

/**
 * One export to run: a video made of [parts] (several = joined into one file), or a PNG sequence
 * of one clip. [audio] plays under the video, mixed with the clips' sound at [clipSound].
 */
data class JobSpec(
    val kind: ExportKind,
    val title: String,
    val parts: List<ExportRequest>,
    val output: OutputSpec?,
    /** Audio clips under the video (only for a whole-timeline export). */
    val audio: List<AudioClip> = emptyList(),
    /** Level of the video clips' own sound, 0..1. */
    val clipSound: Float = 1f,
)
