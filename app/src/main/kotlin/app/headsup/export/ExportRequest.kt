package app.headsup.export

import android.net.Uri
import app.headsup.core.color.ColourMask
import app.headsup.core.color.Presence
import app.headsup.core.frame.Reframe
import app.headsup.core.edit.TextItem
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.TelemetrySampler
import app.headsup.media.VideoFormatInfo
import app.headsup.render.DashboardData
import app.headsup.render.DashboardStyle
import app.headsup.render.EffectSettings
import java.time.Instant
import java.time.ZoneId

enum class ExportKind { VIDEO, PNG_SEQUENCE }

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
    /** Texture, clarity and dehaze, which look at each pixel's surroundings. */
    val presence: Presence = Presence.NONE,
    /** Perspective correction, before the colours. */
    val warp: DoubleArray? = null,
) {
    /** True when a single colour table is not enough. */
    val needsGradeEffect: Boolean get() = masks.isNotEmpty() || (fromCube != null && transitionMs > 0) || !presence.isNeutral || warp != null
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
    /** Zoom, position, straightening and output shape of the picture. */
    val reframe: Reframe = Reframe(),
    /** Playback speed (0.5 = half speed); the dashboard follows the picture. */
    val speed: Float = 1f,
    /** Silence the sound when the speed is changed (otherwise it is stretched, keeping its pitch). */
    val muteChangedSpeed: Boolean = false,
    /** Fades from and to black (picture and sound), in timeline time. */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    /** Level of the clip's own sound (times the level for all clips). */
    val soundLevel: Float = 1f,
    /** A still photo shown for [durationMs]; its dashboard stays at the moment it was taken. */
    val isPhoto: Boolean = false,
    /** Where the clip starts on the timeline, and the timeline's texts shown over it. */
    val timelineStartMs: Long = 0L,
    val texts: List<TextItem> = emptyList(),
    /** Text field values that do not change within the clip (activity summary, photo data). */
    val textValues: Map<String, String> = emptyMap(),
    /** The route map, the activity it shows and (for a photo) its fixed place on it. */
    val routeMap: app.headsup.render.RouteMap = app.headsup.render.RouteMap(),
    val mapTrack: ActivityTrack? = null,
    val mapMarker: Pair<Double, Double>? = null,
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
        val utc = t.startUtcMs + if (isPhoto) 0L else positionMs
        val zone = t.track.utcOffsetSeconds ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
        return DashboardData(t.track, s.frameAt(utc), utc, zone)
    }

}
