import Foundation
import DreamCore

/// Debrid device sign-in done by Dream OS itself, under Dream OS's own identity (DebridLink.kt). The setup page shows
/// `Pending.code` and `Pending.url` (and a QR of `Pending.qr`), calls `poll` every `Pending.interval` seconds, and on
/// `.done` hands the settings to `DebridAccounts.link` (or calls `finish`, which does both).
///
///  - Real-Debrid: device/code with its published client id for open-source apps (each viewer then gets their own
///    client id / secret), poll device/credentials, then oauth token.
///  - AllDebrid: v4.1/pin/get?agent=DreamOS, v4/pin/check until activated.
///  - Premiumize: device sign-in needs a client id registered to Dream OS; without one (this build) the account's API
///    key is entered instead (`premiumizeKey`) and stored as "apikey:<key>".
///  - TorBox: user/auth/device/start?app=DreamOS, user/auth/device/token.
public enum DebridLink {
    public struct Pending: Sendable, Hashable {
        public let service: String
        public let code: String
        public let url: String
        public let qr: String
        /// Seconds between polls.
        public let interval: Int
        /// ms since 1970.
        public let expiresAt: Int64
        let state: [String: String]
    }

    public enum Poll: Sendable {
        case waiting
        case done(settings: [String: String], user: String?)
        case failed(String)
    }

    private static let rdOAuth = "https://api.real-debrid.com/oauth/v2/"
    private static let rdRest = "https://api.real-debrid.com/rest/1.0/"
    /// Real-Debrid's public client id for open-source apps.
    private static let rdClient = "X245A4XAIBGVM"
    private static let tbApi = "https://api.torbox.app/v1/api"
    private static let tbApp = "DreamOS"
    /// A Premiumize device-sign-in client id registered to Dream OS (none yet: the API key is entered instead).
    private static let pmClient = ""

    /// False when [service] can't do the code-and-QR sign-in in this build (Premiumize without a registered client id).
    public static func deviceSignIn(_ service: String) -> Bool { service != "pm" || !pmClient.isEmpty }

    /// Saves an approved sign-in.
    public static func finish(_ service: String, _ poll: Poll) {
        if case let .done(settings, _) = poll { DebridAccounts.link(service, settings: settings) }
    }

    /// Links Premiumize with the account's API key (premiumize.me/account). `.done` when Premiumize accepts it.
    public static func premiumizeKey(_ key: String) async -> Poll {
        let k = key.filter { !$0.isWhitespace }
        if !(8...64).contains(k.count) { return .failed("That doesn't look like a key. Check it and try again.") }
        guard let o = try? await get("https://www.premiumize.me/api/account/info?apikey=\(DebridHttp.enc(k))") else {
            plog.warning("Accounts: PM key check failed")
            return .failed("Premiumize didn't answer. Check the network and try again.")
        }
        if o["status"].string != "success" { return .failed("Premiumize didn't accept that key. Check it and try again.") }
        let user = o["customer_id"].string
        return .done(settings: ["premiumizetoken": DebridHttp.apiKeyPrefix + k, "premiumizeusername": user, "premiumize.enable": "true"], user: user.isEmpty ? nil : user)
    }

    /// Starts a sign-in for "rd" / "ad" / "pm" / "tb"; nil when the service didn't answer.
    public static func start(_ service: String) async -> Pending? {
        let now = wallMs()
        do {
            let p: Pending?
            switch service {
            case "rd":
                let o = try await get("\(rdOAuth)device/code?client_id=\(rdClient)&new_credentials=yes")
                let code = o["user_code"].string, dev = o["device_code"].string
                guard !code.isEmpty, !dev.isEmpty else { return nil }
                let url = o["verification_url"].string.isEmpty ? "https://real-debrid.com/device" : o["verification_url"].string
                let direct = o["direct_verification_url"].string.isEmpty ? "\(url)?user_code=\(code)" : o["direct_verification_url"].string
                p = Pending(service: "rd", code: code, url: url, qr: direct, interval: max(o["interval"].exists ? o["interval"].int : 5, 3),
                            expiresAt: now + Int64(o["expires_in"].exists ? o["expires_in"].int : 600) * 1000, state: ["device_code": dev])
            case "ad":
                let o = try await get("https://api.alldebrid.com/v4.1/pin/get?agent=\(AllDebridService.agent)")["data"]
                let pin = o["pin"].string, check = o["check"].string
                guard !pin.isEmpty, !check.isEmpty else { return nil }
                p = Pending(service: "ad", code: pin, url: o["base_url"].string.isEmpty ? "https://alldebrid.com/pin/" : o["base_url"].string,
                            qr: o["user_url"].string.isEmpty ? "https://alldebrid.com/pin/?pin=\(pin)" : o["user_url"].string, interval: 5,
                            expiresAt: now + Int64(o["expires_in"].exists ? o["expires_in"].int : 600) * 1000, state: ["check": check, "pin": pin])
            case "pm":
                if pmClient.isEmpty { return nil }
                let o = try await post("https://www.premiumize.me/token", ["client_id": pmClient, "response_type": "device_code"])
                let url = o["verification_uri"].string.isEmpty ? "https://www.premiumize.me/device" : o["verification_uri"].string
                p = Pending(service: "pm", code: o["user_code"].string, url: url, qr: url, interval: max(o["interval"].exists ? o["interval"].int : 5, 3),
                            expiresAt: now + Int64(o["expires_in"].exists ? o["expires_in"].int : 600) * 1000, state: ["device_code": o["device_code"].string])
            case "tb":
                let o = try await get("\(tbApi)/user/auth/device/start?app=\(tbApp)")
                guard o["success"].bool else { return nil }
                let d = o["data"]
                var verify = d["verification_url"].string
                if verify.isEmpty { verify = d["verification_uri"].string }
                if verify.isEmpty { verify = "https://torbox.app/oauth/device?app=\(tbApp)" }
                var friendly = d["friendly_verification_url"].string
                if friendly.isEmpty { friendly = d["friendly_url"].string }
                if friendly.isEmpty { friendly = verify }
                p = Pending(service: "tb", code: d["code"].string, url: friendly, qr: verify, interval: max(d["interval"].exists ? d["interval"].int : 5, 3),
                            expiresAt: now + 600_000, state: ["device_code": d["device_code"].string])
            default: return nil
            }
            plog.info("Accounts: \(service.uppercased(), privacy: .public) sign-in started (code shown, polling every \(p?.interval ?? 0) s)")
            return p
        } catch {
            plog.warning("Accounts: \(service.uppercased(), privacy: .public) sign-in start failed")
            return nil
        }
    }

    /// One check whether the viewer approved [p].
    public static func poll(_ p: Pending) async -> Poll {
        if wallMs() > p.expiresAt { return .failed("The code expired. Try again.") }
        do {
            switch p.service {
            case "rd":
                let dev = p.state["device_code"] ?? ""
                guard let cred = try? await getOrNil("\(rdOAuth)device/credentials?client_id=\(rdClient)&code=\(DebridHttp.enc(dev))"),
                      !cred["client_secret"].string.isEmpty else { return .waiting }
                let id = cred["client_id"].string, secret = cred["client_secret"].string
                let tok = try await post("\(rdOAuth)token", ["client_id": id, "client_secret": secret, "code": dev, "grant_type": "http://oauth.net/grant_type/device/1.0"])
                let access = tok["access_token"].string
                guard !access.isEmpty else { return .waiting }
                let user = (try? await get("\(rdRest)user", bearer: access))?["username"].string ?? ""
                return .done(settings: ["realdebrid.clientid": id, "realdebridsecret": secret, "realdebridtoken": access,
                                        "realdebridrefresh": tok["refresh_token"].string, "realdebridusername": user, "realdebrid.enable": "true"],
                             user: user.isEmpty ? nil : user)
            case "ad":
                let d = try await post("https://api.alldebrid.com/v4/pin/check", ["check": p.state["check"] ?? "", "pin": p.state["pin"] ?? ""])["data"]
                let key = d["apikey"].string
                guard d["activated"].bool, !key.isEmpty else { return .waiting }
                let user = (try? await get("https://api.alldebrid.com/v4/user?agent=\(AllDebridService.agent)", bearer: key))?["data"]["user"]["username"].string ?? ""
                return .done(settings: ["alldebridtoken": key, "alldebridusername": user, "alldebrid.enable": "true"], user: user.isEmpty ? nil : user)
            case "pm":
                let o = try await postOrError("https://www.premiumize.me/token", ["client_id": pmClient, "code": p.state["device_code"] ?? "", "grant_type": "device_code"])
                let access = o["access_token"].string
                if !access.isEmpty {
                    let user = (try? await get("https://www.premiumize.me/api/account/info", bearer: access))?["customer_id"].string ?? ""
                    return .done(settings: ["premiumizetoken": access, "premiumizeusername": user, "premiumize.enable": "true"], user: user.isEmpty ? nil : user)
                }
                return o["error"].string == "access_denied" ? .failed("The sign-in was declined.") : .waiting
            case "tb":
                let body = try JSONSerialization.data(withJSONObject: ["device_code": p.state["device_code"] ?? ""])
                let r = try await DebridHttp.request("\(tbApi)/user/auth/device/token", method: "POST", body: body, contentType: "application/json", timeoutMs: 15_000)
                let o = r.json()
                let access = o["data"]["access_token"].string
                guard o["success"].bool, !access.isEmpty else { return .waiting }
                let me = (try? await get("\(tbApi)/user/me", bearer: access))?["data"]
                let email = me?["email"].string ?? ""
                let user = email.isEmpty ? (me?["customer"].string ?? "") : email
                return .done(settings: ["torboxtoken": access, "torbox.username": user, "torbox.enable": "true"], user: user.isEmpty ? nil : user)
            default:
                return .failed("This service can't be linked here.")
            }
        } catch {
            return .waiting // a network blip: keep waiting until the code expires
        }
    }

    // MARK: HTTP

    private struct Bad: Error {}

    private static func get(_ url: String, bearer: String? = nil) async throws -> JV {
        let r = try await DebridHttp.request(url, bearer: bearer, timeoutMs: 15_000)
        guard r.ok else { throw Bad() }
        return r.json()
    }

    /// nil while the server says "not yet" (an error status).
    private static func getOrNil(_ url: String) async throws -> JV? {
        let r = try await DebridHttp.request(url, timeoutMs: 15_000)
        return r.ok && !r.body.isEmpty ? r.json() : nil
    }

    private static func post(_ url: String, _ fields: [String: String]) async throws -> JV {
        let o = try await postOrError(url, fields)
        if o["error"].exists && !o["access_token"].exists && !o["data"].exists && !o["user_code"].exists { throw Bad() }
        return o
    }

    /// The JSON reply whatever the status (device-code polls answer "not yet" with an error status).
    private static func postOrError(_ url: String, _ fields: [String: String]) async throws -> JV {
        try await DebridHttp.postForm(url, fields.map { ($0.key, $0.value) }, timeoutMs: 15_000).json()
    }
}
