package app.headsup.export

import android.net.Uri
import app.headsup.core.color.ColourMask
import app.headsup.core.frame.Reframe
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.TelemetrySampler
import app.headsup.media.VideoFormatInfo
import app.headsup.render.DashboardData
import app.headsup.render.DashboardStyle
import app.headsup.render.EffectSettings
import java.time.Instant
import java.time.ZoneId

enum class ExportKind { VIDEO, PNG_SEQUENCE, TEN_BIT_TEST }

/** The activity matched to a clip, and the UTC time shown on its first frame (manual correction included). */
class Telemetry(val track: ActivityTrack, val startUtcMs: Long)

/**
 * Colour transform for one clip: baked LUT cube (null = untouched colours), vignette strength,
 * masks with one cube each, and the previous clip's cube to blend from during [transitionMs].
 */
class GradeSetup(
    val cube: Array<Array<IntArray>>?,
    val vignette: Float,
    val masks: List<ColourMask> = emptyList(),
    val maskCubes: List<Array<Array<IntArray>>> = emptyList(),
    val fromCube: Array<Array<IntArray>>? = null,
    val transitionMs: Long = 0L,
) {
    /** True when a single colour table is not enough. */
    val needsGradeEffect: Boolean get() = masks.isNotEmpty() || (fromCube != null && transitionMs > 0)
}

data class ExportRequest(
    val kind: ExportKind,
    val uri: Uri,
    val name: String,
    /** Length of the exported part. */
    val durationMs: Long,
    /** Part of the source file to export, in file time. */
    val trimStartMs: Long,
    val trimEndMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val sourceBitrate: Int?,
    val sourceFormat: VideoFormatInfo?,
    /** Watch data for the dashboard; null when the clip has none (the video exports without it). */
    val telemetry: Telemetry?,
    val style: DashboardStyle,
    val effects: EffectSettings,
    val grade: GradeSetup?,
    val bitrateFactor: Float,
    /** Zoom, position, straightening and output shape of the picture. */
    val reframe: Reframe = Reframe(),
) {
    /** Size of the exported frame: the source's, or the largest frame of the reframe's shape inside it (even numbers for encoders). */
    val frameWidth: Int get() = (reframe.outputSize(width.toFloat(), height.toFloat()).first.toInt() / 2 * 2).coerceAtLeast(2)
    val frameHeight: Int get() = (reframe.outputSize(width.toFloat(), height.toFloat()).second.toInt() / 2 * 2).coerceAtLeast(2)

    /** File name without extension; trimmed parts get their start second so names stay distinct. */
    val baseName: String
        get() = name.substringBeforeLast('.') + if (trimStartMs > 0) "_from${trimStartMs / 1000}s" else ""

    /** Builds what the renderer needs for a position in the clip, or null without watch data. */
    fun dataAt(positionMs: Long, sampler: TelemetrySampler?): DashboardData? {
        val t = telemetry ?: return null
        val s = sampler ?: return null
        val utc = t.startUtcMs + positionMs
        val zone = t.track.utcOffsetSeconds ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
        return DashboardData(t.track, s.frameAt(utc), utc, zone)
    }

    /** Close to the source by default; never below 10 Mb/s or above what encoders accept. */
    fun targetBitrate(): Int {
        val base = (sourceBitrate ?: 60_000_000).toLong()
        return (base * bitrateFactor).toLong().coerceIn(10_000_000L, 200_000_000L).toInt()
    }
}
