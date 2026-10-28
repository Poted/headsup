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

## Test notes (2026-10-25 / 10-28) — UI/UX before testers

Already done in the build of 2026-10-25: Project page in two levels (projects ↔ the open
project's files), the preview resized left/right when held sideways, Edit called Crop in albums.

### Now
- [x] Landscape: the timeline falls below the screen; the Adjust section tabs (Light, Colour…)
      squeeze off screen when the tools column is narrowed: make them scroll left/right.
- [x] The handle that resizes the preview is hard to catch (bigger touch area); held sideways
      only the left/right one.
- [ ] Film: changing the crop breaks undo (the crop disappears, undoing resets it) — check again
      after the undo fix.
- [x] Undo can go back past adding a file (to its removal).
- [x] Undo vanished in films (history was cleared whenever the project's name changed, e.g. by a
      highlight reel): it now starts again only when a project is opened or created.
- [x] Adjust: move the colour on/off switch and Copy / Copy to all out of the top (too much room).
- [x] Adjust: the scope tabs (This clip / Group / Masks / All clips) and Copy move to a small
      menu that pops up with a light animation above the Adjust button; the page starts with
      Light, Colour, Mixer… as Edit does. The same for Effects and for albums.
- [x] Remove "Colour off" and the Reset buttons from Adjust; holding a finger on the picture
      shows the original (as in Lightroom).
- [x] Dashboard on/off per clip (now it is on for all clips whose time the activity covers, or
      for none). Without a FIT file: say a file is needed, and no dashboard options on by default.
- [x] The playhead jumps to the clip's start (e.g. after a cut) instead of staying at the join.

### Later
- [ ] Shrinking the preview is not obvious: a short tutorial for users.
- [ ] Safe point: a moment in the edit history to come back to easily.
- [ ] A scrollbar at the side wherever a page scrolls, so users know.
- [ ] Show how far each control is set without opening it (like a guitar amp's knob): the dot on
      each circle placed by the value; to think through (some controls are 0..1, some have
      sub-options).
- [x] Masks: resize and rotate with two fingers (pinch).
- [x] Mask brush (paint, erase, move; size, softness, flow). Still open: drawing a line or a circle
      could offer a line/oval mask instead.
- [ ] Skin tones struggle in odd light.
- [ ] Intro slides: thanks for choosing Sweet Spot, what the app can do, how to make cinematic
      films and professional photos, so a newcomer feels welcome and wants to go further.
- [ ] Speed curves: editable and presets.
- [ ] Mixer: RGB channel mixer (each output channel from R, G, B; film looks like Aerochrome,
      casts), B&W mixer (how bright each colour becomes in black and white), point colour (pick a
      hue from the picture, widen or narrow its range, change its hue/saturation/luminance),
      colour grading wheels (shadows, midtones, highlights), selective colour (CMYK per colour
      family, whites, neutrals, blacks), calibration (hue and saturation of the red, green and
      blue primaries).

## Next (in this order)

1. **Photo tools:** done: colour mixer, looks, point tone curves, colour grading wheels,
   texture / clarity / dehaze (Adjust → Presence; also inside masks).
2. **Masks:** done: brush, two-finger resize/turn, presence (texture, clarity, dehaze) per mask.
   Later: subject/sky selection.
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
