import Foundation
import UIKit
import SwiftUI
import DreamCore
import DreamUI

/// Scrub previews: small frames of the playing title, shown above the scrubber's thumb while dragging.
/// Filled in the background by a second, lightweight decoder (MPVThumbnailer) once playback is stable, at low
/// priority and only while the main picture has plenty buffered, so it never hurts playback. While the viewer
/// drags, the frame under the thumb jumps the queue. The UI only reads `frame(near:)` and calls `want(_:)`.
@MainActor
public final class ScrubThumbnails: ObservableObject {
    struct Frame {
        let image: UIImage
        /// Where the frame really is (a keyframe at or just before its grid spot).
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
    private var grid = 0
    /// Asks the worker for one grid spot (set by the engine while a worker runs).
    var onWant: ((Int) -> Void)?
    /// The viewer wants a frame before the worker runs (the engine may start it early).
    var onFirstWant: (() -> Void)?

    init() {}

    /// A new stream: forget every frame.
    func reset() {
        frames = [:]
        grid = 0
        enabled = false
        onWant = nil
        version &+= 1
    }

    /// The grid for a stream of `duration` seconds (spots `interval` apart, frames `aspect` wide).
    func prepare(grid: Int, interval: Double, aspect: CGFloat) {
        self.grid = grid
        self.interval = interval
        self.aspect = aspect
        frames = [:]
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
        frames[index] = Frame(image: UIImage(cgImage: image), time: time)
        version &+= 1
    }

    var count: Int { frames.count }

    func index(for t: Double) -> Int {
        guard grid > 0 else { return 0 }
        return min(grid - 1, max(0, Int(t / interval)))
    }

    /// The closest frame to `t` within a few grid spots (farther ones would show the wrong scene).
    func frame(near t: Double) -> Frame? {
        guard enabled, !frames.isEmpty else { return nil }
        let i = index(for: t)
        var best: Frame?
        for d in 0...3 {
            for j in d == 0 ? [i] : [i - d, i + d] {
                guard let f = frames[j] else { continue }
                if best == nil || abs(f.time - t) < abs(best!.time - t) { best = f }
            }
            if best != nil, d >= 1 { break }
        }
        return best
    }

    /// The viewer is dragging at `t`: that spot (and its neighbours) first.
    func want(_ t: Double) {
        guard grid > 0 || onFirstWant != nil else { return }
        if onWant == nil { onFirstWant?(); return }
        let i = index(for: t)
        for j in [i + 1, i - 1, i] where j >= 0 && j < grid && frames[j] == nil { onWant?(j) }
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
