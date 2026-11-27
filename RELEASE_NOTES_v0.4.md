# Android v0.4 — OpenBGI v1.0 migration

The Android app now uses the OpenBGI v1.0 core from upstream commit
`e6e9e24790d72ed3c4162092062f7425e82223ab`. The migration branch is
`codex/android-api-v1`; v0.3 remains available at `b628a21` and on
`codex/android-port`. Nothing is published automatically.

## Architecture

The core is built from upstream's new `base`, `fs`, `core`, VM, graphics,
sound and UI sources with only core feature flags. It has no Android/WASM
platform defines. `android/host.c` creates an `OpenBGI_Host_t`, adds Android's
movie player and MPEG decoder, and drives an `OpenBGI_t` through the public
API and upstream's shared `OS_RunEngine` loop. The Android host reuses the
WASM backend's file/cache/overlay, input and canvas adapters with JNI
callbacks. `BGI_ANDROID_APP` distinguishes its real window from upstream's
headless Android command-line build.

The JNI adapter and decoder no longer access `Engine_Main`, global engine
profiles or the old engine mixer. The shared OS adapter is still upstream
implementation code, so future merges may require host changes even when
the public API remains compatible.

## Preserved Android features

- Game library, SAF directory grants, covers, per-game saves and settings,
  control overlays, key holds and launcher languages.
- Translation presets, UIF redirects and character substitutions,
  manual/system/configured/automatically discovered fonts and code pages.
- CP932/UTF-8 Cyrillic word wrapping and natural advances, font size/width,
  letter spacing and OBAConfig.json spacing defaults. Core text options
  belong to each engine and reset when a run restarts.
- Platform MP4 playback and streamed MPEG-1/MP2 playback. The MPEG decoder
  implements `OpenBGI_MovieDecoder_t`, reads through `OpenBGI_MovieFile_t`,
  retains a bounded frame/audio queue and uses precomputed horizontal
  scaling indices. The core owns timing, presentation and mixing.
- Fork support for compact bytecode, 28-bit pointers, pooled allocations,
  expanded display sizes and assembly/decode formats of engine 1.685.
  Upstream's additional engine generations, instructions and API are kept.

Android rasterization options such as font discovery and code pages are
handled by the Android host. The upstream desktop host does not inherit
those Android renderer settings merely by linking the new core.

## Font controls and custom configurations

Game settings include labelled numeric controls for font size, width, spacing
and weight, with validation and game-default resets. The controls preserve
unrelated launch arguments and work with preset values, including Itsusora.
Users can save their current settings as a named configuration, update it or
delete it. User configurations are stored privately and shared by the game
library; built-in presets and game files are not edited. Selected font files
and system families remain per-game settings.

## Explicit opcode overrides

`--enable=FAMILY:OP` first keeps an implementation registered for the chosen
profile, preferring the narrowest matching range as usual. When there is
none, it searches registered ranges by distance in the engine-generation
order. A unique nearest implementation is used and its range is logged.
Equally near registrations of the same function are accepted; equally near
different functions are ambiguous and no implementation is selected. With
no registration, the instruction still reports unimplemented. `--disable`
always leaves the instruction disabled. Profiles without an explicit enable
never borrow implementations from another generation.

This is an opt-in compatibility experiment, since neighbouring builds may
use different operands. Suika uses its existing `--enable=C0:18`; the C0:18
registration itself keeps upstream's original version range.

## Validation

Portable tests are built with `-DBGI_NATIVE_TESTS=ON` in `android/CMakeLists.txt`:

- Upstream `tests/core.c`: API structure sizes/versions, game lifecycle,
  requests, repeated starts/stops and host copies.
- `tests/android_text.c`: Cyrillic classifications and word runs, natural
  advances, spacing limits, rejected malformed options and independent
  settings of multiple engines.
- `tests/android_compat.c`: fork compact bytecode instructions, pointer
  pools and display limits, assembly and truncated bytecode rejection.
- `tests/android_movie.c`: a complete supplied MPEG stream, frame ordering,
  PCM output, completion and invalid sizes. Media is not distributed.
- `tools/checkcore.py`: core code stays independent of platform headers
  and platform macros.

The ARM64 debug APK is built with optimization, linked with undefined
symbols rejected and verified with apksigner. It updates the existing app
with its existing debug key and application ID.

On the connected phone, Eustia reached its translated Russian title menu;
H2O produced game frames. The video smoke program exercises MPEG followed
by MP4 through Android MediaPlayer. These are startup/integration checks,
not full-game playthroughs or a comparison of old and new save formats.
The original app save directories and preferences are retained.

## Building and future synchronization

See [android/README.md](android/README.md). Native v1 objects use
`obj/android-api-v1`; APK packaging and the existing debug keystore remain
under `obj/android-app`. `build-apk.ps1 -Offline` can use an already populated
Gradle cache. Optional Maven mirrors are still selected with
`OPENBGI_GOOGLE_MAVEN` and `OPENBGI_CENTRAL_MAVEN`.

Upstream is merged with its real Git history. For the next update, fetch
`upstream`, create a local integration branch and merge `upstream/main`.
Keep Android callbacks/movie services at the public API boundary and review
the fork's text and 1.685 core changes separately. Do not copy an unpacked
source tree over the fork or resolve future conflicts by replacing all files.
