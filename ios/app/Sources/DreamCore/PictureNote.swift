import Foundation

/// A quiet note about how a stream's picture was chosen, for Dream Player's Info › Now Playing ("Cellular Data
/// Saver"). Dream Core (DreamPlayback) sets it when it hands a link over; the player reads it by the link's torrent
/// hash. Decided at Play time: a later network change never rewrites it. (Cellular Data Saver, 2026-10-28.)
public enum PictureNote {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var notes: [String: String] = [:]

    /// [note] for the link with torrent [hash]; nil clears it.
    public static func set(_ note: String?, hash: String) {
        let k = hash.lowercased()
        guard !k.isEmpty else { return }
        lock.withLock {
            notes[k] = note
            if notes.count > 64 { notes = note.map { [k: $0] } ?? [:] }
        }
    }

    /// The note for the link with torrent [hash], or nil.
    public static func note(hash: String?) -> String? {
        guard let k = hash?.lowercased(), !k.isEmpty else { return nil }
        return lock.withLock { notes[k] }
    }
}
