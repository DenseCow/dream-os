import SwiftUI
import DreamCore
import DreamUI

/// A cast member's page (the TV's X-Ray person card, as a page): their portrait, name and the character they play here,
/// born / died and birthplace, the start of their biography ending on a whole sentence (tap for all of it), Known For
/// (their best-known titles, `catalog.personDetails`), then every movie and show as a poster grid (`catalog.person`).
/// Both already follow the profile's Kids limits. Tapping a poster opens its title page.
struct PersonView: View {
    let person: CastMember
    /// The title they were opened from (left out of Known For).
    var fromTitle: String? = nil
    @State private var details: PersonDetails?
    @State private var titles: [Title] = []
    @State private var loaded = false
    @State private var failed = false
    @State private var bioOpen = false
    /// The bar's black glass, up once the page scrolls under Back.
    @State private var bar = BarGlassModel(lead: 12, fade: 20)

    var body: some View {
        GeometryReader { geo in
            let gutter: CGFloat = geo.size.width > 600 ? 32 : 20
            let cols = max(3, Int((geo.size.width - 2 * gutter + 12) / 122))
            let w = (geo.size.width - 2 * gutter - CGFloat(cols - 1) * 12) / CGFloat(cols)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header
                    bio
                    knownFor(gutter: gutter, width: min(124, w))
                    if !loaded {
                        LazyVGrid(columns: Array(repeating: GridItem(.fixed(w), spacing: 12), count: cols), alignment: .leading, spacing: 18) {
                            ForEach(0..<cols * 2, id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.06)).frame(width: w, height: w * 1.5)
                            }
                        }
                    } else if titles.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            Text(failed ? "Couldn't load \(person.name)'s movies and shows right now." : "No other movies or shows to show for \(person.name).")
                                .font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                            if failed {
                                Button("Try Again") { Task { await load() } }.buttonStyle(PearlButtonStyle())
                            }
                        }
                    } else {
                        Text("Movies & Shows").font(.system(size: 20, weight: .bold)).foregroundStyle(Palette.text)
                        LazyVGrid(columns: Array(repeating: GridItem(.fixed(w), spacing: 12), count: cols), alignment: .leading, spacing: 18) {
                            ForEach(titles.uniqued()) { t in
                                NavigationLink { TitleDetailsView(title: t) } label: { PosterTile(title: t, width: w) }
                                    .buttonStyle(CardPressStyle())
                            }
                        }
                    }
                }
                .barGlassMarker(bar)
                .padding(.horizontal, gutter)
                .padding(.top, 12)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .background(DreamBackground())
        .navigationTitle(person.name)
        .navigationBarTitleDisplayMode(.inline)
        .barGlass(bar)
        .task { if !loaded { await load() } }
    }

    // MARK: Parts

    private var header: some View {
        HStack(spacing: 16) {
            CastPortrait(member: portraitMember, size: 96)
            VStack(alignment: .leading, spacing: 5) {
                if !person.character.isEmpty {
                    Text("As \(person.character)")
                        .font(Cinema.Fonts.kicker)
                        .foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                Text(details?.name.isEmpty == false ? details!.name : person.name)
                    .font(.system(size: 26, weight: .bold)).foregroundStyle(Palette.text).lineLimit(2)
                if let meta = details?.meta, !meta.isEmpty {
                    Text(meta).font(.system(size: 13.5, weight: .medium)).foregroundStyle(Palette.textSecondary)
                        .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// The portrait at the page's size: the person's own picture once known, else the cast list's.
    private var portraitMember: CastMember {
        var m = person
        if m.profilePath == nil, let p = details?.profilePath { m.profilePath = p }
        return m
    }

    @ViewBuilder private var bio: some View {
        if let full = details?.bio, !full.isEmpty {
            let lead = PersonDetails.bioLead(full)
            let more = lead.count < full.trimmingCharacters(in: .whitespacesAndNewlines).count
            VStack(alignment: .leading, spacing: 8) {
                Text(bioOpen ? full : lead)
                    .font(.system(size: 15))
                    .foregroundStyle(Palette.text.opacity(0.84))
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .onTapGesture { if more { withAnimation(.easeOut(duration: 0.25)) { bioOpen.toggle() } } }
                if more && !bioOpen {
                    Button("More") { withAnimation(.easeOut(duration: 0.25)) { bioOpen = true } }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.text)
                }
            }
        }
    }

    @ViewBuilder private func knownFor(gutter: CGFloat, width: CGFloat) -> some View {
        if let list = details?.knownFor, list.count >= 2 {
            VStack(alignment: .leading, spacing: 12) {
                Text("Known For").font(.system(size: 20, weight: .bold)).foregroundStyle(Palette.text)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(list.uniqued()) { t in
                            NavigationLink { TitleDetailsView(title: t) } label: { PosterTile(title: t, width: width) }
                                .buttonStyle(CardPressStyle())
                        }
                    }
                    .padding(.vertical, 6)
                }
                .contentMargins(.horizontal, gutter, for: .scrollContent)
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
                .padding(.horizontal, -gutter)
            }
        }
    }

    private func load() async {
        let c = Services.shared.catalog
        async let facts = c.personDetails(id: person.id, excluding: fromTitle)
        do {
            titles = try await c.person(id: person.id)
            failed = false
        } catch {
            failed = true
        }
        let d = await facts
        withAnimation(.easeOut(duration: 0.2)) { details = d }
        loaded = true
    }
}
