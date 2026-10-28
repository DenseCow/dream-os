import SwiftUI
import DreamCore
import DreamUI

/// Avatars and colors a profile can take (Profiles.kt EMOJIS / COLORS).
enum ProfileLook {
    static let emojis = ["😀", "😎", "🦄", "🐱", "🐶", "🦊", "👾", "🌟", "🚀", "🎮", "🌸", "🐼"]
    static let colors: [Int] = [0x7C5CFF, 0x38BDF8, 0xF472B6, 0x4ADE80, 0xFBBF24, 0xF87171, 0xA78BFA, 0x2DD4BF]
    static let maxProfiles = 8

    static func color(_ p: Profile, index: Int) -> Int { p.color ?? colors[index % colors.count] }
    static func emoji(_ p: Profile, index: Int) -> String { p.avatar.isEmpty ? emojis[index % emojis.count] : p.avatar }

    /// "Kids · Up to PG", "Up to PG-13", "All Ratings", then "PIN", "You".
    static func caption(_ p: Profile, current: String?) -> String {
        let lv = KidsLevels.level(of: p)
        var bits: [String] = []
        if p.isKids { bits.append("Kids · Up to \(KidsLevels.all[lv].movie)") }
        else if lv < KidsLevels.everyone { bits.append("Up to \(KidsLevels.all[lv].movie)") }
        else { bits.append("All Ratings") }
        if p.hasPin { bits.append("PIN") }
        if p.id == current { bits.append("You") }
        return bits.joined(separator: " · ")
    }
}

/// A profile's face: a rounded square in its own color with its picture, a lock badge when it has a PIN.
struct ProfileFace: View {
    var emoji: String
    var color: Int
    var size: CGFloat
    var locked = false
    var editing = false
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
                .fill(LinearGradient(colors: [Color(hex: UInt32(color)).opacity(1).blend(.white, 0.12),
                                              Color(hex: UInt32(color)).blend(.black, 0.38)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
            Text(emoji).font(.system(size: size * 0.44))
            if editing {
                RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Color.black.opacity(0.55))
                Image(systemName: "pencil").font(.system(size: size * 0.26, weight: .semibold)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .overlay(alignment: .topTrailing) {
            if locked {
                Image(systemName: "lock.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .frame(width: 26, height: 26)
                    .background(Circle().fill(Palette.surfaceRaised))
                    .overlay(Circle().strokeBorder(Color(hex: 0x0A0A0C), lineWidth: 2))
                    .padding(6)
            }
        }
        .accessibilityHidden(true)
    }
}

extension Color {
    /// A plain sRGB mix, for profile faces (Color.mix is iOS 18).
    func blend(_ other: Color, _ t: Double) -> Color {
        let a = UIColor(self), b = UIColor(other)
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        a.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        b.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let f = CGFloat(t)
        return Color(red: r1 + (r2 - r1) * f, green: g1 + (g2 - g1) * f, blue: b1 + (b2 - b1) * f)
    }
}

/// "Who's Watching?" (ProfilePicker.kt, Netflix / Disney+ look): the profiles as big faces, Add Profile, and
/// Edit Profiles (tapping a face then edits it). A PIN-locked profile asks for its PIN; leaving a Kids profile
/// whose "Ask for a PIN to Leave" is on asks for a grown-up's PIN.
struct ProfilePickerView: View {
    /// The profile already unlocked (opened from Settings): choosing or editing it asks no PIN.
    var trustedId: String? = nil
    var onChosen: (Profile) -> Void

    @State private var profiles: [Profile] = []
    @State private var current: Profile?
    @State private var editing = false
    @State private var loaded = false
    @State private var pinFor: PinAsk?
    @State private var edit: EditTarget?

    struct PinAsk: Identifiable {
        let id = UUID()
        let profile: Profile
        /// True: the PIN switches to this profile; false: it is only checked (editing, leaving Kids).
        var switches = true
        let then: (String) -> Void
    }

    enum EditTarget: Identifiable {
        case existing(Profile)
        case add
        var id: String { if case .existing(let p) = self { return p.id }; return "__add" }
    }

    var body: some View {
        GeometryReader { geo in
            let size: CGFloat = geo.size.width < 400 ? 104 : 120
            let cols = max(2, min(4, Int((geo.size.width - 40) / (size + 28))))
            ScrollView {
                VStack(spacing: 28) {
                    Spacer(minLength: max(20, geo.size.height * 0.12))
                    Text("Who's Watching?")
                        .font(.system(size: 32, weight: .semibold))
                        .tracking(-0.3)
                        .foregroundStyle(Palette.text)
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(size + 16), spacing: 24), count: cols), spacing: 26) {
                        ForEach(Array(profiles.enumerated()), id: \.element.id) { i, p in
                            Button { tap(p) } label: {
                                VStack(spacing: 14) {
                                    ProfileFace(emoji: ProfileLook.emoji(p, index: i), color: ProfileLook.color(p, index: i),
                                                size: size, locked: p.hasPin, editing: editing)
                                    Text(p.name.isEmpty ? "Profile" : p.name)
                                        .font(.system(size: 16, weight: .medium))
                                        .foregroundStyle(Palette.textSecondary)
                                        .lineLimit(1)
                                        .frame(maxWidth: size + 16)
                                }
                            }
                            .buttonStyle(CardPressStyle(radius: size * 0.22))
                            .contextMenu { Button("Edit Profile", systemImage: "pencil") { openEdit(p) } }
                            .accessibilityLabel(p.name)
                        }
                        if profiles.count < ProfileLook.maxProfiles && loaded {
                            Button { PageHaptics.tap(); edit = .add } label: {
                                VStack(spacing: 14) {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Color.white.opacity(0.06))
                                        RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).strokeBorder(Color.white.opacity(0.32), lineWidth: 2)
                                        Image(systemName: "plus").font(.system(size: size * 0.32, weight: .light)).foregroundStyle(Palette.text.opacity(0.8))
                                    }
                                    .frame(width: size, height: size)
                                    Text("Add Profile").font(.system(size: 16, weight: .medium)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                                }
                            }
                            .buttonStyle(CardPressStyle(radius: size * 0.22))
                        }
                    }
                    .padding(.horizontal, 20)
                    if !profiles.isEmpty {
                        Button(editing ? "Done" : "Edit Profiles") {
                            PageHaptics.select()
                            withAnimation(.easeOut(duration: 0.2)) { editing.toggle() }
                        }
                        .buttonStyle(PearlButtonStyle(selected: editing))
                    }
                    Spacer(minLength: 30)
                }
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .scrollIndicators(.hidden)
        }
        .background(DreamBackground())
        .task { await reload() }
        .sheet(item: $edit) { target in
            NavigationStack {
                switch target {
                case .existing(let p): ProfileEditView(profile: p, isFirst: profiles.first?.id == p.id, isCurrent: current?.id == p.id) { Task { await reload() } }
                case .add: ProfileEditView(profile: nil, isFirst: false, isCurrent: false) { Task { await reload() } }
                }
            }
            .dreamNavigationLook()
            .presentationBackground(Palette.page)
        }
        .overlay {
            if let ask = pinFor {
                PinPadView(title: "Enter PIN", mode: .verify(subtitle: "\(ProfileLook.emoji(ask.profile, index: 0))  \(ask.profile.name)", check: { pin in
                    do {
                        if ask.switches { try await Services.shared.profiles.select(ask.profile.id, pin: pin) }
                        else { try await Services.shared.profiles.checkPin(ask.profile.id, pin: pin) }
                        ask.then(pin)
                        return nil
                    } catch let e as DreamError {
                        return e.message
                    } catch {
                        return "Wrong PIN. Try again."
                    }
                })) { _ in pinFor = nil }
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: pinFor?.id)
    }

    private func reload() async {
        profiles = await Services.shared.profiles.profiles()
        current = await Services.shared.profiles.current()
        loaded = true
    }

    private func tap(_ p: Profile) {
        PageHaptics.tap()
        if editing { openEdit(p); return }
        Task { await choose(p) }
    }

    private func openEdit(_ p: Profile) {
        if p.hasPin && p.id != trustedId {
            // Editing a locked profile checks its PIN first (select only verifies here; the edit sheet saves).
            pinFor = PinAsk(profile: p, switches: false) { _ in edit = .existing(p) }
        } else {
            edit = .existing(p)
        }
    }

    private func choose(_ p: Profile) async {
        if p.hasPin && p.id != trustedId {
            pinFor = PinAsk(profile: p) { _ in finish(p) }
            return
        }
        // Leaving a Kids profile that asks for a PIN: a grown-up profile's PIN (ProfilePicker.leaveKids).
        if let cur = current, cur.id != p.id, cur.isKids, cur.exitPin == true,
           let guard_ = profiles.first(where: { $0.id != cur.id && !$0.isKids && $0.hasPin }) {
            pinFor = PinAsk(profile: guard_, switches: false) { _ in
                Task {
                    try? await Services.shared.profiles.select(p.id, pin: nil)
                    finish(p)
                }
            }
            return
        }
        do {
            try await Services.shared.profiles.select(p.id, pin: nil)
            finish(p)
        } catch {
            pinFor = PinAsk(profile: p) { _ in finish(p) }
        }
    }

    private func finish(_ p: Profile) {
        pinFor = nil
        NotificationCenter.default.post(name: .dreamProfileChanged, object: nil)
        onChosen(p)
    }
}

/// Add or edit a profile: name, picture, color, Kids with a maximum rating, PINs, Remove.
struct ProfileEditView: View {
    var profile: Profile?
    var isFirst: Bool
    var isCurrent: Bool
    var saved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var emoji = ProfileLook.emojis[0]
    @State private var color = ProfileLook.colors[0]
    @State private var kids = false
    @State private var level = KidsLevels.everyone
    @State private var exitPin = false
    @State private var hasPin = false
    @State private var newPin: String?
    @State private var removePin = false
    @State private var choosingPin = false
    @State private var confirmRemove = false
    @State private var error: String?
    @State private var ready = false

    var body: some View {
        PageScaffold(title: profile == nil ? "Add Profile" : "Edit Profile") {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 16) {
                    ProfileFace(emoji: emoji, color: color, size: 72)
                    PageTextField(placeholder: "Name", text: $name, capitalization: .words)
                }
                PageSection(title: "Picture").padding(.top, PageSpace.group)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 10) {
                    ForEach(ProfileLook.emojis, id: \.self) { e in
                        Button { PageHaptics.select(); emoji = e } label: {
                            Text(e).font(.system(size: 26)).frame(width: 48, height: 48)
                                .background(Circle().fill(Color.white.opacity(e == emoji ? 0.16 : 0.05)))
                                .overlay(Circle().strokeBorder(e == emoji ? Palette.pearl.opacity(0.7) : .clear, lineWidth: 1.5))
                        }
                        .buttonStyle(CardPressStyle(radius: 24))
                        .accessibilityAddTraits(e == emoji ? .isSelected : [])
                    }
                }
                .padding(.top, 12)
                PageSection(title: "Color").padding(.top, PageSpace.group)
                HStack(spacing: 12) {
                    ForEach(ProfileLook.colors, id: \.self) { c in
                        Button { PageHaptics.select(); color = c } label: {
                            Circle().fill(Color(hex: UInt32(c))).frame(width: 34, height: 34)
                                .overlay { if c == color { PageCheck(color: .white, weight: 0.11).frame(width: 34, height: 34) } }
                        }
                        .buttonStyle(CardPressStyle(radius: 17))
                    }
                }
                .padding(.top, 12)

                PageSection(title: "Kids").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageRow(title: "Kids Profile", caption: "Limits movies and shows to the rating you choose", toggle: kids) {
                        kids.toggle()
                        level = kids ? 1 : KidsLevels.everyone
                        if !kids { exitPin = false }
                    }
                    if kids {
                        PageLinkRow(title: "Maximum Rating", value: "\(KidsLevels.all[level].name) · \(KidsLevels.all[level].movie)") {
                            RatingChooser(level: $level)
                        }
                        PageRow(title: "Ask for a PIN to Leave", caption: "Uses a grown-up profile's PIN", toggle: exitPin) { exitPin.toggle() }
                    }
                }
                .padding(.top, 10)

                PageSection(title: "Profile").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageRow(title: "Lock With a PIN", caption: "Asks for a 4-digit PIN when this profile is chosen",
                            toggle: (hasPin && !removePin) || newPin != nil) {
                        if (hasPin && !removePin) || newPin != nil { newPin = nil; removePin = hasPin }
                        else { choosingPin = true }
                    }
                    if let profile, !isFirst, !isCurrent {
                        PageRow(title: "Remove Profile", caption: "Also removes its list and history", chevron: true) { confirmRemove = true }
                            .confirmationDialog("Remove \(profile.name.isEmpty ? "This Profile" : profile.name)?", isPresented: $confirmRemove, titleVisibility: .visible) {
                                Button("Remove", role: .destructive) { Task { await remove(profile) } }
                                Button("Cancel", role: .cancel) {}
                            } message: {
                                Text("Its list, history and look are removed from this iPhone.")
                            }
                    }
                }
                .padding(.top, 10)

                if let error {
                    Text(error).font(.system(size: 14)).foregroundStyle(Palette.redText).padding(.top, 16).padding(.horizontal, 6)
                }
                PagePrimaryButton(title: profile == nil ? "Create Profile" : "Done") { Task { await save() } }
                    .padding(.top, 30)
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
        .onAppear(perform: fill)
        .overlay {
            if choosingPin {
                PinPadView(title: name.isEmpty ? "This Profile" : name, mode: .choose(save: { pin in newPin = pin; removePin = false })) { _ in
                    choosingPin = false
                }
            }
        }
    }

    private func fill() {
        guard !ready else { return }
        ready = true
        guard let p = profile else { return }
        name = p.name
        emoji = p.avatar.isEmpty ? ProfileLook.emojis[0] : p.avatar
        color = p.color ?? ProfileLook.colors[0]
        kids = p.isKids
        level = KidsLevels.level(of: p)
        exitPin = p.exitPin ?? false
        hasPin = p.hasPin
    }

    private func save() async {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { error = "Type a name first."; PageHaptics.warning(); return }
        var p = profile ?? Profile(id: "p" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36), name: n)
        p.name = String(n.prefix(20))
        p.avatar = emoji
        p.color = color
        p.isKids = kids
        p.kidsLevel = level
        p.ratingLimit = level < KidsLevels.everyone ? KidsLevels.all[level].movie : nil
        p.exitPin = kids ? exitPin : false
        p.hasPin = newPin != nil || (hasPin && !removePin)
        do {
            // pin: a new PIN to set; "" removes the lock; nil keeps it as it is.
            try await Services.shared.profiles.save(p, pin: newPin ?? (removePin ? "" : nil))
            NotificationCenter.default.post(name: .dreamProfileChanged, object: nil)
            saved()
            dismiss()
        } catch let e as DreamError {
            error = e.message
        } catch {
            self.error = "Couldn't save this profile. Try again."
        }
    }

    private func remove(_ p: Profile) async {
        do {
            try await Services.shared.profiles.delete(p.id)
            saved()
            dismiss()
        } catch let e as DreamError {
            error = e.message
        } catch {
            self.error = "Couldn't remove this profile. Try again."
        }
    }
}

/// Maximum Rating: one friendly level for movies and shows (KidsProfiles.LEVELS).
struct RatingChooser: View {
    @Binding var level: Int
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        PageScaffold(title: "Maximum Rating", subtitle: "Movies and shows rated above this don't appear on this profile.") {
            VStack(spacing: PageSpace.row) {
                ForEach(KidsLevels.all.indices, id: \.self) { i in
                    let l = KidsLevels.all[i]
                    PageRow(title: l.name,
                            caption: i == KidsLevels.everyone ? "No limit" : "Movies up to \(l.movie) · shows up to \(l.tv.last ?? "")",
                            lead: .mark(i == level ? .pick : .off)) {
                        level = i
                        Task { try? await Task.sleep(for: .milliseconds(220)); dismiss() }
                    }
                }
            }
        }
    }
}
