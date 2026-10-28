import Foundation
import Network
import os
import DreamCore

/// How much of the stream Dream Player keeps in memory, sized to this iPhone (like the art memory in
/// DreamUI/Images.swift): a big read-ahead so network hiccups never stall, and a back buffer so jumping back
/// (or forward inside what's already loaded) is instant. On a metered or Low Data connection the read-ahead
/// stays short, so stopping early never wastes the viewer's data.
struct BufferPlan: Sendable, Equatable {
    /// Bytes mpv may read ahead of the playing spot.
    var aheadBytes: Int
    /// Bytes of already played stream kept for instant jumps back.
    var behindBytes: Int
    /// Seconds mpv reads ahead at most (the byte limit usually comes first on big files).
    var aheadSeconds: Int
    /// Wi-Fi / wired and not Low Data Mode.
    var unmetered: Bool
    /// For the log.
    var physicalMB: Int
    var freeMB: Int

    static let mb = 1 << 20

    /// The plan for a load right now.
    static func current() -> BufferPlan {
        let phys = Int(ProcessInfo.processInfo.physicalMemory)
        // What this app may still allocate before iOS ends it (0 where unknown).
        let free = Int(os_proc_available_memory())
        let unmetered = NetPath.shared.unmetered
        // An eighth of the phone's memory (a 4 GB iPhone ~512 MB, 6 GB ~768 MB, 8 GB and up 1 GB), never more
        // than half of what is left after a reserve for the picture, the scrub previews and the rest of Dream.
        var total = min(phys / 8, 1024 * mb)
        if free > 0 { total = min(total, max(0, free - 640 * mb) / 2) }
        if !unmetered { total = min(total, 256 * mb) }
        total = max(total, 128 * mb)
        let behind = total / 4
        return BufferPlan(aheadBytes: total - behind, behindBytes: behind,
                          aheadSeconds: unmetered ? 1800 : 240, unmetered: unmetered,
                          physicalMB: phys / mb, freeMB: free / mb)
    }

    /// mpv option values ("576MiB").
    var aheadOption: String { "\(aheadBytes / Self.mb)MiB" }
    var behindOption: String { "\(behindBytes / Self.mb)MiB" }

    var logLine: String {
        "Buffer: \(aheadBytes / Self.mb) MB ahead (up to \(aheadSeconds / 60) min), \(behindBytes / Self.mb) MB behind; "
            + "\(String(format: "%.1f", Double(physicalMB) / 1024)) GB iPhone, \(freeMB) MB free, \(unmetered ? "Wi-Fi" : "metered or Low Data")"
    }
}

/// The phone's current network path, kept up to date for the player (metered = cellular, hotspot, Low Data).
final class NetPath: @unchecked Sendable {
    static let shared = NetPath()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var _unmetered = true

    private init() {
        monitor.pathUpdateHandler = { [weak self] p in
            let free = p.status == .satisfied && !p.isExpensive && !p.isConstrained
            self?.lock.withLock { self?._unmetered = free }
        }
        monitor.start(queue: DispatchQueue(label: "dreamos.player.path", qos: .utility))
    }

    /// True until the first answer arrives (the player opens long after launch, so it has one by then).
    var unmetered: Bool { lock.withLock { _unmetered } }

    /// Starts watching (call early, e.g. when a player opens, so the first load already knows the path).
    func start() { _ = unmetered }
}
