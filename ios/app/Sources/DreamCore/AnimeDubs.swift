import Foundation

/// Anime audio (fire-home AnimeDubs.kt). New anime episodes usually come out in Japanese with subtitles; dubs follow
/// later. Release names only: which copies say they carry a dub in the viewer's audio language (DreamPlayback reads
/// the names, `AnimeDubs.audio`), what a title page and an episode row say before Play ("English Dub" /
/// "Subtitled Only"), and the per-profile Prefer English Dubs switch Dream Core's ranking follows. A name that says
/// nothing counts for nothing: no label is better than a guess.
public enum AnimeDubs {
    public enum Audio: Sendable { case dub, original, none }

    /// How Dream Core ranks a title's copies: no dub preference, dubs first (anything in another language with Prefer
    /// Dubs on), anime with Prefer Dubs off.
    public enum Rank: Sendable { case none, dub, plain }

    /// Anime as the anime sections define it: Japanese animation, and Chinese or Korean animated series that aren't
    /// for kids or families (RowMix.isAnime reads this).
    public static func isAnime(_ t: Title) -> Bool {
        guard t.genreIds.contains(16) else { return false }
        if t.lang == "ja" { return true }
        return t.kind == .tv && ["zh", "cn", "ko"].contains(t.lang) && !t.genreIds.contains(10762) && !t.genreIds.contains(10751)
    }

    // MARK: The setting (per profile, next to the audio and subtitle languages)

    /// The profile's audio language as a 2-letter code (Dream Player's, else the device's).
    public static var want: String {
        (PlaybackSettings.audioLang ?? Locale.current.language.languageCode?.identifier ?? "en").lowercased()
    }

    /// On unless the viewer's audio language is Japanese (the original is already their language).
    public static var preferDubs: Bool {
        get { DreamStore.shared.get(Bool.self, "player.preferDubs", perProfile: true) ?? (want != "ja") }
        set { DreamStore.shared.set(newValue, "player.preferDubs", perProfile: true) }
    }

    /// Japanese audio: nothing to dub, so the setting isn't offered.
    public static var offered: Bool { want != "ja" }

    /// "English" for "en".
    public static func langName(_ code: String?) -> String {
        guard let c = code, !c.isEmpty else { return "Unknown" }
        let n = Locale(identifier: "en_US").localizedString(forLanguageCode: c) ?? c
        return n.prefix(1).uppercased() + n.dropFirst()
    }

    public static var settingLabel: String { "Prefer \(langName(want)) Dubs" }
    /// Sentence case like the other Sources & Quality captions.
    public static var settingCaption: String { "Plays the \(langName(want)) dub when a copy has one" }

    /// Anything whose original language isn't the viewer's plays a copy carrying their language first (within a
    /// quality tier) while Prefer Dubs is on, so Play matches the title page's "English Dub Available" (`LanguagePills`).
    /// Off: anime gets no language preference, a film or show the usual one.
    public static func rank(for t: Title) -> Rank {
        let dubs = preferDubs && want != "ja"
        if isAnime(t) { return dubs ? .dub : .plain }
        let lang = t.lang.lowercased()
        return dubs && !lang.isEmpty && lang != want ? .dub : .none
    }

    // MARK: What the scrapers found, per title / episode

    /// [dub]: copies that say they carry a dub in the viewer's language; [original]: copies that say subtitles or the
    /// original language only.
    public struct Summary: Sendable {
        public let dub: Int, original: Int
        let at: Date
        public init(dub: Int, original: Int) { self.dub = dub; self.original = original; at = Date() }
    }

    private static let ttl: TimeInterval = 6 * 3600
    private static let lock = NSLock()
    nonisolated(unsafe) private static var found: [String: Summary] = [:]

    /// "movie:603" / "tv:1396:1:2" (Dream Core's key).
    public static func key(_ r: PlayRequest) -> String {
        if r.title.kind == .tv, let s = r.season, let e = r.episode { return "tv:\(r.title.id):\(s):\(e)" }
        return "\(r.title.kind.rawValue):\(r.title.id)"
    }

    /// Dream Core read a scrape's names for [key].
    public static func store(_ key: String, _ s: Summary) {
        lock.withLock {
            if found.count >= 400 {
                found = found.filter { Date().timeIntervalSince($0.value.at) < ttl }
                // All still fresh: the oldest go, so it never grows past 400.
                if found.count >= 400 {
                    for k in found.sorted(by: { $0.value.at < $1.value.at }).prefix(found.count - 300).map(\.key) { found[k] = nil }
                }
            }
            found[key] = s
        }
    }

    public static func known(_ key: String) -> Summary? {
        lock.withLock { found[key].flatMap { Date().timeIntervalSince($0.at) < ttl ? $0 : nil } }
    }

    /// "English Dub" when a copy says it has one, "Subtitled Only" when copies say subtitles and none says a dub, else nil.
    public static func label(_ s: Summary?) -> String? {
        guard let s, want != "ja" else { return nil }
        if s.dub > 0 { return "\(langName(want)) Dub" }
        if s.original > 0 { return "Subtitled Only" }
        return nil
    }

    /// [r]'s label when it's anime and Dream Core has read its copies; nil otherwise.
    public static func label(for r: PlayRequest) -> String? {
        isAnime(r.title) ? label(known(key(r))) : nil
    }
}
