import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback
import DreamPlayer

// Settings › Sources & Quality (fire-home DreamSetupMore.kt: PlaybackPrefs, PlaybackPages, SetupChoices, SpeedTest)
// and the Playback / Sources & Quality groups of the main Settings page (MainActivity.settingsSections).
// Everything is stored where the TV keeps it: the source filters in DreamFilters ("dream_filters"), the audio and
// subtitle choices in Dream Player's PlayerPrefs, so Dream Core and Dream Player read them on the next Play.

// MARK: Preferences (PlaybackPrefs)

enum PlaybackPrefs {
    /// Maximum Quality options (DreamFilters "quality.max"). The TV's chooser has 4K, 1080p and 720p; its Settings row
    /// also reaches SD.
    static let qualities: [(id: String, label: String)] = [("4k", "4K"), ("1080p", "1080p"), ("720p", "720p"), ("sd", "SD")]

    /// The saved Maximum Quality, nil while none was chosen (Dream Core then ranks 1080p first).
    static var quality: String? { DreamFilters.stored()["quality.max"].flatMap { $0.isEmpty ? nil : $0 } }
    static func qualityLabel(_ q: String?) -> String {
        guard let q else { return "Automatic" }
        return qualities.first { $0.id == q }?.label ?? q.uppercased()
    }
    static func setQuality(_ q: String) { DreamFilters.put("quality.max", q) }

    // ---- languages ----

    /// Audio languages offered (code, name).
    static let audio: [(code: String, name: String)] = [
        ("en", "English"), ("es", "Spanish"), ("fr", "French"), ("de", "German"), ("it", "Italian"), ("pt", "Portuguese"),
        ("nl", "Dutch"), ("pl", "Polish"), ("ru", "Russian"), ("uk", "Ukrainian"), ("tr", "Turkish"), ("ar", "Arabic"),
        ("he", "Hebrew"), ("hi", "Hindi"), ("ja", "Japanese"), ("ko", "Korean"), ("zh", "Chinese"), ("sv", "Swedish"),
        ("da", "Danish"), ("no", "Norwegian"), ("fi", "Finnish"), ("el", "Greek"), ("cs", "Czech"), ("hu", "Hungarian"),
        ("ro", "Romanian"),
    ]
    /// Subtitle languages as the subtitle search names them (English names; "Portuguese(Brazil)" after Portuguese).
    static let subs: [String] = {
        let names = audio.map(\.name)
        return Array(names.prefix(6)) + ["Portuguese(Brazil)"] + Array(names.dropFirst(6))
    }()

    static func subLabel(_ name: String) -> String {
        name.replacingOccurrences(of: " ", with: "").caseInsensitiveCompare("Portuguese(Brazil)") == .orderedSame ? "Portuguese (Brazil)" : name
    }

    /// The audio language Dream Player prefers: the viewer's choice, else the iPhone's language.
    static var audioLang: String { PlayerPrefs.audioLang ?? PlayerPrefs.deviceLang }
    static var audioLabel: String {
        let c = audioLang
        return audio.first { $0.code == c }?.name ?? Locale(identifier: "en_US").localizedString(forLanguageCode: c) ?? c.uppercased()
    }
    static func setAudioLang(_ code: String) { PlayerPrefs.setAudioLang(code) }

    /// The first (or second) subtitle language by name; the first defaults to English, the second to none.
    static func subLang(second: Bool = false) -> String? {
        let v = DreamFilters.stored()[second ? "subs.lang2" : "subs.lang1"] ?? ""
        if v.trimmingCharacters(in: .whitespaces).isEmpty || v.caseInsensitiveCompare("none") == .orderedSame { return second ? nil : "English" }
        return v
    }

    static func setSubLang(_ name: String, second: Bool = false) {
        // Dream Player reads both languages on the next Play (DreamSubtitles.prefs, like the TV).
        DreamFilters.put(second ? "subs.lang2" : "subs.lang1", name)
    }

    /// "English" -> "en", "Portuguese(Brazil)" -> "pt"; nil when unknown.
    static func code(_ name: String) -> String? {
        let n = name.trimmingCharacters(in: .whitespaces)
        if n.isEmpty || n.caseInsensitiveCompare("none") == .orderedSame { return nil }
        if subLabel(n) == "Portuguese (Brazil)" { return "pt" }
        if let c = audio.first(where: { $0.name.caseInsensitiveCompare(n) == .orderedSame }) { return c.code }
        let en = Locale(identifier: "en_US")
        return Locale.LanguageCode.isoLanguageCodes.map(\.identifier).first { c in
            c.count == 2 && en.localizedString(forLanguageCode: c)?.caseInsensitiveCompare(n) == .orderedSame
        }
    }

    /// Subtitles show when something starts: the setting on, and not turned off in the player.
    static var subsOn: Bool { DreamFilters.stored()["subs.enabled"] == "true" && PlayerPrefs.subLang != PlayerPrefs.off }

    static func setSubsOn(_ on: Bool) {
        DreamFilters.put("subs.enabled", on ? "true" : "false")
        let player = PlayerPrefs.subLang
        // The player's own choice wins over the setting, so it follows the switch (TV PlaybackPrefs.setSubsOn).
        if on, player == PlayerPrefs.off { PlayerPrefs.setSubLang(code(subLang() ?? "English") ?? "en") }
        else if !on, let player, player != PlayerPrefs.off { PlayerPrefs.setSubLang(PlayerPrefs.off) }
    }

    static func flag(_ key: String, default def: Bool) -> Bool {
        let s = DreamFilters.stored()[key] ?? ""
        return s.isEmpty ? def : s == "true"
    }
    static func setFlag(_ key: String, _ on: Bool) { DreamFilters.put(key, on ? "true" : "false") }

    // ---- source size limits (DreamFilters "size.movie.*" / "size.episode.*") ----

    static let modes: [(id: String, label: String)] = [("off", "No Limit"), ("range", "Size Range"), ("speed", "Match My Connection")]
    static let minGb: [(id: String, label: String)] = ["0", "0.5", "1", "1.5", "2", "3", "4", "5", "8", "10"].map { ($0, $0 == "0" ? "None" : "\($0) GB") }
    static let maxGb: [(id: String, label: String)] = ["1", "1.5", "2", "3", "4", "5", "6", "8", "10", "15", "20", "25", "30", "40", "50", "60", "80", "100"].map { ($0, "\($0) GB") }
    static let mbps: [(id: String, label: String)] = ["5", "10", "15", "20", "25", "30", "40", "50", "75", "100", "150", "200"].map { ($0, "\($0) Mbps") }

    private static func p(_ ep: Bool) -> String { ep ? "size.episode" : "size.movie" }
    static func sizeValue(_ ep: Bool, _ part: String) -> String? { DreamFilters.stored()["\(p(ep)).\(part)"].flatMap { $0.isEmpty ? nil : $0 } }
    static func sizeMode(_ ep: Bool) -> String { sizeValue(ep, "limit").flatMap { m in modes.contains { $0.id == m } ? m : nil } ?? "off" }
    static func minGbValue(_ ep: Bool) -> String { sizeValue(ep, "min_gb") ?? "0" }
    static func maxGbValue(_ ep: Bool) -> String { sizeValue(ep, "max_gb") ?? (ep ? "5" : "20") }
    static func speedMbps(_ ep: Bool) -> String { sizeValue(ep, "speed_mbps") ?? sizeValue(false, "speed_mbps") ?? suggestedMbps }

    /// The last speed test in Mbps, 0 when none ran yet.
    static var lastSpeed: Double {
        get { DreamStore.shared.get(Double.self, "playback.speedMbps") ?? 0 }
        set { DreamStore.shared.set(newValue, "playback.speedMbps") }
    }

    /// The speed list's step at or under the last speed test (or 25 Mbps without one).
    static var suggestedMbps: String {
        let s = lastSpeed
        guard s > 0 else { return "25" }
        return mbps.last { (Double($0.id) ?? 0) <= s * 0.9 }?.id ?? mbps[0].id
    }

    static func setSize(_ ep: Bool, _ part: String, _ value: String) {
        DreamFilters.put("\(p(ep)).\(part)", value)
        // A range always stays a range: the smallest never above the largest.
        let mn = Double(minGbValue(ep)) ?? 0, mx = Double(maxGbValue(ep)) ?? 0
        if part == "min_gb", mn > mx, let up = maxGb.first(where: { (Double($0.id) ?? 0) >= mn }) { DreamFilters.put("\(p(ep)).max_gb", up.id) }
        if part == "max_gb", mx < mn, let down = minGb.last(where: { (Double($0.id) ?? 0) <= mx }) { DreamFilters.put("\(p(ep)).min_gb", down.id) }
        if part == "limit" {
            // A mode's values are saved when it's chosen, so Dream Core never reads a half-made limit.
            if value == "range" { DreamFilters.put("\(p(ep)).min_gb", minGbValue(ep)); DreamFilters.put("\(p(ep)).max_gb", maxGbValue(ep)) }
            if value == "speed" { DreamFilters.put("\(p(ep)).speed_mbps", speedMbps(ep)) }
        }
    }

    static func gb(_ v: String) -> String { (minGb + maxGb).first { $0.id == v }?.label ?? "\(v) GB" }

    static func sizeSummary(_ ep: Bool) -> String {
        switch sizeMode(ep) {
        case "range": return "\(minGbValue(ep) == "0" ? "Up to" : gb(minGbValue(ep)) + " to") \(gb(maxGbValue(ep)))"
        case "speed": return "Up to \(speedMbps(ep)) Mbps"
        default: return "No Limit"
        }
    }

    /// The Settings row's value: "4K · No Size Limit".
    static var summary: String {
        qualityLabel(quality) + (sizeMode(false) == "off" && sizeMode(true) == "off" ? " · No Size Limit" : " · Size Limits On")
    }
}

// MARK: Speed test (SpeedTest)

/// A short download from a neutral speed-test file (Cloudflare's own speed test) over three connections; nothing about
/// the viewer is sent. Used by "Your Internet Speed" (Match My Connection) and free for the setup check.
enum SpeedTest {
    private static let url = URL(string: "https://speed.cloudflare.com/__down?bytes=40000000")!

    private final class Probe: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var total: Int64 = 0
        private var first: Date?
        private var open = 0
        var bytes: Int64 { lock.withLock { total } }
        var started: Bool { lock.withLock { first != nil } }
        var running: Bool { lock.withLock { open > 0 } }
        func begin() { lock.withLock { open += 1 } }
        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            lock.withLock {
                if first == nil { first = Date() }
                total += Int64(data.count)
            }
        }
        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
            lock.withLock { open -= 1 }
        }
    }

    /// The speed in Mbps, or nil when it couldn't run. `live` gets the running speed.
    static func measure(seconds: Double = 4, live: @escaping @MainActor (Double) -> Void = { _ in }) async -> Double? {
        let probe = Probe()
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 6
        let session = URLSession(configuration: config, delegate: probe, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        for _ in 0..<3 {
            var req = URLRequest(url: url)
            req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            probe.begin()
            session.dataTask(with: req).resume()
        }
        // Wait for the first byte (at most 6 s), then a short warm-up so the connection setup doesn't count.
        let deadline = Date().addingTimeInterval(6)
        while !probe.started, probe.running, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(50))
            if Task.isCancelled { return nil }
        }
        guard probe.started else { return nil }
        try? await Task.sleep(for: .milliseconds(600))
        let t0 = Date(), b0 = probe.bytes
        func now() -> Double {
            let dt = max(Date().timeIntervalSince(t0), 0.001)
            return Double(probe.bytes - b0) * 8 / dt / 1_000_000
        }
        while probe.running, Date().timeIntervalSince(t0) < seconds {
            try? await Task.sleep(for: .milliseconds(250))
            if Task.isCancelled { return nil }
            let v = now()
            await live(v)
        }
        let dt = Date().timeIntervalSince(t0)
        guard dt >= 0.5 else { return nil }
        return now()
    }

    /// What the speed is good for, in plain words.
    static func verdict(_ mbps: Double) -> String {
        switch mbps {
        case 40...: "Great for 4K"
        case 25...: "Good for 4K"
        case 8...: "Great for 1080p"
        case 4...: "Good for 720p"
        default: "Slower Connection"
        }
    }

    /// A lower Maximum Quality that suits `mbps` when `quality` ("4k", "1080p", ...) is too much for it, else nil.
    static func suggestion(_ quality: String, _ mbps: Double) -> String? {
        if quality == "4k", mbps < 25 { return mbps >= 8 ? "1080p" : "720p" }
        if quality == "1080p", mbps < 8 { return "720p" }
        return nil
    }
}

// MARK: The Sources & Quality page (PlaybackPages.overview)

struct SourcesQualityView: View {
    var body: some View {
        PageScaffold(title: "Sources & Quality",
                     subtitle: "Choose picture quality, languages, subtitles and file sizes. The defaults suit most people.") {
            SourcesQualityRows()
        }
    }
}

/// The page's groups (Picture, Sound, Subtitles, Source Size, Sources), shared by Settings › Sources & Quality and
/// setup's Playback Preferences step (TV `PlaybackPages.overview`, used by both).
struct SourcesQualityRows: View {
    @State private var tick = 0

    var body: some View {
            VStack(alignment: .leading, spacing: 0) {
                let _ = tick
                PageSection(title: "Picture")
                VStack(spacing: PageSpace.row) {
                    PageLinkRow(title: "Maximum Quality", caption: "Plays the best video up to this quality",
                                value: PlaybackPrefs.qualityLabel(PlaybackPrefs.quality), lead: .symbol("sparkles.tv")) {
                        PageChoiceList(title: "Maximum Quality",
                                       note: "Dream OS plays the best video up to this quality. Choose a lower one if videos pause to load.",
                                       options: PlaybackPrefs.qualities.map { .init(id: $0.id, label: $0.label) },
                                       current: { PlaybackPrefs.quality ?? "" }) { PlaybackPrefs.setQuality($0) }
                    }
                    // Prefer HDR (DreamPlayback HDRPreference): HDR copies may go above Maximum Quality, on Wi-Fi.
                    PageRow(title: "Prefer HDR",
                            caption: HDRPreference.screenShowsHDR
                                ? "Plays an HDR or Dolby Vision copy when there is one, even above Maximum Quality, on Wi-Fi"
                                : "This iPhone's screen doesn't show HDR, so copies stay within Maximum Quality",
                            lead: .symbol("sun.max"), toggle: HDRPreference.chosen) {
                        HDRPreference.set(!HDRPreference.chosen); tick += 1
                    }
                    // Cellular Data Saver (DreamPlayback CellularSaver): Ask Me / Always On / Off, and its quality limit.
                    PageLinkRow(title: "Cellular Data Saver", caption: "Plays smaller copies on mobile data; turns off by itself on Wi-Fi",
                                value: CellularSaver.modeLabel, lead: .symbol("antenna.radiowaves.left.and.right")) {
                        PageChoiceList(title: "Cellular Data Saver",
                                       note: "On mobile data, a personal hotspot or Low Data Mode, Data Saver plays a smaller copy so you use less data. Ask Me asks the first time Dream sees mobile data. Wi-Fi always uses Maximum Quality.",
                                       options: CellularSaver.modeChoices.map { .init(id: $0.id, label: $0.label) },
                                       current: { CellularSaver.mode.rawValue }) { CellularSaver.setMode(id: $0) }
                    }
                    PageLinkRow(title: "Data Saver Quality", caption: "The highest quality Data Saver plays",
                                value: CellularSaver.limit, lead: .symbol("dial.low")) {
                        PageChoiceList(title: "Data Saver Quality",
                                       note: "On mobile data with Data Saver on, videos play up to this quality, smaller files first.",
                                       options: CellularSaver.limitChoices.map { .init(id: $0.id, label: $0.label) },
                                       current: { CellularSaver.limit }) { CellularSaver.setLimit($0) }
                    }
                }
                .padding(.top, 10)

                PageSection(title: "Sound").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageLinkRow(title: "Audio Language", caption: "Preferred when a video has several",
                                value: PlaybackPrefs.audioLabel, lead: .symbol("speaker.wave.2")) {
                        PageChoiceList(title: "Audio Language", note: nil,
                                       options: PlaybackPrefs.audio.map { .init(id: $0.code, label: $0.name) },
                                       current: { PlaybackPrefs.audioLang }) { PlaybackPrefs.setAudioLang($0) }
                    }
                    // AnimeDubs (DreamCore): Dream Core's ranking for anime. Not offered when the audio language is Japanese.
                    if AnimeDubs.offered {
                        PageRow(title: AnimeDubs.settingLabel, caption: AnimeDubs.settingCaption,
                                lead: .symbol("person.wave.2"), toggle: AnimeDubs.preferDubs) {
                            AnimeDubs.preferDubs.toggle(); tick += 1
                        }
                    }
                }
                .padding(.top, 10)

                PageSection(title: "Subtitles").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageRow(title: "Subtitles", caption: "Turns subtitles on when a movie or show starts",
                            lead: .symbol("captions.bubble"), toggle: PlaybackPrefs.subsOn) {
                        PlaybackPrefs.setSubsOn(!PlaybackPrefs.subsOn); tick += 1
                    }
                    PageLinkRow(title: "Subtitle Language", value: PlaybackPrefs.subLabel(PlaybackPrefs.subLang() ?? "English"),
                                lead: .symbol("character.bubble")) {
                        PageChoiceList(title: "Subtitle Language", note: nil,
                                       options: PlaybackPrefs.subs.map { .init(id: $0, label: PlaybackPrefs.subLabel($0)) },
                                       current: { PlaybackPrefs.subLang() ?? "English" }) { PlaybackPrefs.setSubLang($0) }
                    }
                    PageLinkRow(title: "Second Choice", caption: "Used when the first isn't available",
                                value: PlaybackPrefs.subLang(second: true).map(PlaybackPrefs.subLabel) ?? "None",
                                lead: .symbol("character.bubble.fill")) {
                        PageChoiceList(title: "Second Subtitle Language", note: nil,
                                       options: [.init(id: "None", label: "None")] + PlaybackPrefs.subs.map { .init(id: $0, label: PlaybackPrefs.subLabel($0)) },
                                       current: { PlaybackPrefs.subLang(second: true) ?? "None" }) { PlaybackPrefs.setSubLang($0, second: true) }
                    }
                    PageRow(title: "Use Subtitles in the Video First", caption: "Before downloading any",
                            lead: .symbol("film"), toggle: PlaybackPrefs.flag("subs.embedded", default: true)) {
                        PlaybackPrefs.setFlag("subs.embedded", !PlaybackPrefs.flag("subs.embedded", default: true)); tick += 1
                    }
                }
                .padding(.top, 10)

                PageSection(title: "Source Size").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageLinkRow(title: "Source Size Limits",
                                caption: "Movies: \(PlaybackPrefs.sizeSummary(false)) · Episodes: \(PlaybackPrefs.sizeSummary(true))",
                                lead: .symbol("externaldrive")) { SizeLimitsView() }
                }
                .padding(.top, 10)

                PageSection(title: "Sources").padding(.top, PageSpace.group)
                SourceFilterRows(tick: $tick).padding(.top, 10)
            }
            .onAppear { tick += 1 }
    }
}

/// The source filters (Settings › Sources & Quality on the TV), shared by the main Settings page and this page.
struct SourceFilterRows: View {
    @Binding var tick: Int

    var body: some View {
        let f = DreamFilters.current()
        let _ = tick
        VStack(spacing: PageSpace.row) {
            PageRow(title: "Hide AV1", caption: "Older iPhones Play AV1 Less Smoothly", lead: .symbol("eye.slash"), toggle: f.hideAv1) {
                flip("hide.av1", f.hideAv1)
            }
            PageRow(title: "Hide HEVC (H.265)", lead: .symbol("eye.slash"), toggle: f.hideHevc) { flip("hide.hevc", f.hideHevc) }
            PageRow(title: "Hide HDR", caption: "For Screens Without HDR", lead: .symbol("eye.slash"), toggle: f.hideHdr) { flip("hide.hdr", f.hideHdr) }
            PageRow(title: "Hide Dolby Vision", caption: "Keeps Files That Also Have HDR", lead: .symbol("eye.slash"), toggle: f.hideDv) {
                flip("hide.dv", f.hideDv)
            }
            PageRow(title: "HDR and Dolby Vision First", lead: .symbol("sun.max"), toggle: f.preferHdr) { flip("prefer.hdr", f.preferHdr) }
        }
    }

    private func flip(_ key: String, _ on: Bool) {
        PlaybackPrefs.setFlag(key, !on)
        tick += 1
    }
}

// MARK: Source Size Limits (PlaybackPages.sizes)

struct SizeLimitsView: View {
    @State private var tick = 0

    var body: some View {
        PageScaffold(title: "Source Size Limits",
                     subtitle: "Skip files that are too large or too small. Match My Connection skips files too large to play smoothly on your connection.") {
            VStack(alignment: .leading, spacing: 0) {
                let _ = tick
                ForEach([false, true], id: \.self) { ep in
                    PageSection(title: ep ? "Episodes" : "Movies").padding(.top, ep ? PageSpace.group : 0)
                    VStack(spacing: PageSpace.row) {
                        let mode = PlaybackPrefs.sizeMode(ep)
                        PageLinkRow(title: "Limit", value: PlaybackPrefs.modes.first { $0.id == mode }?.label, lead: .symbol("slider.horizontal.3")) {
                            PageChoiceList(title: ep ? "Episode Size Limit" : "Movie Size Limit", note: nil,
                                           options: PlaybackPrefs.modes.map { m in
                                               .init(id: m.id, label: m.label, caption: m.id == "range" ? "Set the smallest and largest file"
                                                     : m.id == "speed" ? "Skips files too large for your connection" : "Any file size")
                                           },
                                           current: { PlaybackPrefs.sizeMode(ep) }) { PlaybackPrefs.setSize(ep, "limit", $0) }
                        }
                        if mode == "range" {
                            PageLinkRow(title: "Smallest File", value: PlaybackPrefs.gb(PlaybackPrefs.minGbValue(ep)), lead: .symbol("arrow.down.to.line")) {
                                PageChoiceList(title: "Smallest File", note: nil, options: PlaybackPrefs.minGb.map { .init(id: $0.id, label: $0.label) },
                                               current: { PlaybackPrefs.minGbValue(ep) }) { PlaybackPrefs.setSize(ep, "min_gb", $0) }
                            }
                            PageLinkRow(title: "Largest File", value: PlaybackPrefs.gb(PlaybackPrefs.maxGbValue(ep)), lead: .symbol("arrow.up.to.line")) {
                                PageChoiceList(title: "Largest File", note: nil, options: PlaybackPrefs.maxGb.map { .init(id: $0.id, label: $0.label) },
                                               current: { PlaybackPrefs.maxGbValue(ep) }) { PlaybackPrefs.setSize(ep, "max_gb", $0) }
                            }
                        }
                        if mode == "speed" {
                            let measured = PlaybackPrefs.lastSpeed
                            PageLinkRow(title: "Your Internet Speed",
                                        caption: measured > 0 ? "Last speed test: \(Int(measured)) Mbps" : nil,
                                        value: "\(PlaybackPrefs.speedMbps(ep)) Mbps", lead: .symbol("speedometer")) {
                                InternetSpeedView(episodes: ep)
                            }
                        }
                    }
                    .padding(.top, 10)
                }
            }
        }
        .onAppear { tick += 1 }
    }
}

/// "Your Internet Speed": the speed list, with a speed test on top that suggests the right step.
struct InternetSpeedView: View {
    var episodes: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var testing = false
    @State private var live: Double?
    @State private var result: String?

    var body: some View {
        PageScaffold(title: "Your Internet Speed",
                     subtitle: "Files too large to stream smoothly at this speed are skipped.") {
            VStack(alignment: .leading, spacing: 0) {
                PageRow(title: testing ? "Testing Your Connection…" : "Test My Connection",
                        caption: result ?? (PlaybackPrefs.lastSpeed > 0 ? "Last speed test: \(Int(PlaybackPrefs.lastSpeed)) Mbps" : "Takes about five seconds"),
                        value: live.map { "\(Int($0)) Mbps" }, lead: .symbol("speedometer")) {
                    guard !testing else { return }
                    Task { await runTest() }
                }
                PageSection(title: "Speed").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    ForEach(PlaybackPrefs.mbps, id: \.id) { o in
                        PageRow(title: o.label, lead: .mark(current == o.id ? .pick : .off)) { pick(o.id) }
                    }
                }
                .padding(.top, 10)
            }
        }
        .onAppear { current = PlaybackPrefs.speedMbps(episodes) }
    }

    private func runTest() async {
        testing = true; result = nil; live = nil
        let mbps = await SpeedTest.measure { live = $0 }
        testing = false
        guard let mbps else {
            live = nil
            result = "Couldn't test your connection right now. Try again."
            PageHaptics.warning()
            return
        }
        live = mbps
        PlaybackPrefs.lastSpeed = mbps
        result = "\(Int(mbps)) Mbps  ·  \(SpeedTest.verdict(mbps))"
        PageHaptics.success()
        PlaybackPrefs.setSize(episodes, "speed_mbps", PlaybackPrefs.suggestedMbps)
        current = PlaybackPrefs.speedMbps(episodes)
    }

    private func pick(_ id: String) {
        PageHaptics.select()
        current = id
        PlaybackPrefs.setSize(episodes, "speed_mbps", id)
        Task { try? await Task.sleep(for: .milliseconds(220)); dismiss() }
    }
}

// MARK: A list to pick one from (SetupChoices)

/// The chosen option checked; picking saves at once and goes back.
struct PageChoiceList: View {
    struct Option: Identifiable {
        var id: String
        var label: String
        var caption: String? = nil
    }
    var title: String
    var note: String?
    var options: [Option]
    var current: () -> String
    var pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var chosen = ""
    @State private var leaving = false

    var body: some View {
        PageScaffold(title: title, subtitle: note) {
            VStack(spacing: PageSpace.row) {
                ForEach(options) { o in
                    PageRow(title: o.label, caption: o.caption, lead: .mark(chosen == o.id ? .pick : .off)) {
                        guard !leaving else { return }
                        leaving = true
                        PageHaptics.select()
                        pick(o.id)
                        chosen = o.id
                        Task { try? await Task.sleep(for: .milliseconds(220)); dismiss() }
                    }
                }
            }
        }
        .onAppear { chosen = current(); leaving = false }
    }
}

// MARK: The main Settings page's Playback and Sources & Quality groups

/// Settings' "Playback" and "Sources & Quality" groups (MainActivity.settingsSections), each row 1:1 with the TV's
/// except Instant Play, which arrives with Dream Core's Instant Play on iPhone.
struct PlaybackSettingsGroups: View {
    @State private var tick = 0

    var body: some View {
        let _ = tick
        let f = DreamFilters.current()
        PageSection(title: "Playback").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            // One setting for the whole app, as on the TV; Dream Player reads the same values.
            PageRow(title: "Autoplay Next Episode", caption: "Up Next Counts Down in the Final Minute", lead: .symbol("play"),
                    toggle: PlaybackSettings.autoplayNext) {
                PlaybackSettings.autoplayNext.toggle(); tick += 1
            }
            PageRow(title: "Skip Intros Automatically", caption: "When Dream Player Knows Where the Intro Ends",
                    lead: .symbol("forward"), toggle: PlaybackSettings.autoSkipIntros) {
                PlaybackSettings.autoSkipIntros.toggle(); tick += 1
            }
            // Dream Player's level swipes (DreamPlayer/UI/LevelSwipes.swift), per profile.
            PageRow(title: "Swipe for Brightness and Volume", caption: "Left Side for Brightness, Right Side for Volume",
                    lead: .symbol("sun.max"), toggle: PlayerPrefs.levelSwipes) {
                PlayerPrefs.levelSwipes.toggle(); tick += 1
            }
            PageRow(title: "Subtitles", caption: "Turns On Subtitles in Your Language", lead: .symbol("captions.bubble"),
                    toggle: PlaybackPrefs.subsOn) {
                PlaybackPrefs.setSubsOn(!PlaybackPrefs.subsOn); tick += 1
            }
            PageLinkRow(title: "Subtitle Languages", caption: "First and Second Choice",
                        value: f.subLangs.map(PlaybackPrefs.subLabel).joined(separator: ", "), lead: .symbol("character.bubble")) {
                SourcesQualityView()
            }
            PageRow(title: "Use Subtitles in the Video First", caption: "Before Downloading Any", lead: .symbol("film"),
                    toggle: PlaybackPrefs.flag("subs.embedded", default: true)) {
                PlaybackPrefs.setFlag("subs.embedded", !PlaybackPrefs.flag("subs.embedded", default: true)); tick += 1
            }
            PageRow(title: "Subtitle Size", value: ["Small", "Medium", "Large", "Extra Large"][PlayerPrefs.subSize], lead: .symbol("textformat.size")) {
                PlayerPrefs.subSize = (PlayerPrefs.subSize + 1) % PlayerPrefs.sizes.count; tick += 1
            }
            PageRow(title: "Subtitle Style", value: PlayerPrefs.styles[PlayerPrefs.subStyle], lead: .symbol("textformat")) {
                PlayerPrefs.subStyle = (PlayerPrefs.subStyle + 1) % PlayerPrefs.styles.count; tick += 1
            }
            PageRow(title: "Subtitle Position", value: PlayerPrefs.positions[PlayerPrefs.subPos], lead: .symbol("arrow.up.and.down.text.horizontal")) {
                PlayerPrefs.subPos = (PlayerPrefs.subPos + 1) % PlayerPrefs.positions.count; tick += 1
            }
        }
        .padding(.top, 10)

        PageSection(title: "Sources & Quality").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            PageRow(title: "Highest Quality", caption: "Hides Anything Better Than This",
                    value: PlaybackPrefs.qualityLabel(PlaybackPrefs.quality), lead: .symbol("sparkles.tv")) {
                let q = PlaybackPrefs.qualities
                let i = q.firstIndex { $0.id == PlaybackPrefs.quality }
                PlaybackPrefs.setQuality(q[i.map { ($0 + 1) % q.count } ?? 1].id); tick += 1
            }
            SourceFilterRows(tick: $tick)
            PageLinkRow(title: "All Source Settings", caption: "Size Limits, Languages and Subtitles", lead: .symbol("slider.horizontal.3")) {
                SourcesQualityView()
            }
        }
        .padding(.top, 10)
        .onAppear { tick += 1 }
    }
}
