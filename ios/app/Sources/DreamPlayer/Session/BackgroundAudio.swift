import Foundation
import AVFoundation
import DreamCore

/// The audio session for Dream Player: `.playback` / movie mode (sound with the silent switch on, in the
/// background and with the screen locked; Info.plist has UIBackgroundModes audio), plus the system's
/// interruptions: a call or an alarm pauses and, when the system says so, resumes; unplugging headphones or
/// AirPods dropping out pauses (like every video app).
@MainActor
enum BackgroundAudio {
    private static var started = false
    private static var resumeAfterInterruption = false
    private static var observers: [NSObjectProtocol] = []
    /// Bumped by every `activate`: a release queued before a new player started is dropped.
    private static var generation = 0

    static func start() {
        activate()
        guard !started else { return }
        started = true
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { n in
            let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let opts = AVAudioSession.InterruptionOptions(rawValue: (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0)
            MainActor.assumeIsolated { interruption(type, resume: opts.contains(.shouldResume)) }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { n in
            let reason = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            MainActor.assumeIsolated {
                guard reason == .oldDeviceUnavailable, let s = NowPlaying.session,
                      s.state.isPlaying || (s.state.isBuffering && !s.state.paused) else { return }
                Log.player.info("Audio: output went away, pausing")
                s.engine.pause()
            }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { activate() }
        })
    }

    static func activate() {
        let a = AVAudioSession.sharedInstance()
        do {
            try a.setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
        } catch {
            try? a.setCategory(.playback, mode: .moviePlayback)
        }
        try? a.setActive(true)
        generation += 1
    }

    /// The player closed: once mpv's sound has stopped (its teardown runs on its own queue), the session goes inactive
    /// and tells others, so music or a podcast the player interrupted resumes. Dropped when a player starts meanwhile.
    static func release() {
        let g = generation
        Task { @MainActor in
            for delay in [0.4, 1.0, 2.0] {
                try? await Task.sleep(for: .seconds(delay))
                guard g == generation, NowPlaying.session == nil else { return }
                // Busy while mpv's audio unit is still running: tried again a moment later.
                if (try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)) != nil {
                    Log.player.info("Audio: session released")
                    return
                }
            }
        }
    }

    private static func interruption(_ type: AVAudioSession.InterruptionType?, resume: Bool) {
        guard let s = NowPlaying.session else { return }
        switch type {
        case .began:
            resumeAfterInterruption = s.state.isPlaying || (s.state.isBuffering && !s.state.paused)
            Log.player.info("Audio: interrupted (was playing: \(resumeAfterInterruption, privacy: .public))")
            if resumeAfterInterruption { s.engine.pause() }
        case .ended:
            activate()
            if resume && resumeAfterInterruption { s.engine.play() }
            resumeAfterInterruption = false
        default:
            break
        }
    }
}
