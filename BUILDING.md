# Building Dream OS

This page is for developers who want to build Dream OS from source. To just install it, see the
[Fire TV](INSTALL-FIRESTICK.md) or [iPhone](INSTALL-IOS.md) install guide.

- [Repository Layout](#repository-layout)
- [Keys and Where They Go](#keys-and-where-they-go)
- [Fire TV App](#fire-tv-app)
- [iPhone App](#iphone-app)
- [Releases](#releases)

<br>

## Repository Layout

| Folder | What's Inside |
| :-- | :-- |
| `firestick/` | The Fire TV app: Kotlin, one Gradle module (`app/`), package `com.firehome.launcher` |
| `firestick/app/src/main/python/` | The small Python layer that runs scraper modules (embedded with Chaquopy) |
| `firestick/tools/` | `deploy.sh` (install and set up on a Fire TV), `release.sh`, offline debrid tests |
| `firestick/docs/STANDARDS.md` | The design and wording rulebook every screen follows |
| `ios/` | The iPhone app: a Swift package (`ios/app/`) built with xtool |
| `ios/app/Vendor/Python/` | CPython 3.13 for iOS and its standard library (third party, unmodified) |
| `ios/tools/` | `build.sh`, `install.sh`, `ipa.sh`, `gen-secrets.sh` and helpers |
| `tools/` | Publishing tools for this repository (privacy scan, release) |

Desktop versions (Windows, Mac, Linux) will get their own folder here when they arrive.

<br>

## Keys and Where They Go

Dream OS needs no keys to build. Every key is optional and **anything you put in at build time ends up inside
the app**, so leave them empty for builds you share. Without them, Dream asks each viewer for their own
(TMDB in setup, OMDb in Settings) or hides the feature.

| Key | Fire TV (`firestick/local.properties`) | iPhone (`ios/secrets.properties`) | Without It |
| :-- | :-- | :-- | :-- |
| TMDB API key | `tmdb.key` | `tmdb.key` | Setup asks the viewer for their own |
| OMDb API key | `omdb.key` | `omdb.key` | IMDb and Rotten Tomatoes scores stay hidden until a key is entered |
| Trakt app | `trakt.client_id`, `trakt.client_secret` | same | Trakt sync is hidden (Fire TV) or uses the viewer's own app keys (iPhone) |
| Premiumize device sign-in | `premiumize.client_id` | not used | Premiumize is linked with the account's API key |
| Update repository | `update.repo` (`owner/name`) | not used | The in-app updater hides itself |

Real-Debrid sign-in uses Real-Debrid's published client ID for open-source apps, so it needs nothing from you.
Never commit `local.properties`, `secrets.properties` or `Secrets.swift`; they are git-ignored.

<br>

## Fire TV App

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

### Install on a Fire TV

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

## Releases

All platforms publish to this repository's [Releases](https://github.com/DenseCow/dream-os/releases). Every
release must include the Fire TV app as **`DreamOS.apk`** (plus `DreamOS.apk.sha256`), because the Downloader
code and the in-app updater always fetch the newest release's `DreamOS.apk`. The iPhone build is attached as
`Dream.ipa` when there is one.

Maintainers use `tools/release.sh` in the repository root: it builds the APK from the committed Fire TV code with
no keys inside, runs the privacy scan on it, and publishes the release. For your own fork, set `update.repo` to
your repository (or leave it empty) and sign with your own key.
