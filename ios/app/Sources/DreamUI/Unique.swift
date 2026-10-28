import Foundation

public extension Array {
    /// The array without repeats of `key`, first one kept, order unchanged. SwiftUI's lazy stacks assert (crash) when a
    /// ForEach meets the same id twice while scrolling, and TMDB lists can repeat a title.
    func uniqued<K: Hashable>(by key: (Element) -> K) -> [Element] {
        var seen = Set<K>()
        return filter { seen.insert(key($0)).inserted }
    }
}

public extension Array where Element: Identifiable {
    func uniqued() -> [Element] { uniqued(by: \.id) }
}
