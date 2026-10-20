package app.headsup.core.sync

import java.time.LocalDateTime

/** Where a clip's recording time was read from. [reliability] weights it against other sources. */
enum class TimeSource(val reliability: Double) {
    /**
     * Wall-clock recording start from a DJI name: `DJI_YYYYMMDDhhmmss_…` on the camera's card,
     * `dji_fly_…` / `dji_export_…` when saved by the DJI Fly or Mimo app.
     */
    DJI_FILENAME(1.0),

    /** Phone camera names such as Samsung's `YYYYMMDD_hhmmss.mp4`. */
    PHONE_FILENAME(1.0),

    /** `creation_time` from the MP4 container (`MediaMetadataRetriever.METADATA_KEY_DATE`). */
    MP4_CREATION_TIME(0.8),

    /** `MediaStore.Video.Media.DATE_TAKEN`. */
    MEDIASTORE_DATE_TAKEN(0.6),
}

/** Which moment of the recording the reading refers to. */
enum class TimeAnchor { START, END, UNKNOWN }

sealed interface ClockReading {
    /** Wall-clock time without a zone, e.g. from a file name. */
    data class Local(val dateTime: LocalDateTime) : ClockReading

    /** A value its source labels as UTC. Some cameras write local time here anyway. */
    data class LabelledUtc(val epochMs: Long) : ClockReading
}

data class TimeEvidence(val source: TimeSource, val reading: ClockReading, val anchor: TimeAnchor)
