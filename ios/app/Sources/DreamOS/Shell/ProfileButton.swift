import SwiftUI
import DreamCore
import DreamUI

/// The profile's face at the top right of every tab's first page: opens profiles and Settings (`SettingsSheet`).
struct ProfileButton: View {
    @Environment(ShellModel.self) private var shell
    @State private var profile: Profile?
    @State private var index = 0

    var body: some View {
        Button { shell.showSettings = true } label: {
            Group {
                if let profile {
                    ProfileFace(emoji: ProfileLook.emoji(profile, index: index), color: ProfileLook.color(profile, index: index), size: 32)
                } else {
                    Image(systemName: "person.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(width: 32, height: 32)
                        .background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(AvatarPressStyle())
        .accessibilityLabel("Profiles and Settings")
        .task(id: shell.showSettings) {
            guard !shell.showSettings else { return }
            let all = await Services.shared.profiles.profiles()
            let p = await Services.shared.profiles.current()
            profile = p
            index = all.firstIndex { $0.id == p?.id } ?? 0
        }
    }
}

private struct AvatarPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .rainbowBorder(configuration.isPressed, radius: 9, width: 2)
            .scaleEffect(configuration.isPressed ? 0.93 : 1)
            .animation(Cinema.pressAnimation, value: configuration.isPressed)
            .pressHaptic(configuration.isPressed)
    }
}
