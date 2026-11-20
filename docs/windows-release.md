# OpenBGI ASV {VERSION} for Windows

32-bit Windows build. Supply game archives and translation fonts separately.

## Launching

Extract the ZIP into its own writable folder. Run `OpenBGI.exe` and select
the game directory. Use **Advanced** for the engine profile and launch
arguments. The launcher creates `games.json` for its settings.
`OpenBGI-Launcher.cmd` or `--launcher` always opens the picker. When started
inside a folder containing `system.arc` or `ipl._bp`, the EXE starts that
game directly.

## Fonts and translation patches

Paths are relative to the selected game folder. Available options include:

| Option | Purpose |
| --- | --- |
| `--engine=PROFILE` | Interpreter profile |
| `--productname=NAME` | Product name expected by the scripts |
| `--patch-dir=patch` | Prefer replacement resources in this folder |
| `--char-remap=003E:00D3,003C:00D5` | Unicode glyph substitutions |
| `--font-file=repair/MyFont.ttf` | Load a font from a file or subfolder |
| `--font-face=FAMILY` | Override the internal font family |
| `--font-charset=N` | GDI charset, 0..255; default 128 (Japanese) |
| `--font-codepage=N` | Single-byte glyph code page |
| `--font-size=N` | Rasterised height, 4..200 pixels |
| `--font-width=N` | Average width, 0..200; 0 selects natural width |
| `--font-weight=N` | Weight, 1..1000; regular 400 |
| `--letter-spacing=N` | Extra horizontal spacing, -16..64 pixels |
| `--no-auto-font` | Disable automatic font discovery |

Discovery searches the game folder before its subfolders. Explicit font
options have priority. Windows reads `letter_spacing` and `auto_font` from
`OBAConfig.json`. The UIF JSON reader and bundled translation-preset
selector are Android features; Windows uses launch options for redirects
and remapping. UIF proxy DLLs are not automatically disabled.

Russian Sakura no Uta example:

```text
--engine=1.616 --productname=Sakuranouta --font-file=repair/YasuSakuuta.ttf --font-face=YasuSakuuta --font-codepage=1252 --font-charset=1
```

Examined Eustia translation:

```text
--engine=1.529 --productname=AiyokunoEustia_Product --font-file=EustiaFont.ttf --font-face=EET --font-width=0 --letter-spacing=0
```

See the repository README for the complete H2O launch example.

## Audio and movies

Vorbis decoding is included. Windows uses DirectShow, with an optional
external `ffmpeg`/`ffprobe` fallback. These helpers are not bundled.
The embedded MPEG-1/MP2 decoder in release {VERSION} is Android-only.
Video textures and the separate archive-entry movie command remain
unsupported.

## Source and licenses

OpenBGI and its integration use GPL-2.0-only (`LICENSE`). stb_vorbis uses
the MIT option (`LICENSE-stb_vorbis.txt`). Corresponding source and build
scripts are provided in the source ZIP accompanying the release.

Project: https://github.com/Kotsuider/openbgi-ASV

Upstream: https://github.com/Cytlan/openbgi

Additional credits are in `RELEASE-NOTES.md`.
