import Foundation
import DreamCore

/// Play decisions (PlayFlow.kt minus its screens, which are the player's): where playback starts, what comes next,
/// and the plain words for why nothing plays.
public enum PlayFlow {
    /// The loading screen's words when the debrid account is at its magnet limit.
    public static let limitText = DreamError(title: "Can't Play Right Now", "Your AllDebrid account has reached its magnet limit. It frees up automatically; try again later.")
    /// No debrid service linked yet.
    public static let noAccountText = DreamError(title: "Connect a Debrid Service", "Add one in Settings › Accounts & Scrapers, then tap Play again.")
    /// No scraper repository added yet.
    public static let noScrapersText = DreamError(title: "Add a Scraper Repository", "Add one in Settings › Accounts & Scrapers › Scrapers, then tap Play again.")
    /// Nothing playable anywhere (not released, or no copy at all yet).
    public static let notYetText = DreamError(title: "This Title Isn't Available Yet", "There's no version to stream yet. Check back soon.")
    /// Copies exist but none is ready to stream (also the loading screen's own timeout).
    public static let notReadyText = DreamError(title: "Couldn't Find a Ready Stream", "Try again in a moment, or choose another source.")
    /// A source picked by hand that isn't ready.
    public static let sourceNotReadyText = DreamError(title: "This Source Isn't Ready", "Try another source.")
    /// The scrapers gave no answer at all (busy, failed, still starting): unknown, so never "not available yet".
    public static let noAnswerText = DreamError(title: "Couldn't Search for Streams", "Your scrapers didn't answer. Try again in a moment.")
    public static let camNote = "Only a CAM Version Is Available"
    public static let bestNote = "Playing the Best Available Version"
    /// The loading screen gives a resolve at most this long before it says so plainly (never a spinner forever).
    public static let resolveTimeoutMs: Int64 = 45_000

    /// The episode after [r] from TMDB season data (rolls over to the next season's first episode), or nil at the end
    /// of the series / when the next one hasn't aired.
    public static func nextEpisode(_ r: PlayRequest) async -> NextUp? {
        guard r.title.kind == .tv, let season = r.season, let episode = r.episode else { return nil }
        let today = DreamTime.todayYmd()
        let catalog = Services.shared.catalog
        var s = season
        var next = (try? await catalog.episodes(showId: r.title.id, season: season))?.first { $0.number == episode + 1 }
        if next == nil {
            // The next season the show lists (specials, season 0, never count).
            guard let d = try? await catalog.details(kind: .tv, id: r.title.id),
                  let ns = d.seasons.map(\.number).filter({ $0 > season }).min() else { return nil }
            s = ns
            next = (try? await catalog.episodes(showId: r.title.id, season: ns))?.min { $0.number < $1.number }
        }
        // Out: its day has come, or it's up before it (the index found a source; `Episode.outEarly`).
        guard let n = next, n.outEarly || (!n.airDate.isEmpty && n.airDate <= today) else { return nil }
        var req = r
        req.season = s; req.episode = n.number; req.episodeName = n.name.isEmpty ? nil : n.name; req.fromStart = false
        return NextUp(request: req, stillPath: n.stillPath, overview: n.overview, runtime: n.runtime)
    }

    /// The error a Play that found nothing ends with: what's missing in setup, the magnet limit, or why nothing plays.
    static func failure(_ req: PlayRequest) async -> DreamError {
        let core = NativeResolver.shared
        if !DebridAccounts.anyLinked { return noAccountText }
        if !(await Services.shared.sources.isActive()) { return noScrapersText }
        if core.limitBlocked() { return limitText }
        switch core.miss(req) {
        case .nothing: return notYetText
        case .noAnswer: return noAnswerText
        default: return notReadyText
        }
    }
}

/// `PlaybackService`: Dream Core for the player and the pages.
struct DreamCorePlayback: PlaybackService {
    private var core: NativeResolver { .shared }

    func isAvailable() async -> Bool { core.available() }

    func resolve(_ request: PlayRequest) async throws -> StreamLink {
        let t0 = Mono.now()
        CellularSaver.logAtPlay(core.label(request))
        guard var found = await core.find(request, budgetMs: 8_000, purpose: DebridPurpose.play) else {
            let e = await PlayFlow.failure(request)
            plog.warning("Play flow: \(e.title ?? e.message, privacy: .public) (\(NativeResolver.secs(Mono.now() - t0), privacy: .public))")
            throw e
        }
        // Nothing passed the viewer's filters (only a CAM copy, say): played anyway, with a short note.
        if let rx = core.relaxed(request, found) { found.note = rx.cam ? PlayFlow.camNote : PlayFlow.bestNote }
        plog.info("Play flow: Dream Core stream (\(found.service.uppercased(), privacy: .public) \(found.quality, privacy: .public)) in \(NativeResolver.secs(Mono.now() - t0), privacy: .public)")
        CellularSaver.mark(found) // Info's quiet "Cellular Data Saver" note, by the network at this Play
        core.handing(found) // the player loads it now: its first frame is timed from here
        return found
    }

    func resolveAll(_ request: PlayRequest) async throws -> StreamLink {
        guard var found = await core.findLoose(request) else { throw await PlayFlow.failure(request) }
        if let rx = core.relaxed(request, found), rx.cam { found.note = PlayFlow.camNote }
        PictureNote.set(nil, hash: found.hash) // Search All Sources: every limit off, Cellular Data Saver too
        core.handing(found)
        return found
    }

    func candidates(_ request: PlayRequest) async throws -> [StreamCandidate] {
        var list = await core.sources(request, loose: false)
        if let ready = await core.readyHashes(list, request: request) {
            for i in list.indices.prefix(DebridPurpose.list.maxUploads) { list[i].cached = ready.contains(list[i].hash) }
        }
        return list
    }

    func sources(_ request: PlayRequest, loose: Bool) async -> [StreamCandidate] { await core.sources(request, loose: loose) }
    func readyHashes(_ candidates: [StreamCandidate]) async -> Set<String>? { await core.readyHashes(candidates) }

    func link(for candidate: StreamCandidate, request: PlayRequest) async throws -> StreamLink {
        guard let f = await core.resolveCandidate(request, candidate) else { throw PlayFlow.sourceNotReadyText }
        PictureNote.set(nil, hash: f.hash) // picked by hand: no Cellular Data Saver note
        return f
    }

    func nextEpisode(after request: PlayRequest) async -> PlayRequest? { await PlayFlow.nextEpisode(request)?.request }

    func postPlay(after request: PlayRequest) async -> PostPlayPlan {
        if let n = await PlayFlow.nextEpisode(request) {
            core.ahead(n.request) // its link is ready well before the countdown ends
            return PostPlayPlan(next: n)
        }
        let more = (try? await Services.shared.catalog.details(kind: request.title.kind, id: request.title.id))?
            .recommendations.filter { $0.backdropPath != nil }.prefix(4) ?? []
        return PostPlayPlan(next: nil, more: Array(more))
    }

    func forget(_ request: PlayRequest) async { core.forget(request) }
    func handing(_ link: StreamLink) async { core.handing(link) }
    func firstFrame(_ request: PlayRequest, link: StreamLink) async { core.firstFrame(request, link) }

    func skipMarkers(for request: PlayRequest) async -> [SkipMarker] {
        await SkipMarkers.shared.info(request, durationMs: 0, chapters: []).markers
    }
    func skipInfo(for request: PlayRequest, durationMs: Int64, chapters: [SkipChapter]) async -> SkipInfo {
        await SkipMarkers.shared.info(request, durationMs: durationMs, chapters: chapters)
    }
    func learnSkip(_ request: PlayRequest, fromMs: Int64, toMs: Int64) async { SkipMarkers.shared.learn(request, fromMs: fromMs, toMs: toMs) }
    func confirmLearnedSkip(_ request: PlayRequest) async { SkipMarkers.shared.confirmLearned(request) }

    func survey(_ request: PlayRequest) async -> SourceSurvey? { await core.survey(request) }
}

/// Dream Player's extras (PlayerContract.swift): until 2026-10-28 nothing adopted them, so the player never forgot a
/// failed link (Try Again replayed the same one), Search All Sources was a plain Play, the CAM / best-available note
/// never showed and no first-frame time was measured.
extension DreamCorePlayback: PlaybackExtras {
    func resolve(_ request: PlayRequest, loose: Bool) async throws -> StreamLink {
        loose ? try await resolveAll(request) : try await resolve(request)
    }
    func candidates(_ request: PlayRequest, loose: Bool) async throws -> [StreamCandidate] {
        loose ? await core.sources(request, loose: true) : try await candidates(request)
    }
    func forget(_ request: PlayRequest) { core.forget(request) }
    func note(for link: StreamLink) -> String? { link.note }
    func started(_ request: PlayRequest, link: StreamLink, seconds: Double) { core.firstFrame(request, link) }
    /// Instant Play's race: the next link unlocked alongside the failed one (`forget` it first); the player switches
    /// to it behind the curtain. Timed from here like a fresh hand-over.
    func nextLink(for request: PlayRequest) -> StreamLink? {
        guard let f = core.nextLink(request) else { return nil }
        CellularSaver.mark(f)
        core.handing(f)
        return f
    }
    func hasNextLink(for request: PlayRequest) -> Bool { core.hasAlternate(request) }
    func fresh(_ request: PlayRequest) { core.fresh(request) }
    func waiting(for request: PlayRequest) -> String? { core.waiting(request) }
}
