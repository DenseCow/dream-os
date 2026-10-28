import SwiftUI
import DreamCore
import DreamUI

/// Set Up Scrapers (ScraperPages.overview): the repositories, Add, the providers with switches, Use Recommended
/// Settings and Turn All On/Off. Shared by Settings and setup.
struct ScrapersList: View {
    var changed: () -> Void = {}
    @State private var st = ScraperStatus()
    @State private var loaded = false
    @State private var adding = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if st.repos.isEmpty {
                PageSection(title: "Your Repository", required: true)
                PageNote(text: loaded ? "No repository yet. Tap below, then paste its address." : " ", size: 15).padding(.top, 10)
                PageRow(title: "Add Scraper Repository", caption: "Type or Paste Its Address", lead: .symbol("plus"), chevron: true) { adding = true }
                    .padding(.top, 14)
            } else {
                PageSection(title: st.repos.count == 1 ? "Your Repository" : "Your Repositories", required: true)
                VStack(spacing: PageSpace.row) {
                    ForEach(st.repos) { r in
                        PageLinkRow(title: r.name,
                                    caption: [r.version.isEmpty ? nil : "Version \(r.version)", repoHost(r.url)].compactMap { $0 }.joined(separator: " · "),
                                    value: "Installed", valueTone: Palette.greenText,
                                    lead: .monogram(String(r.name.prefix(1)).uppercased()), trailingMark: .ok) {
                            RepoView(repo: r) { Task { await load() } }
                        }
                    }
                    PageRow(title: "Add Another Repository", lead: .symbol("plus"), chevron: true) { adding = true }
                }
                .padding(.top, 10)
                let on = st.providers.filter(\.enabled).count
                PageSection(title: "Providers · \(on) of \(st.providers.count) On").padding(.top, PageSpace.group)
                if st.providers.isEmpty {
                    PageNote(text: "This repository didn't add any providers yet. Try Update, or add another repository.", size: 14).padding(.top, 10)
                } else {
                    VStack(spacing: PageSpace.row) {
                        if st.recommended {
                            PageInfoRow(title: "Recommended Settings On", caption: "The fastest, most reliable providers are on.", lead: .mark(.ok))
                        } else {
                            PageRow(title: "Use Recommended Settings",
                                    caption: "Turns on the fastest, most reliable providers so most titles start in about a second.",
                                    lead: .mark(.off)) {
                                Task { await PagesServices.scrapers.applyRecommended(); await load() }
                            }
                        }
                        PageRow(title: on == st.providers.count ? "Turn All Off" : "Turn All On") {
                            let target = on != st.providers.count
                            Task {
                                for p in st.providers { await PagesServices.scrapers.setEnabled(p.id, target) }
                                await load()
                            }
                        }
                        ForEach(st.providers) { p in
                            PageRow(title: p.name, caption: p.caption, toggle: p.enabled) {
                                if let i = st.providers.firstIndex(of: p) { st.providers[i].enabled.toggle() }
                                Task { await PagesServices.scrapers.setEnabled(p.id, !p.enabled); await load() }
                            }
                        }
                    }
                    .padding(.top, 10)
                }
            }
        }
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .dreamScrapersChanged)) { _ in Task { await load() } }
        .sheet(isPresented: $adding) {
            NavigationStack { AddRepoView { Task { await load() } } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
    }

    private func load() async {
        st = await PagesServices.scrapers.status()
        loaded = true
        changed()
    }
}

func repoHost(_ url: String) -> String {
    URL(string: url.trimmingCharacters(in: .whitespaces))?.host()?.replacingOccurrences(of: "www.", with: "") ?? String(url.prefix(48))
}

/// Settings › Scrapers.
struct ScrapersView: View {
    var body: some View {
        PageScaffold(title: "Scrapers", subtitle: "Scrapers search the web for streams of what you pick. Dream OS doesn't include any: add the repository you use.") {
            ScrapersList()
        }
    }
}

/// Add Scraper Repository: the address (iOS keyboard, paste), then calm progress in its own words, then the result.
struct AddRepoView: View {
    var changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var step: String?
    @State private var result: Result<ScraperRepoInfo, DreamError>?
    @State private var providers = 0

    var body: some View {
        PageScaffold(title: "Add Repository") {
            VStack(alignment: .leading, spacing: 16) {
                if let result {
                    switch result {
                    case .success(let r):
                        PageResult(mark: .ok, headline: "Added \(r.name)")
                        PageNote(text: providers > 0 ? "\(providers) providers are ready." : "Installed.")
                        PagePrimaryButton(title: "Done") { dismiss() }
                    case .failure(let e):
                        PageResult(mark: .warn, headline: "Couldn't Add It")
                        PageNote(text: e.message)
                        ErrorHelp(lines: SetupGuide.repoHelp(e.message))
                        PagePrimaryButton(title: "Try Again") { self.result = nil }
                    }
                } else if let step {
                    PageInfoRow(title: step, caption: repoHost(url), lead: .mark(.busy))
                } else {
                    PageNote(text: "Paste the repository's address exactly as you got it. It usually starts with https:// and often has github in it, such as https://someone.github.io/repo.")
                    PageTextField(placeholder: "https://yourname.github.io/repo", text: $url, keyboard: .URL) { Task { await add() } }
                    HStack(spacing: 10) {
                        PageSecondaryButton(title: "Paste", systemImage: "doc.on.clipboard") {
                            if let s = UIPasteboard.general.string { url = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                        }
                        PagePrimaryButton(title: "Add") { Task { await add() } }
                    }
                }
            }
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }

    private func add() async {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !u.isEmpty, u != "https://" else { return }
        step = "Starting…"
        do {
            let r = try await PagesServices.scrapers.addRepository(u) { msg in
                Task { @MainActor in step = msg }
            }
            providers = await PagesServices.scrapers.status().providers.count
            result = .success(r)
            PageHaptics.success()
            NotificationCenter.default.post(name: .dreamScrapersChanged, object: nil)
            changed()
        } catch let e as DreamError {
            result = .failure(e)
            PageHaptics.warning()
        } catch {
            result = .failure(DreamError("That didn't work. Check the address and try again."))
        }
        step = nil
    }
}

/// One repository: its modules' versions, the address, Update and Remove.
struct RepoView: View {
    let repo: ScraperRepoInfo
    var changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var updating: String?
    @State private var updateResult: (String, String?, PageMark)?
    @State private var confirmRemove = false

    var body: some View {
        PageScaffold(title: repo.name) {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: PageSpace.row) {
                    if repo.modules.isEmpty {
                        PageInfoRow(title: "Version", value: repo.version.isEmpty ? "Unknown" : repo.version)
                    } else {
                        ForEach(repo.modules, id: \.name) { m in PageInfoRow(title: m.name, caption: "Installed", value: "Version \(m.version)") }
                    }
                    PageInfoRow(title: "Address", caption: repo.url)
                }
                PageSection(title: "Actions").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    if let updating {
                        PageInfoRow(title: updating, lead: .mark(.busy))
                    } else if let r = updateResult {
                        PageRow(title: r.0, caption: r.1, lead: .mark(r.2)) { Task { await update() } }
                    } else {
                        PageRow(title: "Update", caption: "Gets the Newest Version of Its Providers") { Task { await update() } }
                    }
                    PageRow(title: "Remove Repository", caption: "Its Providers Are Removed Too", chevron: true) { confirmRemove = true }
                }
                .padding(.top, 10)
            }
        }
        .confirmationDialog("Remove \(repo.name)?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                Task {
                    await PagesServices.scrapers.removeRepository(repo.id)
                    NotificationCenter.default.post(name: .dreamScrapersChanged, object: nil)
                    changed()
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Dream OS removes this repository and its providers. Add a repository again to keep finding streams.")
        }
    }

    private func update() async {
        updating = "Checking for Updates…"
        do {
            let r = try await PagesServices.scrapers.updateAll { m in Task { @MainActor in updating = m } }
            updateResult = ("Up to Date", "Version \(r.version)", .ok)
            NotificationCenter.default.post(name: .dreamScrapersChanged, object: nil)
            changed()
        } catch let e as DreamError {
            updateResult = ("Update", e.message, .warn)
        } catch {
            updateResult = ("Update", "Couldn't check right now. Try again later.", .warn)
        }
        updating = nil
    }
}
