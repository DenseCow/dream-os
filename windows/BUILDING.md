# Building Dream OS for Windows

The Windows app is Kotlin with Compose Multiplatform for desktop. The repository's main
[Building guide](../BUILDING.md#windows-app) covers it in full; this page is the short version for this folder.

## Modules

| Folder | What's Inside |
| :-- | :-- |
| `core/` | Dream Core: TMDB, rows, profiles, settings, debrid accounts, the source resolver, Trakt, the update check |
| `scrapers/` | Runs the viewer's scraper repositories in the bundled Python (`src/main/resources/dreamos/python/`) |
| `player/` | Dream Player: libmpv through JNA, and the player's controls |
| `app/` | The window, every page, setup, settings and the startup archive step |
| `installer/` | `DreamOS.iss` (the Inno Setup wizard) and its images |
| `tools/` | `fetch-third-party.sh`, `build-installer.sh`, the icon and wizard art scripts |

## Build

On Windows 10 or 11 (64-bit), in Git Bash, with a JDK 17 installed (Gradle's toolchain finds it) and
[Inno Setup](https://jrsoftware.org/isinfo.php) 6.7 or newer:

```sh
tools/fetch-third-party.sh          # libmpv and Python into third_party/, each checked against a pinned SHA-256
./gradlew :app:run                  # run from source
./gradlew test                      # tests (add -Pdream.live=true for the live network tests)
tools/build-installer.sh            # app/build/release/DreamOS-Windows-Setup.exe and its .sha256
```

`build-installer.sh` builds the app image, runs its SelfCheck with no window, then compiles the setup.
`--dev` marks the build DEV in About.

## Keys

No key is compiled in. A run from source (`./gradlew :app:run`) reads `tmdb.key` from a `local.properties` in this
folder and adds it once, as if you had typed it in Settings. The packaged app asks each viewer for their
own key during setup. Never commit `local.properties`; it is git-ignored.
