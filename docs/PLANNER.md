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
auto, warnings with a suggested filter), `Planner.frame` (all of it for one moment, optionally from
a measured EV), `Timelapse` (the filter cannot change during a timelapse: from the light's span
over a window, the filter range that one filter must lie in, and the shutter and ISO at each
moment; shutter first, at the lowest ISO).

`:app` `ui/PlannerScreen.kt`: place and date, the sky chart (drag to turn; sun path with its disc,
moon path, Milky Way band), time bar, conditions, the exposure tiles and the warning.

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

## Not built yet (in this order)

1. Live view: camera behind the chart, GPS, compass and gyro, a metered EV as the base (`Measured`).
2. Light pollution raster (VIIRS / Falchi atlas, small, bundled) instead of the Bortle stepper.
3. Terrain: horizon profile from elevation data, the sun hidden behind peaks, a 3D horizon.
   Needs data fetched or bundled.
4. Mini map (needs network: adds the INTERNET permission, used by the planner only) with the
   observation point and an arrow for the way the phone looks.
5. Cloud forecast (Open-Meteo): optional, shown beside the clear-sky result, with a manual cloud
   slider, since a forecast of cloud is sometimes wrong.
6. Saved plans (a place with its timelapse settings); saved places exist already.
