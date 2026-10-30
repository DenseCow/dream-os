import SwiftUI
import DreamCore
import DreamUI

/// The side sheets (fire-home PlayerMenus + SourcePicker): one at a time, sliding in from the right over a scrim.
struct SheetHost: View {
    @ObservedObject var session: PlayerSession
    let sheet: PlayerSheet
    let close: () -> Void
    let openXRay: () -> Void
    @State private var shown = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                LinearGradient(colors: [.clear, .black.opacity(0.46), .black.opacity(0.80)], startPoint: .leading, endPoint: .trailing)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { dismiss() }
                VStack(alignment: .leading, spacing: 0) {
                    content
                }
                .frame(width: min(width, geo.size.width - 40))
                .frame(maxHeight: .infinity)
                .background(SheetBackground())
                .padding(.vertical, 10).padding(.trailing, 10)
                .offset(x: shown ? 0 : 60).opacity(shown ? 1 : 0)
            }
        }
        .onAppear { withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.3)) { shown = true } }
    }

    private var width: CGFloat {
        switch sheet {
        case .audio: return 440
        case .speed: return 360
        case .info: return 480
        case .episodes, .source: return 540
        default: return 420
        }
    }

    private func dismiss() {
        withAnimation(.easeIn(duration: 0.2)) { shown = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { close() }
    }

    @ViewBuilder private var content: some View {
        switch sheet {
        case .audio: AudioSheet(session: session, close: dismiss)
        case .speed: SpeedSheet(session: session, close: dismiss)
        case .info: InfoSheet(session: session, close: dismiss, openXRay: openXRay)
        case .episodes: EpisodesSheet(session: session, close: dismiss)
        case .source: SourceSheet(session: session, close: dismiss)
        default: EmptyView()
        }
    }
}

struct SheetBackground: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 28, style: .continuous)
            .fill(LinearGradient(colors: [Color(white: 0.06).opacity(0.95), Color(hex: 0x040405).opacity(0.96)], startPoint: .top, endPoint: .bottom))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(.white.opacity(0.10), lineWidth: 1))
    }
}

struct SheetHeader: View {
    var kicker: String?
    let title: String
    let close: () -> Void
    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                if let kicker, !kicker.isEmpty {
                    Text(kicker).font(.system(size: 12, weight: .bold)).foregroundStyle(.white.opacity(0.62)).lineLimit(1)
                }
                Text(title).font(.system(size: 24, weight: .bold)).foregroundStyle(.white).lineLimit(1)
            }
            Spacer(minLength: 12)
            Button(action: close) { Image(systemName: "xmark").font(.system(size: 14, weight: .semibold)) }
                .buttonStyle(RoundGlassStyle(size: 36)).accessibilityLabel("Close")
        }
        .padding(.horizontal, 22).padding(.top, 22).padding(.bottom, 12)
    }
}

struct SectionHeading: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 13, weight: .bold)).foregroundStyle(.white.opacity(0.55))
            .padding(.top, 18).padding(.bottom, 6).padding(.horizontal, 8)
    }
}

/// A sheet row: pressed it is a pearl box with dark ink and a rainbow edge; the chosen one has a check.
struct SheetRow: View {
    let title: String
    var subtitle: String?
    var checked = false
    var trailing: String?
    var dimmed = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "checkmark").font(.system(size: 14, weight: .bold)).opacity(checked ? 1 : 0).frame(width: 18)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    if let subtitle, !subtitle.isEmpty { Text(subtitle).font(.system(size: 13, weight: .medium)).opacity(0.62).lineLimit(1) }
                }
                Spacer(minLength: 8)
                if let trailing { Text(trailing).font(.system(size: 15, weight: .semibold)).opacity(0.8) }
            }
            .opacity(dimmed ? 0.42 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(RowStyle(chosen: checked))
    }
}

struct RowStyle: ButtonStyle {
    var chosen = false
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : .white)
            .padding(.horizontal, 12).padding(.vertical, 11)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(p ? Palette.pearl : .white.opacity(chosen ? 0.06 : 0)))
            .rainbowBorder(p, radius: 12, width: 2)
            .animation(.easeOut(duration: 0.15), value: p)
    }
}

// MARK: Audio & Subtitles

private struct AudioSheet: View {
    @ObservedObject var session: PlayerSession
    let close: () -> Void
    @State private var rev = 0

    var body: some View {
        let audio = session.tracks(.audio)
        let text = session.tracks(.text)
        SheetHeader(title: "Audio & Subtitles", close: close)
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                SectionHeading(text: "Audio")
                if audio.isEmpty {
                    Text("Default Audio").font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.5)).padding(.horizontal, 12)
                }
                ForEach(audio) { t in
                    SheetRow(title: t.title?.nilIfEmpty ?? t.label.nilIfEmpty ?? Labels.languageName(t.language),
                             subtitle: t.supported ? t.detail : [t.detail, "Not Supported on This iPhone"].compactMap { $0 }.joined(separator: "  ·  "),
                             checked: t.selected, dimmed: !t.supported) {
                        haptic(.light)
                        if !t.supported { session.show(toast: "This audio format can't play on this iPhone") } else { session.pickAudio(t) }
                        refresh()
                    }
                }
                SheetRow(title: "Volume Boost", trailing: PlayerPrefs.boostLabels[PlayerPrefs.boost]) { haptic(.light); session.cycleBoost(); refresh() }
                SectionHeading(text: "Subtitles")
                SheetRow(title: "Off", checked: !text.contains { $0.selected }) {
                    haptic(.light)
                    if text.contains(where: { $0.selected }) { session.pickSubtitles(nil) }
                    refresh()
                }
                ForEach(text) { t in
                    SheetRow(title: t.label.nilIfEmpty ?? Labels.languageName(t.language), subtitle: t.supported ? nil : "Not Supported on This iPhone",
                             checked: t.selected, dimmed: !t.supported) {
                        haptic(.light)
                        if !t.supported { session.show(toast: "These subtitles can't be shown on this device") }
                        else if !t.selected { session.pickSubtitles(t) }
                        refresh()
                    }
                }
                SectionHeading(text: "Subtitle Appearance")
                SubtitlePreview().padding(.horizontal, 8)
                Segmented(title: "Size", options: PlayerPrefs.sizes, selected: PlayerPrefs.subSize) { PlayerPrefs.subSize = $0; styleChanged() }
                Segmented(title: "Style", options: PlayerPrefs.styles, selected: PlayerPrefs.subStyle) { PlayerPrefs.subStyle = $0; styleChanged() }
                Segmented(title: "Position", options: PlayerPrefs.positions, selected: PlayerPrefs.subPos) { PlayerPrefs.subPos = $0; styleChanged() }
            }
            .padding(.horizontal, 12).padding(.bottom, 22)
            .id(rev)
        }
    }

    private func refresh() {
        Task { try? await Task.sleep(for: .milliseconds(120)); rev += 1 }
    }

    private func styleChanged() {
        session.subtitleStyleChanged(raised: false)
        rev += 1
    }
}

private struct SubtitlePreview: View {
    var body: some View {
        let scale = PlayerPrefs.sizeScale[PlayerPrefs.subSize]
        let style = PlayerPrefs.subStyle
        let bottom: [CGFloat] = [6, 13, 26]
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [Color(hex: 0x2A2A30), Color(hex: 0x0C0C0E)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Text("I'll be right there.")
                .font(.system(size: 15 * scale, weight: .medium))
                .foregroundStyle(style == 2 ? Color(hex: 0xFFE45C) : .white)
                .padding(.horizontal, style == 1 ? 8 : 0).padding(.vertical, style == 1 ? 4 : 0)
                .background(style == 1 ? RoundedRectangle(cornerRadius: 4).fill(Color(red: 8 / 255, green: 6 / 255, blue: 18 / 255).opacity(190 / 255)) : nil)
                .shadow(color: style == 1 ? .clear : .black, radius: 3, y: 1)
                .padding(.bottom, bottom[PlayerPrefs.subPos])
        }
        .frame(height: 92)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct Segmented: View {
    let title: String
    let options: [String]
    let selected: Int
    let pick: (Int) -> Void
    var body: some View {
        HStack {
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
            Spacer()
            HStack(spacing: 6) {
                ForEach(options.indices, id: \.self) { i in
                    Button(options[i]) { haptic(.light); pick(i) }
                        .buttonStyle(ChipStyle(selected: i == selected))
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
    }
}

struct ChipStyle: ButtonStyle {
    var selected: Bool
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(p ? Palette.ink : .white)
            .padding(.horizontal, 12).frame(minHeight: 34)
            .background(Capsule().fill(p ? Palette.pearl : .white.opacity(selected ? 0.16 : 0.05)))
            .overlay(Capsule().strokeBorder(.white.opacity(selected && !p ? 0.30 : 0.10), lineWidth: 1))
            .rainbowBorder(p, radius: 17, width: 2)
    }
}

// MARK: Playback (speed, intros, sleep)

private struct SpeedSheet: View {
    @ObservedObject var session: PlayerSession
    let close: () -> Void
    @State private var autoSkip = PlayerPrefs.autoSkipIntros
    static let speeds: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    var body: some View {
        SheetHeader(title: "Playback", close: close)
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                SectionHeading(text: "Speed")
                ForEach(Self.speeds, id: \.self) { s in
                    SheetRow(title: Labels.speed(s), checked: abs(session.speed - s) < 0.01) {
                        haptic(.light); session.setSpeed(s); close()
                    }
                }
                SectionHeading(text: "Intros")
                SheetRow(title: "Skip Intros Automatically", subtitle: "Skips intros and recaps when they're known", checked: autoSkip) {
                    haptic(.light)
                    autoSkip.toggle()
                    PlayerPrefs.autoSkipIntros = autoSkip
                    session.show(toast: autoSkip ? "Intros will be skipped automatically" : "Skip Intro button only")
                }
                // No sleep timer on iPhone (owner, 2026-10-28): the phone locks itself.
            }
            .padding(.horizontal, 12).padding(.bottom, 22)
        }
    }
}


// MARK: Info

private struct InfoSheet: View {
    @ObservedObject var session: PlayerSession
    let close: () -> Void
    let openXRay: () -> Void

    var body: some View {
        let r = session.request
        SheetHeader(kicker: kicker(r), title: r?.episodeName ?? session.episodeInfo?.name ?? r?.title.name ?? "Now Playing", close: close)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // Hidden only once the lookup said nobody is listed (TV PlayerXRay.canOpen).
                if !session.isTrailer, session.xrayCast?.isEmpty != true {
                    Button { openXRay() } label: { Label("Cast & Characters", systemImage: "person.2").labelStyle(GapLabelStyle()) }
                        .buttonStyle(ChipStyle(selected: false))
                }
                if let meta = meta(r), !meta.isEmpty {
                    Text(meta).font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.66))
                }
                Text(synopsis).font(.system(size: 15)).foregroundStyle(.white.opacity(0.84)).lineSpacing(3)
                SectionHeading(text: "Now Playing").padding(.horizontal, -8)
                let parts = session.engine.facts().summary.split(separator: "·").map { $0.trimmingCharacters(in: .whitespaces) }.prefix(6)
                if !parts.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(Array(parts), id: \.self) { p in
                            Text(p).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(Capsule().fill(.white.opacity(0.08)))
                        }
                    }
                }
                if !session.isTrailer { fact("Picture", pictureLine) }
                fact("Audio", session.tracks(.audio).first { $0.selected }?.label ?? "Default")
                fact("Subtitles", session.tracks(.text).first { $0.selected }?.label ?? "Off")
                if abs(session.speed - 1) > 0.01 { fact("Speed", Labels.speed(session.speed)) }
                if let svc = session.link?.service { fact("Source", serviceName(svc)) }
            }
            .padding(.horizontal, 22).padding(.bottom, 22)
        }
    }

    private var synopsis: String {
        if session.details == nil && session.episodeInfo == nil { return "Loading…" }
        return session.episodeInfo?.overview.nilIfEmpty ?? session.details?.title.overview.nilIfEmpty ?? "No synopsis available."
    }

    private func kicker(_ r: PlayRequest?) -> String? {
        guard let r else { return nil }
        if let s = r.season, let e = r.episode { return "\(r.title.name)  ·  S\(s) · E\(e)" }
        return r.title.year.isEmpty ? "Movie" : "Movie  ·  \(r.title.year)"
    }

    private func meta(_ r: PlayRequest?) -> String? {
        if r?.episode != nil, let ep = session.episodeInfo {
            return [airDate(ep.airDate), Labels.runtime(ep.runtime)].compactMap { $0 }.joined(separator: "  ·  ")
        }
        let d = session.details
        return [r?.title.year.nilIfEmpty, Labels.runtime(d?.runtime ?? 0), d?.genres.prefix(2).joined(separator: ", ").nilIfEmpty]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }

    private func airDate(_ ymd: String) -> String? {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC")
        guard let d = f.date(from: ymd) else { return nil }
        let o = DateFormatter(); o.dateFormat = "MMM d, yyyy"; o.locale = Locale(identifier: "en_US"); o.timeZone = TimeZone(identifier: "UTC")
        return o.string(from: d)
    }

    private func serviceName(_ s: String) -> String {
        switch s { case "ad": return "AllDebrid"; case "pm": return "Premiumize"; case "tb": return "TorBox"; case "rd": return "Real-Debrid"; default: return s }
    }

    /// "Dolby Vision  ·  Shown in HDR", "HDR10  ·  Shown in Standard Range" (this screen or the output can't), "Standard Range".
    private var pictureLine: String {
        let b = StreamBadges.make(session.engine.facts(), fileName: session.link?.fileName, sizeBytes: session.link?.sizeBytes ?? 0)
        // Cellular Data Saver chose this copy (Dream Core, by the network at Play): "1080p  ·  Cellular Data Saver".
        if let saver = PictureNote.note(hash: session.link?.hash) {
            return [session.link?.quality.nilIfEmpty, b?.hdrName, saver].compactMap { $0 }.joined(separator: "  ·  ")
        }
        guard let hdr = b?.hdrName else { return "Standard Range" }
        return hdr + "  ·  " + (session.engine.hdrOnScreen ? "Shown in HDR" : "Shown in Standard Range")
    }

    private func fact(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(k).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white.opacity(0.55)).frame(width: 96, alignment: .leading)
            Text(v).font(.system(size: 14, weight: .medium)).foregroundStyle(.white).lineLimit(2)
        }
    }
}

// MARK: Episodes

private struct EpisodesSheet: View {
    @ObservedObject var session: PlayerSession
    let close: () -> Void
    @State private var season = 1
    @State private var episodes: [Episode]?
    @State private var history: HistoryEntry?

    var body: some View {
        let r = session.request
        SheetHeader(kicker: r?.title.name, title: "Episodes", close: close)
        let seasons = Array((session.details?.seasons ?? []).filter { $0.episodeCount > 0 }
            .sorted { ($0.number == 0 ? 9999 : $0.number) < ($1.number == 0 ? 9999 : $1.number) }.prefix(60))
        // The season picker: one clear pill that opens the list of seasons, the episode count beside it.
        HStack(spacing: 14) {
            Menu {
                Picker("Season", selection: Binding(get: { season }, set: { n in
                    guard n != season else { return }
                    haptic(.light); season = n; Task { await load() }
                })) {
                    ForEach(seasons) { s in Text(chipLabel(s)).tag(s.number) }
                }
            } label: {
                HStack(spacing: 10) {
                    Text(seasons.first { $0.number == season }.map(chipLabel) ?? (season == 0 ? "Specials" : "Season \(season)"))
                        .font(.system(size: 16, weight: .semibold))
                    Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 18).frame(minHeight: 40)
                .background(Capsule().fill(.white.opacity(0.10)))
                .overlay(Capsule().strokeBorder(.white.opacity(0.16), lineWidth: 1))
            }
            .disabled(seasons.count < 2)
            if let episodes, !episodes.isEmpty {
                Text(episodes.count == 1 ? "1 Episode" : "\(episodes.count) Episodes")
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.55))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 22).padding(.bottom, 14)
        Rectangle().fill(.white.opacity(0.08)).frame(height: 1).padding(.horizontal, 22)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if let episodes {
                        if episodes.isEmpty {
                            Text("No Episodes to Watch Yet").font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.55))
                                .padding(.horizontal, 10).padding(.vertical, 18)
                        }
                        ForEach(episodes.prefix(60)) { ep in episodeRow(ep).id(ep.number) }
                    } else {
                        ForEach(0..<4, id: \.self) { _ in
                            HStack(spacing: 16) {
                                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.white.opacity(0.06)).frame(width: 136, height: 76)
                                VStack(alignment: .leading, spacing: 8) {
                                    Capsule().fill(.white.opacity(0.07)).frame(width: 170, height: 12)
                                    Capsule().fill(.white.opacity(0.05)).frame(height: 10)
                                    Capsule().fill(.white.opacity(0.05)).frame(width: 90, height: 10)
                                }
                            }
                            .padding(10)
                        }
                    }
                }
                .padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 24)
            }
            .onChange(of: episodes?.count) { _, _ in
                if season == r?.season, let e = r?.episode { proxy.scrollTo(e, anchor: .center) }
            }
        }
        .task {
            season = r?.season ?? 1
            if let key = r?.title.key { history = await Services.shared.history.entry(for: key) }
            await load()
        }
    }

    private func chipLabel(_ s: SeasonSummary) -> String {
        if !s.name.isEmpty, !s.name.hasPrefix("Season"), s.name.count <= 18 { return s.name }
        return s.number == 0 ? "Specials" : "Season \(s.number)"
    }

    private func load() async {
        guard let id = session.request?.title.id else { return }
        episodes = nil
        let s = season
        let today = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withFullDate])
        let all = (try? await Services.shared.catalog.episodes(showId: id, season: s)) ?? []
        // Another season picked meanwhile: its own load fills the list (a slow answer showed the wrong season's).
        guard s == season else { return }
        episodes = all.filter { $0.outEarly || (!$0.airDate.isEmpty && $0.airDate <= today) } // up early too: it plays
    }

    private func progress(_ ep: Episode) -> Double? {
        let r = session.request
        if r?.season == ep.season && r?.episode == ep.number {
            let d = session.state.duration
            return d > 0 ? session.state.position / d : nil
        }
        guard let h = history, let hs = h.season, let he = h.episode else { return nil }
        if hs == ep.season && he == ep.number && !h.upNext { return h.progress }
        if ep.season < hs || (ep.season == hs && ep.number < he) { return 1 }
        return nil
    }

    /// One tidy row: the still on the left, "E3 · Title", a two-line synopsis and the runtime on the right.
    /// The episode that is playing carries the rainbow edge.
    private func episodeRow(_ ep: Episode) -> some View {
        let r = session.request
        let playing = r?.season == ep.season && r?.episode == ep.number
        let p = progress(ep)
        let watched = (p ?? 0) >= 0.9 && !playing
        return Button {
            haptic(.light)
            if playing { close(); return }
            var resume: Double = 0
            if let h = history, h.season == ep.season, h.episode == ep.number, !h.upNext, h.progress > 0.02, h.progress < 0.9 {
                resume = Double(h.positionMs) / 1000
            }
            close()
            guard let r else { return }
            var next = r
            next.season = ep.season; next.episode = ep.number; next.episodeName = ep.name; next.fromStart = resume == 0
            session.playNext(next)
        } label: {
            HStack(alignment: .top, spacing: 16) {
                ZStack(alignment: .bottomLeading) {
                    AsyncImage(url: ep.still("w300")) { ph in
                        if let img = ph.image { img.resizable().scaledToFill() }
                        else {
                            ZStack {
                                Color.white.opacity(0.06)
                                Text("E\(ep.number)").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white.opacity(0.5))
                            }
                        }
                    }
                    .frame(width: 136, height: 76).clipped()
                    if let p, p > 0 {
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Rectangle().fill(.black.opacity(0.45))
                                Rectangle().fill(p >= 0.9 ? Color.white : Rainbow.fadeColor()).frame(width: g.size.width * max(0.06, min(1, p)))
                            }
                        }
                        .frame(height: 3)
                    }
                }
                .frame(width: 136, height: 76)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 6) {
                    Text("E\(ep.number)  ·  " + (ep.name.isEmpty ? "Episode \(ep.number)" : ep.name))
                        .font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    if !ep.overview.isEmpty {
                        Text(ep.overview).font(.system(size: 13)).opacity(0.70).lineLimit(2).lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    let meta = [playing ? "Now Playing" : nil, Labels.runtime(ep.runtime), watched ? "Watched" : nil]
                        .compactMap { $0 }.joined(separator: "  ·  ")
                    if !meta.isEmpty { Text(meta).font(.system(size: 12, weight: .medium)).monospacedDigit().opacity(0.58) }
                }
                .padding(.top, 2)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(EpisodeRowStyle(playing: playing))
    }

    private func shortDate(_ ymd: String) -> String? {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC")
        guard let d = f.date(from: ymd) else { return nil }
        let o = DateFormatter(); o.dateFormat = "MMM d, yyyy"; o.locale = Locale(identifier: "en_US"); o.timeZone = TimeZone(identifier: "UTC")
        return o.string(from: d)
    }
}

/// An episode row: a soft box, pearl with ink when pressed; the playing episode keeps a rainbow edge.
struct EpisodeRowStyle: ButtonStyle {
    var playing: Bool
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : .white)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(p ? Palette.pearl : .white.opacity(playing ? 0.07 : 0.025)))
            .rainbowBorder(p || playing, radius: 14, width: 2)
            .animation(.easeOut(duration: 0.15), value: p)
    }
}

// MARK: Change Source

private struct SourceSheet: View {
    @ObservedObject var session: PlayerSession
    let close: () -> Void
    @State private var loose = false
    @State private var items: [StreamCandidate]?
    @State private var showAll = false
    @State private var busy: String?
    @State private var failed: String?

    var body: some View {
        let r = session.request
        SheetHeader(kicker: loose ? "All Sources" : "Change Source", title: title(r), close: close)
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if let items {
                    let shown = items.filter { showAll || !otherEpisode($0) }
                    if items.isEmpty {
                        note("No other sources are available right now.")
                    } else if shown.isEmpty {
                        note("Every source found is for a different title or episode.")
                    }
                    ForEach(shown) { c in row(c) }
                    if !showAll, shown.count < items.count {
                        SheetRow(title: "Show \(items.count - shown.count) More", subtitle: "For a Different Title or Episode") { showAll = true }
                    }
                    if !loose {
                        SheetRow(title: "Search All Sources", subtitle: "Includes Ones Your Source Settings Usually Hide") {
                            loose = true
                            Task { await load() }
                        }
                    }
                    SheetRow(title: "Find Another Source", subtitle: "Dream OS Picks the Best One, From Where You Are") {
                        close(); session.tryAgain()
                    }
                } else {
                    HStack(spacing: 12) {
                        CurtainRing().frame(width: 20, height: 20)
                        Text("Looking for Sources").font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.7))
                    }
                    .padding(14)
                }
            }
            .padding(.horizontal, 12).padding(.bottom, 22)
        }
        .task { await load() }
    }

    private func title(_ r: PlayRequest?) -> String {
        guard let r else { return "" }
        if let s = r.season, let e = r.episode { return "\(r.title.name)  ·  S\(s) E\(e)" }
        return r.title.name
    }

    private func note(_ s: String) -> some View {
        Text(s).font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.6)).padding(12)
    }

    private func load() async {
        guard let r = session.request else { return }
        items = nil
        let pb = Services.shared.playback
        let list: [StreamCandidate]
        if let x = pb as? PlaybackExtras { list = (try? await x.candidates(r, loose: loose)) ?? [] }
        else { list = (try? await pb.candidates(r)) ?? [] }
        // Same torrent or the same name twice: keep the better copy where the first one was.
        var seen: [String: Int] = [:]
        var out: [StreamCandidate] = []
        for c in list {
            let norm = (c.fileName ?? c.torrentName).lowercased().filter { $0.isLetter || $0.isNumber }
            let keys = [c.hash.lowercased()] + (norm.count >= 8 ? [norm] : [])
            if let i = keys.compactMap({ seen[$0] }).first {
                if rank(c) > rank(out[i]) { out[i] = c }
                continue
            }
            for k in keys { seen[k] = out.count }
            out.append(c)
        }
        items = out
    }

    private func rank(_ c: StreamCandidate) -> (Int, Int64) {
        let q = c.quality.uppercased()
        let r = q.contains("4K") || q.contains("2160") ? 4 : q.contains("1080") ? 3 : q.contains("720") ? 2 : q == "SD" ? 1 : 0
        return (r, c.sizeBytes)
    }

    private func otherEpisode(_ c: StreamCandidate) -> Bool {
        guard let s = session.request?.season, let e = session.request?.episode else { return false }
        let n = (c.fileName ?? c.torrentName).uppercased()
        if let m = n.range(of: #"S(\d{1,2})E(\d{1,3})"#, options: .regularExpression) {
            let t = n[m]
            let parts = t.dropFirst().split(separator: "E")
            if parts.count == 2, let ss = Int(parts[0]), let ee = Int(parts[1]) { return ss != s || ee != e }
        }
        return false
    }

    private func playing(_ c: StreamCandidate) -> Bool { session.link?.hash.lowercased() == c.hash.lowercased() && !c.hash.isEmpty }

    private func row(_ c: StreamCandidate) -> some View {
        let q = c.quality.uppercased()
        let quality = q.contains("4K") || q.contains("2160") ? "4K" : q.contains("1080") ? "1080p" : q.contains("720") ? "720p" : q
        let line1 = [quality, Labels.size(c.sizeBytes)].compactMap { $0 }.joined(separator: " · ")
        let name = (c.fileName ?? c.torrentName)
        let up = " " + name.uppercased().replacingOccurrences(of: ".", with: " ") + " "
        var tags: [String] = []
        if up.contains(" DV ") || up.contains("DOVI") || up.contains("DOLBY VISION") { tags.append("Dolby Vision") }
        else if up.contains("HDR10+") || up.contains("HDR10PLUS") { tags.append("HDR10+") }
        else if up.contains("HDR") { tags.append("HDR") }
        if up.contains("HEVC") || up.contains("X265") || up.contains("H265") { tags.append("HEVC") } else if up.contains(" AV1 ") { tags.append("AV1") }
        if up.contains("REMUX") { tags.append("Remux") }
        if up.contains("ATMOS") { tags.append("Atmos") } else if up.contains("7 1") { tags.append("7.1") } else if up.contains("5 1") { tags.append("5.1") }
        if c.seeders > 0 { tags.append(c.seeders == 1 ? "1 Seeder" : "\(c.seeders) Seeders") }
        let line2 = busy == c.id ? "Getting Ready" : failed == c.id ? "Not Available Right Now" : tags.joined(separator: "  ·  ")
        return Button { pick(c) } label: {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(line1).font(.system(size: 16, weight: .semibold))
                    if !line2.isEmpty { Text(line2).font(.system(size: 13, weight: .medium)).opacity(0.7) }
                    Text(pretty(name)).font(.system(size: 11, weight: .medium)).opacity(0.5).lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 4) {
                    if playing(c) { pill("Playing", Palette.green) }
                    else if otherEpisode(c) { pill("Other Episode", Palette.amber) }
                    if c.cached == true && !playing(c) { pill("Ready", Palette.green) }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(RowStyle(chosen: playing(c)))
    }

    private func pill(_ t: String, _ c: Color) -> some View {
        Text(t).font(.system(size: 11, weight: .bold)).foregroundStyle(c)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(c.opacity(0.14)))
            .overlay(Capsule().strokeBorder(c.opacity(0.5), lineWidth: 1))
    }

    private func pretty(_ n: String) -> String {
        var s = n
        for ext in [".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts"] where s.lowercased().hasSuffix(ext) { s.removeLast(ext.count) }
        var out = ""
        let chars = Array(s)
        for (i, ch) in chars.enumerated() {
            if (ch == "." || ch == "_"), !(i > 0 && i < chars.count - 1 && chars[i - 1].isNumber && chars[i + 1].isNumber) { out.append(" ") }
            else { out.append(ch) }
        }
        return out
    }

    private func pick(_ c: StreamCandidate) {
        guard busy == nil, let r = session.request else { return }
        haptic(.light)
        if playing(c) { close(); return }
        busy = c.id
        Task {
            do {
                let link = try await Services.shared.playback.link(for: c, request: r)
                busy = nil
                let at = session.resumeSpot()
                close()
                session.play(link: link, for: r, at: at)
            } catch {
                busy = nil
                failed = c.id
                try? await Task.sleep(for: .seconds(2.6))
                if failed == c.id { failed = nil }
            }
        }
    }
}
