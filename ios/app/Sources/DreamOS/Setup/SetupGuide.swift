import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

// Beginner-friendly setup help (owner, 2026-10-28 night): every account step opens with one plain sentence (what it
// is, why Dream needs it, what it costs), then a "Show Me How" card with numbered steps, the exact taps and screen
// names on the outside website and buttons that open the exact page, and an "If Something Goes Wrong" card. Errors
// carry their own "What to Try" help right under them (`ErrorHelp`). Text and presentation only: no setup logic here.

// MARK: Parts

/// An outside web page a guide step opens in Safari.
struct GuideLink: Hashable {
    var title: String
    var url: URL
    init(_ title: String, _ url: String) {
        self.title = title
        self.url = URL(string: url)!
    }
}

/// One numbered instruction: what to do, what the viewer will see, and the pages it opens.
struct GuideStep: Hashable {
    var text: String
    var see: String?
    var links: [GuideLink]
    /// Shows the TMDB key drawing under this step.
    var keyArt: Bool
    init(_ text: String, see: String? = nil, links: [GuideLink] = [], keyArt: Bool = false) {
        self.text = text
        self.see = see
        self.links = links
        self.keyArt = keyArt
    }
}

/// A problem in the viewer's words and what to do about it.
struct TroubleTip: Hashable {
    var problem: String
    var fix: String
}

/// A card row that opens in place ("Show Me How", "If Something Goes Wrong"). Pressed, the row is the pearl box with
/// dark ink and the rainbow border like every other row.
struct SetupDisclosure<Content: View>: View {
    var title: String
    var caption: String?
    var symbol: String
    @ViewBuilder var content: () -> Content
    @State private var open: Bool

    init(title: String, caption: String? = nil, symbol: String, startOpen: Bool = false, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.caption = caption
        self.symbol = symbol
        self.content = content
        _open = State(initialValue: startOpen)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                PageHaptics.tap()
                withAnimation(.easeInOut(duration: 0.26)) { open.toggle() }
            } label: {
                DisclosureHead(title: title, caption: caption, symbol: symbol, open: open)
            }
            .buttonStyle(PageRowStyle())
            .accessibilityHint(open ? "Hides the steps" : "Shows the steps")
            if open {
                content()
                    .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.03)))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
                    .padding(.top, 8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

private struct DisclosureHead: View {
    var title: String
    var caption: String?
    var symbol: String
    var open: Bool
    @Environment(\.pageRowLit) private var lit
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(lit ? Palette.ink : Palette.text.opacity(0.85))
                .frame(width: 36, height: 36)
                .background(Circle().fill(lit ? Palette.ink.opacity(0.10) : Color.white.opacity(0.07)))
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(lit ? Palette.ink : Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                if let caption {
                    Text(caption)
                        .font(.system(size: 13.5))
                        .foregroundStyle(lit ? Palette.ink.opacity(0.62) : Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            PageChevron(color: lit ? Palette.ink.opacity(0.55) : .white.opacity(0.40))
                .rotationEffect(.degrees(open ? 90 : 0))
        }
        .padding(.leading, 14).padding(.trailing, 18).padding(.vertical, 11)
        .frame(minHeight: 60)
    }
}

/// The numbered steps inside a Show Me How card.
struct GuideSteps: View {
    var steps: [GuideStep]
    var intro: String? = nil
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let intro {
                Text(intro).font(.system(size: 14.5)).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("\(i + 1)")
                        .font(.system(size: 13.5, weight: .bold)).foregroundStyle(Palette.ink)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Palette.pearl))
                        .accessibilityLabel("Step \(i + 1)")
                    VStack(alignment: .leading, spacing: 6) {
                        Text(s.text)
                            .font(.system(size: 15.5, weight: .medium)).foregroundStyle(Palette.text).lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                        if let see = s.see {
                            Text(see)
                                .font(.system(size: 14.5)).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if s.keyArt { TMDBKeyArt().padding(.top, 6) }
                        if !s.links.isEmpty {
                            // MetaFlow: a button wider than the line gets the line's width and wraps its words.
                            MetaFlow(spacing: 10, lineSpacing: 10) {
                                ForEach(s.links, id: \.self) { l in
                                    Button {
                                        PageHaptics.tap()
                                        openURL(l.url)
                                    } label: {
                                        HStack(spacing: 8) {
                                            Image(systemName: "safari").font(.system(size: 14, weight: .semibold))
                                            Text(l.title).font(.system(size: 15, weight: .semibold))
                                                .multilineTextAlignment(.leading)
                                                .fixedSize(horizontal: false, vertical: true)
                                        }
                                    }
                                    .buttonStyle(PearlButtonStyle())
                                }
                            }
                            .padding(.top, 6)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }
}

/// Problems and fixes inside an If Something Goes Wrong card.
struct TroubleList: View {
    var tips: [TroubleTip]
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(tips, id: \.self) { t in
                VStack(alignment: .leading, spacing: 5) {
                    Text(t.problem)
                        .font(.system(size: 15.5, weight: .semibold)).foregroundStyle(Palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(t.fix)
                        .font(.system(size: 14.5)).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// "What to Try", shown right under an error message.
struct ErrorHelp: View {
    var lines: [String]
    var body: some View {
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("What to Try").font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.text)
                ForEach(lines, id: \.self) { l in
                    Text(l).font(.system(size: 14.5)).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.04)))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
        }
    }
}

/// What a repository address looks like, in the address's own monospaced type.
struct AddressExamples: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("A Repository Address Looks Like This")
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.text)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(["https://someone.github.io/repo", "https://github.com/someone/repo", "https://example.com/repo.zip"], id: \.self) { a in
                    Text(a)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                        .foregroundStyle(Palette.text.opacity(0.85))
                        .lineLimit(1).minimumScaleFactor(0.6)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(0.06)))
                }
            }
            Text("Copy it exactly as you got it. It usually starts with https:// and often has github in it.")
                .font(.system(size: 14)).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.045)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
    }
}

/// A drawing of TMDB's API page (Dream Cinema look, no screenshot): the short API Key lit with the rainbow border,
/// the long API Read Access Token dimmed, so the viewer copies the right one.
struct TMDBKeyArt: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Circle().fill(Color.white.opacity(0.18)).frame(width: 7, height: 7)
                Circle().fill(Color.white.opacity(0.18)).frame(width: 7, height: 7)
                Circle().fill(Color.white.opacity(0.18)).frame(width: 7, height: 7)
                Text("themoviedb.org  ·  API")
                    .font(.system(size: 11.5, weight: .medium)).foregroundStyle(Palette.textSecondary)
                    .lineLimit(1).padding(.leading, 6)
            }
            field(label: "API Key", value: "8f3a1c9e2b7d4f60a5e1c3b9d2f7a4e8", tag: "Copy This One", lit: true)
            field(label: "API Read Access Token", value: "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI4ZjNhMWM5ZTJiN2Q0ZjYwYTVl", tag: "Not This One", lit: false)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(hex: 0x0E0E10)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("On TMDB's API page, copy the short API Key, not the long API Read Access Token.")
    }

    private func field(label: String, value: String, tag: String, lit: Bool) -> some View {
        let name = Text(label)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(lit ? Palette.text : Palette.textSecondary)
            .lineLimit(1)
        let badge = Text(tag)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(lit ? Palette.ink : Palette.textSecondary)
            .lineLimit(1)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(lit ? Palette.pearl : Color.white.opacity(0.06)))
        return VStack(alignment: .leading, spacing: 6) {
            // Side by side when both fit; on a narrow phone the tag drops under the name (never truncated).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    name.fixedSize()
                    Spacer(minLength: 8)
                    badge.fixedSize()
                }
                VStack(alignment: .leading, spacing: 6) {
                    name.fixedSize()
                    badge.fixedSize()
                }
            }
            Text(value)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(lit ? Palette.text : Palette.text.opacity(0.35))
                // The short key shrinks to stay whole on one line; the long token wraps (it reads as "much longer").
                .lineLimit(lit ? 1 : nil)
                .minimumScaleFactor(lit ? 0.6 : 1)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(lit ? 0.07 : 0.03)))
        .rainbowBorder(lit, radius: 10, width: 1.5)
        .padding(lit ? 2 : 0)
        .opacity(lit ? 1 : 0.8)
    }
}

// MARK: The words, step by step

@MainActor
enum SetupGuide {
    // MARK: TMDB

    static let tmdbIntro = "TMDB's sign-up is easiest on a computer, but your iPhone works too. If a page looks cramped, choose Request Desktop Website in Safari's page menu."

    static let tmdbSteps: [GuideStep] = [
        GuideStep("Make a free TMDB account. Tap Join TMDB, fill in the form and tap Sign Up.",
                  see: "TMDB sends you an email. Tap Activate My Account in it, then log in. Already have an account? Log in instead.",
                  links: [GuideLink("Join TMDB", "https://www.themoviedb.org/signup")]),
        GuideStep("Open the API page in your TMDB settings.",
                  see: "On TMDB, tap your profile picture at the top right, then Settings, then API in the Settings menu. Or use the button below to go straight there.",
                  links: [GuideLink("Open the API Page", "https://www.themoviedb.org/settings/api")]),
        GuideStep("Tap Create, choose Developer, and accept the terms of use.",
                  see: "TMDB shows a short form about the app you'll use the key in."),
        GuideStep("Fill in the form, then tap Submit.",
                  see: "If it asks how you'll use it, choose Personal. For the app's name, type Dream. For its website, any address works, such as https://www.themoviedb.org. For the summary, a short line is fine, such as \"A personal app for browsing movies and shows.\" Then add your name and contact details."),
        GuideStep("Copy the key labeled API Key.",
                  see: "TMDB shows two. Copy API Key: 32 letters and numbers. Leave the much longer API Read Access Token. To copy, press and hold the key, then tap Copy.",
                  keyArt: true),
        GuideStep("Come back to Dream and tap Movie & Show Info.",
                  see: "Paste your key, then tap Save Key. Dream checks it and shows Key Added. Tap Done, then Continue."),
    ]

    /// The same steps for setup, where the last one is Enter My Key and Dream moves on by itself.
    static var tmdbSetupSteps: [GuideStep] {
        Array(tmdbSteps.dropLast()) + [GuideStep("Come back to Dream, tap Enter My Key, then Paste.",
                                                 see: "Dream checks your key, shows Key Added and moves on by itself.")]
    }

    static let tmdbTrouble: [TroubleTip] = [
        TroubleTip(problem: "That Key Didn't Work",
                   fix: "Make sure you copied API Key, the short one, not the long API Read Access Token. Copy it again with nothing before or after it, then paste it."),
        TroubleTip(problem: "TMDB Won't Let You Ask for a Key",
                   fix: "Your account needs to be activated first. Open the email from TMDB, tap Activate My Account, log in again, then open the API page."),
        TroubleTip(problem: "There's No Key on the API Page",
                   fix: "Finish the form under Create first. If TMDB says it's reviewing your request, wait a few minutes and open the API page again."),
        TroubleTip(problem: "The Key Won't Save",
                   fix: "Check that Wi-Fi or cellular data is on, then tap Save Key again."),
    ]

    // MARK: OMDb

    static let omdbSteps: [GuideStep] = [
        GuideStep("Open OMDb's key page and choose FREE! (1,000 daily limit).",
                  links: [GuideLink("Open OMDb", "https://www.omdbapi.com/apikey.aspx")]),
        GuideStep("Type your email, fill in anything else it asks for, and tap Generate API Key.",
                  see: "OMDb emails you your key a few minutes later. Check your junk folder if it doesn't arrive."),
        GuideStep("Open OMDb's email and tap the link in it to activate your key.",
                  see: "The key only works once it's activated. Then press and hold the key in the email and tap Copy."),
        GuideStep("Come back to Dream and tap Ratings.",
                  see: "Paste your key, then tap Save Key. Dream checks it and shows Key Added."),
    ]

    static var omdbSetupSteps: [GuideStep] {
        Array(omdbSteps.dropLast()) + [GuideStep("Come back to Dream and tap Paste.", see: "Dream checks your key and shows Key Added.")]
    }

    static let omdbTrouble: [TroubleTip] = [
        TroubleTip(problem: "That Key Didn't Work",
                   fix: "Tap the activation link in OMDb's email first, then save the key again. Copy only the key, with nothing before or after it."),
        TroubleTip(problem: "The Email Never Came",
                   fix: "Look in your junk folder. Some email services take up to an hour. You can skip ratings for now and add them later in Settings."),
    ]

    // MARK: Debrid services

    static let pickLine = "Premiumize is recommended: Play starts fastest with it. It's about $12 a month, or about $80 for a year. TorBox works the same way. AllDebrid and Real-Debrid cost less, about $4 a month, and work too: Play takes a second or two longer with them."

    static func debridSteps() -> [GuideStep] {
        let d = PagesServices.debrid
        let codeNames = DebridServices.all.filter { d.usesDeviceSignIn($0) }.map(DebridServices.name)
        let keyNames = DebridServices.all.filter { !d.usesDeviceSignIn($0) }.map(DebridServices.name)
        var steps: [GuideStep] = [
            GuideStep("Choose a service and open its website.",
                      see: "Already have one? Go to step 3.",
                      links: [GuideLink("Premiumize", "https://www.premiumize.me/premium"), GuideLink("TorBox", "https://torbox.app/pricing"),
                              GuideLink("AllDebrid", "https://alldebrid.com/offer/"), GuideLink("Real-Debrid", "https://real-debrid.com/premium")]),
            GuideStep("Tap Sign Up, make an account with your email and a password, then buy a plan.",
                      see: "Dream plays only with an active paid plan. The plans page lists each length and its price."),
            GuideStep("Come back to Dream and tap your service in the list.",
                      see: [codeNames.isEmpty ? nil : "\(list(codeNames)) \(codeNames.count == 1 ? "shows" : "show") a short code.",
                            keyNames.isEmpty ? nil : "\(list(keyNames)) \(keyNames.count == 1 ? "asks" : "ask") for your key instead."]
                        .compactMap { $0 }.joined(separator: " ")),
        ]
        if !codeNames.isEmpty {
            steps.append(GuideStep("For a code: tap Open Website. Dream copies the code for you.",
                                   see: "Sign in on the page that opens, paste the code (press and hold the box, then tap Paste) and approve Dream. The code works for about 10 minutes, and Dream connects by itself once you approve."))
        }
        if !keyNames.isEmpty {
            steps.append(GuideStep("For a key: tap Open Your \(keyNames[0]) Account and sign in.",
                                   see: "Find API Key on your account page, press and hold it and tap Copy. Come back, paste it and tap Connect."))
        }
        steps.append(GuideStep("Dream says Connected and moves on by itself.",
                               see: "You can connect more than one. Dream asks them in the order you choose in Settings."))
        return steps
    }

    static let debridTrouble: [TroubleTip] = [
        TroubleTip(problem: "The Code Expired",
                   fix: "Codes work for about 10 minutes. Tap Try Again for a new code, then enter it on the website right away."),
        TroubleTip(problem: "It Says Expired or No Premium Plan",
                   fix: "Your account needs an active paid plan. Buy or renew one on the service's website, then tap the service here and choose Check Again."),
        TroubleTip(problem: "It Says Sign In Again",
                   fix: "Tap the service, choose Link Again, and enter the new code."),
        TroubleTip(problem: "Your Key Wasn't Accepted",
                   fix: "Copy the key again from the API Key line on your account page, with nothing before or after it."),
        TroubleTip(problem: "It Still Says Waiting After You Approve",
                   fix: "Wait a few seconds on this screen. If nothing changes, tap Cancel, tap your service again and use the new code."),
    ]

    // MARK: Trakt

    static func traktSteps() -> [GuideStep] {
        var steps = [GuideStep("Make a free Trakt account, if you don't have one.",
                               links: [GuideLink("Open Trakt", "https://trakt.tv/auth/join")])]
        if !Trakt.shared.configured {
            steps.append(GuideStep("Tap Trakt above, then Use Your Own Trakt App. Tap Open Trakt Apps, then New Application.",
                                   see: "Type any name, such as Dream. Tap Copy next to the Redirect URI Dream shows, paste it into Trakt's Redirect URI box, and tap Save App."))
            steps.append(GuideStep("Copy your Client ID and Client Secret into Dream, then tap Sign In.",
                                   see: "Trakt shows both on your new app's page. Press and hold each one, tap Copy, and paste it into the matching box in Dream."))
        } else {
            steps.append(GuideStep("Tap Trakt above, then Sign In.", see: "Dream shows a short code and copies it for you."))
        }
        steps.append(GuideStep("Tap Open Website, sign in to Trakt, paste the code and approve Dream.",
                               see: "Dream says Connected and starts syncing your history and watchlist."))
        return steps
    }

    // MARK: Scrapers

    static let scraperSteps: [GuideStep] = [
        GuideStep("Copy your repository's address.",
                  see: "If it's a link, press and hold it, then tap Copy Link. If it's text, press and hold it, drag to select all of it, then tap Copy."),
        GuideStep("Come back to Dream, tap Add Repository, then Paste.",
                  see: "Dream shows its progress: Checking Address, Downloading, Installing. Then it shows Added."),
        GuideStep("Dream turns on the recommended providers and moves on by itself.",
                  see: "Each provider searches one website. The recommended ones are the fastest and most reliable, so most titles start in about a second. Manage Scrapers switches any provider on or off."),
    ]

    static let scraperTrouble: [TroubleTip] = [
        TroubleTip(problem: "That Doesn't Look Like a Web Address",
                   fix: "Copy the whole address and paste only that, with no spaces or extra words."),
        TroubleTip(problem: "No Scrapers Were Found",
                   fix: "The address may lead to a page about the repository instead of the repository itself. Use the exact address its maker gives, or ask them for it."),
        TroubleTip(problem: "Couldn't Reach That Address",
                   fix: "Check that Wi-Fi or cellular data is on. If it is, the website may be down for a while: try again later."),
        TroubleTip(problem: "It Isn't in a Format Dream Supports",
                   fix: "That repository won't work with Dream. Try another one."),
        TroubleTip(problem: "Providers Shows 0 On",
                   fix: "Tap Use Recommended Settings, or switch on at least one provider."),
    ]

    /// Help under a failed Add Repository, matched to the message DreamScrapers gave.
    static func repoHelp(_ message: String) -> [String] {
        let m = message.lowercased()
        if m.contains("look like a web address") {
            return ["Copy the whole address and paste only that, with no spaces or extra words. It usually starts with https://."]
        }
        if m.contains("no scrapers were found") {
            return ["The address may lead to a page about the repository instead of the repository itself.",
                    "Use the exact address its maker gives, or ask them for it."]
        }
        if m.contains("couldn't reach") {
            return ["Check that Wi-Fi or cellular data is on.", "If it is, the website may be down for a while: try again later."]
        }
        if m.contains("insecure") || m.contains("format") || m.contains("single add-on") || m.contains("too large") {
            return ["That repository won't work with Dream. Try another one."]
        }
        if m.contains("check out") || m.contains("didn't match") {
            return ["The repository may be updating right now. Wait a few minutes, then try again."]
        }
        return ["Check the address, then tap Try Again.", "If it still won't add, wait a few minutes and try again, or try another repository."]
    }

    // MARK: The check

    /// Help under a check that didn't pass, matched to the message the scrapers gave.
    static func checkHelp(_ message: String) -> [String] {
        let m = message.lowercased()
        if m.contains("add a source") || m.contains("add a debrid account and a scraper") {
            return ["Tap Back at the top left to return to Add Your Scrapers, then add your repository."]
        }
        if m.contains("turn on at least one provider") {
            return ["Tap Back at the top left to return to Add Your Scrapers, tap Manage Scrapers, then Use Recommended Settings."]
        }
        if m.contains("link a streaming account") {
            return ["Your scrapers work, but no debrid service is ready to play.",
                    "Tap Back twice to return to Connect a Debrid Service, and check that your service says Connected and has an active paid plan."]
        }
        if m.contains("no sources came back") {
            return ["Check that Wi-Fi or cellular data is on, then tap Try Again.",
                    "If it still finds nothing, go back to Add Your Scrapers, tap Manage Scrapers and choose Use Recommended Settings, or add another repository."]
        }
        if m.contains("taking too long") {
            return ["Tap Try Again. If it keeps happening, go back to Add Your Scrapers, tap Manage Scrapers and choose Use Recommended Settings."]
        }
        if m.contains("restart") {
            return ["Close Dream completely: swipe up from the bottom of the screen, then swipe Dream away. Open it again and tap Try Again."]
        }
        return ["Tap Try Again. If it still doesn't pass, go back and check that your debrid service says Connected and your scrapers have providers on."]
    }

    private static func list(_ names: [String]) -> String {
        switch names.count {
        case 0: return ""
        case 1: return names[0]
        default: return names.dropLast().joined(separator: ", ") + " and " + names.last!
        }
    }
}
