# Light planner

For any place and day: where the sun, moon and Milky Way are, how bright the light will be hour by
hour, and what to set on the camera (aperture, shutter, ISO, ND filter). It is a second app inside
the app, with nothing shared with the editor: after the logo `ModeChooser` asks Editor or Planner;
Back from either returns to the choice, and the planner's "Editor" button goes straight over. The
editor is built when first chosen and kept. `HeadsupScreen.kt` knows nothing of the planner. Its
settings and saved places are kept by `PlannerPrefs` in `PlannerScreen.kt`, not in `ProjectStore`.

## Where it lives

`:core` `planner/` (pure, tested):
- `Sky`: the sun (to about 0.02°), the moon (to about 0.3°, its height as seen from the ground, with
  parallax), the galactic centre and plane (J2000 sky); sunrise/sunset and twilights,
  moonrise/moonset, golden hours (sun −4°…6°), blue hours (−8°…−4°), and the Milky Way's window: its
  centre 10° up in a fully dark sky, and the part of that with the moon down or under a quarter lit.
- `SceneLight`: scene EV at ISO 100 from sun and moon height, place height, Bortle class, cloud.
- `Exposure`: the triangle with ND, one setting auto, warnings with a suggested filter, the filter
  window for a held shutter, lux from EV, camera-style printing of the values.
- `Planner.frame`: all of it for one moment, optionally from a measured EV and under a forecast.
- `Weather`: `CloudForecast` (hourly cloud, moved smoothly between hours); `OpenMeteo` (asks for
  and reads the cloud cover).
- `CameraScale`: the camera's dials a third of a stop apart (shutter 1/8000 s … 30 s and a few
  long times, f/1 … f/32, ISO 50 …); settings move along them, worked-out values are shown as the
  nearest of them (so nothing reads 1/1s).
- `WebMercator`: map pixels from latitude and longitude.
- `Ar`: the phone's attitude and a direction in the sky to a point on the screen; the lens's
  focal length in pixels.

`:app` `ui/`:
- `PlannerScreen`: the host, tabs Sun / Live / Calculator, a button to the editor.
- `PlannerState`: everything the views share, kept on the phone as it changes; saved places; the
  camera's reading; the forecast.
- `PlannerSunView`: the sky over the whole view (drag to turn and tilt, pinch to zoom), the map in
  its corner, the day bar, panels for the place and for the day's times.
- `PlannerLiveView`: the camera behind the sky overlay, compass and tilt, the light it reads (EV, lux).
- `PlannerCalculator`: exposure and filter for the hour.
- `TileMap`: the map (tiles fetched when seen and kept in the cache folder; drag, pinch, tap to
  put the pin; the pin, a cone for the way the phone looks, rays for the sun, hour dots).
- `PlannerPlans`: saved plans (place, day and hour, camera settings, sky), opened from the header.
- `PlannerParts`: small controls and the day bar. `ModeChooser`: Editor or Planner after the logo.

Three views, not one page: the sun view is for looking (the sky with the map in its corner, ⤢ swaps
them), the live view is for standing there (the camera, the sun's real path with a dot per hour, the
light it reads), the calculator is for working out settings for one hour (it is not a timelapse planner). They share place, day and hour.

## Live view

- Camera through CameraX (`FILL_CENTER`, a texture view so the overlay draws above it). The lens's
  field of view comes from the camera's sensor size and focal length; the focal length in pixels
  is worked out by `Ar.focalPx`, held upright or sideways. A phone with several lenses uses the
  default back one.
- The phone's attitude from the rotation-vector sensor (smoothed), as a matrix; the sky is
  projected with `Ar.project`. Sun and sky are true-north, the sensor magnetic: the place's
  declination (`GeomagneticField`) is applied. The sensor's axes are turned into the screen's
  for the way the screen is turned (`remapCoordinateSystem`), so it works held either way.
- The light: what the camera's auto exposure chose (f-number, shutter, ISO from the capture
  result) gives EV = log2(N²/t) − log2(ISO/100), smoothed. The camera exposes for a mid-tone, so
  this is the reflected scene's EV where it is aimed; aim at the scene, not at the sun. It is a
  reading to ±1 stop at best (phones add their own tone mapping). "Use as base" makes it the
  planner's level (`PlannerState.useReading`); the model then gives only the change over time
  (`Measured`). A reading is used only while the place stays within about 20 km of where it was
  taken, and is kept on the phone for 12 hours.
- The way the phone looks (true heading) sets the cone on the sun view's map.
- Not built: a choice of lens, a compass correction, a compass chart (polar sun diagram), a month bar.

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

## Cloud: the sky as set, and the forecast beside it

Every result is given for the sky as set (the cloud slider, clear by default) and, once a forecast
is loaded (Open-Meteo, no key, hourly total cloud cover, fetched on request for the place and day
and kept on the phone), under the forecast's cloud in brackets: "ISO 400 (1600)", "EV 11.2 (with
forecast 9.4)". A forecast of cloud is sometimes wrong, so it never replaces the result. A reading
taken with the camera was made under the sky as set, so the forecast moves it by the difference.
A forecast is used only while it is for the place (to 0.1°) and day shown. Open-Meteo's free
service is for non-commercial use and reaches about two weeks ahead.

## Network

The planner is the only part with network use (permission INTERNET): the weather forecast, and the map's tiles from
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
5. [done] Cloud forecast (Open-Meteo), shown in brackets beside the result.
6. [done] Saved plans (place, day and hour, camera, sky).
