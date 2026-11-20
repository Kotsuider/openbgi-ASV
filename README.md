# OpenBGI for Android

An Android launcher and native port of [Cytlan/OpenBGI](https://github.com/Cytlan/openbgi)
for games built with the Ethornell/Buriko General Interpreter.

**Alternative for Android:** [Enginehost Buriko plugin](https://github.com/Droidtop/enginehost-buriko-plugin)
packages OpenBGI as an engine for [Enginehost](https://github.com/Droidtop/enginehost).
This fork builds a standalone Android application.

## Android features

- **Folders, Library and Logs** with swipe navigation. Folders shows
  each game's path and engine profile. Library shows cover cards in two
  columns on portrait phones. The launcher remembers the folder, profile,
  custom cover and command-line override. Removing an entry keeps the
  game files and saves.
- **Automatic BGI version detection** from the original game EXE. The
  launcher shows the detected build and flags missing exact profiles.
  For the 1.69 family it can suggest the nearest known build. If there
  is no suggestion, select a profile manually before launching.
- **Per-game configurator.** Choose an engine profile and translation
  preset, edit launch arguments, pick a cover, and select a font file or
  Android system font. Settings are saved separately for each game.
  `OBAConfig.json` and supported UIF configurations are loaded automatically;
  the bundled JSON presets can also be selected manually. See
  [configuration and font priorities](#translation-presets-and-font-discovery).
- **Partial UIF configuration support** for loose-file redirects,
  character substitution and a configured replacement font. Translation
  files can be placed in `patch/` beside the archives.
- **Touch and configurable controls.** Tap with two fingers for a right
  click and swipe vertically for the mouse wheel. Overlay buttons can
  send Windows keys, including hold-to-skip with Ctrl, and can be moved,
  resized, styled or hidden.
- **Local saves, Vorbis audio and a readable launch log.** The log records
  the last game launch. The launcher has eleven interface languages.
- **Cyrillic text and font options.** Horizontal Russian text supports
  proportional spacing and word wrapping. Font size, width, weight,
  family and extra letter spacing can be set in launch arguments.
  See the [font settings guide](android/README.md#russian-text-and-font-settings).

## Quick start

1. Use an ARM64 Android device running Android 9 (API 28) or later.
2. Copy your game files to a folder such as `Games/My Novel` in shared
   storage. Keep the original EXE for version detection.
3. Tap **+**, select the game folder in the system picker and grant read
   access. Launch from **Folders** or **Library**. To edit launch options,
   tap the profile in **Folders** or long-press the Library card.
4. In **Game settings**, choose an engine profile and translation
   configuration, enter a command-line override or restore the configured
   arguments. You can also select a font file or system font, enable or
   disable automatic font discovery, and pick or reset the cover.

For a translated game, place the patch files, any required fonts, and
the configuration in the same game folder. Example:

```json
{
  "preview": "prev.bpfa",
  "uif": "uif_config.json",
  "arguments": "--productname=H2ORTAAACSE"
}
```

Save this as `OBAConfig.json`. All fields are optional. A WebP preview can
use the `.bpfa` extension. The translation settings support
`file_monitor.actions` redirects, `character_substitution`, and
`font_manager` resource files with font creation override. See the
[Android guide](android/README.md) for lookup and override rules.

## Translation presets and font discovery

Android game settings include a translation configuration selector. The
bundled presets live in `android/src/main/assets/game-configs.json` and
currently cover H2O, Suika A.S+, Sakura no Uta, Eustia and Itsusora.
Each entry has an `id`, display `name`, `match` aliases, recommended
`engine`, and `arguments`. Matching uses the game folder name, ignoring
case, punctuation and spaces. The first matching entry wins.

Automatic selection uses this order:

1. A supported UIF JSON found in the game folder, including the file named
   by `OBAConfig.json`. The local launch arguments remain available.
2. `OBAConfig.json` in the game folder.
3. A matching preset from `game-configs.json`.
4. No translation configuration.

Game settings allow a manual choice of any available UIF, local config,
bundled preset, or **None**. The choice, custom arguments, engine profile
and automatic-font switch are saved per game. Selecting a preset manually
also selects its recommended engine profile. New games use the preset's
recommended engine where available, including when an original EXE reports
a different version. A manually overridden engine takes priority.
Choosing **None** turns off UIF configuration loading; automatic font
selection remains controlled by its separate switch.

Android game settings also offer **Choose font** and **Reset font selection**.
Use **System font** to select an Android font family, with a preview in
each list row. Standard `sans-serif`, `serif` and `monospace` are always
listed; additional families and aliases are read from accessible system
font configuration files. Their availability depends on the device.
Choosing a system family or importing a file replaces the active manual
choice for that game. **Reset font selection** clears the manual choice.

Choose a TTF, OTF or TTC file through the system file picker. The app
validates it and keeps a private copy per game, so the source file can be
removed afterward. Files up to 64 MiB are accepted; TTC uses the first
face. A manually selected font has priority over command-line/configured
fonts, UIF and automatic discovery. Resetting restores the normal font
selection order. Font selection applies immediately to the next launch,
like the cover setting. It changes the typeface; encoding, remapping and
font-size settings continue to come from the game configuration.

On Windows and Android, automatic font discovery searches the game folder
first, then subfolders in breadth-first order. Files and subfolders are
sorted by filename within each folder. Supported formats are `.ttf`,
`.otf` and `.ttc` (first face). Unreadable or unsupported fonts are skipped.
The scan is limited to 16 directory levels and 4096 folders. A font chosen
through `--font-file` or UIF takes priority; an explicit `--font-face`
also prevents automatic discovery. Windows reads the font's internal
family name, which can differ from its filename. This selects a font;
translation-specific character mappings still need their configuration.

Use the per-game **Find a font automatically** switch on Android or
`--no-auto-font` on either platform to disable discovery. `--auto-font`
enables it explicitly. `OBAConfig.json` can also contain `"auto_font": false`.
Command-line settings override this field; explicitly configured fonts
continue to load when automatic discovery is disabled.

## Translation patch methods

OpenBGI reads translated archives, scripts and images through its own
engine. A patch can also depend on font settings or hooks supplied by the
original game's modified EXE or a Windows proxy DLL. The methods below
describe the parts currently reproduced by this fork.

### UIF configuration and loose files — H2O

Universal Injector Framework (UIF) commonly runs through a proxy such as
`winmm.dll`. Its JSON configuration tells the Windows game how to redirect
resource requests, substitute characters and select a replacement font.
For example, a request for a script can be redirected to `patch/<name>`,
giving the translated file priority over the archive.

The Android host implements a subset of this configuration directly.
Place the patch files, JSON and fonts in the selected game folder. It
reads the file named by `OBAConfig.json`'s `uif` field, then tries
`uif_config.json` and `config.json`. Supported features include the following.

- `file_monitor.actions` with regex `pattern` and `redirect` fields.
  An enabled action redirects to a matching file in the game folder.
  With no `file_monitor` section, extensionless resources and `._bp`
  programs are looked up under `patch/` first.
- `character_substitution` with positional pairs from
  `source_characters` and `target_characters`. The same substitutions
  are used for drawing and measuring glyphs.
- Enabled `font_manager` and `spoof_creation`, loading a font from
  `resource_files`. A nonempty `override_face` is matched against the
  resource filename without its extension.

H2O uses loose translated resources, a replacement font and character
substitution. Its launcher configuration can use these fields.

```json
{
  "uif": "config.json",
  "arguments": "--productname=H2ORTAAACSE"
}
```

For Windows OpenBGI, select the profile and product name in
`OpenBGI.cmd`.

```bat
@echo off
setlocal
cd /d "%~dp0"
start "" "OpenBGI.exe" --engine=1.69/472 --productname=H2ORTAAACSE ^
  --patch-dir=patch ^
  --char-remap=003E:00D3,003C:00D5,002B:00B9,0025:00B2,0023:00C9,005E:00AB,0040:00BB,003B:00A1 ^
  --font-file=OUCTSfontD1.ttf --font-face=OUCTSfontD1 --font-charset=0
```

`--patch-dir=patch` gives files in that folder priority over the game's
loose resources and archive entries. Relative paths start at the selected
game folder. Missing replacements fall back to the original resources.
This covers resource reads, size and existence queries, and movie paths.
Save files keep their normal location.

`--char-remap` supplies comma-separated Unicode hexadecimal pairs in
`SOURCE:TARGET` form. The H2O table above reproduces its UIF substitutions
`><+%#^@;` → `ÓÕ¹²É«»¡`. These target characters select the intended glyphs
in the translation's custom font. Each substitution is applied once,
after decoding a character, when drawing and measuring it. Script strings
and control commands keep their original bytes. Up to 64 unique source
characters are accepted. An empty value clears the table.

Windows OpenBGI does not read UIF's JSON configuration. The launch options
above provide its resource override, font and character substitution
settings directly. It does not automatically disable or unload a UIF
proxy DLL. For a launch without UIF, keep `OpenBGI.exe` in a directory
without the proxy, or disable the proxy before starting it. Launching a
separate OpenBGI executable with the game folder as its boot path also
keeps the proxy out of its executable directory. When running the original
Windows game with UIF, retain its proxy and configuration.

Android reads the supported UIF JSON sections through its host. UIF's
executable hooks and window-title overrides are outside this subset.

### Repacked resources and a modified EXE — Itsusora

This method replaces scripts and images inside the game's archives.
The translation also patches the original EXE to change font creation,
character widths or line breaking. OpenBGI can read the repacked resources;
the EXE's code changes must be reproduced through engine settings.

The examined Itsusora translation sets Segoe UI, a height of 24 pixels,
natural character width and regular weight. Its Windows launch script
uses these settings with an adjustable letter-spacing value.

```bat
@echo off
setlocal
cd /d "%~dp0"
start "" "OpenBGI.exe" --engine=1.66 --productname=ItsukaTodokuAnoSorani --font-face="Segoe UI" --font-size=24 --font-width=0 --font-weight=400 --letter-spacing=2
```

On Android, select profile `1.66` in game settings and place the launch
arguments in `OBAConfig.json`.

```json
{
  "arguments": "--productname=ItsukaTodokuAnoSorani --font-size=24 --font-width=0 --font-weight=400 --letter-spacing=2"
}
```

To use a font file on Android, place it in the game folder and add
`--font-file` directly to the arguments. For example, with
`SegoeUI.ttf` beside the archives.

```json
{
  "arguments": "--productname=ItsukaTodokuAnoSorani --font-file=SegoeUI.ttf --font-size=24 --font-width=0 --font-weight=400 --letter-spacing=2"
}
```

Use the actual font filename. Paths are relative to the selected game
folder. On Android the loaded file takes priority over the UIF font and
`--font-face`. Without a file override, the existing font selection applies.

The `2` is a manually selected spacing adjustment for this translation.
Tune it against the original game's rendering.

| Launch option | Effect |
| --- | --- |
| `--font-face="NAME"` | Override the font family. The font must be available on the target platform. |
| `--font-file=FILE.ttf` | Load a font file on Windows or Android before the game creates its fonts. Android paths are relative to the selected game folder. |
| `--font-size=N` | Rasterised font height, 4..200 pixels. The script still supplies layout size and line spacing. |
| `--font-width=N` | Average rasterised character width, 0..200 pixels. Zero uses natural proportions and advances for Cyrillic and ASCII. |
| `--font-weight=N` | Font weight on Windows and Android, 1..1000. Regular is 400 and bold is 700. |
| `--letter-spacing=N` | Extra horizontal advance per character, including spaces, from -16 to 64 pixels. Positive values spread text out and negative values tighten it. |

Spacing defaults to zero and is included in text measurement and wrapping.
It applies to horizontal text, including bidirectional text. Vertical
text and ruby readings retain their existing spacing. The script's
encoding must also match the translation. Cyrillic is supported in
CP932 and in the engine's UTF-8 mode.

On Android, enter the options in the game's **Arguments** field or the
`arguments` string in `OBAConfig.json`. Android uses its available system
families or a font loaded through the supported UIF configuration.
See the [Android font guide](android/README.md#russian-text-and-font-settings).

### VNTextPatch and font-proxy patches — Eustia

Some translations store Russian text as Latin character codes and put
Russian glyphs at those positions in a custom font. The accompanying
Windows DLL loads that font and changes the game's font selection.
Running the translated resources with a regular Latin font then displays
the encoded Latin text.

The examined Eustia patch includes `EustiaFont.ttf`, whose internal family
name is **EET**. Drawing its Latin alphabet produces Russian letters.
The accompanying `version.dll` identifies its build as `VNTextProxy`,
registers the TTF and replaces the requested font family with EET.
This describes the examined package's font proxy; other VNTextPatch
distributions can use different hooks and resource layouts.

Windows OpenBGI loads the font directly through these launch options.

```bat
@echo off
setlocal
cd /d "%~dp0"
start "" "OpenBGI.exe" --engine=1.529 --productname=AiyokunoEustia_Product --font-file=EustiaFont.ttf --font-face=EET --font-width=0 --letter-spacing=0
```

Keep the script's encoded character values when using this font. The
glyph replacement is supplied by the font itself. The family's internal
name, EET, differs from the filename.

Eustia's `d3d9.dll` also scales requested font widths greater than 30 to
80% of their original value. The example above uses natural width as a
tested alternative setup. Text rendering was checked on Windows.
The original EXE reports BGI 1.521.4, and `1.529` is a provisional
compatibility profile. Full playthrough compatibility remains unverified.

On Android, select profile `1.529` in game settings, keep
`EustiaFont.ttf` in the game folder, and use this `OBAConfig.json`.

```json
{
  "arguments": "--productname=AiyokunoEustia_Product --font-file=EustiaFont.ttf --font-width=0 --letter-spacing=0"
}
```

The Android host uses the loaded file directly, so the internal family
name can be omitted. The font-file option is implemented on Android;
Eustia's text rendering has been verified on Windows.

## Video on Android

Standalone movies opened by the game's `90 F0` command are supported:

- MP4/H.264/AAC uses Android MediaPlayer with a video surface below the
  editable game buttons. Playback reads the selected game folder directly.
- MPEG-1 program streams with MP2 audio use the embedded MIT-licensed
  PL_MPEG decoder. Video is drawn through the engine; audio goes through
  its existing mixer. No external FFmpeg installation is required.

H2O's `data00500.arc` is an MP4 video with an `.arc` extension, rather than
an archive. Format detection uses its contents. Its system script calls
`90 F0`, so it uses the same movie player. Suika A.S+ uses
`Movie/opening.mpg` (MPEG-1/MP2). Both have been checked on Android.
Movie stop, volume and completion are connected to the engine's existing
movie commands. The game script continues to control skipping.

Android native builds use `RelWithDebInfo` (`-O2`). MPEG playback retains
one file descriptor, precomputes scaling coordinates, and converts only
the latest frame during catch-up. The **Logs** tab reports playback FPS
and decode/output time every five seconds. A checked Suika opening segment
on the test device displayed 30 fps, with about 1.5–1.8 ms for decoding and
2.8 ms for conversion/output per frame. These are measurements from that
device and segment, rather than a performance guarantee for other games
or phones. See the [Android video guide](android/README.md#video-playback).

This covers standalone movie playback. It does not add the separate
archive-entry player (`92 F0`) or video textures (`91 F*` / `92 F2`).
The existing BF_Movie frame-sequence decoder is independent of this path.
Windows and Linux retain their existing native/FFmpeg playback paths.

## Compatibility and builds

- **H2O after and another Complete Story Edition** was tested on Android
  with profile `1.69/472`, Russian translation assets, audio, saving and
  loading.
- **Suika A.S+** reaches the title menu and starts a scene with
  `1.69/472` and `--productname=SuikaENG`. Its MPEG-1/MP2 opening also
  plays on Android through the embedded PL_MPEG decoder.
- **Sakura no Uta (Russian translation)** has a bundled `1.616` preset
  with `--productname=Sakuranouta`, `repair/YasuSakuuta.ttf`, font family
  `YasuSakuuta`, `--font-codepage=1252` and `--font-charset=1`. The preset
  supplies the recommended engine even if the patched EXE reports a
  different build. A manual engine override takes priority.
- **Subarashiki Hibi 15th Anniversary Edition** has the `1.685` runtime
  changes from WenniDev. The VM instructions were tested, and the game
  itself has yet to be verified on Android.

See the [Android build and signing guide](android/README.md).
Compatibility statuses in the upstream section below refer to the
upstream project unless noted above.

## Windows release

The experimental Windows package is `OpenBGI-ASV-0.3-windows-x86.zip`.
Extract it into its own folder and run `OpenBGI.exe` to open the game
picker. Select a game directory and use **Advanced** for its engine
profile and launch arguments. The launcher records games in `games.json`.

When started inside a game folder containing `system.arc` or
`ipl._bp`, OpenBGI starts that game directly. Run `OpenBGI-Launcher.cmd`
or pass `--launcher` to always open the picker.

Itsusora and Eustia have been checked on Windows for launch and Russian
text rendering with the options above. Full playthroughs remain
unverified. This package includes the engine, its licenses and a short
usage guide. Supply game files and translation fonts separately.

## Licensing and credits

- OpenBGI is **GPL-2.0-only**. See [LICENSE](LICENSE). The Android port
  retains upstream copyright notices and source license headers.
- The launcher and game controls use Android framework Views. The Android
  app has no external Gradle runtime dependencies.
- Thanks to [Cytlan and OpenBGI contributors](https://github.com/Cytlan/openbgi),
  and to [WenniDev](https://github.com/WenniDev/openbgi/commit/fd835aac0a793c83494aeaaa5b2a4ef1025dfcaf)
  for the Subarashiki Hibi 15th Anniversary compatibility changes.
- See [third-party notices](android/THIRD_PARTY_NOTICES.md) for dependency licenses and source distribution.
  Game files and translation patches are supplied separately by users.

---

## Upstream OpenBGI

OpenBGI is an open-source reimplementation, in C99, of Ethornell's
"Buriko General Interpreter" (BGI), the visual-novel engine.  It is
reverse-engineered function by function from the `tayutama.exe` build
(ver 1.69, build 444.2, with the "Wave Master" sound library) and
extended to run the scripts of the engine's other builds, from ver 1.58
to ver 1.685 (`docs/versions.md`).  Every function keeps the original's
results for the same inputs, including its result codes and quirks.  The
Windows specifics sit behind an OS layer (`inc/bgi/os.h`) with a Win32
back end, a Linux back end (X11, FreeType, ALSA) and a WebAssembly back
end (a canvas, the game's files over a websocket; `docs/wasm.md`).

* Website: [OpenBGI.net](https://openbgi.net)
* Discord: <https://discord.gg/3zFTJWUNt7>

**OpenBGI is a work in progress.**  Expect bugs and missing pieces;
please report bugs and issues in our
[Discord](https://discord.gg/3zFTJWUNt7).

**OpenBGI is not a Visual Novel development platform.**  If you wish to
develop a VN on the Ethornell/BGI platform, then you must contact Buriko
Co., Ltd.

## Games

The games we have been working on, with the engine build each one needs
(`docs/versions.md` has the details of the builds):

| Game | Product name | Engine version | Status |
|---|---|---|---|
| Nursery Rhyme (trial) | `NurseryRhymeTrial` | 1.58 | Very playable |
| Nursery Rhyme | `NurseryRhyme` | 1.64 | Very playable |
| Itsuka, Todoku, Ano Sora ni. | `ItsukaTodokuAnoSorani` | 1.66 | Mostly playable |
| Tayutama -Kiss on my Deity- (trial; the reference build) | `Tayutama` | 1.69 build 444 | Very playable |
| Tayutama -Kiss on my Deity- | `Tayutama` | 1.69 build 451 | Very playable |
| Tayutama -It's happy days- | `TayutamaHD` | 1.69 build 472 | Very playable |
| Prism Rhythm | `PrismRhythm` | 1.494 | Very playable |
| Diamic Days | `DiamicDays` | 1.529 | Very playable |
| Gakuou -THE ROYAL SEVEN STARS- | `Gackoh` | 1.535 | Very playable |
| Gakuou -It's Heartful Days!!- | `Gackoh_HD` | 1.547 | Very playable |
| Hanairo Heptagram | `HanairoHeptagram` | 1.553 | Very playable |
| Magical Charming! | `MagicalCharming!` | 1.573 | Very playable |
| Sekai to Sekai no Mannaka de | `SekaiToSekaiNoMannakaDe` | 1.588 | Very playable |
| Unmei Senjou no Phi | `UnmeisenjouNoPhi` | 1.599 | Very playable |
| Kodomo no Asobi | `KodomoNoAsobi` | 1.616 | Playable |
| Sen no Hatou, Tsukisome no Kouki (trial) | `SenNoHatouTsukisomeNoKouki` | 1.636 | Testing in progress |
| Tayutama 2 -After Stories- | `Tayutama2AS` | 1.640 | Playable |
| Wakabairo no Quartet | `WakabaironoQuartet` | 1.653 | Playable |
| Nekotsuku, Sakura. | `NekoTsukuSakura` | 1.654 | Somewhat playable, glitchy |
| Madohi Shiroki no Kamikakushi | `MadoiShirokinoKamikakushi` | 1.659 | Boots, but crashes in prolouge |
| Yumahorome | `Yumahorome` | 1.662 | Boots, but crashes in prolouge |
| Arcana Alchemia | `ArcanaAlchemia` | 1.667 | Testing in progress |
| Haruka Ao no Hanayome ni | `HarukaAonoHanayomeni` | 1.669 | Testing in progress |
| Subarashiki Hibi -15th Anniversary Edition- | `Subarashikihibi15th` | 1.685 | Playable |

### Definitions

* Verified - Extensive test against all aspects of the game; Visuals, paths, choices, saving, loading, system settings, and a complete 1:1 verification against executed bytecode. Visuals are indistinguishable from the original release.
* Very playable - No noticeable glitches were encountered during gameplay. Skipping, saving, reloading, sound, cutscenes and choices all work as expected through extended gameplay. Not comprehensively tested, but confidently playable from start to finish.
* Mostly playable - Same as very playable, but more limited gameplay testing.
* Playable - Some visual glitches noticed, or incomplete gameplay testing.
* Somewhat playable - Incomplete gameplay testing. Obvious glitches noticed. May crash.
* Boots - Title screen reached, but will crash in-game. Gameplay testing impossible.
* Testing in progress - Runs basic boot routine, but nothing more is known at this time.

### Testing scope

Apart from the trial of Sen no Hatou, Tsukisome no Kouki (August), whose
build is surveyed but not yet playable, Fortune Arterial (August),
which boots (`--no-disc-check`), and Yoake mae yori Ruri iro na
(August, 1.658.2 on the 1.659 profile), whose prologue plays, only Lump
of Sugar games have been tested so far.  We are interested in testing other BGI-based games: if you have one, tell us how it goes in
our [Discord](https://discord.gg/3zFTJWUNt7).

Testing happens on a case-by-case basis, and to exercise newly implemented opcodes and subsystems. The is currently no extensive testkit again individual games, except booting up a game and actually playing it.

## Building

    make              Linux (gcc or clang; X11, Xext, FreeType, optionally
                      ALSA, fontconfig and libvorbisfile via pkg-config)
    make win          Windows through MinGW (i686-w64-mingw32-gcc)
    make wasm         the browser through Emscripten (emcc) -> bin/wasm/openbgi.html;
                      `node tools/wasm/server.js <game dir>` serves it (docs/wasm.md)
    make tools        the command-line tools: bin/bpasm, the assembler /
                      disassembler of the game's programs (docs/asm.md);
                      bin/bgiscn, the decompiler / compiler of the scenario
                      files (docs/scenario.md); bin/bgidump and bin/bgipack,
                      which turn a game's archives into editable files and
                      back, for translations (docs/dump.md)
    make check        syntax-check every translation unit with -pedantic
    make test         build and run tests/
    make clean

Plain `make` only, no autotools / CMake.  Objects go to `obj/<platform>/`,
binaries to `bin/`.  `VORBIS=vorbisfile|stb|none` selects the Vorbis
decoder, `OPT=...` the optimisation flags; `docs/building.md` has the
details, the optional dependencies and the state of the Windows and
WebAssembly builds.

## Running

    cd <the game's directory>   # ipl._bp, system.arc, sysprg.arc, ...
    /path/to/bin/bgi [arguments as for the original]

or `bin/bgi "<the game's directory>"` from anywhere: the directory given
(or the one holding the archive or program file given) becomes the game
directory, as if the engine were started there.

or just `bin/bgi` from anywhere: started outside a game directory (no
`system.arc` or `ipl._bp` where it runs, no path given) the engine opens
a "Select game" window - the games it has run before, or a directory
to pick, with the game it finds there named; "Advanced" uncovers the
engine profile and extra options, Launch starts it and records the
directory in `games.json` for the next time.

The engine build a game needs is chosen from the game's `ipl._bp` through
`games.json`, which also holds the profiles themselves: the engine writes
the default one next to `bgi` on its first run (a game's own directory
may carry one too) - see `docs/versions.md`.  `--engine=NAME` forces a
profile, `--list-engines` prints them, `--productname=NAME` gives the
product name the game's scripts check for (with `--engine=`, a game that
`games.json` does not know runs without an entry), `--games=PATH` names
another table, `--enable=FAM:OP` / `--disable=FAM:OP` override single
opcodes; these options are removed before the rest of the command line
reaches the original's parser.  The original's own options such as `"Do
not use mutex."` (needed for a second instance in the same directory)
work as before.  A game path with spaces in it may be passed as one
argument (`bin/bgi "$HOME/VN/[August] Fortune Arterial"`), as
may an option's value (`--games="$HOME/VN/my games.json"`).

A game copied straight from its disc carries the disc marker, a 16-byte
file named like the product (`Tayutama`, `HanairoHeptagram`,
`YoakemaeyoriRuriirona`, ...) that holds `0123456789ABCDEF`, and every
boot program quits at once when it finds that file next to the game -
the disc is meant to be installed, not run.  The engine hides the marker
from the scripts (a file named like the product, or one without an
extension holding those 16 characters), so such a copy starts;
`--disc-marker` gives the original's behaviour back.

Some of the older games (Fortune Arterial among them) check their
registration at boot: `comap.dat`, written for the machine by the
game's registration program `reg.exe`, which wants the disc and runs on
Windows only.  OpenBGI does not start that program, so without a
`comap.dat` made for the machine the boot program asks for the disc;
`--no-disc-check` counts the game as registered.

Movies: the frame-sequence movies of the later builds (`docs/bfmovie.md`)
are decoded by the engine itself.  The opening movies in common formats
(`.mpg`, `.wmv`, ...) go through DirectShow on Windows, as in the
original; on Linux, and on Windows when DirectShow cannot render a file,
the engine plays them through the `ffmpeg` and `ffprobe` programs when it
finds them on the PATH or next to `bgi` (no library is linked: the video
comes in through a pipe, the sound through the engine's own mixer).
Without them the movie is skipped (a note on stderr says so) and the
game goes on, as before.

The later builds (1.529 on) open their window at the size their scripts
ask for, which is the game's own resolution scaled down when the
picture would not fit the screen - the picture is scaled into the window
as the original does with the "81 64" instruction.

The engine's own messages (script errors, notices, the installer) are
shown in English; `--lang=ja` or `BGI_LANG=ja` selects the Japanese
originals, which stay in the binary (`tools/messages.txt` holds both, the
text engine's character tables and the player-facing dialog labels are
never translated).  Message boxes raised by the game's own scripts (the
"B0 80 .. 82" instructions and the installer prompts of the `_bp`
programs) carry whatever text the script passes and are marked with a
"(script)" suffix in their caption, so that an untranslated box can be
told from an engine message.  The window title is the game's own
followed by " - OpenBGI".

The same binary looks inside the game's archives: `--list=ARCHIVE`
prints every entry with what it is (a CompressedBG image and its size, a
BW sound and its codec, a program, a compiled scenario, a BF_Movie);
`--extract=ARCHIVE` writes the entries into a directory (`--out=DIR`,
default the archive's name without its extension) as the engine sees
them after the DSC layer, with `--raw` as stored, with `--convert` turned
into common formats (images as .bmp, sounds as .wav - Vorbis only with a
decoder built in -, BF_Movie frames as one .bmp each); `--entry=NAME`
picks one entry.  `bin/bpasm` (`make tools`) disassembles and assembles
the programs (`docs/asm.md`) and decompiles them to the C-like source
they were written in and compiles that back (`docs/bpc.md`; an edited
source may name the engine's memory through declared variables -
`global int gAbort @ 1124;` - and records - `stSprite[i].x` -, keep its
texts as `#define`d constants and share all of it through `#include`);
`bin/bgiscn` does the same for the scenario files of every build (the
1.494+ containers, the headerless files of 1.66 / 1.69, the 16-bit
scenes of 1.64) and repacks the archive (`docs/scenario.md`).

For what the original cannot do - a message longer than its window,
UTF-8 text in every generation, languages written from right to left -
OpenBGI has instructions of its own, the "EF xx" family, which a game's
programs call when they are rebuilt for it (`docs/obgi.md`).

To translate a game, `bin/bgidump GAMEDIR DUMPDIR` dumps every archive at
once - the programs and scenario files decompiled, the pictures as PNG,
the sounds as Ogg Vorbis, the tables as JSON - and `bin/bgipack DUMPDIR
OUTDIR` builds the archives again from the dump, compiling and encoding
what was edited and copying the rest from the originals unchanged
(`docs/dump.md`).

`--debug` opens the debugger with the game (`--debug=pause` holds the
scripts until F5); F12 in the game's window opens and closes it at any
time.  It shows the threads with their stacks and listings, the loaded
programs, the bitmap slots, the display objects, the sound channels, the
memory areas and a log, and pauses, steps and breaks the VM
(`docs/debugger.md`).  Not in the WebAssembly build.

The Windows build needs no Japanese system locale and no locale
emulator: it calls the wide-character API of Windows and converts the
games' Shift-JIS file names, titles, messages and font names itself, and
a game may sit in a directory whose name Shift-JIS cannot spell
(`docs/os_layer.md`, "Strings").

The Linux build keeps its settings (the original's registry values) in
`~/.config/bgi/`.  Fonts: `BGI_FONT_GOTHIC`, `BGI_FONT_MINCHO` or
`BGI_FONT` name a font file to use instead of what fontconfig / the font
directories offer for "ＭＳ ゴシック" / "ＭＳ 明朝".

## Layout

    inc/bgi/        headers, one per module (gfx/ for graphics, snd/ for
                    sound, sysobj/ for the script-visible system objects)
    src/core/       archives, decoders (DSC, CBG, SDC, BF_Movie), files and
                    paths, save files, strings, the loader queue, JSON;
                    arcread.c, the tools' archive reader, and what only
                    the tools use: the DSC, CBG and BF_Movie encoders, PNG
                    and zlib streams
    src/asm/        the assembler / disassembler of the programs (bin/bpasm); the
                    preprocessor of the source languages (#include, #define)
    src/bpc/        the decompiler / compiler of the programs (bin/bpasm -D / -C)
    src/scn/        the scenario files: container, command table scan,
                    decompiler and compiler (bin/bgiscn)
    src/dump/       dumping a game's archives into editable files and
                    packing them again (bin/bgidump, bin/bgipack)
    src/vm/         the script machine: threads, the scheduler, the opcode
                    families (ops_base, ops_compact, ops_sys, ops_sys81,
                    ops_gfx0 .. 2, ops_snd, ops_ext0, ops_ext1), the tracing aids
    src/wait/       wait objects (text output, tweens, menus, loads, ...)
    src/sys/        the engine object, main window, display and presentation,
                    input, cursor, panels, child windows, text entry,
                    dialogs, CPU identification, the game chooser
                    (launcher.c); install/ the installer
    src/sysobj/     the system objects of the "80 8x .. 80 Dx" instructions:
                    names, flags, the global database, message history,
                    rings, locks, dicts, string tables, hit targets, events
    src/gfx/        bitmaps and blitters (blit*.c), the bitmap manager, fonts,
                    display objects (sprite, map, filter, knob, effector),
                    background/ (the twelve background types), mgr/ (the
                    graphics manager), window/ (the message window), text/
                    (the text engine and layout), particle/ and rain, movies
    src/snd/        the sound manager, wm/ (the Wave Master library), bw/
                    (the BW container and its codecs)
    src/dbg/        the debugger (--debug / F12), views/ its panes
    src/os/         the OS layer: os_common.c, posix/, win32/, wasm/
    tools/          messages.txt -> inc/bgi/msg.h (every Shift-JIS string of
                    the binary, with English translations); win32stub/ and
                    wasmstub/ (the syntax-check stand-ins); wasm/ (the asset
                    server, the page, the browser-less simulator)
    tests/          unit tests (make test)
    docs/           Markdown documentation of the subsystems

`CONVENTIONS.md` has the naming, formatting and commenting rules.  Of the
docs, `building.md` covers the builds and their
optional dependencies, `vm.md` the VM, `versions.md` the engine builds
and the profiles, `bfmovie.md` the frame-sequence movies of the later
builds, `sound.md` the Wave Master library and the BW container,
`os_layer.md` the OS interface, `wasm.md` the browser build, `asm.md` the
assembler / disassembler and its listing format, `scenario.md` the
scenario files, their source language and the translation workflow,
`dump.md` the dump and pack tools and the files of a dump,
`debugger.md` the debugger, `panel_system.md`, `particle_rain.md` and
`child_edit_dialogs.md` the subsystems they name.

Development switches (environment): `BGI_MSGBOX_STDERR=1` (message boxes
print and take their default button), `BGI_TRACE`, `BGI_TRACE_FROM`,
`BGI_TRACE_LAST`, `BGI_TRACE_OPS` (instruction traces, see
`src/vm/trace.c`), `BGI_OPSTAT=<file>` (the executed opcodes),
`BGI_INPUT_DEBUG=1`, `BGI_SND_DEBUG=1` (music opens, loop restarts),
`BGI_PANEL_DEBUG=1` (the buttons of every panel at its start and the
events it queues), `BGI_DEBUG_OVERLAY=1` (the debugger over the game's
picture instead of in a window of its own).

## Special Thanks and Honourable Mentions

### Special Thanks

* Darios Sawm / Buriko Co., Ltd.
* [Anonymous]
* minirop
* Galladite
* txt231

### Honourable Mentions

* The BGI tools of the community:
  * [GARbro](https://github.com/morkt/GARbro) (morkt)
  * [arc_unpacker](https://github.com/vn-tools/arc_unpacker) (vn-tools)
  * [EthornellTools](https://github.com/arcusmaximus/EthornellTools)
    (arcusmaximus)
  * [VNTranslationTools](https://github.com/arcusmaximus/VNTranslationTools)
    (arcusmaximus)
  * [Bgi_tools](https://github.com/mchubby/Bgi_tools) (mchubby)
  * [engine_bgi](https://github.com/mnakamura1337/engine_bgi)
    (mnakamura1337)
  * [ethornell-tools](https://github.com/Jair4x/ethornell-tools) (Jair4x)
  * [bgi-extractor](https://github.com/mirinnano/bgi-extractor)
    (mirinnano)
  * [BGIextractor](https://github.com/myzert/BGIextractor) (myzert)
* [BGI](https://github.com/txt231/BGI) by txt231
* [ethornell-rs](https://github.com/xmoezzz/ethornell-rs) (xmoezzz)
* Operation Bellflower - [Adventures in the Ethornell
  Engine](https://www.operationbellflower.com/blog/adventures-in-the-ethornell-engine/part-1)

## License

OpenBGI is licensed under the GNU General Public License, version 2
(GPL-2.0); see `LICENSE`.
