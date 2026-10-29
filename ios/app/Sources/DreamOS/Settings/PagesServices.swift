import Foundation
import DreamCore

// The seams the Pages screens need from modules that are still being written (docs/port/pages.md).
// Each is a tiny protocol with a stand-in, registered in `PagesServices`; the main session points them at the
// real APIs (DreamPlayback's debrid accounts, DreamScrapers' repositories, DreamPlayer's play entry) once those land.

// MARK: Debrid accounts (DreamPlayback: DreamAccounts, DebridLink, AccountHealth, DebridExpiry)

/// One debrid service's state as the Accounts page shows it.
struct DebridAccountState: Sendable, Hashable {
    enum Health: Sendable, Hashable {
        /// Linked, never checked.
        case unchecked
        case ok
        /// Signed out on the service's side: sign in again.
        case invalid
        /// The service didn't answer.
        case offline
        /// The service holds the account on this new network until the owner approves it (AllDebrid's email).
        case blocked
    }
    /// "ad" | "rd" | "pm" | "tb"
    var service: String
    var linked: Bool
    var user: String? = nil
    var health: Health = .unchecked
    /// False = no active premium plan; nil = unknown.
    var premium: Bool? = nil
    var premiumUntil: Date? = nil

    var expired: Bool { premium == false || (premiumUntil.map { $0 < Date() } ?? false) }
    /// Verified with an active premium plan (SetupGate.verified).
    var verified: Bool { linked && health == .ok && !expired }
}

/// A device sign-in in progress (DebridLink.Pending): the code, the website, the QR payload.
struct DebridDeviceLink: Sendable, Hashable {
    var service: String
    var code: String
    /// The page to open ("https://alldebrid.com/pin").
    var url: URL
    var intervalSeconds: Int = 5
    /// Opaque state the implementation needs to poll.
    var token: String = ""
}

enum DebridLinkPoll: Sendable, Hashable {
    case waiting
    case done
    case failed(String)
}

protocol DebridAccountsAPI: Sendable {
    /// Linked services first-to-ask order.
    func linkedOrder() async -> [String]
    func state(_ service: String) async -> DebridAccountState
    /// Asks the service for the account's status (one read-only call).
    func check(_ service: String) async -> DebridAccountState
    /// True when the service signs in with a code on its website; false = it takes an API key.
    func usesDeviceSignIn(_ service: String) -> Bool
    func startDeviceLink(_ service: String) async throws -> DebridDeviceLink
    func poll(_ link: DebridDeviceLink) async -> DebridLinkPoll
    /// Links with an API key (Premiumize without a device sign-in); throws a DreamError with a plain message.
    func link(_ service: String, apiKey: String) async throws
    func unlink(_ service: String) async
    func makePrimary(_ service: String) async
}

enum DebridServices {
    /// The order setup lists them (SetupBrand.SERVICES).
    static let all = ["ad", "rd", "pm", "tb"]
    static func name(_ s: String) -> String {
        switch s {
        case "ad": "AllDebrid"
        case "rd": "Real-Debrid"
        case "pm": "Premiumize"
        case "tb": "TorBox"
        default: s.uppercased()
        }
    }
    static func monogram(_ s: String) -> String { s.uppercased() }
    static func site(_ s: String) -> String {
        switch s {
        case "ad": "alldebrid.com"
        case "rd": "real-debrid.com"
        case "pm": "premiumize.me"
        case "tb": "torbox.app"
        default: ""
        }
    }
    static func signUpURL(_ s: String) -> URL? { URL(string: "https://" + (s == "pm" ? "www." : "") + site(s)) }
}

struct StandInDebrid: DebridAccountsAPI {
    func linkedOrder() async -> [String] { [] }
    func state(_ service: String) async -> DebridAccountState { DebridAccountState(service: service, linked: false) }
    func check(_ service: String) async -> DebridAccountState { DebridAccountState(service: service, linked: false) }
    func usesDeviceSignIn(_ service: String) -> Bool { service != "pm" }
    func startDeviceLink(_ service: String) async throws -> DebridDeviceLink {
        throw DreamError("\(DebridServices.name(service)) didn't answer. Check your internet connection and try again.")
    }
    func poll(_ link: DebridDeviceLink) async -> DebridLinkPoll { .failed("Something went wrong. Try again.") }
    func link(_ service: String, apiKey: String) async throws { throw DreamError("That key didn't work. Check it and try again.") }
    func unlink(_ service: String) async {}
    func makePrimary(_ service: String) async {}
}

// MARK: Scrapers (DreamScrapers: Scrapers, ScraperRepos, ScraperEngine)

struct ScraperRepoInfo: Sendable, Hashable, Identifiable {
    var id: String
    var name: String
    var version: String
    var url: String
    /// The scraper modules it installed, as (name, version) ("CocoScrapers", "1.0.45").
    var modules: [ScraperModuleInfo] = []
}

struct ScraperModuleInfo: Sendable, Hashable {
    var name: String
    var version: String
}

struct ScraperProviderInfo: Sendable, Hashable, Identifiable {
    var id: String
    var name: String
    /// "Torrent Sources" style caption, or nil.
    var caption: String?
    var enabled: Bool
}

struct ScraperStatus: Sendable, Hashable {
    var repos: [ScraperRepoInfo] = []
    var providers: [ScraperProviderInfo] = []
    /// A repository and at least one provider on (SetupGate.scrapersReady).
    var ready: Bool = false
    /// The fastest, most reliable set is on (Scrapers.isRecommended).
    var recommended: Bool = false
}

/// The final check's answer (Scrapers.TestResult).
struct ScraperTestResult: Sendable, Hashable {
    var ok: Bool
    var found: Int
    var ready: Int
    /// A plain sentence.
    var message: String
}

protocol ScrapersAPI: Sendable {
    func status() async -> ScraperStatus
    /// Adds a repository by its address; `progress` gets short plain steps ("Downloading…").
    func addRepository(_ url: String, progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo
    func updateAll(progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo
    func removeRepository(_ id: String) async
    func setEnabled(_ providerId: String, _ on: Bool) async
    func applyRecommended() async
    /// One real search with the user's accounts and scrapers.
    func test(progress: @escaping @Sendable (String) -> Void) async -> ScraperTestResult
}

struct StandInScrapers: ScrapersAPI {
    func status() async -> ScraperStatus { ScraperStatus() }
    func addRepository(_ url: String, progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo {
        throw DreamError("That didn't work. Check the address and try again.")
    }
    func updateAll(progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo {
        throw DreamError("Couldn't check right now. Try again later.")
    }
    func removeRepository(_ id: String) async {}
    func setEnabled(_ providerId: String, _ on: Bool) async {}
    func applyRecommended() async {}
    func test(progress: @escaping @Sendable (String) -> Void) async -> ScraperTestResult {
        ScraperTestResult(ok: false, found: 0, ready: 0, message: "Add a debrid account and a scraper repository, then try again.")
    }
}

// MARK: Registry

@MainActor
enum PagesServices {
    static var debrid: any DebridAccountsAPI = StandInDebrid()
    static var scrapers: any ScrapersAPI = StandInScrapers()
    /// Dream Player's entry for a title or episode. Nil until the Player worker's API is wired: the title page then
    /// asks Dream Core for a link itself and says plainly what's missing.
    static var play: ((PlayRequest) -> Void)?
    /// A trailer inside Dream Player (TrailerEngine), by TMDB video key. Nil = the Trailer button stays hidden.
    static var playTrailer: ((Title, String) -> Void)?
}
