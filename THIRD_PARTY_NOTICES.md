# Third-Party Notices

Dream OS is licensed under the [GNU General Public License v3.0](LICENSE). It includes or links the open-source
software below, each under its own license. Thank you to everyone who builds and maintains these projects.

## Fire TV App

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

## Notes

- **LGPL libraries (iPhone).** mpv and FFmpeg are used in their LGPL builds through MPVKit. Their source code is
  available from the projects linked above, and Dream's own source code (which links them) is in this
  repository under GPL-3.0, so you can rebuild Dream with modified versions of them.
- **Scraper compatibility layer.** The small `xbmc*.py` modules in `firestick/app/src/main/python/` and
  `ios/app/Sources/DreamScrapers/Python/` are Dream's own code (GPL-3.0). They provide the module names that
  community scraper modules expect, so those modules run unchanged.
- **Data services.** Dream uses the APIs of TMDB, OMDb, Trakt, TVmaze, TheIntroDB, AniSkip, ARM, Kitsu,
  OpenSubtitles and others under their own terms. See [Disclaimers](DISCLAIMER.md).
- The full license texts are in each project's repository; the CPython license is included in this repository.
