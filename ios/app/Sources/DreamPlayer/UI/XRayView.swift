import SwiftUI
import DreamCore
import DreamUI

/// X-Ray (fire-home PlayerXRay): who is in what's playing (an episode's own cast and guest stars, `catalog.cast(for:)`)
/// over the running picture; a face opens the person's card (born / died, the start of the biography, Known For),
/// pausing playback, resumed on the way back; Known For opens a title page.
struct XRayView: View {
    @ObservedObject var session: PlayerSession
    @Binding var isOpen: Bool
    @State private var cast: [CastMember]?
    @State private var person: CastMember?
    @State private var facts: PersonDetails?
    @State private var knownFor: [Title]?
    @State private var knownForFailed = false
    @State private var pausedForCard = false

    var body: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black.opacity(0.58), location: 0.5),
                                   .init(color: .black.opacity(0.86), location: 1)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { back() }
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(kicker).font(.system(size: 12, weight: .bold)).tracking(2).textCase(.uppercase).foregroundStyle(.white.opacity(0.62)).lineLimit(1)
                        Text(person == nil ? (session.request?.episode != nil ? "In This Episode" : "Cast") : (person?.name ?? ""))
                            .font(.system(size: 24, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                    }
                    Spacer()
                    Button { back() } label: { Image(systemName: person == nil ? "xmark" : "chevron.backward").font(.system(size: 14, weight: .semibold)) }
                        .buttonStyle(RoundGlassStyle(size: 36)).accessibilityLabel(person == nil ? "Close" : "Back")
                }
                if let p = person { card(p) } else { strip }
            }
            .padding(.horizontal, 28).padding(.bottom, 20)
        }
        .transition(.opacity)
        .task {
            if let have = session.xrayCast { cast = Array(have.prefix(24)) }
            let list = await session.castForXRay()
            guard isOpen else { return }
            if list.isEmpty {
                session.show(toast: "Cast details aren't available for this title")
                isOpen = false
            } else {
                cast = Array(list.prefix(24))
            }
        }
    }

    private var kicker: String {
        guard let r = session.request else { return "" }
        if let s = r.season, let e = r.episode { return "\(r.title.name)  ·  S\(s) · E\(e)" }
        return r.title.name
    }

    private var strip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 10) {
                if let cast {
                    ForEach(cast) { c in
                        Button { open(c) } label: {
                            VStack(spacing: 6) {
                                Face(member: c, size: 92)
                                Text(c.name).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.center)
                                Text(c.character).font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.6)).lineLimit(2)
                                    .multilineTextAlignment(.center).opacity(c.character.isEmpty ? 0 : 1)
                            }
                            .frame(width: 116)
                        }
                        .buttonStyle(CardPress(radius: 46))
                        .accessibilityLabel(c.character.isEmpty ? c.name : "\(c.name) as \(c.character)")
                    }
                } else {
                    // Still looking up: the faces' places, so the strip doesn't jump when they arrive.
                    ForEach(0..<7, id: \.self) { _ in
                        VStack(spacing: 8) {
                            Circle().fill(.white.opacity(0.06)).frame(width: 92, height: 92)
                            RoundedRectangle(cornerRadius: 4).fill(.white.opacity(0.05)).frame(width: 78, height: 12)
                        }
                        .frame(width: 116)
                    }
                    .accessibilityHidden(true)
                }
            }
            .padding(.vertical, 6)
        }
    }

    private func card(_ p: CastMember) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 18) {
                Face(member: p, size: 96)
                VStack(alignment: .leading, spacing: 6) {
                    if !p.character.isEmpty {
                        Text("As \(p.character)").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    }
                    if let meta = facts?.meta, !meta.isEmpty {
                        Text(meta).font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.64)).lineLimit(1)
                    }
                    if let bio = facts?.bio, !bio.isEmpty {
                        // Ends on a whole sentence (PlayerXRay fitBio): about three lines in landscape.
                        Text(PersonDetails.bioLead(bio, maxChars: 200))
                            .font(.system(size: 14)).foregroundStyle(.white.opacity(0.80)).lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: 520, alignment: .leading)
                    }
                }
            }
            Text("Known For").font(.system(size: 13, weight: .bold)).tracking(1.6).textCase(.uppercase).foregroundStyle(.white.opacity(0.55))
                .padding(.top, 4)
            if knownForFailed {
                Text("Couldn't load this person's titles right now.").font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.6))
            } else if let k = knownFor, k.isEmpty {
                Text("No other titles to show").font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.6))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        if let k = knownFor {
                            ForEach(k) { t in
                                Button { haptic(.light); DreamPlayerPresenter.shared.open(t) } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        AsyncImage(url: t.poster("w185")) { ph in
                                            if let img = ph.image { img.resizable().scaledToFill() } else { Color.white.opacity(0.06) }
                                        }
                                        .frame(width: 84, height: 126).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                        Text(t.name).font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.8)).lineLimit(2).frame(width: 84, alignment: .leading)
                                    }
                                }
                                .buttonStyle(CardPress(radius: 10))
                            }
                        } else {
                            ForEach(0..<8, id: \.self) { _ in RoundedRectangle(cornerRadius: 10).fill(.white.opacity(0.05)).frame(width: 84, height: 126) }
                        }
                    }
                }
            }
        }
    }

    private func open(_ c: CastMember) {
        haptic(.light)
        person = c
        facts = nil
        knownFor = nil
        knownForFailed = false
        if session.state.isPlaying { session.engine.pause(); pausedForCard = true }
        let playing = session.request?.title.key
        Task {
            let catalog = Services.shared.catalog
            // Facts, biography and Known For in one lookup (XRayData.person), without the title playing.
            if let p = await catalog.personDetails(id: c.id, excluding: playing) {
                guard person?.id == c.id else { return }
                facts = p
                knownFor = p.knownFor
                return
            }
            // No facts from the catalog: their titles alone, by the same rules.
            do {
                let all = try await catalog.person(id: c.id)
                guard person?.id == c.id else { return }
                let withPoster = all.filter { $0.posterPath != nil && !$0.adult && !$0.genreIds.contains(10767) && !$0.genreIds.contains(10763) }
                let popular = withPoster.filter { $0.votes >= 20 }
                let base = (popular.count >= 4 ? popular : withPoster).sorted { $0.votes > $1.votes }
                knownFor = Array(base.filter { $0.key != playing }.prefix(14))
            } catch {
                guard person?.id == c.id else { return }
                knownForFailed = true
            }
        }
    }

    private func back() {
        if person != nil {
            person = nil
            if pausedForCard { pausedForCard = false; session.engine.play() }
        } else {
            isOpen = false
        }
    }
}

/// A round headshot, faces kept high in the crop; initials when there's no photo.
struct Face: View {
    let member: CastMember
    let size: CGFloat
    var body: some View {
        ZStack {
            Circle().fill(Color(white: 0.10))
            Text(initials).font(.system(size: size * 0.32, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
            if let url = member.profile("w185") {
                AsyncImage(url: url) { ph in
                    if let img = ph.image {
                        img.resizable().scaledToFill().frame(width: size, height: size * 1.5).offset(y: size * 0.20)
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
    private var initials: String {
        member.name.split(separator: " ").prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
}
