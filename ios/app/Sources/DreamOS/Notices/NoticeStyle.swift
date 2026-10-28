import SwiftUI
import DreamCore
import DreamUI

/// Notice words and colors (DreamNoticesUi's companion): value words in their status color, severity accents,
/// "2 Days Ago", the summary's two columns.
enum NoticeStyle {
    /// Detail lines: near-white.
    static let detail = Color.white.opacity(0.86)
    static let smoke = Color(hex: 0x141417)

    static func severityColor(_ s: Notice.Severity) -> Color {
        switch s {
        case .critical: Palette.red
        case .warning: Palette.amber
        case .info: Color(hex: 0xE9E6F0)
        }
    }

    // "96 Days" green, "21 Days" amber, "3 Days" amber, "Expired" red, "Magnet Limit Reached" amber (STANDARDS §2: the
    // three status tints only).
    private static let values = try? NSRegularExpression(pattern:
        "(\\d+) Days?\\b|(\\d+) Years?\\b|\\d+ Months?\\b|Ends Today|Ends Tomorrow|Expired|No Premium Plan|(?:Magnet |Download )?Limit Reached|Renews Automatically")

    private static func color(_ v: String, days: Int?) -> Color {
        if v == "Expired" || v == "No Premium Plan" { return Palette.redText }
        if v == "Renews Automatically" { return Palette.greenText.opacity(0.85) }
        if v.hasSuffix("Limit Reached") || v.hasPrefix("Ends") { return Palette.amberText }
        if let days { return days > 30 ? Palette.greenText : Palette.amberText }
        return Palette.greenText // months and years
    }

    /// A whole value's status color ("76 Days", "Expired", "Renews Automatically", "Active"); nil when it has none.
    static func valueColor(_ value: String) -> Color? {
        if value == "Active" { return Palette.greenText }
        guard let re = values, let m = re.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              let r = Range(m.range, in: value) else { return nil }
        return color(String(value[r]), days: days(m, value))
    }

    private static func days(_ m: NSTextCheckingResult, _ s: String) -> Int? {
        guard let r = Range(m.range(at: 1), in: s) else { return nil }
        return Int(s[r])
    }

    /// The line's own color with the value words in their status color. [plain]: no colors (the pearl pressed face).
    static func colorize(_ text: String, base: Color, plain: Bool = false) -> AttributedString {
        var out = AttributedString(text)
        out.foregroundColor = base
        guard !plain, let re = values else { return out }
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text),
                  let lo = AttributedString.Index(r.lowerBound, within: out), let hi = AttributedString.Index(r.upperBound, within: out) else { continue }
            out[lo..<hi].foregroundColor = color(String(text[r]), days: days(m, text))
        }
        return out
    }

    /// "Just Now", "5 Minutes Ago", "3 Hours Ago", "Yesterday", "2 Days Ago", then the date.
    static func ago(_ at: Int64, now: Int64 = wallNow()) -> String {
        let m = max(0, now - at) / 60_000
        switch m {
        case ..<1: return "Just Now"
        case ..<60: return m == 1 ? "1 Minute Ago" : "\(m) Minutes Ago"
        case ..<(24 * 60): return m / 60 == 1 ? "1 Hour Ago" : "\(m / 60) Hours Ago"
        case ..<(48 * 60): return "Yesterday"
        case ..<(7 * 24 * 60): return "\(m / (24 * 60)) Days Ago"
        default: return DreamTime.format(Date(timeIntervalSince1970: Double(at) / 1000), "MMM d")
        }
    }

    /// Under a list row: "2 Days Ago   ·   Renew".
    static func meta(_ n: Notice) -> String {
        ([ago(n.at)] + (n.action.map { [$0.label] } ?? [])).joined(separator: "   ·   ")
    }
}

/// The summary's "Name: value" lines as two aligned columns: the names with their colons on the left, each value right
/// after in its status color. Up to four lines (one per linked service).
struct NoticeSummaryGrid: View {
    let text: String
    var size: CGFloat = 14
    var ink = false

    private var rows: [(String, String)] {
        text.split(separator: "\n").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.prefix(4).map { line in
            guard let r = line.range(of: ": ") else { return (line, "") }
            return (String(line[..<r.lowerBound]).trimmingCharacters(in: .whitespaces), String(line[r.upperBound...]).trimmingCharacters(in: .whitespaces))
        }
    }

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 4) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                GridRow {
                    Text(row.0 + ":").font(.system(size: size, weight: .medium)).foregroundStyle(ink ? Palette.ink : Palette.text)
                    Text(row.1).font(.system(size: size, weight: .semibold))
                        .foregroundStyle(ink ? Palette.ink : (NoticeStyle.valueColor(row.1) ?? NoticeStyle.detail))
                }
                .lineLimit(1)
            }
        }
    }
}
