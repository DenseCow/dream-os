import SwiftUI
import DreamCore
import DreamUI

/// Where a tap can take you inside a tab's NavigationStack (pages push with the system's back swipe).
enum Route: Hashable {
    case title(Title)
    case page(CatalogPage)
    case person(Int, String)
}

/// A route's page. Title pages and people are the Pages worker's (TitleDetailsView, PersonView).
struct RouteView: View {
    let route: Route
    var body: some View {
        switch route {
        case .title(let t): TitleDetailsView(title: t)
        case .page(.season(let id)): HubPageView(id: id)
        case .page(let p): CatalogPageView(page: p)
        case .person(let id, let name): PersonView(person: CastMember(id: id, name: name, character: ""))
        }
    }
}

extension CatalogPage {
    /// The page's name in its bar ("Movies", "Netflix", "Horror").
    var name: String {
        switch self {
        case .home: "Home"
        case .movies: "Movies"
        case .shows: "TV Shows"
        case .anime: "Anime"
        case .kids: "Kids"
        case .service(let s): s.name
        case .genre(let g): g.name
        case .season(let id): id.split(separator: "-").map { $0.capitalized }.joined(separator: " ")
        }
    }
}

extension CatalogItem {
    /// What Play asks for: the title, and the episode the card points at.
    var playRequest: PlayRequest {
        PlayRequest(title: title, season: episode?.season, episode: episode?.number, episodeName: episode?.name)
    }
}
