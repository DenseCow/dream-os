import Foundation

/// The title page's language pills (fire-home LangPills.kt; owner, 2026-10-29), for every language, movies, shows and
/// anime alike. They show only when the title's original language isn't the viewer's audio language (their own
/// setting, per profile).
///  - Their language available, green with the check: when any good copy (no CAM, playable on this phone) carries it, by
///    its release name (DUB, DUAL AUDIO, ENG / ENGLISH as audio, "Kor-Eng", MULTI with ENG) or because Dream Player
///    heard it in that copy or this title before (`heard`). "English Dub Available" when it's a dub (a name says dub, or
///    English isn't among TMDB's spoken languages), else "English Audio Available". Play picks that copy
///    (`AnimeDubs.rank`, Prefer Dubs).
///  - Audio, amber: "Korean Audio Only", "Hindi and Tamil Audio" or "Multi Audio" when no good copy does, beside
///    "English Subtitles" with the check when subtitles in the viewer's subtitle language (else audio language) are
///    confirmed by a release name or the subtitle index; plain "No English Subtitles" only when the index answered
///    without them.
/// Nothing shows until something is known: never a guess that later flips. The answers are kept (400 titles, a week;
/// what the player heard, two months) with the availability surveys feeding them, so a title page shows them on its
/// first frame and refreshes behind them. DreamPlayback reads the names (`AnimeDubs.record`), DreamUI draws the pills.
public enum LanguagePills {
    public enum Kind: String, Sendable { case audio, dub, subs, noSubs }

    /// One pill: its words, longest first (a tight line takes the shorter form).
    public struct Pill: Sendable, Hashable, Identifiable {
        public let kind: Kind
        public let labels: [String]
        public var id: String { kind.rawValue }
        public init(kind: Kind, labels: [String]) { self.kind = kind; self.labels = labels }
    }

    /// One title's (or episode's) good copies read for the viewer language `want`: `match` carry it (`dubSaid` of them by
    /// a name that says dub), `unnamed` name no audio language (the original), `top` is the audio-language set named most
    /// often (`topCount` copies), `subs` the subtitle languages named; `index` the subtitle index's languages once asked,
    /// `orig` / `spoken` TMDB's original and spoken languages.
    struct Entry: Codable, Sendable {
        var want: String, total: Int, match: Int, unnamed: Int
        var top: [String], topCount: Int, subs: [String]
        var index: [String]?, orig: String?
        var at: Date
        var dubSaid: Int?
        var spoken: [String]?
    }

    /// Audio languages Dream Player saw in a stream (`heard`), by title / episode key and by the copy's info hash.
    struct Heard: Codable, Sendable { var langs: [String]; var at: Date }

    private struct Saved: Codable {
        var entries: [String: Entry]
        var viewerSubs: [String]?
        var heardKeys: [String: Heard]?
        var heardHashes: [String: Heard]?
    }

    private static let storeKey = "languagePills.v1"
    private static let keep = 400
    private static let maxAge: TimeInterval = 7 * 86_400
    /// What Dream Player heard is kept longer: a copy's tracks don't change.
    private static let heardAge: TimeInterval = 60 * 86_400
    /// A title page asks the scrapers again once its answer is older than this.
    private static let fresh: TimeInterval = 6 * 3600

    private static let lock = NSLock()
    nonisolated(unsafe) private static var found: [String: Entry] = [:]
    nonisolated(unsafe) private static var heardKeys: [String: Heard] = [:]
    nonisolated(unsafe) private static var heardHashes: [String: Heard] = [:]
    /// The viewer's subtitle languages as last read, so a first frame needs nothing else.
    nonisolated(unsafe) private static var viewerSubs: [String]?
    nonisolated(unsafe) private static var loaded = false
    nonisolated(unsafe) private static var saving = false

    /// Reads the saved answers in the background (launch), so the first title page has them in memory.
    public static func warm() { Task.detached(priority: .utility) { load() } }

    private static func load() {
        if lock.withLock({ loaded }) { return }
        let s = DreamStore.shared.get(Saved.self, storeKey)
        lock.withLock {
            guard !loaded else { return }
            let old = Date().addingTimeInterval(-maxAge), heardOld = Date().addingTimeInterval(-heardAge)
            for (k, e) in s?.entries ?? [:] where e.at > old && found[k] == nil { found[k] = e }
            for (k, h) in s?.heardKeys ?? [:] where h.at > heardOld && heardKeys[k] == nil { heardKeys[k] = h }
            for (k, h) in s?.heardHashes ?? [:] where h.at > heardOld && heardHashes[k] == nil { heardHashes[k] = h }
            if viewerSubs == nil { viewerSubs = s?.viewerSubs }
            loaded = true
        }
    }

    /// The newest `keep` of `m` (callers hold the lock).
    private static func newest<T>(_ m: [String: T], _ at: (T) -> Date) -> [String: T] {
        guard m.count > keep else { return m }
        return Dictionary(uniqueKeysWithValues: m.sorted { at($0.value) > at($1.value) }.prefix(keep).map { ($0.key, $0.value) })
    }

    /// Writes a few seconds after the last change (one write for a burst of surveys).
    private static func save() {
        let go = lock.withLock { () -> Bool in
            guard !saving else { return false }
            saving = true
            return true
        }
        guard go else { return }
        Task.detached(priority: .utility) {
            try? await Task.sleep(for: .seconds(3))
            let snapshot = lock.withLock { () -> Saved in
                saving = false
                let old = Date().addingTimeInterval(-maxAge)
                return Saved(entries: newest(found.filter { $0.value.at > old }, \.at), viewerSubs: viewerSubs,
                             heardKeys: newest(heardKeys, \.at), heardHashes: newest(heardHashes, \.at))
            }
            DreamStore.shared.set(snapshot, storeKey)
        }
    }

    /// Dream Core read a scrape's good copies for `key` (DreamPlayback `AnimeDubs.record`).
    public static func record(_ key: String, want: String, total: Int, match: Int, unnamed: Int,
                              top: Set<String>, topCount: Int, subs: Set<String>, dubSaid: Int = 0) {
        load()
        lock.withLock {
            let was = found[key].flatMap { $0.want == want ? $0 : nil }
            found[key] = Entry(want: want, total: total, match: match, unnamed: unnamed, top: top.sorted(), topCount: topCount,
                               subs: subs.sorted(), index: was?.index, orig: was?.orig, at: Date(), dubSaid: dubSaid, spoken: was?.spoken)
            if found.count > keep + keep / 4 { found = newest(found, \.at) }
        }
        save()
    }

    /// Dream Player heard `lang` in the copy with info hash `hash` before (Dream Core ranks it as the dub).
    public static func heardIn(_ hash: String, _ lang: String) -> Bool {
        load()
        return lock.withLock { heardHashes[hash.lowercased()]?.langs.contains(lang) == true }
    }

    /// Dream Player started a stream for `key` (its first frame): the audio languages its tracks carry are kept for the
    /// title / episode and, when known, the copy's info hash, so the next page open and the ranking know them.
    public static func heard(_ key: String, hash: String?, langs: Set<String>) {
        guard !langs.isEmpty else { return }
        load()
        lock.withLock {
            heardKeys[key] = Heard(langs: langs.union(heardKeys[key]?.langs ?? []).sorted(), at: Date())
            if let h = hash?.lowercased(), !h.isEmpty { heardHashes[h] = Heard(langs: langs.sorted(), at: Date()) }
            if heardKeys.count > keep + keep / 4 { heardKeys = newest(heardKeys, \.at) }
            if heardHashes.count > keep + keep / 4 { heardHashes = newest(heardHashes, \.at) }
        }
        save()
    }

    private static func heardKey(_ key: String, _ want: String) -> Bool {
        lock.withLock { heardKeys[key]?.langs.contains(want) == true }
    }

    private static func entry(_ key: String, want: String) -> Entry? {
        lock.withLock { found[key].flatMap { $0.want == want && Date().timeIntervalSince($0.at) < maxAge ? $0 : nil } }
    }

    // MARK: What the title page adds

    /// Ask the scrapers again: nothing known for `key`, or the answer is older than `fresh`.
    public static func needsRefresh(_ key: String, want: String) -> Bool {
        load()
        guard let e = entry(key, want: want) else { return true }
        return Date().timeIntervalSince(e.at) > fresh
    }

    /// TMDB's original and spoken languages for `key`. Only Dream Player's ears know (no copies read): an empty entry
    /// keeps them for the first frame.
    public static func noteTitle(_ key: String, want: String, original: String, spoken: [String]) {
        guard heardKey(key, want) || entry(key, want: want) != nil else { return }
        lock.withLock {
            var e = found[key].flatMap { $0.want == want ? $0 : nil }
                ?? Entry(want: want, total: 0, match: 0, unnamed: 0, top: [], topCount: 0, subs: [], index: nil, orig: nil, at: Date())
            e.orig = original
            if !spoken.isEmpty { e.spoken = spoken }
            found[key] = e
        }
        save()
    }

    public static func noteViewerSubs(_ langs: [String]) {
        let changed = lock.withLock { () -> Bool in
            guard viewerSubs != langs else { return false }
            viewerSubs = langs
            return true
        }
        if changed { save() }
    }

    public static func noteIndex(_ key: String, _ langs: Set<String>) {
        lock.withLock { found[key]?.index = langs.sorted() }
        save()
    }

    /// The subtitle index is worth asking for `key`: copies were read, none carries the viewer's language, and no
    /// release name already confirms the subtitle language.
    public static func subtitleToCheck(_ key: String, original: String, want: String) -> Bool {
        guard let e = entry(key, want: want), e.total > 0, e.match == 0, !heardKey(key, want) else { return false }
        let langs = audioLangs(e, orig: original, want: want)
        let sub = subLanguage(lock.withLock { viewerSubs } ?? [], langs, want)
        return !e.subs.contains(plain(sub))
    }

    // MARK: The rule

    /// "French" for "fr", in English like the rest of Dream; nil for a code with no known name.
    public static func name(_ code: String) -> String? {
        switch code {
        case "pob": return "Brazilian Portuguese"
        case "cn": return "Cantonese"
        default:
            guard let n = Locale(identifier: "en_US").localizedString(forLanguageCode: code), !n.isEmpty,
                  n.lowercased() != code.lowercased() else { return nil }
            return n.prefix(1).uppercased() + n.dropFirst()
        }
    }

    /// TMDB's original language as a usable code ("xx" and unknown codes give nil).
    public static func original(_ lang: String?) -> String? {
        guard let c = lang?.lowercased(), !c.isEmpty, name(c) != nil else { return nil }
        return c
    }

    /// "pob" plays as Portuguese.
    public static func plain(_ code: String) -> String { code == "pob" ? "pt" : code }

    /// The audio languages the copies carry when none carries the viewer's: the set named most, else the original.
    private static func audioLangs(_ e: Entry, orig: String, want: String) -> [String] {
        let langs: Set<String> = e.topCount > e.unnamed && !e.top.isEmpty ? Set(e.top).subtracting([want]) : [orig]
        return langs.filter { name($0) != nil }.sorted { a, b in
            if a == orig { return b != orig }
            if b == orig { return false }
            return (name(a) ?? a) < (name(b) ?? b)
        }
    }

    private static func audioWords(_ langs: [String], only: Bool) -> [String] {
        let names = langs.compactMap(name)
        switch names.count {
        case 0: return []
        case 1: return only ? ["\(names[0]) Audio Only", "\(names[0]) Audio"] : ["\(names[0]) Audio"]
        case 2: return ["\(names[0]) and \(names[1]) Audio"]
        default: return ["Multi Audio"]
        }
    }

    /// The viewer's first subtitle language not already playing, else their audio language.
    private static func subLanguage(_ prefs: [String], _ langs: [String], _ want: String) -> String {
        prefs.first { !langs.contains(plain($0)) } ?? want
    }

    /// A dub, not the original: a name says dub, or the viewer's language isn't among TMDB's spoken languages.
    private static func isDub(_ e: Entry?, _ want: String) -> Bool {
        (e?.dubSaid ?? 0) > 0 || e?.spoken.map { !$0.contains(want) } == true
    }

    /// `e` may be empty (total 0) when only Dream Player's ears know.
    private static func verdict(_ key: String, _ e: Entry?, orig: String, want: String) -> [Pill] {
        guard let mine = name(want) else { return [] }
        if (e?.match ?? 0) > 0 || heardKey(key, want) {
            let what = isDub(e, want) ? "Dub" : "Audio"
            return [Pill(kind: .dub, labels: ["\(mine) \(what) Available", "\(mine) \(what)"])]
        }
        guard let e, e.total > 0 else { return [] } // no copies read: no guess at their audio
        let langs = audioLangs(e, orig: orig, want: want)
        let words = audioWords(langs, only: true)
        guard !words.isEmpty else { return [] }
        let audio = Pill(kind: .audio, labels: words)
        guard let prefs = lock.withLock({ viewerSubs }) else { return [audio] }
        let sub = subLanguage(prefs, langs, want)
        guard let n = name(sub) else { return [audio] }
        if e.subs.contains(plain(sub)) || e.index?.contains(sub) == true { return [audio, Pill(kind: .subs, labels: ["\(n) Subtitles"])] }
        if e.index != nil { return [audio, Pill(kind: .noSubs, labels: ["No \(n) Subtitles"])] }
        return [audio]
    }

    /// The pills for `key` from what's known, no network: empty when the title is in the viewer's own language, nil
    /// when nothing is known yet (show nothing).
    public static func pills(_ key: String, original: String?, want: String) -> [Pill]? {
        load()
        let e = entry(key, want: want)
        guard let orig = Self.original(original) ?? e?.orig else { return nil }
        if orig == want { return [] }
        guard e != nil || heardKey(key, want) else { return nil }
        return verdict(key, e, orig: orig, want: want)
    }

    /// An episode row's compact words ("English Dub", "English Audio", "Japanese Audio"), the same rule, once that
    /// episode's copies were read or it played; nil otherwise.
    public static func episodeWords(_ key: String, original: String?, want: String) -> String? {
        let e = entry(key, want: want)
        guard e != nil || heardKey(key, want), let orig = Self.original(original) ?? e?.orig, orig != want else { return nil }
        if (e?.match ?? 0) > 0 || heardKey(key, want) { return name(want).map { "\($0) \(isDub(e, want) ? "Dub" : "Audio")" } }
        guard let e, e.total > 0 else { return nil }
        return audioWords(audioLangs(e, orig: orig, want: want), only: false).first
    }
}
