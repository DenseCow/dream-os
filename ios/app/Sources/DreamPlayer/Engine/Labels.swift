import Foundation

/// How Dream Player words formats, times and languages (ExoEngine.kt companion, PlayerOverlay.fmt, PlayerPrefs.normLang).
public enum Labels {
    public static func resolution(_ w: Int, _ h: Int) -> String {
        if h >= 2000 || w >= 3800 { return "2160p" }
        if h >= 1400 || w >= 2500 { return "1440p" }
        if h >= 1000 || w >= 1900 { return "1080p" }
        if h >= 700 || w >= 1260 { return "720p" }
        return "\(h)p"
    }

    /// Info line channels: Mono, 2.0, 5.1, 7.1.
    public static func channels(_ n: Int) -> String? {
        switch n {
        case ...0: return nil
        case 1: return "Mono"
        case 2: return "2.0"
        case 3: return "2.1"
        case 6: return "5.1"
        case 7: return "6.1"
        case 8: return "7.1"
        default: return "\(n)ch"
        }
    }

    /// Audio menu / badges channels: Mono, Stereo, 5.1, 7.1.
    public static func channelsLong(_ n: Int) -> String? { n == 2 ? "Stereo" : channels(n) }

    /// "1:02:03" / "4:05".
    public static func time(_ seconds: Double) -> String {
        let s = max(0, Int(seconds.isFinite ? seconds : 0))
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    /// "+10" / "−1:30" (seek bubbles and the scrub readout).
    public static func offset(_ delta: Double) -> String {
        let sign = delta < 0 ? "\u{2212}" : "+"
        let s = Int(abs(delta))
        return sign + (s < 60 ? "\(s)" : time(Double(s)))
    }

    /// "38 min left", "1 hr 12 min left"; nil when the length isn't known.
    public static func timeLeft(position: Double, duration: Double) -> String? {
        guard duration > 0 else { return nil }
        let min = Int((max(0, duration - position) + 30) / 60)
        if min < 1 { return "Less than a minute left" }
        if min < 60 { return "\(min) min left" }
        if min % 60 == 0 { return "\(min / 60) hr left" }
        return "\(min / 60) hr \(min % 60) min left"
    }

    /// PlayerMeta.runtime: "2h" / "1h 5m" / "45 min".
    public static func runtime(_ minutes: Int) -> String? {
        guard minutes > 0 else { return nil }
        if minutes >= 60 { return minutes % 60 == 0 ? "\(minutes / 60)h" : "\(minutes / 60)h \(minutes % 60)m" }
        return "\(minutes) min"
    }

    /// "4.2 GB" / "700 MB"; nil when unknown.
    public static func size(_ bytes: Int64) -> String? {
        guard bytes > 0 else { return nil }
        if bytes >= 1 << 30 { return String(format: "%.1f GB", Double(bytes) / Double(1 << 30)) }
        return "\(bytes >> 20) MB"
    }

    /// PlayerMenus.speedLabel: "0.75×", "Normal", "2.0×".
    public static func speed(_ s: Double) -> String {
        if abs(s - 1) < 0.01 { return "Normal" }
        var t = String(format: "%.2f", s)
        while t.hasSuffix("0") { t.removeLast() }
        if t.hasSuffix(".") { t += "0" }
        return t + "\u{00D7}"
    }

    private static let biblio: [String: String] = [
        "fre": "fr", "ger": "de", "chi": "zh", "dut": "nl", "gre": "el", "cze": "cs", "rum": "ro", "slo": "sk",
        "ice": "is", "per": "fa", "wel": "cy", "arm": "hy", "baq": "eu", "geo": "ka", "mac": "mk", "mao": "mi",
        "may": "ms", "alb": "sq", "bur": "my", "tib": "bo",
    ]

    /// PlayerPrefs.normLang: "eng" / "en-US" / "English" -> "en"; nil for und/unknown.
    public static func normLang(_ l: String?) -> String? {
        guard var c = l?.trimmingCharacters(in: .whitespaces).lowercased(), !c.isEmpty else { return nil }
        if let cut = c.firstIndex(where: { $0 == "-" || $0 == "_" }) { c = String(c[..<cut]) }
        if ["", "und", "unknown", "zxx", "mis"].contains(c) { return nil }
        if c.count == 3 {
            if let b = biblio[c] { return b }
            if let two = Locale.LanguageCode(c).identifier(.alpha2) { return two }
            return c
        }
        if c.count > 3 {
            // An English name ("English").
            for code in Locale.LanguageCode.isoLanguageCodes {
                if Locale(identifier: "en").localizedString(forLanguageCode: code.identifier)?.lowercased() == c {
                    return code.identifier(.alpha2) ?? code.identifier
                }
            }
        }
        return c
    }

    /// "English" in the device language, or "Unknown".
    public static func languageName(_ code: String?) -> String {
        guard let c = normLang(code) else { return "Unknown" }
        let name = Locale.current.localizedString(forLanguageCode: c) ?? c
        return name.prefix(1).uppercased() + name.dropFirst()
    }
}
