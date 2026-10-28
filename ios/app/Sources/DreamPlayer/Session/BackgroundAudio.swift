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
                guard reason == .oldDeviceUnavailable, let s = NowPlaying.session, s.state.isPlaying else { return }
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
