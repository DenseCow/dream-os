import Foundation
import Network
import CryptoKit

/// Instant Play's head start on the stream itself (the Fire Stick's StreamCache, sized for an iPhone): once a link
/// is ready ahead of Play (a title page's look-ahead, Continue Watching, the next episode during the credits), the
/// parts of the file the player reads first are fetched to disk: the head (container header and the first seconds),
/// the tail (an MKV keeps its seek index there; ffmpeg reads it before the first frame) and, for a resume, a window
/// around where the position lands. Dream Player then reads the stream through a loopback HTTP server (`localURL`):
/// bytes on disk are served from disk, everything else is fetched from the same link with the same headers, so the
/// first frame no longer waits for a TLS connect, the header and the index over the network.
///
/// Sizes: 96 MB on disk at most, two titles at most (the newest wins), entries dropped after two hours (debrid links
/// don't live longer). One prefetch at a time, lowest priority; a player opening another title stops it. URLs are
/// never logged in full.
public final class StreamCache: @unchecked Sendable {
    public static let shared = StreamCache()

    static let headBytes: Int64 = 8 << 20
    static let tailBytes: Int64 = 2 << 20
    static let resumeBefore: Int64 = 3 << 20
    static let resumeAfter: Int64 = 8 << 20
    static let maxTitles = 2
    static let budgetBytes: Int64 = 96 << 20
    static let entryTTL: Int64 = 2 * 3_600_000
    /// One range request at most this big (a piece); the prefetch is several pieces in a row.
    static let pieceBytes: Int64 = 4 << 20

    struct Segment: Codable, Sendable { var offset: Int64; var length: Int64; var file: String }
    struct Entry: Codable, Sendable {
        var id: String
        var url: String
        var headers: [String: String]
        var length: Int64
        var contentType: String?
        var segments: [Segment]
        var at: Int64
        var done: Bool
        var resumeAt: Int64
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]
    private var loaded = false
    private var queue: [Job] = []
    private var running: Job?
    private var worker: Task<Void, Never>?
    private let root: URL
    private let proxy = StreamProxy()
    /// Turned off by `StreamCache.enabled = false` (a kill switch while the proxy is new).
    public static var enabled: Bool {
        get { DreamStore.shared.get(Bool.self, "stream.headStart") ?? true }
        set { DreamStore.shared.set(newValue, "stream.headStart") }
    }

    struct Job: Sendable {
        let id: String
        let url: URL
        let headers: [String: String]
        let startMs: Int64
        let durationMs: Int64
        let sizeBytes: Int64
        let label: String
    }

    let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpShouldSetCookies = false
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 120
        c.httpMaximumConnectionsPerHost = 4
        c.networkServiceType = .background
        return URLSession(configuration: c)
    }()

    private init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        root = base.appendingPathComponent("DreamOS-stream", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    // MARK: Public API

    /// "https://host/file.mkv|User-Agent=Foo&Referer=..." -> the URL and its headers (the Fire TV's StreamUrls form).
    public static func split(_ raw: String) -> (URL?, [String: String]) {
        guard let bar = raw.firstIndex(of: "|") else { return (URL(string: raw), [:]) }
        var out: [String: String] = [:]
        for pair in raw[raw.index(after: bar)...].split(separator: "&") {
            guard let eq = pair.firstIndex(of: "="), eq != pair.startIndex else { continue }
            let k = String(pair[..<eq]).removingPercentEncoding?.trimmingCharacters(in: .whitespaces) ?? ""
            let v = String(pair[pair.index(after: eq)...]).removingPercentEncoding?.trimmingCharacters(in: .whitespaces) ?? ""
            if k.isEmpty || k.lowercased() == "verifypeer" || k.lowercased() == "seekable" || k.hasPrefix("!") { continue }
            out[k] = v
        }
        return (URL(string: String(raw[..<bar])), out)
    }

    static func id(_ url: URL) -> String {
        let d = SHA256.hash(data: Data(url.absoluteString.utf8))
        return d.prefix(10).map { String(format: "%02x", $0) }.joined()
    }

    /// Fetches the start of [link] (the Fire TV's `url|headers` form allowed) to disk in the background; [startMs]
    /// and [durationMs] place a resume window (0 = from the start). Returns at once. A link already on disk, or on
    /// its way, isn't fetched again. [label] names the title in the log.
    public func warm(_ link: StreamLink, startMs: Int64 = 0, durationMs: Int64 = 0, label: String) {
        guard Self.enabled else { return }
        let (u, h) = Self.split(link.url.absoluteString)
        guard let u, u.scheme?.hasPrefix("http") == true else { return }
        proxy.start()
        let id = Self.id(u)
        let job = Job(id: id, url: u, headers: h, startMs: startMs, durationMs: durationMs, sizeBytes: link.sizeBytes, label: label)
        let go: Bool = lock.withLock {
            loadIndex()
            if let e = entries[id], e.done, e.resumeAt == job.startMs || job.startMs == 0 { return false }
            if running?.id == id { return false }
            queue.removeAll { $0.id == id }
            queue.insert(job, at: 0)
            if queue.count > Self.maxTitles { queue.removeLast(queue.count - Self.maxTitles) }
            return true
        }
        guard go else { return }
        startWorker()
    }

    /// Dream Player opens [url]: the loopback URL that serves it from the cache when its start is on disk, else nil
    /// (the player reads the link directly, as before). Any prefetch for another title stops.
    public func localURL(for url: URL, fileName: String? = nil) -> URL? {
        guard Self.enabled else { return nil }
        let id = Self.id(url)
        let entry: Entry? = lock.withLock {
            loadIndex()
            // Another title's prefetch never competes with the player for the network.
            queue.removeAll { $0.id != id }
            if let r = running, r.id != id { running = nil; cancelRunning = true }
            guard let e = entries[id], e.length > 0, e.segments.contains(where: { $0.offset == 0 }) else { return nil }
            return e
        }
        guard let entry else { return nil }
        return proxy.register(entry, fileName: fileName ?? url.lastPathComponent)
    }

    /// The link failed to play: whatever was cached for it goes.
    public func forget(_ url: URL) {
        let (u, _) = Self.split(url.absoluteString)
        guard let u else { return }
        let id = Self.id(u)
        lock.withLock {
            loadIndex()
            queue.removeAll { $0.id == id }
            if running?.id == id { running = nil; cancelRunning = true }
            removeEntry(id)
        }
    }

    /// Whether [url]'s start is on disk (for the log and the speed table).
    public func ready(_ url: URL) -> Bool {
        let (u, _) = Self.split(url.absoluteString)
        guard let u else { return false }
        return lock.withLock { loadIndex(); return entries[Self.id(u)]?.done == true }
    }

    // MARK: Index

    private var indexURL: URL { root.appendingPathComponent("index.json") }

    private func loadIndex() {
        if loaded { return }
        loaded = true
        if let d = try? Data(contentsOf: indexURL), let list = try? JSONDecoder().decode([Entry].self, from: d) {
            let now = wallNow()
            for e in list where now - e.at < Self.entryTTL { entries[e.id] = e }
        }
        // Folders without an entry (a crash mid-write) go.
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        for d in dirs where d.lastPathComponent != "index.json" && entries[d.lastPathComponent] == nil {
            try? FileManager.default.removeItem(at: d)
        }
        saveIndex()
    }

    private func saveIndex() {
        if let d = try? JSONEncoder().encode(Array(entries.values)) { try? d.write(to: indexURL, options: .atomic) }
    }

    private func removeEntry(_ id: String) {
        entries[id] = nil
        try? FileManager.default.removeItem(at: root.appendingPathComponent(id, isDirectory: true))
        saveIndex()
    }

    /// Room for [id]: at most `maxTitles` entries and `budgetBytes` on disk, the oldest dropped first (never [id]).
    private func makeRoom(for id: String) {
        var others = entries.values.filter { $0.id != id }.sorted { $0.at < $1.at }
        func bytes() -> Int64 { entries.values.reduce(0) { $0 + $1.segments.reduce(0) { $0 + $1.length } } }
        while !others.isEmpty && (others.count + 1 > Self.maxTitles || bytes() > Self.budgetBytes) {
            removeEntry(others.removeFirst().id)
        }
    }

    private func wallNow() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: Fetching

    private var cancelRunning = false

    private func startWorker() {
        lock.withLock {
            if worker != nil { return }
            worker = Task.detached(priority: .utility) { [weak self] in
                await self?.drain()
            }
        }
    }

    private func drain() async {
        while true {
            let job: Job? = lock.withLock {
                if queue.isEmpty { worker = nil; running = nil; return nil }
                let j = queue.removeFirst()
                running = j
                cancelRunning = false
                return j
            }
            guard let job else { return }
            await fetch(job)
            lock.withLock { if running?.id == job.id { running = nil } }
        }
    }

    private func cancelled(_ job: Job) -> Bool { lock.withLock { cancelRunning || running?.id != job.id } }

    private func fetch(_ job: Job) async {
        let t0 = Date()
        var entry: Entry = lock.withLock {
            loadIndex()
            makeRoom(for: job.id)
            if var e = entries[job.id] { e.at = wallNow(); e.headers = job.headers; entries[job.id] = e; saveIndex(); return e }
            let e = Entry(id: job.id, url: job.url.absoluteString, headers: job.headers, length: job.sizeBytes > 0 ? job.sizeBytes : -1,
                          contentType: nil, segments: [], at: wallNow(), done: false, resumeAt: 0)
            entries[job.id] = e
            try? FileManager.default.createDirectory(at: root.appendingPathComponent(job.id, isDirectory: true), withIntermediateDirectories: true)
            saveIndex()
            return e
        }
        var pieces = 0
        // The head first: it also tells the file's size (Content-Range) and type. A big file (a 4K remux) gets more:
        // the same seconds of video at its bitrate.
        let head = job.sizeBytes > 12 << 30 ? 2 * Self.headBytes : Self.headBytes
        if !covered(entry, 0, head) {
            guard let got = await pull(job, &entry, offset: 0, length: head), got.0 > 0 else {
                Log.core.notice("Stream cache: \(job.label, privacy: .public): the head couldn't be fetched; nothing cached")
                lock.withLock { removeEntry(job.id) }
                return
            }
            pieces += 1
            let len = got.0
            if entry.length <= 0 || entry.length != len {
                if entry.length > 0 { Log.core.notice("Stream cache: \(job.label, privacy: .public): size \(entry.length >> 20) MB said, \(len >> 20) MB served") }
                entry.length = len
            }
            if let type = got.1 { entry.contentType = type }
            save(entry)
        }
        let len = entry.length
        guard len > 0 else { lock.withLock { removeEntry(job.id) }; return }
        if cancelled(job) { return }
        // The tail: an MKV's Cues (ffmpeg's matroska demuxer reads them through the SeekHead before the first cluster).
        if len > Self.headBytes + Self.tailBytes, !covered(entry, len - Self.tailBytes, Self.tailBytes) {
            if await pull(job, &entry, offset: len - Self.tailBytes, length: Self.tailBytes) != nil { pieces += 1 }
            save(entry)
        }
        if cancelled(job) { return }
        // Resuming: a window around where the position should land (proportional; a miss just reads the network).
        var window: Int64 = 0
        if job.startMs > 0, job.durationMs > 0 {
            let at = Int64(Double(len) * Double(job.startMs) / Double(job.durationMs))
            let from = max(Self.headBytes, at - Self.resumeBefore)
            let to = min(len - Self.tailBytes, at + Self.resumeAfter)
            if to > from, !covered(entry, from, to - from) {
                var p = from
                while p < to && !cancelled(job) {
                    let n = min(Self.pieceBytes, to - p)
                    if await pull(job, &entry, offset: p, length: n) == nil { break }
                    pieces += 1
                    p += n
                }
                window = to - from
                save(entry)
            }
            entry.resumeAt = job.startMs
        }
        if cancelled(job) { return }
        entry.done = true
        entry.at = wallNow()
        save(entry)
        let mb = Double(entry.segments.reduce(0) { $0 + $1.length }) / 1_048_576
        Log.core.notice("Stream cache: \(job.label, privacy: .public): \(String(format: "%.1f", mb), privacy: .public) MB ahead in \(String(format: "%.1f", Date().timeIntervalSince(t0)), privacy: .public) s of \(len >> 20) MB\(window > 0 ? " (resume window \(window >> 20) MB at \(job.startMs / 60_000) min)" : "", privacy: .public)\(pieces == 0 ? " (already there)" : "", privacy: .public)")
    }

    /// Writes [e] back unless it was dropped meanwhile (`forget`).
    private func save(_ e: Entry) { lock.withLock { if entries[e.id] != nil { entries[e.id] = e; saveIndex() } } }

    private func covered(_ e: Entry, _ offset: Int64, _ length: Int64) -> Bool {
        var p = offset
        let end = offset + length
        while p < end {
            guard let s = e.segments.first(where: { $0.offset <= p && p < $0.offset + $0.length }) else { return false }
            p = s.offset + s.length
        }
        return true
    }

    /// One range request written as a segment; (total file length, content type) from the response, nil on failure.
    private func pull(_ job: Job, _ e: inout Entry, offset: Int64, length: Int64) async -> (Int64, String?)? {
        var r = URLRequest(url: job.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        for (k, v) in job.headers { r.setValue(v, forHTTPHeaderField: k) }
        if job.headers.keys.first(where: { $0.lowercased() == "user-agent" }) == nil { r.setValue(Net.userAgent, forHTTPHeaderField: "User-Agent") }
        r.setValue("bytes=\(offset)-\(offset + length - 1)", forHTTPHeaderField: "Range")
        r.setValue("*/*", forHTTPHeaderField: "Accept")
        guard let (data, resp) = try? await session.data(for: r), let http = resp as? HTTPURLResponse else { return nil }
        guard http.statusCode == 206 || (http.statusCode == 200 && offset == 0) else {
            Log.core.notice("Stream cache: \(job.label, privacy: .public): HTTP \(http.statusCode) at \(offset >> 20) MB")
            return nil
        }
        var total: Int64 = -1
        if let cr = http.value(forHTTPHeaderField: "Content-Range"), let slash = cr.lastIndex(of: "/") {
            total = Int64(cr[cr.index(after: slash)...].trimmingCharacters(in: .whitespaces)) ?? -1
        } else if http.statusCode == 200 {
            total = Int64(http.value(forHTTPHeaderField: "Content-Length") ?? "") ?? -1
        }
        // A 200 to a range request: the whole file would follow; what came is the head anyway, cut to what was asked.
        let bytes = data.count > Int(length) ? data.prefix(Int(length)) : data[...]
        guard !bytes.isEmpty else { return nil }
        let name = "\(offset).bin"
        let file = root.appendingPathComponent(e.id, isDirectory: true).appendingPathComponent(name)
        do { try bytes.write(to: file, options: .atomic) } catch { return nil }
        e.segments.removeAll { $0.offset == offset }
        e.segments.append(Segment(offset: offset, length: Int64(bytes.count), file: name))
        e.segments.sort { $0.offset < $1.offset }
        return (total, http.value(forHTTPHeaderField: "Content-Type"))
    }

    /// Cached bytes of entry [id] from [offset], at most [max]; nil when that byte isn't on disk.
    func read(_ id: String, offset: Int64, max: Int) -> Data? {
        let seg: Segment? = lock.withLock { entries[id]?.segments.first { $0.offset <= offset && offset < $0.offset + $0.length } }
        guard let seg else { return nil }
        let file = root.appendingPathComponent(id, isDirectory: true).appendingPathComponent(seg.file)
        guard let h = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? h.close() }
        let within = offset - seg.offset
        let n = Int(min(Int64(max), seg.length - within))
        guard n > 0, (try? h.seek(toOffset: UInt64(within))) != nil, let d = try? h.read(upToCount: n), !d.isEmpty else { return nil }
        return d
    }
}

// MARK: - The loopback server

/// A tiny HTTP/1.1 server on 127.0.0.1 for Dream Player: `GET /s/<id>/<name>` with a Range header answers from the
/// cache where it can and from the link otherwise, in one response. One response per connection (ffmpeg's HTTP
/// client opens a connection per request and reconnects on a seek).
final class StreamProxy: @unchecked Sendable {
    private let lock = NSLock()
    private var listener: NWListener?
    private var port: UInt16 = 0
    private var served: [String: StreamCache.Entry] = [:]
    private let queue = DispatchQueue(label: "dreamos.streamproxy", qos: .userInitiated)
    private let upstream: URLSession
    private let delegate = PipeDelegate()

    init() {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpShouldSetCookies = false
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 24 * 3600
        c.httpMaximumConnectionsPerHost = 6
        upstream = URLSession(configuration: c, delegate: delegate, delegateQueue: nil)
    }

    func start() {
        lock.withLock {
            if listener != nil { return }
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)
            guard let l = try? NWListener(using: params) else { Log.core.error("Stream cache: no loopback listener"); return }
            listener = l
            l.stateUpdateHandler = { [weak self] st in
                guard let self else { return }
                switch st {
                case .ready:
                    let p = l.port?.rawValue ?? 0
                    self.lock.withLock { self.port = p }
                    Log.core.info("Stream cache: serving on 127.0.0.1:\(p)")
                case .failed(let e):
                    Log.core.error("Stream cache: listener failed: \(String(describing: e), privacy: .public)")
                    self.lock.withLock { self.listener = nil; self.port = 0 }
                default: break
                }
            }
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.start(queue: queue)
        }
    }

    /// The loopback URL for [entry], or nil while the server isn't up.
    func register(_ entry: StreamCache.Entry, fileName: String) -> URL? {
        start()
        let p = lock.withLock { () -> UInt16 in served[entry.id] = entry; return port }
        guard p > 0 else { return nil }
        var name = fileName.isEmpty ? "stream" : fileName
        name = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "stream"
        return URL(string: "http://127.0.0.1:\(p)/s/\(entry.id)/\(name)")
    }

    /// Open connections (held here; a client lets go of itself in `close`).
    private var clients: [ObjectIdentifier: Client] = [:]

    private func accept(_ c: NWConnection) {
        let client = Client(c, proxy: self)
        lock.withLock { clients[ObjectIdentifier(client)] = client }
        client.begin(on: queue)
    }

    fileprivate func closed(_ client: Client) {
        lock.withLock { clients[ObjectIdentifier(client)] = nil }
    }

    fileprivate func entry(_ id: String) -> StreamCache.Entry? {
        // The live entry (segments may have grown since it was registered).
        lock.withLock { served[id] }.map { reg in
            StreamCache.shared.liveEntry(reg.id) ?? reg
        }
    }

    fileprivate func task(for request: URLRequest, pipe: Pipe) -> URLSessionDataTask {
        let t = upstream.dataTask(with: request)
        delegate.attach(pipe, to: t)
        return t
    }

    // MARK: One client connection

    final class Client: @unchecked Sendable {
        private let conn: NWConnection
        private unowned let proxy: StreamProxy
        private var buffer = Data()
        private var closed = false
        private var pipe: Pipe?
        private let lock = NSLock()

        init(_ c: NWConnection, proxy: StreamProxy) { conn = c; self.proxy = proxy }

        func begin(on q: DispatchQueue) {
            conn.stateUpdateHandler = { [weak self] st in
                switch st {
                case .failed, .cancelled: self?.close()
                default: break
                }
            }
            conn.start(queue: q)
            readHeader()
        }

        private func readHeader() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 16 << 10) { [weak self] data, _, done, err in
                guard let self else { return }
                if let data { self.buffer.append(data) }
                if let r = self.buffer.range(of: Data("\r\n\r\n".utf8)) {
                    let head = String(decoding: self.buffer[..<r.lowerBound], as: UTF8.self)
                    self.handle(head)
                } else if done || err != nil || self.buffer.count > 64 << 10 {
                    self.close()
                } else {
                    self.readHeader()
                }
            }
        }

        private func handle(_ head: String) {
            let lines = head.split(separator: "\r\n", omittingEmptySubsequences: true)
            guard let first = lines.first else { close(); return }
            let parts = first.split(separator: " ")
            guard parts.count >= 2 else { close(); return }
            let method = String(parts[0]).uppercased()
            let path = String(parts[1])
            var range: (Int64, Int64?)?
            var badRange = false
            for l in lines.dropFirst() {
                guard let colon = l.firstIndex(of: ":") else { continue }
                let k = l[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                let v = l[l.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                if k == "range" {
                    let spec = v.lowercased().replacingOccurrences(of: "bytes=", with: "")
                    let ab = spec.split(separator: "-", omittingEmptySubsequences: false)
                    if ab.count == 2, let a = Int64(ab[0]) { range = (a, Int64(ab[1])) } else { badRange = true }
                }
            }
            let comps = path.split(separator: "/").map(String.init)
            guard comps.count >= 2, comps[0] == "s", let entry = proxy.entry(comps[1]), let url = URL(string: entry.url) else {
                respond(status: "404 Not Found", headers: ["Content-Length": "0"], body: nil); return
            }
            if method != "GET" && method != "HEAD" { respond(status: "405 Method Not Allowed", headers: ["Content-Length": "0"], body: nil); return }
            let len = entry.length
            let a = range?.0 ?? 0
            let b = min(range?.1 ?? (len - 1), len - 1)
            if badRange || a < 0 || a > b || len <= 0 {
                passThrough(url, entry, rangeHeader: range.map { "bytes=\($0.0)-\($0.1.map(String.init) ?? "")" }, head: method == "HEAD")
                return
            }
            let type = entry.contentType ?? "application/octet-stream"
            var headers = ["Accept-Ranges": "bytes", "Content-Type": type, "Content-Length": "\(b - a + 1)", "Connection": "close"]
            let status: String
            if range != nil { status = "206 Partial Content"; headers["Content-Range"] = "bytes \(a)-\(b)/\(len)" } else { status = "200 OK" }
            if method == "HEAD" { respond(status: status, headers: headers, body: nil); return }
            // The first bytes on disk: our own headers, then disk until the first gap, then the link for the rest.
            if StreamCache.shared.read(entry.id, offset: a, max: 1) != nil {
                respond(status: status, headers: headers, body: nil, keepOpen: true)
                serve(entry, url, from: a, to: b)
            } else {
                passThrough(url, entry, rangeHeader: "bytes=\(a)-\(b)", head: false)
            }
        }

        /// Where a disk-first response is (the sends run one after another on the connection's queue).
        private final class Cursor: @unchecked Sendable { var pos: Int64; var fromDisk: Int64 = 0; init(_ p: Int64) { pos = p } }

        /// Bytes [from, to] of [entry]: disk while it has them, then one upstream request for the rest.
        private func serve(_ entry: StreamCache.Entry, _ url: URL, from: Int64, to: Int64) {
            let cur = Cursor(from)
            serveNext(entry, url, cur, from: from, to: to)
        }

        private func serveNext(_ entry: StreamCache.Entry, _ url: URL, _ cur: Cursor, from: Int64, to: Int64) {
            if cur.pos > to { finish(); return }
            if let d = StreamCache.shared.read(entry.id, offset: cur.pos, max: Int(min(1 << 20, to - cur.pos + 1))) {
                cur.pos += Int64(d.count)
                cur.fromDisk += Int64(d.count)
                conn.send(content: d, completion: .contentProcessed { [weak self] err in
                    guard let self, err == nil else { self?.close(); return }
                    self.serveNext(entry, url, cur, from: from, to: to)
                })
            } else {
                Log.core.debug("Stream cache: \(cur.fromDisk >> 10) KB from disk at \(from >> 10) KB, the link from \(cur.pos >> 10) KB")
                pipeUpstream(url, entry.headers, rangeHeader: "bytes=\(cur.pos)-\(to)", forwardHeaders: false)
            }
        }

        /// Nothing on disk at the start: the link's own answer (status and headers) is forwarded as is.
        private func passThrough(_ url: URL, _ entry: StreamCache.Entry, rangeHeader: String?, head: Bool) {
            pipeUpstream(url, entry.headers, rangeHeader: rangeHeader, forwardHeaders: true, headOnly: head)
        }

        private func pipeUpstream(_ url: URL, _ headers: [String: String], rangeHeader: String?, forwardHeaders: Bool, headOnly: Bool = false) {
            var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
            for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
            if headers.keys.first(where: { $0.lowercased() == "user-agent" }) == nil { r.setValue(Net.userAgent, forHTTPHeaderField: "User-Agent") }
            if let rangeHeader { r.setValue(rangeHeader, forHTTPHeaderField: "Range") }
            r.setValue("*/*", forHTTPHeaderField: "Accept")
            if headOnly { r.httpMethod = "HEAD" }
            let p = Pipe(client: self, forwardHeaders: forwardHeaders)
            lock.withLock { pipe = p }
            let t = proxy.task(for: r, pipe: p)
            p.task = t
            t.priority = URLSessionTask.highPriority
            t.resume()
        }

        // ---- sending ----

        fileprivate func respond(status: String, headers: [String: String], body: Data?, keepOpen: Bool = false) {
            var s = "HTTP/1.1 \(status)\r\n"
            for (k, v) in headers { s += "\(k): \(v)\r\n" }
            s += "\r\n"
            var d = Data(s.utf8)
            if let body { d.append(body) }
            if keepOpen {
                conn.send(content: d, completion: .contentProcessed { [weak self] err in if err != nil { self?.close() } })
            } else {
                conn.send(content: d, completion: .contentProcessed { [weak self] _ in self?.finish() })
            }
        }

        /// Upstream headers arrived (pass-through): forwarded to the client.
        fileprivate func forward(_ http: HTTPURLResponse) {
            var headers: [String: String] = ["Connection": "close"]
            for (k, v) in http.allHeaderFields {
                guard let ks = k as? String, let vs = v as? String else { continue }
                switch ks.lowercased() {
                case "content-length", "content-range", "content-type", "accept-ranges", "last-modified", "etag": headers[ks] = vs
                default: break
                }
            }
            let status = "\(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode).capitalized)"
            var s = "HTTP/1.1 \(status)\r\n"
            for (k, v) in headers { s += "\(k): \(v)\r\n" }
            s += "\r\n"
            conn.send(content: Data(s.utf8), completion: .contentProcessed { [weak self] err in if err != nil { self?.close() } })
        }

        /// A chunk from upstream: sent on; `done` runs when the client took it (back-pressure).
        fileprivate func send(_ d: Data, done: @escaping @Sendable () -> Void) {
            conn.send(content: d, completion: .contentProcessed { [weak self] err in
                if err != nil { self?.close() }
                done()
            })
        }

        fileprivate func finish() {
            conn.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
                self?.close()
            })
        }

        fileprivate func close() {
            let p: Pipe? = lock.withLock {
                if closed { return nil }
                closed = true
                let x = pipe; pipe = nil
                return x
            }
            p?.task?.cancel()
            conn.cancel()
            proxy.closed(self)
        }
    }

    /// One upstream transfer feeding one client; pauses the task while more than `high` bytes wait to be sent.
    final class Pipe: @unchecked Sendable {
        weak var client: Client?
        var task: URLSessionDataTask?
        let forwardHeaders: Bool
        private let lock = NSLock()
        private var pending = 0
        private var suspended = false
        static let high = 6 << 20
        static let low = 1 << 20

        init(client: Client, forwardHeaders: Bool) { self.client = client; self.forwardHeaders = forwardHeaders }

        func response(_ r: URLResponse) {
            guard let http = r as? HTTPURLResponse else { client?.close(); return }
            if forwardHeaders { client?.forward(http) }
            else if http.statusCode != 206 {
                // Mid-response (our headers already went out): only a 206 continues the byte range we promised; a 200
                // would be the file from its start, anything else an error. Close; ffmpeg reconnects.
                Log.core.notice("Stream cache: the link answered HTTP \(http.statusCode) for the rest; closing")
                client?.close()
            }
        }

        func data(_ d: Data) {
            guard let c = client else { task?.cancel(); return }
            let count = d.count
            let pause: Bool = lock.withLock {
                pending += count
                if pending > Self.high && !suspended { suspended = true; return true }
                return false
            }
            if pause { task?.suspend() }
            c.send(d) { [weak self] in
                guard let self else { return }
                let resume: Bool = self.lock.withLock {
                    self.pending -= count
                    if self.suspended && self.pending < Self.low { self.suspended = false; return true }
                    return false
                }
                if resume { self.task?.resume() }
            }
        }

        func complete(_ error: Error?) {
            guard let error else { client?.finish(); return }
            if (error as NSError).code == NSURLErrorCancelled { return } // the client went away first
            Log.core.notice("Stream cache: the link's transfer ended: \((error as NSError).code)")
            client?.close()
        }
    }

    /// Routes URLSession's delegate calls to the pipe of each task.
    final class PipeDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var pipes: [Int: Pipe] = [:]

        func attach(_ p: Pipe, to t: URLSessionTask) { lock.withLock { pipes[t.taskIdentifier] = p } }
        private func pipe(_ t: URLSessionTask) -> Pipe? { lock.withLock { pipes[t.taskIdentifier] } }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            pipe(dataTask)?.response(response)
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            pipe(dataTask)?.data(data)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let p = lock.withLock { pipes.removeValue(forKey: task.taskIdentifier) }
            p?.complete(error)
        }

        // No redirects back through us: the link's own redirect (a CDN hop) is followed by URLSession as usual.
    }
}

extension StreamCache {
    /// The entry as it is now (segments grow while a prefetch runs).
    func liveEntry(_ id: String) -> Entry? { lock.withLock { entries[id] } }
}
