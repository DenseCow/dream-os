import Foundation
import DreamCore

/// "Now Available in 4K" (Availability.kt `nowAvailable`): a My List movie went from CAM Only / Not Available Yet to
/// Available Now, one notice ("Now Available in 4K", the movie's name), kept in Notifications. Dream Notices holds its
/// banner while Dream Player is up, so never during playback. A followed show's new episode has its own notice
/// (NewEpisodeSpot). DreamContent's availability checker reports the flip through `AvailabilityNews`.
enum NowAvailableNotices {
    @MainActor static func install() {
        AvailabilityNews.onNowAvailable = { title, best in
            guard title.kind == .movie else { return }
            Task { @MainActor in
                guard await Services.shared.myList.contains(title.key) else { return }
                let res = AvailabilityDetail(state: .availableNow, best: best).resolution
                let head = "Now Available" + (res.map { " in \($0)" } ?? "")
                if NoticeCenter.shared.post(key: "avail:movie:\(title.id)", type: .episodes, title: head, body: title.name,
                                            action: .init(label: "Go to Movie", target: NoticeActions.titleTarget(title))) {
                    Log.app.notice("Avail: notice \"\(head, privacy: .public)  ·  \(title.name, privacy: .public)\"")
                }
            }
        }
    }
}
