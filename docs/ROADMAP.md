# Roadmap

Done: trim/split/reorder, data follows every cut, per-clip colour and effects, sync from the
graph, global colour node, graduated mask, clip groups, projects (autosave), colour transitions within a shot, multiple masks (lines and
ovals, up to 4 per clip), cine look assistant, reframing (zoom, pan, straighten, thirds grid),
continuous playback across clips, highlights from the watch data, shot match, photos, audio
tracks with a sound library, 10-bit export (Rec.709 like DJI Mimo, or HLG), mask keyframes and
tracking.

## Next

1. **Timeline as its own thing, like a DaVinci timeline.**
   - The timeline has its own frame (resolution and shape, e.g. 1080×1920 or 3840×2160) and frame
     rate, set when the project starts (or from the first clip) and shown on the Edit page; the
     export follows it instead of taking the first clip's.
   - Each clip is fitted into that frame: fill (crop) or fit (bars), plus its own framing on top.
     A vertical clip in a horizontal timeline (and the other way round) must not end up cropped
     at random as now, where the first clip decides the shape and the others are cropped to it.
   - Two views: the **project timeline** (all clips in a row, audio tracks, cuts, export) and
     the **clip timeline** (one clip: masks and their keys, colour transition, cut, speed). Masks
     and colour are edited on the clip timeline, music and order on the project timeline; the
     preview and the playhead follow whichever is open.
   - The preview shows the timeline's frame (the project's shape), not the clip's.
2. **Photos** with EXIF time, matched like clips.

## Ideas


- **Sharing and platform presets** – share an export straight to Instagram/TikTok/YouTube, with
  presets for each platform's resolution, frame rate and bit rate.

## Later: more from DaVinci's windows

- a rectangle shape;
- perspective/planar tracking (the tracker follows position, rotation and scale only).
