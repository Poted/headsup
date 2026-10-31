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
      mask). Tap: the object under the finger (see AI selection below).
- [ ] Skin tones struggle in odd light.
- [x] AI selection in brush masks (Select): a tap selects the whole object (MobileSAM on the phone,
      ONNX Runtime); tapping the same place again steps through a part / the object / more; a loop
      drawn round an object is its box. Sky: the sky model (DeepLab v3, ADE20K). Everything found is
      laid onto the picture's edges (colour guided filter). Add / Subtract.
- [x] Brush masks on video tracked with AI: Track finds the mask again on each frame (12 a second):
      objects by MobileSAM told the last frame's box, a point inside and its own answer; the sky by
      the sky model. The mask keeps the object's shape and ends when it leaves the picture. Maps
      are kept per clip (files/sequence) and fade from one to the next in preview and export; a fix
      painted on some frame shows on top until tracked again from there.
- [x] Clean up (albums): tap something to remove it (MobileSAM finds it) or paint over it; filled
      by LaMa (Apache 2.0, full precision; MI-GAN was tried and left smeared texture) area by area,
      each seen with as much around it again at 512 px, grown past the object's edge, given back
      the photo's grain and faded in. Kept as patches over the photo (never changing the file), in
      preview and export (TIFF, Ultra HDR: the headroom where something was removed is that around
      it); a raw developed differently is cleaned again by itself. Undo last / Restore all.
      The model is fetched by the build at a fixed revision and checked by its hash.
- [x] Masks UI: the masks as a row of their own with one + (Object, Sky, Brush, Line, Oval); the
      selected mask's actions in one row (Invert, Overlay, Clear, Reset, Delete); its tabs as the
      photo's (Mask first: tools, shape, tracking and keys; then Light, Colour, …). Brush tools as
      a palette (Select, Paint, Erase, Move, View: one finger moves the zoomed picture); Select
      shows only Add/Subtract, Sky and Edge (tighter … fuller). The mask is drawn as an outline
      (Overlay fills it in red). A double tap fits the view and paints nothing. Long jobs (AI
      tracking, Clean up) can be stopped; tracking keeps what it found.
- [x] Sky model: DeepLab v3+ Xception65 (ADE20K) instead of MobileNetV2 (it took a sunset sea for
      sky); built by CI from the TensorFlow graph (tools/models/sky_model.py).
- [x] Effects in masks (as DaVinci's effects on a windowed node): Glow and Halation in a mask's
      Effects tab. Only what is bright inside the mask gives light, and the light spreads past its
      edge as light does (no cut-off at the mask's edge). Preview, photo and video export.
      Later, if wanted: Blur / Sharpen inside a mask.
- [x] Fixes on AI-tracked masks last: on the frame tracking starts from, what the user's mask has
      beyond the model's answer (painted in or out) is kept and carried with the picture's movement
      (CameraMotion, tested) onto each next frame's answer. Frames tracked from are keys: tracking
      from a frame runs to the next key either way, never over it. Copy / Opposite of a mask (with
      its tracking). View mode moves the zoomed picture again.
- [x] Track on an AI-tracked mask always runs both ways from the playhead (up to the frames fixed
      earlier). Export: a tracked mask's fixes are drawn once (not on every frame), its pictures are
      not read from the disk again on each frame, and the muxer waits up to a minute for a slow
      frame (the export failed with MUXING_TIMEOUT after 10 s).
- [x] Mask flow: a mask's shape is changed in a mode of its own (Shape: its tools, tracking and
      keys, Overlay, Clear, Done); its corrections are the default view, where a finger on the
      picture changes nothing. A new mask opens in Shape; Done goes to its corrections. The
      transport jumps to a clip's first or last frame (⇤ ⇥). The export progress bar has no end dot.
- [x] Tracked masks' maps are kept packed (a few kB each, opened when read; older files are
      read too): a long clip's ~1300 maps took ~300 MB open and stalled export (MUXING_TIMEOUT at
      the same point every time). An error in the grade effect now ends the export with its cause.
- [x] Fit with blurred bars (Edit > Frame: Bars Black / Blur, also for Fit all clips): the bars are
      the picture itself covering the frame, blurred and a little darker, in preview (a 32 px frame
      scaled up) and export (BlurFillEffect: a 64-tap spiral blur behind the fitted picture).
- [x] Export stuck at 25 % (MUXING_TIMEOUT), found from Media3's trace: a screen recording's picture
      begins 2.8 s after its sound; Media3 changes a picture's speed from its first frame, the
      sound's from its start, so at 2× the picture ran 0.8 s past the clip's end and the next
      clip's sound could not start (deadlock). Sped-up clips now start at their first frame of
      picture. Bars (Black / Blur) are set for the whole film.
- [x] Blurred bars as a cloud (as YouTube's ambient light): export reads the picture from its
      mipmaps (~24 px across) with a soft 5×5 kernel, colours richer, a touch darker; the preview
      reads frames from the file (the TextureView gives nothing for HDR screen recordings) and
      blurs them with the system's blur (Android 12+).
- [ ] Layers: an image (PNG, logo, photo) over a photo or video; a second video as picture in
      picture or side by side (Media3 composes several video sequences). To plan.
- [ ] Masks, next: (1) edges at full resolution in export (the mask is kept at 640 px: refine it
      against the full picture in the shader); (2) faster tracking (the encoder takes about a
      second per frame on a phone: GPU/NNAPI, or a smaller encoder); (3) more prompts: + / − points
      on an object; (4) Select Subject (the main subject without a tap), people (skin, hair, clothes).
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
2. **Masks:** done: brush, two-finger resize/turn, presence (texture, clarity, dehaze) per mask,
   AI object and sky selection, AI tracking on video (per frame). Next: full-resolution edges, speed.
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
- **Effects that follow the body (on the phone, no cloud)** – e.g. a flame on an opened hand:
  hand / face / body landmarks found on each frame (MediaPipe Hand/Face/Pose Landmarker class of
  models, small and real-time), a gesture read from them (the hand opens), and an effect drawn
  there: a procedural shader (fire, sparks, glow, smoke) or an animated sprite with alpha, moved,
  turned and scaled with the hand, lighting its surroundings a little; the AI masks put fingers in
  front of it where they should be. Our own pipeline and assets, no generation.
- **Removing things from videos** – per-frame filling flickers. First: shots where the object
  moves and the background shows on other frames (static or tracked camera): the background
  taken from those frames, aligned (no model needed). The general case (moving camera, nothing
  behind): a video inpainting model on a server (open ones are often non-commercial: check) or a
  cloud model, as Premium.
- **Generated effects (realistic fire that lights the scene, changing what is in the shot)** –
  only video generation models can (Veo and the like), in the cloud and paid per use: Premium,
  later. Claude does not make pictures; Gemini/Veo or other image and video models do.
- **Sharing and platform presets** – share an export straight to Instagram/TikTok/YouTube, with
  presets for each platform's resolution, frame rate and bit rate.
- Timeline: reordering clips by dragging, clip sound as a waveform, snapping cuts to beats, more
  than one video track (picture in picture, titles).

## Later: more from DaVinci's windows

- a rectangle shape;
- perspective/planar tracking (the tracker follows position, rotation and scale only).
