# Sweet Spot (repo `headsup`): working notes

An offline Android video and photo editor in Kotlin and Jetpack Compose. It aims at a
"DaVinci in the phone" quality of colour, light effects and export, worked with one thumb. It
began as a Garmin dashboard burner, which is why the package is `app.headsup` and FIT data still
drives the dashboard overlay.

## Build and test

- The Android app compiles **only in CI** (`.github/workflows/ci.yml`: `:core:test`, then
  `:app:assembleDebug`). It produces the APK artifact `headsup-debug-apk`.
- `:core` is pure JVM Kotlin and runs locally: `gradle -q :core:test --offline`. Check
  `core/build/test-results/test/*.xml` for `<failure`.
- Put pure logic (maths, colour, framing, timing) in `:core` with a unit test. Keep Android
  code (Compose, Media3, GL, Bitmaps) in `:app`.
- Work on a branch, open a PR into `main`, and merge only when the owner says so.

## Modules

`:core` (`core/src/main/kotlin/app/headsup/core/`):

| Package | What it holds |
|---|---|
| `color/` | `ColorGrade` (all corrections as one data class, with `+`, `*` and `synced`); `LutBaker.apply` (the per-colour pipeline, baked into a 3D table); masks; `SkinTone`; `PrinterLights`; `ColourHarmony`; `CineAssistant` (Analyse, scene traits); `ShotMatch`; curves, wheels, mixer |
| `frame/` | `Reframe` (crop, zoom, turn, fit/fill, `cropped`, `sourceOf`/`frameOf`); `FrameKeys` (Ken Burns, Follow); `Stabilizer` (Steady/Locked); `Lens`; `Perspective` |
| `edit/` | `CutTransition`, `GateWeave`, speed curves, texts |
| `photo/` | `Glow`, `LightFx` (mist, streaks, star, prism), `LensBlur` (bokeh), `ExposureFusion`, `PhotoCull` (Auto pick) |
| `raw/` | DNG decoding and development |
| `fit/`, `track/`, `sync/` | Garmin FIT, telemetry, matching clip time to activity time |
| `audio/` | loudness (LUFS) |

`:app` (`app/src/main/kotlin/app/headsup/`):

| Package | What it holds |
|---|---|
| `ui/HeadsupViewModel.kt` | All state (`UiState`, `ClipState`) and every action; undo history; autosave |
| `ui/HeadsupScreen.kt` | The screen: bottom tools Project · Edit · Adjust · Overlays · Audio · Export, and every panel |
| `ui/ClipPreview.kt` | Preview: ExoPlayer (two players for cuts) or the photo bitmap, colour through `GradePreviewShader` (AGSL RenderEffect), framing as graphicsLayer transforms, all picture gestures |
| `ui/Components.kt` | Shared controls: `RulerSlider` (hold = compare), `ChoiceGrid`, `ToolSection` (title, ⓘ, Reset), `CircleOption` |
| `color/` | Preview shader; AI models (`Segmenter`: scene model, MobileSAM; subject maps); `FastLooks.kt` (cinematic looks, `AppliedLook`/`AppliedHarmony`/`AppliedPreset` layers) |
| `export/` | `ExportManager` (Media3 Transformer and a chain of GL effects per clip: grade, glow, light FX, gate weave, cuts, overlays); `PhotoExporter` (CPU and RenderNode); `HdrRoute` (10-bit / HLG) |
| `render/` | `EffectSettings` (grain, glow, bars, bokeh, light effects…); the dashboard and text renderers, shared by the preview and the export |
| `project/ProjectStore.kt` | JSON save and load of projects (`SavedClip`, `Json.grade` / `Json.effects`); user presets |
| `media/` | Metadata (fps as shot, plus `measuredFrameRate`), RAW, proxies, reversing |

## Rules that matter

- **The preview and the export must match.** Every picture feature has a preview path (shader,
  RenderEffect or Compose layer) and an export path (GL effect or `PhotoExporter`). Both read the
  same core function: same noise, same timing, same maths.
- **Adding a field to `ColorGrade` or `EffectSettings`** means updating:
  - its use;
  - `isNeutralColour`, `times`, `plus` and `synced`;
  - `ProjectStore` JSON (with a default for old projects);
  - its UI.
- **Adding a field to `ClipState`** means mirroring it in `SavedClip`. Both conversions are in the
  ViewModel: search for `cineLook =` to find them.
- **Whole-look layers** (cinematic look, harmony, preset) are stored with the clip, together with
  the grade before them. **None** takes the layer off with `synced`, keeping changes made since.
  Never bake such a layer irreversibly.
- **Holding** a ruler or a circle compares only that thing (`onHold`, `compareGrade` /
  `compareEffects`). Holding the picture shows the original.
- **Gestures** on the preview live in `ClipPreview`:
  - several `pointerInput`s chained on one box, so order and pass (Initial or Main) matter;
  - a gesture that outlives compositions reads `rememberUpdatedState` values or plain holders,
    never `remember(key)` state that gets replaced.
- **Only data and services that may be used commercially** (models, maps, weather, datasets):
  no "non-commercial" licences or free tiers. See `docs/PLANNER.md` for the planner's sources.
- **Code style:**
  - doc comments in plain English sentences that say what a thing is for the user;
  - UI text in English;
  - no model names or IDs in the repository.
- **Docs:**
  - `docs/UX_FLOWS.md`: personas, flows and open UX problems;
  - `docs/ROADMAP.md`: done and planned;
  - `docs/TEN_BIT_PIPELINE.md`: 10-bit export;
  - `docs/ARCHITECTURE.md`: the original FIT and time-sync design.
  - `docs/PLANNER.md`: the light planner (sun, moon, Milky Way, exposure, live view).

## Commits

End commit messages with the attribution lines the session gives. Describe the change for the
user ("what it does now"), not the code.
