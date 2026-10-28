import Foundation

/// What's playing as short badges: "4K", "Dolby Vision", "Dolby Atmos", "WEB-DL" (fire-home StreamBadges.kt).
public struct StreamBadges: Equatable {
    public struct Badge: Equatable, Hashable {
        public var text: String
        /// Amber (a CAM copy).
        public var warn = false
    }
    public var badges: [Badge]
    public var size: String?

    /// The HDR kinds a badge can name.
    public static let hdrNames: Set<String> = ["Dolby Vision", "HDR10+", "HDR10", "HLG"]
    /// This stream's HDR kind ("Dolby Vision", "HDR10+", "HDR10", "HLG"), nil for a standard picture.
    public var hdrName: String? { badges.first { Self.hdrNames.contains($0.text) }?.text }

    public static func make(_ f: StreamFacts, fileName: String?, sizeBytes: Int64) -> StreamBadges? {
        let tag = Self.tag(fileName ?? "")
        func has(_ w: String) -> Bool { tag.contains(" \(w) ") }
        var out: [Badge] = []
        let h = f.height, w = f.width
        if h > 0 || w > 0 {
            out.append(Badge(text: h >= 2000 || w >= 3200 ? "4K" : h >= 1000 || w >= 1800 ? "1080p" : h >= 700 || w >= 1200 ? "720p" : "SD"))
        }
        if out.isEmpty && f.audioCodec == nil { return nil }
        if let hdr = f.hdr {
            out.append(Badge(text: hdr == "HDR10" && (has("HDR10+") || has("HDR10PLUS")) ? "HDR10+" : hdr))
        } else if has("DV") || has("DOVI") || tag.contains(" DOLBY VISION ") {
            out.append(Badge(text: "Dolby Vision"))
        } else if has("HDR10+") || has("HDR10PLUS") {
            out.append(Badge(text: "HDR10+"))
        } else if has("HDR") || has("HDR10") {
            out.append(Badge(text: "HDR10"))
        } else if has("HLG") {
            out.append(Badge(text: "HLG"))
        }
        let ch = Labels.channelsLong(f.channels)
        func with(_ s: String) -> String { ch.map { "\(s) \($0)" } ?? s }
        switch f.audioCodec {
        case "Dolby Atmos": out.append(Badge(text: "Dolby Atmos"))
        case "Dolby Digital Plus": out.append(Badge(text: has("ATMOS") ? "Dolby Atmos" : with("Dolby Digital Plus")))
        case "Dolby TrueHD": out.append(Badge(text: has("ATMOS") ? "Dolby Atmos" : with("Dolby TrueHD")))
        case "Dolby Digital": out.append(Badge(text: with("Dolby Digital")))
        case "Dolby AC-4": out.append(Badge(text: with("Dolby AC-4")))
        case "DTS:X": out.append(Badge(text: "DTS:X"))
        case "DTS-HD": out.append(Badge(text: with("DTS-HD")))
        case "DTS", "DTS Express": out.append(Badge(text: with("DTS")))
        default: if let ch { out.append(Badge(text: ch)) }
        }
        if has("REMUX") || has("BDREMUX") { out.append(Badge(text: "Remux")) }
        else if has("BLURAY") || tag.contains(" BLU RAY ") || has("BDRIP") || has("BRRIP") || has("BD25") || has("BD50") { out.append(Badge(text: "BluRay")) }
        else if has("WEBRIP") || tag.contains(" WEB RIP ") { out.append(Badge(text: "WEBRip")) }
        else if tag.contains(" WEB DL ") || has("WEBDL") || has("WEB") { out.append(Badge(text: "WEB-DL")) }
        else if has("HDTV") { out.append(Badge(text: "HDTV")) }
        else if ["CAM", "HDCAM", "CAMRIP", "TS", "HDTS", "TELESYNC", "TC", "HDTC", "TELECINE", "SCR", "SCREENER", "DVDSCR"].contains(where: has) {
            out.append(Badge(text: "CAM", warn: true))
        } else if has("DVDRIP") || has("DVD") { out.append(Badge(text: "DVD")) }
        return StreamBadges(badges: out, size: Labels.size(sizeBytes))
    }

    static func tag(_ name: String) -> String {
        var n = name
        for ext in [".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts"] where n.lowercased().hasSuffix(ext) { n.removeLast(ext.count) }
        let up = n.uppercased()
        var out = ""
        var lastSpace = false
        for c in up {
            if c.isASCII && (c.isLetter || c.isNumber || c == "+") { out.append(c); lastSpace = false }
            else if !lastSpace { out.append(" "); lastSpace = true }
        }
        return " " + out.trimmingCharacters(in: .whitespaces) + " "
    }
}
