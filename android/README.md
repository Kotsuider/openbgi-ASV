# Android core build

This target compiles the engine to an ARM64 static library. The APK build
links the same engine to the Android host, which supplies the OS
interface and calls `Engine_Main` from the game activity.

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
running Android 9 or later. The debug signing key stays in `obj/android-app`.

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
