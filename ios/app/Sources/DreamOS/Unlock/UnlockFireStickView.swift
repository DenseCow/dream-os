import SwiftUI
import DreamCore
import DreamUI

/// Settings › Fire TV › Unlock Fire Stick: unlocks Dream OS's features on a Fire TV from this iPhone, no computer
/// needed. Pick the Fire TV (it shows up while Dream OS's Unlock screen is open on it) or type its address, choose OK
/// on the TV once, and the grants run. The result lists each feature the way the TV does.
struct UnlockFireStickView: View {
    @State private var model = FireStickUnlockModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PageScaffold(title: "Unlock Fire Stick",
                     subtitle: "Let Dream OS take over your Fire Stick: the Home button, startup, voice search and the screensaver. The Fire Stick allows that after a one-time unlock, which this iPhone does over your Wi-Fi.") {
            VStack(alignment: .leading, spacing: 0) {
                switch model.phase {
                case .pick: pick
                case .working(let stage): working(stage)
                case .done: done
                case .failed(let problem): failed(problem)
                }
            }
            .animation(.easeOut(duration: 0.2), value: model.phase)
        }
        .onAppear { model.startLooking() }
        .onDisappear { model.stopLooking(); model.cancelIfWorking() }
    }

    // MARK: Pick the Fire TV

    @ViewBuilder private var pick: some View {
        PageSection(title: "Before You Start")
        VStack(alignment: .leading, spacing: 12) {
            UnlockStep(n: 1, text: "On the Fire Stick, open Settings › My Fire TV › Developer Options and turn on ADB Debugging.")
            UnlockStep(n: 2, text: "No Developer Options? Open About and press Select on the Fire Stick's name 7 times, then go back.")
            UnlockStep(n: 3, text: "Keep this iPhone on the same Wi-Fi as the Fire Stick.")
        }
        .padding(.top, 12)

        PageSection(title: "Your Fire Stick").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            if model.found.isEmpty {
                PageInfoRow(title: "Looking on Your Wi-Fi",
                            caption: "Open Dream OS › Settings › Unlock Features on the TV and it shows up here.",
                            lead: .mark(.busy))
            } else {
                ForEach(model.found) { stick in
                    PageRow(title: stick.name, caption: "On Your Wi-Fi", lead: .symbol("tv"), chevron: true) {
                        model.unlock(stick)
                    }
                }
            }
        }
        .padding(.top, 10)

        PageSection(title: "Or Enter Its Address").padding(.top, PageSpace.group)
        VStack(alignment: .leading, spacing: 12) {
            PageTextField(placeholder: "192.168.1.20", text: $model.address, keyboard: .numbersAndPunctuation) {
                model.unlockTyped()
            }
            PageNote(text: "The Fire Stick shows it on Dream OS's unlock screen, and in Settings › My Fire TV › About › Network.", size: 13.5)
            PagePrimaryButton(title: "Unlock") { model.unlockTyped() }
                .padding(.top, 4)
        }
        .padding(.top, 10)
    }

    // MARK: Working

    @ViewBuilder private func working(_ stage: FireStickUnlock.Stage) -> some View {
        PageResult(mark: .busy, headline: headline(stage))
        PageNote(text: detail(stage)).padding(.top, 12)
        if stage == .allow {
            UnlockAllowPicture().padding(.top, 20)
        }
        PageSecondaryButton(title: "Cancel") { model.cancel() }
            .padding(.top, 24)
    }

    private func headline(_ stage: FireStickUnlock.Stage) -> String {
        switch stage {
        case .connecting: "Connecting to Your Fire Stick"
        case .allow: "Choose OK on the TV"
        case .unlocking: "Unlocking"
        }
    }

    private func detail(_ stage: FireStickUnlock.Stage) -> String {
        switch stage {
        case .connecting: "Reaching \(model.target) on your Wi-Fi."
        case .allow: "The Fire Stick asks to allow USB debugging. With the remote, check “Always allow from this computer”, then choose OK."
        case .unlocking: "Turning on the Home button, voice search and the rest. This takes a few seconds."
        }
    }

    // MARK: Done

    @ViewBuilder private var done: some View {
        let all = model.features.allSatisfy(\.on)
        PageResult(mark: all ? .ok : .warn, headline: all ? "Your Fire Stick Is Unlocked" : "Almost Unlocked")
        PageNote(text: all ? "Dream OS on the TV picks this up by itself. Press Home on the remote to see it."
                           : "A few features didn't turn on. Try again, and Dream OS on the TV finishes the rest when it can.")
            .padding(.top, 12)

        PageSection(title: "What It Turned On").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            ForEach(model.features) { f in
                PageInfoRow(title: f.title, value: f.on ? "On" : "Off", valueTone: f.on ? Palette.greenText : Palette.amberText,
                            lead: .mark(f.on ? .ok : .off))
            }
        }
        .padding(.top, 10)

        PageSection(title: "Finish").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            if model.adbTurnedOff {
                PageInfoRow(title: "ADB Debugging Is Off", caption: "Everything stays unlocked.", lead: .mark(.ok))
            } else {
                PageRow(title: "Turn Off ADB Debugging", caption: "Recommended. Everything stays unlocked.",
                        value: model.turningOff ? "Turning Off…" : nil, lead: .symbol("lock"),
                        trailingMark: model.turningOff ? .busy : .none) { model.turnOffAdb() }
            }
        }
        .padding(.top, 10)

        VStack(spacing: 10) {
            PagePrimaryButton(title: "Done") { dismiss() }
            if !all { PageSecondaryButton(title: "Try Again") { model.tryAgain() } }
        }
        .padding(.top, 28)
    }

    // MARK: Failed

    @ViewBuilder private func failed(_ problem: UnlockProblem) -> some View {
        PageResult(mark: .warn, headline: problem.title)
        PageNote(text: problem.message).padding(.top, 12)
        VStack(spacing: 10) {
            if problem == .localNetwork {
                PagePrimaryButton(title: "Open iPhone Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            if problem == .badAddress {
                PagePrimaryButton(title: "Enter the Address Again") { model.chooseAnother() }
            } else {
                PagePrimaryButton(title: "Try Again") { model.tryAgain() }
                PageSecondaryButton(title: "Choose Another Fire Stick") { model.chooseAnother() }
            }
        }
        .padding(.top, 24)
    }
}

/// A numbered step: the round badge and the whole sentence beside it.
private struct UnlockStep: View {
    var n: Int
    var text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            PageMonogram(text: "\(n)", size: 28)
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 5 }
            Text(text)
                .font(.system(size: 15.5))
                .foregroundStyle(Palette.text)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 6)
    }
}

/// The TV's question, drawn small so the viewer knows what to look for: the checkbox and the OK button.
private struct UnlockAllowPicture: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Allow USB debugging?")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.text)
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(Palette.pearl, lineWidth: 1.5)
                    .frame(width: 18, height: 18)
                    .overlay(PageCheck(color: Palette.pearl, weight: 0.16).frame(width: 18, height: 18))
                Text("Always allow from this computer")
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 10) {
                Spacer()
                Text("Cancel").font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal, 14).padding(.vertical, 7)
                Text("OK").font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.ink)
                    .padding(.horizontal, 18).padding(.vertical, 7)
                    .background(Capsule().fill(Palette.pearl))
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("On the TV: Allow USB debugging? Check Always allow from this computer, then choose OK.")
    }
}
