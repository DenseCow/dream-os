import Foundation
import DreamCore

/// Smart source learning (Instant Play, 2026-10-28): which kinds of release start fast and play cleanly on this
/// phone, and which fail. Every link's outcome is recorded against a few traits of its release: the scraper provider,
/// the release group, the container, the video codec and a size bucket. Dream Core's ranking reads a nudge from it:
/// a trait set that failed repeatedly goes a step later, one that has started quickly and cleanly several times goes
/// a step earlier, always inside the viewer's own quality and size filters (those are never touched) and after the
/// quality tier, so a learned nudge only reorders otherwise-equal releases.
final class SourceMemory: @unchecked Sendable {
    static let shared = SourceMemory()

    struct Stat: Codable, Sendable {
        var ok = 0
        var fail = 0
        /// Rolling first-frame time of the clean starts (ms), recent ones weighing more.
        var ms: Int64 = 0
        var at: Int64 = 0
    }

    /// What a trait needs before it says anything.
    static let minSamples = 3
    /// A start slower than this isn't "fast" even when it worked.
    static let fastMs: Int64 = 3_500
    private static let key = "core.learn"
    private static let maxKeys = 600

    private let lock = NSLock()
    private var stats: [String: Stat]
    private var dirty = false

    private init() {
        stats = DreamStore.shared.get([String: Stat].self, Self.key) ?? [:]
    }

    /// A read-only copy for one ranking pass (no lock per candidate).
    struct Snapshot: Sendable {
        let stats: [String: Stat]
        var isEmpty: Bool { stats.isEmpty }

        /// -1: this release's traits have started fast and cleanly here; +1: they have failed repeatedly; 0: nothing known.
        func nudge(_ traits: [String]) -> Int {
            if stats.isEmpty { return 0 }
            var bad = false, fast = false, anyFail = false
            for t in traits {
                guard let s = stats[t] else { continue }
                // The release group is the most telling trait: two failures and more failures than clean plays is enough.
                if t.hasPrefix("grp:") && s.fail >= 2 && s.fail > s.ok { bad = true }
                guard s.ok + s.fail >= SourceMemory.minSamples else { continue }
                if Double(s.fail) / Double(s.ok + s.fail) >= 0.6 { bad = true }
                if s.fail > 0 { anyFail = true }
                if s.fail == 0 && s.ok >= SourceMemory.minSamples && s.ms > 0 && s.ms <= SourceMemory.fastMs { fast = true }
            }
            if bad { return 1 }
            if fast && !anyFail { return -1 }
            return 0
        }
    }

    func snapshot() -> Snapshot { lock.withLock { Snapshot(stats: stats) } }

    /// A link played its first frame after [ms] (clean start) or failed to open / start ([ok] false).
    func record(_ traits: [String], ok: Bool, ms: Int64?) {
        if traits.isEmpty { return }
        let now = wallMs()
        lock.withLock {
            for t in traits {
                var s = stats[t] ?? Stat()
                if ok {
                    s.ok += 1
                    if let ms, ms > 0 { s.ms = s.ms > 0 ? (s.ms * 2 + ms) / 3 : ms }
                } else {
                    s.fail += 1
                }
                s.at = now
                stats[t] = s
            }
            if stats.count > Self.maxKeys {
                for (k, _) in stats.sorted(by: { $0.value.at < $1.value.at }).prefix(stats.count - Self.maxKeys + 50) { stats[k] = nil }
            }
            DreamStore.shared.set(stats, Self.key)
        }
        let what = traits.first { $0.hasPrefix("grp:") } ?? traits.first ?? ""
        plog.info("Dream Core: learned \(ok ? "clean start" : "failure", privacy: .public) for \(what, privacy: .public) (\(traits.count) traits)")
    }

    // MARK: Traits of a release

    /// The traits of a release: "prov:<scraper>", "grp:<release group>", "ext:<container>", "codec:<video codec>",
    /// "size:<bucket>". Built from what Dream Core knows up front (the scraped name, provider and size); for a link
    /// whose scrape is gone, from the link's file name and size.
    static func traits(name: String, fileName: String?, provider: String?, sizeBytes: Int64) -> [String] {
        var out: [String] = []
        if let p = provider?.lowercased(), !p.isEmpty { out.append("prov:" + (p.split(separator: ":").last.map(String.init) ?? p)) }
        if let g = group(name) { out.append("grp:" + g) }
        let ext = container(fileName ?? name)
        if let ext { out.append("ext:" + ext) }
        let t = name.uppercased()
        if tok(t, "HEVC") || tok(t, "X265") || tok(t, "H265") || tok(t, "H.265") { out.append("codec:hevc") }
        else if tok(t, "AV1") { out.append("codec:av1") }
        else if tok(t, "X264") || tok(t, "H264") || tok(t, "H.264") || tok(t, "AVC") { out.append("codec:avc") }
        if sizeBytes > 0 {
            let gb = Double(sizeBytes) / Double(1 << 30)
            out.append("size:" + (gb < 2 ? "s" : gb < 6 ? "m" : gb < 15 ? "l" : "xl"))
        }
        return out
    }

    /// The release group: what follows the last '-' of the name (its extension off), when it looks like one.
    static func group(_ name: String) -> String? {
        var base = name
        if let dot = base.lastIndex(of: "."), base.distance(from: dot, to: base.endIndex) <= 5 { base = String(base[..<dot]) }
        guard let dash = base.lastIndex(of: "-") else { return nil }
        var g = String(base[base.index(after: dash)...]).trimmingCharacters(in: .whitespaces)
        if let sp = g.firstIndex(where: { $0 == " " || $0 == "[" || $0 == "(" }) { g = String(g[..<sp]) }
        g = g.lowercased()
        guard g.count >= 2, g.count <= 20, g.first?.isLetter == true || g.first?.isNumber == true else { return nil }
        // Numbers alone (a "-1080p" tail) aren't a group.
        if g.allSatisfy({ $0.isNumber }) { return nil }
        return g
    }

    static func container(_ name: String) -> String? {
        let n = name.lowercased()
        for e in ["mkv", "mp4", "m4v", "avi", "ts", "webm", "mov"] where n.hasSuffix("." + e) { return e == "m4v" ? "mp4" : e }
        return nil
    }

    private static func tok(_ t: String, _ w: String) -> Bool {
        guard let r = t.range(of: w) else { return false }
        let before = r.lowerBound == t.startIndex ? nil : t[t.index(before: r.lowerBound)]
        let after = r.upperBound == t.endIndex ? nil : t[r.upperBound]
        func sep(_ c: Character?) -> Bool { c.map { !($0.isLetter || $0.isNumber) } ?? true }
        return sep(before) && sep(after)
    }
}
