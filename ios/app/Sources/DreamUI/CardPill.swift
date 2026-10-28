import SwiftUI
import Observation
import DreamCore

// A card's availability pill, kept fresh (Availability.attachTitle on the TV): it shows what the row already knew
// (an unreleased title's date, or the cache) on the first frame, then asks `catalog.availability(for:)` once the card
// is on screen. The catalog decides which titles get a pill at all (STANDARDS §5: new movies and brand-new shows
// only) and answers old titles with nil at once, so every card can ask. Answers are remembered here for the session,
// so a card that scrolls back in shows its pill straight away.
//
// A title the viewer has watched (WatchedLedger: a finished movie, a show caught up with nothing new) shows the gold
// Watched pill instead, on the first frame, from the local ledger; no availability lookup is made for it. Personal
// rows (`live: false`: Continue Watching, New Episodes for You) never show it, nor does a card badged NEW EPISODE.

@MainActor
final class CardPillMemory {
    static let shared = CardPillMemory()
    /// title key -> the last answer (nil inside: asked, no pill).
    var answers: [String: Availability?] = [:]
}

/// The Watched state for views, instant (the local ledger, no network). Reading it in a body redoes that body when
/// the ledger, the watch history or the profile changes.
@MainActor
@Observable
public final class WatchedState {
    public static let shared = WatchedState()
    public private(set) var version = 0

    private init() {
        for n in [Notification.Name.dreamWatchedChanged, .dreamHistoryChanged, .dreamProfileChanged] {
            NotificationCenter.default.addObserver(forName: n, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.version += 1 }
            }
        }
    }

    /// A finished movie, or a show caught up with nothing new.
    public func title(_ t: Title) -> Bool {
        _ = version
        return WatchedLedger.shared.isWatched(t)
    }

    /// An episode the viewer actually finished.
    public func episode(show: Int, season: Int, episode: Int) -> Bool {
        _ = version
        return WatchedLedger.shared.episodeWatched(show: show, season: season, episode: episode)
    }
}

public struct CardPill: View {
    let title: Title
    let known: Availability?
    let live: Bool
    let maxWidth: CGFloat?
    let newEpisode: Bool
    @State private var answer: Availability??

    /// `known`: what the row item carries. `live: false` shows only that (personal rows that point at an episode).
    /// `newEpisode`: the card carries the NEW EPISODE badge (the show is not caught up, whatever the ledger says).
    public init(_ title: Title, known: Availability?, live: Bool = true, maxWidth: CGFloat? = nil, newEpisode: Bool = false) {
        self.title = title; self.known = known; self.live = live; self.maxWidth = maxWidth; self.newEpisode = newEpisode
    }

    private var shown: Availability? {
        guard live else { return known }
        if let answer { return answer }
        if let remembered = CardPillMemory.shared.answers[title.key] { return remembered }
        if let coming = Self.comingFromDate(title) { return coming }
        return known
    }

    /// An unreleased title's pill straight from its date, on the first frame (no check needed: AvailabilityChecker's
    /// localEntry gives the same answer). These never sat in the stored answers, so they used to pop in.
    static func comingFromDate(_ t: Title) -> Availability? {
        guard t.date.count == 10, t.date > DreamTime.ymdPlus(0) else { return nil }
        return .coming(t.date)
    }

    private var watched: Bool { live && !newEpisode && WatchedState.shared.title(title) }

    public var body: some View {
        // A zero-size anchor, so the check runs even while there is no pill to show.
        ZStack(alignment: .bottomLeading) {
            Color.clear.frame(width: 0, height: 0)
            if watched {
                WatchedPill().frame(maxWidth: maxWidth, alignment: .leading).transition(.opacity)
            } else if let s = shown {
                AvailabilityPill(s).frame(maxWidth: maxWidth, alignment: .leading).transition(.opacity)
            }
        }
        .task(id: title.key) {
            guard live else { return }
            let c = Services.shared.catalog
            // A watched show: TMDB's air state again when it's due (a new episode takes Watched away).
            if title.kind == .tv, WatchedLedger.shared.needsAirCheck(title.id), let s = await c.showAirState(showId: title.id) {
                WatchedLedger.shared.noteAir(show: title.id, s)
            }
            guard !Task.isCancelled, newEpisode || !WatchedLedger.shared.isWatched(title) else { return }
            let a = await c.availability(for: title)
            guard !Task.isCancelled else { return }
            CardPillMemory.shared.answers[title.key] = .some(a)
            if answer != .some(a) { withAnimation(.easeOut(duration: 0.2)) { answer = .some(a) } }
        }
    }
}
