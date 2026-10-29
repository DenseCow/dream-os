import DreamCore

/// Instant pills (docs/port/speed.md): the cards' pill memory (`CardPillMemory`, read synchronously by every
/// CardPill on its first frame and observed per title), filled from the stored answers at launch and by the checks run
/// ahead of the screen.
@MainActor
public enum CardPills {
    /// The stored answers at launch. An answer already given this session is newer and stays.
    public static func seed(_ answers: [String: Availability]) {
        let m = CardPillMemory.shared
        for (k, a) in answers where m.answer(k) == nil { m.set(k, a, checked: false) }
    }

    /// A real answer for `key` (nil: no pill). Failed checks never come here. Every card and title page showing that
    /// title redraws with it at once.
    public static func remember(_ key: String, _ a: Availability?) {
        CardPillMemory.shared.set(key, a, checked: true)
    }

    /// What a card of `key` shows from memory: nil = not known, .some(nil) = asked, no pill. Not observed.
    public static func known(_ key: String) -> Availability?? { CardPillMemory.shared.answer(key) }

    /// The same, observed: a view reading it in its body redraws when an answer for `key` lands.
    public static func observed(_ key: String) -> Availability?? { CardPillMemory.shared.slot(key).answer }
}
