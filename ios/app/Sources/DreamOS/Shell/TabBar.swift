import SwiftUI
import DreamUI

/// The bottom tab bar (CinemaShell's top bar for touch): black glass, a hairline on top; the current tab bright and
/// bold with the rainbow line centered under its label, the others quiet. No bubble, no colored fills.
struct DreamTabBar: View {
    let selected: ShellModel.Tab
    let onSelect: (ShellModel.Tab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(ShellModel.Tab.allCases) { t in
                let current = t == selected
                Button { onSelect(t) } label: {
                    VStack(spacing: 4) {
                        Image(systemName: current ? t.iconSelected : t.icon)
                            .font(.system(size: 19, weight: current ? .semibold : .regular))
                            .frame(height: 24)
                        Text(t.title)
                            .font(.system(size: 10.5, weight: current ? .semibold : .medium))
                            .lineLimit(1)
                        // The current tab's line in the fading rainbow (only that one runs the clock).
                        Group {
                            if current { RainbowFade { h in Capsule().fill(Color(hex: h)) } } else { Color.clear }
                        }
                        .frame(width: 18, height: 3)
                    }
                    .foregroundStyle(current ? Palette.text : Palette.text.opacity(0.55))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
                    .padding(.bottom, 2)
                    .contentShape(Rectangle())
                }
                .buttonStyle(TabPressStyle())
                .accessibilityLabel(t.title)
                .accessibilityAddTraits(current ? [.isSelected, .isButton] : .isButton)
            }
        }
        .padding(.horizontal, 8)
        .background {
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                Color.black.opacity(0.66)
            }
            .overlay(alignment: .top) { Rectangle().fill(Palette.hairline).frame(height: 1) }
            .ignoresSafeArea(edges: .bottom)
        }
        .sensoryFeedback(.selection, trigger: selected)
        .animation(.easeOut(duration: 0.18), value: selected)
    }
}

private struct TabPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(Cinema.pressAnimation, value: configuration.isPressed)
    }
}
