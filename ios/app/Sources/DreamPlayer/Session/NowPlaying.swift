import Foundation
import MediaPlayer
import AVFoundation
import DreamCore

/// Lock screen / Control Center / AirPods controls (DreamPlayerActivity's MediaSession): play, pause, seek,
/// and AirPods' double / triple press as ±10 s, like the on-screen buttons.
@MainActor
enum NowPlaying {
    private static weak var current: PlayerSession?
    private static var wired = false
    /// The session the lock screen / Control Center talk to.
    static var session: PlayerSession? { current }

    static func update(_ s: PlayerSession) {
        current = s
        if !wired {
            wire()
            // Sound with the ring / silent switch on, like a video app.
            BackgroundAudio.activate()
        }
        let r = s.request
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = r?.title.name ?? "Dream Player"
        if s.isTrailer { info[MPMediaItemPropertyArtist] = "Trailer" }
        else if let r, let se = r.season, let e = r.episode {
            info[MPMediaItemPropertyArtist] = "S\(se) E\(e)" + ((r.episodeName ?? s.episodeInfo?.name).map { " · \($0)" } ?? "")
        } else if let y = r?.title.year, !y.isEmpty { info[MPMediaItemPropertyArtist] = y }
        info[MPMediaItemPropertyPlaybackDuration] = s.state.duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = s.state.position
        info[MPNowPlayingInfoPropertyPlaybackRate] = s.state.isPlaying ? s.state.speed : 0
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.video.rawValue
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    static func clear() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        current = nil
    }

    private static func wire() {
        wired = true
        let c = MPRemoteCommandCenter.shared()
        func on(_ cmd: MPRemoteCommand, _ f: @escaping @MainActor (PlayerSession) -> Void) {
            cmd.isEnabled = true
            cmd.addTarget { _ in
                MainActor.assumeIsolated {
                    guard let s = current else { return .noActionableNowPlayingItem }
                    f(s)
                    return .success
                }
            }
        }
        on(c.playCommand) { $0.engine.play() }
        on(c.pauseCommand) { $0.engine.pause() }
        on(c.togglePlayPauseCommand) { $0.togglePlay() }
        on(c.nextTrackCommand) { $0.seek(to: $0.state.position + 10) }
        on(c.previousTrackCommand) { $0.seek(to: $0.state.position - 10) }
        c.skipForwardCommand.preferredIntervals = [10]
        c.skipBackwardCommand.preferredIntervals = [10]
        on(c.skipForwardCommand) { $0.seek(to: $0.state.position + 10) }
        on(c.skipBackwardCommand) { $0.seek(to: $0.state.position - 10) }
        c.changePlaybackPositionCommand.isEnabled = true
        c.changePlaybackPositionCommand.addTarget { e in
            let t = (e as? MPChangePlaybackPositionCommandEvent)?.positionTime ?? 0
            return MainActor.assumeIsolated {
                guard let s = current else { return .noActionableNowPlayingItem }
                s.seek(to: t)
                return .success
            }
        }
    }
}
