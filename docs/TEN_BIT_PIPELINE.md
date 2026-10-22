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

## Design

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
