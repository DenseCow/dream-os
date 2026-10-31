import SwiftUI
import DreamUI

/// What a level swipe changes: the left third of the picture is the screen's brightness, the right third the volume.
enum LevelKind: Equatable {
    case brightness, volume

    var name: String { self == .brightness ? "Brightness" : "Volume" }
}

/// The slim level bar beside the picture while the viewer swipes brightness or volume (VLC / Infuse style):
/// a dark-glass capsule with a thin track filling bottom to top in the fading rainbow (the brand's only colour, one hue
/// at a time, with its soft glow) and a drawn sun or speaker under it, 14 pt clear of the track.
struct LevelBar: View {
    let kind: LevelKind
    let level: Double

    static let trackHeight: CGFloat = 128
    static let trackWidth: CGFloat = 6

    var body: some View {
        let l = min(max(level, 0), 1)
        VStack(spacing: 14) {
            ZStack(alignment: .bottom) {
                Capsule().fill(Color.white.opacity(0.16))
                RainbowFade { h in
                    Capsule()
                        .fill(Color(hex: h))
                        .mask(alignment: .bottom) {
                            Capsule().frame(height: max(0, Self.trackHeight * l))
                        }
                        .shadow(color: Color(hex: h).opacity(l > 0 ? 0.55 : 0), radius: 5)
                }
            }
            .frame(width: Self.trackWidth, height: Self.trackHeight)
            Image(systemName: glyph(l))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                // A fixed box: the speaker's waves come and go without the bar changing size.
                .frame(width: 28, height: 20)
        }
        .padding(.top, 18).padding(.bottom, 16)
        .frame(width: 46)
        .background(Capsule().fill(Color(white: 0.06).opacity(0.74)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.14), lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 12)
        .animation(.easeOut(duration: 0.08), value: l)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(kind.name) \(Int((l * 100).rounded())) Percent")
    }

    private func glyph(_ l: Double) -> String {
        switch kind {
        case .brightness: return l < 0.5 ? "sun.min.fill" : "sun.max.fill"
        case .volume:
            if l <= 0.001 { return "speaker.slash.fill" }
            if l < 0.34 { return "speaker.wave.1.fill" }
            if l < 0.67 { return "speaker.wave.2.fill" }
            return "speaker.wave.3.fill"
        }
    }
}
