# Android third-party notices

| Component | License and source |
| --- | --- |
| OpenBGI engine and Android integration | GPL-2.0-only. See [LICENSE](../LICENSE) and [upstream](https://github.com/Cytlan/openbgi). |
| Subarashiki Hibi 15th Anniversary support | Changes by WenniDev. See the [source commit](https://github.com/WenniDev/openbgi/commit/fd835aac0a793c83494aeaaa5b2a4ef1025dfcaf) and the retained file headers. |
| stb_vorbis | MIT option, copyright Sean Barrett. The pinned [source file](https://github.com/nothings/stb/blob/9600522de53973336fa033a289858a4354cbb04c/stb_vorbis.c) contains the complete license notice. |
| PL_MPEG | MIT, Dominic Szablewski. Pinned [source](https://github.com/phoboslab/pl_mpeg/blob/c871f2be022ece7ef4f64230b4fb8e1fb9eb6023/pl_mpeg.h); vendored in `src/third_party/pl_mpeg.h`. See `assets/licenses/pl_mpeg.txt` in the APK. |
| Universal Injector Framework | Reference for configuration behavior. See the [UIF repository](https://github.com/AtomCrafty/UniversalInjectorFramework). The Android integration implements a subset of the configuration format in Java. |

## Public distribution

The Android launcher and game controls use the platform APIs. The Gradle
app module has no external runtime dependencies. The native engine includes
stb_vorbis and PL_MPEG under their MIT licenses. The license texts are
included in the APK at `assets/licenses/stb_vorbis.txt` and
`assets/licenses/pl_mpeg.txt`. Android MediaPlayer uses the platform codecs;
no FFmpeg binary or library is bundled in the Android APK.

When distributing an APK, make the corresponding source and build scripts
available under the applicable terms and retain the third-party notices.
