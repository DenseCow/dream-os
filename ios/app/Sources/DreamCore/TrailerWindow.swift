import Foundation

/// A short head start for a title's trailer over other look-ahead traffic: while a trailer is being looked up and its
/// first picture loaded (a card pressed, a title page opening), art prefetch and Instant Play's stream head start
/// wait before their next download, at most `maxWait` seconds. A window closes when the trailer has its picture, when
/// something plays (the hero's stop), or by itself after `life` seconds, so it can never hold anything up for long.
/// Never used for a real Play: only look-ahead waits on it.
public enum TrailerWindow {
    public static let life: TimeInterval = 3
    public static let maxWait: TimeInterval = 2

    private static let lock = NSLock()
    nonisolated(unsafe) private static var open: [Int: Date] = [:]
    nonisolated(unsafe) private static var next = 0

    /// Opens a window; pass the returned id to `close`.
    @discardableResult
    public static func begin() -> Int {
        lock.withLock {
            next += 1
            open[next] = Date().addingTimeInterval(life)
            return next
        }
    }

    public static func close(_ id: Int) { lock.withLock { _ = open.removeValue(forKey: id) } }

    /// Something is about to play: every window closes.
    public static func closeAll() { lock.withLock { open.removeAll() } }

    public static var isOpen: Bool {
        lock.withLock {
            let now = Date()
            open = open.filter { $0.value > now }
            return !open.isEmpty
        }
    }

    /// Look-ahead work calls this before a download: returns at once when no window is open, else when they close
    /// (at most `maxWait`).
    public static func yield() async {
        guard isOpen else { return }
        let until = Date().addingTimeInterval(maxWait)
        while isOpen, Date() < until {
            try? await Task.sleep(for: .milliseconds(100))
        }
    }
}
