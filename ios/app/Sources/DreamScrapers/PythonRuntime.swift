import CDreamPython
import DreamCore
import DreamPythonStdlib
import Foundation
import os

/// The embedded CPython 3.13 (Vendor/Python) that runs the scraper modules the user installed (the Fire TV app's
/// Chaquopy). Started once, off the main thread. Calls are strings in, a string out (`dreambridge.py`).
enum PythonRuntime {
    static let log = Logger(subsystem: "com.dreamos", category: "Scrapers")

    /// Held through the whole start; `stateLock` only guards the two flags, so readers never wait out the start.
    private static let lock = NSLock()
    private static let stateLock = NSLock()
    nonisolated(unsafe) private static var failure: String?
    nonisolated(unsafe) private static var started = false

    /// Python is up.
    static var isStarted: Bool { stateLock.withLock { started } }
    /// Python couldn't start here (logged once); scrapes then find nothing.
    static var startFailure: String? { stateLock.withLock { failure } }

    /// Our Python code (shims, runner, bridge) inside the app.
    static var codeDir: URL? { Bundle.module.url(forResource: "Python", withExtension: nil) }

    /// Blocking: starts the interpreter if needed. True when Python runs. Never on the main thread.
    @discardableResult
    static func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if isStarted { return true }
        if startFailure != nil { return false }
        guard let home = DreamPythonStdlib.home, let code = codeDir else {
            stateLock.withLock { failure = "missing files" }
            log.error("Scrapers: python files missing from the app")
            return false
        }
        let lib = home.appendingPathComponent("lib/python3.13").path
        let site = home.appendingPathComponent("site-packages").path
        let paths = [code.path, lib, lib + "/lib-dynload", site]
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let pycache = caches.appendingPathComponent("pycache", isDirectory: true)
        // The bytecode cache mirrors source paths, and the app's path changes with every install: an older install's
        // tree would stay forever.
        let marker = pycache.appendingPathComponent(".bundle")
        if (try? String(contentsOf: marker, encoding: .utf8)) != Bundle.main.bundlePath {
            try? FileManager.default.removeItem(at: pycache)
            try? FileManager.default.createDirectory(at: pycache, withIntermediateDirectories: true)
            try? Bundle.main.bundlePath.write(to: marker, atomically: true, encoding: .utf8)
        }
        // Python's ssl has no system certificate store on iOS: the bundled certifi list.
        setenv("SSL_CERT_FILE", home.appendingPathComponent("site-packages/certifi/cacert.pem").path, 1)
        setenv("PYTHONIOENCODING", "utf-8", 1)
        dream_py_set_callbacks(sinkCallback, logCallback)
        let t0 = Date()
        var err: UnsafeMutablePointer<CChar>?
        let cPaths = paths.map { strdup($0) }
        defer { cPaths.forEach { free($0) } }
        let exe = Bundle.main.executablePath ?? ""
        let rc = cPaths.map { UnsafePointer($0) }.withUnsafeBufferPointer { buf in
            dream_py_start(home.path, buf.baseAddress, Int32(paths.count), pycache.path, exe, &err)
        }
        if rc != 0 {
            let why = err.map { String(cString: $0) } ?? "code \(rc)"
            free(err)
            stateLock.withLock { failure = why }
            log.error("Scrapers: python couldn't start (\(why, privacy: .public))")
            return false
        }
        stateLock.withLock { started = true }
        log.info("Scrapers: python started in \(Int(Date().timeIntervalSince(t0) * 1000)) ms")
        return true
    }

    struct CallError: Error, CustomStringConvertible { let description: String }

    /// Blocking: `module.function(*args)`; the result's text. Throws the Python exception's text.
    /// A call that waits long for the interpreter lock or runs long is logged (Bug 1 evidence).
    static func call(_ function: String, _ args: [String] = [], module: String = "dreambridge") throws -> String {
        guard start() else { throw CallError(description: "python unavailable") }
        if Thread.isMainThread { log.error("Scrapers: python \(function, privacy: .public) called on the main thread") }
        let cArgs = args.map { strdup($0) }
        defer { cArgs.forEach { free($0) } }
        var failed: Int32 = 1
        let t0 = Date()
        let out = cArgs.map { UnsafePointer($0) }.withUnsafeBufferPointer { buf in
            dream_py_call(module, function, buf.baseAddress, Int32(args.count), &failed)
        }
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        if ms > 3000 && function != "scrape" { log.notice("Scrapers: python \(function, privacy: .public) took \(ms) ms") }
        let text = out.map { String(cString: $0) } ?? ""
        free(out)
        if failed != 0 { throw CallError(description: text) }
        return text
    }

    /// Runs blocking Python work on its own thread (never the cooperative pool: a scrape blocks for seconds).
    static func background<T: Sendable>(qos: QualityOfService = .userInitiated, _ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { (c: CheckedContinuation<T, Never>) in
            let t = Thread {
                ScraperEngine.trace("python thread started")
                let r = work()
                ScraperEngine.trace("python thread done")
                c.resume(returning: r)
            }
            t.qualityOfService = qos
            t.stackSize = 4 << 20
            t.name = "scrapers-python"
            t.start()
        }
    }
}

// C callbacks: Python worker threads, interpreter lock held. Hand off and return quickly.

private func sinkCallback(job: Int32, kind: Int32, provider: UnsafePointer<CChar>?, payload: UnsafePointer<CChar>?,
                          count: Int32, ms: Int32, error: UnsafePointer<CChar>?) {
    let p = provider.map { String(cString: $0) } ?? ""
    if kind == 0 {
        ScraperEngine.deliver(job: Int(job), provider: p, json: payload.map { String(cString: $0) } ?? "[]")
    } else {
        ScraperEngine.finished(job: Int(job), provider: p, count: Int(count), ms: Int(ms), error: error.map { String(cString: $0) })
    }
}

private func logCallback(level: Int32, message: UnsafePointer<CChar>?) {
    let m = message.map { String(cString: $0) } ?? ""
    if level >= 3 { PythonRuntime.log.error("Scrapers: \(m, privacy: .public)") }
    else if level >= 2 { PythonRuntime.log.notice("Scrapers: \(m, privacy: .public)") }
    else { PythonRuntime.log.debug("Scrapers: \(m, privacy: .public)") }
}
