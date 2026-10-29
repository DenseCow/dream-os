<p align="center"><img alt="Dream OS" src="docs/assets/icon.png" width="96"></p>

# Install Dream OS on a Fire Stick

This guide takes about ten minutes. All you need is your Fire Stick remote. A computer is only needed for one
optional extra at the end.

- [Compatibility](#compatibility)
- [Step 1: Get the Downloader App](#step-1-get-the-downloader-app)
- [Step 2: Let Downloader Install Apps](#step-2-let-downloader-install-apps)
- [Step 3: Download and Install Dream OS](#step-3-download-and-install-dream-os)
- [Step 4: First Launch and Setup](#step-4-first-launch-and-setup)
- [Recommended: Debloat Your Fire Stick](#recommended-debloat-your-fire-stick)
- [Optional: Make Dream Your Home Screen](#optional-make-dream-your-home-screen)
- [Updating](#updating)
- [Uninstalling](#uninstalling)
- [Troubleshooting](#troubleshooting)

> [!TIP]
> If your Fire Stick feels slow, even on Amazon's home screen, [debloat it](#recommended-debloat-your-fire-stick).
> It frees the memory Amazon's background apps hold so Dream OS has room to run.

<br>

## Compatibility

Dream OS has been tested on two Fire Sticks:

| Device | Model | Software Tested | Plays Up To |
| :-- | :-- | :-- | :-- |
| Fire TV Stick 4K Max (1st gen, 2021) | AFTKA | Fire OS 7.7.1.3 (Android 9) | 4K with HDR and Dolby Vision |
| Fire TV Stick HD | AFTSS | Fire OS 7.7.1.7 (Android 9) | 1080p |

- **Fire TV Stick HD:** its video decoders stop at 1920x1088 and it outputs 1080p, so Dream OS
  picks 1080p copies on it automatically. It has about 1 GB of memory, so [debloating](#recommended-debloat-your-fire-stick) is
  strongly recommended.
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

## Recommended: Debloat Your Fire Stick

Amazon runs dozens of its own apps in the background on every Fire Stick: ads, shopping, telemetry, Alexa extras,
Amazon Music, photo screensavers and more. They hold memory even when you never open them. On a stick with about
1 GB of memory (see [Compatibility](#compatibility)), that's enough to make everything lag, Amazon's home
screen included.
Turning them off frees that memory for Dream OS.

Nothing is deleted. Each app is only switched off, and you can switch any of them back on with one command. Your
streaming apps (Prime Video, Netflix and the rest), the Appstore and Downloader aren't touched.

You need a Windows, Mac or Linux computer on the same Wi-Fi as the Fire Stick.

1. Install Android's platform tools (they include `adb`) from
   [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
2. On the Fire Stick, open **Settings › My Fire TV › Developer Options** and turn on **ADB Debugging**. Then find
   its IP address in **Settings › My Fire TV › About › Network**.
3. On the computer, connect (replace `FIRE-TV-IP` with that address). The TV asks **Allow USB debugging?**: tick
   **Always allow from this computer** and choose **OK**.

   ```sh
   adb connect FIRE-TV-IP:5555
   ```

4. Run these commands. Some models don't have every app, so a few lines may answer `Unknown package`. That's
   normal; skip past it.

   ```sh
   # Ads, shopping and promotions
   adb shell pm disable-user --user 0 com.amazon.tv.acr
   adb shell pm disable-user --user 0 com.amazon.hybridadidservice
   adb shell pm disable-user --user 0 com.amazon.kso.blackbird
   adb shell pm disable-user --user 0 com.amazon.device.sale.service
   adb shell pm disable-user --user 0 com.amazon.shoptv.client
   adb shell pm disable-user --user 0 com.amazon.shoptv.firetv.client
   adb shell pm disable-user --user 0 com.amazon.sneakpeek
   adb shell pm disable-user --user 0 com.amazon.minitv.android.app
   adb shell pm disable-user --user 0 com.amazon.gamehub

   # Usage reports and diagnostics sent to Amazon
   adb shell pm disable-user --user 0 com.amazon.dp.logger
   adb shell pm disable-user --user 0 com.amazon.tv.fw.metrics
   adb shell pm disable-user --user 0 com.amazon.wirelessmetrics.service
   adb shell pm disable-user --user 0 com.amazon.perfcollection
   adb shell pm disable-user --user 0 com.amazon.connectivitydiag

   # Tutorials, release notes and help videos
   adb shell pm disable-user --user 0 com.amazon.tv.releasenotes
   adb shell pm disable-user --user 0 com.amazon.tv.support
   adb shell pm disable-user --user 0 com.amazon.storm.lightning.tutorial
   adb shell pm disable-user --user 0 com.amazon.tmm.tutorial

   # Amazon Photos screensaver, smart home and Matter
   adb shell pm disable-user --user 0 com.amazon.bueller.photos
   adb shell pm disable-user --user 0 com.amazon.smarthomemapviewapp
   adb shell pm disable-user --user 0 com.amazon.tv.matter
   adb shell pm disable-user --user 0 com.amazon.tv.mattercohost
   adb shell pm disable-user --user 0 com.amazon.wifilocker

   # Alexa extras: alerts, notifications, music and multi-room audio
   adb shell pm disable-user --user 0 com.amazon.tv.alexaalerts
   adb shell pm disable-user --user 0 com.amazon.tv.alexanotifications
   adb shell pm disable-user --user 0 com.amazon.alexa.externalmediaplayer.fireos
   adb shell pm disable-user --user 0 com.amazon.alexamediaplayer.runtime.ftv
   adb shell pm disable-user --user 0 com.amazon.whad
   adb shell pm disable-user --user 0 com.amazon.whasettings
   adb shell pm disable-user --user 0 com.amazon.wha.mediabrowserservice
   adb shell pm disable-user --user 0 com.amazon.audiohome
   adb shell pm disable-user --user 0 com.amazon.bueller.music
   adb shell pm disable-user --user 0 com.amazon.spotify.mediabrowserservice

   # Frustration-Free Setup: keeps a Bluetooth scan running that can make AirPods stutter
   adb shell pm disable-user --user 0 com.amazon.whisperjoin.middleware.np

   # Fire OS updates that install on their own
   adb shell pm disable-user --user 0 com.amazon.tv.forcedotaupdater.v2
   ```

5. Optional, for snappier menus: halve the animation times.

   ```sh
   adb shell settings put global window_animation_scale 0.5
   adb shell settings put global transition_animation_scale 0.5
   adb shell settings put global animator_duration_scale 0.5
   ```

6. Restart the Fire Stick (**Settings › My Fire TV › Restart**). Turn **ADB Debugging** off again if you like.

Leave Alexa's voice apps (`com.amazon.vizzini`, `com.amazon.vizzini.ftvcds` and
`com.amazon.alexadirectivebrokerservice`) switched on. Dream OS's voice search listens through them, so the voice
remote stops searching without them.

### Undo It

To switch an app back on, run the same command with `enable` in place of `disable-user --user 0`, for example:

```sh
adb shell pm enable com.amazon.bueller.photos
```

<br>

## Optional: Make Dream Your Home Screen

Dream can take the place of Amazon's home screen, start up in Dream, answer the voice remote's microphone
button, add your other apps' shows to Continue Watching, and use the Dream screensaver. The Fire Stick only allows
these after a one-time permission over ADB. Do it on the TV, from the Dream app on your iPhone, or from a computer.

> These steps were tested on Fire OS 7 only. Fire OS updates can change how they behave.

### On the TV

1. Open **Settings › My Fire TV › Developer Options** and turn on **ADB Debugging**. (No Developer Options? Choose
   **About** and press Select on your Fire Stick's name **7 times**, then go back.)
2. In Dream, open **Settings › Accounts & Scrapers › Unlock Features** (it's also a step in setup) and choose
   **Unlock Now**.
3. Fire Stick asks **Allow USB debugging?** Check **Always allow from this computer** and choose **OK**.
4. When every feature shows as on, choose **Turn Off ADB Debugging**. Everything stays unlocked.

If Dream shows **Unlock From Your iPhone**, this Fire Stick only accepts the unlock from another device on your
Wi-Fi (the Fire TV Stick HD on Fire OS 7.7.1.7 works this way). Use your iPhone or a computer, below. The screen
shows the address you need, and Dream finishes by itself once the other device is done.

### From the Dream App on Your iPhone (No Computer Needed)

You need [Dream for iPhone](INSTALL-IOS.md), on the same Wi-Fi as the Fire Stick.

1. On the Fire Stick, turn on **ADB Debugging** (step 1 above).
2. On the Fire Stick, open Dream's **Unlock Features** page and leave it open. Your iPhone finds the Fire Stick by
   name while it's showing.
3. On the iPhone, open Dream, tap your profile picture at the top right, then **Settings › Unlock Fire Stick**.
   When iOS asks to find devices on your local network, choose **Allow**.
4. Tap your Fire Stick, or type the address the TV shows and tap **Unlock**.
5. The TV asks **Allow USB debugging?** With the remote, check **Always allow from this computer** and choose **OK**.
6. When every feature shows **On**, tap **Turn Off ADB Debugging**. Everything stays unlocked.

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
