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
        "quality.max", "quality.min", "quality.hdr", "hide.cam", "hide.hdr", "hide.dv", "hide.hevc", "hide.av1", "prefer.hdr", "prefer.hevc",
        "size.movie.limit", "size.movie.min_gb", "size.movie.max_gb", "size.movie.speed_mbps",
        "size.episode.limit", "size.episode.min_gb", "size.episode.max_gb", "size.episode.speed_mbps",
        "subs.enabled", "subs.lang1", "subs.lang2", "subs.embedded", "subs.hi", "subs.forced",
    ]
    /// Keys the resolver honors that Settings doesn't show.
    private static let extra: Set<String> = ["hide.avc", "hide.3d", "hide.upscaled"]

    /// The filters as the resolver applies them. Missing values fall back to safe defaults.
    public struct Values: Sendable {
        let v: [String: String]
        init(_ v: [String: String]) {
            self.v = v
            let s = v["quality.hdr"] ?? ""
            hdrAboveCap = (s.isEmpty ? HDRPreference.screenShowsHDR : s == "true") && HDRPreference.heldBack == nil
        }
        /// Prefer HDR applies to this Play: an HDR copy may go above Maximum Quality (`HDRPreference`).
        public let hdrAboveCap: Bool
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
    public static func current() -> Values { Values(stored()) }

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
    private init() {
        monitor.pathUpdateHandler = { [weak self] p in
            let ok = p.status == .satisfied && !p.isExpensive && !p.isConstrained
            self?.lock.withLock { self?.free = ok }
        }
        monitor.start(queue: DispatchQueue(label: "dreamos.playback.path", qos: .utility))
    }
    var unmetered: Bool { lock.withLock { free } }
}
