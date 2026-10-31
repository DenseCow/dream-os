import SwiftUI
import DreamCore
import DreamUI

// The one shape every setup step takes (fire-home docs/setup-redesign.md, layer 1): head, body, actions pinned at the
// bottom with exactly one primary. The body scrolls only when it can't fit (small phones, the largest text sizes).

/// Setup's chapters, the TV's four; its Your Fire Stick chapter is Your iPhone here.
enum SetupChapter: Int, CaseIterable {
    case getReady, connect, device, makeItYours
    var title: String { ["Get Ready", "Connect", "Your iPhone", "Make It Yours"][rawValue] }
}

/// One button on a step.
struct SetupAction {
    var title: String
    var run: () -> Void
}

/// The spacing scale setup uses (pt). One scale so every step shares a rhythm and nothing touches.
enum SetupSpace {
    static let gutter: CGFloat = 20
    static let headToBody: CGFloat = 24
    static let row: CGFloat = 8
    static let group: CGFloat = 16
}

/// A step's frame: the scrolling-if-needed column and the pinned actions.
struct SetupFrame<Content: View>: View {
    var primary: SetupAction
    var secondary: [SetupAction] = []
    var blocker: String? = nil
    var shake: Int = 0
    var centered = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: centered ? .center : .leading, spacing: 0) {
                    content()
                }
                .frame(maxWidth: 560, alignment: centered ? .center : .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, SetupSpace.gutter)
                .padding(.top, 8)
                .padding(.bottom, 20)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
            actions
        }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            if let blocker {
                Text(blocker)
                    .font(.subheadline)
                    .foregroundStyle(Palette.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .modifier(Shake(amount: CGFloat(shake)))
                    .transition(.opacity)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            SetupPrimaryButton(title: primary.title, action: primary.run)
            if !secondary.isEmpty {
                PageButtonPair(spacing: 10) {
                    ForEach(Array(secondary.enumerated()), id: \.offset) { _, a in
                        SetupQuietButton(title: a.title, action: a.run)
                    }
                }
            }
        }
        .frame(maxWidth: 560)
        .padding(.horizontal, SetupSpace.gutter)
        .padding(.top, 12)
        .padding(.bottom, 10)
        .animation(.easeOut(duration: 0.2), value: blocker)
    }
}

/// The step's one forward action: the rainbow fade fill (the brand's only accent besides progress).
struct SetupPrimaryButton: View {
    var title: String
    var action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            Text(title)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(PlayButtonStyle(fullWidth: true))
        // Reduce Motion: the fade holds one hue (the style's own pause switch).
        .environment(\.pageActive, !reduceMotion)
    }
}

/// A secondary action: quiet glass at rest, the pearl box with the rainbow border while pressed.
struct SetupQuietButton: View {
    var title: String
    var action: () -> Void
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 46)
        }
        .buttonStyle(PearlButtonStyle(fullWidth: true))
    }
}

/// Emblem and "Step 2 of 4", then the title and the step's sentence.
struct SetupHead: View {
    var art: SetupArt.Kind
    var check: SetupArt.CheckState = .idle
    var counter: String
    var title: String
    var sentence: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 14) {
                SetupArt(art, size: 48, check: check)
                VStack(alignment: .leading, spacing: 2) {
                    Text(counter)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Palette.textSecondary)
                    Text(title)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(sentence)
                .font(.body)
                .foregroundStyle(Palette.textSecondary)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The chapter rail: three labeled segments. The finished part is the rainbow, stretched over exactly what's done so
/// every color shows. Current chapter white and semibold, finished ones 70% white, upcoming 52% white.
struct ChapterRail: View {
    var chapter: SetupChapter
    /// The current chapter's share done, counting the step on screen (Step 1 of 4 = 0.25).
    var fraction: Double
    var counterText: String

    private let gap: CGFloat = 6

    var body: some View {
        VStack(spacing: 8) {
            GeometryReader { geo in
                let n = CGFloat(SetupChapter.allCases.count)
                let seg = (geo.size.width - gap * (n - 1)) / n
                let done = CGFloat(chapter.rawValue) * (seg + gap) + seg * CGFloat(fraction)
                ZStack(alignment: .leading) {
                    Color.white.opacity(0.16)
                    RainbowFade { h in Color(hex: h) }.frame(width: max(0, done))
                }
                .mask {
                    HStack(spacing: gap) {
                        ForEach(SetupChapter.allCases, id: \.self) { _ in Capsule() }
                    }
                }
                .animation(.easeInOut(duration: 0.45), value: done)
            }
            .frame(height: 4)
            HStack(spacing: gap) {
                ForEach(SetupChapter.allCases, id: \.self) { c in
                    Text(c.title)
                        .font(.caption.weight(c == chapter ? .semibold : .regular))
                        .foregroundStyle(Color.white.opacity(c == chapter ? 1 : (c.rawValue < chapter.rawValue ? 0.7 : 0.52)))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(chapter.title), \(counterText)")
    }
}

/// A numbered card on Here's What You'll Need: what it is, what it costs, how long it takes.
struct NeedCard: View {
    var number: Int
    var title: String
    var caption: String
    var facts: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Text("\(number)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.text)
                .frame(width: 30, height: 30)
                .overlay(Circle().strokeBorder(Color.white.opacity(0.32), lineWidth: 1))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).foregroundStyle(Palette.text)
                Text(caption).font(.subheadline).foregroundStyle(Palette.textSecondary)
                Text(facts).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text.opacity(0.88))
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(number). \(title). \(caption). \(facts)")
    }
}

/// A big result line on a step: a status disc, a headline and one sentence ("Your TMDB Key Is Added").
struct SetupResult: View {
    var mark: PageMark
    var headline: String
    var line: String?

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            PageMarkGlyph(mark: mark, size: 40)
            VStack(alignment: .leading, spacing: 6) {
                Text(headline).font(.title3.weight(.semibold)).foregroundStyle(Palette.text)
                if let line, !line.isEmpty {
                    Text(line).font(.subheadline).foregroundStyle(Palette.textSecondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A short numbered instruction on a step's body (the long walkthrough lives in Help).
struct ShortSteps: View {
    var steps: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("\(i + 1)")
                        .font(.footnote.weight(.bold)).foregroundStyle(Palette.ink)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Palette.pearl))
                        .accessibilityHidden(true)
                    Text(s)
                        .font(.body)
                        .foregroundStyle(Palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Step \(i + 1). \(s)")
            }
        }
    }
}
