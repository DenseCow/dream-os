import SwiftUI

// Dream Cinema's brand for touch (fire-home Rainbow.kt, CinemaStyle.kt, PearlFocus; docs STANDARDS §2).
// TV focus becomes press/selection on the phone: Play-type actions fill with the rainbow fade while pressed,
// every other button is a pearl box with dark ink and a rainbow border while pressed or selected, cards and images
// take the rainbow border alone. Nothing animates at rest: the rainbow's clock runs only while something is pressed.

public enum Palette {
    /// Page: #000000 -> #050506, never tinted.
    public static let page = Color(red: 0, green: 0, blue: 0)
    public static let pageBottom = Color(hex: 0x050506)
    public static let surface = Color(hex: 0x111113)
    public static let surfaceRaised = Color(hex: 0x1C1C1E)
    /// A card's fill under its art (and a skeleton's).
    public static let tile = Color(hex: 0x151517)
    public static let hairline = Color.white.opacity(0.08)
    public static let text = Color.white
    public static let textSecondary = Color.white.opacity(0.62)
    public static let textTertiary = Color.white.opacity(0.40)
    /// PearlFocus: the pressed/selected box and its ink.
    public static let pearl = Color(hex: 0xFBF8FF)
    public static let ink = Color(hex: 0x111114)
    /// The ink's quieter shade for captions and values on pearl.
    public static let inkDim = Color(hex: 0x55555E)
    // Status colors, only for status (STANDARDS §2).
    public static let green = Color(hex: 0x30D158), greenText = Color(hex: 0x6BEB8F)
    public static let amber = Color(hex: 0xFFD60A), amberText = Color(hex: 0xFFE266)
    public static let red = Color(hex: 0xFF453A), redText = Color(hex: 0xFF8178)
    // The Watched pill only (owner-approved, STANDARDS §2): gold words and check on a dark graphite-gold capsule.
    public static let gold = Color(hex: 0xE8C468), goldFill = Color(hex: 0x2A2412)
}

public enum Rainbow {
    /// Rainbow.kt COLORS: electric blue, violet, magenta, coral, orange, yellow, lime, aqua.
    public static let hexes: [UInt32] = [0x2F7BFF, 0x8B5CFF, 0xFF3DC8, 0xFF4D6D, 0xFF8A1F, 0xFFD21F, 0x7CFF4F, 0x19E3D6]
    public static let colors: [Color] = hexes.map { Color(hex: $0) }
    /// How long the fade rests on each hue.
    public static let hueSeconds: Double = 2.2

    /// The fade's color at `date`: one solid hue easing into the next, from one clock so every Play button agrees.
    public static func fadeColor(_ date: Date = Date()) -> Color { Color(hex: fadeHex(date)) }

    /// `fadeColor` as 0xRRGGBB (for `inkOn`).
    public static func fadeHex(_ date: Date = Date()) -> UInt32 {
        let n = Double(hexes.count)
        let pos = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: hueSeconds * n) / hueSeconds
        let i = Int(pos), t = pos - Double(i)
        let e = t * t * (3 - 2 * t)
        return mixHex(hexes[i], hexes[(i + 1) % hexes.count], e)
    }

    public static var gradient: AngularGradient { AngularGradient(colors: colors + [colors[0]], center: .center) }
    public static var linear: LinearGradient { LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing) }
    /// The hue at `index`, wrapping (genre cards run the rainbow across them).
    public static func hue(_ index: Int) -> UInt32 { hexes[((index % hexes.count) + hexes.count) % hexes.count] }

    /// Readable ink on `fill`: white or the dark ink, whichever has the higher contrast (never a blend).
    public static func inkOn(_ fill: UInt32) -> Color {
        let l = luminance(fill)
        let onWhite = 1.05 / (l + 0.05)
        let onInk = (l + 0.05) / (luminance(0x111114) + 0.05)
        return onInk >= onWhite ? Palette.ink : .white
    }

    static func luminance(_ c: UInt32) -> Double {
        func lin(_ v: UInt32) -> Double { let s = Double(v) / 255; return s <= 0.03928 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF)
    }

    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> Color { Color(hex: mixHex(a, b, t)) }

    /// Blends two 0xRRGGBB colors.
    public static func mixHex(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        func ch(_ s: UInt32) -> UInt32 {
            let x = Double((a >> s) & 0xFF), y = Double((b >> s) & 0xFF)
            return UInt32(max(0, min(255, (x + (y - x) * t).rounded()))) << s
        }
        return ch(16) | ch(8) | ch(0)
    }
}

public extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// The page background: black to near-black, smooth.
public struct DreamBackground: View {
    public init() {}
    public var body: some View {
        LinearGradient(colors: [Palette.page, Palette.pageBottom], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
    }
}

/// Rainbow glowing border (cards/images get only this when pressed/selected). Animates only while `active`.
public struct RainbowBorder: ViewModifier {
    var active: Bool
    var radius: CGFloat
    var width: CGFloat
    public func body(content: Content) -> some View {
        content.overlay {
            if active {
                TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
                    let c = Rainbow.fadeColor(ctx.date)
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(c, lineWidth: width)
                        .shadow(color: c.opacity(0.45), radius: 10)
                }
                .allowsHitTesting(false)
                .transition(.opacity.animation(.easeOut(duration: 0.15)))
            }
        }
    }
}

public extension View {
    func rainbowBorder(_ active: Bool, radius: CGFloat = Cinema.rCard, width: CGFloat = 3) -> some View {
        modifier(RainbowBorder(active: active, radius: radius, width: width))
    }

    /// A light tap when a press starts (cards, buttons, chips).
    func pressHaptic(_ pressed: Bool) -> some View {
        sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: pressed) { old, new in !old && new }
    }
}

/// A button's label: optional SF Symbol icon with real spacing (≥ 8 pt) before the words.
public struct DreamLabel: View {
    let title: String
    let systemImage: String?
    public init(_ title: String, systemImage: String? = nil) { self.title = title; self.systemImage = systemImage }
    public var body: some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage).font(.body.weight(.semibold)).imageScale(.medium) }
            Text(title).lineLimit(1)
        }
    }
}

/// Play-type primary action: pearl at rest, the rainbow fade while pressed (Rainbow.Fade), ink following the hue.
public struct PlayButtonStyle: ButtonStyle {
    var fullWidth: Bool
    public init(fullWidth: Bool = false) { self.fullWidth = fullWidth }
    public func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        // Always the rainbow fade (owner, 2026-10-27), not only while a finger is on it.
        TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
            let hex = Rainbow.fadeHex(ctx.date)
            configuration.label
                .font(.body.weight(.semibold))
                .foregroundStyle(Rainbow.inkOn(hex))
                .padding(.horizontal, 22).frame(minHeight: 46)
                .frame(maxWidth: fullWidth ? .infinity : nil)
                .background(Color(hex: hex), in: RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous))
        }
        .contentShape(RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous))
        .scaleEffect(pressed ? 0.97 : 1)
        .animation(.easeOut(duration: 0.16), value: pressed)
        .pressHaptic(pressed)
    }
}

/// Every other button: dark glass at rest; pressed (or `selected`) it is a pearl box with dark ink and a rainbow border.
public struct PearlButtonStyle: ButtonStyle {
    var selected: Bool
    var fullWidth: Bool
    public init(selected: Bool = false, fullWidth: Bool = false) { self.selected = selected; self.fullWidth = fullWidth }
    public func makeBody(configuration: Configuration) -> some View {
        let lit = configuration.isPressed || selected
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(lit ? Palette.ink : Palette.text)
            .padding(.horizontal, 18).frame(minHeight: 46)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .background(lit ? Palette.pearl : Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous).strokeBorder(Color.white.opacity(lit ? 0 : 0.10), lineWidth: 1))
            .rainbowBorder(configuration.isPressed, radius: Cinema.rButton, width: 2)
            .contentShape(RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
            .pressHaptic(configuration.isPressed)
    }
}

/// A round icon control (the profile avatar, a close button): pearl with ink while pressed, rainbow ring.
public struct RoundButtonStyle: ButtonStyle {
    var size: CGFloat
    public init(size: CGFloat = 36) { self.size = size }
    public func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : Palette.text)
            .frame(width: size, height: size)
            .background(p ? Palette.pearl : Color.white.opacity(0.12), in: Circle())
            .rainbowBorder(p, radius: size / 2, width: 2)
            .contentShape(Circle())
            .scaleEffect(p ? 0.94 : 1)
            .animation(.easeOut(duration: 0.16), value: p)
            .pressHaptic(p)
    }
}
