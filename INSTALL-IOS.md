<p align="center"><img alt="Dream" src="docs/assets/icon.png" width="96"></p>

# Install Dream on iPhone

Dream for iPhone isn't on the App Store, so you install it yourself ("sideloading") with your own Apple Account.
You don't need a Mac or a paid developer account.

- [Compatibility](#compatibility)
- [Before You Start](#before-you-start)
- [Option 1: SideStore (Recommended)](#option-1-sidestore-recommended)
- [Option 2: AltStore](#option-2-altstore)
- [Keeping Dream Working: The 7-Day Refresh](#keeping-dream-working-the-7-day-refresh)
- [First Launch and Setup](#first-launch-and-setup)
- [Updating and Uninstalling](#updating-and-uninstalling)
- [Build It Yourself From Source](#build-it-yourself-from-source)

<br>

## Compatibility

- **Tested on iOS 27.2.** Requires iOS 17 or later; earlier iOS 17 to 26 releases are untested.
- Tested on one phone, an iPhone Air. Other iPhone models are untested. Older iPhones may load more slowly, and
  HDR needs an HDR-capable screen.
- iPad isn't supported yet.

Tried it on another iPhone or iOS version? Please share how it went in
[Issues](https://github.com/DenseCow/dream-os/issues), with your model and iOS version.

<br>

## Before You Start

- **Get the app file.** On the [latest release](https://github.com/DenseCow/dream-os/releases/latest), download
  **Dream.ipa**. If a release doesn't include one, [build it yourself](#build-it-yourself-from-source).
- **An Apple Account.** A free one works. Sideloading tools sign Dream with your account so iOS will run it.
  Consider making a separate free Apple Account just for sideloading.
- **Developer Mode.** iOS asks for it the first time you open a sideloaded app: turn it on in
  **Settings › Privacy & Security › Developer Mode**, restart, and confirm.

<br>

## Option 1: SideStore (Recommended)

SideStore installs and refreshes apps right on your iPhone. You need a computer (Windows, Mac or Linux) once,
to set SideStore itself up; after that, everything happens on the phone.

1. Install SideStore by following the official guide at [sidestore.io](https://sidestore.io). It walks you
   through installing SideStore and creating your phone's pairing file.
2. Download **Dream.ipa** on your iPhone (Safari saves it to **Files › Downloads**).
3. Open **SideStore**, go to **My Apps**, tap **+**, and choose **Dream.ipa**.
4. Sign in with your Apple Account if asked. SideStore signs and installs Dream.
5. Open **Dream** from your Home Screen.

<br>

## Option 2: AltStore

AltStore works the same way but refreshes apps with the help of **AltServer** running on a computer (Windows or
Mac) on the same Wi-Fi.

1. Install AltServer and AltStore by following the guide at [altstore.io](https://altstore.io).
2. Download **Dream.ipa** on your iPhone.
3. Open **AltStore**, go to **My Apps**, tap **+**, and choose **Dream.ipa**.

<br>

## Keeping Dream Working: The 7-Day Refresh

Apps signed with a **free** Apple Account stop opening after **7 days** until they're refreshed. Your profiles,
accounts and history aren't lost; Dream just needs a fresh signature.

- **SideStore:** open SideStore and tap **Refresh All** (or turn on its background refresh). Do it at least
  once a week.
- **AltStore:** keep AltServer running on your computer; AltStore refreshes over Wi-Fi, or tap **Refresh All**.
- Free accounts can have only a few sideloaded apps active at once (SideStore or AltStore counts as one).
- A paid Apple Developer account signs apps for a year instead of 7 days.

<br>

## First Launch and Setup

Dream's setup walks you through everything: your **debrid account** (Real-Debrid, AllDebrid, Premiumize or
TorBox, signed in with a code), your free **TMDB key** (Dream shows how to get one), the **scraper
repositories** you want to use (Dream doesn't include any), playback preferences and profiles. Your keys are
kept in the iPhone's Keychain. Everything can be changed later in **Settings**.

<br>

## Updating and Uninstalling

- **Update:** download the new **Dream.ipa** from the [latest release](https://github.com/DenseCow/dream-os/releases/latest)
  and install it again from SideStore or AltStore (tap **+** and choose the file). It installs over the old
  version and keeps your data, as long as you use the same Apple Account.
- **Uninstall:** press and hold Dream on the Home Screen, choose **Remove App › Delete App**. This removes
  everything Dream stored on the phone.

<br>

## Build It Yourself From Source

Dream is a Swift package built with [xtool](https://github.com/xtool-org/xtool), which builds iPhone apps on
Linux, Windows (through WSL) and macOS. See [BUILDING.md](BUILDING.md#iphone-app) for the full steps. In short:

1. Install Swift 6.x (with [swiftly](https://www.swift.org/install/)) and xtool, then run `xtool setup`
   (it asks for Xcode's `.xip`, downloaded with your Apple Account from developer.apple.com, and how to sign in).
2. Get the code and (optionally) your keys:

   ```sh
   git clone https://github.com/DenseCow/dream-os.git
   cd dream-os/ios
   cp secrets.properties.example secrets.properties   # optional; every key may stay empty
   tools/gen-secrets.sh
   ```

3. Either install straight onto a connected iPhone with `tools/install.sh`, or make an unsigned
   `builds/Dream.ipa` for SideStore or AltStore with `tools/ipa.sh`.

Builds you make yourself use your own Apple Account and are refreshed the same way (every 7 days on a free
account).
