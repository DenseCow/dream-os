import SwiftUI
import os
import DreamCore
import DreamUI

/// Dream Player module entry (engine, controls, subtitles, skip, post-play). See docs/port/player.md.
public enum DreamPlayerModule {}

extension Log {
    static let player = Logger(subsystem: "com.dreamos", category: "DreamPlayer")
}
