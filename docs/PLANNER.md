# Light planner

For any place and day: where the sun, moon and Milky Way are, how bright the light will be hour by
hour, and what to set on the camera (aperture, shutter, ISO, ND filter). It is a second app inside
the app, with nothing shared with the editor: after the logo `ModeChooser` asks Editor or Planner;
Back from either returns to the choice, and the planner's "Editor" button goes straight over. The
editor is built when first chosen and kept. `HeadsupScreen.kt` knows nothing of the planner. Its
settings and saved places are kept by `PlannerPrefs` in `PlannerScreen.kt`, not in `ProjectStore`.

## Where it lives

`:core` `planner/` (pure, tested): `Sky` (sun to about 0.02°, moon to about 0.3°, galactic centre and
plane for the J2000 sky, sunrise/sunset and twilights), `SceneLight` (scene EV at ISO 100 from sun
and moon height, place height, Bortle class, cloud), `Exposure` (the triangle with ND, one setting
auto, warnings with a suggested filter, the filter window for a held shutter), `Planner.frame` (all
of it for one moment, optionally from a measured EV), `Timelapse` (the filter cannot change during
a timelapse: from the light's span over a window, the filter range that one filter must lie in,
and the shutter and ISO at each moment; shutter first, at the lowest ISO), `WebMercator` (map
pixels from latitude and longitude, for the map), `Ar` (the phone's attitude and a direction in the
sky to a point on the screen, and the lens's focal length in pixels).

`:app` `ui/`: `PlannerScreen` (the host: tabs Sun / Live / Calculator, button to the editor),
`PlannerState` (everything the two views share, kept on the phone as it changes, and the saved
places), `PlannerSunView` (sky chart, the map in its corner, hour, day, place), `PlannerLiveView`
(camera behind the sky overlay, compass and tilt, the light it reads), `PlannerCalculator`
(exposure, filter, timelapse), `TileMap` (the map: tiles fetched when seen and kept in the cache
folder, drag, pinch, tap to put the pin; the pin, a cone for the way the phone looks, rays for the
sun), `PlannerParts` (small controls), `ModeChooser` (Editor or Planner after the logo).

Three views, not one page: the sun view is for looking (the sky with the map in its corner, ⤢ swaps
them), the live view is for standing there (the camera, the sun's real path with a dot per hour, the
light it reads), the calculator is for working out settings. They share place, day and hour.

## Live view

- Camera through CameraX (`FILL_CENTER`, a texture view so the overlay draws above it). The lens's
  field of view comes from the camera's sensor size and focal length; the focal length in pixels
  is worked out by `Ar.focalPx`. A phone with several lenses uses the default back one.
- The phone's attitude from the rotation-vector sensor (smoothed), as a matrix; the sky is
  projected with `Ar.project`. Sun and sky are true-north, the sensor magnetic: the place's
  declination (`GeomagneticField`) is applied. The view keeps the phone upright while open.
- The light: what the camera's auto exposure chose (f-number, shutter, ISO from the capture
  result) gives EV = log2(N²/t) − log2(ISO/100), smoothed. The camera exposes for a mid-tone, so
  this is the reflected scene's EV where it is aimed; aim at the scene, not at the sun. It is a
  reading to ±1 stop at best (phones add their own tone mapping). "Use as base" makes it the
  planner's level (`PlannerState.useReading`); the model then gives only the change over time
  (`Measured`). A reading is used only while the place stays within about 20 km of where it was
  taken, and is kept for the visit only.
- The way the phone looks (true heading) sets the cone on the sun view's map.
- Not built: landscape, a compass chart (polar sun diagram), a month bar.

References looked at for the layout: Sun Seeker (AR path, solar compass), SunCast and SunQuest
(hour dots on the path, day bar coloured by the sun's height, map with rise and set lines). Their
3D buildings and shadows are left out: the planner is for photography.

The phone's back button is not used by the planner or by the choice screen (it is left to each
half; the UI work will decide how to move between them). Today: planner → editor by its button;
editor → planner by restarting the app.

## Decisions to know

- EV is for a mid-tone subject; clear noon is about 15. Estimated, good to about 1.5 stops. A
  light reading taken with the camera (`Measured`) replaces the level and keeps only the model's
  change over time. The camera reading is not built yet.
- Night EV comes from a Bortle table (1 → −6.5 … 9 → +1), practical values for a correctly
  exposed night shot. The Bortle class is set by hand until a light-pollution raster is bundled.
- Cloud dims the day by up to 2 stops (Kasten–Czeplak), the moon by less.
- With ND "picked", the planner chooses the weakest listed filter that keeps ISO above the
  camera's lowest, only while ISO is the auto setting.
- Time zone is a whole-hour UTC offset (starts at the phone's), set by hand for another place.

## Timelapse in short

Shutter × ISO the light asks for is 100·N²·2^ND / 2^EV. It must lie in [ISOmin·shutterMin,
ISOmax·shutterMax] at every moment of the window, so ND ≥ EVmax + log2(ISOmin·shutterMin/(100·N²))
and ND ≤ EVmin + log2(ISOmax·shutterMax/(100·N²)). If the lower bound is above the upper one, no
filter serves the window and the plan says by how many stops. The shortest shutter sets how much
the day is blurred, the longest is limited by the interval.

## Network

The planner is the only part with network use (permission INTERNET): the map's tiles from
OpenStreetMap's own tile server, with its credit on the map and an identifying user agent, kept in
the cache folder so a place seen once can be seen again with no signal. That server's policy is
for light use; for a store release use a tile provider with a key. The editor uses no network.

## Not built yet (in this order)

1. [done, to be tried on a phone] Live view: camera behind the sky, compass and tilt, the light
   it reads as a base (`Measured`).
2. Light pollution raster (VIIRS / Falchi atlas, small, bundled) instead of the Bortle stepper.
3. Terrain: horizon profile from elevation data, the sun hidden behind peaks, a 3D horizon.
   Needs data fetched or bundled.
4. [done] Map with the pin, the cone for the way the phone looks, and the sun's rise, set and
   current directions. The cone follows the dragged sky now; it will follow the compass in live view.
5. Cloud forecast (Open-Meteo): optional, shown beside the clear-sky result, with a manual cloud
   slider, since a forecast of cloud is sometimes wrong.
6. Saved plans (a place with its timelapse settings); saved places exist already.
