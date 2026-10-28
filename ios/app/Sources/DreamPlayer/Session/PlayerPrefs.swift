import Foundation
import DreamCore

/// The viewer's player choices, per profile (fire-home PlayerPrefs in PlayerMenus.kt; SkipMarkers auto-skip;
/// PostPlay settings). Stored in DreamStore under "player.*".
public enum PlayerPrefs {
    public static let off = "off"

    public static let sizes = ["S", "M", "L", "XL"]
    public static let sizeScale: [Double] = [0.78, 1.0, 1.25, 1.5]
    public static let styles = ["Classic", "Boxed", "Yellow"]
    public static let positions = ["Low", "Standard", "Raised"]
    public static let positionFraction: [Double] = [0.045, 0.08, 0.16]
    public static let boostLabels = ["Off", "+25%", "+50%", "+100%"]
    /// LoudnessEnhancer 0 / 300 / 600 / 1000 mB.
    public static let boostDb: [Double] = [0, 3, 6, 10]

    private static func int(_ k: String, _ def: Int, _ range: ClosedRange<Int>) -> Int {
        min(max(DreamStore.shared.get(Int.self, "player.\(k)", perProfile: true) ?? def, range.lowerBound), range.upperBound)
    }
    private static func setInt(_ k: String, _ v: Int) { DreamStore.shared.set(v, "player.\(k)", perProfile: true) }
    private static func str(_ k: String) -> String? { DreamStore.shared.get(String.self, "player.\(k)", perProfile: true) }
    private static func bool(_ k: String, _ def: Bool) -> Bool { DreamStore.shared.get(Bool.self, "player.\(k)", perProfile: true) ?? def }

    public static var subSize: Int { get { int("subSize", 1, 0...3) } set { setInt("subSize", newValue) } }
    public static var subStyle: Int { get { int("subStyle", 0, 0...2) } set { setInt("subStyle", newValue) } }
    public static var subPos: Int { get { int("subPos", 1, 0...2) } set { setInt("subPos", newValue) } }
    public static var boost: Int { get { int("boost", 0, 0...3) } set { setInt("boost", newValue) } }

    /// Normalized code, nil = never chosen.
    public static var audioLang: String? { str("audioLang") }
    /// "off", a code, or nil (never chosen).
    public static var subLang: String? { str("subLang") }
    /// Does nothing for nil (a track with an unknown language never clears the choice).
    public static func setAudioLang(_ l: String?) { if let l { DreamStore.shared.set(l, "player.audioLang", perProfile: true) } }
    public static func setSubLang(_ l: String?) { if let l { DreamStore.shared.set(l, "player.subLang", perProfile: true) } }

    /// Skip Intros Automatically (default off).
    public static var autoSkipIntros: Bool {
        get { bool("autoSkipIntro", false) }
        set { DreamStore.shared.set(newValue, "player.autoSkipIntro", perProfile: true) }
    }
    /// Autoplay Next Episode (default on): one setting for the whole app, like the TV's PlayFlow prefs
    /// (`PlaybackSettings`, "play.autoplayNext"), not per profile.
    public static var autoplayNext: Bool {
        get { PlaybackSettings.autoplayNext }
        set { PlaybackSettings.autoplayNext = newValue }
    }
    /// "Are You Still Watching?" is off on iPhone (owner, 2026-10-28): autoplay never stops to ask. The Fire Stick keeps it.
    public static var stillWatching: Bool {
        get { false }
        set { PlaybackSettings.stillWatchingCheck = newValue }
    }

    /// The device's language ("en").
    public static var deviceLang: String { Labels.normLang(Locale.preferredLanguages.first) ?? "en" }

    public static func style(raised: Bool) -> SubtitleStyle {
        var s = SubtitleStyle()
        s.scale = sizeScale[subSize]
        switch subStyle {
        case 1: s.color = 0xFFFFFF; s.background = 190.0 / 255
        case 2: s.color = 0xFFE45C
        default: s.color = 0xFFFFFF
        }
        s.position = positionFraction[subPos]
        s.raised = raised
        return s
    }
}
