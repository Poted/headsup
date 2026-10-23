# Planned: own 10-bit export pipeline

Status: planned, not started. Decided to finish features first (trimming, stable export) and
build this as a separate stage.

## Why

Measured on Galaxy S25 Ultra (Android 16) with the 10-bit test in the app:

```
Input:  HEVC 10-bit SDR BT.709   (Osmo Action 6, D-Log M)
Output: HEVC 8-bit SDR BT.709    (Media3 Transformer, LUT + dashboard)
Encoders: c2.qti.hevc.encoder Main10=yes; c2.qti.hevc.encoder.hdr Main10=yes, HDR editing=yes
```

The hardware can encode Main10. The 8-bit output comes from Media3: it keeps 10 bits only on its
HDR path (HLG/PQ input). D-Log M is tagged SDR, so effects run in 8-bit textures and the encoder is
fed 8-bit frames. The baked colour table (`SingleColorLut`, ARGB_8888) is 8-bit as well.

## Current experiment: Media3 HDR route (10-bit SDR or HLG)

DJI Mimo's "10-bit" export of the same Samsung Log clip turned out to be HEVC Main10 tagged
BT.709 SDR (limited range), transcoded through FFmpeg; HLG renders darker in phone galleries and
editors. The default 10-bit mode therefore writes the same: Media3 still runs its 10-bit (HLG)
pipeline, `HdrOutputEffect` pre-distorts the finished BT.709 values so Media3's HLG OETF writes
them back unchanged, and `HdrRoute.sdrEncoderFactory` configures the HEVC encoder as Main10 BT.709
SDR (reporting the requested format back to Media3). The dashboard is blended by
`DashboardEffect` on display colours in both modes. HLG stays available as "10-bit HDR (HLG)".


`export/HdrRoute.kt`, behind "Colour depth → 10-bit HDR (HLG) · experimental" in the export panel
(and always used by the "Test" button):

- An extractor wrapper labels every video track HLG/BT.2020, so Media3 keeps 16-bit float
  textures and encodes HEVC Main10 HLG (Android 13+, encoder with `FEATURE_HdrEditing`).
- Media3 then converts the samples with the BT.2020 matrix and HLG EOTF; `GradeEffect`
  (`hdrInput`) undoes that exactly (HLG OETF, then BT.709·BT.2020⁻¹), so the colour tables see
  the camera's own values, as in the preview.
- `HdrOutputEffect` maps the finished SDR picture (BT.1886, BT.709) into linear BT.2020 light with
  SDR white at 75 % HLG (BT.2408); the dashboard goes in after it as a "text" overlay with the
  same luminance multiplier (`HdrOverlay`).
- Inputs (`HdrInput`): SDR/log with the BT.709 matrix; log with the BT.2020 matrix (Samsung Log:
  10-bit, BT.2020, transfer untagged), where only the HLG curve is undone; real HLG (Samsung
  HDR video), which is brought to SDR white with a highlight roll-off above it, graded, and
  written back at 75 % HLG, so its brightest highlights are compressed like in the preview.
- Not covered: photos, PQ (HDR10/HDR10+) clips.
- White level: BT.2408 puts SDR white at 75 % HLG, but phone galleries and DJI Mimo render HLG
  so that this looks about a third as bright as the SDR export (measured from screenshots). The
  default is therefore 93 %, which they show like SDR; 85 % and 75 % remain selectable.

BT.2020-tagged SDR/log footage (Samsung Log) is converted to BT.709 in the colour table when no
LUT is applied (`Gamut`, `LutBaker.bake(wideGamut = true)`), as players do; with a LUT the camera
values go to the LUT unchanged. The result looks like the SDR export on SDR
  screens and is shown as HDR on HDR screens, like DJI Mimo's 10-bit files.

If this route fails on devices, the own pipeline below remains the plan.

## Design (own pipeline, if the HDR route fails)

Replace Media3 Transformer for video exports (keep it as a fallback) with:

1. **Demux/decode**: `MediaExtractor` → `MediaCodec` HEVC decoder rendering to a `SurfaceTexture`
   (external OES texture). Request 10-bit output from the decoder where supported.
2. **GPU stage** (OpenGL ES 3.0, own EGL context):
   - EGL config RGBA 10:10:10:2 (`EGL_COLOR_COMPONENT_TYPE`/ `EGL_RGBA1010102`), window surface
     on the encoder's input `Surface`.
   - Shader: sample the OES texture (highp), apply the colour table as a **16-bit float 3D
     texture** (`GL_RGBA16F`, `GL_TEXTURE_3D`, hardware trilinear filtering), then blend the
     dashboard overlay texture (premultiplied alpha, uploaded only when it changes, as now).
   - Keep the same `LutBaker` output, but as float (needs a float variant of `bake`).
3. **Encode**: `MediaCodec` HEVC encoder, `HEVCProfileMain10`, `COLOR_FormatSurface`,
   colour standard BT.709, transfer SDR, bit rate as today. Prefer `c2.qti.hevc.encoder`.
4. **Mux**: `MediaMuxer` MP4; copy the audio track sample by sample (no re-encode); keep
   rotation and frame timestamps from the source.
5. **Progress/cancel** through the existing `ExportManager` job model.

## Risks and checks

- Decoder → `SurfaceTexture` precision: verify 10-bit survives (render a gradient test clip,
  inspect output histogram/banding).
- Device differences in 10-bit EGL configs; fall back to Media3 (8-bit) with the existing warning.
- Frame pacing/timestamps for VFR phone footage; audio/video sync.
- Each iteration needs a phone run; keep the 10-bit test screen as the acceptance check
  (`Output: HEVC 10-bit SDR BT.709`).
