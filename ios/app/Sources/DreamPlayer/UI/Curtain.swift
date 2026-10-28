import SwiftUI
import DreamCore
import DreamUI

/// The "Now Playing" moment (fire-home PlayFlow.LoadingCurtain): the backdrop, the green NOW PLAYING with a
/// small rainbow ring, the title's logo or name and one quiet line. No status text; it lifts on the first
/// frame. Failures show a title, one plain line and Try Again / Search All Sources / Change Source / Back.
struct Curtain: View {
    @ObservedObject var session: PlayerSession
    let openSources: () -> Void
    @State private var backdropShown = false

    var body: some View {
        // The pictures fill the whole screen; the words and buttons stay inside the safe area. (They used to sit
        // under the Dynamic Island in landscape: the island's black pill covered part of NOW PLAYING.)
        ZStack(alignment: .bottomLeading) {
            backdrop.ignoresSafeArea()
            column.padding(.leading, 18).padding(.bottom, 24).padding(.trailing, 28)
            if case .loading = session.phase {
                Button { session.close() } label: { Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)) }
                    .buttonStyle(RoundGlassStyle(size: 44)).accessibilityLabel("Back")
                    .padding(.top, 12).padding(.trailing, 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
    }

    private var backdrop: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottomLeading) {
                LinearGradient(colors: [Color(hex: 0x000000), Color(hex: 0x040405)], startPoint: .top, endPoint: .bottom)
                if let url = session.request?.title.backdrop("w1280") {
                    AsyncImage(url: url) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill().frame(width: geo.size.width, height: geo.size.height).clipped()
                                .opacity(backdropShown ? 1 : 0)
                                .onAppear { withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.5)) { backdropShown = true } }
                        }
                    }
                }
                LinearGradient(stops: [.init(color: .black.opacity(0.80), location: 0), .init(color: .black.opacity(0.43), location: 0.5),
                                       .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing)
                    .frame(width: geo.size.width * 0.62)
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                    .frame(height: geo.size.height * 0.42).frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
    }

    private var column: some View {
        VStack(alignment: .leading, spacing: 0) {
            if case .loading = session.phase {
                // NOW PLAYING with its ring on the right, a little larger, nearer the edge and higher (owner, 2026-10-27).
                HStack(spacing: 14) {
                    NowPlayingLabel(size: 16)
                    CurtainRing().frame(width: 24, height: 24)
                }
                .fixedSize()
                .padding(.bottom, 26)
            }
            TitleBlock(session: session, logoHeight: 96, titleSize: 34, lines: 2).frame(maxWidth: 520, alignment: .leading)
            if !line.isEmpty {
                Text(line).font(.system(size: 17, weight: .medium)).foregroundStyle(.white.opacity(0.86)).lineLimit(1).padding(.top, 12)
            }
            if let note = session.note, case .loading = session.phase {
                Text(note).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.amberText).padding(.top, 12)
                    .transition(.opacity)
            }
            if case .failed(let title, let message) = session.phase {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                    Text(message).font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.70)).lineLimit(2)
                        .frame(maxWidth: 560, alignment: .leading)
                }
                .padding(.top, 28)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        Button("Try Again") { haptic(.light); session.tryAgain() }.buttonStyle(PillStyle(primary: true))
                        if !session.isTrailer {
                            Button("Search All Sources") { haptic(.light); session.searchAllSources() }.buttonStyle(PillStyle())
                            if (session.request?.title.id ?? 0) > 0 {
                                Button("Change Source") { haptic(.light); openSources() }.buttonStyle(PillStyle())
                            }
                        }
                        Button("Back") { session.close() }.buttonStyle(PillStyle())
                    }
                    .padding(.vertical, 6)
                }
                .padding(.top, 18)
                .transition(.opacity.combined(with: .offset(y: 12)))
            }
        }
        .animation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.3), value: session.phase)
    }

    private var line: String {
        guard let r = session.request else { return "" }
        if session.isTrailer { return "Trailer" }
        if let s = r.season, let e = r.episode {
            let name = r.episodeName ?? session.episodeInfo?.name
            return "S\(s) · E\(e)" + (name.map { "    " + $0 } ?? "")
        }
        return r.title.year
    }
}

/// The curtain's small spinner: a faint track and a rainbow arc turning once every 0.9 s.
struct CurtainRing: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let a = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9 * 360
            ZStack {
                Circle().stroke(.white.opacity(46 / 255), lineWidth: 2.5)
                Circle().trim(from: 0, to: 300 / 360)
                    .stroke(AngularGradient(colors: [.clear] + Rainbow.colors, center: .center), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(a))
            }
        }
    }
}
