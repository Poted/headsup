# Roadmap

Done: trim/split/reorder, data follows every cut, per-clip colour and effects, sync from the
graph, global colour node, graduated mask, clip groups.

## Next

1. **Projects** – save and reopen the timeline: clips (by URI), cuts, sync corrections, colour
   (clip, group, mask, global), effects, dashboard layout. Video and FIT files are referenced,
   not copied.
2. **Colour transition within a shot** – split a clip where the light changes (e.g. from sun
   into shade) and let the grade blend from the first part's into the second's over a chosen
   length, instead of jumping at the cut. Export: the two-table effect (`MaskGradeEffect`'s
   approach) with a time-based weight instead of a spatial one.
3. **Reframing** – crop/zoom the frame with a rule-of-thirds grid.
4. **Photos** with EXIF time, matched like clips.
5. **10-bit pipeline** – see `TEN_BIT_PIPELINE.md`.

## Later: more masks, as in DaVinci Resolve

Several windows per clip, each with its own corrections:

- shapes: linear (today's graduated mask), circle/ellipse (faces, subject), maybe a rectangle;
- per window: position, size, angle, softness, invert;
- each window gets one more baked table; the shaders blend them in order
  (`c = mix(c, tableᵢ(src), weightᵢ)`), so cost grows with the number of windows – a limit of
  about 4 per clip keeps phones fast;
- optional later: tracking a window to follow a subject (much harder; needs motion analysis).
