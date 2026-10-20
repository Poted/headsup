# headsup

Offline Android app that burns a Garmin FIT telemetry dashboard (heart rate, speed/pace,
altitude, distance, grade, cadence, minimap, …) into videos from DJI cameras and phones,
synchronising data to each clip automatically.

Status: stage 1 – FIT parsing, telemetry sampling and automatic time synchronisation
(`:core` module). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tests

```
./gradlew :core:test
```

To also decode your own watch files (kept out of the repository):

```
HEADSUP_FIT_SAMPLES=/path/to/fit/files ./gradlew :core:test --tests '*LocalSamplesTest*' -i
```
