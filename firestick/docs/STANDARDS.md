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
- **Every rainbow is the cycling color, never a static spectrum** (owner, 2026-10-30: "all should be color cycle"; the
  same on the iPhone and Windows): one hue at a time, fading round the palette on Cinema's shared clock. No hues side
  by side, no sweep or linear gradients across the palette, no flowing spectrum, in fills, rings, rails, bars, badges,
  glows, words or the phone remote page. The logo artwork is the only exception. Use `Rainbow.fade` (a fill with ink),
  `Rainbow.fadeFill` (a dot, badge or bar), `Rainbow.fadeText` (words, lifted by `Rainbow.textOn` only as far as
  4.5:1 on their surface needs) or `Rainbow.redrawOnFade` for a view that draws in `Rainbow.fadeColor()` itself;
  something already animating (the player's buffering arc, the Up Next bar, the intro sparkles, the loading ring's
  RenderThread animation) reads the hue in its own frames instead of joining the clock. Ink on a fill follows the hue
  (`Rainbow.inkOn`: white or `#0A0A0C`). Per-item identity tints (a genre tile's or a profile's color) are not a rainbow.
- **Focus:** Play-type primary actions get the full rainbow fill **at all times** (owner, 2026-10-30, as the iPhone and
  Windows): `Rainbow.fade`, words and icon in `Rainbow.inkOn` (`PLAY_INK` `#0A0A0C`: the fade's darkest moment, the
  violet `#8A5CFF`, gives the pearl ink `#16161A` only 4.34:1; `#0A0A0C` holds 4.76:1). Focused, the rainbow glow and the lift mark them (the fill is
  already the rainbow). Every other button, row, chip or key, when focused, is a **white pearl box with dark ink and a
  rainbow border** (`PearlFocus`). Cards and images get the rainbow border only.
- **Status colors** are allowed only for status: green `#30D158` (light text tint `#6BEB8F`), amber `#FFD60A`
  (`#FFE266`), red `#FF453A` (`#FF8178`). "NOW PLAYING" stays green.
- **The Watched pill (owner-approved, 2026-10-28)** is the one other pill color: gold text and a drawn gold check
  `#E8C468` on a dark graphite-gold capsule `#2A2412` (92%) with a faint gold edge (`#E8C468` at 34%). Same values on
  the iPhone (`Palette.gold` / `goldFill`, `WatchedPill`). Gold is for this pill only, never another accent.
- **Icons are drawn, never text.** No "✓", "✔", "•", "!", "✗" characters as icons. Use `StatusGlyph`
  (`BrandRow.Mark.OK / WARN / BUSY / OFF / PICK`) for status marks and `CheckInk` for bare checks. **Emoji are allowed**
  (owner, 2026-10-29): notification banner icons, profile avatars and the like are fine; don't replace them.
- **Illustrations:** Dream OS line art (`SetupArt`, `GlyphPen`): monoline pearl strokes on a dark-glass disc in a thin
  ring of the fading rainbow. No glossy, 3D, emoji or sticker styles. Exception: the AirPods-in-case art stays as it is.

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
- **Setup text is never cut** (owner, 2026-10-29): in the setup and its shared pages (`SetupUi`, `BrandRow`,
  `SetupParts`), titles, captions, steps and values wrap in full (`lines = 0`), never a line cap with "…".
  `k.label`'s default is one line with an ellipsis, so every label on a setup-reachable page (guides, sign-in
  codes, Trakt, Time Zone, Restore From Backup, QR captions) passes `lines = 0`; drawn text (the TMDB key
  picture) fades at its edge instead of ending in "…". A
  scrolling setup list keeps `SetupScreen.LIFT_H` / `LIFT_V` of room so the last pill's glow isn't cut. Check every
  setup screen at 1080p (960 x 540 dp), the Fire TV Stick HD's size.
- **Setup's shape** (owner, 2026-10-29; spec in `docs/setup-redesign.md`): every step uses the one frame in
  `SetupWizard.Frame` (chapter rail, emblem + "Step n of m" + title, one or two sentences, the body, buttons
  pinned at the bottom). Exactly one primary forward action per screen (never Done beside Continue); focus
  starts on the likely next action and never on one that starts something unasked (a sign-in, an unlock).
  Finished tasks (a key check, a passed check) return or move on by themselves after the result shows. A step
  that can take several (debrid services, scraper repositories) never moves on by itself: a sign-in or an added
  repository returns to the step with Continue focused (2026-10-30). **Nothing scrolls on a setup step at 1080p**: more than fits becomes its own page
  (help is a one-step-per-page walkthrough, Change Settings a hub of short pages); only genuinely long choice
  lists (time zones, languages, all providers) scroll, each on a page of its own. No dropdowns, chips as
  content, or small targets in clusters: a cycling choice is one row that Left/Right steps through.
- **Focus can always reach the action.** A long list never traps focus away from Continue / the page's buttons (picking
  an item or Left/Right moves to the action; if everything on a page is informational, focus starts on Continue).
- **Pickers on the TV are big-row side panels, never small drop-downs** (owner, 2026-10-30). A choice from a list
  (Discover's Movies or TV Shows, Sort By, Genres) may be a picker: a button naming the current choice with a drawn
  chevron that opens a full-height panel on the right (`CinemaPicker`) with the heading and big focusable rows (the
  current one marked with a drawn check, genres with their glyphs). Up / Down move, Select picks and closes, Back
  closes, and focus returns to the button. No menu anchored under the button, no small rows.
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
  `NewReleases.animeClause`, which also excludes TMDB's anime keyword). It's a per-profile setting, on by default
  (Settings › Home & Apps › Keep Anime in Anime Sections, `RowMix.animeApart`): new filter code reads it through
  `RowMix.animeHidden` / `RowMix.tidy`, never `isAnime` alone. Off, anime shows everywhere like any other title.
  Heroes and Spotlights count too: every hero or Spotlight picker outside an anime section filters with
  `RowMix.animeHidden` (or `Tmdb.trendingShown`, which does).
  - What counts as anime (`RowMix.isAnime`, the same rule on Windows and iPhone): animation (genre 16) that is
    Japanese-language; or made in Japan (origin country JP) and not for kids or families (genres 10762 / 10751),
    whatever its language (Cyberpunk: Edgerunners is English on TMDB); or a Chinese or Korean animated series that
    isn't for kids or families. The origin comes from the list result (TMDB's show lists carry `origin_country`) or,
    for movies (whose list results don't), from a details call the app already made (`Tmdb.origin`). Never add a
    per-card details call to learn it.
- **Dubs** (`AnimeDubs`): source badges come only from what release names say; no badge when they say nothing.
  Prefer English Dubs (per profile, on unless the audio language is Japanese) ranks copies carrying the viewer's
  language first within a quality tier for any title whose original language isn't theirs (anime included), never
  above a better tier, a real release or the filters. Audio in another language than the viewer's turns subtitles on
  with a notice unless they chose subtitles in the player for that title.
- **Language pills** (owner, 2026-10-29; `LangPills`, iPhone `LanguagePills`): on the title page, for every title
  whose original language isn't the viewer's audio language (their per-profile setting); their own language shows
  none. Any good copy (no CAM, playable on the device) carrying their language, by its name (DUB, DUAL AUDIO, ENG /
  ENGLISH as audio, "Kor-Eng", MULTI with ENG) or because Dream Player heard it in that copy or title before: "English
  Dub Available" (a name says dub, or English isn't among TMDB's spoken languages), else "English Audio Available",
  green with the status check, and Play picks that copy. None: "<Language> Audio Only" in amber with a drawn speaker (the language the
  copies name most, else TMDB's original), "<A> and <B> Audio", or "Multi Audio" past two, beside "<Language>
  Subtitles" with the check (the viewer's subtitle language not already playing, else their audio language) when a
  release name or Dream Subtitles' index confirms them; a plain "No <Language> Subtitles" only when the index answered
  without them. Nothing shows until the release names are known (never a guess that later flips); answers are kept
  (small, capped) so a title page shows them on its first frame and refreshes behind them. Episode rows: "English
  Dub" / "Japanese Audio" once that episode's copies were read. Informational: never focusable.
- **Kids profiles:** every row, grid, search and title page applies the profile's rating limit (`KidsFilter`,
  `KidsProfiles`).
- **Availability pills only on new titles:** movies released (digital or theatrical, US) in the last ~90 days or
  upcoming; shows only if the show itself premiered within ~12 months or hasn't premiered. Long-running shows never get
  a card pill (their next episode belongs on the show page). Episode pills only for episodes aired in the last 21 days
  (`Availability.recentEpisode()`), so pills expire by themselves. A movie whose main date is 90-240 days old still
  gets its pill when its US digital release is within 90 days or ahead (`Availability.digitalTarget`).
- **A source decides, not the date** (owner, 2026-10-30): a title is available as soon as the index has a copy (HD:
  Available Now; only CAM: CAM Only and the CAM plays), whatever date TMDB lists. Titles still ahead are looked up like
  any other and keep "Coming ..." only while nothing is found; an episode up early is playable on the title page, in the
  player's list and as Up Next. Never change or hide the listed date itself. Details: `docs/bugs/out-early-2026-10-30.md`.
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
- **A new episode is new to the viewer** (owner, 2026-10-30): a show is in New Episodes for You, Coming Up, the New
  Episode banner and its notice, a NEW EPISODE badge, and the general New Episodes rows only while it has an aired
  episode the viewer hasn't reached (in the existing windows). Once they finish its latest aired episode (the last of a
  binge drop, a season or series finale) or a later one, it leaves until a newer one airs. One rule,
  `NewEpisodes.caughtUp`: the `Watched` ledger (Trakt's history included) has that episode or a later one, or the watch
  history is on a later episode (in progress or Up Next), or holds that episode finished or removed from Continue
  Watching. An episode still in progress stays new. The iPhone and Windows apps use the same rule.
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
- **Continue Watching shows where the viewer really is:** the row is rebuilt whenever the history changes, focus
  resting on it included (focus stays on the same title), and a card plays the history as stored when pressed, never
  its own copy. A show never moves back by itself: an older episode's finish, a Trakt paused point or a late scrobble
  never replaces a later episode (`WatchHistory.later`), and an older episode started by hand plays 90 s for real
  before it takes the row back. A queued Trakt paused point the history has moved past is dropped, never sent late.
- **The Up Next card shows only in an episode's last minute** (`PostPlay` `UP_NEXT_LEAD_MS`), whatever the credits
  markers, estimates or fallbacks say, for short and long episodes alike.
- **Where playback starts is the watch history's call** (`PlayFlow.historyStart`), never a spot a page, card or button
  captured earlier: a finished, removed or moved-on title starts from the beginning. A page that shows Resume /
  progress redoes it on `TitlePage.historyChanged` (a details page waits behind the player while a movie ends).
- **The title page's Play button, one rule on all three apps** (owner, 2026-10-30: "the play button should say
  Resume whatever episode you're on"). It goes by the watch history entry Continue Watching uses, the one next-episode
  rule (`PlayFlow.episodeAfter`) and the out-early rule (a source decides, not the date), and the words are what it plays:
  partway through an episode "Resume S2 E4" (a movie: "Resume"), and it resumes there, with Start Over beside it where
  the app has one; an episode finished and the next one out (aired, or up early) "Play S2 E5", a new season included
  (caught up on S1 and S2 E1 is out: "Play S2 E1", `WatchHistory.caughtUpNext`); caught up with nothing newer out, or
  never watched: "Play S1 E1" (a movie: "Play", from the start). A show the viewer took off Continue Watching starts
  fresh ("Play S1 E1"). The label and what Play (and Instant Play's look-ahead) plays always come from the same entry.
- **What the stick can play bounds every pick** (2026-10-30): `DeviceVideo`'s cap (1080p on the Fire TV Stick HD)
  is checked first in `NativeResolver.allowed` and nothing relaxes or bypasses it; 4320p / 8K never passes. A
  preference that lifts the viewer's own Maximum Quality (Prefer HDR, `quality.hdr`) works only on a TV that
  reports HDR, keeps the speed and size limits, and never lifts to a Remux or past the Wi-Fi-friendly size. A
  setting that can do nothing on this TV isn't shown (Prefer HDR on a non-HDR TV).

## 6. Performance budgets (Fire TV Stick 4K: weak GPU, ~1.7 GB RAM)

- Every screen while scrolling or navigating: **janky frames < 10%, p90 ≤ 18 ms, p99 ≤ 35 ms**
  (`adb shell dumpsys gfxinfo com.firehome.launcher reset`, use the screen, then `dumpsys gfxinfo com.firehome.launcher`).
- **Nothing animates per frame while idle.** Animators end; looping animations run only while they mean something
  (a check running, a hero trailer). Animate only alpha/translation/scale, with a hardware layer for big views.
- **The one idle exception: the fade** (owner, 2026-10-30). Every rainbow on screen (Play's fill, the emblem rings,
  setup's chapter rail, the bell badge and gear dot, the New Episode kicker, the intro's words) fades through the hues
  at rest, so it is never fully still. Kept to a budget: it all steps only on Cinema's shared glow clock
  (`CinemaStyle.followFade`), in the frames that re-tint the focus glow anyway, never on a timer or animator of its
  own; resting, that clock steps every `FADE_IDLE_TICK_MS` (125 ms, 8 a second) while anything in the fade shows
  instead of 250 ms. A step is a color set and an invalidate of that one view (`GradientDrawable.setColor`, a span's
  color, a ring's paint), never a layout, a bitmap re-render or an allocation. It skips any view that isn't attached,
  shown and on screen (`getGlobalVisibleRect`), and holds still while the clock is paused (Home in the background or
  behind Dream Player), under the screensaver (Home hidden, `DreamSaver.dreaming`) and while Dream Player shows. Only
  a focused Play where the clock is stopped (the player's Up Next card) steps on its own 66 ms timer; in the player,
  the buffering arc and the Up Next bar take the hue on their own running animations (the arc redraws at most every
  100 ms). Never add another always-on animation on this basis; measure Home idle (hero Play, bell badge) and a setup
  step (emblem + rail) on the Fire TV Stick HD.
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
- **Covers over other apps show Dream OS, never black** (voice search, 2026-10-29): `ScreenCover` draws the window
  itself (Fire OS 7 fails `PixelCopy` of a window), and MainActivity's onResume / onPause must keep reporting to it
  (`screenResumed` / `screenPaused`); without that every cover is the bare black background. Anything that moves
  under a cover rides on it as a `ScreenCover.Live` twin. Check with `PingReceiver --es voice test`.

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
| Choosing from a list (type, order, genre) | `CinemaPicker` in a `CinemaPickerLine` (big-row side panel) |
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

- [ ] Black/gray surfaces, rainbow focus, pearl focused rows; no new accent colors; every rainbow the fading color, no static spectrum.
- [ ] Drawn icons, no text check marks (emoji are allowed); illustrations in Dream OS line art.
- [ ] Focus every element: nothing clipped (all four sides), nothing cut by the screen edge, nothing overlapping.
- [ ] Long lists: `softEdges`, `clipViewport`, and focus can always reach the page's action.
- [ ] Words: Title Case labels, sentence-case descriptions, no hints, no technical terms, no "can't" lists.
- [ ] Content: anime filter, Kids limits, pill rules, `DreamTime` for every date/time.
- [ ] Performance: measured with gfxinfo on the stick within the budgets; nothing animating while idle.
- [ ] No Kodi or Umbrella anywhere, no YouTube as a trailer source (YouTube TV in Where to Watch is fine); no secrets in source.
- [ ] Logged with the `FireHome` tag; deferred bits filed as issues.
