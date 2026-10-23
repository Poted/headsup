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
    val track: ActivityTrack,
    /** UTC of the telemetry shown on the first frame, including any manual correction. */
    val telemetryStartUtcMs: Long,
    val style: DashboardStyle,
    val effects: EffectSettings,
    val grade: GradeSetup?,
    val bitrateFactor: Float,
    /** Zoom, position and straightening of the picture. */
    val reframe: Reframe = Reframe(),
) {
    /** File name without extension; trimmed parts get their start second so names stay distinct. */
    val baseName: String
        get() = name.substringBeforeLast('.') + if (trimStartMs > 0) "_from${trimStartMs / 1000}s" else ""

    /** Builds what the renderer needs for a position in the clip, using [sampler] over [track]. */
    fun dataAt(positionMs: Long, sampler: TelemetrySampler): DashboardData {
        val utc = telemetryStartUtcMs + positionMs
        val zone = track.utcOffsetSeconds ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
        return DashboardData(track, sampler.frameAt(utc), utc, zone)
    }

    /** Close to the source by default; never below 10 Mb/s or above what encoders accept. */
    fun targetBitrate(): Int {
        val base = (sourceBitrate ?: 60_000_000).toLong()
        return (base * bitrateFactor).toLong().coerceIn(10_000_000L, 200_000_000L).toInt()
    }
}
