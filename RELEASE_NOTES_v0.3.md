# OpenBGI ASV 0.3

This release adds translation presets, per-game font selection, Android video playback, and new Windows font and patch options.

## Android

- Select a translation configuration in each game's settings. Presets are stored in `game-configs.json` and matched against the game folder name.
- Automatic configuration priority is **UIF → OBAConfig.json → matching bundled preset → none**. A manual choice overrides automatic selection.
- Bundled presets cover H2O, Suika A.S+, Sakura no Uta, Eustia, and Itsusora. A preset's recommended engine overrides version detection; a manual engine override has priority.
- Choose a TTF, OTF, or TTC file, or select an Android system font with a preview. Manual fonts have highest priority and are saved separately for each game. Imported files are copied into app storage; the source file can then be removed. Reset restores normal font selection.
- Automatic font discovery searches the game folder first, then subfolders. It can be disabled per game. Configured fonts take priority over discovery.
- MP4/H.264/AAC movies play through Android MediaPlayer. H2O's standalone MP4 movies work with their original `.arc` filenames.
- MPEG-1/MP2 movies, including Suika's opening, play through the embedded MIT-licensed PL_MPEG decoder. No external FFmpeg installation is needed for these Android paths.
- Native builds now use `-O2`. MPEG playback retains its file descriptor, precomputes scaling coordinates, and converts only the latest frame during catch-up. The tested Suika opening segments displayed 30 fps on the test phone. Playback timing is recorded in Logs.
- Custom translation fonts can use `--font-codepage`, including code page 1252 for Sakura no Uta.

## Windows

- Includes the game picker and `OpenBGI-Launcher.cmd`; `--launcher` opens the picker explicitly.
- `--patch-dir=DIR` loads replacement resources before loose originals and archive entries.
- `--char-remap=SOURCE:TARGET,...` supplies Unicode glyph substitutions for both drawing and measurement.
- `--font-charset=N` selects the GDI charset; the default remains Japanese (128).
- `--font-codepage=N` controls single-byte glyph decoding independently of the script encoding.
- Font files can use relative subfolder paths. The engine reads their internal family names when no explicit family is supplied.
- Automatic font discovery is enabled by default; use `--no-auto-font` to disable it. Explicit font settings take priority.

## Compatibility checked

- **H2O:** Android Russian text and standalone H.264/AAC movies; Windows patch-directory, remap and font settings.
- **Suika A.S+:** profile `1.69/472`, product `SuikaENG`; Android MPEG-1/MP2 opening playback.
- **Sakura no Uta, Russian translation:** bundled `1.616` preset, YasuSakuuta font and code page 1252. The configured profile takes priority over a different version reported by the patched EXE.
- **Eustia:** Windows and Android startup and Russian text rendering with the translation font.

Compatibility checks cover the exercised paths; full playthroughs have not been verified. Video textures and the separate archive-entry movie command remain unsupported. Windows retains DirectShow and the optional external FFmpeg fallback; the embedded MPEG decoder is currently Android-only.

## Downloads

- `OpenBGI-ASV-0.3-android-arm64.apk` — signed Android APK; Android 9/API 28 or later, ARM64.
- `OpenBGI-ASV-0.3-windows-x86.zip` — 32-bit Windows package with the launcher, instructions and licenses.
- `OpenBGI-ASV-0.3-source.zip` — corresponding source, build scripts, and the pinned stb_vorbis source used for the binaries.
- `SHA256SUMS.txt` — SHA-256 checksums for these downloads.

Game archives, translation fonts and game media are supplied separately. Windows executables are not Authenticode-signed. An Android installation signed with a different key, such as a debug build, cannot be updated in place with the release APK; use the same signing lineage to preserve an existing installation.

## Licenses and credits

OpenBGI and the Android integration are GPL-2.0-only. Third-party license notices are retained, including MIT notices for stb_vorbis and PL_MPEG. Thanks to Cytlan and the OpenBGI contributors, WenniDev for the imported Subarashiki Hibi support, and Dominic Szablewski for PL_MPEG. See `android/THIRD_PARTY_NOTICES.md` in the source package.
