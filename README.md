<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/logo.png">
    <img alt="Dream OS" src="docs/assets/logo.png" width="560">
  </picture>
</p>

<p align="center"><b>Browse and play movies and shows on your Fire Stick and iPhone, using your own accounts.</b><br>
Free and open source under GPL-3.0. Dream OS ships with no content and no sources.</p>

<p align="center">
  <a href="https://github.com/DenseCow/dream-os/releases/latest"><img alt="Latest release on GitHub" src="https://img.shields.io/github/v/release/DenseCow/dream-os?style=flat-square&color=2c2c2e&labelColor=0b0b0c&label=release"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-2c2c2e?style=flat-square&labelColor=0b0b0c"></a>
</p>

<p align="center">
  <img alt="Dream OS on a Fire Stick: the Home screen with a large title image, ratings and a Play button" src="docs/screenshots/firestick/01-home.webp" width="900"><br>
  <sub>The Fire Stick Home Screen</sub>
</p>

Dream OS is an app for finding and watching movies and shows. It has large artwork, trailers that play behind the
title you're on, rows built from what you watch, and one Play button. On a Fire Stick it can also take the place of
Amazon's home screen.

Dream doesn't host or include anything to watch. It connects the services you already use and puts them behind
one interface.

## What You Need

- **A Fire Stick or an iPhone.** See [Tested Devices](#tested-devices) for the models Dream OS has been tried on.
- **A free TMDB key.** Dream uses TMDB for posters, details, search and rows. Setup shows you how to get one in a
  couple of minutes.
- **Your own debrid account.** Real-Debrid, AllDebrid, Premiumize or TorBox, signed in with a code on screen. Dream
  works with whichever ones you link, in your order.
- **At least one scraper repository.** Dream runs the scraper modules you choose to add, inside the app, to find
  links for what you want to watch. Dream ships with none.
- **Optional:** a free OMDb key for IMDb and Rotten Tomatoes scores, and a Trakt account to sync your history.

## Install

### Fire Stick

Install the free **Downloader** app from the Amazon Appstore, open it, choose the address box, and type this code:

<table>
  <tr>
    <td align="center">
      <sub>Downloader Code</sub><br>
      <h2><!--dl-code-->5988263<!--/dl-code--></h2>
    </td>
  </tr>
</table>

If the code doesn't work, type the full address instead:
`github.com/DenseCow/dream-os/releases/latest/download/DreamOS.apk`

The Fire Stick blocks apps from outside the Appstore until you allow Downloader to install them. The
[Fire Stick Install Guide](INSTALL-FIRESTICK.md) walks through that and every other step, and covers the optional
steps that make Dream your Home screen. To install from a computer instead, download
[DreamOS.apk](https://github.com/DenseCow/dream-os/releases/latest/download/DreamOS.apk) directly.

> [!TIP]
> **Debloat your Fire Stick first (recommended).** Amazon keeps dozens of its own apps running in the background:
> ads, shopping, telemetry, Alexa extras and more. On a stick with about 1 GB of memory they make everything lag,
> Amazon's own home screen included. Turning them off takes a few minutes from a computer, and you can turn any of
> them back on later.
> [How to debloat your Fire Stick](INSTALL-FIRESTICK.md#recommended-debloat-your-fire-stick)

### iPhone

Dream for iPhone isn't on the App Store. You install it yourself with a free sideloading app such as SideStore or
AltStore, using your own Apple Account; no Mac needed. The [iPhone Install Guide](INSTALL-IOS.md) explains how, and
how to build it from source. The app file, **Dream.ipa**, is on the
[latest release](https://github.com/DenseCow/dream-os/releases/latest).

## Platforms

| Platform | Status | Guide |
| :-- | :-- | :-- |
| Fire Stick | Available | [Install on Fire Stick](INSTALL-FIRESTICK.md) |
| iPhone | Available (sideloaded) | [Install on iPhone](INSTALL-IOS.md) |
| Windows, Mac, Linux | Coming soon | |

### Tested Devices

| Device | Model | Software Tested | Plays Up To |
| :-- | :-- | :-- | :-- |
| Fire TV Stick 4K Max (1st gen, 2021) | AFTKA | Fire OS 7.7.1.3 (Android 9) | 4K with HDR and Dolby Vision |
| Fire TV Stick HD | AFTSS | Fire OS 7.7.1.7 (Android 9) | 1080p |
| iPhone Air | | iOS 27.2 (needs iOS 17 or later) | |

- **Fire TV Stick HD:** its video decoders stop at 1920x1088 and it outputs 1080p, so set
  Maximum Quality to 1080p during setup (4K copies won't play on it). It has about 1 GB of memory, so
  [debloating](INSTALL-FIRESTICK.md#recommended-debloat-your-fire-stick) is strongly recommended.
- **Other Fire TV models and Fire OS versions are untested.** Fire TV devices running Vega OS can't install Dream OS
  at all, because they only install apps from the Amazon Appstore.
- **Other iPhones are untested.** Older models may load more slowly, and HDR needs an HDR-capable screen.

Tried it on another device? Reports are welcome in [Issues](https://github.com/DenseCow/dream-os/issues), with your
model and OS version.

## What It Does

### Pressing Play

Dream Core searches your own debrid accounts and the scraper repositories you added, picks the best match for your
quality settings, and plays it in Dream Player. Instant Play gets the next episode ready before you need it.

### Finding Something to Watch

The Dream Cinema look is black and gray with a soft rainbow glow. Trailers play behind the title you're on. Home has
rows like Continue Watching, Top 10 Today, In Theaters, New Episodes and Because You Watched, and seasonal hubs
gather holiday favorites when it's their time of year. Anime lives on its own page instead of filling every row.

Title pages show IMDb and Rotten Tomatoes scores (with a free OMDb key), cast, season guides, air times, and which of
the services you already pay for are streaming it.

### Dream Player

Subtitles, audio languages, Skip Intro and Skip Credits, an X-Ray style cast and info panel, Up Next with autoplay,
and Start Over. On iPhone it also does Picture in Picture.

### Your Library and Profiles

My List, Continue Watching, and a Watched mark on everything you've finished. A banner tells you when a new episode
of a show you follow is out. Connect Trakt (optional) to keep your history in sync.

Each profile has its own lists and history and an optional PIN. Kids profiles have age limits.

### On the Fire Stick

Dream can replace Amazon's home screen (optional). You can search with the voice remote, type from your phone with
Phone Remote, see whether your AirPods are connected, use the Dream screensaver, and install updates inside the app.

### On the iPhone

Portrait browsing, a landscape player and background audio, with the same accounts, profiles and library rules as
the TV.

## Screenshots

<table>
  <tr>
    <td width="50%" align="center"><img alt="Top Picks and seasonal rows on Home" src="docs/screenshots/firestick/02-home-rows.webp"><br><sub>Top Picks and Seasonal Rows</sub></td>
    <td width="50%" align="center"><img alt="The Top 10 Today row with large ranked numbers" src="docs/screenshots/firestick/03-top-10.webp"><br><sub>Top 10 Today</sub></td>
  </tr>
  <tr>
    <td width="50%" align="center"><img alt="Posters marked with where each title is available" src="docs/screenshots/firestick/04-availability-badges.webp"><br><sub>Availability on Every Poster</sub></td>
    <td width="50%" align="center"><img alt="The 31 Nights of Horror seasonal hub" src="docs/screenshots/firestick/06-seasonal-hub.webp"><br><sub>Seasonal Hub: 31 Nights of Horror</sub></td>
  </tr>
  <tr>
    <td width="50%" align="center"><img alt="Browse by genre, service or catalog" src="docs/screenshots/firestick/07-browse.webp"><br><sub>Browse by Genre, Service or Catalog</sub></td>
    <td width="50%" align="center"><img alt="A genre page with its own title image" src="docs/screenshots/firestick/08-genre-page.webp"><br><sub>Genre Pages</sub></td>
  </tr>
  <tr>
    <td width="50%" align="center"><img alt="A movie page with IMDb and Rotten Tomatoes scores" src="docs/screenshots/firestick/09-movie-details.webp"><br><sub>Movie Page With IMDb and Rotten Tomatoes Scores</sub></td>
    <td width="50%" align="center"><img alt="The About and Cast section of a movie page" src="docs/screenshots/firestick/10-cast.webp"><br><sub>About and Cast</sub></td>
  </tr>
  <tr>
    <td width="50%" align="center"><img alt="A TV show page that picks up where you left off" src="docs/screenshots/firestick/11-tv-show.webp"><br><sub>TV Show Page That Picks Up Where You Left Off</sub></td>
    <td width="50%" align="center"><img alt="The season and episode rail" src="docs/screenshots/firestick/12-episodes.webp"><br><sub>Seasons and Episodes</sub></td>
  </tr>
  <tr>
    <td width="50%" align="center"><img alt="Search results" src="docs/screenshots/firestick/13-search.webp"><br><sub>Search</sub></td>
    <td width="50%" align="center"><img alt="The Settings page" src="docs/screenshots/firestick/14-settings.webp"><br><sub>Settings</sub></td>
  </tr>
</table>

<sub>Real screenshots of Dream OS on a Fire Stick. Posters, artwork and descriptions come from TMDB. The colored
service tiles are Dream's own designs, not official logos. iPhone screenshots are coming soon.</sub>

## Privacy

Dream has no accounts, no tracking and no analytics. It has no servers of its own and sends nothing to the
developer.

Your keys, accounts, profiles and history stay on your device. TMDB, OMDb, debrid and Trakt sign-ins are stored
only on the device you entered them on (in the iPhone's Keychain on iOS).

Dream talks straight to the services you use: TMDB, OMDb, Trakt, your debrid services and your scraper
repositories. It also uses a few free public services for extras:

- TVmaze for air times
- TheIntroDB, AniSkip, ARM and Kitsu for skip markers
- OpenSubtitles for subtitles
- the video host TMDB lists for trailers
- Cloudflare, only when you run the speed test
- on the Fire Stick, Open-Meteo for the weather (with an approximate location from your internet address via
  ipwho.is or GeoJS), and GitHub to check for Dream updates

## Source Code

Dream OS is free software, released under the [GNU General Public License v3.0](LICENSE). You can use it, study it,
change it and share it under the same terms. It builds on other open-source projects; see
[Third-Party Notices](THIRD_PARTY_NOTICES.md) for their licenses.

| Folder | What's Inside |
| :-- | :-- |
| [`firestick/`](firestick) | The Fire Stick app (Kotlin, Android 7.1+ / Fire OS 6+) |
| [`ios/`](ios) | The iPhone app (Swift and SwiftUI, iOS 17+) |
| [`docs/`](docs) | Brand images and screenshots |
| [`tools/`](tools) | Publishing and release scripts |

To help, start with [Contributing](CONTRIBUTING.md) and [Building](BUILDING.md). To report a security problem, see
[Security](SECURITY.md).

## Credits

- This product uses the TMDB API but is not endorsed or certified by TMDB.
- Streaming availability data provided by JustWatch (through TMDB).
- Ratings data from the OMDb API. Air times from TVmaze. Skip markers from TheIntroDB and AniSkip.

### Built With Claude

Dream OS was designed and built with the help of Claude, Anthropic's AI assistant, through
[Claude Code](https://claude.com/claude-code). Every change was reviewed and tested by the maintainer on real
devices. This doesn't mean Anthropic endorses or sponsors the project.

## Disclaimers

Dream OS is an independent open-source project. It hosts, stores and distributes no movies, shows or streams, and
includes no sources. You bring your own TMDB key, debrid accounts and scraper repositories, and you are responsible
for following the law and the terms of the services you use where you live. Dream isn't affiliated with, endorsed
by, or sponsored by Amazon, Apple, TMDB, any streaming service, any debrid service, Anthropic or anyone else it
mentions. It is provided as is, with no warranty. Please read the full [Disclaimers](DISCLAIMER.md).

<p align="center">
  <img alt="" src="docs/assets/mark.png" width="72"><br>
  <sub>Dream OS · Free and open source under GPL-3.0</sub>
</p>
