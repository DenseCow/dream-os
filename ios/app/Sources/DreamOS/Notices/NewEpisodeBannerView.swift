import SwiftUI
import DreamCore
import DreamUI

/// Home's New Episode banner (NewEpisodeBanner.kt, Dream Cinema for the phone), right under the hero: one followed
/// show's new episode on a dark panel, its still, "NEW EPISODE" in a still rainbow, the show, "S3 · E7  Episode Name",
/// whether it's out, and Play (the rainbow), Go to Show and Dismiss. Gone while there is nothing to offer.
struct NewEpisodeBannerView: View {
    /// The panel's surface (the kicker's fading words are read against it).
    static let panelHex: UInt32 = 0x0F0F10
    @Environment(ShellModel.self) private var shell
    private var spot: NewEpisodeSpot { NewEpisodeSpot.shared }

    var body: some View {
        Group {
            if let item = spot.current {
                panel(item)
                    .id(item.key)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
        }
        .animation(.easeOut(duration: 0.3), value: spot.current?.key)
        // Loads come from Home (HomeView.refreshPersonal: appear, pull to refresh, history changed) and the spot's own
        // half-hour check; an empty banner holds no task.
    }

    private func panel(_ item: NewEpisodeInfo) -> some View {
        let batch = item.lastNumber > item.number
        let word = batch ? "NEW EPISODES" : "NEW EPISODE"
        let epName = batch ? "\(item.lastNumber - item.number + 1) Episodes" : (item.name.isEmpty ? "Episode \(item.number)" : item.name)
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 14) {
                RemoteImage([item.episode.still("w500"), item.show.backdrop("w780")]) {
                    Rectangle().fill(Palette.tile)
                }
                .frame(width: 132, height: 74)
                .clipShape(RoundedRectangle(cornerRadius: Cinema.rCard, style: .continuous))
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 10) {
                        // The fading rainbow, lifted only as far as 4.5:1 on the panel needs.
                        RainbowFade(interval: 1 / 15) { h in
                            Text(word)
                                .font(.system(size: 11.5, weight: .semibold))
                                .tracking(1.5)
                                .foregroundStyle(Color(hex: Rainbow.textOn(h, Self.panelHex)))
                                .lineLimit(1)
                                .fixedSize()
                        }
                        if let a = spot.availability {
                            AvailabilityPill(a)
                        } else if let s = spot.statusWords(item) {
                            Text(s).font(.system(size: 12.5, weight: .medium)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                        }
                    }
                    Text(item.show.name)
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(Palette.text)
                        .lineLimit(1)
                    HStack(spacing: 10) {
                        Text(NewEpisodeSpot.code(item)).font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.text.opacity(0.92))
                            .fixedSize()
                        Text(epName).font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.text.opacity(0.72))
                    }
                    .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                Button { play(item) } label: { DreamLabel("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(PlayButtonStyle(fullWidth: true))
                Button { shell.open(item.show) } label: { DreamLabel("Go to Show", systemImage: "info.circle") }
                    .buttonStyle(PearlButtonStyle())
                Button { dismiss(item) } label: { Image(systemName: "xmark").font(.system(size: 14, weight: .bold)) }
                    .buttonStyle(RoundButtonStyle(size: 46))
                    .accessibilityLabel("Dismiss")
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(hex: Self.panelHex)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
        .padding(.horizontal, Cinema.gutter)
    }

    private func play(_ item: NewEpisodeInfo) {
        Log.app.notice("NewEp: Play \(item.show.name, privacy: .public) S\(item.season)E\(item.number)")
        shell.play(CatalogItem(title: item.show, episode: item.episode))
        // Once the player covers Home, the banner leaves (Continue Watching has the episode now).
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { spot.done(item, why: "played") }
    }

    private func dismiss(_ item: NewEpisodeInfo) {
        withAnimation(.easeOut(duration: 0.2)) { spot.done(item, why: "dismissed") }
    }
}

/// The bell beside the profile face on Home: opens Notifications; the unread count in a small rainbow badge.
struct NoticeBell: View {
    private var board: NoticeBoard { NoticeBoard.shared }

    var body: some View {
        Button { board.route = .list } label: {
            NoticeGlyph(kind: .bell, color: Palette.text, weight: 8)
                .frame(width: 24, height: 24)
                .frame(width: 44, height: 44)
                .overlay(alignment: .topTrailing) {
                    if board.unread > 0 {
                        // The fading rainbow, the count in the readable ink for each hue.
                        RainbowFade(interval: 1 / 15) { h in
                            Text(board.unread > 9 ? "9+" : "\(board.unread)")
                                .font(.system(size: 10.5, weight: .bold))
                                .foregroundStyle(Rainbow.inkOn(h))
                                .padding(.horizontal, 4)
                                .frame(minWidth: 17, minHeight: 17)
                                .background(Capsule().fill(Color(hex: h)))
                                .overlay(Capsule().strokeBorder(Color(hex: 0x0C0C0E), lineWidth: 1.5))
                        }
                        .offset(x: -3, y: 5)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(NoticeBellStyle())
        .accessibilityLabel(board.unread > 0 ? "Notifications, \(board.unread) unread" : "Notifications")
    }
}

private struct NoticeBellStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .opacity(configuration.isPressed ? 0.75 : 1)
            .animation(Cinema.pressAnimation, value: configuration.isPressed)
            .pressHaptic(configuration.isPressed)
    }
}
