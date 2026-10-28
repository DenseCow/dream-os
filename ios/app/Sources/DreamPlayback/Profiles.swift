import Foundation
import CryptoKit
import DreamCore

/// The profile palette and avatars (Profiles.kt). The avatar is one of `avatars`; `colors` are hex strings the pages
/// draw the avatar square in.
public enum ProfileStyle {
    public static let avatars = ["😀", "😎", "🦄", "🐱", "🐶", "🦊", "👾", "🌟", "🚀", "🎮", "🌸", "🐼"]
    public static let colors = ["#7C5CFF", "#38BDF8", "#F472B6", "#4ADE80", "#FBBF24", "#F87171", "#A78BFA", "#2DD4BF"]
}

/// Viewer profiles (Profiles.kt + KidsProfiles.kt + PinPad's PIN rules). Each profile has its own history, My List and
/// settings (DreamStore per-profile documents under its id). Any profile can be locked with a 4-digit PIN, kept as a
/// salted SHA-256 hash in the Keychain. A 4-digit PIN keeps profiles apart; it isn't a parental lock.
public final class Profiles: @unchecked Sendable {
    public static let shared = Profiles()
    public static let first = "p1"
    /// Wrong PINs in a row before every further try waits a growing while.
    static let freeTries = 5
    private static let kidsName = "(?i)\\b(kids?|kiddos?|children|child|junior|juniors)\\b"
    private let lock = NSLock()

    private init() {
        DreamStore.shared.profileId = currentId()
    }

    // MARK: List

    public func all() -> [Profile] {
        let list = lock.withLock { DreamStore.shared.get([Profile].self, "profiles.list") } ?? []
        let base = list.isEmpty ? [Profile(id: Self.first, name: "Me", avatar: ProfileStyle.avatars[0], color: 0)] : list
        return base.map(normalized)
    }

    private func store(_ list: [Profile]) { lock.withLock { DreamStore.shared.set(list, "profiles.list") } }

    /// The flags pages read: hasPin and faceId from the Keychain, ratingLimit from the kids level.
    private func normalized(_ p0: Profile) -> Profile {
        var p = p0
        p.hasPin = hasPin(p.id)
        p.faceId = p.hasPin && faceIdOn(p.id) ? true : nil
        let level = KidsLevels.level(of: p)
        p.ratingLimit = level < KidsLevels.everyone ? KidsLevels.all[level].movie : nil
        return p
    }

    private func currentId() -> String { DreamStore.shared.get(String.self, "profiles.current") ?? Self.first }

    public func current() -> Profile {
        let list = all()
        return list.first { $0.id == currentId() } ?? list[0]
    }

    /// Switches to [id] (no PIN check: `select` in the service asks for it).
    public func setCurrent(_ id: String) {
        DreamStore.shared.set(id, "profiles.current")
        DreamStore.shared.profileId = id
        Task { @MainActor in NotificationCenter.default.post(name: .dreamProfileChanged, object: nil) }
    }

    /// A new profile; a name like "Kids" or "Junior" makes it a kids profile.
    public func add(_ name: String) -> Profile {
        var list = all()
        let n = list.count
        let clean = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Profile" : name.trimmingCharacters(in: .whitespaces)
        let kids = clean.range(of: Self.kidsName, options: .regularExpression) != nil
        let p = Profile(id: "p" + String(Int64(Date().timeIntervalSince1970 * 1000), radix: 36), name: clean,
                        avatar: ProfileStyle.avatars[n % ProfileStyle.avatars.count], isKids: kids, color: n % ProfileStyle.colors.count)
        list.append(p)
        store(list)
        return normalized(p)
    }

    public func update(_ p: Profile) {
        var list = all()
        if let i = list.firstIndex(where: { $0.id == p.id }) { list[i] = p } else { list.append(p) }
        store(list)
    }

    /// Deletes profile [id] and everything it kept. The first profile stays.
    public func remove(_ id: String) {
        if id == Self.first { return }
        store(all().filter { $0.id != id })
        if currentId() == id { setCurrent(Self.first) }
        setPin(id, nil)
        DreamStore.shared.removeProfileData(id)
    }

    // MARK: First-run setup

    /// False until the setup wizard has run once; until then the first profile has no real name yet.
    public var isSetUp: Bool { DreamStore.shared.get(Bool.self, "profiles.setup_done") ?? false }
    public func markSetUp() { DreamStore.shared.set(true, "profiles.setup_done") }

    // MARK: PIN

    public func hasPin(_ id: String) -> Bool { SecureStore.get("pin." + id) != nil }

    /// Saves [pin] for profile [id] as "salt:sha256(salt+pin)", or removes the lock (and Face ID) when nil or "".
    public func setPin(_ id: String, _ pin: String?) {
        guard let pin, !pin.isEmpty else { SecureStore.set("pin." + id, nil); setFaceId(id, false); return }
        var salt = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, salt.count, &salt)
        let s = salt.map { String(format: "%02x", $0) }.joined()
        SecureStore.set("pin." + id, s + ":" + Self.hash(s, pin))
    }

    private static func hash(_ salt: String, _ pin: String) -> String {
        SHA256.hash(data: Data((salt + pin).utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// True when [pin] unlocks profile [id] (or it has no PIN). After 5 wrong PINs in a row every further try waits a
    /// growing while (`pinWaitSeconds`); a try during the wait always fails.
    public func checkPin(_ id: String, _ pin: String) -> Bool {
        guard let stored = SecureStore.get("pin." + id) else { return true }
        if pinWaitSeconds(id) > 0 { return false }
        let parts = stored.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return false }
        let a = Array(Self.hash(parts[0], pin).utf8), b = Array(parts[1].utf8)
        let ok = a.count == b.count && zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0 // constant time
        if ok {
            clearPinFails(id)
            rearmFaceId(id)
        } else {
            let fails = (DreamStore.shared.get(Int.self, "pin.fails." + id) ?? 0) + 1
            let wait: Int64 = fails < Self.freeTries ? 0 : min(Int64(30_000) << Int64(min(fails - Self.freeTries, 5)), 15 * 60_000)
            DreamStore.shared.set(fails, "pin.fails." + id)
            DreamStore.shared.set(wait > 0 ? wallMs() + wait : 0, "pin.wait." + id)
        }
        return ok
    }

    /// Seconds before profile [id]'s PIN can be tried again (0 = now). Never longer than the longest wait.
    public func pinWaitSeconds(_ id: String) -> Int {
        let left = (DreamStore.shared.get(Int64.self, "pin.wait." + id) ?? 0) - wallMs()
        return left <= 0 ? 0 : Int((min(left, 15 * 60_000) + 999) / 1000)
    }

    /// The PIN pad's line after a wrong PIN.
    public func pinError(_ id: String) -> String {
        let w = pinWaitSeconds(id)
        return w <= 0 ? "Wrong PIN. Try again." : w < 60 ? "Too many tries. Try again in \(w) seconds." : "Too many tries. Try again in \((w + 59) / 60) minutes."
    }

    /// Whose PIN switching to (or editing) [id] needs: [id]'s own when it has one; leaving a kids profile whose "PIN to
    /// leave" is on, the main profile's PIN (else the first grown-up profile's that has one). nil = no PIN needed.
    public func pinNeeded(toOpen id: String) -> Profile? {
        let list = all()
        guard let to = list.first(where: { $0.id == id }) else { return nil }
        if to.hasPin { return to }
        let cur = current()
        if to.id == cur.id || !cur.isKids || cur.exitPin != true { return nil }
        if let main = list.first, main.id != cur.id, main.hasPin { return main }
        return list.first { $0.id != cur.id && !$0.isKids && $0.hasPin }
    }

    private func clearPinFails(_ id: String) {
        DreamStore.shared.set(nil as Int?, "pin.fails." + id); DreamStore.shared.set(nil as Int64?, "pin.wait." + id)
    }

    // MARK: Face ID (owner pick, 2026-10-28)

    /// Unlock With Face ID lives in the Keychain next to the PIN: "faceid.<id>" holds the phone's enrolled faces /
    /// fingers when it was turned on (`FaceIdCheck.enrolledNow`), or "on" when the phone couldn't say. The PIN itself
    /// is never stored any other way.
    private static func faceKey(_ id: String) -> String { "faceid." + id }

    public func faceIdOn(_ id: String) -> Bool { SecureStore.get(Self.faceKey(id)) != nil }

    /// Turns Unlock With Face ID on (remembering today's enrolled faces) or off for profile [id].
    public func setFaceId(_ id: String, _ on: Bool) {
        SecureStore.set(Self.faceKey(id), on ? (FaceIdCheck.enrolledNow() ?? "on") : nil)
    }

    /// True when Face ID may open profile [id] now: it has a PIN, the option is on, the phone can check, and no face or
    /// finger was added or removed since it was turned on (then the PIN is needed once, which turns it back on).
    public func faceIdReady(_ id: String) -> Bool {
        guard hasPin(id), let saved = SecureStore.get(Self.faceKey(id)), FaceIdCheck.ready else { return false }
        guard saved != "on", let now = FaceIdCheck.enrolledNow() else { return true }
        if saved != now { Log.core.info("Profiles: Face ID faces changed since it was turned on; PIN needed once"); return false }
        return true
    }

    /// After the right PIN: Face ID that paused because the phone's faces changed works again with today's faces.
    private func rearmFaceId(_ id: String) {
        guard let saved = SecureStore.get(Self.faceKey(id)), let now = FaceIdCheck.enrolledNow(), saved != now else { return }
        SecureStore.set(Self.faceKey(id), now)
    }

    /// Face ID passed for [id]: the wrong-PIN count starts over.
    public func faceIdPassed(_ id: String) { clearPinFails(id) }
}

/// `ProfileService` for the app.
struct DreamProfileService: ProfileService {
    private var p: Profiles { .shared }
    func profiles() async -> [Profile] { p.all() }
    func current() async -> Profile? { p.current() }

    func select(_ id: String, pin: String?) async throws {
        if let guardian = p.pinNeeded(toOpen: id) {
            guard let pin, p.checkPin(guardian.id, pin) else { throw DreamError(title: "Enter PIN", p.pinError(guardian.id)) }
        }
        p.setCurrent(id)
    }

    func checkPin(_ id: String, pin: String) async throws {
        guard p.checkPin(id, pin) else { throw DreamError(title: "Enter PIN", p.pinError(id)) }
    }

    /// Whose Face ID opening [id] uses: switching, the profile `select` asks the PIN of; checking, [id] itself.
    private func faceGuardian(_ id: String, switches: Bool) -> Profile? {
        switches ? p.pinNeeded(toOpen: id) : p.all().first { $0.id == id && $0.hasPin }
    }

    func faceIdReady(_ id: String, switches: Bool) async -> Bool {
        guard let g = faceGuardian(id, switches: switches) else { return false }
        return p.faceIdReady(g.id)
    }

    func unlockWithFaceId(_ id: String, switches: Bool) async throws {
        guard let g = faceGuardian(id, switches: switches) else {
            if switches { p.setCurrent(id) }
            return
        }
        guard p.faceIdReady(g.id) else { throw DreamError(title: "Enter PIN", "Enter the PIN.") }
        switch await FaceIdCheck.run(reason: "Confirm it's you to open this profile.") {
        case .passed:
            Log.core.info("Profiles: opened with \(FaceIdCheck.label, privacy: .public)")
            p.faceIdPassed(g.id)
            if switches { p.setCurrent(id) }
        case .cancelled:
            throw CancellationError()
        case .failed(let line):
            throw DreamError(title: "Enter PIN", line)
        }
    }

    func save(_ profile: Profile, pin: String?) async throws {
        p.update(profile)
        // pin: a new PIN to set; "" removes the lock; nil keeps it (or removes it when the profile no longer has one).
        if let pin, !pin.isEmpty { p.setPin(profile.id, pin) }
        else if pin == "" || (!profile.hasPin && p.hasPin(profile.id)) { p.setPin(profile.id, nil) }
        let faceOn = profile.faceId == true && p.hasPin(profile.id)
        if faceOn != p.faceIdOn(profile.id) { p.setFaceId(profile.id, faceOn) }
    }

    func delete(_ id: String) async throws {
        if id == Profiles.first { throw DreamError("The first profile stays. Choose another profile to delete.") }
        p.remove(id)
    }
}
