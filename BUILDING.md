# Building Dream OS

This page is for developers who want to build Dream OS from source. To just install it, see the
[Fire Stick](INSTALL-FIRESTICK.md), [iPhone](INSTALL-IOS.md) or [Windows](INSTALL-WINDOWS.md) install guide.

- [Repository Layout](#repository-layout)
- [Keys and Where They Go](#keys-and-where-they-go)
- [Fire Stick App](#fire-stick-app)
- [iPhone App](#iphone-app)
- [Windows App](#windows-app)
- [Releases](#releases)

<br>

## Repository Layout

| Folder | What's Inside |
| :-- | :-- |
| `firestick/` | The Fire Stick app: Kotlin, one Gradle module (`app/`), package `com.firehome.launcher` |
| `firestick/app/src/main/python/` | The small Python layer that runs scraper modules (embedded with Chaquopy) |
| `firestick/tools/` | `deploy.sh` (install and set up on a Fire Stick), `release.sh`, offline debrid tests |
| `firestick/docs/STANDARDS.md` | The design and wording rulebook every screen follows |
| `ios/` | The iPhone app: a Swift package (`ios/app/`) built with xtool |
| `ios/app/Vendor/Python/` | CPython 3.13 for iOS and its standard library (third party, unmodified) |
| `ios/tools/` | `build.sh`, `install.sh`, `ipa.sh`, `gen-secrets.sh` and helpers |
| `windows/` | The Windows app: Kotlin and Compose for desktop, Gradle modules `core`, `scrapers`, `player` and `app` |
| `windows/installer/` | The setup wizard (`DreamOS.iss`, Inno Setup) and its images |
| `windows/tools/` | `fetch-third-party.sh` (libmpv and Python), `build-installer.sh`, art scripts |
| `tools/` | Publishing tools for this repository (privacy scan, release) |

Mac and Linux versions will get their own folders here when they arrive.

<br>

## Keys and Where They Go

Dream OS needs no keys to build. Every key is optional and **anything you put in at build time ends up inside
the app**, so leave them empty for builds you share. Without them, Dream asks each viewer for their own
(TMDB in setup, OMDb in Settings) or hides the feature.

| Key | Fire Stick (`firestick/local.properties`) | iPhone (`ios/secrets.properties`) | Without It |
| :-- | :-- | :-- | :-- |
| TMDB API key | `tmdb.key` | `tmdb.key` | Setup asks the viewer for their own |
| OMDb API key | `omdb.key` | `omdb.key` | IMDb and Rotten Tomatoes scores stay hidden until a key is entered |
| Trakt app | `trakt.client_id`, `trakt.client_secret` | same | Trakt sync is hidden (Fire Stick) or uses the viewer's own app keys (iPhone) |
| Premiumize device sign-in | `premiumize.client_id` | not used | Premiumize is linked with the account's API key |
| Update repository | `update.repo` (`owner/name`) | not used | The in-app updater hides itself |

Real-Debrid sign-in uses Real-Debrid's published client ID for open-source apps, so it needs nothing from you.
Never commit `local.properties`, `secrets.properties` or `Secrets.swift`; they are git-ignored.

The Windows app compiles no keys in at all. See [Keys on Windows](#keys-on-windows).

<br>

## Fire Stick App

### What You Need

- **JDK 17** (for example Microsoft Build of OpenJDK 17 or Temurin 17)
- **Gradle 8.7** on your PATH (the project has no Gradle wrapper)
- **Android SDK** with platform 34 and build-tools 34
- **Python 3.11** on the build machine. Chaquopy uses it at build time to install and precompile the Python
  packages scrapers rely on. Point the build at it with `python.build=` in `local.properties`, the `PYTHON311`
  environment variable, or the Windows `py -3.11` launcher.

### Build

```sh
cd firestick
cp local.properties.example local.properties    # then set sdk.dir (and any optional keys)
gradle assembleRelease                         # or assembleDebug
```

The APK lands in `firestick/app/build/outputs/apk/release/app-release.apk`.

### Signing

Release builds look for a signing properties file **outside** the project (with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`): `~/.android/dreamos-release.properties` by default, or the
path in `local.properties` `release.signing` or the `DREAMOS_SIGNING` environment variable. Without one, the
release build is signed with your debug key (with a warning). That's fine for your own testing, but a build
signed with a different key can't update an install of the official release; uninstall first.

### Install on a Fire Stick

```sh
cd firestick
tools/deploy.sh FIRE-TV-IP     # builds, installs over ADB, grants the optional permissions, starts Dream
```

`deploy.sh` finds `adb` and `gradle` on your PATH (or set `ADB` and `GRADLE`). `SKIP_BUILD=1` installs the APK
already built; `NO_START=1` installs quietly. See the
[install guide](INSTALL-FIRESTICK.md#from-a-computer-with-adb) for what each permission does.

### Offline Debrid Tests

`firestick/tools/debrid-tests/run.sh` compiles the app's real debrid code against a local mock server and runs
contract tests for AllDebrid, Premiumize, Real-Debrid and TorBox on your computer. It uses the Kotlin compiler
jars from your Gradle cache (run a normal build first).

### Rules for Changes

Read [`firestick/docs/STANDARDS.md`](firestick/docs/STANDARDS.md) before changing any screen: colors, focus,
layout, wording and performance rules all live there.

<br>

## iPhone App

The iPhone app is a Swift package (`ios/app/Package.swift`, product `DreamOS`, shown as "Dream") built with
[xtool](https://github.com/xtool-org/xtool). No Mac is needed.

### What You Need

- **Linux**, **Windows with WSL** (Ubuntu), or **macOS**
- **Swift 6.x** (installed with [swiftly](https://www.swift.org/install/); `ios/tools/install-swift.sh` does it
  on Linux / WSL)
- **xtool**, set up once with `xtool setup`: it asks for Xcode's `.xip` (download it with your Apple Account from
  [developer.apple.com/download](https://developer.apple.com/download/all/?q=Xcode)) and how to sign in
  (Apple Account password, any account; or an App Store Connect API key, paid accounts)
- To install on a phone: `usbmuxd` and `libimobiledevice-utils` (Linux / WSL), the phone unlocked and trusted,
  and **Developer Mode** turned on (Settings › Privacy & Security)

Swift packages it pulls in: [MPVKit](https://github.com/mpvkit/MPVKit) (the player engine) and
[YouTubeKit](https://github.com/alexeichhorn/YouTubeKit) (trailer streams). CPython 3.13 for iOS is vendored
in `ios/app/Vendor/Python/`.

### Keys

```sh
cd ios
cp secrets.properties.example secrets.properties   # optional; every key may stay empty
tools/gen-secrets.sh                               # writes app/Sources/DreamCore/Secrets.swift (git-ignored)
```

`Secrets.swift` must exist for the app to compile, so run `gen-secrets.sh` once even with no keys.

### Build, Install, or Make an .ipa

```sh
tools/build.sh mybuild              # compile check (copies app/ to ~/dreamos-ios/mybuild and builds there)
tools/build.sh mybuild DreamCore    # compile one module only
tools/install.sh                    # build and install on the connected iPhone
tools/ipa.sh                        # unsigned builds/Dream.ipa for SideStore / AltStore
tools/crash.sh                      # newest Dream crash report from the phone, summarized
```

The scripts copy `app/` into the WSL / Linux home folder and build there (building straight from a Windows
drive is slow). Two notes: MPVKit ships static frameworks that xtool would try to sign, so the scripts remove
them from the built app (`strip-static.sh`); and `xtool dev run` isn't used for the same reason.

### Windows: Reaching the iPhone From WSL

USB passthrough (`usbipd`) is unreliable with iPhones. What works is Windows' own Apple service:

1. Install **Apple Devices** from the Microsoft Store. Its service listens on `127.0.0.1:27015` on Windows.
2. In an administrator PowerShell, forward that port to the WSL network and allow it through the firewall
   (find the gateway address in Ubuntu with `ip route list default`):

   ```powershell
   netsh interface portproxy set v4tov4 listenport=27015 listenaddress=<WSL-GATEWAY-IP> connectport=27015 connectaddress=127.0.0.1
   New-NetFirewallRule -DisplayName "WSL iPhone relay" -Direction Inbound -Protocol TCP -LocalPort 27015 -RemoteAddress 172.16.0.0/12 -Action Allow
   ```

3. In Ubuntu: `export USBMUXD_SOCKET_ADDRESS=<WSL-GATEWAY-IP>:27015`, then `ideviceinfo -k DeviceName` should
   print your phone's name. (`install.sh` and `crash.sh` set this for you from the default route.)

If the gateway address changes after a restart, redo the `portproxy` line. If an install waits forever at
"Waiting for device", unlock the phone.

### Logs

```sh
idevicesyslog | grep --line-buffered 'DreamOS\['
```

<br>

## Windows App

The Windows app is Kotlin with [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) for
desktop, in four Gradle modules: `core` (Dream Core: TMDB, rows, profiles, settings, debrid accounts, the source
resolver, Trakt, updates), `scrapers` (runs scraper repositories in the bundled Python), `player` (Dream Player on
libmpv through JNA) and `app` (the window and every page). The Gradle wrapper (Gradle 8.7) is included.

### What You Need

- **Windows 10 or 11, 64-bit**, with **Git for Windows**: the scripts are Bash and run in Git Bash
- **JDK 17** (for example Microsoft Build of OpenJDK 17). Gradle's toolchain finds it, and its `jlink` and
  `jpackage` make the Java runtime that ships inside the app
- **[Inno Setup](https://jrsoftware.org/isinfo.php) 6.7 or newer**, only to build the setup
  (`winget install --id JRSoftware.InnoSetup -e`, or set `ISCC` to its `ISCC.exe`)
- **Python 3 with Pillow**, only to redraw the icon or the wizard images (`tools/make-icon.py`,
  `tools/make-wizard-images.py`)

### Bundled Programs

```sh
cd windows
tools/fetch-third-party.sh
```

This fills `windows/third_party/` (git-ignored) with exact versions, each checked against a pinned SHA-256 before
it's unpacked:

- `mpv/libmpv-2.dll` from [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake)
  (`tools/fetch-mpv.sh`)
- `python/`: the official Windows "embeddable" CPython (`tools/fetch-python.sh`), with Dream's own `._pth` file
- `python-site/`: Requests and the packages it needs, from PyPI

### Run, Test, Build the Setup

```sh
./gradlew :app:run                  # run from source
./gradlew test                      # tests (add -Pdream.live=true for the live network tests)
tools/build-installer.sh            # the setup, for release (About shows PUBLIC)
tools/build-installer.sh --dev      # the same, marked DEV
```

`build-installer.sh` runs `fetch-third-party.sh`, builds the app image (`./gradlew :app:createDistributable`),
runs the image's SelfCheck with no window (Java runtime, libmpv, Python, TLS, Skia) and stops if a check fails,
then compiles `installer/DreamOS.iss`. The output is `windows/app/build/release/DreamOS-Windows-Setup.exe` and
`DreamOS-Windows-Setup.exe.sha256`, the two files the in-app updater looks for.

**Startup archive.** After the app image is built, Gradle launches it once with no window, no sound and a
throwaway data folder (`app/build/startup-train`) to record the classes a start loads (`app\dream.classlist`), and
makes a Java class archive (`app\dream.jsa`). Both make later starts faster; if a step fails, the app starts
without them. With a TMDB key in `DREAM_TMDB_KEY` or `windows/local.properties`, that launch gets as far as Home's
rows and records more classes. Release builds run it with no key.

### Keys on Windows

No key is compiled into the Windows app. Each viewer adds their own TMDB key, OMDb key, debrid accounts and Trakt
app in setup or Settings. For your own runs from source, `windows/local.properties` (git-ignored) can hold
`tmdb.key` and `premiumize.client_id`: they're read at run time from the working folder or the folder above it,
and the TMDB key is added once as if you'd typed it in Settings. The updater always checks this repository
(`UpdateCheck.REPO` in `core`); change it in a fork.

### Logs

`%APPDATA%\Dream OS\logs\dream.log`. Tests write theirs under `build/` instead.

<br>

## Releases

All platforms publish to this repository's [Releases](https://github.com/DenseCow/dream-os/releases). Every
release must include the Fire Stick app as **`DreamOS.apk`** (plus `DreamOS.apk.sha256`), because the Downloader
code and the in-app updater always fetch the newest release's `DreamOS.apk`. The iPhone build is attached as
`Dream.ipa` when there is one. From 4.3.6 on, releases also carry **`DreamOS-Windows-Setup.exe`** (plus
`DreamOS-Windows-Setup.exe.sha256`), which the Windows updater and the README's download link fetch.

Maintainers use `tools/release.sh` in the repository root (`IPA=1` adds the iPhone build, `WIN=1` the Windows
setup). It exports the committed code of each app, builds every file from that exported copy with no keys inside,
runs the privacy scan on the code and on each built file, and publishes the release. For your own fork, set
`update.repo` to your repository (or leave it empty) and sign with your own key.
