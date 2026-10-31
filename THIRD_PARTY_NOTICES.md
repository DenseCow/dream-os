# Third-Party Notices

Dream OS is licensed under the [GNU General Public License v3.0](LICENSE). It includes or links the open-source
software below, each under its own license. Thank you to everyone who builds and maintains these projects.

## Fire Stick App

| Component | Version | License | Use |
| :-- | :-- | :-- | :-- |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer, HLS, DASH, UI) | 1.4.1 | Apache-2.0 | Dream Player's engine |
| [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) | 0.26.5 | GPL-3.0 | Trailer streams |
| [dadb](https://github.com/mobile-dev-inc/dadb) | 1.2.10 | Apache-2.0 | Talks to the Fire TV's own ADB for Unlock Features |
| [Chaquopy](https://github.com/chaquo/chaquopy) | Gradle plugin | MIT | Embedded Python for scraper modules |
| [CPython](https://www.python.org/) (bundled by Chaquopy) | 3.11 | PSF License 2.0 | Runs scraper modules |
| [Requests](https://github.com/psf/requests) | 2.32.3 | Apache-2.0 | HTTP for scraper modules |
| [urllib3](https://github.com/urllib3/urllib3) | (with Requests) | MIT | |
| [idna](https://github.com/kjd/idna) | (with Requests) | BSD-3-Clause | |
| [charset-normalizer](https://github.com/jawah/charset_normalizer) | (with Requests) | MIT | |
| [certifi](https://github.com/certifi/python-certifi) | (with Requests) | MPL-2.0 | |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | 1.9 | Apache-2.0 | |
| [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) | 2.1.5 | GPL-2.0 with Classpath Exception | Java library support on older Android |
| [JSON-java](https://github.com/stleary/JSON-java) (`tools/debrid-tests/lib/json.jar`, tests only) | 2024 | Public Domain | Offline tests |

## iPhone App

| Component | Version | License | Use |
| :-- | :-- | :-- | :-- |
| [MPVKit](https://github.com/mpvkit/MPVKit) | 1.1.0-n9.0.2 | LGPL-3.0 | Dream Player's engine (packages the libraries below) |
| [mpv](https://github.com/mpv-player/mpv) (via MPVKit, LGPL build) | | LGPL-2.1-or-later | Playback |
| [FFmpeg](https://ffmpeg.org/) (via MPVKit, LGPL build) | | LGPL-2.1-or-later | Decoding |
| Other MPVKit libraries (such as libass, libplacebo, FreeType, HarfBuzz, dav1d) | | Their own permissive or LGPL licenses | See MPVKit |
| [YouTubeKit](https://github.com/alexeichhorn/YouTubeKit) | 0.4.9 | MIT | Trailer streams |
| [Python-Apple-support](https://github.com/beeware/Python-Apple-support) | 3.13-b18 | MIT | CPython built for iOS |
| [CPython](https://www.python.org/) and its standard library (`ios/app/Vendor/Python/`) | 3.13.18 | PSF License 2.0 (`Stdlib/python/lib/python3.13/LICENSE.txt`) | Runs scraper modules |
| Libraries inside CPython for iOS (OpenSSL, libffi, XZ, bzip2, mpdecimal, SQLite) | | Apache-2.0, MIT, 0BSD, bzip2, BSD-2-Clause, Public Domain | |
| [Requests](https://github.com/psf/requests) | 2.32.3 | Apache-2.0 | HTTP for scraper modules |
| [urllib3](https://github.com/urllib3/urllib3) | 2.2.3 | MIT | |
| [idna](https://github.com/kjd/idna) | 3.10 | BSD-3-Clause | |
| [charset-normalizer](https://github.com/jawah/charset_normalizer) | 3.4.0 | MIT | |
| [certifi](https://github.com/certifi/python-certifi) | 2024.8.30 | MPL-2.0 | |

## Windows App

What `DreamOS-Windows-Setup.exe` installs, besides Dream's own code. Versions are the ones pinned in
`windows/tools/fetch-*.sh` and the Gradle build files.

| Component | Version | License | Use |
| :-- | :-- | :-- | :-- |
| [libmpv](https://github.com/mpv-player/mpv) (`libmpv-2.dll`), built by [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake) | build 20261021 (mpv git e471496c56) | GPL (mpv and FFmpeg in their GPL builds) | Dream Player's engine |
| [FFmpeg](https://ffmpeg.org/), [libplacebo](https://code.videolan.org/videolan/libplacebo) and the other libraries inside that build | (with libmpv) | GPL, LGPL and permissive licenses, each its own | Decoding and video output |
| [CPython](https://www.python.org/), Windows embeddable build | 3.13.17 | PSF License 2.0 (`LICENSE.txt` in the installed `python` folder) | Runs scraper modules |
| Libraries inside that CPython (OpenSSL, libffi, SQLite, bzip2, XZ, Expat, mpdecimal, zlib) | | Apache-2.0, MIT, Public Domain, bzip2, 0BSD, MIT, BSD-2-Clause, zlib | |
| Microsoft Visual C++ runtime (`vcruntime140.dll`, shipped with CPython) | | Microsoft's redistribution terms | |
| [Requests](https://github.com/psf/requests) | 2.34.2 | Apache-2.0 | HTTP for scraper modules |
| [urllib3](https://github.com/urllib3/urllib3) | 2.8.0 | MIT | |
| [idna](https://github.com/kjd/idna) | 3.20 | BSD-3-Clause | |
| [charset-normalizer](https://github.com/jawah/charset_normalizer) | 3.5.2 | MIT | |
| [certifi](https://github.com/certifi/python-certifi) | 2026.10.23 | MPL-2.0 | |
| Java runtime: [Microsoft Build of OpenJDK](https://www.microsoft.com/openjdk) 17, trimmed with jlink | 17.0.20 | GPL-2.0 with Classpath Exception (notices in the installed `runtime\legal` folder) | Runs the app |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) for desktop (and the AndroidX libraries it uses: lifecycle, collection, annotation, arch core) | 1.7.3 | Apache-2.0 | The interface |
| [Skiko](https://github.com/JetBrains/skiko) with [Skia](https://skia.org/) | 0.8.18 | Apache-2.0 (Skia: BSD-3-Clause) | Drawing |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | 2.0.21 | Apache-2.0 | |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), [atomicfu](https://github.com/Kotlin/kotlinx-atomicfu) | 1.9.0, 1.7.3, 0.23.2 | Apache-2.0 | |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) | 23.0.0 | Apache-2.0 | |
| [JNA](https://github.com/java-native-access/jna) and JNA Platform | 5.15.0 | Apache-2.0 or LGPL-2.1-or-later (your choice) | Loads libmpv; Windows' data protection for saved sign-ins |
| [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) | 0.26.5 | GPL-3.0-or-later | Trailer streams |
| [nanojson](https://github.com/TeamNewPipe/nanojson) (NewPipe's fork) | | MIT or Apache-2.0 | Used by NewPipe Extractor |
| [jsoup](https://github.com/jhy/jsoup) | 1.22.2 | MIT | Used by NewPipe Extractor |
| [Rhino](https://github.com/mozilla/rhino) | 1.8.1 | MPL-2.0 | Used by NewPipe Extractor |
| [Protocol Buffers Lite](https://github.com/protocolbuffers/protobuf) | 4.35.1 | BSD-3-Clause | Used by NewPipe Extractor |
| [JSR 305 annotations](https://github.com/findbugsproject/findbugs) | 3.0.2 | Apache-2.0 | Used by NewPipe Extractor |
| [QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) (`windows/app/src/main/java/io/nayuki/qrcodegen/`) | | MIT | QR codes for sign-in pages |
| [Inno Setup](https://jrsoftware.org/isinfo.php) | 6.7 | Inno Setup License | The setup and uninstaller programs |

## Notes

- **LGPL libraries (iPhone).** mpv and FFmpeg are used in their LGPL builds through MPVKit. Their source code is
  available from the projects linked above, and Dream's own source code (which links them) is in this
  repository under GPL-3.0, so you can rebuild Dream with modified versions of them.
- **GPL libmpv (Windows).** The Windows app ships shinchiro's GPL build of libmpv unmodified, as downloaded by
  `windows/tools/fetch-mpv.sh` (the exact file and its SHA-256 are pinned there). The build recipes are in
  [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake), and the source of mpv, FFmpeg
  and each library it builds is linked from there. Dream OS itself is GPL-3.0.
- **Java runtime (Windows).** The installed `runtime` folder is a trimmed copy of the Microsoft Build of OpenJDK 17,
  unmodified. Its license and the notices of the libraries inside it are in `runtime\legal`.
- **Scraper compatibility layer.** The small `xbmc*.py` modules in `firestick/app/src/main/python/`,
  `ios/app/Sources/DreamScrapers/Python/` and `windows/scrapers/src/main/resources/dreamos/python/` are Dream's own
  code (GPL-3.0). They provide the module names that
  community scraper modules expect, so those modules run unchanged.
- **Data services.** Dream uses the APIs of TMDB, OMDb, Trakt, TVmaze, TheIntroDB, AniSkip, ARM, Kitsu,
  OpenSubtitles and others under their own terms. See [Disclaimers](DISCLAIMER.md).
- The full license texts are in each project's repository; the CPython license is included in this repository.
