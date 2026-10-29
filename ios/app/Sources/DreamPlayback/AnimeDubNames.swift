import Foundation
import DreamCore

/// What release names say about a dub (fire-home AnimeDubs.audio / badge), for Dream Core's ranking, the source list's
/// badge and the title page's label (`AnimeDubs` in DreamCore keeps the setting and what was found).
extension AnimeDubs {
    private static let subs: Set<String> = ["SUB", "SUBS", "SUBBED", "SUBTITLE", "SUBTITLES", "SUBTITLED", "ESUB", "ESUBS", "ENGSUB",
        "ENGSUBS", "MULTISUB", "MULTISUBS", "SOFTSUB", "SOFTSUBS", "HARDSUB", "HARDSUBS", "VOSTFR", "VOSE", "VOSTA"]
    /// Release groups that only put out the original audio with subtitles.
    private static let subGroups: Set<String> = ["SUBSPLEASE", "HORRIBLESUBS", "ERAI"]
    private static let dubs: Set<String> = ["DUB", "DUBS", "DUBBED", "DUBBING"]

    private static func tokens(_ name: String) -> [String] {
        name.uppercased().components(separatedBy: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").inverted).filter { !$0.isEmpty }
    }

    /// What [name] says about its audio for a viewer whose language is [want]: `.dub` when it says it carries [want]
    /// (DUAL AUDIO, DUAL, ENGLISH DUB, ENG DUB, DUBBED, MULTI with ENG, a bare ENG), `.original` when it says subtitles
    /// or only the original language (SubsPlease, "ENG SUB", VOSTFR, JAPANESE), else `.none`. A language next to a
    /// subtitle word ("ENG SUB", "MULTI-SUBS") is about subtitles, never audio.
    static func audio(_ name: String, want: String) -> Audio {
        if want == "ja" { return .none }
        let t = tokens(name)
        func at(_ i: Int) -> String? { t.indices.contains(i) ? t[i] : nil }
        func subNear(_ i: Int) -> Bool { at(i + 1).map(subs.contains) == true || at(i - 1).map(subs.contains) == true }
        let subWord = t.contains(where: subs.contains) || t.contains(where: subGroups.contains)
        var langs = Set<String>()
        var multi = false
        for (i, w) in t.enumerated() {
            if ["MULTI", "MULTIAUDIO", "MULTILANG"].contains(w) { if !subNear(i) { multi = true }; continue }
            if subNear(i) { continue }
            if let l = ReleaseLanguage.tag(w) { langs.insert(l) }
        }
        let dual = t.contains("DUALAUDIO") || t.contains("DUAL")
        let dubWord = t.contains(where: dubs.contains)
        let engDub = t.contains("ENGDUB") || t.contains("ENGDUBBED")
        let says = (want == "en" && (engDub || dual))
            // Named as audio ("ENG", "ENGLISH", "Kor-Eng", "[Eng+Kor]", MULTI with ENG, "ENG AAC"), wherever a subtitle word sits.
            || langs.contains(want)
            // "Dubbed" alone (no other language named): English-speaking trackers' word for the English dub.
            || (want == "en" && dubWord && langs.allSatisfy { $0 == "en" || $0 == "ja" })
        if says { return .dub }
        return subWord || (langs == ["ja"] && !multi && !dubWord) ? .original : .none
    }

    /// The source list's audio badge (Choose a Source): "English Dub", "Dual Audio", "Multi Audio", "Subtitled", or for
    /// anime the one language it names ("Japanese Audio"); nil when it says none.
    static func badge(_ name: String, want: String, anime: Bool) -> String? {
        let t = tokens(name)
        let a = audio(name, want: want)
        // A film in the viewer's own language names it too ("ENG ITA"): "Dub" only where the original is another language.
        if anime && a == .dub { return "\(langName(want)) Dub" }
        if t.contains("DUALAUDIO") || t.contains("DUAL") { return "Dual Audio" }
        if let i = t.firstIndex(of: "MULTI"), !(t.indices.contains(i + 1) && subs.contains(t[i + 1])) { return "Multi Audio" }
        if t.contains("MULTIAUDIO") { return "Multi Audio" }
        if a == .original && (t.contains(where: subs.contains) || t.contains(where: subGroups.contains)) { return "Subtitled" }
        guard anime else { return nil }
        var named = Set<String>()
        for (i, w) in t.enumerated() {
            let near = (t.indices.contains(i + 1) && subs.contains(t[i + 1])) || (i > 0 && subs.contains(t[i - 1]))
            if !near, let l = ReleaseLanguage.tag(w) { named.insert(l) }
        }
        guard named.count == 1, let l = named.first, l != want else { return nil }
        return "\(langName(l)) Audio"
    }

    /// Subtitle words that name their language themselves.
    private static let subLang = ["ESUB": "en", "ESUBS": "en", "ENGSUB": "en", "ENGSUBS": "en", "VOSTFR": "fr", "VOSE": "es"]

    /// Dream Core read [names] for [key] (a survey, a lookup, the source list): kept for the title page's language
    /// pills (`LanguagePills`, every title) and the anime dub summary.
    static func record(_ key: String, copies: [(name: String, hash: String)], want: String) {
        guard !copies.isEmpty else { return }
        recordLanguages(key, copies: copies, want: want)
        guard want != "ja" else { return }
        var dub = 0, orig = 0
        for n in copies.map(\.name) {
            switch audio(n, want: want) { case .dub: dub += 1; case .original: orig += 1; case .none: break }
        }
        store(key, Summary(dub: dub, original: orig))
    }

    /// What each copy says for the language pills: whether it carries `want` (its name, or Dream Player heard it in that
    /// copy), the audio languages it names (a language beside a subtitle word is about subtitles), its subtitle languages.
    private static func recordLanguages(_ key: String, copies: [(name: String, hash: String)], want: String) {
        var match = 0, unnamed = 0, dubSaid = 0
        var named: [Set<String>: Int] = [:], subLangs = Set<String>()
        for (n, hash) in copies {
            let t = tokens(n)
            func at(_ i: Int) -> String? { t.indices.contains(i) ? t[i] : nil }
            func subNear(_ i: Int) -> Bool { at(i + 1).map(subs.contains) == true || at(i - 1).map(subs.contains) == true }
            var langs = Set<String>(), said = Set<String>()
            var multi = false
            for (i, w) in t.enumerated() {
                if let l = subLang[w] { said.insert(l) }
                if subGroups.contains(w) { said.insert("en") }
                if ["MULTI", "MULTIAUDIO", "MULTILANG"].contains(w) { if !subNear(i) { multi = true }; continue }
                guard let l = ReleaseLanguage.tag(w) else { continue }
                if subNear(i) { said.insert(l) } else { langs.insert(l) }
            }
            subLangs.formUnion(said)
            let dual = t.contains("DUAL") || t.contains("DUALAUDIO")
            let saysDub = t.contains(where: dubs.contains) || dual || t.contains("ENGDUB") || t.contains("ENGDUBBED")
            let byName = want == "ja" ? langs.contains("ja") : audio(n, want: want) == .dub
            if byName || LanguagePills.heardIn(hash, want) { match += 1; if byName && saysDub { dubSaid += 1 } }
            else if langs.isEmpty && !multi && !dual && !t.contains(where: dubs.contains) { unnamed += 1 }
            else if !langs.isEmpty { named[langs, default: 0] += 1 }
        }
        let top = named.max { $0.value < $1.value }
        LanguagePills.record(key, want: want, total: copies.count, match: match, unnamed: unnamed,
                             top: top?.key ?? [], topCount: top?.value ?? 0, subs: subLangs, dubSaid: dubSaid)
    }
}
