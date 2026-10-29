import Foundation
import DreamCore

/// Subtitles from the web for Dream Core streams (fire-home DreamSubtitles.kt): the free OpenSubtitles index
/// (no key), up to 2 files per language, matched to the playing file's name. Only used when the viewer wants
/// subtitles or might switch them on; never delays the start.
enum DreamSubtitles {
    private static let base = "https://opensubtitles-v3.strem.io/subtitles"

    private struct Index: Decodable { let subtitles: [Entry]? }
    private struct Entry: Decodable {
        let id: String?
        let url: String?
        let lang: String?
        let subtitleFileName: String?
        let season: Int?
        let episode: Int?
    }

    /// The viewer's subtitle choices (DreamSubtitles.kt `Prefs`). `langs`: normalized codes in order ("pob" for
    /// Portuguese (Brazil), OpenSubtitles' own code); `show`: subtitles should be on (Settings › Subtitles, or a
    /// language picked in the player); `embeddedFirst`: "Use Subtitles in the Video First".
    struct Prefs: Sendable {
        let langs: [String]
        let show: Bool
        let embeddedFirst: Bool
        let hi: Bool
        let forced: Bool
        /// The languages as the engine labels tracks ("pob" plays as "pt").
        var trackLangs: [String] { langs.map(DreamSubtitles.trackCode) }
    }

    /// Settings › Subtitles as Dream OS keeps them: DreamFilters' "dream_filters" in DreamStore (DreamPlayer reads
    /// the store directly; the keys are `subs.*`).
    /// The same name for the same address in every launch (`hashValue` is seeded per launch, so the cache never hit).
    static func stableKey(_ s: String) -> String {
        String(s.utf8.reduce(UInt64(14_695_981_039_346_656_037)) { ($0 ^ UInt64($1)) &* 1_099_511_628_211 })
    }

    private static func settings() -> [String: String] { DreamStore.shared.get([String: String].self, "dream_filters") ?? [:] }

    /// Languages in order: the player's subtitle choice, then Settings' first and second language. Subtitles off
    /// everywhere: just the first one (fetched and listed in the menu, not shown, so turning them on mid-film needs
    /// no download). TV `prefs()`.
    static func prefs() -> Prefs {
        let s = settings()
        let player = PlayerPrefs.subLang
        let fromPlayer = player == PlayerPrefs.off ? nil : player
        let named = [s["subs.lang1"], s["subs.lang2"]].compactMap(code)
        var seen = Set<String>()
        var langs = ([fromPlayer].compactMap { $0 } + named).filter { seen.insert($0).inserted }
        if langs.isEmpty { langs = [PlayerPrefs.audioLang ?? PlayerPrefs.deviceLang] }
        let show = player != PlayerPrefs.off && (fromPlayer != nil || s["subs.enabled"] == "true")
        return Prefs(langs: Array(langs.prefix(show ? 3 : 1)), show: show,
                     embeddedFirst: s["subs.embedded"] != "false",
                     hi: s["subs.hi"] == "true", forced: s["subs.forced"] == "true")
    }

    /// The subtitle language the engine picks from the video's own tracks at the start: "off" when the viewer
    /// turned subtitles off in the player, the first language while subtitles are on, else nil (no choice).
    static func preferredText(_ p: Prefs) -> String? {
        if PlayerPrefs.subLang == PlayerPrefs.off { return PlayerPrefs.off }
        return p.show ? p.trackLangs.first : nil
    }

    /// "English" -> "en", "Portuguese(Brazil)" -> "pob"; nil for none / unknown.
    static func code(_ name: String?) -> String? {
        guard let n = name?.trimmingCharacters(in: .whitespaces), !n.isEmpty,
              n.caseInsensitiveCompare("none") != .orderedSame else { return nil }
        if n.replacingOccurrences(of: " ", with: "").caseInsensitiveCompare("Portuguese(Brazil)") == .orderedSame { return "pob" }
        return Labels.normLang(n).flatMap { $0.count <= 3 ? $0 : nil }
    }

    /// A Prefs language as the engine labels tracks.
    static func trackCode(_ l: String) -> String { l == "pob" ? "pt" : l }

    /// The best files per language for `r` (2 at most each) as local files, none marked to show: the session
    /// decides that once it knows the video's own tracks (`PlayerSession.loadSubtitles`).
    static func fetch(for r: PlayRequest, fileName: String, prefs p: Prefs) async -> [SubtitleFile] {
        guard let imdb = r.imdbId, !imdb.isEmpty else { return [] }
        let path = r.season != nil && r.episode != nil
            ? "/series/\(imdb):\(r.season!):\(r.episode!).json" : "/movie/\(imdb).json"
        guard let url = URL(string: base + path) else { return [] }
        var req = URLRequest(url: url)
        req.timeoutInterval = 6
        guard let data = try? await Net.data(req), let index = try? JSONDecoder().decode(Index.self, from: data) else { return [] }
        let name = fileName.lowercased()
        var out: [SubtitleFile] = []
        for lang in p.langs {
            let entries = (index.subtitles ?? []).filter { e in
                guard let u = e.url, u.hasPrefix("https://"), let l = e.lang else { return false }
                if let s = r.season, let es = e.season, es > 0, es != s { return false }
                if let ep = r.episode, let ee = e.episode, ee > 0, ee != ep { return false }
                let code = l.lowercased() == "pob" ? "pob" : Labels.normLang(l)
                return code == lang
            }
            let scored = entries.enumerated().map { i, e in (score(e.subtitleFileName ?? "", against: name, prefs: p), -i, e) }
                .sorted { ($0.0, $0.1) > ($1.0, $1.1) }
            var kept = 0
            for (_, _, e) in scored where kept < 2 {
                guard let u = e.url, let remote = URL(string: u), let local = await download(remote) else { continue }
                let fn = (e.subtitleFileName ?? "").lowercased()
                let extra = fn.range(of: #"\b(sdh|hi|cc)\b"#, options: .regularExpression) != nil ? "SDH"
                    : fn.contains("forced") ? "Forced" : lang == "pob" ? "Brazil" : nil
                out.append(SubtitleFile(url: local, language: lang == "pob" ? "pt-BR" : lang, label: extra,
                                        online: true, asDefault: false))
                kept += 1
            }
        }
        Log.player.info("Player: Dream Subtitles \(out.count, privacy: .public) file(s)")
        return out
    }

    private static func score(_ sub: String, against file: String, prefs p: Prefs) -> Int {
        let s = sub.lowercased().replacingOccurrences(of: "web-dl", with: "webdl")
        let f = file.replacingOccurrences(of: "web-dl", with: "webdl")
        var n = 0
        if let dash = f.lastIndex(of: "-") {
            let group = f[f.index(after: dash)...].split(separator: ".").first.map(String.init) ?? ""
            if (2...20).contains(group.count), group.allSatisfy({ $0.isLetter || $0.isNumber }), s.contains(group) { n += 6 }
        }
        for w in ["remux", "bluray", "bdrip", "brrip", "web", "webdl", "webrip", "hdtv", "dvdrip", "hdrip", "amzn", "nf", "dsnp", "hmax", "atvp"]
        where f.contains(w) && s.contains(w) { n += 2 }
        for r in ["2160p", "1080p", "720p", "480p"] where f.contains(r) && s.contains(r) { n += 1 }
        if s.range(of: #"\bcd[1-9]\b"#, options: .regularExpression) != nil { n -= 20 }
        // Forced files only cover the foreign-language parts; hearing-impaired ones add sound notes.
        if s.contains("forced") != p.forced { n -= 8 }
        if (s.range(of: #"\b(sdh|hi|cc)\b"#, options: .regularExpression) != nil) != p.hi { n -= 2 }
        return n
    }

    private static func download(_ url: URL) async -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("dreamsubs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(Self.stableKey(url.absoluteString) + ".srt")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        var req = URLRequest(url: url)
        req.timeoutInterval = 6
        guard var data = try? await Net.data(req), data.count <= 2_000_000 else { return nil }
        if data.starts(with: [0xEF, 0xBB, 0xBF]) { data = data.dropFirst(3) }
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1), text.contains("-->") else { return nil }
        try? text.write(to: file, atomically: true, encoding: .utf8)
        return file
    }
}
