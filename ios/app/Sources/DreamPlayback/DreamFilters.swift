import Foundation
import DreamCore
import AVFoundation
import Network

/// Dream OS's own source filters (DreamStore.kt `DreamFilters`): quality cap, Hide AV1 / HEVC / HDR / Dolby Vision /
/// CAM, size limits, HDR / HEVC preference and subtitle languages. Dream Core reads them here (`current`);
/// Settings › Sources & Quality writes them (`put`). Keys are `keys`, plus three the UI doesn't show:
/// hide.avc, hide.3d, hide.upscaled.
public enum DreamFilters {
    private static let q = ["4k", "1080p", "720p", "sd"]

    /// The option keys Settings shows.
    public static let keys: Set<String> = [
        "quality.max", "quality.min", "quality.hdr", "quality.cellular", "quality.cellular.mode", "hide.cam", "hide.hdr", "hide.dv", "hide.hevc", "hide.av1", "prefer.hdr", "prefer.hevc",
        "size.movie.limit", "size.movie.min_gb", "size.movie.max_gb", "size.movie.speed_mbps",
        "size.episode.limit", "size.episode.min_gb", "size.episode.max_gb", "size.episode.speed_mbps",
        "subs.enabled", "subs.lang1", "subs.lang2", "subs.embedded", "subs.hi", "subs.forced",
    ]
    /// Keys the resolver honors that Settings doesn't show.
    private static let extra: Set<String> = ["hide.avc", "hide.3d", "hide.upscaled", "quality.cellular.ask2"]

    /// The filters as the resolver applies them. Missing values fall back to safe defaults.
    public struct Values: Sendable {
        let v: [String: String]
        /// [saver]: Cellular Data Saver may apply (the viewer's own filters; never Search All Sources).
        init(_ v: [String: String], saver: Bool = false) {
            self.v = v
            let s = v["quality.hdr"] ?? ""
            hdrAboveCap = (s.isEmpty ? HDRPreference.screenShowsHDR : s == "true") && HDRPreference.heldBack == nil
            cellCap = saver ? CellularSaver.cap(v) : nil
        }
        /// Prefer HDR applies to this Play: an HDR copy may go above Maximum Quality (`HDRPreference`).
        public let hdrAboveCap: Bool
        /// Cellular Data Saver applies to this lookup (metered network, a limit chosen): 1 1080p, 2 720p. Copies above it
        /// rank after every copy within it (smallest first), and smaller files win ties (`CellularSaver`).
        public let cellCap: Int?
        private func on(_ k: String, _ def: Bool) -> Bool { let s = v[k] ?? ""; return s.isEmpty ? def : s == "true" }
        /// A quality cap was ever chosen; without one the resolver ranks 1080p first.
        public var known: Bool { !(v["quality.max"] ?? "").isEmpty }
        /// 0 4K, 1 1080p, 2 720p, 3 SD: anything better is dropped.
        public var cap: Int { DreamFilters.q.firstIndex(of: v["quality.max"] ?? "") ?? 0 }
        /// SD only goes when something better exists.
        public var hideSd: Bool { let s = v["quality.min"] ?? ""; return s.isEmpty ? true : s != "sd" }
        /// Not an option on iPhone (owner, 2026-10-28): CAM copies are always passed over while a real copy exists; the
        /// relaxed filters still offer one (with the CAM Only note) when it's all there is.
        public var hideCam: Bool { true }
        public var hideHdr: Bool { on("hide.hdr", false) }
        public var hideDv: Bool { on("hide.dv", false) }
        public var hideHevc: Bool { on("hide.hevc", false) }
        /// Kept as on the Fire Stick (10-bit AV1 crashed its display driver); the viewer can turn it off.
        public var hideAv1: Bool { on("hide.av1", true) }
        public var hideAvc: Bool { on("hide.avc", false) }
        public var hide3d: Bool { on("hide.3d", true) }
        public var hideUpscaled: Bool { on("hide.upscaled", false) }
        public var preferHdr: Bool { on("prefer.hdr", false) }
        public var preferHevc: Bool { on("prefer.hevc", false) }
        public var subsOn: Bool { on("subs.enabled", false) }
        /// Subtitle languages in order of preference (English names, e.g. "Portuguese(Brazil)").
        public var subLangs: [String] {
            var seen = Set<String>()
            let l = [v["subs.lang1"], v["subs.lang2"]].compactMap { $0 }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0).inserted }
            return l.isEmpty ? ["English"] : l
        }
        /// Size range in GB when the limit is "range".
        public func range(_ ep: Bool) -> ClosedRange<Double>? {
            let p = ep ? "size.episode" : "size.movie"
            guard v["\(p).limit"] == "range" else { return nil }
            let mn = Double(v["\(p).min_gb"] ?? "") ?? 0
            let mx = Double(v["\(p).max_gb"] ?? "").flatMap { $0 > 0 ? $0 : nil } ?? Double.greatestFiniteMagnitude
            return mn...max(mn, mx)
        }
        /// Internet speed in Mbps when the limit is "speed" (files too big to stream at it are skipped); nil otherwise.
        public func speedMbps(_ ep: Bool) -> Double? {
            let p = ep ? "size.episode" : "size.movie"
            guard v["\(p).limit"] == "speed" else { return nil }
            return Double(v["\(p).speed_mbps"] ?? v["size.movie.speed_mbps"] ?? "").flatMap { $0 > 0 ? $0 : nil }
        }
    }

    /// What Dream OS holds, by option key (for the settings UI).
    public static func stored() -> [String: String] { DreamStore.shared.get([String: String].self, "dream_filters") ?? [:] }
    public static func current() -> Values { Values(stored(), saver: true) }

    /// A value the viewer chose; non-filter keys are ignored.
    public static func put(_ key: String, _ value: String) {
        guard keys.contains(key) || extra.contains(key) else { return }
        var s = stored()
        s[key] = value
        DreamStore.shared.set(s, "dream_filters")
    }
}

/// Languages a release name says ("ITA", "Latino", "MULTI"), AudioLanguages.kt.
enum ReleaseLanguage {
    static let multi = "multi"
    private static let tags: [String: String] = {
        var m: [String: String] = [:]
        func put(_ code: String, _ t: String...) { for x in t { m[x] = code } }
        put("en", "ENG", "ENGLISH")
        put("it", "ITA", "ITALIAN", "ITALIANO")
        put("es", "SPA", "SPANISH", "ESP", "ESPANOL", "CASTELLANO")
        put("es", "LATINO", "LATAM", "LATINOAMERICANO")
        put("fr", "FRE", "FRA", "FRENCH", "TRUEFRENCH", "VFF", "VFI", "VF", "VF2")
        put("fr", "VFQ", "QUEBEC")
        put("de", "GER", "GERMAN", "DEU", "DEUTSCH")
        put("pt", "POR", "PORTUGUESE", "PORTUGUES")
        put("pt", "PTBR", "BRAZILIAN", "DUBLADO")
        put("ru", "RUS", "RUSSIAN")
        put("uk", "UKR", "UKRAINIAN")
        put("pl", "POL", "POLISH", "PLDUB", "LEKTOR")
        put("nl", "DUTCH", "NLD")
        put("sv", "SWE", "SWEDISH")
        put("no", "NOR", "NORWEGIAN")
        put("da", "DAN", "DANISH")
        put("fi", "FIN", "FINNISH")
        put("cs", "CZE", "CZECH")
        put("hu", "HUN", "HUNGARIAN")
        put("ro", "RUM", "ROMANIAN")
        put("el", "GREEK")
        put("tr", "TUR", "TURKISH")
        put("ar", "ARABIC")
        put("he", "HEB", "HEBREW")
        put("hi", "HIN", "HINDI")
        put("ta", "TAMIL")
        put("te", "TELUGU")
        put("ml", "MALAYALAM")
        put("kn", "KANNADA")
        put("bn", "BENGALI")
        put("ja", "JPN", "JAPANESE")
        put("ko", "KOR", "KOREAN")
        put("zh", "CHINESE", "MANDARIN", "CANTONESE")
        put("th", "THAI")
        put("vi", "VIETNAMESE")
        put(multi, "MULTI", "MULTILANG", "MULTIAUDIO", "DUAL", "DUALAUDIO")
        return m
    }()

    /// English language names to 2-letter codes ("italian" -> "it").
    private static let names: [String: String] = {
        var m: [String: String] = [:]
        let en = Locale(identifier: "en_US")
        for code in Locale.LanguageCode.isoLanguageCodes.map(\.identifier) where code.count == 2 {
            if let n = en.localizedString(forLanguageCode: code) { m[n.lowercased()] = code }
        }
        return m
    }()

    /// A language from a release name ("Italiano", "ENG 5.1", "Castellano"), or nil.
    static func from(_ name: String) -> String? {
        let tokens = name.uppercased().components(separatedBy: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+").inverted).filter { !$0.isEmpty }
        for t in tokens {
            if let c = tags[t], c != multi { return c }
            if t.count > 3, let c = names[t.lowercased()] { return c }
        }
        return nil
    }

    /// "eng" / "en-US" / "pt_BR" -> "en" / "pt"; nil for und/unknown.
    static func norm(_ l: String?) -> String? {
        guard var p = l?.trimmingCharacters(in: .whitespaces).lowercased(), !p.isEmpty else { return nil }
        p = String(p.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "")
        if ["", "und", "unknown", "zxx", "mis"].contains(p) { return nil }
        if p.count == 3 {
            let biblio = ["ger": "de", "fre": "fr", "dut": "nl", "chi": "zh", "cze": "cs", "gre": "el", "per": "fa", "rum": "ro", "slo": "sk", "alb": "sq", "arm": "hy", "baq": "eu", "bur": "my", "geo": "ka", "ice": "is", "mac": "mk", "may": "ms", "wel": "cy", "tib": "bo"]
            if let b = biblio[p] { return b }
            let loc = Locale(identifier: p)
            if let two = loc.language.languageCode?.identifier(.alpha2) { return two }
        }
        return p
    }
}

// MARK: Prefer HDR (watching worker, 2026-10-28 night)

/// "Prefer HDR" (DreamFilters "quality.hdr"): a copy in HDR10 / HDR10+ / Dolby Vision / HLG may go above Maximum
/// Quality (almost every HDR copy is 4K, so a 1080p cap otherwise never plays HDR). Only on a screen that shows HDR
/// and an unmetered network; every other copy still obeys the cap. Default: on when the screen shows HDR.
public enum HDRPreference {
    /// The screen (and decoder) can show HDR video. Thread-safe (AVFoundation class property).
    public static var screenShowsHDR: Bool { AVPlayer.eligibleForHDRPlayback }
    /// The viewer's choice, else the default for this screen.
    public static var chosen: Bool {
        let s = DreamFilters.stored()["quality.hdr"] ?? ""
        return s.isEmpty ? screenShowsHDR : s == "true"
    }
    public static func set(_ on: Bool) {
        DreamFilters.put("quality.hdr", on ? "true" : "false")
        Log.core.info("Sources: prefer HDR \(on ? "on" : "off", privacy: .public) (screen shows HDR: \(screenShowsHDR, privacy: .public))")
    }
    /// Why it doesn't apply right now (nil = it does, when chosen).
    public static var heldBack: String? {
        if !screenShowsHDR { return "this screen shows no HDR" }
        if !PlaybackPath.shared.unmetered { return "metered or Low Data network" }
        return nil
    }
    /// HDR copies may go above the cap for this Play.
    public static var active: Bool { chosen && heldBack == nil }
}

/// The phone's network path for source choices (metered = cellular, hotspot, Low Data Mode).
final class PlaybackPath: @unchecked Sendable {
    static let shared = PlaybackPath()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var free = true
    private var about = "not known yet"
    /// Signalled by the monitor's first answer: the first lookup after launch reads the real path, not the default.
    private var seen = false
    private let first = DispatchSemaphore(value: 0)
    private init() {
        monitor.pathUpdateHandler = { [weak self] p in
            guard let self else { return }
            let ok = Self.unmetered(p)
            let text = Self.describe(p)
            let (wasFirst, flipped) = self.lock.withLock { () -> (Bool, Bool) in
                let flip = self.seen && self.free != ok
                self.free = ok
                self.about = text
                defer { self.seen = true }
                return (!self.seen, flip)
            }
            if wasFirst { self.first.signal() }
            if flipped || wasFirst { Log.core.info("Sources: network now \(text, privacy: .public)") }
            // Wi-Fi <-> mobile data: links remembered under the other network's rules are dropped or wait (Dream Core).
            // Cellular Data Saver's mobile-data session: begins (Ask Me asks) or ends (back on Wi-Fi, the saver turns off).
            if flipped || wasFirst { CellularSaver.networkSeen(unmetered: ok, first: wasFirst) }
            if flipped { NativeResolver.shared.networkChanged(unmetered: ok) }
        }
        monitor.start(queue: DispatchQueue(label: "dreamos.playback.path", qos: .utility))
    }
    /// Metered: cellular (also when the path names it without `isExpensive`), personal hotspot, Low Data Mode.
    private static func unmetered(_ p: NWPath) -> Bool {
        p.status == .satisfied && !p.isExpensive && !p.isConstrained && !p.usesInterfaceType(.cellular)
    }
    /// "Wi-Fi, unmetered (expensive no, constrained no)" for the log.
    private static func describe(_ p: NWPath) -> String {
        let kind = p.status != .satisfied ? "offline" : p.usesInterfaceType(.wifi) ? "Wi-Fi" : p.usesInterfaceType(.cellular) ? "cellular"
            : p.usesInterfaceType(.wiredEthernet) ? "wired" : "other"
        let yn = { (b: Bool) in b ? "yes" : "no" }
        return "\(kind), \(unmetered(p) ? "unmetered" : "metered") (expensive \(yn(p.isExpensive)), constrained \(yn(p.isConstrained)))"
    }
    private func waitFirst() {
        // The monitor answers within milliseconds of starting; wait for that once (each waiter passes the signal on).
        if !lock.withLock({ seen }), first.wait(timeout: .now() + 0.3) == .success { first.signal() }
    }
    /// Read fresh from the monitor (its current path), not only the last callback.
    var unmetered: Bool {
        waitFirst()
        if lock.withLock({ seen }) { return Self.unmetered(monitor.currentPath) }
        return lock.withLock { free }
    }
    /// The path in plain log words, read fresh.
    var description: String {
        waitFirst()
        return lock.withLock({ seen }) ? Self.describe(monitor.currentPath) : lock.withLock { about }
    }
}

// MARK: Cellular Data Saver (data saver worker, 2026-10-28)

public extension Notification.Name {
    /// Dream saw mobile data and Cellular Data Saver is on Ask Me with no answer yet this mobile-data session: the app
    /// asks (DreamOS `CellularPrompt`). Posted from any thread.
    static let dreamCellularSaverAsk = Notification.Name("dreamCellularSaverAsk")
    /// Back on Wi-Fi after a mobile-data session where the saver was on: the app shows its quiet notice.
    static let dreamCellularSaverWifiBack = Notification.Name("dreamCellularSaverWifiBack")
}

/// "Cellular Data Saver": on a metered network (cellular, personal hotspot, Low Data Mode; `PlaybackPath`) copies within
/// the limit (720p / 1080p, DreamFilters "quality.cellular", default 1080p) rank before every copy above it, smaller
/// files win ties, and when nothing within it is ready the smallest copy above it plays (never "no streams").
///
/// A mode (DreamFilters "quality.cellular.mode"): Ask Me (default), Always On, Off. Ask Me asks once per mobile-data
/// session (`answer`); the answer lasts until Wi-Fi is back, which ends the session (the saver turns itself off; the
/// app shows "Back on Wi-Fi · Full Quality Again" when it was on). Wi-Fi always uses Maximum Quality. Decided once per
/// Play, by the network and the answer at that moment; a title already playing keeps its copy on a network change,
/// except when the viewer answers Use Data Saver over the player (the app switches it then).
public enum CellularSaver {
    public enum Mode: String, Sendable, CaseIterable { case ask, on, off }
    public static let modeChoices: [(id: String, label: String)] = [("ask", "Ask Me"), ("on", "Always On"), ("off", "Off")]
    public static let limitChoices: [(id: String, label: String)] = [("720p", "720p"), ("1080p", "1080p")]
    static let defaultLimit = "1080p"
    /// The note Dream Player's Info › Now Playing shows when it applied (`PictureNote`).
    public static let noteText = "Cellular Data Saver"
    public static let nothingSmallerText = "Cellular Data Saver  ·  Nothing Smaller Ready"

    // ---- the viewer's settings ----

    /// The mode; Ask Me by default for everyone (owner, 2026-10-28). One-time migration (marker "quality.cellular.ask2"):
    /// every earlier value (pass 1's 720p / 1080p / No Limit / nothing, and the mode pass 3's own migration set, which
    /// can't be told from a tap) becomes Ask Me; a saved 720p / 1080p stays only as the Data Saver Quality.
    public static var mode: Mode { mode(DreamFilters.stored()) }
    static func mode(_ v: [String: String]) -> Mode {
        if v["quality.cellular.ask2"] == "true", let m = v["quality.cellular.mode"].flatMap(Mode.init(rawValue:)) { return m }
        let was = v["quality.cellular.mode"] ?? v["quality.cellular"] ?? "nothing"
        DreamFilters.put("quality.cellular.mode", Mode.ask.rawValue)
        DreamFilters.put("quality.cellular.ask2", "true")
        Log.core.info("Sources: cellular saver mode set to Ask Me (one-time move from \(was, privacy: .public); quality \(limit(v), privacy: .public))")
        return .ask
    }
    public static func label(_ m: Mode) -> String { modeChoices.first { $0.id == m.rawValue }?.label ?? m.rawValue }
    public static var modeLabel: String { label(mode) }
    public static func setMode(_ m: Mode) {
        DreamFilters.put("quality.cellular.ask2", "true") // the viewer's own choice: never moved by the migration
        DreamFilters.put("quality.cellular.mode", m.rawValue)
        Log.core.info("Sources: cellular saver mode set to \(label(m), privacy: .public)")
    }
    public static func setMode(id: String) { if let m = Mode(rawValue: id) { setMode(m) } }

    /// The quality limit (720p or 1080p).
    public static var limit: String { limit(DreamFilters.stored()) }
    static func limit(_ v: [String: String]) -> String {
        let s = v["quality.cellular"] ?? ""
        return limitChoices.contains { $0.id == s } ? s : defaultLimit
    }
    public static func setLimit(_ id: String) {
        guard limitChoices.contains(where: { $0.id == id }) else { return }
        DreamFilters.put("quality.cellular", id)
        Log.core.info("Sources: cellular saver limit set to \(id, privacy: .public)")
    }

    // ---- this mobile-data session ----

    public enum Answer: String, Sendable { case none, use, notNow }
    private final class Session: @unchecked Sendable {
        let lock = NSLock()
        var answer = Answer.none
        var wasOn = false
    }
    private static let session = Session()

    /// The phone is on a metered network right now (cellular, hotspot, Low Data Mode).
    public static var onMeteredNetwork: Bool { !PlaybackPath.shared.unmetered }
    /// Starts the network monitor (the app's first moments, so the first mobile-data moment is seen).
    public static func start() { _ = PlaybackPath.shared }
    /// This session's answer (none until asked).
    public static var answer: Answer { session.lock.withLock { session.answer } }
    /// Ask Me, on mobile data, not answered this session: the app asks (now, or right before the next Play).
    public static var needsAnswer: Bool { answer == .none && mode == .ask && onMeteredNetwork }
    /// Whether the saver applies right now (mobile data and Always On, or Ask Me answered Use Data Saver).
    public static var applies: Bool { applies(DreamFilters.stored()) }
    static func applies(_ v: [String: String]) -> Bool {
        guard onMeteredNetwork else { return false }
        switch mode(v) {
        case .on: return true
        case .off: return false
        case .ask: return answer == .use
        }
    }

    /// The app showed the question ([why] for the log).
    public static func promptShown(_ why: String) {
        Log.core.info("Sources: cellular saver prompt shown (\(why, privacy: .public)) on \(PlaybackPath.shared.description, privacy: .public)")
    }
    /// The viewer's answer. [use]: Use Data Saver up to [limit] for this session ([always]: Always On from now on).
    /// [dontAsk]: Off from now on (Settings can change it).
    public static func answer(use: Bool, limit l: String? = nil, always: Bool = false, dontAsk: Bool = false) {
        if let l { setLimit(l) }
        if always && use { setMode(.on) }
        if dontAsk { setMode(.off) }
        // Answered after Wi-Fi came back (the question was still up): the session is over, so nothing carries over to
        // the next mobile-data session (it asks again); the mode choices above still count.
        let metered = onMeteredNetwork
        if metered {
            session.lock.withLock {
                session.answer = use ? .use : .notNow
                if use { session.wasOn = true }
            }
        }
        let what = use ? "Use Data Saver, up to \(limit), \(always ? "always on mobile data" : "this session")"
            : dontAsk ? "Don't Ask Again, now Off" : "Not Now"
        Log.core.info("Sources: cellular saver prompt answered (\(what, privacy: .public))")
        // Links remembered above the limit wait as fallbacks; their failovers go (Dream Core).
        if use && metered { NativeResolver.shared.networkChanged(unmetered: false) }
    }

    /// The network flipped or was first seen (`PlaybackPath`).
    static func networkSeen(unmetered: Bool, first: Bool) {
        if unmetered {
            let wasOn: Bool = session.lock.withLock {
                let on = session.wasOn
                session.answer = .none
                session.wasOn = false
                return on
            }
            if !first && wasOn {
                Log.core.info("Sources: cellular saver off by itself (back on Wi-Fi; full quality again)")
                NotificationCenter.default.post(name: .dreamCellularSaverWifiBack, object: nil)
            }
            return
        }
        let m = mode
        if m == .on { session.lock.withLock { session.wasOn = true } }
        if m == .ask && answer == .none {
            Log.core.info("Sources: cellular saver: mobile data seen, Ask Me (the question is due)")
            NotificationCenter.default.post(name: .dreamCellularSaverAsk, object: nil)
        }
    }

    // ---- Dream Core's view ----

    /// The cap tier for filters [v] when the saver applies right now (1 1080p, 2 720p); nil otherwise.
    static func cap(_ v: [String: String]) -> Int? {
        guard applies(v) else { return nil }
        return limit(v) == "720p" ? 2 : 1
    }
    /// The log line at the start of every Play: the network as the monitor sees it and whether the saver applies.
    static func logAtPlay(_ what: String) {
        let v = DreamFilters.stored()
        let m = mode(v)
        let state: String
        if cap(v) != nil { state = "applies, up to \(limit(v)) (\(label(m))\(m == .ask ? ", answered Use Data Saver" : ""))" }
        else if !onMeteredNetwork { state = "not needed on this network (\(label(m)))" }
        else if m == .off { state = "off" }
        else { state = answer == .notNow ? "not used this session (Not Now)" : "not answered yet (Ask Me), not applied" }
        Log.core.info("Sources: cellular saver at Play for \(what, privacy: .public): \(PlaybackPath.shared.description, privacy: .public); \(state, privacy: .public)")
    }
    /// Remembers the Info note for [link], decided now (`PictureNote`, read by Dream Player): set while the saver
    /// applies ("· Nothing Smaller Ready" when the copy is above the limit), cleared otherwise.
    static func mark(_ link: StreamLink) {
        var note: String?
        if let c = cap(DreamFilters.stored()) {
            note = tier(link.quality) < c ? nothingSmallerText : noteText
            session.lock.withLock { session.wasOn = true }
        }
        PictureNote.set(note, hash: link.hash)
    }
    /// The playing copy, judged now (Use Data Saver answered over the player): Info's note as at a Play.
    public static func markPlaying(_ link: StreamLink) { mark(link) }
    /// The playing copy stays (the switch found nothing within the limit in time): Info says so.
    public static func keptBigger(_ link: StreamLink) {
        PictureNote.set(nothingSmallerText, hash: link.hash)
        Log.core.info("Sources: cellular saver found nothing smaller ready in time; the playing \(link.quality, privacy: .public) copy stays")
    }
    /// [quality] ("4K", "1080p", ...) is above the limit right now.
    public static func above(_ quality: String) -> Bool {
        guard let c = cap(DreamFilters.stored()) else { return false }
        return tier(quality) < c
    }
    private static func tier(_ q: String) -> Int { ["4K": 0, "1080p": 1, "720p": 2][q] ?? 3 }
}
