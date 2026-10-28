# Dream OS standards

The rules every screen, feature and fix must follow. They come from the owner's reviews on the TV; each one exists
because something broke or looked wrong before. Check new work against the **checklist at the end** before it ships.

## 1. Product

- **Dream Cinema is the only theme.** Build nothing for other layouts. Custom/downloadable themes are a future feature
  (a future idea); don't add theme pickers or per-user colors.
- **Standalone and open source.** Dream OS ships no content and no sources. Users connect their own debrid accounts
  and add their own scraper repositories (e.g. CocoScrapers, run in embedded Python). Kodi and Umbrella are gone; never
  reintroduce Kodi code paths, wording, or hand-offs.
- **Never show YouTube as the trailer source.** Trailers play inside Dream Player (TrailerEngine); no "YouTube" text
  for them anywhere, and YouTube's movie store stays out of Where to Watch. Exception (owner, 2026-10-28): **YouTube
  TV** is a real streaming service and shows in Where to Watch and Also Streaming On like any other.
- **Never borrow another app's identity or API keys** (e.g. a Premiumize client id). Each service gets Dream OS's own
  ("DreamOS" agent/app name) or the user's own key.
- **No secrets in source.** Keys live in `local.properties` (ignored by git); `local.properties.example` lists them.

## 2. Look (brand)

- **Colors:** black and dark neutral grays only (page `#000000`→`#050506`, smooth, never banded). No purple, navy or
  other tints, including window backgrounds (`res/drawable/bg.xml` is near-black).
- **The rainbow is the only accent:** `Rainbow.fade` / `Rainbow.fadeColor()`, `CinemaStyle` glow helpers.
- **Focus:** Play-type primary actions get the full rainbow fill. Every other button, row, chip or key, when focused, is
  a **white pearl box with dark ink and a rainbow border** (`PearlFocus`). Cards and images get the rainbow border only.
- **Status colors** are allowed only for status: green `#30D158` (light text tint `#6BEB8F`), amber `#FFD60A`
  (`#FFE266`), red `#FF453A` (`#FF8178`). "NOW PLAYING" stays green.
- **The Watched pill (owner-approved, 2026-10-28)** is the one other pill color: gold text and a drawn gold check
  `#E8C468` on a dark graphite-gold capsule `#2A2412` (92%) with a faint gold edge (`#E8C468` at 34%). Same values on
  the iPhone (`Palette.gold` / `goldFill`, `WatchedPill`). Gold is for this pill only, never another accent.
- **Icons are drawn, never text.** No "✓", "✔", "•", "!", "✗" characters as icons, and no emoji as UI icons. Use
  `StatusGlyph` (`BrandRow.Mark.OK / WARN / BUSY / OFF / PICK`) for status marks and `CheckInk` for bare checks.
- **Illustrations:** Dream OS line art (`SetupArt`, `GlyphPen`): monoline pearl strokes on a dark-glass disc in a thin
  rainbow ring. No glossy, 3D, emoji or sticker styles. Exception: the AirPods-in-case art stays as it is.

## 3. Layout (things that must never happen)

- **Nothing is clipped.** A focused item lifts and glows; every container on its path must leave room
  (`clipChildren`/`clipToPadding` false, `Ui.unclip`, `Ui.liftRoom`, room inside the scroller's content, not just padding
  on the scroller). Check top, bottom, left and right, in rows, grids, sheets and player cards.
- **Nothing is cut by the screen edge.** Rows keep Home's right gutter; a partial last card must read as an intentional
  peek. The focused card always scrolls fully into view.
- **Nothing overlaps.** Scrolling lists stop at their own viewport (`SmoothScrollView.clipViewport`) and never draw over
  headers, titles or profile chips.
- **Nothing touches.** Real spacing everywhere: icon-to-text gaps of at least 8 dp, an en space after glyphs like ☰,
  pills apart from other card elements.
- **Soft edges, never hard cuts:** a list running past its panel melts into the panel with
  `SmoothScrollView.softEdges(...)`. Never use Android's fading edges (`setFadingEdgeLength`): they cost an offscreen
  layer every frame on this GPU.
- **No reserved empty gaps:** a row without an icon doesn't keep an empty icon slot (`Mark.NONE` is GONE). In lists where
  some rows have marks, use `OFF` / `PICK` so rows line up.
- **Text never breaks badly:** names and titles stay on one line with an ellipsis (no mid-word hyphenation; set
  `hyphenationFrequency = NONE`); captions and poster titles may use two lines; bios end on a full sentence.
- **Focus can always reach the action.** A long list never traps focus away from Continue / the page's buttons (picking
  an item or Left/Right moves to the action; if everything on a page is informational, focus starts on Continue).
- **Top tab bar:** no focus bubble; a focused/current tab is its bright, bold label with the rainbow line centered under
  the label text.
- **Pills on cards:** bottom-left on posters (inset ≥ 6 dp), inside the text column on wide cards that overlay text,
  never on top of text or a title logo.

## 4. Words

- **Professional, product-page tone** (Apple TV / Netflix): confident, short, plain words. No slang, no filler
  ("just", "simply", "basically"), no "our own …".
- **Title Case** for labels, buttons, headings and settings rows; sentence case for descriptions and explanations.
- **No remote hints** ("OK to open", "BACK to close"). If an instruction is needed, write a sentence that calls the
  center button "Select".
- **No technical wording** in anything a viewer sees: no HTTP codes, "API", "JSON", "module", stack traces. Errors say
  what happened and what to do ("Couldn't reach Trakt. Try again.").
- **Positive framing:** describe what a feature does, not what it can't do (e.g. the AirPods page lists no "can't" lines).
- **Legal credits** (e.g. TMDB attribution) are full sentences, never truncated. Where-to-watch data (TMDB's, from
  JustWatch) carries a small dim "Streaming Availability From JustWatch" credit where it shows (title page
  Information, Also Streaming On) and in Settings › About.

## 5. Content rules

- **Anime only in Anime sections** (the Anime page/chip, Crunchyroll, and personal rows like Continue Watching / My
  List). Every row loader and every Discover query goes through the shared anime filter (`RowMix` / `RowClaims`,
  `NewReleases.animeClause`, which also excludes TMDB's anime keyword).
- **Kids profiles:** every row, grid, search and title page applies the profile's rating limit (`KidsFilter`,
  `KidsProfiles`).
- **Availability pills only on new titles:** movies released (digital or theatrical, US) in the last ~90 days or
  upcoming; shows only if the show itself premiered within ~12 months or hasn't premiered. Long-running shows never get
  a card pill (their next episode belongs on the show page). Episode pills only for episodes aired in the last 21 days
  (`Availability.recentEpisode()`), so pills expire by themselves. A movie whose main date is 90-240 days old still
  gets its pill when its US digital release is within 90 days or ahead (`Availability.digitalTarget`).
- **One fixed card label per state:** "Available Now", "CAM Only", "Coming Nov 14" (date via `DreamTime`), "Not Available
  Yet" (out, but no copy yet), "Coming Soon" (not out, no date). Short forms on narrow cards ("Available", "CAM",
  "Nov 14", "Soon"); never an icon-only pill. Quality detail belongs on the title page and in the player badges.
- **Watched pill** ("Watched", the word alone on a narrow card): on any title card, title page and episode card the
  viewer has watched, old titles included: a finished movie (`WatchHistory.FINISHED`, or Trakt's history); an episode
  only if the viewer actually finished that episode (kept one by one per profile; nothing is inferred from a later
  one, so starting at S3E5 marks nothing else); a show only when every aired episode is finished and nothing newer has
  aired. It replaces the availability pill. A new episode takes it away (the NEW EPISODE badge or pill
  takes over); starting a finished title again keeps it. Never on personal rows (Continue Watching, New Episodes for
  You) or on an episode in progress. Read from the local ledger (`Watched`), never waiting on the network.
- **Rows:** New Releases = new movies + brand-new shows; New Episodes = returning shows (no pills); In Theaters = recent
  theatrical releases only (`NewReleases.inTheaters`), not re-releases.
- **All times and dates** go through `DreamTime` (the user's time zone and 12/24-hour choice). Date-only TMDB dates never
  shift a day.
- **Scores:** the Rotten Tomatoes tomato, then the audience score exactly as published (`Ratings.audienceFor` /
  `audienceBadge`: "IMDb 6.5", else "TMDB 7.1" after 150 votes). No stars anywhere: rounding into stars read harsher
  than the real number.
- **Top 10 Today** is what's most watched today (TMDB daily trending), not a quality ranking; keep its order. Only
  titles out today (`RowMix.released`): a chart lists films months ahead of release; they belong in Coming Soon. The
  same goes for Trending Now, the hero, the Spotlight and a genre's trending shelf.
- **Taste:** discovery rows are ranked by `Taste` (list order, taste, steadied quality, freshness; franchises spread);
  taste comes from finished titles, My List, Trakt and opened pages, **never from started-but-unfinished titles**, and
  a title removed from Continue Watching before it was finished seeds nothing (taste, Because You Watched, Top Picks).
  Charts, Coming Soon, In Theaters and the viewer's own rows keep their order (`Taste.ranks`).
- **Plainly bad titles** (under 5.0 with 100+ votes, `RowMix.poorlyRated`) never make a discovery row.
- **Continue Watching is real watching:** a title joins it only after 90 s of actual playback in Dream Player (seeks
  and skips add nothing, `DreamPlayerActivity.realWatchedMs`); one already on the row keeps saving as before.
- **Where playback starts is the watch history's call** (`PlayFlow.historyStart`), never a spot a page, card or button
  captured earlier: a finished, removed or moved-on title starts from the beginning. A page that shows Resume /
  progress redoes it on `TitlePage.historyChanged` (a details page waits behind the player while a movie ends).

## 6. Performance budgets (Fire TV Stick 4K: weak GPU, ~1.7 GB RAM)

- Every screen while scrolling or navigating: **janky frames < 10%, p90 ≤ 18 ms, p99 ≤ 35 ms**
  (`adb shell dumpsys gfxinfo com.firehome.launcher reset`, use the screen, then `dumpsys gfxinfo com.firehome.launcher`).
- **Nothing animates per frame while idle.** Animators end; looping animations run only while they mean something
  (a check running, a hero trailer). Animate only alpha/translation/scale, with a hardware layer for big views.
- **No offscreen layers in scrolling content** (no fading edges, no `withLayer()` on large views during scroll).
- **No main-thread network or disk work.** TMDB/scraper/debrid calls run on background threads, cached.
- **Heavy work waits:** the scraper engine's Python start, availability sweeps and speculative lookups never run during
  the boot intro or playback; they pause while the player is active (`PlaybackTrim`, `Availability`).
- **Memory:** keep Dream OS's PSS well under ~300 MB during playback; trim caches when playback starts.
- **TMDB queries:** `Tmdb.url` already adds `language`, `region` and the page number; never add them again in a query
  (a repeated parameter makes TMDB answer 400 and the row silently disappears). Every TMDB request goes through
  `Net.getText` (paced at 35 a second by `TmdbGate`, a 429 retried after its pause); never open your own connection.
- **Page dates** (daily pages, Because You Watched seeds, "new" windows, year floors) go through `DreamTime` and are
  computed per call, never held in a `val` from class load (the launcher runs for weeks).
- **Card art never sits blank:** every card passes its next art choices as fallbacks (`CinemaArt.source`); a stalled
  download gives up in 6 s and after 1.5 s of downloading the next choice is asked too (`Net.image`, `HEDGE_MS`).

## 7. Building blocks to reuse (don't reinvent)

| Need | Use |
|---|---|
| Setup / settings row with status | `BrandRow` (`DreamSetupPages.kt`), `SetupUi` rows (`SetupUi.kt`) |
| Status mark / check | `StatusGlyph`, `CheckInk` |
| Section heading with Required/Optional | `SetupParts.section` |
| Text entry with phone typing + QR | `setupEntry` (`DreamSetupPages.kt`), `PhoneTyping` |
| Full-screen settings page | `DreamSettingsPage` / `SetupPanel` + `SetupScreen` |
| Illustration | `SetupArt` / `GlyphPen` |
| Focus look | `PearlFocus`, `CinemaStyle`, `Rainbow` |
| Scrolling list | `SmoothScrollView` with `softEdges` and `clipViewport` |
| Availability pill | `Availability.Pill` (never draw your own) |
| Watched pill / state | `Availability.Pill.watched(true)`, `Availability.attachWatched`; state from `Watched` |
| Dates and times | `DreamTime` |
| Notices / banners | `DreamNotices` (sound, smile animation, in-place updates) |
| Playing a title | `PlayFlow` → Dream Core (`NativeResolver`) → Dream Player |

## 8. Process

- **Git:** local repo. Commit a checkpoint before big changes; never commit `local.properties`, keystores or APKs.
- **Releases:** one version for both apps. `tools/set-version.sh <version>` here (`dreamVersion` in
  `app/build.gradle.kts`) and the same command in DreamOS-iOS (its `app/Info.plist`); commit both. Notes in
  `docs/releases/<version>.md`, then dream-os-public's `tools/release.sh` (signed with the release key; back up
  `~/.android/dreamos-release.*`). It refuses to publish while the two versions differ, and marks what it builds
  PUBLIC (every other build shows "(DEV)" after its version).
- **Versions on screen** always come from the build (`BuildConfig.VERSION_NAME` / `VERSION_CODE` /
  `BUILD_CHANNEL`; on iPhone the bundle), never a number written in code or art.
- **Bugs and ideas:** GitHub issues.
- **Edits:** small, targeted edits; no bulk rewrite scripts across many files; keep LF line endings.

## 9. Checklist for every new screen or feature

- [ ] Black/gray surfaces, rainbow focus, pearl focused rows; no new accent colors.
- [ ] Drawn icons only (no text check marks or emoji); illustrations in Dream OS line art.
- [ ] Focus every element: nothing clipped (all four sides), nothing cut by the screen edge, nothing overlapping.
- [ ] Long lists: `softEdges`, `clipViewport`, and focus can always reach the page's action.
- [ ] Words: Title Case labels, sentence-case descriptions, no hints, no technical terms, no "can't" lists.
- [ ] Content: anime filter, Kids limits, pill rules, `DreamTime` for every date/time.
- [ ] Performance: measured with gfxinfo on the stick within the budgets; nothing animating while idle.
- [ ] No Kodi or Umbrella anywhere, no YouTube as a trailer source (YouTube TV in Where to Watch is fine); no secrets in source.
- [ ] Logged with the `FireHome` tag; deferred bits filed as issues.
