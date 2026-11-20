# Building

Plain `make` only - no autotools, no CMake.  Objects go to
`obj/<platform>/`, binaries to `bin/`.  The Makefile's header comment is
the short form of this file.

## Prerequisites on Linux

* a C99 compiler - gcc (the default) or clang (`make CC=clang`) - and GNU
  make;
* `pkg-config`, the X11 and Xext development files, FreeType
  (`freetype2`), pthreads;
* `python3` for the generated message table (`inc/bgi/msg.h` is produced
  from `tools/messages.txt` by `tools/mkmsg.py` when either is newer) and
  for the Win32 stub check.

Optional, detected with `pkg-config` at build time:

* **ALSA** (`alsa`): sound output.  Without it the sound library runs
  against a silent clock - the mixer is still pulled at 20 ms intervals so
  that fades, loop counters and the "is it still playing" answers keep
  their timing, but nothing is heard.
* **fontconfig** (`fontconfig`): resolves the GDI face names the engine
  asks for ("ＭＳ ゴシック" = MS Gothic, "ＭＳ 明朝" = MS Mincho) to font
  files.  Without it the usual font directories are searched for
  well-known Japanese fonts.  Either way `BGI_FONT_GOTHIC`,
  `BGI_FONT_MINCHO` or `BGI_FONT` in the environment name a font file to
  use instead.
* **libvorbisfile** (`vorbisfile`): the Vorbis codec of the BW sound
  container (codec 3, which the games use for their music and many of
  their effects).  `VORBIS=auto` (the default) takes libvorbisfile when
  `pkg-config` finds it, else `src/snd/stb_vorbis.c` when that single-file
  decoder (public domain, github.com/nothings/stb) has been dropped into
  `src/snd/`, else none.  `VORBIS=vorbisfile`, `VORBIS=stb` and
  `VORBIS=none` force one.  Without a decoder codec-3 sounds play as
  silence of the right length, so that games whose every sound is Vorbis
  still run.  `docs/sound.md` describes the container.

Optional at run time, not linked:

* **ffmpeg and ffprobe**: the opening movies in common formats (`.mpg`,
  `.wmv`, ...), which the Windows original hands to DirectShow.  The
  engine starts the two programs as child processes when a movie is to be
  played - found on the PATH or next to `bgi` / `bgi.exe` - and reads the
  decoded frames and sound through pipes (`src/sys/moviefx.c`,
  `docs/os_layer.md`).  Without them the movie is skipped, as before.  On
  Windows DirectShow is tried first and the programs take over only when
  it cannot render the file.  `make test` generates a short clip with
  `ffmpeg` for `bin/test_moviefx` and skips that part when the program is
  missing.

`OPT=...` replaces the optimisation flags (`-O2 -g` by default).

## Android native optimisation

Android APKs use the CMake/Gradle build described in
[android/README.md](../android/README.md). The native Ninja build defaults
to `RelWithDebInfo`, and `android/build-apk.ps1` selects that build type
explicitly. This enables `-O2` while retaining native debug information.
An empty CMake build type produces unoptimised code and can noticeably
slow the software MPEG decoder. The Gradle APK variant and the CMake
native build type are separate settings.

PL_MPEG is vendored at a pinned revision in `src/third_party/pl_mpeg.h`.
Android MPEG-1/MP2 playback therefore needs no additional codec download.
MP4/H.264/AAC uses Android MediaPlayer. These Android paths do not use the
optional desktop `ffmpeg`/`ffprobe` helpers described above. See
[third-party notices](../android/THIRD_PARTY_NOTICES.md) for source and
license information.


## Targets

`--auto-font` / `--no-auto-font` control launch-time font discovery, enabled
by default on Windows and Android. `--auto-font=1` / `--auto-font=0` are
aliases. The game folder is searched first, then subfolders in breadth-first
order, with names sorted within each directory. Supported SFNT formats
are TTF, OTF and TTC (first face). A configured font file or family skips
discovery. The Android host also skips it when UIF has loaded a font.
`OBAConfig.json` supports `"auto_font": false`; explicit launch options
win over this field. The selected automatic font is reported in the log.


`--patch-dir=DIR` searches that directory before loose game resources and
archive entries. A relative directory is resolved against the selected
game folder. Reads, decoded reads, size checks, existence checks and movie
paths use the replacement. Saves are unaffected. Resource subdirectories
are preserved. Absolute resource names and parent traversal are excluded.
An empty value disables this override.

`--char-remap=003E:00D3,003C:00D5` replaces Unicode glyphs while drawing
and measuring text. Pairs use hexadecimal scalar values without `U+`, with
at most 64 unique sources. Replacements are simultaneous, so they do not
cascade. Invalid tables are reported and leave the previous table intact.
`--char-remap=` clears the table. Apply these settings at launch, before
fonts and glyph caches are created. The script encoding and string
operations remain unchanged. The README includes a complete H2O example.


Windows font selection defaults to `SHIFTJIS_CHARSET` (128), including
with a family override. Use `--font-charset=N` to set the GDI charset
explicitly (0..255). Common values are 0 (ANSI), 1 (DEFAULT), 128
(SHIFTJIS) and 204 (RUSSIAN). The selected charset applies to both normal
and monochrome fonts. Windows font availability determines the result.
This setting controls GDI font selection. `--font-codepage` separately
controls conversion of single-byte glyph bytes into Unicode.

For Sakura no Uta's custom font, use
`--font-file=repair\YasuSakuuta.ttf --font-face=YasuSakuuta --font-charset=1 --font-codepage=1252`.

The Windows game picker opens automatically when the current directory
contains neither `system.arc` nor `ipl._bp` and no boot path is supplied.
`--launcher` opens it explicitly, including from a game directory.

For translations that patch the original executable's font creation,
`OPENBGI_FONT_FACE` overrides the rasterised font face and
`OPENBGI_FONT_SIZE` its pixel height (4..200). The script's layout size
and line spacing still apply. On Windows, `OPENBGI_FONT_WEIGHT` overrides
the GDI weight (1..1000, with 400 for regular). Set these environment
variables before launching OpenBGI. Font height is scaled for the engine's
antialiasing internally.
`OPENBGI_FONT_WIDTH` overrides the rasterised average character width in
pixels (0..200). Zero asks the platform for the font's natural proportions.

The equivalent launch options are `--font-face="Segoe UI"`,
`--font-size=24`, `--font-width=0` and `--font-weight=400`.
They take precedence over the corresponding environment variables.
`--font-file=FILE.ttf` loads a translation font on Windows or Android
before the game creates its fonts. On Windows, use its internal family
name with `--font-face`; this can differ from the filename.
On Android, paths are relative to the selected game folder and the loaded
file is selected directly, taking priority over UIF and family overrides.

Windows also resolves relative font paths against the selected game
directory, including when the launcher starts elsewhere. For a font in
a subfolder, use `--font-file=repair\YasuSakuuta.ttf`. Paths with spaces
can be quoted, as in `--font-file="repair\My Font.ttf"`. Absolute
Windows paths are also accepted.

Some translation fonts assign punctuation to Latin-1 or other single-byte
character positions. On Windows, `--font-codepage=1252` changes the
conversion of single-byte glyphs for drawing and font measurement.
For example, Sakura no Uta's YasuSakuuta font uses byte D1 as a comma,
A5 as a full stop and C9 as a dash. This option affects glyph conversion;
script parsing, double-byte CP932 characters and UTF-8 text keep their
existing encoding. The code page must be installed on Windows.
Font weight overrides are supported on Windows and Android. With width
zero, horizontal Cyrillic and ASCII use the font's natural advances.

For example, a Windows launch script for a translation can use:

```bat
start "" "OpenBGI.exe" --engine=1.66 --font-face="Segoe UI" --font-size=24 --font-width=0 --font-weight=400 --letter-spacing=2
```

Horizontal letter spacing can be adjusted with `--letter-spacing=N`
or a `"letter_spacing": N` member in `OBAConfig.json` in the game
directory. Values are integer pixels from -16 to 64, added to the advance
of each character, including spaces. Zero is the default, positive values
spread text out, and negative values tighten it. The command-line option
takes precedence over the file. The resulting advance is at least one
pixel when an adjustment is active. Measurement and word wrapping use the
same adjustment. The setting applies to horizontal text, including the
bidirectional layout, and leaves vertical text and ruby readings alone.

| target | what it builds or does |
|--------|------------------------|
| `make` | the Linux engine, `bin/bgi` |
| `make test` | builds and runs the unit tests of `tests/` (`bin/test_*`): the assembler, the BF_Movie decoder, the blitters, the archive reader and the DSC / CBG decoders, the extraction options, the games table, the ffmpeg movie player, the sound codecs and library, the text engine, the VM's base instructions, the WebAssembly file layer against a mock server and the Win32 string conversions |
| `make check` | syntax-checks every translation unit of the Linux build with `-pedantic` |
| `make check-win` | syntax-checks the Win32 back end against the stub SDK headers (below; no MinGW needed) |
| `make check-wasm` | syntax-checks the WebAssembly back end against a stub Emscripten header (below; no emcc needed) |
| `make win` | the Windows engine through MinGW, `bin/bgi.exe` |
| `make wasm` | the browser build through Emscripten, `bin/wasm/openbgi.html` with its `.js` and `.wasm` |
| `make wasm-sim` | the WebAssembly back end as a native Linux binary, `bin/openbgi-wasmsim`, with no browser involved |
| `make wasm-test` | the asset server's protocol test under node |
| `make tools` | the command-line tools: `bin/bpasm`, the assembler / disassembler (`docs/asm.md`) and decompiler / compiler (`docs/bpc.md`) of the game's programs; `bin/bgiscn`, the decompiler / compiler of the scenario files (`docs/scenario.md`); `bin/bgidump` and `bin/bgipack`, which dump a game's archives into editable files and pack them again (`docs/dump.md`).  `make PLATFORM=win32 tools` builds them as `.exe` through MinGW |
| `make msg` | regenerates `inc/bgi/msg.h` from `tools/messages.txt` |
| `make clean` | removes `obj/` and `bin/` |

The engine is run from inside a game's directory (`cd <game>;
/path/to/bin/bgi`); the README has the command line.

## The Windows build

`make win` builds with `i686-w64-mingw32-gcc` (`CROSS=` selects another
prefix, e.g. `make win CROSS=x86_64-w64-mingw32-`) and links gdi32,
user32, winmm, imm32, comctl32, ole32, oleaut32, shell32, advapi32,
comdlg32, uuid and strmiids.  The optional libraries are looked up with
the toolchain's own `$(CROSS)pkg-config`, never the host's: without a
libvorbisfile built for MinGW the Vorbis decoder is `src/snd/stb_vorbis.c`
when that file is there, else none.  The objects go to `obj/win32/`;
objects left there by another compiler have to be removed first (`rm -rf
obj/win32`), since make does not notice the change.  The Win32 back end
(`src/os/win32/`) is the original's windowing, dialog, GDI, waveOut, MCI
and DirectShow code with the engine's decisions taken out
(`docs/os_layer.md`).

The back end calls the wide-character (W) entry points of Windows only
and converts the engine's Shift-JIS strings itself (`docs/os_layer.md`),
so the binary does not depend on the system's code page; it runs on the
NT line of Windows only (Windows XP and later), not on Windows 9x.

A machine without MinGW can still check the back end: `tools/win32stub/`
is a stand-in `windows.h` (and the other headers the back end includes)
with the structures under their real member names, the constants with
dummy values generated into `autogen.h`, the functions that take or
return strings under their real prototypes and the other API functions
left implicitly declared; `make check-win` runs `python3
tools/win32stub/gen.py`, which compiles every Win32 file (and
`src/sys/main.c`) with `gcc -fsyntax-only -fshort-wchar` against it,
collects the identifiers GCC reports as undeclared into `autogen.h` and
repeats until nothing new turns up.  This catches syntax errors,
misspelt identifiers, wrong structure members and a narrow string given
to a wide-character call or the reverse; it does not check the arguments
of the functions it leaves undeclared, which is what a real `make win`
is for.  `make test` also builds `bin/test_win32wide`, the back end's
string conversions against the same stub (`tests/win32wide.c`).

Resources: the original carries an icon (id 0x65), a cursor (0x6A) and
seven dialog templates in its resource section.  The dialogs are rebuilt
in memory from the layouts of those resources
(`src/os/win32/dlgtemplate.c`); the icon and cursor are loaded with
LoadIcon / LoadCursor and are simply absent when no resource script
provides them - a resource script with `ICON` / `CURSOR` entries under
ids 0x65 / 0x6A brings them back.

## The WebAssembly build

`make wasm` needs `emcc` on the PATH (from emsdk) and produces the page,
which `node tools/wasm/server.js <game directory>` serves together with
the game's files over a websocket; `docs/wasm.md` explains the design,
the server and the page's parameters.  The Vorbis codec needs
`src/snd/stb_vorbis.c` dropped in on this platform (there are no host
libraries); without it those sounds are silent.

This build has not been run against a real Emscripten yet either.  Its
stand-ins:

* `make check-wasm` compiles `src/os/wasm/*.c` with the host compiler
  against `tools/wasmstub/emscripten/emscripten.h`, which declares the
  `EM_JS` / `EM_ASYNC_JS` functions and drops their JavaScript bodies.
  (The bodies still have to tokenise as C, which rules out
  regular-expression literals and empty `''` strings in them.)
* `make test` includes `bin/test_wasmfs` (`tests/wasmfs.c`): the back
  end's file layer, block cache and overlay on the host against
  `tools/wasm/hostmock.c`, a mock of the server.
* `make wasm-test` runs `tools/wasm/selftest.js`, which starts the server
  on a scratch directory and drives it through the real browser-side
  client (`client.js`) under node 22's WebSocket.
* `make wasm-sim` links the engine with the WebAssembly back end,
  `hostmock.c` and `tools/wasm/fakebrowser.c` (the JavaScript side on a
  host: a canvas written out as a picture, the host's clock, a WAV file
  for the sound, a scripted mouse) into a native binary that runs a game
  directory through the back end's own file layer, overlay and
  presentation.

What none of these covers is the JavaScript in the browser - the canvas
text, the `keyCode` mapping, the Web Audio scheduling, Asyncify's
behaviour - so the first real `make wasm` and page load may also need a
round of small fixes.

## Windows release packaging

After building the Windows executable, run `tools/package-windows.ps1`.
It reads the release version from `android/build.gradle`, strips a copy
of `bin/bgi.exe`, and creates a clean ZIP under `bin/releases/<version>/`.
The package contains the launcher command, Windows instructions, release
notes, GPL license and the Vorbis decoder notice. Personal `games.json`
settings and game files are excluded. Pass `-Binary`, `-Strip`, or
`-Version` to override the defaults.
