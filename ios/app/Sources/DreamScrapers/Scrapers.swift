import DreamCore
import Foundation

/// Where the scraper files live (app-private; the Fire TV app's files/scrapers layout).
/// root/repos.json (what was added and from where), root/home/addons/<id>/ (installed modules as their own app lays
/// them out), root/home/userdata/addon_data/<id>/settings.xml (each module's settings: its own defaults with the
/// user's provider choices on top, rewritten by Dream OS). Provider on/off and speed stats: DreamStore "scrapers.prefs".
enum ScraperFiles {
    static let root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("DreamOS/scrapers", isDirectory: true)
    }()
    static var home: URL { dir(root.appendingPathComponent("home", isDirectory: true)) }
    static var addons: URL { dir(home.appendingPathComponent("addons", isDirectory: true)) }
    static var temp: URL {
        let c = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return dir(c.appendingPathComponent("scrapers", isDirectory: true))
    }
    static var reposFile: URL { dir(root).appendingPathComponent("repos.json") }
    static func profileDir(_ id: String) -> URL { home.appendingPathComponent("userdata/addon_data/\(id)", isDirectory: true) }

    private static func dir(_ u: URL) -> URL {
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
}

/// One provider file inside an installed module, for the engine.
struct ProviderRef: Sendable, Hashable {
    /// "<addon>:<name>"
    var id: String
    var addon: String
    var file: URL
    var name: String
    /// "torrent" | "hoster"
    var kind: String
    var defaultOn: Bool
}

/// Sources: the scraper repositories the user adds (e.g. CocoScrapers), whose providers Dream OS runs itself in
/// embedded Python to find streams; the debrid check and Play stay DreamPlayback's. Dream OS ships no sources.
///
/// The setup pages' contract: status, add / update / remove, provider switches, recommended settings, test.
/// Blocking work is `async`; everything else is cheap and safe from any thread. Changes post `didChange` on the
/// main thread. Every `message` is a plain sentence ready to show.
public enum Scrapers {
    public struct Repo: Sendable, Hashable, Identifiable {
        public var url: String
        public var id: String
        public var name: String
        public var version: String
        /// The scraper modules it installed ("CocoScrapers", "1.0.32").
        public var modules: [Module] = []
    }

    public struct Module: Sendable, Hashable {
        public var name: String
        public var version: String
    }

    public struct Provider: Sendable, Hashable, Identifiable {
        /// "<module>:<name>"
        public var id: String
        public var name: String
        /// "torrent" | "hoster"
        public var kind: String
        public var enabled: Bool
    }

    public struct Status: Sendable, Hashable {
        public var repos: [Repo]
        public var providers: [Provider]
        /// The engine is up (Python started with the installed modules loaded).
        public var pythonReady: Bool
        /// Python couldn't start on this phone.
        public var pythonBroken: Bool
        public var ready: Bool { !repos.isEmpty && providers.contains { $0.enabled } }
    }

    public enum AddResult: Sendable, Hashable {
        /// The repository, and the names of what it installed (or updated).
        case ok(Repo, [String])
        /// A plain sentence for the viewer.
        case failed(String)
    }

    public struct TestResult: Sendable, Hashable {
        public var ok: Bool
        public var found: Int
        public var ready: Int
        public var ms: Int
        public var message: String
    }

    /// What the engine measured for one provider: average answer time (ms, smoothed) and recent answers with /
    /// without sources.
    public struct Speed: Sendable, Hashable {
        public var ms: Int
        public var hits: Int
        public var misses: Int
        public var tries: Int { hits + misses }
        public var hitRate: Double { tries == 0 ? 0 : Double(hits) / Double(tries) }
    }

    /// Posted on the main thread whenever repositories, providers or switches change.
    public static let didChange = Notification.Name("DreamScrapers.didChange")

    // MARK: State

    struct Rec: Codable, Sendable {
        var url: String
        var source: ScraperRepos.Source
        /// module id -> version
        var modules: [String: String]
        var repo: Repo {
            let mods = modules.keys.sorted().compactMap { id -> Module? in
                guard let d = try? Data(contentsOf: ScraperFiles.addons.appendingPathComponent("\(id)/addon.xml")),
                      let e = ScraperRepos.parseAddons(d).first else { return nil }
                return Module(name: friendly(e.name), version: e.version.isEmpty ? (modules[id] ?? "") : e.version)
            }
            return Repo(url: url, id: source.id, name: friendly(source.name),
                        version: source.version.isEmpty ? (modules[source.id] ?? modules.values.sorted().first ?? "") : source.version,
                        modules: mods)
        }
    }

    struct Prefs: Codable {
        var on: [String: Bool] = [:]
        /// provider id -> [ms, hits, misses]
        var stats: [String: [Double]] = [:]
        var recApplied: [String]?
    }

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var recs: [Rec]?
        var refs: [ProviderRef]?
        var stamp = 0
        var prefs: Prefs?
        var statsDirty = 0
        var pendingSettings = Set<String>()
    }

    private static let state = State()
    private static let installGate = SerialGate()
    private static let prefsKey = "scrapers.prefs"

    /// Changes whenever what's installed changes (the engine reloads its modules when it differs).
    static var stamp: Int { state.lock.withLock { state.stamp } }

    private static func prefs() -> Prefs {
        state.lock.withLock {
            if let p = state.prefs { return p }
            let p = DreamStore.shared.get(Prefs.self, prefsKey) ?? Prefs()
            state.prefs = p
            return p
        }
    }

    private static func editPrefs(save: Bool = true, _ f: (inout Prefs) -> Void) {
        let p: Prefs = state.lock.withLock {
            var p = state.prefs ?? DreamStore.shared.get(Prefs.self, prefsKey) ?? Prefs()
            f(&p)
            state.prefs = p
            return p
        }
        if save { DreamStore.shared.set(p, prefsKey) }
    }

    static func recs() -> [Rec] {
        if let r = state.lock.withLock({ state.recs }) { return r }
        var list: [Rec] = []
        if let d = try? Data(contentsOf: ScraperFiles.reposFile) {
            do { list = try JSONDecoder().decode([Rec].self, from: d) }
            catch { PythonRuntime.log.error("Scrapers: repos.json unreadable") }
        }
        state.lock.withLock { if state.recs == nil { state.recs = list } }
        return list
    }

    private static func saveRecs(_ list: [Rec]) {
        if let d = try? JSONEncoder().encode(list) { try? d.write(to: ScraperFiles.reposFile, options: .atomic) }
        state.lock.withLock { state.recs = list }
    }

    /// Every provider of the installed modules (filesystem scan, cached until something is installed or removed).
    static func refs() -> [ProviderRef] {
        if let r = state.lock.withLock({ state.refs }) { return r }
        let r = scan()
        state.lock.withLock { state.refs = r }
        return r
    }

    private static func enabled(_ p: Prefs, _ r: ProviderRef) -> Bool { p.on[r.id] ?? r.defaultOn }

    static func enabledRefs() -> [ProviderRef] { let p = prefs(); return refs().filter { enabled(p, $0) } }

    /// The installed modules' Python folders, for sys.path.
    static func libDirs() -> [URL] {
        addonDirs().compactMap { d in moduleLib(d).map { $0 == "." ? d : d.appendingPathComponent($0, isDirectory: true) } }
            .filter { isDir($0) }
    }

    public static func status() -> Status {
        let p = prefs()
        return Status(repos: recs().map(\.repo), providers: refs().map { Provider(id: $0.id, name: $0.name, kind: $0.kind, enabled: enabled(p, $0)) },
                      pythonReady: ScraperEngine.ready, pythonBroken: ScraperEngine.broken)
    }

    /// Installed, with providers switched on, and Python can run here. Cheap; any thread.
    public static var active: Bool { !ScraperEngine.broken && !recs().isEmpty && !enabledRefs().isEmpty }

    /// Starts the engine in the background (Python + the enabled providers imported) so the next Play doesn't wait for
    /// it. Optional: `find` starts it on demand. Returns at once.
    public static func prepare() {
        guard !recs().isEmpty, !enabledRefs().isEmpty, !ScraperEngine.ready || ScraperEngine.needsSync else { return }
        let t = Thread { ScraperEngine.ensure() }
        t.qualityOfService = .utility
        t.stackSize = 4 << 20
        t.start()
    }

    private static func changed() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: didChange, object: nil) }
    }

    private static func installedChanged() {
        state.lock.withLock { state.refs = nil; state.stamp += 1 }
        ScraperSources.shared.scrapersChanged()
        changed()
    }

    // MARK: Add / update / remove

    public static func addRepository(_ url: String, progress: @escaping @Sendable (String) -> Void = { _ in }) async -> AddResult {
        await installGate.run { await addLocked(url, progress: progress) }
    }

    private static func addLocked(_ url: String, progress: @escaping @Sendable (String) -> Void) async -> AddResult {
        do {
            let u = try ScraperRepos.normalize(url)
            progress("Checking Address…")
            let src = try await ScraperRepos.resolve(u)
            var installed: [String: String] = [:]
            var names: [String] = []
            if let direct = src.direct {
                progress("Downloading…")
                let bytes = try await ScraperRepos.download(direct, max: ScraperRepos.maxZip)
                progress("Installing \(friendly(src.name))…")
                try ScraperRepos.unpack(bytes, expectId: src.id, into: ScraperFiles.addons)
                installed[src.id] = src.version
                names.append(friendly(src.name))
            } else {
                progress("Downloading…")
                let (spec, entries) = try await ScraperRepos.firstIndex(src.specs)
                let pick = ScraperRepos.pick(entries)
                if pick.isEmpty { return .failed(ScraperRepos.nothing) }
                for e in pick {
                    progress("Installing \(friendly(e.name))…")
                    try await ScraperRepos.install(e, spec, into: ScraperFiles.addons)
                    installed[e.id] = e.version
                    names.append(friendly(e.name))
                }
            }
            state.lock.withLock { state.refs = nil }
            let found = refs().filter { installed[$0.addon] != nil }
            if found.isEmpty {
                removeModules(Set(installed.keys), keepFor: recs())
                installedChanged()
                return .failed(ScraperRepos.nothing)
            }
            progress("Getting Ready…")
            installed.keys.forEach(writeSettings)
            let rec = Rec(url: u, source: src, modules: installed)
            saveRecs(recs().filter { $0.source.id != src.id } + [rec])
            installedChanged()
            PythonRuntime.log.info("Scrapers: added \(src.id, privacy: .public) (\(installed.map { "\($0.key) \($0.value)" }.joined(separator: ", "), privacy: .public)): \(found.count) providers")
            return .ok(rec.repo, unique(names))
        } catch let e as ScraperRepos.UserError {
            PythonRuntime.log.notice("Scrapers: add failed: \(e.message, privacy: .public)")
            return .failed(e.message)
        } catch is URLError {
            return .failed(ScraperRepos.unreachable)
        } catch {
            PythonRuntime.log.notice("Scrapers: add failed: \(String(describing: error), privacy: .public)")
            return .failed("Something went wrong while adding that source. Try again.")
        }
    }

    public static func updateAll(progress: @escaping @Sendable (String) -> Void = { _ in }) async -> AddResult {
        await installGate.run { await updateLocked(progress: progress) }
    }

    private static func updateLocked(progress: @escaping @Sendable (String) -> Void) async -> AddResult {
        let list = recs()
        if list.isEmpty { return .failed("There are no sources to update yet.") }
        var first: Repo?
        var updated: [String] = []
        var failure: String?
        var out: [Rec] = []
        let fm = FileManager.default
        for rec in list {
            do {
                progress("Checking \(friendly(rec.source.name))…")
                var mods = rec.modules
                var src = rec.source
                if let direct = src.direct {
                    let bytes = try await ScraperRepos.download(direct, max: ScraperRepos.maxZip)
                    let v = ScraperRepos.addonXMLFromZip(bytes).flatMap { ScraperRepos.parseAddons($0).first?.version } ?? ""
                    if ScraperRepos.compareVersions(v, mods[src.id] ?? "") > 0 {
                        progress("Installing \(friendly(src.name))…")
                        try ScraperRepos.unpack(bytes, expectId: src.id, into: ScraperFiles.addons)
                        mods[src.id] = v
                        updated.append(friendly(src.name))
                        src.version = v
                    }
                } else {
                    let (spec, entries) = try await ScraperRepos.firstIndex(src.specs)
                    // A newer repository add-on may have moved: follow its new addresses too.
                    if let e = entries.first(where: { $0.id == src.id && !$0.specs.isEmpty && ScraperRepos.compareVersions($0.version, src.version) > 0 }) {
                        var seen = Set<String>()
                        src = ScraperRepos.Source(id: src.id, name: e.name, version: e.version,
                                                  specs: (e.specs + src.specs).filter { seen.insert($0.info).inserted })
                    }
                    for e in ScraperRepos.pick(entries) {
                        if let have = mods[e.id], ScraperRepos.compareVersions(e.version, have) <= 0,
                           fm.fileExists(atPath: ScraperFiles.addons.appendingPathComponent(e.id).path) { continue }
                        progress("Installing \(friendly(e.name))…")
                        try await ScraperRepos.install(e, spec, into: ScraperFiles.addons)
                        mods[e.id] = e.version
                        updated.append(friendly(e.name))
                    }
                }
                let r = Rec(url: rec.url, source: src, modules: mods)
                out.append(r)
                if first == nil { first = r.repo }
            } catch {
                out.append(rec)
                failure = (error as? ScraperRepos.UserError)?.message
                    ?? (error is URLError ? ScraperRepos.unreachable : "Couldn't check for updates right now. Try again later.")
                PythonRuntime.log.notice("Scrapers: update of \(rec.source.id, privacy: .public) failed")
            }
        }
        saveRecs(out)
        if !updated.isEmpty {
            out.flatMap { $0.modules.keys }.forEach(writeSettings)
            installedChanged()
            PythonRuntime.log.info("Scrapers: updated \(updated.joined(separator: ", "), privacy: .public)")
        }
        guard let first else { return .failed(failure ?? "Couldn't check for updates right now. Try again later.") }
        return .ok(first, unique(updated))
    }

    public static func removeRepository(_ repoId: String) async {
        await installGate.run {
            let list = recs()
            let gone = list.filter { $0.source.id == repoId }
            guard !gone.isEmpty else { return }
            let keep = list.filter { $0.source.id != repoId }
            saveRecs(keep)
            removeModules(Set(gone.flatMap { $0.modules.keys }), keepFor: keep)
            installedChanged()
            PythonRuntime.log.info("Scrapers: removed \(repoId, privacy: .public)")
        }
    }

    /// Deletes `ids` (code, settings, provider switches) unless a repository in `keepFor` still lists them.
    private static func removeModules(_ ids: Set<String>, keepFor: [Rec]) {
        let still = Set(keepFor.flatMap { $0.modules.keys })
        let fm = FileManager.default
        let gone = ids.subtracting(still)
        for id in gone {
            try? fm.removeItem(at: ScraperFiles.addons.appendingPathComponent(id))
            try? fm.removeItem(at: ScraperFiles.profileDir(id))
        }
        editPrefs { p in p.on = p.on.filter { k, _ in !gone.contains(where: { k.hasPrefix("\($0):") }) } }
    }

    // MARK: Provider switches

    public static func setEnabled(_ providerId: String, _ on: Bool) {
        editPrefs { $0.on[providerId] = on }
        let addon = String(providerId.split(separator: ":", maxSplits: 1).first ?? "")
        // A burst of switches (Turn All On) writes each module's settings once.
        let first = state.lock.withLock { state.pendingSettings.insert(addon).inserted }
        if first {
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.4) {
                _ = state.lock.withLock { state.pendingSettings.remove(addon) }
                writeSettings(addon)
                ScraperEngine.settingsChanged()
            }
        }
        // Newly enabled providers are imported by the runner on their first scrape.
        ScraperSources.shared.scrapersChanged()
        changed()
    }

    // MARK: The installed modules on disk

    private static func isDir(_ u: URL) -> Bool {
        var d: ObjCBool = false
        return FileManager.default.fileExists(atPath: u.path, isDirectory: &d) && d.boolValue
    }

    private static func isFile(_ u: URL) -> Bool {
        var d: ObjCBool = false
        return FileManager.default.fileExists(atPath: u.path, isDirectory: &d) && !d.boolValue
    }

    private static func children(_ u: URL) -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: u, includingPropertiesForKeys: nil)) ?? [])
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private static func addonDirs() -> [URL] { children(ScraperFiles.addons).filter(isDir) }

    /// The xbmc.python.module library folder of the add-on in `dir` ("lib"), or nil if it isn't a Python module.
    private static func moduleLib(_ dir: URL) -> String? {
        guard let xml = try? String(contentsOf: dir.appendingPathComponent("addon.xml"), encoding: .utf8),
              let tag = xml.firstMatch(#"<extension\b[^>]*point\s*=\s*"xbmc\.python\.module"[^>]*>"#)?[0] else { return nil }
        let lib = tag.firstMatch(#"library\s*=\s*"([^"]*)""#)?[1] ?? "lib"
        let t = lib.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if t.contains("..") { return "." }
        return t.isEmpty ? "." : t
    }

    /// Providers, Fenomscrapers / CocoScrapers layout: <lib>/<package>/sources_<x>/<kind>/<name>.py (kind folders
    /// "torrents", "hosters"...). Default on/off from the module's own provider.<name> setting.
    private static func scan() -> [ProviderRef] {
        var out: [ProviderRef] = []
        var seen = Set<String>()
        for addon in addonDirs() {
            guard let libName = moduleLib(addon) else { continue }
            let lib = libName == "." ? addon : addon.appendingPathComponent(libName, isDirectory: true)
            var defaults: [String: String]?
            for pkg in children(lib) where isDir(pkg) && isFile(pkg.appendingPathComponent("__init__.py")) {
                for src in children(pkg) where isDir(src) && src.lastPathComponent.hasPrefix("sources") {
                    for kindDir in children(src) where isDir(kindDir) && !kindDir.lastPathComponent.hasPrefix("_") {
                        let kind = kindDir.lastPathComponent.lowercased().contains("torrent") ? "torrent" : "hoster"
                        for f in children(kindDir) where isFile(f) && f.pathExtension == "py" && !f.lastPathComponent.hasPrefix("_") {
                            let n = f.deletingPathExtension().lastPathComponent
                            if defaults == nil { defaults = settingDefaults(addon) }
                            let def = defaults?["provider.\(n)"].map { $0.lowercased() == "true" } ?? true
                            let id = "\(addon.lastPathComponent):\(n)"
                            if seen.insert(id).inserted {
                                out.append(ProviderRef(id: id, addon: addon.lastPathComponent, file: f, name: pretty(n), kind: kind, defaultOn: def))
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /// Every setting id of the module's resources/settings.xml with its default (old and new formats).
    private static func settingDefaults(_ addon: URL) -> [String: String] {
        guard let d = try? Data(contentsOf: addon.appendingPathComponent("resources/settings.xml")), let root = XNode.parse(d) else { return [:] }
        var out: [String: String] = [:]
        var order: [String] = []
        for s in root.descendants("setting") {
            guard let id = s["id"], s["type"] != "action" else { continue }
            if out[id] == nil { order.append(id) }
            out[id] = s.first("default")?.text.trimmingCharacters(in: .whitespacesAndNewlines) ?? s["default"] ?? ""
        }
        return out
    }

    private static func readSettings(_ f: URL) -> [String: String] {
        guard let d = try? Data(contentsOf: f), let root = XNode.parse(d) else { return [:] }
        var out: [String: String] = [:]
        for s in root.descendants("setting") {
            guard let id = s["id"] else { continue }
            out[id] = s["value"] ?? s.text
        }
        return out
    }

    /// The module's userdata settings.xml (every setting, version 2 format): its defaults, what it saved itself, then
    /// Dream OS's provider switches; its debug log off.
    private static func writeSettings(_ addonId: String) {
        let addon = ScraperFiles.addons.appendingPathComponent(addonId, isDirectory: true)
        guard isDir(addon) else { return }
        let dir = ScraperFiles.profileDir(addonId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = dir.appendingPathComponent("settings.xml")
        var values = settingDefaults(addon)
        values.merge(readSettings(f)) { _, b in b }
        let p = prefs()
        for r in refs() where r.addon == addonId { values["provider.\(r.id.afterLast(":"))"] = enabled(p, r) ? "true" : "false" }
        if values["debug.enabled"] != nil { values["debug.enabled"] = "false" }
        var s = "<settings version=\"2\">\n"
        for k in values.keys.sorted() { s += "    <setting id=\"\(esc(k))\">\(esc(values[k] ?? ""))</setting>\n" }
        s += "</settings>\n"
        do { try Data(s.utf8).write(to: f, options: .atomic) }
        catch { PythonRuntime.log.notice("Scrapers: couldn't write \(addonId, privacy: .public) settings") }
    }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    private static func unique(_ a: [String]) -> [String] { var s = Set<String>(); return a.filter { s.insert($0).inserted } }

    // MARK: Names

    private static let names: [String: String] = [
        "1337x": "1337x", "ytsmx": "YTS", "eztv": "EZTV", "piratebay": "The Pirate Bay", "torrentio": "Torrentio",
        "bitsearch": "BitSearch", "knaben": "Knaben", "torrentgalaxy": "TorrentGalaxy", "kickass2": "Kickass", "nyaa": "Nyaa",
        "torrentdownload": "TorrentDownload", "torrentproject2": "TorrentProject", "torrentquest": "TorrentQuest",
        "yourbittorrent": "YourBittorrent", "limetorrents": "LimeTorrents", "torlock": "Torlock", "torrentz2": "Torrentz2",
        "rutor": "Rutor", "mediafusion": "MediaFusion", "comet": "Comet", "aiostreams": "AIOStreams", "bitmagnet": "Bitmagnet",
        "dmm": "Debrid Media Manager", "meteor": "Meteor", "prowlarr": "Prowlarr", "torrentsdb": "TorrentsDB", "torz": "Torz",
        "zilean": "Zilean", "isohunt2": "isoHunt", "bitcq": "BitCQ", "bitlord": "BitLord", "torrentfunk": "TorrentFunk",
    ]

    static func pretty(_ n: String) -> String {
        names[n.lowercased()] ?? n.replacingOccurrences(of: "_", with: " ").split(separator: " ")
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }

    /// The provider's display name ("Torrentio") for an id ("script.module.cocoscrapers:torrentio").
    public static func providerName(_ providerId: String) -> String { pretty(providerId.afterLast(":")) }

    /// "CocoScrapers Module" -> "CocoScrapers"; formatting tags like [COLOR] dropped.
    static func friendly(_ name: String) -> String {
        var s = name.replacingOccurrences(of: #"\[/?[A-Za-z]+[^\]]*\]"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        for suffix in [" Module", " module"] where s.hasSuffix(suffix) { s.removeLast(suffix.count) }
        if s.hasPrefix("Script.Module.") { s.removeFirst("Script.Module.".count) }
        s = s.trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? "Source" : s
    }

    // MARK: Provider speed (the quick check uses the fastest)

    static func recordStat(_ providerId: String, ms: Int, count: Int, error: String?) {
        var save = false
        editPrefs(save: false) { p in
            var s = p.stats[providerId] ?? [Double(ms), 0, 0]
            s[0] = s[0] * 0.7 + Double(ms) * 0.3
            if count > 0 { s[1] += 1 } else { s[2] += 1 }
            if s[1] + s[2] > 40 { s[1] = (s[1] / 2).rounded(.down); s[2] = (s[2] / 2).rounded(.down) }
            p.stats[providerId] = s
        }
        state.lock.withLock { state.statsDirty += 1; if state.statsDirty >= 10 { state.statsDirty = 0; save = true } }
        if save { DreamStore.shared.set(prefs(), prefsKey) }
    }

    /// Index-style providers that usually answer in well under a second, for the quick check before stats exist.
    private static let quick = ["torrentio", "knaben", "bitsearch", "piratebay", "eztv", "ytsmx", "torrentdownload", "1337x"]

    /// The quick check's providers: the `n` enabled ones that answered with sources and fastest lately (or, before
    /// any stats, the known index-style ones), never ones that keep failing.
    static func quickRefs(_ n: Int = 2) -> [ProviderRef] {
        let p = prefs()
        func score(_ r: ProviderRef) -> Double {
            let q = Double(quick.firstIndex(of: r.id.afterLast(":")) ?? 50)
            guard let s = p.stats[r.id], s.count == 3 else { return 5_000 + q * 100 }
            let tries = s[1] + s[2]
            let miss = tries == 0 ? 0 : s[2] / tries
            return s[0] + miss * 8_000 + q
        }
        return Array(enabledRefs().filter { $0.kind == "torrent" }.sorted { score($0) < score($1) }.prefix(n))
    }

    /// The engine's measured speed and success for a provider, or nil before it ran.
    public static func speed(_ providerId: String) -> Speed? {
        guard let s = prefs().stats[providerId], s.count == 3 else { return nil }
        return Speed(ms: Int(s[0]), hits: Int(s[1]), misses: Int(s[2]))
    }

    // MARK: Use Recommended Settings

    /// CocoScrapers' fast indexes and dependable backups (Nyaa only ever gets anime titles). Everything else off.
    private static let cocoOn: Set<String> = ["torrentio", "knaben", "bitsearch", "1337x", "torrentgalaxy", "eztv", "ytsmx", "nyaa"]
    /// Providers that do nothing until they get their own server, key or account.
    private static let needsSetup: Set<String> = ["comet", "mediafusion", "aiostreams", "prowlarr", "zilean", "dmm", "bitmagnet", "meteor"]
    /// General torrent sites: work, but slow to answer.
    private static let generalSites: Set<String> = ["piratebay", "limetorrents", "torlock", "torrentdownload", "torrentproject2", "torrentsdb",
        "torrentz2", "torz", "kickass2", "torrentquest", "yourbittorrent", "isohunt2", "bitcq", "bitlord", "torrentfunk"]

    /// Whether `r` belongs in the recommended set. CocoScrapers: the fixed list. Any other module: the engine's own
    /// numbers once a provider has answered a few times (sources at least 40% of the time, in 5 s or less on
    /// average), else the module's own default; never one that needs its own setup.
    private static func recommendedOn(_ r: ProviderRef) -> Bool {
        let n = r.id.afterLast(":").lowercased()
        if r.addon.lowercased().contains("cocoscrapers") { return cocoOn.contains(n) }
        if needsSetup.contains(n) { return false }
        if let s = speed(r.id), s.tries >= 3 { return s.hitRate >= 0.4 && s.ms <= 5_000 }
        return r.defaultOn
    }

    /// The provider ids "Use Recommended Settings" turns on (every other one goes off).
    public static func recommended() -> Set<String> { Set(refs().filter(recommendedOn).map(\.id)) }

    /// The switches match the recommended set: the one last applied (so measured speeds drifting later don't flip it
    /// by themselves), or the current recommendation when it was never applied.
    public static func isRecommended() -> Bool {
        let all = Set(refs().map(\.id))
        if all.isEmpty { return false }
        let target = prefs().recApplied.map { Set($0).intersection(all) } ?? recommended()
        return Set(enabledRefs().map(\.id)) == target
    }

    /// Turns the recommended providers on and every other one off.
    public static func applyRecommended() {
        let target = recommended()
        editPrefs { $0.recApplied = Array(target).sorted() }
        let p = prefs()
        for r in refs() where enabled(p, r) != target.contains(r.id) { setEnabled(r.id, target.contains(r.id)) }
        PythonRuntime.log.info("Scrapers: recommended settings on (\(target.count) of \(refs().count) providers)")
        changed()
    }

    /// The short line under a provider's name: what it is, at a glance. Nil for one Dream OS doesn't know.
    public static func caption(_ providerId: String) -> String? {
        let n = providerId.afterLast(":").lowercased()
        switch n {
        case "torrentio", "knaben", "bitsearch": return "Fast Index · Recommended"
        case "1337x", "torrentgalaxy": return "Torrent Site · Backup"
        case "eztv": return "TV Episodes"
        case "ytsmx": return "Movies · Small Files"
        case "nyaa": return "Anime"
        case "rutor": return "Russian Site"
        default:
            if needsSetup.contains(n) { return "Needs Its Own Setup" }
            if generalSites.contains(n) { return "Torrent Site" }
            return nil
        }
    }

    // MARK: The setup page's test

    /// A real search for a well-known film through the installed scrapers and the linked account(s).
    public static func test(progress: @escaping @Sendable (String) -> Void = { _ in }) async -> TestResult {
        let t0 = Date()
        func ms() -> Int { Int(Date().timeIntervalSince(t0) * 1000) }
        if recs().isEmpty { return TestResult(ok: false, found: 0, ready: 0, ms: 0, message: "Add a source first.") }
        if enabledRefs().isEmpty { return TestResult(ok: false, found: 0, ready: 0, ms: 0, message: "Turn on at least one provider first.") }
        progress("Starting Up…")
        ScraperEngine.trace("test: starting the engine")
        let up = await PythonRuntime.background { ScraperEngine.ensure() }
        ScraperEngine.trace("test: engine \(up ? "up" : "unavailable") after \(ms()) ms")
        if !up {
            return TestResult(ok: false, found: 0, ready: 0, ms: ms(), message: "Sources can't run on this iPhone right now. Restart Dream OS and try again.")
        }
        progress("Searching…")
        let req = PlayRequest(title: Title(id: 603, kind: .movie, name: "The Matrix", year: "1999"), imdbId: "tt0133093")
        let found = await ScraperSources.shared.find(req, budget: 25)
        ScraperEngine.trace("test: search done, \(found.count) sources after \(ms()) ms")
        if found.isEmpty {
            return TestResult(ok: false, found: 0, ready: 0, ms: ms(), message: "No sources came back. Check your internet connection and try again.")
        }
        progress("Checking Your Account…")
        let playback = Services.shared.playback
        let checked = await playback.isAvailable() ? ((try? await playback.candidates(req)) ?? []) : []
        ScraperEngine.trace("test: account check done, \(checked.count) candidates after \(ms()) ms")
        if !checked.contains(where: { $0.cached != nil }) {
            return TestResult(ok: false, found: found.count, ready: 0, ms: ms(), message: "Found \(found.count) sources. Link a streaming account to play them.")
        }
        let ready = checked.filter { $0.cached == true }.count
        let msg: String
        switch ready {
        case 0: msg = "Found \(found.count) sources, but none are ready on your account right now."
        case 1: msg = "Found \(found.count) sources. 1 is ready to play."
        default: msg = "Found \(found.count) sources. \(ready) are ready to play."
        }
        PythonRuntime.log.info("Scrapers: test: \(found.count) found, \(ready) ready in \(ms()) ms")
        return TestResult(ok: true, found: found.count, ready: ready, ms: ms(), message: msg)
    }
}

/// Runs async operations one after another (installs never overlap).
actor SerialGate {
    private var tail: Task<Void, Never>?

    func run<T: Sendable>(_ op: @escaping @Sendable () async -> T) async -> T {
        let prev = tail
        let t = Task { () -> T in
            _ = await prev?.value
            return await op()
        }
        tail = Task { _ = await t.value }
        return await t.value
    }
}
