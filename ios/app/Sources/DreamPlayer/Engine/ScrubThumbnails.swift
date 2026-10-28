import Foundation
import UIKit
import SwiftUI
import QuartzCore
import DreamCore
import DreamUI

/// Scrub previews: small frames of the playing title, shown above the scrubber's thumb while dragging.
/// Filled by small second decoders (MPVThumbnailer, sharing one ThumbDesk) once playback is steady, at low
/// priority and only while the main picture is healthy. While the viewer drags, the card always shows the nearest
/// frame already made (never blank), the spot under the thumb jumps the queue, and its own frame swaps in when it
/// lands. The UI only reads `frame(near:)` and calls `want(_:)`.
@MainActor
public final class ScrubThumbnails: ObservableObject {
    struct Frame {
        let image: UIImage
        /// Where the frame really is (a keyframe at or near its grid spot).
        let time: Double
    }

    /// A grid exists for this stream (the preview card shows an image box).
    @Published private(set) var enabled = false
    /// Bumped when a frame lands (the open preview card redraws).
    @Published private(set) var version = 0
    private(set) var interval: Double = 10
    /// The picture's shape (width / height).
    private(set) var aspect: CGFloat = 16 / 9
    private var frames: [Int: Frame] = [:]
    /// Grabbed spots in order, for the nearest-frame lookup while dragging.
    private var sorted: [Int] = []
    private var grid = 0
    /// Asks the decoders for the spot under the thumb and the next one in the drag direction (set while they run).
    var onWant: ((Int, Int?) -> Void)?
    /// The viewer wants a frame before the decoders run (the engine may start them early).
    var onFirstWant: (() -> Void)?
    // Drag measurements for the log.
    private var lastWant: (spot: Int, at: CFTimeInterval)?
    private var lastSpot: Int?
    private var wants = 0, ownAtOnce = 0, waits: [Double] = []

    init() {}

    /// A new stream: forget every frame.
    func reset() {
        noteDragSummary()
        frames = [:]
        sorted = []
        grid = 0
        enabled = false
        onWant = nil
        lastWant = nil; lastSpot = nil
        version &+= 1
    }

    /// The grid for a stream (spots `interval` apart, frames `aspect` wide).
    func prepare(grid: Int, interval: Double, aspect: CGFloat) {
        self.grid = grid
        self.interval = interval
        self.aspect = aspect
        frames = [:]
        sorted = []
        enabled = grid > 0
        version &+= 1
    }

    func setAspect(_ a: CGFloat) {
        guard a.isFinite, a > 0.5, a < 4, abs(a - aspect) > 0.01 else { return }
        aspect = a
        version &+= 1
    }

    func add(_ index: Int, _ image: CGImage, time: Double) {
        guard enabled, index >= 0, index < grid else { return }
        if frames[index] == nil {
            let at = sorted.firstIndex { $0 > index } ?? sorted.count
            sorted.insert(index, at: at)
        }
        frames[index] = Frame(image: UIImage(cgImage: image), time: time)
        if let w = lastWant, w.spot == index {
            // The spot the thumb last asked for: how long the viewer looked at a nearby frame instead.
            let ms = (CACurrentMediaTime() - w.at) * 1000
            waits.append(ms)
            lastWant = nil
            Log.player.info("Thumbs: dragged spot \(Labels.time(Double(index) * self.interval), privacy: .public) shown \(Int(ms), privacy: .public) ms after the thumb reached it")
        }
        version &+= 1
    }

    var count: Int { frames.count }

    func index(for t: Double) -> Int {
        guard grid > 0 else { return 0 }
        return min(grid - 1, max(0, Int(t / interval)))
    }

    /// The closest frame to `t` already made, however far (the card is never blank once one exists); its own
    /// frame replaces it as soon as it lands.
    func frame(near t: Double) -> Frame? {
        guard enabled, !sorted.isEmpty else { return nil }
        let i = index(for: t)
        if let f = frames[i] { return f }
        // Binary search for the first grabbed spot after i; the best is it or the one before.
        var lo = 0, hi = sorted.count
        while lo < hi { let m = (lo + hi) / 2; if sorted[m] < i { lo = m + 1 } else { hi = m } }
        var best: Frame?
        for k in [lo - 1, lo] where k >= 0 && k < sorted.count {
            guard let f = frames[sorted[k]] else { continue }
            if best == nil || abs(f.time - t) < abs(best!.time - t) { best = f }
        }
        return best
    }

    /// The viewer is dragging at `t`: that spot first, then the next one in the drag direction.
    func want(_ t: Double) {
        guard grid > 0 || onFirstWant != nil else { return }
        if onWant == nil { onFirstWant?(); return }
        let i = index(for: t)
        let dir = lastSpot.map { i > $0 ? 1 : i < $0 ? -1 : 0 } ?? 0
        lastSpot = i
        wants += 1
        if frames[i] != nil {
            ownAtOnce += 1
            lastWant = nil
        } else {
            lastWant = (i, CACurrentMediaTime())
        }
        var ahead: Int? = dir != 0 ? i + dir : nil
        if let a = ahead, frames[a] != nil || a < 0 || a >= grid { ahead = nil }
        onWant?(i, ahead)
        if wants % 25 == 0 { noteDragSummary() }
    }

    private func noteDragSummary() {
        guard wants > 0 else { return }
        let w = waits.sorted()
        let median = w.isEmpty ? 0 : Int(w[w.count / 2])
        Log.player.info("Thumbs: drag summary: \(self.ownAtOnce, privacy: .public)/\(self.wants, privacy: .public) spots had their own frame at once, the rest a nearby one; own frame landed after median \(median, privacy: .public) ms (\(w.count, privacy: .public) timed), \(self.frames.count, privacy: .public)/\(self.grid, privacy: .public) frames made")
        wants = 0; ownAtOnce = 0; waits = []
    }
}


// MARK: - The preview card

/// The card above the scrubber's thumb: the frame (rounded, hairline edge), then the time and its chapter
/// ("Intro", "Credits") or the jump ("+2:30"). Without previews for this stream it is the plain readout.
struct ScrubPreviewCard: View {
    @ObservedObject var thumbs: ScrubThumbnails
    let time: Double
    let label: String

    static let imageWidth: CGFloat = 168

    static func imageHeight(_ aspect: CGFloat) -> CGFloat { min(100, max(72, (imageWidth / aspect).rounded())) }
    /// Card height: padding 6 + image + gap 8 + time (24) + 2 + label (15) + padding 8.
    static func height(_ aspect: CGFloat) -> CGFloat { imageHeight(aspect) + 63 }
    static let width: CGFloat = imageWidth + 12

    var body: some View {
        let h = Self.imageHeight(thumbs.aspect)
        let f = thumbs.frame(near: time)
        VStack(spacing: 0) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(white: 0.12))
                if let f {
                    Image(uiImage: f.image).resizable().interpolation(.medium).aspectRatio(contentMode: .fill)
                        .frame(width: Self.imageWidth, height: h)
                        .transition(.opacity)
                } else {
                    Image(systemName: "film").font(.system(size: 20, weight: .regular)).foregroundStyle(.white.opacity(0.22))
                }
            }
            .frame(width: Self.imageWidth, height: h)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            // The TV's preview frame: a soft glow edge in the rainbow's current hue (the same hue as the played bar).
            .overlay {
                let hue = Rainbow.fadeColor()
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(hue.opacity(0.55), lineWidth: 1)
                    .shadow(color: hue.opacity(0.42), radius: 8)
            }
            .animation(.easeOut(duration: 0.12), value: f?.time)
            Text(Labels.time(time)).font(.system(size: 20, weight: .semibold)).monospacedDigit()
                .foregroundStyle(.white).lineLimit(1)
                .frame(height: 24).padding(.top, 8)
            Text(label).font(.system(size: 12, weight: .medium)).monospacedDigit().foregroundStyle(.white.opacity(0.72))
                .lineLimit(1).frame(height: 15).padding(.top, 2)
        }
        .padding(.top, 6).padding(.horizontal, 6).padding(.bottom, 8)
        .frame(width: Self.width, height: Self.height(thumbs.aspect))
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(white: 0.06).opacity(0.95)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.white.opacity(0.10), lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 14, y: 4)
        .onAppear { thumbs.want(time) }
        .onChange(of: thumbs.index(for: time)) { _, _ in thumbs.want(time) }
    }
}
