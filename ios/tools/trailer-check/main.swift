// Timing check for the trailer look-up's direct path (app/Sources/DreamPlayer/Session/TrailerDirect.swift), run on
// Linux by tools/trailer-check.sh against the live service: three trailers in a row from a cold start.
// Passes when the visitor id is fetched once (the 2nd and 3rd look-ups reuse it, nothing is rebuilt), every look-up
// answers within 2.5 s, the pick is the same top quality as before (H.264 up to 1080p + AAC), and the picked
// streams' first and last bytes are readable (a stream that stops partway would fail the tail read).
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

let keys = CommandLine.arguments.count > 1 ? Array(CommandLine.arguments.dropFirst()) : ["Bmhqw0F3q-g", "TcMBFSGVi1c", "d9MyW72ELq0"]
let suite = "trailer-check"
UserDefaults().removePersistentDomain(forName: suite)
let direct = TrailerDirect(defaults: UserDefaults(suiteName: suite)!)
var failures: [String] = []

func readable(_ url: URL, _ range: String) async -> String {
    var r = URLRequest(url: url)
    r.setValue("bytes=\(range)", forHTTPHeaderField: "Range")
    guard let (d, resp) = try? await direct.session.data(for: r), let h = resp as? HTTPURLResponse else { return "error" }
    return h.statusCode == 206 ? "206 (\(d.count / 1024) KB)" : "HTTP \(h.statusCode)"
}

func length(_ url: URL) -> Int? {
    URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "clen" }?.value.flatMap(Int.init)
}

for (i, key) in keys.enumerated() {
    do {
        let a = try await direct.formats(forKey: key)
        guard let c = TrailerPick.pick(a.formats) else { failures.append("\(key): nothing playable"); continue }
        let v = a.visitorMs.map { "\($0) ms" } ?? "-"
        print("lookup \(i + 1) \(key): \(a.ms) ms (visitor id \(a.visitorSource), fetch \(v)), \(c.label)")
        if i > 0 && a.visitorSource != "kept" { failures.append("\(key): visitor id \(a.visitorSource) on look-up \(i + 1), expected kept") }
        if a.ms > 2500 { failures.append("\(key): \(a.ms) ms, over 2.5 s") }
        if !c.label.contains("H.264") || c.audio == nil { failures.append("\(key): pick \(c.label), expected H.264 + AAC") }
        let len = length(c.video) ?? 0
        let head = await readable(c.video, "0-262143")
        let tail = len > 0 ? await readable(c.video, "\(len - 262144)-\(len - 1)") : "no length"
        print("  video head \(head), tail \(tail)")
        if !head.hasPrefix("206") || !tail.hasPrefix("206") { failures.append("\(key): stream not readable to the end") }
    } catch {
        failures.append("\(key): \(error)")
    }
}
UserDefaults().removePersistentDomain(forName: suite)
if failures.isEmpty { print("PASS") } else { print("FAIL"); failures.forEach { print("  \($0)") }; exit(1) }
