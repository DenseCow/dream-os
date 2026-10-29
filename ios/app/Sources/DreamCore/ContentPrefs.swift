import Foundation

/// Settings › Keep Anime in Anime Sections, per profile, on by default (Theme.ANIME_APART on the Fire Stick). On, anime
/// shows only in the anime sections and personal rows; off, in every row, chart and genre search (DreamContent's RowMix).
public enum AnimePrefs {
    public static var apart: Bool {
        get { DreamStore.shared.get(Bool.self, "anime.apart", perProfile: true) ?? true }
        set { DreamStore.shared.set(newValue, "anime.apart", perProfile: true) }
    }
}
