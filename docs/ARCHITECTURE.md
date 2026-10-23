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
within about 1–2 s; as fallbacks there is a ±10 min offset ruler and a graph picker (tap the moment on the
speed / heart-rate / altitude graph that matches the paused frame).

### Observed file names and metadata

| Source | Example | Time in name |
|---|---|---|
| DJI Fly (drone, saved to phone) | `dji_fly_20260524_155144_0147_1783265392318_video_d_logm.mp4` | local start; trailing epoch ms = download time |
| DJI Mimo download (Osmo Action 6) | `dji_mimo_20261009_211616_0046_1792442826780_video.mp4` | local start; trailing epoch ms = download time |
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

## Colour

All colour work is baked into one 33³ table (`LutBaker`) that preview and export apply in a
single lookup. With a camera LUT the corrections run first, on the log-encoded values, and the
LUT is the last node (as in DaVinci Resolve), so exposure can still pull back highlights the LUT
would clip; exposure and white balance are offsets in log space (≈0.075 per stop). Without a
LUT, footage is treated as display-ready and corrected in display space. On Android 13+ the
preview plays into a TextureView and applies the same table with an AGSL shader
(`GradePreviewShader`), so slider changes are immediate; older versions use a Media3 `ColorLut`
whose table is swapped in place.

Corrections, LUT and effects belong to each clip (`ClipState.grade`, `effects`, `lutName`). The
last edited values become the default for clips added later, and a clip's look can be copied,
pasted onto another clip or applied to all. The dashboard layout is shared by all clips.
Groups (Colour → Group; long-press a clip to add it) sit between a clip and the global node:
a clip is in at most one group, whose corrections are added and whose LUT is used when the clip
has none of its own. One group per clip keeps the result predictable: clip → group → all clips.
A global node (Colour → All clips) holds corrections added on top of every clip's own, e.g.
one vignette for the whole project; the values are offsets, so the two simply add up
(`ColorGrade.plus`).

Masks (Colour → Masks) are windows with their own corrections, like power windows in Resolve:
lines (graduated filters) and ovals, up to four per clip, each invertible and movable by
dragging on the preview. Each mask gets its own baked table (clip + group + global + mask
corrections) and the tables are blended in order by `ColourMask.weight`. A clip can also blend
in from the previous clip's look (Colour → This clip → Blend in), e.g. after a split where the
light changes; the weight is `ColourTransition.fromWeight`. The preview shader and, for export,
`GradeEffect` (replacing Media3's single colour table for such clips) use the same formulas.

### Cine look

`FilmLook` runs on display colours after the LUT (inside the same baked table): a highlight
shoulder (`rolloff`), split toning of shadows and highlights by vectorscope angle (`toneAmount`,
`shadowHue`, `highlightHue`) and calming of loud greens/blues (`tame`); skin, found by its
closeness to the 123° line, is spared. `CineAssistant` needs no AI: it measures the paused frame
(luminance percentiles, clipping, colourfulness, share of skin and foliage, colour cast) and
derives a suggestion per style (warm film, teal & orange, muted) that is added to the clip's
grade at a chosen strength, plus grain and halation (a red-orange bleed around highlights in
the glow pass of the preview shader and `FinishEffect`).

### Reframing

`Reframe` (output shape, zoom, centre, straightening angle) is per clip (Frame tool); with a
shape such as 9:16 the output is the largest frame of that shape inside the picture, and export
crops to it with a Media3 `Presentation` after the matrix; zoom never drops below what keeps the
corners filled after rotation. Colour and texture are applied to the whole picture first and
the framing afterwards, then the dashboard: in the preview as a transform of the graded layer,
in export as a Media3 `MatrixTransformation` (aspect-corrected in NDC) before the overlay.

## Export (stage 3)

- Media3 Transformer, HEVC, source resolution and frame rate, bitrate near the source.
- Overlay bitmap sized to the dashboard, not the full frame (3840×3840 frames would otherwise
  upload ~59 MB per frame).
- 10-bit: a Mimo export is HEVC Main 10, HLG (BT.2020, `arib-std-b67`); Media3 can keep HLG
  10-bit with effects where the device has a 10-bit HEVC encoder and HDR GL support. D-Log M
  is most likely tagged as SDR, and the Media3 SDR effect pipeline is 8-bit. Both to be
  measured on the Galaxy S25 Ultra; the app warns instead of silently downgrading.
- Output via MediaStore to `DCIM/TelemetryOverlay`, foreground service, progress, cancel.

Media3 exports SDR input (including D-Log M) in 8 bits; see
[TEN_BIT_PIPELINE.md](TEN_BIT_PIPELINE.md) for the planned own 10-bit pipeline.

## Platform

minSdk 29 (Android 10). Nothing in the design is Samsung-specific; on other phones the open
question is encoder capability (4K HEVC, 10-bit), which is probed at runtime.

## Projects

The timeline is saved automatically (`ProjectStore`, JSON in the app's private storage) shortly
after each change and the last project reopens on start. A project stores file URIs, cuts, sync
corrections, colour (clip, group, mask, global) and effects; the dashboard layout stays an app
setting. Picked files get a persistable read permission so a project can open them again later.

## Highlights

`HighlightFinder` looks at the watch data once a second, only where there is video: smoothed
top speed, sprints (speed gained in 5 s), fastest descent and steepest climb (vertical speed over
10 s), highest point, heart-rate and power peaks. Each kind is scored against the whole
activity (median → best = 0 → 1), weighted (speed and descents first), and moments closer than
20 s merge. A highlight reel is a new project of short clips around the chosen moments, cut
from the clips that show them with their colour and effects.

## Shot match

A reference frame's on-screen statistics (luminance at 10/50/90 %, mid-tone colour cast in
Cb/Cr, mean colourfulness) are stored. To match another clip, `ShotMatch` runs samples of its
paused frame through the clip's whole colour path (corrections, group, global, LUT) and adjusts
exposure, contrast, temperature, tint and saturation with damped Gauss–Newton steps until the
statistics agree. If both frames show skin (over 2 % of pixels near the 123° line), the skin hue
is then turned to the reference's mean skin angle. The change is added to the clip's own
corrections and can be undone. For a clip in a group, the default is to match the whole group
(the camera): exposure and contrast are solved only so the frames compare like with like, and
just white balance, tint, saturation and skin hue go into the group's corrections, so every
shot of that camera follows while keeping its own brightness.

## Undo and redo

The ViewModel keeps up to 60 steps of the edit (files, cuts, colour, groups, masks, effects,
framing, dashboard; not selection or playback). A step is recorded half a second after an edit
settles, so one slider drag is one step; a step pending when undo is pressed is recorded first,
so redo can bring it back. Opening another project starts a new history. The arrows sit quietly
in the corner of the preview and only appear once there is something to undo.

## Export settings, timeline and speed

The project has a timeline frame rate (Auto = the first clip's). Video export defaults to the
whole timeline as one file: one Media3 `EditedMediaItemSequence` of all clips (silence is added
where a clip has no sound), each scaled and cropped to the same frame (`Presentation`, size from
the chosen resolution and the first clip's shape). Per clip the effects run: colour, texture,
framing, output frame, dashboard, then speed (`SpeedChangeEffect`, sound via
`SpeedChangingAudioProcessor`, pitch kept, or removed) and frame rate (`FrameDropEffect` when the
clip has more frames than the timeline; frames are never invented). The dashboard comes before
the speed change, so it reads the clip's own time and slows down with the picture. Bit rate
follows the source's per pixel times the chosen quality; HEVC or H.264. The PNG dashboard export
is separate and per clip.
