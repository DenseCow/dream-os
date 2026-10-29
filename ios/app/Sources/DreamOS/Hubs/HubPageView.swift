import SwiftUI
import DreamCore
import DreamUI

/// One seasonal hub's page (SeasonHub.kt CinemaSeason): the masthead (glyph, kicker, title, countdown or tagline),
/// Tonight's Pick across the page, the hub's rows for this profile (a kids profile sees only its kids-safe rows), then
/// the other hubs that are on ("More to Celebrate"). The rows come from the catalog's `.season(id)` page; they fill
/// one by one and hold a title only once, like every page. Opened from Home's hub row, a genre page's hub row,
/// Browse's Seasons & Events, and More to Celebrate.
struct HubPageView: View {
    let id: String
    @State private var model: CatalogPageModel
    @State private var hub: HubTile?
    @State private var scrolled = false
    @State private var bar = BarGlassModel(lead: 40, fade: 40)
    @Environment(ShellModel.self) private var shell

    init(id: String) {
        self.id = id
        _model = State(initialValue: CatalogPageModel(page: .season(id)))
    }

    private var name: String { hub?.title ?? CatalogPage.season(id).name }

    var body: some View {
        CinemaScrollPage(tab: nil) { _, safeTop, toTop in
            LazyVStack(alignment: .leading, spacing: Cinema.rowGap) {
                HubMasthead(hub: hub)
                    .barGlassMarker(bar)
                    .padding(.top, safeTop + 64)
                    .padding(.bottom, 2)
                    .onGeometryChange(for: Bool.self) { $0.frame(in: .named(Cinema.scrollSpace)).maxY < safeTop + 52 } action: { scrolled = $0 }
                rows(toTop)
            }
            .padding(.bottom, 12)
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(name).font(.headline).foregroundStyle(Palette.text).lineLimit(1).opacity(scrolled ? 1 : 0)
            }
        }
        // Dream's black glass (BarGlass): up by the time the masthead reaches Back.
        .barGlass(bar)
        .toolbarRole(.editor)
        // `start`, like every catalog page: the fill is the model's own task, so opening a title never cuts it short,
        // and coming back doesn't fetch every row again.
        .task {
            hub = await Services.shared.catalog.hub(id: id)
            model.start()
        }
        .refreshable {
            hub = await Services.shared.catalog.hub(id: id)
            await model.load()
        }
        .onChange(of: shell.reloadToken) { _, _ in Task { await model.load() } }
    }

    @ViewBuilder private func rows(_ toTop: @escaping () -> Void) -> some View {
        switch model.phase {
        case .loading:
            SkeletonRow(.big)
            SkeletonRow(.big)
            SkeletonRow(.landscape)
        case .failed(let message):
            LoadFailed(message) { Task { await model.load() } }
        case .ready:
            let list = model.rows.uniqued()
            if list.isEmpty {
                EmptyState(systemImage: "sparkles", title: "Nothing Here Yet",
                           message: "\(name) will fill up as soon as Dream OS can reach its catalog. Check your connection and try again.",
                           actionTitle: "Try Again") { Task { await model.load() } }
            } else {
                ForEach(list) { row in
                    CatalogRowView(row, open: { shell.open($0.title) },
                                   openHub: { h in if h.id != id { shell.push(.page(.season(h.id))) } },
                                   menu: { shell.menu(for: $0.title) },
                                   onNearEnd: { Task { await model.more(row) } })
                }
                if list.count > 3 { EndCap(onTop: toTop) }
            }
        }
    }
}

/// The hub page's masthead (SeasonUi.masthead): the glyph in the hub's rainbow color, then the kicker, the title large,
/// the countdown and night ("5 Nights Until Halloween  ·  Night 26 of 31") or the tagline, and under a countdown the
/// tagline on its own line. Not a button.
struct HubMasthead: View {
    let hub: HubTile?

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            if let hub {
                GenreGlyph(hub.glyph, tint: HubCard.tint(hub))
                    .frame(width: 64, height: 64)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Kicker(hub.kicker)
                    Text(hub.title)
                        .font(.title.weight(.bold)).tracking(-0.3)
                        .foregroundStyle(Palette.text)
                        .lineLimit(2).minimumScaleFactor(0.8)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                    Text(Self.line(hub))
                        .font(.subheadline.weight(.medium)).foregroundStyle(Palette.textSecondary)
                        .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                    if hub.hasCountdown, !hub.tagline.isEmpty {
                        Text(hub.tagline)
                            .font(.footnote).foregroundStyle(Palette.textSecondary.opacity(0.85))
                            .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 4)
                    }
                }
                .accessibilityElement(children: .combine)
            } else {
                // The hub's words arrive at once (no network); this keeps the page from jumping meanwhile.
                Color.clear.frame(height: 96)
            }
        }
        .padding(.horizontal, Cinema.gutter)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The countdown and the night, or the tagline when the hub has neither.
    static func line(_ hub: HubTile) -> String {
        let parts = [hub.hasCountdown ? hub.status : nil, hub.night].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? hub.tagline : parts.joined(separator: Cinema.dot)
    }
}

/// Browse's Seasons & Events: every hub on today for this profile, as hub tiles (the one Home shows first).
struct HubShelf: View {
    let hubs: [HubTile]
    @Environment(ShellModel.self) private var shell
    @Environment(\.cinemaLayout) private var layout

    var body: some View {
        HorizontalRow("Seasons & Events", items: hubs.uniqued()) { _, h in
            Button { shell.push(.page(.season(h.id))) } label: { HubCard(h, width: max(layout.landW, 200)) }
                .buttonStyle(CardButtonStyle())
        }
    }
}
