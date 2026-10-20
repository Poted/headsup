# Architecture

Offline Android app that burns a Garmin FIT dashboard into videos from DJI Osmo Action 6,
DJI Mini 5 Pro and a Samsung phone, matching data to each clip automatically.

Written from scratch. The prototype at `metroloist/telemetry-overlay` has no license, so
only its technical approach is reused: Media3 Transformer with a per-frame `CanvasOverlay`
driven by the frame's presentation time, and ExoPlayer for preview.

## Modules

```
:core   pure Kotlin/JVM, no Android dependency, unit-tested on the JVM
  fit/    FitActivityReader     Garmin FIT SDK → ActivityTrack
  track/  ActivityTrack         per-metric time series, pauses, HR zones, watch UTC offset
          TelemetrySampler      value of every metric at any UTC millisecond
  sync/   FilenameTimeParser    DJI / Samsung / Android / Pixel file names
          Mp4DateParser         MP4 creation_time strings
          SyncResolver          clip start in UTC + matching activity + confidence

:app    Android, Jetpack Compose (stage 2+)
  media/    metadata reader (MediaMetadataRetriever, MediaStore)
  render/   DashboardRenderer on android.graphics.Canvas, shared by preview and export
  preview/  ExoPlayer + overlay + manual offset fallback
  export/   foreground service, Media3 Transformer (HEVC, bitrate, HDR mode), MediaStore output
```

## FIT parsing

- Official SDK `com.garmin:fit`. Timestamps are converted by the SDK (FIT epoch 1989-12-31 UTC).
- Every recorded metric becomes a separate series, so a metric that is missing in some records
  (e.g. GPS before a fix) does not erase others.
- Derived: ascent/descent with 2 m hysteresis, grade over a 20 m window when the watch does not
  record it, steps per minute for foot sports.
- `activity.local_timestamp − activity.timestamp` gives the watch's UTC offset. This is the
  key input for interpreting camera clocks, which store local time.
- Timer stop/start events become pauses.
- HR zones come from `time_in_zone.hr_zone_high_boundary`.

## Sampling and gaps

Linear interpolation between neighbouring records. Smart recording leaves 10–20 s between
records even during steady activity (16 s in the Forerunner 255 sample), so a gap is only
treated as missing data above 30 s or across a timer pause. Instantaneous readings
(HR, speed, …) then show `--`; totals (distance, ascent) keep their last value.

## Time synchronisation

Each clip yields evidence: file name (local wall clock, recording start), MP4 `creation_time`
(labelled UTC; start or end unknown), MediaStore date. Each piece of evidence is expanded into
candidates:

| Interpretation | Weight |
|---|---|
| wall clock in the watch's zone (from FIT) | 1.0 |
| wall clock in the phone's zone (with DST rules for that date) | 0.5 |
| UTC value taken as UTC | 1.0 |
| UTC value that is really local time in the watch's zone | 0.5 |
| any other offset, −12 h…+14 h in 15 min steps | 0.1 |

× source reliability × start/end reading (known anchor 1.0 vs 0.3, unknown 0.6 each).

Candidates within 2 s of each other are merged, so independent sources confirm one another.
The highest-scoring start that puts the whole clip (±5 s) inside an activity wins.

- **HIGH**: score ≥ 1 and at least twice the next fitting alternative.
- **MEDIUM**: fits, but supported only by weaker interpretations or with close alternatives.
- **LOW**: only an arbitrary zone shift fits, or the clip does not fit any activity.

File names have 1 s resolution and camera clocks drift, so automatic sync is expected to be
within about 1–2 s; the manual ±60 s slider remains as a fallback.

### Observed file names and metadata

| Source | Example | Time in name |
|---|---|---|
| DJI Fly (drone, saved to phone) | `dji_fly_20260524_155144_0147_1783265392318_video_d_logm.mp4` | local start; trailing epoch ms = download time |
| DJI Mimo export | `dji_export_20261020_211852_1792527178764_editor.mp4` | local start of the exported segment (confirmed by Mimo's own overlay); trailing epoch ms = export time |
| Samsung camera | `20260517_131512.mp4` | local start |

- Editing in the Samsung gallery rewrites `creation_time` to the editing moment and adds `_001`
  to the name. The name still carries the original start, so a clip trimmed at its beginning
  is shifted by the trimmed amount and cannot be detected automatically. Overlay the
  original files and trim afterwards.
- Samsung writes `com.samsung.android.utc_offset` (e.g. `+0200`) into the MP4 `udta`; worth
  reading in the app as an explicit zone for phone clips.
- Mimo's dashboard holds the last recorded HR value; this app interpolates (e.g. 57 bpm
  instead of Mimo's 56 between records of 56 and 59).

## Export (stage 3)

- Media3 Transformer, HEVC, source resolution and frame rate, bitrate near the source.
- Overlay bitmap sized to the dashboard, not the full frame (3840×3840 frames would otherwise
  upload ~59 MB per frame).
- 10-bit: a Mimo export is HEVC Main 10, HLG (BT.2020, `arib-std-b67`); Media3 can keep HLG
  10-bit with effects where the device has a 10-bit HEVC encoder and HDR GL support. D-Log M
  is most likely tagged as SDR, and the Media3 SDR effect pipeline is 8-bit. Both to be
  measured on the Galaxy S25 Ultra; the app warns instead of silently downgrading.
- Output via MediaStore to `DCIM/TelemetryOverlay`, foreground service, progress, cancel.

## Platform

minSdk 29 (Android 10). Nothing in the design is Samsung-specific; on other phones the open
question is encoder capability (4K HEVC, 10-bit), which is probed at runtime.
