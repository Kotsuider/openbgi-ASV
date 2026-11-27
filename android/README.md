# Android core build

This target compiles the engine to an ARM64 static library. The APK build
links the same engine to the Android host. The JNI bridge creates an engine
with `OpenBGI_New` and runs it through the v1.0 API using the shared host loop.
The core has no Android or WASM platform defines; the host reuses the upstream
WASM file/window services with Android JNI implementations of their callbacks.
The `BGI_ANDROID_APP` host define enables the window table (upstream Android
CLI builds intentionally have none).

From the repository root, with NDK r26b and SDK CMake 3.22.1 installed:

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$cmake = Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $sdk 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $sdk 'ndk/26.1.10909125/build/cmake/android.toolchain.cmake'
& $cmake -S android -B obj/android-arm64 -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-23
& $cmake --build obj/android-arm64
```

The library is written to `obj/android-arm64/libopenbgi_core.a`.

## Debug APK

Install Android SDK Platform 35 and Build Tools 35.0.0 alongside the tools
above. With JDK 17 and Gradle 8.10.2 on PATH, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android/build-apk.ps1
```

Pass `-Gradle <path-to-gradle.bat>` to use an existing Gradle installation.
The resulting `bin/openbgi-android-debug.apk` requires an ARM64 device
running Android 9 or later. The debug signing key stays in `obj/android-api-v1`.

In the launcher, tap **+**, select the folder containing your game files in Android's
system picker and grant read access. Select the game and engine profile,
then tap **Launch**. The private launcher preferences store each game's
folder URI, display name and engine profile. Android preserves the read
grant for each added folder.

- **Folders** shows the path, selected profile, detected BGI build and
  whether a game config was found. Tap the profile to open game settings.
- **Library** shows two cover cards per row in portrait orientation.
  Long-press a card for the same settings. **Choose cover** opens the
  Android image picker. **Use game preview** clears the gallery override.
- **Logs** shows the last game launch. Use **Refresh** to reread the log.

Swipe horizontally to move between tabs. The top right gear opens the
launcher language settings.

Each game uses a separate save directory keyed by its selected folder
URI. Newly added games use the profile suggested by version detection
when available. If detection cannot suggest a profile, **Launch** opens
game settings so you can select one.

Android supplies the screen, touch input, fonts and PCM output. The in-game
gear configures overlay buttons, key assignments, holds, sizing and style.
In Move buttons mode, drag the enabled buttons and tap the checkmark to exit.
The build script fetches a pinned copy of stb_vorbis when
`src/snd/stb_vorbis.c` is absent.

## Signed release build

Use a private PKCS#12 keystore and supply its password in the
`OPENBGI_STORE_PASSWORD` environment variable for the build process.
From the repository root, run:

```powershell
.\android\build-apk.ps1 -Release -Keystore '<path-to-private-keystore.p12>' -KeyAlias openbgi-release
```

The script verifies the signature and writes
`bin/openbgi-android-release.apk`. Keep the keystore and password outside
the repository. Future updates signed outside Google Play require the
same signing key. Android will reject an in-place update over an
existing debug build signed with a different key.

Gradle's `assembleRelease` task writes an unsigned APK to
`android/build/outputs/apk/release/OpenBGIAndroid-release-unsigned.apk`.
See [third-party notices](THIRD_PARTY_NOTICES.md) for source and notice
requirements when distributing an APK.

## Translation patches

Place the translation's `patch` directory, JSON configuration and any
configured fonts in the selected game folder. The application checks
`uif_config.json` first and falls back to `config.json`, both UTF-8. No Windows
DLL is needed.

- The default file redirect checks `patch/<name>` for extensionless resource
  names and `._bp` scripts. A matching file is used before the original
  loose resource or the archive fallback. A `file_monitor` section replaces
  the default with its enabled `actions` (regex `pattern` and `redirect`).
  Targets must resolve to files within the selected folder.
- Enabled `character_substitution` pairs `source_characters` and
  `target_characters` by position for font measurement and drawing.
- Enabled font creation spoofing loads a matching font from
  `font_manager.resource_files` for use during rendering.

Other UIF features, including DLL hooks and binary configuration, are outside
this implementation.


## Per-game launcher configuration

Put `OBAConfig.json` next to the game archives:

```json
{
  "preview": "prev.bpfa",
  "uif": "uif_config.json",
  "arguments": "--productname=H2ORTAAACSE"
}
```

All keys are optional. The preview is decoded from its content, so WebP
works with the `.bpfa` extension. A cover selected in the gallery takes
precedence until you choose Use game preview. Preview and UIF paths are
relative to the selected game folder. If the configured UIF file cannot
be read, the game tries `uif_config.json` and `config.json`.

The launch settings allow a saved per-game command-line override. With
Use arguments from game config enabled, the launcher uses the JSON
arguments each time. Extra arguments go to OpenBGI's command-line parser
after the selected profile. Set `--productname` here if the game needs it.

## Russian text and font settings

Horizontal Cyrillic text supports CP932 (Shift-JIS) and the engine's
UTF-8 mode. Cyrillic words are kept together when they fit on a line.
UTF-8 scripts must enable the appropriate text encoding in the engine.

Enter font options in the game's **Arguments** field, or in the
`arguments` string of `OBAConfig.json`. For example:

```text
--font-size=24 --font-width=0 --letter-spacing=2
```

- `--letter-spacing=N` adds integer pixels to each horizontal character's
  advance, including spaces. The range is -16..64, and the default is 0.
  Positive values spread letters out. Negative values tighten them.
  Measurement and wrapping use the same adjustment.
- `--font-size=N` sets the rasterised font height (4..200 pixels). The
  script still controls the text area's layout size and line spacing.
- `--font-width=N` sets the average rasterised character width
  (0..200 pixels). Zero uses the font's natural proportions and advances
  for Cyrillic, Latin and ASCII punctuation and spaces.
- `--font-weight=N` sets the font weight (1..1000). Regular is 400,
  and bold is 700. Availability of individual weights depends on the font.
- `--font-file=FILE.ttf` loads a font directly from the selected game
  folder. Subfolders are supported. The loaded file takes priority over
  a UIF font and `--font-face`.
- `--font-face="sans-serif"` selects an Android system font family when
  no file override is loaded. Windows fonts such as Segoe UI can be
  supplied as a font file through `--font-file`.

An optional top-level `"letter_spacing": 2` in `OBAConfig.json` supplies
the spacing when the arguments do not contain `--letter-spacing`.
Explicit arguments take precedence. Letter spacing affects horizontal
text, including bidirectional text. Vertical text and ruby readings use
their existing spacing.

## Translation configuration presets

Long-press a library card, or tap the engine profile in Folders, to open
game settings. **Translation configuration** offers automatic detection,
any supported UIF JSON found in that game folder, `OBAConfig.json`, the
bundled presets, and None. Automatic selection prioritizes UIF, then the
local OBA configuration, then a preset matched against the folder name.
Manual selection is stored per game. Custom arguments override the
selected configuration's argument string.

Edit `src/main/assets/game-configs.json` to add a bundled preset:

```json
{
  "version": 1,
  "configs": [
    {
      "id": "my-translation",
      "name": "My game — translation",
      "match": ["My game", "Alternative title"],
      "engine": "1.616",
      "arguments": "--font-file=repair/MyFont.ttf --font-width=0",
      "auto_font": true
    }
  ]
}
```

The match aliases are compared to the selected folder name, ignoring case
and punctuation. Keep aliases specific to the intended game and edition.
Fonts and game resources are supplied separately; they are not in the APK.

**Find a font automatically** searches the root folder first, then
subfolders. A font explicitly specified by the selected configuration,
UIF, or command line takes priority. Disable the switch to retain the
engine's normal font selection. This setting is stored in the app's
private per-game preferences. The native options `--auto-font` and
`--no-auto-font` are also available. Android supports `--font-codepage`
for the single-byte glyph conversion used by custom translation fonts,
including code page 1252 used by the Sakura no Uta preset. Script parsing
and double-byte CP932 characters keep their existing encoding.

### A font selected on the device

In game settings, use **Choose font** to select a `.ttf`, `.otf` or `.ttc`
file through the system picker (maximum 64 MiB; first face for TTC).
A validated private copy is stored under the app's `files/fonts/` folder,
keyed by the game folder URI. The original file can then be removed.
The selected filename is shown in settings. **Reset font selection**
removes that game's private copy and restores normal font selection.
This setting applies to the next launch as soon as the file is imported.
Removing a game from the library also removes its private font copy.

The priority is **manually selected font → configured/command-line font →
UIF font → automatic discovery → engine defaults**. A manual font also
works when automatic discovery is disabled. Font weight, size, character
encoding and remapping still follow the existing launch settings.

**System font** selects a named Android font family directly, without a
file picker or a copy of a system font. The list previews each family and
includes the standard `sans-serif`, `serif`, and `monospace` families.
Additional named families and aliases are read from accessible system and
vendor font XML files. The list therefore varies by Android version and
firmware. The family is saved per game, with the same highest priority as
an imported font file. Selecting a family replaces the active manual file
choice, and importing a file switches back to that file. **Reset font
selection** clears both choices and deletes any retained private font copy.

## Video playback

The `90 F0` movie player uses Android MediaPlayer for supported platform
formats, including H.264/AAC MP4. The video surface follows the game image
rectangle and stays below the game controls. Files are opened through the
selected folder grant. H2O stores MP4 movies under names such as
`data00500.arc`; their contents determine the format, so renaming or
extracting them is unnecessary.

MPEG-1/MP2 program streams, including Suika A.S+'s `Movie/opening.mpg`,
use the embedded PL_MPEG decoder. It reads incrementally, displays decoded
frames through OpenBGI, and sends PCM through the engine mixer. Movie
volume, script stop/skip and completion use the existing movie interface.
Neither `ffmpeg` nor `ffprobe` is required for these Android playback paths.

Video textures remain unsupported. BF_Movie bitmap sequences use the upstream
decoder. MPEG movie files are read through the public movie-file interface,
including sources the engine can read from archives.

Native APK builds default to `RelWithDebInfo` (`-O2`). MPEG playback keeps
the selected document descriptor open for the duration of the movie and
uses precomputed horizontal scaling coordinates. Catch-up decoding converts
only the most recent frame to BGRA. Every five seconds the launch log
reports displayed FPS and decode/output timing; these measure the engine
path and do not include the Android compositor latency.

### Playback diagnostics

Open **Logs** after a launch and look for the selected player:

```text
bgi: PL_MPEG C:\Movie\opening.mpg (640x480, 30.000 fps, 44100 Hz, 132899 ms)
bgi: MPEG performance: 30.0 fps shown, decode 1.47 ms/frame, output 2.78 ms/frame, worst tick 5.76 ms
```

MP4 playback reports `Android MediaPlayer opened` followed by the movie
path. The `.arc` suffix of H2O's standalone movie does not require an
archive decoder. The examined `data00500.arc` is byte-for-byte identical
to the supplied `1data00500.mp4` example: H.264/AAC MP4 at 800×600.

The timing line above is an example recorded on the test phone during a
Suika opening segment. At 30 fps the frame interval is approximately
33.3 ms. Decode/output timing covers the engine path; it does not measure
final on-screen presentation or guarantee smooth playback on every device.

For portable regression checks (a host C compiler, CMake and Ninja required):

```powershell
python tools/mkmsg.py
cmake -S android -B obj/android-api-tests -G Ninja -DBGI_NATIVE_TESTS=ON
cmake --build obj/android-api-tests
ctest --test-dir obj/android-api-tests --output-on-failure
obj/android-api-tests/test_android_movie.exe path/to/MPEG1-MP2.mpg
```

The tests cover the upstream core lifecycle/API contract, Cyrillic word runs,
font option boundaries and engine isolation, compact instructions of 1.685,
pointer pools, display sizes, bytecode decoding and streamed movie playback.
No game media is bundled with tests or the APK.

`src/androidTest/` contains an optional adb smoke driver. Build it with
`assembleDebugAndroidTest`, install the test APK and pass a game tree URI
for which the app already has a persisted grant:

```text
adb shell am instrument -w -e tree CONTENT_URI -e profile 1.529 -e wait 30000 org.openbgi.test/org.openbgi.SmokeInstrumentation
```

The driver saves `files/smoke-last.png` before it ends. Instrumentation ending
stops the app's processes; this is not a game crash. Optional `configuration`,
`arguments`, `tapX` and `tapY` extras select a preset/arguments and tap once after
the wait, then capture after another ten seconds. The driver is not in the app APK.
See [migration notes](../RELEASE_NOTES_v0.4.md) for the scope of validation.


## Font controls and user configurations

Open game settings (long-press a library card, or tap the profile in Folders)
and choose **Text and font parameters**. The fields control glyph height,
width, letter spacing including spaces, and weight. Their ranges and examples
are shown beside them; 0 width uses natural font proportions. Empty fields
remove the corresponding override. Apply, then save game settings. Other
launch arguments and quoted font paths are preserved. Advanced arguments
remain editable and the font controls read their current values.

**Save as new configuration** stores the current arguments, engine profile,
automatic font setting and selected UIF path under a name. User configurations
appear as **My configuration · Name** and can be used by other games. Select
one to update or delete it. Built-in presets and game JSON files are read-only
in this editor; use them as a starting point for a new configuration.
Configurations are private app preferences, not files written to the game
folder. Imported font files and manually chosen system fonts remain per-game
settings. Deleting a user configuration switches its games to None after a
confirmation; manual arguments and font selections are kept.

The argument editing tests run with the JDK alone:

```powershell
javac -encoding UTF-8 -d obj/android-java-tests android/src/main/java/org/openbgi/FontArguments.java tests/android/FontArgumentsTest.java
java -cp obj/android-java-tests org.openbgi.FontArgumentsTest
```

The instrumentation driver supports `-e mode settings` for isolated storage
checks, and `-e mode editor -e tree CONTENT_URI` for numeric validation and
create/update/delete through the actual UI. The UI test cancels game settings
and removes its own temporary configurations. It stores `settings-font-ui.png`
and `settings-config-ui.png` in app files for visual inspection.
