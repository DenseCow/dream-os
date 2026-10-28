import SwiftUI
import DreamCore
import DreamUI

/// Up Next, More Like This and "Are You Still Watching?" (fire-home PostPlay.kt).
struct PostPlayLayer: View {
    @ObservedObject var session: PlayerSession

    var body: some View {
        switch session.postPlay {
        case .none: EmptyView()
        case .upNext(let countdown): UpNextCard(session: session, countdown: countdown)
        case .moreLikeThis: MoreLikeThis(session: session)
        case .stillWatching: StillWatching(session: session)
        }
    }
}

private struct UpNextCard: View {
    @ObservedObject var session: PlayerSession
    let countdown: Bool
    @State private var progress: CGFloat = 0
    @State private var shown = false

    var body: some View {
        let n = session.next
        let ep = session.nextEpisode
        HStack(alignment: .center, spacing: 14) {
            ZStack(alignment: .bottom) {
                AsyncImage(url: ep?.still("w500") ?? session.request?.title.backdrop("w780")) { p in
                    if let img = p.image { img.resizable().scaledToFill() } else { Color.white.opacity(0.06) }
                }
                .frame(width: 124, height: 70).clipped()
                if countdown {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(.white.opacity(0.27))
                            Rectangle().fill(Rainbow.fadeColor()).frame(width: g.size.width * progress)
                        }
                    }
                    .frame(height: 3)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 6) {
                Text("Next Episode").font(.system(size: 12, weight: .bold)).tracking(2).textCase(.uppercase).foregroundStyle(.white.opacity(0.62))
                (Text(line(n, ep)) + Text(ep.flatMap { Labels.runtime($0.runtime) }.map { "  ·  \($0)" } ?? "").foregroundColor(.white.opacity(0.6)))
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(.white).lineLimit(2).frame(maxWidth: 190, alignment: .leading)
                HStack(spacing: 10) {
                    Button { haptic(.medium); session.advance(auto: false) } label: {
                        Label("Play", systemImage: "play.fill").labelStyle(GapLabelStyle())
                    }
                    .buttonStyle(PillStyle(primary: true))
                    Button("Credits") { session.dismissPostPlay() }.buttonStyle(PillStyle())
                }
                .padding(.top, 2)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color(white: 0.05).opacity(0.94)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.10), lineWidth: 1))
        .offset(x: shown ? 0 : 40).opacity(shown ? 1 : 0)
        .padding(.trailing, 28).padding(.bottom, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .onAppear {
            withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.3)) { shown = true }
            if countdown { withAnimation(.linear(duration: 10).delay(0.3)) { progress = 1 } }
        }
    }

    private func line(_ n: PlayRequest?, _ ep: Episode?) -> String {
        guard let n, let s = n.season, let e = n.episode else { return "" }
        let name = n.episodeName ?? ep?.name
        return ["S\(s) E\(e)", name].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")
    }
}

private struct MoreLikeThis: View {
    @ObservedObject var session: PlayerSession
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black.opacity(0.52).ignoresSafeArea()
            LinearGradient(colors: [.clear, .black.opacity(0.62), .black.opacity(0.94)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                Text(session.request?.episode != nil ? "You've reached the end of \(session.request?.title.name ?? "")" : "More Like This")
                    .font(.system(size: 26, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                GeometryReader { g in
                    let items = session.moreLikeThis
                    let w = min(224, (g.size.width - CGFloat(max(0, items.count - 1)) * 16) / CGFloat(max(1, items.count)))
                    HStack(alignment: .top, spacing: 16) {
                        ForEach(items) { t in
                            Button { pick(t) } label: {
                                VStack(alignment: .leading, spacing: 8) {
                                    AsyncImage(url: t.backdrop("w300")) { p in
                                        if let img = p.image { img.resizable().scaledToFill() } else { Color.white.opacity(0.06) }
                                    }
                                    .frame(width: w, height: w * 9 / 16).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    Text(t.name).font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.86)).lineLimit(2)
                                        .frame(width: w, alignment: .leading)
                                }
                            }
                            .buttonStyle(CardPress(radius: 10))
                        }
                    }
                }
                .frame(height: 170)
                HStack(spacing: 12) {
                    Button(session.ended ? "Watch Again" : "Watch Credits") { session.watchCredits() }.buttonStyle(PillStyle())
                    Button("Back to Browse") { session.close() }.buttonStyle(PillStyle())
                }
            }
            .padding(.horizontal, 32).padding(.bottom, 28)
        }
    }

    private func pick(_ t: Title) {
        haptic(.medium)
        if t.kind == .movie { session.playNext(PlayRequest(title: t)) }
        else { DreamPlayerPresenter.shared.open(t) }
    }
}

private struct StillWatching: View {
    @ObservedObject var session: PlayerSession
    var body: some View {
        ZStack(alignment: .leading) {
            Color.black.opacity(0.46).ignoresSafeArea()
            LinearGradient(colors: [.black.opacity(0.86), .black.opacity(0.52), .clear], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 12) {
                Text(session.request?.title.name ?? "").font(.system(size: 13, weight: .bold)).tracking(2.2).textCase(.uppercase)
                    .foregroundStyle(.white.opacity(0.62)).lineLimit(1)
                Text("Are You Still Watching?").font(.system(size: 34, weight: .bold)).foregroundStyle(.white)
                if let r = session.request, let s = r.season, let e = r.episode {
                    Text(["S\(s) E\(e)", r.episodeName ?? session.episodeInfo?.name].compactMap { $0 }.joined(separator: "  ·  "))
                        .font(.system(size: 17, weight: .medium)).foregroundStyle(.white.opacity(0.84))
                }
                HStack(spacing: 12) {
                    Button("Continue Watching") { haptic(.medium); session.continueWatching() }.buttonStyle(PillStyle(primary: true))
                    Button("Back to Browse") { session.close() }.buttonStyle(PillStyle())
                }
                .padding(.top, 10)
            }
            .padding(.leading, 40)
        }
    }
}

/// Cards: the rainbow border only while pressed.
struct CardPress: ButtonStyle {
    var radius: CGFloat
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .rainbowBorder(configuration.isPressed, radius: radius, width: 3)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

/// The sleep timer's expiry card (SleepTimer.kt): 60 s to answer.
struct SleepCard: View {
    let seconds: Int
    @ObservedObject var session: PlayerSession
    var body: some View {
        ZStack {
            Color.black.opacity(150 / 255).ignoresSafeArea()
            VStack(spacing: 14) {
                Text("Still Watching?").font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                // The iPhone pauses (and closes the player, spot saved) instead of turning the TV off.
                Text("Pausing in \(seconds) s").font(.system(size: 16, weight: .medium)).monospacedDigit().foregroundStyle(.white.opacity(0.74))
                HStack(spacing: 12) {
                    Button("Keep Watching") { session.keepWatching() }.buttonStyle(PillStyle(primary: true))
                    Button("Stop Now") { session.sleepNow() }.buttonStyle(PillStyle())
                }
                .padding(.top, 6)
            }
            .padding(32)
            .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Color(white: 0.05).opacity(0.96)))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(.white.opacity(0.10), lineWidth: 1))
        }
    }
}
