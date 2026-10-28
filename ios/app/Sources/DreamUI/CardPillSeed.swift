import DreamCore

/// Instant pills (docs/port/speed.md): the cards' pill memory (`CardPillMemory`, read synchronously by every
/// CardPill on its first frame), filled from the stored answers at launch and by the checks run ahead of the screen.
@MainActor
public enum CardPills {
    /// The stored answers at launch. An answer already given this session is newer and stays.
    public static func seed(_ answers: [String: Availability]) {
        let m = CardPillMemory.shared
        for (k, a) in answers where m.answers[k] == nil { m.answers[k] = .some(a) }
    }

    /// A real answer for `key` (nil: no pill). Failed checks never come here.
    public static func remember(_ key: String, _ a: Availability?) {
        CardPillMemory.shared.answers[key] = .some(a)
    }

    /// What a card of `key` shows from memory: nil = not known, .some(nil) = asked, no pill.
    public static func known(_ key: String) -> Availability?? { CardPillMemory.shared.answers[key] }
}
