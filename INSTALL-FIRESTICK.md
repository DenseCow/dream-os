<p align="center"><img alt="Dream OS" src="docs/assets/icon.png" width="96"></p>

# Install Dream OS on the Fire Stick

This guide takes about ten minutes. All you need is your Fire Stick remote. A computer is only needed for one
optional extra at the end.

- [Compatibility](#compatibility)
- [Step 1: Get the Downloader App](#step-1-get-the-downloader-app)
- [Step 2: Let Downloader Install Apps](#step-2-let-downloader-install-apps)
- [Step 3: Download and Install Dream OS](#step-3-download-and-install-dream-os)
- [Step 4: First Launch and Setup](#step-4-first-launch-and-setup)
- [Optional: Make Dream Your Home Screen](#optional-make-dream-your-home-screen)
- [Updating](#updating)
- [Uninstalling](#uninstalling)
- [Troubleshooting](#troubleshooting)

<br>

## Compatibility

- **Tested on:** a Fire TV Stick 4K Max (1st generation, 2021) running Fire OS 7.7.1.3 (based on Android 9).
  That is the only device Dream OS has been tested on.
- **Other Fire TV models and Fire OS versions are untested.** They may not work or may behave differently,
  for example Fire OS 8 on newer sticks. The optional Home screen steps rely on Fire OS 7 behavior and are the
  most likely to differ.
- **Fire TV devices running Vega OS can't install Dream OS at all,** because they can't install apps from
  outside the Amazon Appstore.
- The app needs Fire OS 6 or later (Android 7.1 or later).

Tried it on another device? Please share how it went in
[Issues](https://github.com/DenseCow/dream-os/issues), with your model and Fire OS version
(Settings › My Fire TV › About).

<br>

## Step 1: Get the Downloader App

Downloader is a free app by AFTVnews from the Amazon Appstore. It downloads files from the internet onto your
Fire Stick.

1. On the Fire Stick home screen, choose **Find**, then **Search**.
2. Type **Downloader** and choose the Downloader app (orange icon).
3. Choose **Get** (or **Download**) and wait for it to install.

<br>

## Step 2: Let Downloader Install Apps

The Fire Stick only installs apps from the Appstore until you allow another app to install them.

1. Open **Settings › My Fire TV**.
2. If you don't see **Developer Options**, choose **About**, then press the Select button on your Fire Stick's
   name **7 times** until it says you're a developer. Go back one screen.
3. Open **Developer Options › Install Unknown Apps** (on some versions: **Apps from Unknown Sources**).
4. Turn it **On** for **Downloader**.

<br>

## Step 3: Download and Install Dream OS

1. Open **Downloader**. If it asks for access to your files, choose **Allow**.
2. Select the address box on the Home tab and type the Downloader code:

   <table><tr><td align="center"><sub>DOWNLOADER CODE</sub><br><h2><!--dl-code-->5988263<!--/dl-code--></h2></td></tr></table>

   Or type the full address instead:
   `github.com/DenseCow/dream-os/releases/latest/download/DreamOS.apk`
3. Choose **Go**. Downloader fetches the newest Dream OS.
4. When the install screen appears, choose **Install**, then **Done** (or **Open**).
5. Back in Downloader, choose **Delete** to remove the download file and free up space. Dream OS stays
   installed.

<br>

## Step 4: First Launch and Setup

Open Dream OS from **Your Apps & Channels** (hold the Home button, then choose **Apps**). To keep it handy,
highlight it, press the Menu button (three lines) and choose **Move to front**.

Dream's setup walks you through everything, one simple screen at a time:

1. **Your Time Zone.**
2. **Connect Your Accounts.** Sign in to your debrid service (Real-Debrid, AllDebrid, Premiumize or TorBox).
   Dream shows a short code and a QR code; enter the code on your phone or computer and you're linked.
3. **TMDB Key.** Dream gets posters, details and search from TMDB. A key is free: scan the code on screen, sign
   up or sign in at themoviedb.org, and copy the key shown as **API Key**. You can type it with your phone.
4. **Scrapers.** Add the address of a scraper repository you want to use. Dream doesn't include any.
5. **Playback Preferences** and a final check that everything works.
6. **Unlock Features** (optional, see below), **Profiles**, your look and screen fit, and a few extras like
   Phone Remote, AirPods and voice search.

Every choice is saved as you go, and everything lives in **Settings** later. You can run setup again from
**Settings › Profiles › Run Setup Again**.

<br>

## Optional: Make Dream Your Home Screen

Dream can take the place of Amazon's home screen, start up in Dream, answer the voice remote's microphone
button, show your other apps' shows in Continue Watching, and use the Dream screensaver. The Fire Stick only allows
these with a one-time permission over ADB. You can do it right on the TV, or from a computer.

> These steps were tested on Fire OS 7 only. Fire OS updates can change how they behave.

### On the TV (No Computer Needed)

1. Open **Settings › My Fire TV › Developer Options** and turn on **ADB Debugging**.
2. In Dream, open **Settings › Accounts & Scrapers › Unlock Features** (it's also a step in setup) and choose
   **Unlock Now**.
3. Fire Stick asks **Allow USB debugging?** Tick **Always allow from this computer** and choose **OK**.
   (The "computer" is Dream itself, connecting to your own Fire Stick. Nothing leaves the TV.)
4. When every feature shows as on, choose **Turn Off ADB Debugging**. Everything stays unlocked.

### From a Computer With ADB

If you prefer, give the same permissions from a Windows, Mac or Linux computer on the same Wi-Fi.

1. Install Android's platform tools (they include `adb`) from
   [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
2. On the Fire Stick, turn on **ADB Debugging** (as above) and find its IP address in
   **Settings › My Fire TV › About › Network**.
3. On the computer, connect (replace `FIRE-TV-IP` with that address) and allow the prompt on the TV:

   ```sh
   adb connect FIRE-TV-IP:5555
   ```

4. Run these commands:

   ```sh
   # Lets Dream turn its Home button service, voice search and screensaver on and keep them on after updates
   adb shell pm grant com.firehome.launcher android.permission.WRITE_SECURE_SETTINGS

   # Continue Watching: read what your other streaming apps are playing
   adb shell cmd notification allow_listener com.firehome.launcher/com.firehome.launcher.AppWatchService

   # Self-updates: let Dream hand a verified update to the installer
   adb shell appops set com.firehome.launcher REQUEST_INSTALL_PACKAGES allow

   # App sizes on the System page
   adb shell appops set com.firehome.launcher GET_USAGE_STATS allow

   # AirPods connection status (Android needs location permission for Bluetooth scans)
   adb shell pm grant com.firehome.launcher android.permission.ACCESS_FINE_LOCATION

   # The Home button: turn on Dream's Home button service
   adb shell settings put secure enabled_accessibility_services com.firehome.launcher/com.firehome.launcher.HomeRedirectService
   adb shell settings put secure accessibility_enabled 1

   # Voice remote and the Dream screensaver
   adb shell settings put secure voice_interaction_service com.firehome.launcher/.FireVoiceService
   adb shell settings put secure assistant com.firehome.launcher/.FireVoiceService
   adb shell settings put secure screensaver_components com.firehome.launcher/com.firehome.launcher.DreamSaver
   adb shell settings put secure screensaver_enabled 1
   ```

5. Turn **ADB Debugging** off again on the TV if you like. The permissions stay.

If you already have other accessibility services turned on, the `enabled_accessibility_services` command
replaces them; add them back separated by `:`.

### Undo It

Turn off **Dream OS** under **Settings › Accessibility** on the Fire Stick (or uninstall Dream OS). The Amazon home
screen comes back right away.

<br>

## Updating

Dream checks for updates once a day and lets you know when a new version is ready. You can also check any time
in **Settings › Updates**. Choose **Install** and confirm. Your profiles, accounts and history stay.

You can also update by repeating [Step 3](#step-3-download-and-install-dream-os): the Downloader code always
gets the newest version, and it installs over the old one.

<br>

## Uninstalling

1. Open **Settings › Applications › Manage Installed Applications**.
2. Choose **Dream OS**, then **Uninstall**, and confirm.

Uninstalling removes everything Dream stored on the TV, including your linked accounts and history.

<br>

## Troubleshooting

| Problem | Try This |
| :-- | :-- |
| Downloader says it can't install apps | Repeat [Step 2](#step-2-let-downloader-install-apps) and make sure Downloader is turned on. |
| "App not installed" when updating | You have a Dream OS signed by someone else (for example your own build). Uninstall it first, then install again. |
| The code doesn't work | Type the full address shown in Step 3 instead. |
| Posters and rows don't load | Check your TMDB key in **Settings › Advanced › TMDB API Key**. |
| Nothing plays | Check your debrid account in **Settings › Accounts & Scrapers › Accounts** and that at least one scraper repository is added under **Scrapers** on the same page. |
| The Home button still opens Amazon's home | Re-run **Unlock Features**, or restart the Fire Stick once. |

Still stuck? Open an [issue](https://github.com/DenseCow/dream-os/issues/new/choose) with your Fire Stick model and
Fire OS version.
