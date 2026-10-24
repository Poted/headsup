# Roadmap

## Done

**Films:** trim/split/reorder, data follows every cut, per-clip colour and effects, sync from the
graph, graduated and oval masks (up to 4 per clip, keyframes and tracking), clip groups, projects
(autosave), colour transitions within a shot, cine look assistant, reframing (zoom, pan,
straighten, thirds grid), continuous playback across clips, highlights from the watch data, shot
match, audio tracks with a sound library, 10-bit export (Rec.709 like DJI Mimo, or HLG), a
project timeline (video and audio tracks, fixed playhead, zoom, trimming) with its own frame
shape and frame rate, 1080p preview proxies for 8K clips, texts with animations and data fields,
route map.

**Albums (photo projects):** photos edited one by one (no timeline, no sound), crop per photo,
photo strip (move, remove, mark for export, remove the marked), export of all / this / the
marked photos, texts per photo, activity data and the photo's GPS in texts and the map.

**Corrections sync:** "All" and "Group" set a changed value on every clip or photo they reach
(as Lightroom's sync), so "This clip/photo" shows what it really has.

**Raw (DNG) development:**
- reading: Bayer mosaics (uncompressed, lossless JPEG) and linear raws (uncompressed, JPEG XL
  through native libjxl); read in parts in a separate process (`:raw`), data in shared memory;
- full-size demosaicing for export (Hamilton–Adams green, colour differences for red/blue),
  2×2 averaging for the preview;
- DNG colour model: matrices blended by the scene's colour temperature, forward matrices,
  profile tone curve, profile hue/sat map and look table (linear ProPhoto), baseline exposure;
  a default tone curve for raws without a profile curve;
- highlight rebuild (clipped channels take the brightest one's value, headroom kept);
- DNG opcodes: gain maps (lens shading), radial vignetting, rectilinear warp (distortion and
  lateral colour fringes, per colour);
- RAW controls: exposure, highlights, shadows, temperature, tint, sharpen, colour noise; the
  info line shows what the file holds and what is applied;
- export: developed into a half-float picture, graded on the GPU at that precision, drawn at
  10 bits and dithered into the JPEG.

## Next (in this order)

1. **Photo tools:** colour mixer (HSL per hue band), looks (profiles: vivid, warm, vintage,
   mono…), then point tone curves (RGB and per channel), colour grading wheels (shadows,
   midtones, highlights), texture / clarity / dehaze.
2. **Masks:** a brush to paint where corrections apply; later subject/sky selection.
3. **Geometry:** perspective correction.
4. **Export:** 16-bit TIFF, Ultra HDR (JPEG with a gain map).
5. **Formats (last):** camera raws that are not DNG (CR3, NEF, ARW, RAF, …) through LibRaw,
   GoPro GPR (VC-5), lossy DNG.

## Ideas

- **Sharing and platform presets** – share an export straight to Instagram/TikTok/YouTube, with
  presets for each platform's resolution, frame rate and bit rate.
- Timeline: reordering clips by dragging, clip sound as a waveform, snapping cuts to beats, more
  than one video track (picture in picture, titles).

## Later: more from DaVinci's windows

- a rectangle shape;
- perspective/planar tracking (the tracker follows position, rotation and scale only).
