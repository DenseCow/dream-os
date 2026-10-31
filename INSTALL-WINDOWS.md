<p align="center"><img alt="Dream OS" src="docs/assets/icon.png" width="96"></p>

# Install Dream OS on Windows

Dream OS for Windows comes as one setup program. It installs everything Dream needs (the player, the Python that
runs your scrapers and its own Java runtime), so there's nothing else to download.

> [!WARNING]
> **Windows is new and still early.** Dream OS 4.3.6 is the first release for Windows, and it has had much less
> use than the Fire Stick and iPhone apps. Expect bugs. If something breaks, please
> [open an issue](https://github.com/DenseCow/dream-os/issues/new/choose) and tell us what you were doing, your
> Windows version and your Dream version. [Your log file](#logs-and-reporting-a-bug) helps a lot.

- [Compatibility](#compatibility)
- [Download and Install](#download-and-install)
- [First Launch and Setup](#first-launch-and-setup)
- [Updates](#updates)
- [Where Dream Keeps Your Things](#where-dream-keeps-your-things)
- [Logs and Reporting a Bug](#logs-and-reporting-a-bug)
- [Uninstall](#uninstall)
- [Build It Yourself From Source](#build-it-yourself-from-source)

<br>

## Compatibility

- **Windows 10 or 11, 64-bit.** So far Dream OS has been tested on one Windows 11 PC. Windows 10 and other PCs are
  untested.
- **About 300 MB of free space.** Setup shows the exact amount before it installs.
- A mouse, the keyboard (arrow keys, Enter and Esc), or both. F11 switches full screen on and off.

Tried it on another PC? Please share how it went in [Issues](https://github.com/DenseCow/dream-os/issues), with
your Windows version.

<br>

## Download and Install

1. Download
   [DreamOS-Windows-Setup.exe](https://github.com/DenseCow/dream-os/releases/latest/download/DreamOS-Windows-Setup.exe)
   from the latest release.
2. Open it. The setup isn't code-signed, so Windows may show a blue box that says **Windows protected your PC**.
   Select **More info**, then **Run anyway**. Windows shows this for most downloads from small projects that
   haven't bought a signing certificate.
3. **Welcome to Dream OS:** select **Next**.
4. **Choose Where to Install:** the folder is `C:\Users\<you>\AppData\Local\Programs\Dream OS` unless you pick
   another one with **Browse**. Updates go into the same folder later.
5. **Shortcuts:** Dream OS always gets a Start menu entry. Leave **Create a Desktop Shortcut** ticked if you want
   one on your desktop too.
6. **Ready to Install:** select **Install**.
7. **Dream OS Is Installed:** leave **Launch Dream OS** ticked and select **Finish**.

Setup installs Dream OS for your Windows account only, so it never asks for an administrator password. Someone else
on the same PC installs it for their own account.

<details>
<summary>Check the download (optional)</summary>

Each release lists `DreamOS-Windows-Setup.exe.sha256` next to the setup. In PowerShell, in your Downloads folder:

```powershell
Get-FileHash .\DreamOS-Windows-Setup.exe -Algorithm SHA256
```

The hash it prints should match the one in the `.sha256` file (Windows prints it in capitals; that's fine).

</details>

<br>

## First Launch and Setup

Setup walks you through a few short chapters. You can close it and pick up where you left off.

1. **Get Ready.** What you'll need, then your time zone (used for new episode and release times).
2. **Connect.**
   - **Your TMDB key.** Make a free account at [TMDB](https://www.themoviedb.org/), open its
     [API page](https://www.themoviedb.org/settings/api) and copy the key labeled **API Key** (not the long API
     Read Access Token). Select **Enter My Key** and paste it. You can also add a free OMDb key here for IMDb and
     Rotten Tomatoes scores.
   - **A debrid service.** Choose Real-Debrid, AllDebrid, Premiumize or TorBox and sign in. Real-Debrid,
     AllDebrid and TorBox show a code to enter on their website, from this PC or your phone. Premiumize asks for
     the API key on your Premiumize account page. **Connect Trakt** is optional and also lives here.
   - **Your scrapers.** Select **Add Repository** and enter the address of a scraper repository you trust.
     Dream ships with none and doesn't recommend any.
   - **Making Sure It All Works.** Dream runs one real search with your accounts and scrapers, so you know
     playback works before you start.
3. **Your PC.** New episode alerts as Windows notifications, and whether Dream OS opens when you sign in to
   Windows.
4. **Make It Yours.** Your profile, anyone else who watches (Kids profiles have age limits), picture and sound
   defaults, and a few extras. All of it can be changed later in Settings.

<br>

## Updates

Dream OS checks this repository for new versions. In **Settings › Updates**:

- **Check for Updates** looks right away.
- **Automatic Updates** (on by default) downloads a new version in the background, then asks before installing.

**Get the Update** downloads the new setup, checks it against its published SHA-256, closes Dream OS, installs the
update into the same folder and opens Dream OS again. Your profiles and settings stay as they are. You can also run
a newer `DreamOS-Windows-Setup.exe` by hand at any time; it updates the copy you have.

<br>

## Where Dream Keeps Your Things

| What | Folder |
| :-- | :-- |
| The app | The folder you chose in setup (`%LOCALAPPDATA%\Programs\Dream OS` by default) |
| Profiles, accounts and settings | `%APPDATA%\Dream OS` |
| Image cache and stream cache | `%LOCALAPPDATA%\Dream OS` |

Debrid and Trakt sign-ins are sealed with Windows' own data protection (DPAPI), so only your Windows account on
this PC can read them. To open a folder, paste its name (for example `%APPDATA%\Dream OS`) into the File Explorer
address bar.

<br>

## Logs and Reporting a Bug

Dream writes a log to `%APPDATA%\Dream OS\logs\dream.log`. If Dream OS ever crashes, the next start puts the crash
report (`hs_err_<number>.log`) in the same folder.

When you [open an issue](https://github.com/DenseCow/dream-os/issues/new/choose), include:

- what you were doing and what happened
- your Windows version (**Settings › System › About**) and your Dream version (**Settings › Updates** in Dream)
- `dream.log`, if you can. It can name titles you opened and services you use, so read it over before you share it.

<br>

## Uninstall

Open Windows **Settings › Apps › Installed apps** (on Windows 10: **Settings › Apps › Apps & features**), find
**Dream OS** and choose **Uninstall**.

Dream asks once. By default it removes the app and its shortcuts and **keeps** your profiles, accounts and settings,
so they're all back if you install it again. Tick **Also Remove My Settings** to delete those too, along with the
cache.

<br>

## Build It Yourself From Source

The Windows app's code is in [`windows/`](windows). See [Building](BUILDING.md#windows-app) for the steps.
