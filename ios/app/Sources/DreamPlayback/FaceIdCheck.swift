import Foundation
import LocalAuthentication
import DreamCore

/// The phone's Face ID / Touch ID check for profile PINs (owner pick, 2026-10-28). Biometrics only
/// (`.deviceOwnerAuthenticationWithBiometrics`): the phone's passcode never stands in for a profile PIN; when the check
/// is cancelled or fails, the PIN pad is the fallback. Needs `NSFaceIDUsageDescription` in Info.plist.
public enum FaceIdCheck {
    public enum Kind: Sendable { case none, faceId, touchId, opticId }

    public enum Outcome: Sendable, Equatable {
        case passed
        /// Cancelled by the viewer or the system (the PIN pad just stays, no message).
        case cancelled
        /// The plain line for the PIN pad ("Face ID didn't work. Enter the PIN.").
        case failed(String)
    }

    /// What this phone has: none without the hardware or with nothing enrolled. Locked out (too many misses) still
    /// counts, so the option stays visible.
    public static var kind: Kind {
        let c = LAContext()
        var err: NSError?
        let ok = c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
        if !ok, let code = err?.code, code != LAError.biometryLockout.rawValue { return .none }
        switch c.biometryType {
        case .faceID: return .faceId
        case .touchID: return .touchId
        case .opticID: return .opticId
        default: return .none
        }
    }

    /// "Face ID", "Touch ID", "Optic ID".
    public static var label: String { label(kind) }
    public static func label(_ k: Kind) -> String {
        switch k {
        case .touchId: return "Touch ID"
        case .opticId: return "Optic ID"
        default: return "Face ID"
        }
    }

    /// The SF Symbol for the button on the PIN pad.
    public static func symbol(_ k: Kind) -> String {
        switch k {
        case .touchId: return "touchid"
        case .opticId: return "opticid"
        default: return "faceid"
        }
    }

    /// True when a check can run right now (enrolled, not locked out).
    public static var ready: Bool {
        var err: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
    }

    /// A fingerprint of the faces / fingers enrolled right now (changes when one is added or removed), so a face added
    /// later can't open a profile until its PIN is entered once. nil when the phone can't say.
    public static func enrolledNow() -> String? {
        let c = LAContext()
        var err: NSError?
        guard c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) else { return nil }
        return c.evaluatedPolicyDomainState?.base64EncodedString()
    }

    /// Runs the check. [reason] is the line Touch ID shows (Face ID shows Info.plist's sentence).
    public static func run(reason: String) async -> Outcome {
        let c = LAContext()
        c.localizedFallbackTitle = "Enter PIN"
        var err: NSError?
        let name = label
        guard c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) else {
            return outcome(err?.code, name)
        }
        do {
            let ok = try await c.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
            return ok ? .passed : .failed("\(name) didn't work. Enter the PIN.")
        } catch {
            return outcome((error as NSError).code, name)
        }
    }

    private static func outcome(_ code: Int?, _ name: String) -> Outcome {
        guard let code, let e = LAError.Code(rawValue: code) else { return .failed("\(name) didn't work. Enter the PIN.") }
        switch e {
        case .userCancel, .appCancel, .systemCancel, .userFallback, .notInteractive: return .cancelled
        case .biometryLockout: return .failed("\(name) is locked for now. Enter the PIN.")
        case .biometryNotEnrolled, .biometryNotAvailable, .passcodeNotSet:
            return .failed("\(name) isn't set up on this iPhone. Enter the PIN.")
        default: return .failed("\(name) didn't work. Enter the PIN.")
        }
    }
}
