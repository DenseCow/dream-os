import Foundation
import Security
import os

/// Logging. Use `Log.app.info(...)`; never log tokens or full debrid URLs (STANDARDS §1).
public enum Log {
    public static let app = Logger(subsystem: "com.dreamos", category: "DreamOS")
    public static let core = Logger(subsystem: "com.dreamos", category: "DreamCore")
}

/// Small JSON documents in Application Support, optionally per profile (the Fire TV app's SharedPreferences
/// with `Profiles.suffix`). Thread-safe; reads are cached in memory.
public final class DreamStore: @unchecked Sendable {
    public static let shared = DreamStore()
    private let lock = NSLock()
    private var cache: [String: Data] = [:]
    /// The active profile's id; set by the profile service. Per-profile keys are stored under it.
    public var profileId: String { get { lock.withLock { _profile } } set { lock.withLock { _profile = newValue; cache.removeAll() } } }
    private var _profile = "default"
    private let root: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        root = base.appendingPathComponent("DreamOS", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    private func file(_ key: String, perProfile: Bool) -> URL {
        let name = (perProfile ? "p_\(_profile)_" : "") + key.replacingOccurrences(of: "/", with: "_") + ".json"
        return root.appendingPathComponent(name)
    }

    public func get<T: Decodable>(_ type: T.Type, _ key: String, perProfile: Bool = false) -> T? {
        lock.withLock {
            let url = file(key, perProfile: perProfile)
            let data = cache[url.path] ?? (try? Data(contentsOf: url))
            guard let data else { return nil }
            cache[url.path] = data
            do { return try JSONDecoder().decode(T.self, from: data) } catch {
                // Callers read nil as empty and their next save replaces the file: a copy is kept aside first, once.
                let aside = url.appendingPathExtension("unreadable")
                if !FileManager.default.fileExists(atPath: aside.path) {
                    try? FileManager.default.copyItem(at: url, to: aside)
                    Log.core.error("Store: \(key, privacy: .public) couldn't be read; a copy was kept")
                }
                return nil
            }
        }
    }

    public func set<T: Encodable>(_ value: T?, _ key: String, perProfile: Bool = false) {
        lock.withLock {
            let url = file(key, perProfile: perProfile)
            guard let value, let data = try? JSONEncoder().encode(value) else {
                cache[url.path] = nil
                try? FileManager.default.removeItem(at: url)
                return
            }
            cache[url.path] = data
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// Deletes every per-profile document of profile [id] (a deleted profile: its history, My List, settings).
    public func removeProfileData(_ id: String) {
        lock.withLock {
            let prefix = "p_\(id)_"
            let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
            for f in files where f.lastPathComponent.hasPrefix(prefix) {
                cache[f.path] = nil
                try? FileManager.default.removeItem(at: f)
            }
        }
    }
}

/// Secrets the viewer enters (debrid tokens, Trakt tokens, their own TMDB/OMDb keys) live in the Keychain.
public enum SecureStore {
    private static let service = "com.dreamos.secure"

    public static func get(_ key: String) -> String? { lookup(key).value }

    /// [key]'s value, and whether the Keychain answered at all. `readable` false (locked since the phone started,
    /// or a Keychain error) means unknown, never "not saved".
    public static func lookup(_ key: String) -> (value: String?, readable: Bool) {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let s = SecItemCopyMatching(q as CFDictionary, &out)
        switch s {
        case errSecSuccess: return ((out as? Data).flatMap { String(data: $0, encoding: .utf8) }, true)
        case errSecItemNotFound: return (nil, true)
        default:
            Log.core.error("Keychain: reading \(key, privacy: .public) failed (\(s, privacy: .public))")
            return (nil, false)
        }
    }

    /// Saves in place: a write that fails keeps the old value (deleting first lost it whenever the add then failed).
    /// nil or empty deletes.
    public static func set(_ key: String, _ value: String?) {
        let q = query(key)
        guard let value, !value.isEmpty else {
            let s = SecItemDelete(q as CFDictionary)
            if s != errSecSuccess && s != errSecItemNotFound { Log.core.error("Keychain: deleting \(key, privacy: .public) failed (\(s, privacy: .public))") }
            return
        }
        let attrs: [String: Any] = [kSecValueData as String: Data(value.utf8),
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        var s = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if s == errSecItemNotFound {
            s = SecItemAdd(q.merging(attrs) { $1 } as CFDictionary, nil)
        }
        if s != errSecSuccess { Log.core.error("Keychain: saving \(key, privacy: .public) failed (\(s, privacy: .public)); the old value stays") }
    }

    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }
}

/// Build-time keys (Secrets.swift is generated from fire-home/local.properties by tools/gen-secrets.sh and never
/// committed). A key the viewer enters in Settings wins over the built-in one.
public enum Keys {
    /// TMDB: only the viewer's own key (owner, 2026-10-28: no built-in key option). A build that carries the owner's
    /// key (Secrets.swift) hands it over once as if the owner typed it in (`adoptOwnersKeys`), so it shows as their
    /// connected key; every other build asks for one in setup.
    public static var tmdb: String { SecureStore.get("tmdb.key") ?? "" }

    /// Once per install: the build's own TMDB key becomes the viewer's saved key (the owner's builds only; open-source
    /// builds have none). Never overwrites a key the viewer entered.
    public static func adoptOwnersKeys() {
        guard SecureStore.get("tmdb.key") == nil, !BuiltInKeys.tmdb.isEmpty else { return }
        SecureStore.set("tmdb.key", BuiltInKeys.tmdb)
    }
    public static var omdb: String { SecureStore.get("omdb.key") ?? BuiltInKeys.omdb }
    public static var traktClientId: String { BuiltInKeys.traktClientId }
    public static var traktClientSecret: String { BuiltInKeys.traktClientSecret }
}
