import SwiftUI
import DreamCore
import DreamUI

/// Four-digit PIN entry (PinPad.kt): a card with the title, four dots and a keypad; it submits by itself once four
/// digits are in. Wrong PINs shake the dots and say why.
struct PinPadView: View {
    enum Mode {
        /// Check a PIN: the closure answers nil when it's right, else the plain error ("Wrong PIN, try again").
        case verify(subtitle: String?, check: (String) async -> String?)
        /// Choose a new PIN (entered twice); the closure saves it.
        case choose(save: (String) async -> Void)
    }

    /// Face ID on the pad (a profile with Unlock With Face ID on): tried by itself once when the pad opens, then again
    /// from the small button next to Cancel. The PIN always works too.
    struct FaceIdOffer {
        /// "Face ID" / "Touch ID".
        var label: String
        /// "faceid" / "touchid".
        var symbol: String
        var auto = true
        /// nil = passed; "" = cancelled (the pad just stays); else the plain line to show.
        var run: () async -> String?
    }

    var title: String
    var mode: Mode
    var faceId: FaceIdOffer? = nil
    var done: (Bool) -> Void

    @State private var digits = ""
    @State private var first: String?
    @State private var error: String?
    @State private var shake = 0
    @State private var busy = false
    @State private var triedFaceId = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.72).ignoresSafeArea()
            VStack(spacing: 0) {
                Text(headline)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .multilineTextAlignment(.center)
                Text(error ?? subline ?? " ")
                    .font(.system(size: 15))
                    .foregroundStyle(error != nil ? Palette.redText : Palette.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.top, 6)
                HStack(spacing: 18) {
                    ForEach(0..<4, id: \.self) { i in
                        Circle()
                            .fill(i < digits.count ? Palette.pearl : Color.clear)
                            .overlay(Circle().strokeBorder(Palette.pearl.opacity(0.6), lineWidth: 1.5))
                            .frame(width: 16, height: 16)
                            .scaleEffect(i == digits.count - 1 ? 1.1 : 1)
                    }
                }
                .modifier(Shake(amount: CGFloat(shake)))
                .padding(.vertical, 24)
                keypad
                HStack(spacing: 14) {
                    Button("Cancel") { done(false) }
                        .buttonStyle(PearlButtonStyle())
                    if let faceId {
                        Button { PageHaptics.tap(); runFaceId(faceId) } label: {
                            Image(systemName: faceId.symbol).font(.system(size: 22, weight: .regular))
                        }
                        .buttonStyle(RoundButtonStyle(size: 46))
                        .disabled(busy)
                        .accessibilityLabel("Use \(faceId.label)")
                    }
                }
                .padding(.top, 18)
            }
            .padding(.horizontal, 30).padding(.vertical, 28)
            .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Palette.surfaceRaised))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
            .padding(20)
        }
        .task {
            guard let faceId, faceId.auto, !triedFaceId else { return }
            triedFaceId = true
            // A moment for the pad to settle (and the app to be active at launch) before the system sheet comes up.
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            runFaceId(faceId)
        }
    }

    private func runFaceId(_ offer: FaceIdOffer) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            let line = await offer.run()
            busy = false
            switch line {
            case nil: PageHaptics.success(); done(true)
            case ""?: break
            case let e?: PageHaptics.warning(); error = e
            }
        }
    }

    private var headline: String {
        switch mode {
        case .verify: return title
        case .choose: return first == nil ? "Choose a 4-Digit PIN" : "Confirm Your PIN"
        }
    }

    private var subline: String? {
        switch mode {
        case .verify(let s, _): return s
        case .choose: return first == nil ? title : "Enter the same 4 digits again."
        }
    }

    private var keypad: some View {
        let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["del", "0", "done"]]
        return VStack(spacing: 12) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 18) {
                    ForEach(row, id: \.self) { k in key(k) }
                }
            }
        }
    }

    @ViewBuilder private func key(_ k: String) -> some View {
        Button {
            PageHaptics.tap()
            switch k {
            case "del": if !digits.isEmpty { digits.removeLast() }
            case "done": submit()
            default: append(k)
            }
        } label: {
            Group {
                switch k {
                case "del": Image(systemName: "delete.left").font(.system(size: 22, weight: .medium))
                case "done": Image(systemName: "checkmark").font(.system(size: 22, weight: .semibold))
                default: Text(k).font(.system(size: 28, weight: .regular))
                }
            }
            .frame(width: 70, height: 70)
        }
        .buttonStyle(KeyStyle())
        .disabled(busy)
        .accessibilityLabel(k == "del" ? "Delete" : (k == "done" ? "Done" : k))
    }

    private func append(_ d: String) {
        guard digits.count < 4 else { return }
        error = nil
        digits += d
        if digits.count == 4 { submit() }
    }

    private func submit() {
        guard digits.count == 4, !busy else { return }
        let pin = digits
        switch mode {
        case .verify(_, let check):
            busy = true
            Task {
                let e = await check(pin)
                busy = false
                if let e { fail(e) } else { PageHaptics.success(); done(true) }
            }
        case .choose(let save):
            if first == nil {
                first = pin
                digits = ""
            } else if first == pin {
                busy = true
                Task { await save(pin); busy = false; PageHaptics.success(); done(true) }
            } else {
                first = nil
                fail("PINs didn't match. Try again.")
            }
        }
    }

    private func fail(_ e: String) {
        PageHaptics.warning()
        error = e
        digits = ""
        withAnimation(.linear(duration: 0.4)) { shake += 1 }
    }
}

private struct KeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : Palette.text)
            .background(Circle().fill(p ? Palette.pearl : Color.white.opacity(0.08)))
            .overlay { if p { Circle().strokeBorder(Rainbow.fadeColor(), lineWidth: 2) } }
            .scaleEffect(p ? 0.95 : 1)
            .animation(.easeOut(duration: 0.12), value: p)
    }
}

/// A horizontal shake (three wiggles per step).
struct Shake: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat {
        get { amount }
        set { amount = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 10 * sin(amount * .pi * 6), y: 0))
    }
}
