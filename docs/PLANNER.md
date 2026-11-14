# Light planner

For any place and day: where the sun, moon and Milky Way are, how bright the light will be hour by
hour, and what to set on the camera (aperture, shutter, ISO, ND filter). It is a second app inside
the app, with nothing shared with the editor: after the logo `ModeChooser` asks Editor or Planner;
Back from either returns to the choice, and the planner's "Editor" button goes straight over. The
editor is built when first chosen and kept. `HeadsupScreen.kt` knows nothing of the planner. Its
settings and saved places are kept by `PlannerPrefs` in `PlannerScreen.kt`, not in `ProjectStore`.

## Where it lives

`:core` `planner/` (pure, tested):
- `Sky`: the sun (to about 0.02°), the moon (to about 0.1–0.2°: the main terms of its motion, its
  height as seen from the ground, with parallax), the galactic centre and plane (J2000 sky); sunrise/sunset and twilights,
  moonrise/moonset, golden hours (sun −4°…6°), blue hours (−8°…−4°), and the Milky Way's window: its
  centre 5° up in a fully dark sky, and the part of that with the moon down or under a quarter lit;
  the best Milky Way nights of a year (longest moonless window); moon phases.
- `SceneLight`: scene EV at ISO 100 from sun and moon height, place height, Bortle class, cloud.
- `Exposure`: the triangle with ND, one setting auto, warnings with a suggested filter, the filter
  window for a held shutter, lux from EV, camera-style printing of the values.
- `Planner.frame`: all of it for one moment, optionally from a measured EV and under a forecast.
- `Weather`: `CloudForecast` (hourly cloud, moved smoothly between hours); `MetNorway` (asks for
  and reads the cloud cover).
- `Terrain`: the ground's height from Terrarium terrain tiles; the horizon's dip for a camera
  above the ground (used by sunrise, sunset, moonrise and moonset).
- `CameraScale`: the camera's dials a third of a stop apart (shutter 1/8000 s … 30 s and a few
  long times, f/1 … f/32, ISO 50 …); settings move along them, worked-out values are shown as the
  nearest of them (so nothing reads 1/1s).
- `Geo`: distance and direction between places, destination, how high a point looks (Earth's
  curve and the air's bending, k = 0.13), refraction of a body near the horizon.
- `Horizon`: the skyline from a terrain model: along each degree of azimuth, points out to 60 km
  (close together near, wider far; terrain zoom 12 / 11 / 10 by distance), the highest angle.
  `Sky.sunOverSkyline` / `moonOverSkyline`: when the top edge clears it (refraction included).
- `Alignment`: when the sun, the moon or the Milky Way's centre stands in a direction (and, if
  asked, within 1° of a height): day by day the moment it crosses that azimuth; the Milky Way only
  in a fully dark sky.
- `PlaceInput` / `PlusCode`: a place pasted as text: decimal or degrees-minutes-seconds
  coordinates, a Google Maps link with coordinates, a Plus Code (full, or short completed from the
  town its words name, else from the place set); all on the phone.
- `Gazetteer`: search by name without accents or case (towns, villages, peaks, passes, lakes,
  huts), best match first, bigger towns and higher peaks before, nearer before farther.
- `Capture`: Photo / Video / Timelapse: a video's shutter from its frame rate (180°: 1/(2·fps)), a
  timelapse's from its interval (half of it), with notes for what is out of reach.
- `WebMercator`: map pixels from latitude and longitude.
- `Ar`: the phone's attitude and a direction in the sky to a point on the screen; the lens's
  focal length in pixels; the compass and tilt correction from aiming at the sun or moon.

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
- `PlannerTerrain`: `TerrainStore`, terrain tiles fetched and kept on the phone; a point's height;
  the skyline of a place.
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
- Calibrate: aim the cross at the real sun or moon and tap Align; the difference to its true place
  becomes the compass and tilt correction (kept on the phone), or nudge by 1° / 0.5°. Phones'
  compasses are often a few degrees off; the green skyline shows whether the overlay fits.
- The Milky Way threshold is 5° up: at 50°N its centre never climbs much above 11°.
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
is loaded, under the forecast's cloud in brackets: "ISO 400 (1600)", "EV 11.2 (with forecast 9.4)".
A forecast of cloud is sometimes wrong, so it never replaces the result. A reading taken with the
camera was made under the sky as set, so the forecast moves it by the difference. The forecast is
MET Norway's Locationforecast (worldwide, about nine days ahead), fetched on request, kept on the
phone, and used only while it is for the place (to 0.1°) and reaches the day shown.

## Height

The ground's height comes from the terrain model when the place changes (typed, picked on the
map, found by the phone, a plan opened), a moment after it stops changing; it can be typed over.
The phone's GPS height is not used for it (it is measured from the ellipsoid, tens of metres off).
"Camera above the ground" (a drone, a roof) adds to it for the light, and dips the horizon
(1.76′ × √metres) for sunrise, sunset, moonrise and moonset: 100 m up, the sun rises about two
minutes sooner.

## Skyline (Find is out of the interface)

When the place changes, the land around it is read (about 70 terrain tiles the first time, a few
MB, kept on the phone) and the skyline worked out from the camera's height. It is drawn on the sky
chart (the land filled, a green line) and in Live (a green line, to check the overlay against the
real hills). The Times panel adds when the sun and moon clear the land and go behind it.

Find (not in the interface since 2026-11-14: it confused more than it helped; `Alignment` and its
tests stay in `:core`): pick a target on the map (a peak, a tower; its ground from the terrain plus how tall it
stands) or take the way the phone looks; choose Sun, Moon or Milky Way centre; search 30, 90 or
365 days from the day shown, at the target's height (within 1°; for the phone's direction, the
skyline's height there) or at any height. A found moment opens that day and hour everywhere.

Limits: terrain at about 25 m near and 100 m far, so a narrow summit or a building may be missed or
lower (a building or a tree is not in the model: give the target's height by hand); the moon's
position is good to about 0.3°.

## Checked against real terrain (2026-11-14)

From Turbacz's summit (1302 m) the model gives the Tatras (Kasprowy 1962 m at 36 km, Gerlach
2591 m at 42 km) 1.0–1.4° above level, and every other direction below level (−0.3° to −1.3°):
right, but on a chart 120° wide a degree is a few pixels, so the Tatras look flat. To do in the
interface: a vertical scale that shows a degree or two clearly, and a cross-section of the ground
from the camera to a point on the map (the line of sight over it).

## Handover to the UI work (2026-11-14)

What is there and works (logic tested in `:core`, the screens built in CI and tried on a phone):
- Editor / Planner choice after the logo (`ModeChooser`, with the last crash report and Copy);
  the phone's back button is left alone; the planner has a button to the editor; the editor has
  none to the planner yet.
- Planner header: Sun · Live · Calculator · Plans · Editor (too many for a narrow phone).
- Sun: the sky full screen (drag to turn and tilt, pinch to zoom), the map in the corner (⤢
  swaps), the exposure bar (EV | aperture | shutter | filter | ISO, the following one marked, red
  out of reach, forecast in brackets; tap → Calculator), the day bar, ◀ ▶ Today, Times and Place
  panels over the sky. Place: search by name, or paste coordinates / Plus Code / Maps link.
- Live: camera with the sun's path, hour dots, horizon, skyline, moon, Milky Way; the exposure bar;
  the reading of the light (EV, lux) and Use as base; My location; Calibrate (align on the sun or moon, nudges).
- Calculator: Photo / Video / Timelapse; frame rate or interval; Aperture / Shutter / ISO tiles
  (one auto in Photo; in Video and Timelapse the shutter is held by the mode and ISO follows);
  filter (None, ND2 … ND2000 with exact stops; Pick for me, ◀ ▶); ISO range; the filter window for the held shutter; warnings; Night
  sky (Bortle), Cloud, Get forecast (results in brackets: "(with forecast …)").
- Plans: save everything set under a name, open, delete.

Status: an MVP of the first stories (US1 live view and metering, US2 remote planning with map and
sky, US3 exposure with locks, ND and warnings over the time bar, US4 the bar on one screen). The
logic is tested; the screens work but were built without design.

Known UX problems to solve there: units and steps of the settings (now the camera's dials), the
header's width, the shutter and aperture tiles' Auto in Video and Timelapse (ISO is always auto
there), the sky chart's vertical scale (the Tatras look flat), the Place panel's length, no way
from the editor to the planner, the chart's text sizes; short Plus Codes ("X3QR+5F Kluszkowce")
were still landing a cell away on the phone (fixed in code: the town is looked up once the names
are read; to be checked).

## Later (noted, not started)

- Name search beyond GeoNames (streets, addresses, any point of interest): needs a geocoding
  service usable commercially (paid). Other countries: add their codes to `buildPlaces`.
- The frame on the map: the cone drawn as the camera's field of view for a chosen focal length
  and sensor (300 mm 8°, 135 mm 18°, 85 mm 28°, 50 mm 46°, 35 mm 63°, 24 mm 84°, 14 mm 114° on full
  frame), and a depth-of-field calculator (near and far limit, hyperfocal distance).
- A cross-section of the ground from the camera to a point on the map.

## Pitfalls met

- Regular expressions: Android's engine (ICU) rejects a lone `}` or `]` that the JVM accepts, so
  a pattern that passes `:core:test` can crash on the phone. Escape every brace and bracket; the
  test `theForecastPatternsWorkOnAndroidToo` checks the planner's patterns.
- `app/build.gradle.kts` is compiled by Gradle's own, older Kotlin (no `break`/`continue` inside
  lambdas, no `use { }` there), and this machine cannot compile it (no Android SDK, no Google Maven):
  only CI tells. A local `:core:test` that passes says nothing about the app's build script.
  Inside a task, `java` means the project's `java` extension: write `java.util.…` as an import.
- After a crash the next start shows its report on the Editor/Planner screen (Copy, Hide).

## Data and services: only what may be used commercially

A rule for the planner: no data or service that may not be used in a paid app.

| What | Source | Terms (as checked 2026-11) |
|---|---|---|
| Sun, moon, Milky Way | computed on the phone | ours |
| Cloud forecast | MET Norway, api.met.no Locationforecast 2.0 | free, commercial use with credit ("Data from MET Norway", NLOD / CC BY 4.0); a User-Agent naming the app and a contact is required (`PLANNER_USER_AGENT`: put the maker's contact there before a release); keep answers until they expire. To confirm on api.met.no/doc/TermsOfService before a release. |
| Ground height | Terrain Tiles on AWS (Terrarium) | sources public domain or open licences (SRTM, USGS, Copernicus EU-DEM, …) with credit; shown in the Place panel |
| Map | OpenStreetMap's tile server | data ODbL (commercial with credit), but the tile server is for light use only: replace with a tile provider before a release |
| Place names | GeoNames country dumps (PL, SK, CZ), fetched by the build (`buildPlaces`) into the asset `places.tsv.gz` | CC BY 4.0: commercial with credit ("Place names: GeoNames", shown in the Place panel). Not pinned (dumps change daily); a failed download leaves the search empty. |
| Light pollution | none yet | the night-sky atlas (Falchi 2016) is CC BY-NC: not used. Bortle is set by hand. |
| Weather (earlier) | Open-Meteo free API | non-commercial: taken out |

## Network

The planner is the only part with network use (permission INTERNET): the weather forecast, terrain heights, and the map's tiles from
OpenStreetMap's own tile server, with its credit on the map and an identifying user agent, kept in
the cache folder so a place seen once can be seen again with no signal. That server's policy is
for light use; for a store release use a tile provider with a key. The editor uses no network.

## Not built yet (in this order)

1. [done, to be tried on a phone] Live view: camera behind the sky, compass and tilt, the light
   it reads as a base (`Measured`).
2. Light pollution: only from a source usable commercially (VIIRS night lights with a sky-glow
   model, if its licence allows); until then Bortle is set by hand.
3. [done] Terrain: skyline from elevation data, the sun hidden behind peaks; Find. A 3D view is not planned.
4. [done] Map with the pin, the cone for the way the phone looks, and the sun's rise, set and
   current directions. The cone follows the dragged sky now; it will follow the compass in live view.
5. [done] Cloud forecast (Open-Meteo), shown in brackets beside the result.
6. [done] Saved plans (place, day and hour, camera, sky).
