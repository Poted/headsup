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

- [x] Held sideways: the picture takes at most 3/4 of the width, the tools keep room for three
      circles; the transport and timeline fold away (play and time on the picture, the timeline a
      line to scrub) for a bigger picture.

### Later
- [ ] To do, not now: shrinking the preview is not obvious: a short tutorial for users.
- [x] Holding undo for 3 s asks to undo all changes (back to the project as opened; redo brings
      them back).
- [x] Safe point: the flag by the undo arrows keeps the edit as it is; tapping it again goes back
      there (a step of its own) or sets it anew. For the session.
- [x] A thin scroll bar at the panel's edge when it scrolls (takes no room; drag along the edge).
- [-] Not doing: show how far each control is set on its circle (like a guitar amp's knob).
- [x] Masks: resize and rotate with two fingers (pinch).
- [x] Mask brush (paint, erase, move; size, softness, flow), tracking that follows the painted
      area, keys as in other masks. Select: draw round an object (it is found by colour, like an
      oval mask) or a line across (the side above it, split along edges near the line, like a line
      mask). Tap: the object under the finger, found by MediaPipe's Interactive
      Segmenter on the phone.
- [ ] Skin tones struggle in odd light.
- [ ] Masks selected and tracked by an AI model: the tap selection (MediaPipe) works well on clear
      subjects but struggles on harder ones (thin branches, low contrast), and tracking follows
      only position, turn and scale. To do: a stronger segmentation model (subject/sky, refined
      edges), the selection run again on each frame so the mask changes shape with the object.
- [ ] To do, not now: intro slides: thanks for choosing Sweet Spot, what the app can do, how to make cinematic
      films and professional photos, so a newcomer feels welcome and wants to go further.
- [x] Speed curves: an editable ramp per clip (Edit > Speed) and presets in the reel style (Speed ramp:
      fast in, slow, fast out; Fast in, Fast out, Double ramp, Slow in/out), slow parts as slow as the
      footage stays smooth; the presets are also among the moves the auto edit may use.
      Exported as pieces of steady speed (about 0.35 s each).
- [x] Mixer: RGB channel mixer, B&W mixer (brightness per colour in black and white), calibration
      (hue and saturation of the red, green and blue primaries).
- [ ] Later: stabilization from the camera's gyro (GoPro GPMF, DJI and phone metadata), which also
      undoes rolling shutter. A picture-only attempt (motion read from frames) was tried and
      removed: it lost the path on fast walking and FPV footage and could not fix the wobble.
- [ ] Mixer, still to do: point colour (pick a hue from the picture, widen or narrow its range,
      change its hue/saturation/luminance), selective colour (CMYK per colour family, whites,
      neutrals, blacks).

## Next (in this order)

1. **Photo tools:** done: colour mixer, looks, point tone curves, colour grading wheels,
   texture / clarity / dehaze (Adjust → Presence; also inside masks).
2. **Masks:** done: brush, two-finger resize/turn, presence (texture, clarity, dehaze) per mask.
   Later: subject/sky selection.
3. **Geometry:** done: perspective correction (Vertical, Horizontal, with a zoom that hides empty
   corners). Later: guided upright (draw the lines that should be straight).
4. **Export:** done: 16-bit TIFF and Ultra HDR photos: a raw's gain map from the scene's real light
   above white (up to 3 stops, framed as the photo); other photos lift their highlights near white
   (up to 2 stops).
5. **Formats (last):** camera raws that are not DNG (CR3, NEF, ARW, RAF, …) through LibRaw,
   GoPro GPR (VC-5), lossy DNG.

## Ideas

- **A guide agent** – ask it "how do I make this clip cinematic?" and it shows the way step by
  step in the app itself: it highlights what to tap next (the tool, the section, the ruler) and
  says what to set and why, waiting for each step, so the user learns the app while editing. Built
  on the same AI proxy and app-defined functions as the auto edit (it may also apply a step on
  request).
- **Auto edit (as GoPro's), through an AI model behind our own proxy** – the app analyses the
  clips (motion, faces, sharpness, the watch data: speed, jumps, heart rate, existing Moments),
  the user picks a style (serious/cinematic, energetic/sport, trekking, wedding, …) and a pace
  (fast or slow), and the model is asked for an edit. It answers only with calls to functions the
  app defines and runs itself (a plan, not pixels): cut a clip to a span, order the pieces (e.g.
  one-second cuts on a fast pace), add a transition (cut, cross-fade, fade through black, slow
  fade out), set speed (slow motion over a jump found in these frames for Sport, or one of the speed-curve
  presets), apply a look or
  a vignette (Cinematic), place titles, pick music and cut to its beats. Styles are presets: a
  prompt plus the functions and rules they allow, so results stay in the app's own vocabulary and
  every step can be undone or changed by hand. The proxy keeps the API key off the phone; clips
  are sent as small frames and numbers, not whole videos.
  In a store release the whole auto edit is a Premium feature (the model calls cost money per
  edit); no free tier or length limit.
- **Sharing and platform presets** – share an export straight to Instagram/TikTok/YouTube, with
  presets for each platform's resolution, frame rate and bit rate.
- Timeline: reordering clips by dragging, clip sound as a waveform, snapping cuts to beats, more
  than one video track (picture in picture, titles).

## Later: more from DaVinci's windows

- a rectangle shape;
- perspective/planar tracking (the tracker follows position, rotation and scale only).
