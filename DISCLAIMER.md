# Disclaimers

Please read this before installing or using Dream OS.

- [No Content Is Included](#no-content-is-included)
- [Your Responsibility](#your-responsibility)
- [Not Affiliated](#not-affiliated)
- [TMDB and JustWatch](#tmdb-and-justwatch)
- [Tested Devices](#tested-devices)
- [Sideloading](#sideloading)
- [No Warranty](#no-warranty)
- [Built With AI Help](#built-with-ai-help)
- [Privacy](#privacy)

<br>

## No Content Is Included

Dream OS hosts, stores and distributes **no** movies, shows or streams, and includes **no** sources. It has no
servers. Out of the box it can't play anything: it shows information from TMDB, and plays only what the viewer's
own accounts and the scraper repositories the viewer chooses to add return. The developers don't provide,
recommend or control any scraper repository.

## Your Responsibility

You bring your own TMDB key, accounts and scraper repositories. You are responsible for how you use Dream
OS and for following the laws of your country and the terms of every service you connect to it. Don't use Dream
OS to access content you don't have the right to watch.

## Not Affiliated

Dream OS is an independent open-source project. It is not affiliated with, endorsed by, or sponsored by any of
the companies or services below. All names, logos and trademarks belong to their owners and are used only to
describe compatibility or what a row shows. Dream OS uses no company logos; the colored service tiles inside the
app are Dream's own designs, not official artwork.

**Streaming services** (Dream shows rows and pages of what's on them)
- Netflix (Netflix, Inc.)
- Hulu (The Walt Disney Company)
- Disney+ (The Walt Disney Company)
- HBO Max (Warner Bros. Discovery)
- Prime Video (Amazon)
- Apple TV (Apple)
- Paramount+ (Paramount)
- Peacock (NBCUniversal)
- AMC+ (AMC Networks)
- Crunchyroll (Sony)
- Tubi (Fox)
- Pluto TV (Paramount)

**Platforms**
- Amazon: Fire TV, Fire OS, Amazon Appstore, Alexa
- Apple: iPhone, iOS, App Store, AirPods, AirPlay
- Microsoft: Windows, Microsoft Defender SmartScreen, Windows Hello

**Data and ratings**
- TMDB (The Movie Database)
- IMDb (Amazon)
- Rotten Tomatoes (Fandango)
- OMDb API
- Trakt
- JustWatch
- TVmaze, TheIntroDB, AniSkip, ARM, Kitsu, OpenSubtitles, Open-Meteo, ipwho.is, GeoJS, Cloudflare, GitHub

**Sources**
- Torrentio and Stremio (named in Dream's source list and as the subtitle index host)

**Sideloading tools named in the install guides**
- Downloader (AFTVnews)
- SideStore
- AltStore

**AI assistant**
- Anthropic (Claude)

## TMDB and JustWatch

This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability ("where to
watch") data is provided by JustWatch, through TMDB.

## Tested Devices

Dream OS has been tested on these devices:

- **Fire Stick:** a Fire TV Stick 4K Max (1st generation, 2021, model AFTKA) running Fire OS 7.7.1.3, which plays up
  to 4K with HDR and Dolby Vision, and a Fire TV Stick HD (model AFTSS) running Fire OS 7.7.1.7, which plays up to
  1080p. Both versions of Fire OS are based on Android 9. Other Fire TV models and Fire OS versions, such as Fire
  OS 8 on newer sticks, are untested and may not work or may behave differently, especially the optional "make
  Dream your Home screen" steps, which rely on Fire OS 7 behavior. Fire TV devices running Vega OS can't install
  it at all.
- **iPhone:** an iPhone Air on iOS 27.2; requires iOS 17 or later. Other iPhone models and iOS versions are
  untested.
- **Windows:** one PC running Windows 11 (64-bit). The Windows app is new in 4.3.6 and has had much less use than
  the other two, so expect more bugs there. Windows 10 and other PCs are untested.

Use on other devices at your own risk. Reports are welcome in
[Issues](https://github.com/DenseCow/dream-os/issues).

## Sideloading

The Fire Stick app installs from outside the Amazon Appstore, and the iPhone app is sideloaded with your own Apple
Account instead of coming from the App Store. Neither has been reviewed by Amazon or Apple. Amazon's and Apple's
terms for your devices and accounts still apply. The optional Fire Stick Home screen steps change system settings
over ADB; you can undo them at any time (see the [install guide](INSTALL-FIRESTICK.md#undo-it)).

The Windows setup isn't code-signed, so Microsoft Defender SmartScreen warns before it runs (see the
[install guide](INSTALL-WINDOWS.md#download-and-install)). Each release lists the setup's SHA-256 so you can check
that your download matches.

## No Warranty

Dream OS is provided **as is**, without warranty of any kind, and the authors aren't liable for any damage or
loss from using it. See sections 15 and 16 of the [GNU General Public License v3.0](LICENSE).

## Built With AI Help

Dream OS was designed and built with the help of Claude, Anthropic's AI assistant, through
[Claude Code](https://claude.com/claude-code). Parts of the code were written with AI help and reviewed and
tested by the maintainer, but mistakes can slip through. If you find a bug, please
[open an issue](https://github.com/DenseCow/dream-os/issues/new/choose). Anthropic doesn't endorse or sponsor
this project.

## Privacy

Dream OS keeps your keys, accounts, profiles and watch history on your device and sends nothing to the
developer. It has no analytics, tracking or crash reporting. It connects directly to the services you set up
(TMDB, OMDb, Trakt, your other accounts and your scraper repositories) and to a few free public services for
extras: TVmaze (air times), TheIntroDB, AniSkip, ARM and Kitsu (skip markers), OpenSubtitles (subtitles),
trailer streams from the video host TMDB lists, Cloudflare (only when you run the speed test), on the Fire Stick
Open-Meteo for the weather (using an approximate location from your internet address via ipwho.is or GeoJS), and on
the Fire Stick and Windows GitHub (to check for Dream updates).
