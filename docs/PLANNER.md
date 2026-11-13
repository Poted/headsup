# Light planner

For any place and day: where the sun, moon and Milky Way are, how bright the light will be hour by
hour, and what to set on the camera (aperture, shutter, ISO, ND filter). Offline. Opened from
Project › Projects › "Light planner". It is not an editing project: its settings are kept by
itself (`PlannerPrefs` in `PlannerScreen.kt`), not in `ProjectStore`.

## Where it lives

`:core` `planner/` (pure, tested): `Sky` (sun to about 0.02°, moon to about 0.3°, galactic centre and
plane for the J2000 sky, sunrise/sunset and twilights), `SceneLight` (scene EV at ISO 100 from sun
and moon height, place height, Bortle class, cloud), `Exposure` (the triangle with ND, one setting
auto, warnings with a suggested filter), `Planner.frame` (all of it for one moment, optionally from
a measured EV).

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

## Not built yet (in this order)

1. Live view: camera behind the chart, GPS, compass and gyro, a metered EV as the base (`Measured`).
2. Light pollution raster (VIIRS / Falchi atlas, small, bundled) instead of the Bortle stepper.
3. Terrain: horizon profile from elevation data, the sun hidden behind peaks, a 3D horizon.
   Needs data fetched or bundled: the app has no INTERNET permission today.
4. Mini map with the observation point and view cone.
5. Cloud forecast (needs network).
6. Saved places and plans; the planner as a project kind.
