import CryptoKit
import DreamCore
import Foundation

/// Kodi-format add-on repositories, read the way their own app reads them (port of ScraperRepos.kt): a repository
/// add-on (repository.x.zip) names its addons.xml (<info>), the MD5 next to it (<checksum>) and where the zips live
/// (<datadir>, laid out as <id>/<id>-<version>.zip). What the user types may be a GitHub repository page, a
/// repository zip, a single add-on zip, an addons.xml, or a web folder listing a repository zip. HTTPS only
/// (http:// is upgraded), sizes capped, zips unpacked safely.
///
/// Downloaded files are untrusted: nothing here runs them; Scrapers only lists their providers.
enum ScraperRepos {
    static let maxIndex = 8 << 20
    static let maxZip = 40 << 20
    static let maxUnzipped = 120 << 20
    static let maxEntries = 6000
    static let maxPage = 3 << 20

    /// A user-facing reason: a plain sentence.
    struct UserError: Error { let message: String; init(_ m: String) { message = m } }

    static let unreachable = "Dream OS couldn't reach that address. Check your internet connection and the address, then try again."
    static let nothing = "No scrapers were found at that address. Check the address and try again."
    static let badAddress = "That doesn't look like a web address. Check it and try again."
    static let insecure = "That source uses an insecure connection, so Dream OS can't use it."
    static let tooLarge = "That download is too large."
    static let unsafe = "That download isn't safe to install."

    /// Their app's own add-ons and what Dream OS provides itself (the requests stack ships inside the app).
    private static let builtInIds: Set<String> = [
        "xbmc.python", "xbmc.gui", "xbmc.json", "xbmc.metadata", "xbmc.addon", "xbmc.core", "kodi.resource", "kodi.binary.global.main",
        "script.module.requests", "script.module.urllib3", "script.module.chardet", "script.module.charset_normalizer",
        "script.module.idna", "script.module.certifi",
    ]

    static func builtIn(_ id: String) -> Bool { builtInIds.contains(id) || id.hasPrefix("xbmc.") || id.hasPrefix("kodi.") }

    /// Where a repository's index lives.
    struct Spec: Codable, Sendable, Hashable {
        var info: String
        var checksum: String?
        var datadir: String
        var zip = true
        var gz = false
    }

    /// One add-on in an addons.xml (or a single addon.xml).
    struct Entry: Sendable {
        var id: String
        var name: String
        var version: String
        var requires: [(id: String, optional: Bool)]
        /// Has xbmc.python.module.
        var module: Bool
        /// A repository add-on's <dir>s.
        var specs: [Spec]
    }

    /// What an address turned out to be. `specs` in the order to try; `direct`: a single add-on zip's address.
    struct Source: Codable, Sendable, Hashable {
        var id: String
        var name: String
        var version: String
        var specs: [Spec]
        var direct: String?
    }

    // MARK: What the address is

    /// Normalized https address, or a UserError.
    static func normalize(_ raw: String) throws -> String {
        var u = raw.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "\"'<>"))
        if u.isEmpty || u.contains(" ") { throw UserError(badAddress) }
        if u.lowercased().hasPrefix("http://") { u = "https://" + u.dropFirst(7) }
        else if !u.lowercased().hasPrefix("https://") {
            if u.contains("://") { throw UserError(badAddress) }
            u = "https://" + u
        }
        guard let host = URL(string: u)?.host, host.contains(".") else { throw UserError(badAddress) }
        return u
    }

    /// What `url` (already normalized) points at, as a repository to install from.
    static func resolve(_ url: String) async throws -> Source {
        let path = URL(string: url)?.path ?? ""
        let lower = path.lowercased()
        let clean = String(url.split(separator: "?", maxSplits: 1).first ?? "").components(separatedBy: "#")[0]
        let gh = clean.firstMatch(#"^https://(?:www\.)?github\.com/([^/]+)/([^/#?]+)(?:/tree/([^/]+)(/.*)?)?/?$"#, caseInsensitive: true)
        if lower.hasSuffix(".zip") { return try fromZip(url, try await download(url, max: maxZip)) }
        if lower.hasSuffix(".xml") || lower.hasSuffix(".xml.gz") { return try await fromIndexURL(url) }
        if let gh, !path.contains("/releases"), !path.contains("/blob/") {
            var repo = gh[2]
            if repo.hasSuffix(".git") { repo.removeLast(4) }
            let sub = gh[4].trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return try await fromGitHub(owner: gh[1], repo: repo, ref: gh[3].isEmpty ? nil : gh[3], sub: sub.isEmpty ? nil : sub)
        }
        return try await fromPage(url)
    }

    /// A zip: a repository add-on (its <dir>s), or a scraper module itself.
    private static func fromZip(_ url: String, _ bytes: Data) throws -> Source {
        guard let xml = addonXMLFromZip(bytes), let e = parseAddons(xml).first else { throw UserError(nothing) }
        return e.specs.isEmpty ? Source(id: e.id, name: e.name, version: e.version, specs: [], direct: url)
            : Source(id: e.id, name: e.name, version: e.version, specs: e.specs)
    }

    private static func fromIndexURL(_ url: String) async throws -> Source {
        let dir = url.beforeLast("/") + "/"
        let gz = url.lowercased().hasSuffix(".gz")
        let spec = Spec(info: url, checksum: (gz ? String(url.dropLast(3)) : url) + ".md5", datadir: dir, gz: gz)
        _ = try await index(spec) // reachable and readable, or throws
        let u = URL(string: url)
        let host = u?.host ?? "site"
        let folder = (u?.path ?? "").beforeLast("/").replacingOccurrences(of: "/", with: ".")
        var id = "site.\(host)\(folder)"
        while id.hasSuffix(".") { id.removeLast() }
        return Source(id: id, name: host, version: "", specs: [spec])
    }

    /// A GitHub repository: its file list (one API call) shows the repository zip (preferred: its <info> is where the
    /// owner publishes updates) and any addons.xml (the fallback, also used when the file list isn't available: then
    /// the usual places are tried directly).
    private static func fromGitHub(owner: String, repo: String, ref: String?, sub: String?) async throws -> Source {
        let branch = ref ?? "HEAD"
        let raw = "https://raw.githubusercontent.com/\(owner)/\(repo)/\(branch)/"
        var paths: [String]?
        do {
            let d = try await download("https://api.github.com/repos/\(owner)/\(repo)/git/trees/\(branch)?recursive=1", max: maxPage,
                                       headers: ["Accept": "application/vnd.github+json"])
            let tree = ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?["tree"] as? [[String: Any]] ?? []
            paths = tree.filter { $0["type"] as? String == "blob" }.compactMap { $0["path"] as? String }
                .filter { p in sub.map { p.hasPrefix($0 + "/") || p == $0 } ?? true }
        } catch let e as UserError {
            throw e
        } catch {
            PythonRuntime.log.notice("Scrapers: GitHub file list unavailable for \(owner, privacy: .public)/\(repo, privacy: .public); trying the usual places")
        }
        let indexes = paths?.filter { $0.hasSuffix("addons.xml") }.sorted { $0.count(of: "/") < $1.count(of: "/") }
            ?? ["zips/addons.xml", "addons.xml", "repo/zips/addons.xml", "_zips/addons.xml", "docs/addons.xml", "repo/addons.xml"]
        // repository.<x>-<version>.zip: the newest of each, the one named like this repository first.
        var best: [String: (path: String, version: String)] = [:]
        for p in paths ?? [] {
            guard let m = p.firstMatch(#"(?:^|/)(repository\.[^/]+?)-([0-9][^/]*)\.zip$"#) else { continue }
            if let old = best[m[1]], compareVersions(old.version, m[2]) >= 0 { continue }
            best[m[1]] = (p, m[2])
        }
        let wanted = repo.replacingOccurrences(of: "repository.", with: "").lowercased()
        let repoZip = best.sorted { a, b in
            let x = a.key.contains(wanted), y = b.key.contains(wanted)
            return x != y ? x : a.key < b.key
        }.first?.value
        var fromRepo: Source?
        if let repoZip {
            do {
                let s = try fromZip(raw + repoZip.path, try await download(raw + repoZip.path, max: maxZip))
                if !s.specs.isEmpty { fromRepo = s }
            } catch {
                PythonRuntime.log.notice("Scrapers: repository zip unreadable")
            }
        }
        // The repository's own addresses first, then the addons.xml right here as a fallback.
        let local = indexes.map { p in
            Spec(info: raw + p, checksum: raw + p + ".md5", datadir: raw + (p.contains("/") ? p.beforeLast("/") + "/" : ""))
        }
        var seen = Set<String>()
        let specs = ((fromRepo?.specs ?? []) + local).filter { seen.insert($0.info).inserted }
        var working: Spec?
        for s in specs {
            if (try? await index(s)) != nil { working = s; break }
        }
        guard let working else { throw UserError(paths == nil ? unreachable : nothing) }
        let ordered = [working] + specs.filter { $0 != working }.prefix(2)
        if let r = fromRepo { return Source(id: r.id, name: r.name, version: r.version, specs: ordered) }
        return Source(id: "github.\(owner).\(repo)".lowercased(), name: repo, version: "", specs: ordered)
    }

    /// A web folder ("file source" style): the repository zip it lists, else its addons.xml.
    private static func fromPage(_ url: String) async throws -> Source {
        let base = url.hasSuffix("/") ? url : url + "/"
        let html: String
        do { html = String(decoding: try await download(url, max: maxPage), as: UTF8.self) }
        catch let e as UserError { throw e } catch { throw UserError(unreachable) }
        let hrefs = html.allMatches(#"href=["']([^"']*repository\.[^"'/]+?\.zip)["']"#, caseInsensitive: true).map { $0[1] }
        func ver(_ h: String) -> String { String(h.afterLast("-").dropLast(4)) }
        for h in hrefs.sorted(by: { compareVersions(ver($0), ver($1)) > 0 }) {
            guard let abs = URL(string: h, relativeTo: URL(string: base))?.absoluteString, let u = try? normalize(abs) else { continue }
            if let s = try? fromZip(u, try await download(u, max: maxZip)) { return s }
        }
        do { return try await fromIndexURL(base + "addons.xml") } catch { throw UserError(nothing) }
    }

    // MARK: The index

    private static let indexCache = IndexCache()

    /// The spec's addons.xml, MD5-checked when a checksum is published. Cached a minute.
    static func index(_ spec: Spec) async throws -> [Entry] {
        if let hit = indexCache.get(spec.info) { return hit }
        var bytes = try await download(spec.info, max: maxIndex)
        if spec.gz || spec.info.lowercased().hasSuffix(".gz") { bytes = try Inflate.gunzip(bytes, max: maxIndex) }
        if let c = spec.checksum, !c.isEmpty, let sum = try? await download(c, max: 4096) {
            let want = String(decoding: sum, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                .components(separatedBy: " ")[0].lowercased()
            if want.count == 32, want.allSatisfy({ "0123456789abcdef".contains($0) }) {
                let got = Insecure.MD5.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
                if got != want { throw UserError("The source's file list didn't check out. Try again in a few minutes.") }
            }
        }
        let list = parseAddons(bytes)
        if list.isEmpty { throw URLError(.cannotParseResponse) }
        indexCache.put(spec.info, list)
        return list
    }

    /// The first spec of `specs` that answers, with its entries.
    static func firstIndex(_ specs: [Spec]) async throws -> (Spec, [Entry]) {
        var err: Error?
        for s in specs {
            do { return (s, try await index(s)) } catch { err = error }
        }
        throw (err as? UserError) ?? UserError(unreachable)
    }

    /// The add-ons to install: the scraper modules (by name; else every Python module, at most a few) plus what they
    /// need that isn't built in and is in the same index.
    static func pick(_ entries: [Entry]) -> [Entry] {
        let byId = Dictionary(entries.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let modules = entries.filter(\.module)
        var wanted = modules.filter { $0.id.lowercased().contains("scraper") || $0.name.lowercased().contains("scraper") }
        if wanted.isEmpty { wanted = Array(modules.prefix(6)) }
        var out: [Entry] = []
        var ids = Set<String>()
        func add(_ e: Entry) {
            guard ids.insert(e.id).inserted else { return }
            out.append(e)
            for dep in e.requires where !builtIn(dep.id) {
                if let d = byId[dep.id] { add(d) }
                else if !dep.optional { PythonRuntime.log.notice("Scrapers: \(e.id, privacy: .public) needs \(dep.id, privacy: .public), which this source doesn't have") }
            }
        }
        wanted.forEach(add)
        return out
    }

    /// Downloads `e` from `spec` and unpacks it into `addonsDir`. Returns its folder.
    @discardableResult
    static func install(_ e: Entry, _ spec: Spec, into addonsDir: URL) async throws -> URL {
        let dir = spec.datadir.hasSuffix("/") ? spec.datadir : spec.datadir + "/"
        if !spec.zip { throw UserError("This source isn't in a format Dream OS supports yet.") }
        let url = try normalize("\(dir)\(e.id)/\(e.id)-\(e.version).zip")
        return try unpack(try await download(url, max: maxZip), expectId: e.id, into: addonsDir)
    }

    // MARK: Zips

    /// The addon.xml at the top of an add-on zip (<id>/addon.xml).
    static func addonXMLFromZip(_ bytes: Data) -> Data? {
        guard let z = try? ZipArchive(bytes, maxEntries: maxEntries) else { return nil }
        for e in z.entries {
            let name = e.name.replacingOccurrences(of: "\\", with: "/")
            if !e.isDirectory, name.count(of: "/") == 1, name.hasSuffix("/addon.xml") { return try? z.read(e, max: 1 << 20) }
        }
        return nil
    }

    /// Unpacks an add-on zip (everything under one <id>/ folder) into addonsDir/<id>, replacing what was there only
    /// once the new copy is complete. Refuses paths that climb out, absolute paths, too many files, too much data,
    /// and a zip whose addon.xml isn't `expectId` (when given).
    @discardableResult
    static func unpack(_ bytes: Data, expectId: String?, into addonsDir: URL) throws -> URL {
        let fm = FileManager.default
        try fm.createDirectory(at: addonsDir, withIntermediateDirectories: true)
        let parent = addonsDir.deletingLastPathComponent()
        let staging = parent.appendingPathComponent("staging-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        let z: ZipArchive
        do { z = try ZipArchive(bytes, maxEntries: maxEntries) } catch ArchiveError.tooLarge { throw UserError(tooLarge) } catch { throw UserError(nothing) }
        var top: String?
        var total = 0
        let root = staging.standardizedFileURL.path + "/"
        for e in z.entries {
            var name = e.name.replacingOccurrences(of: "\\", with: "/")
            if e.name.hasPrefix("/") || name.contains(":") || name.split(separator: "/").contains("..") { throw UserError(unsafe) }
            while name.hasPrefix("/") { name.removeFirst() }
            if name.isEmpty { continue }
            let first = String(name.split(separator: "/").first ?? "")
            if top == nil { top = first } else if first != top { throw UserError("That download isn't a single add-on.") }
            let out = staging.appendingPathComponent(name)
            guard out.standardizedFileURL.path.hasPrefix(root) else { throw UserError(unsafe) }
            if e.isDirectory { try fm.createDirectory(at: out, withIntermediateDirectories: true); continue }
            total += e.size
            if total > maxUnzipped { throw UserError(tooLarge) }
            try fm.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
            let d: Data
            do { d = try z.read(e, max: maxUnzipped) } catch ArchiveError.tooLarge { throw UserError(tooLarge) } catch { throw UserError(nothing) }
            try d.write(to: out)
        }
        guard let id = top, let xml = try? Data(contentsOf: staging.appendingPathComponent("\(id)/addon.xml")),
              let parsed = parseAddons(xml).first else { throw UserError(nothing) }
        if parsed.id != id || (expectId != nil && parsed.id != expectId) || id.firstMatch(#"^[A-Za-z0-9._-]+$"#) == nil {
            throw UserError("That download didn't match what the source listed.")
        }
        let dest = addonsDir.appendingPathComponent(id, isDirectory: true)
        let old = parent.appendingPathComponent("old-\(id)-\(UUID().uuidString)", isDirectory: true)
        if fm.fileExists(atPath: dest.path) { try fm.moveItem(at: dest, to: old) }
        do { try fm.moveItem(at: staging.appendingPathComponent(id), to: dest) }
        catch { try? fm.moveItem(at: old, to: dest); throw error }
        try? fm.removeItem(at: old)
        return dest
    }

    // MARK: XML

    /// addons.xml (or one addon.xml): every <addon>, with its imports, module flag and repository <dir>s.
    static func parseAddons(_ xml: Data) -> [Entry] {
        guard let root = XNode.parse(xml) else {
            PythonRuntime.log.notice("Scrapers: unreadable add-on list")
            return []
        }
        let addons = root.name == "addon" ? [root] : root.all("addon")
        return addons.compactMap { a in
            guard let id = a["id"], !id.isEmpty else { return nil }
            var reqs: [(id: String, optional: Bool)] = []
            for r in a.all("requires") { for i in r.all("import") { if let d = i["addon"] { reqs.append((d, i["optional"] == "true")) } } }
            var module = false
            var specs: [Spec] = []
            for ext in a.all("extension") {
                switch ext["point"] {
                case "xbmc.python.module": module = true
                case "xbmc.addon.repository":
                    // <dir> blocks, or the old form with <info>/<checksum>/<datadir> right inside the extension.
                    let dirs = ext.all("dir").isEmpty ? [ext] : ext.all("dir")
                    for d in dirs {
                        guard let info = d.first("info"), let data = d.first("datadir") else { continue }
                        let i = info.text.trimmingCharacters(in: .whitespacesAndNewlines)
                        let dd = data.text.trimmingCharacters(in: .whitespacesAndNewlines)
                        let c = d.first("checksum")?.text.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard let ni = try? normalize(i), let nd = try? normalize(dd) else { continue }
                        specs.append(Spec(info: ni, checksum: c.flatMap { $0.isEmpty ? nil : try? normalize($0) }, datadir: nd,
                                          zip: data["zip"] != "false", gz: info["compressed"] == "true"))
                    }
                default: break
                }
            }
            let name = a["name"] ?? ""
            return Entry(id: id, name: name.isEmpty ? id : name, version: a["version"] ?? "", requires: reqs, module: module, specs: specs)
        }
    }

    /// Version order: numeric parts compared as numbers ("1.0.45" > "1.0.9").
    static func compareVersions(_ a: String, _ b: String) -> Int {
        let sep = CharacterSet(charactersIn: ".-~+_")
        let x = a.components(separatedBy: sep), y = b.components(separatedBy: sep)
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : "0", q = i < y.count ? y[i] : "0"
            let c: Int
            if let pn = Int64(p), let qn = Int64(q) { c = pn < qn ? -1 : (pn > qn ? 1 : 0) } else { c = p < q ? -1 : (p > q ? 1 : 0) }
            if c != 0 { return c }
        }
        return 0
    }

    // MARK: HTTPS

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20
        c.httpAdditionalHeaders = ["User-Agent": "Dream OS"]
        return URLSession(configuration: c, delegate: HTTPSOnly(), delegateQueue: nil)
    }()

    /// GET over HTTPS only (redirects stay on HTTPS), at most `max` bytes.
    static func download(_ url: String, max: Int, headers: [String: String] = [:]) async throws -> Data {
        guard url.lowercased().hasPrefix("https://"), let u = URL(string: url) else { throw UserError(insecure) }
        var req = URLRequest(url: u)
        req.timeoutInterval = 20
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let (data, resp): (Data, URLResponse)
        do { (data, resp) = try await session.data(for: req) }
        catch let e as URLError where e.code == .cancelled { throw UserError(insecure) }
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if let final = http.url, final.scheme?.lowercased() != "https" { throw UserError(insecure) }
        guard http.statusCode == 200 else { throw URLError(.badServerResponse) }
        if data.count > max { throw UserError(tooLarge) }
        return data
    }

    private final class HTTPSOnly: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? {
            // Refusing returns the redirect response itself (not 200), so the download fails as unreachable.
            request.url?.scheme?.lowercased() == "https" ? request : nil
        }
    }

    private final class IndexCache: @unchecked Sendable {
        private let lock = NSLock()
        private var map: [String: (at: Date, list: [Entry])] = [:]
        func get(_ k: String) -> [Entry]? {
            lock.withLock { map[k].flatMap { Date().timeIntervalSince($0.at) < 60 ? $0.list : nil } }
        }
        func put(_ k: String, _ v: [Entry]) { lock.withLock { map[k] = (Date(), v) } }
    }
}

// MARK: Small string helpers

extension String {
    /// Capture groups of the first match (index 0 = whole match; missing groups are "").
    func firstMatch(_ pattern: String, caseInsensitive: Bool = false) -> [String]? {
        allMatches(pattern, caseInsensitive: caseInsensitive, limit: 1).first
    }

    func allMatches(_ pattern: String, caseInsensitive: Bool = false, limit: Int = .max) -> [[String]] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : []) else { return [] }
        let ns = self as NSString
        var out: [[String]] = []
        for m in re.matches(in: self, range: NSRange(location: 0, length: ns.length)) {
            out.append((0..<m.numberOfRanges).map { i in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            })
            if out.count >= limit { break }
        }
        return out
    }

    func beforeLast(_ s: String) -> String {
        guard let r = range(of: s, options: .backwards) else { return self }
        return String(self[..<r.lowerBound])
    }

    func afterLast(_ s: String) -> String {
        guard let r = range(of: s, options: .backwards) else { return self }
        return String(self[r.upperBound...])
    }

    func count(of s: Character) -> Int { reduce(0) { $1 == s ? $0 + 1 : $0 } }
}
