import Foundation
import Darwin

/// Speed pass (docs/port/speed.md): the clocks behind the timing lines in the log ("Speed: ..."), and the catalog's
/// look-ahead hook. Read the lines with `idevicesyslog | grep --line-buffered 'Speed:'`.
public enum Speed {
    /// When the process started (the kernel's own start time, so it includes the time before `main`).
    public static let launch: Date = {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        if sysctl(&mib, 4, &info, &size, nil, 0) == 0 {
            let t = info.kp_proc.p_un.__p_starttime
            return Date(timeIntervalSince1970: TimeInterval(t.tv_sec) + TimeInterval(t.tv_usec) / 1_000_000)
        }
        return Date()
    }()

    /// Milliseconds since `d`.
    public static func ms(_ d: Date) -> Int { Int(Date().timeIntervalSince(d) * 1000) }

    /// Milliseconds since the process started.
    public static var sinceLaunch: Int { ms(launch) }

    /// One timing line in the log: "Speed: <what> <n> ms".
    public static func note(_ what: String, _ start: Date) {
        let n = ms(start)
        Log.app.notice("Speed: \(what, privacy: .public) \(n) ms")
    }

    /// "Speed: <what> <n> ms after launch" (first frame, Home's first row).
    public static func noteLaunch(_ what: String) {
        let n = sinceLaunch
        Log.app.notice("Speed: \(what, privacy: .public) \(n) ms after launch")
    }
}

public extension CatalogService {
    /// Runs a row's fetches without claiming its titles, so a page can fetch every row at once and still claim them
    /// top-down (the fill that follows reads the answers from the cache). Default: nothing.
    func warm(row: CatalogRow, page: CatalogPage) async {}
}
