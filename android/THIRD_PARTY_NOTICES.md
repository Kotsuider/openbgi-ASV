# Android third-party notices

| Component | License and source |
| --- | --- |
| OpenBGI engine and Android integration | GPL-2.0-only. See [LICENSE](../LICENSE) and [upstream](https://github.com/Cytlan/openbgi). |
| Subarashiki Hibi 15th Anniversary support | Changes by WenniDev. See the [source commit](https://github.com/WenniDev/openbgi/commit/fd835aac0a793c83494aeaaa5b2a4ef1025dfcaf) and the retained file headers. |
| stb_vorbis | MIT option, copyright Sean Barrett. The pinned [source file](https://github.com/nothings/stb/blob/9600522de53973336fa033a289858a4354cbb04c/stb_vorbis.c) contains the complete license notice. |
| Universal Injector Framework | Reference for configuration behavior. See the [UIF repository](https://github.com/AtomCrafty/UniversalInjectorFramework). The Android integration implements a subset of the configuration format in Java. |

## Public distribution

The Android launcher and game controls use the platform APIs. The Gradle
app module has no external runtime dependencies. The native engine includes
stb_vorbis under the MIT license option listed above. Its license text is
included in the APK at `assets/licenses/stb_vorbis.txt`.

When distributing an APK, make the corresponding source and build scripts
available under the applicable terms and retain the third-party notices.
