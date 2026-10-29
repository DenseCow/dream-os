import Foundation
import DreamCore

/// What Dream Subtitles' index has for a title, for the title page's language pills (`LanguagePills`; fire-home
/// DreamSubtitles.indexLanguages): every subtitle language listed, nothing downloaded.
public enum SubtitleIndex {
    private static let base = "https://opensubtitles-v3.strem.io/subtitles"
    private struct Index: Decodable { let subtitles: [Entry]? }
    private struct Entry: Decodable { let lang: String?; let season: Int?; let episode: Int? }

    private static let ttl: TimeInterval = 3600
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: (at: Date, langs: Set<String>)] = [:]

    /// The viewer's subtitle languages in order (the player's choice, then Settings' first and second); empty when none
    /// is set.
    public static func viewerLanguages() -> [String] {
        let s = DreamStore.shared.get([String: String].self, "dream_filters") ?? [:]
        let fromPlayer = PlayerPrefs.subLang == PlayerPrefs.off ? nil : PlayerPrefs.subLang
        var seen = Set<String>()
        return ([fromPlayer].compactMap { $0 } + [s["subs.lang1"], s["subs.lang2"]].compactMap(DreamSubtitles.code))
            .filter { seen.insert($0).inserted }
    }

    /// Every language the index lists for `r` (normalized; "pob" for Brazilian Portuguese); nil when it couldn't be asked.
    public static func languages(for r: PlayRequest) async -> Set<String>? {
        guard let imdb = r.imdbId, !imdb.isEmpty else { return nil }
        let episode = r.title.kind == .tv
        if episode && (r.season == nil || r.episode == nil) { return nil }
        let key = episode ? "\(imdb):\(r.season!):\(r.episode!)" : imdb
        if let hit = lock.withLock({ cache[key] }), Date().timeIntervalSince(hit.at) < ttl { return hit.langs }
        guard let url = URL(string: base + (episode ? "/series/\(key).json" : "/movie/\(imdb).json")) else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 6
        guard let data = try? await Net.data(req), let index = try? JSONDecoder().decode(Index.self, from: data),
              let list = index.subtitles else { return nil }
        var out = Set<String>()
        for e in list {
            if let s = r.season, let es = e.season, es > 0, es != s { continue }
            if let ep = r.episode, let ee = e.episode, ee > 0, ee != ep { continue }
            guard let l = e.lang?.lowercased() else { continue }
            if let code = l == "pob" ? "pob" : Labels.normLang(l) { out.insert(code) }
        }
        lock.withLock {
            if cache.count > 200 { cache.removeAll() }
            cache[key] = (Date(), out)
        }
        return out
    }
}
