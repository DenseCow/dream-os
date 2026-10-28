// swift-tools-version: 6.0

import PackageDescription

// Dream OS for iPhone. One library product (xtool's rule): the app, DreamOS.
// Modules, each owned by one area of the port (see docs/PORT.md):
//   DreamCore      shared models, service contracts, networking, storage, time (the contract every module builds on)
//   DreamUI        the Dream Cinema look for touch: colors, rainbow, pearl press, cards, rows, pills, glyphs
//   DreamContent   TMDB catalog, rows, filters (anime, kids), ratings, availability, title info
//   DreamPlayback  Dream Core Resolver, debrid accounts, play decisions, watch history, My List, profiles
//   DreamScrapers  user scraper repositories run in embedded Python
//   DreamPlayer    Dream Player: engine, controls, subtitles, skip, post-play
//   DreamOS        the app: tabs, pages, details, settings, setup
let package = Package(
    name: "DreamOS",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "DreamOS", targets: ["DreamOS"]),
    ],
    dependencies: [
        // Dream Player's engine: libmpv + FFmpeg as static xcframeworks (LGPL build; docs/port/player.md).
        // Its frameworks are static archives: tools/install.sh must strip them from the .app (see player.md).
        .package(url: "https://github.com/mpvkit/MPVKit.git", exact: "1.1.0-n9.0.2"),
        // Trailer streams from a TMDB video key, extracted on the device (behind DreamPlayer's
        // TrailerStreamResolver; never named on screen).
        .package(url: "https://github.com/alexeichhorn/YouTubeKit.git", exact: "0.4.9"),
    ],
    targets: [
        .target(name: "DreamCore"),
        .target(name: "DreamUI", dependencies: ["DreamCore"]),
        .target(name: "DreamContent", dependencies: ["DreamCore"]),
        .target(name: "DreamPlayback", dependencies: ["DreamCore"]),
        // Embedded CPython 3.13 for the scraper modules (third-party files in Vendor/Python; docs/port/scrapers.md).
        .binaryTarget(name: "Python", path: "Vendor/Python/Python.xcframework"),
        .target(name: "DreamPythonStdlib", path: "Vendor/Python/Stdlib", resources: [.copy("python")]),
        .target(name: "CDreamPython", dependencies: ["Python"]),
        .target(
            name: "DreamScrapers",
            dependencies: ["DreamCore", "CDreamPython", "DreamPythonStdlib"],
            resources: [.copy("Python")]
        ),
        .target(name: "DreamPlayer", dependencies: ["DreamCore", "DreamUI", .product(name: "MPVKit", package: "MPVKit"),
                                                  .product(name: "YouTubeKit", package: "YouTubeKit")]),
        .target(
            name: "DreamOS",
            dependencies: ["DreamCore", "DreamUI", "DreamContent", "DreamPlayback", "DreamScrapers", "DreamPlayer"]
        ),
    ]
)
