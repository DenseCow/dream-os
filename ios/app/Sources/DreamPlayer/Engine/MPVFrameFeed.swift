#if canImport(Libmpv)
import Foundation
import UIKit
import AVFoundation
import CoreMedia
import CoreVideo
import Accelerate
import QuartzCore
import Libmpv
import DreamCore

// Picture in Picture for libmpv. iOS can only float an AVPlayerLayer or an AVSampleBufferDisplayLayer, and mpv
// normally draws straight into its own Metal layer. So while Picture in Picture is on, mpv switches to its
// render API (`vo=libmpv`, software renderer): every frame (with subtitles) is drawn at window size into an
// IOSurface pixel buffer and handed to an AVSampleBufferDisplayLayer, which the system floats. CPU only, so
// it is allowed in the background, and small (at most 1280 wide), so it stays cheap. Decoding stays on
// VideoToolbox (`videotoolbox-copy`). When the window closes, mpv goes back to gpu-next on the Metal layer.

/// A view whose layer is the AVSampleBufferDisplayLayer Picture in Picture floats.
final class SampleBufferView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isUserInteractionEnabled = false
        displayLayer.videoGravity = .resizeAspect
    }
    required init?(coder: NSCoder) { fatalError() }
}

/// mpv's software render context -> CVPixelBuffers -> the sample buffer layer. Rendering runs on its own
/// serial queue (mpv's update callback only schedules it); everything mutable sits behind `lock`.
final class MPVFrameFeed: @unchecked Sendable {
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "dreamos.mpv.pip", qos: .userInteractive)
    /// The layer's renderer: safe to feed from a background queue (AVSampleBufferDisplayLayer.h).
    private let renderer: AVSampleBufferVideoRenderer
    private var ctx: OpaquePointer?
    private var pool: CVPixelBufferPool?
    private var poolW = 0, poolH = 0
    private var format: CMVideoFormatDescription?
    private var width = 960, height = 540
    private var pending = false
    private var feeding = false
    private var firstSent = false
    /// Called (on the main queue) after the first real frame of a feeding run reached the layer.
    var onFirstFrame: (@Sendable () -> Void)?
    let timebase: CMTimebase?

    @MainActor init(layer: AVSampleBufferDisplayLayer) {
        renderer = layer.sampleBufferRenderer
        var tb: CMTimebase?
        CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &tb)
        timebase = tb
        if let tb {
            CMTimebaseSetTime(tb, time: .zero)
            CMTimebaseSetRate(tb, rate: 0)
            layer.controlTimebase = tb
        }
    }

    var hasContext: Bool { lock.withLock { ctx != nil } }

    /// Creates the software render context once (mpv allows one per handle). False if mpv refused.
    ///
    /// `lock` is never held across a libmpv call here: `mpv_render_context_set_update_callback` calls the
    /// callback once right away on the calling thread (render.c), and the callback takes `lock` in `schedule()`.
    /// Holding it deadlocked the main thread on the PiP button (crash report 2026-10-27 21:16, 0x8BADF00D).
    func attach(to handle: OpaquePointer) -> Bool {
        if hasContext { return true }
        var out: OpaquePointer?
        let r: Int32 = "sw".withCString { api in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(mutating: api)),
                mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
            ]
            return params.withUnsafeMutableBufferPointer { mpv_render_context_create(&out, handle, $0.baseAddress) }
        }
        guard r >= 0, let c = out else {
            Log.player.error("PiP: render context failed: \(String(cString: mpv_error_string(r)), privacy: .public)")
            return false
        }
        lock.withLock { ctx = c }
        mpv_render_context_set_update_callback(c, { ud in
            guard let ud else { return }
            Unmanaged<MPVFrameFeed>.fromOpaque(ud).takeUnretainedValue().schedule()
        }, Unmanaged.passUnretained(self).toOpaque())
        Log.player.info("PiP: software render context ready")
        return true
    }

    /// Frees the render context (before mpv itself is destroyed). Waits for a render in progress.
    func detach() {
        queue.sync {
            lock.lock()
            let c = ctx
            ctx = nil
            feeding = false
            lock.unlock()
            if let c {
                mpv_render_context_set_update_callback(c, nil, nil)
                mpv_render_context_free(c)
            }
        }
    }

    /// Start / stop handing frames to the layer (mpv's frames are rendered either way while vo=libmpv).
    func setFeeding(_ on: Bool) {
        let sent: Int = lock.withLock {
            feeding = on
            if on { firstSent = false; feedStartedAt = CACurrentMediaTime(); framesSent = 0 }
            return framesSent
        }
        if !on, sent > 0 { Log.player.info("PiP: feed stopped after \(sent, privacy: .public) frames") }
    }
    private var feedStartedAt: CFTimeInterval = 0
    private var framesSent = 0
    /// Frames handed to the layer in this feeding run (diagnostics).
    var frameCount: Int { lock.withLock { framesSent } }

    /// The size frames are drawn at (pixels, even numbers).
    func setSize(width w: Int, height h: Int) {
        lock.withLock {
            width = max(64, w & ~1)
            height = max(36, h & ~1)
        }
    }

    /// The layer's clock, so the floating window's progress bar and play state are right.
    func syncClock(position: Double, rate: Double) {
        guard let tb = timebase else { return }
        let now = CMTimebaseGetTime(tb).seconds
        if abs(CMTimebaseGetRate(tb) - rate) > 0.001 { CMTimebaseSetRate(tb, rate: rate) }
        if !now.isFinite || abs(now - position) > 0.75 {
            CMTimebaseSetTime(tb, time: CMTime(seconds: max(0, position), preferredTimescale: 1000))
        }
    }

    /// Drops whatever the layer holds (back to the Metal picture).
    func flush() {
        queue.async { [self] in renderer.flush() }
    }

    /// One black frame so the layer has content (Picture in Picture wants a layer that shows something).
    func prime() {
        queue.async { [self] in
            guard let pb = makeBuffer(width: 320, height: 180) else { return }
            CVPixelBufferLockBaseAddress(pb, [])
            if let base = CVPixelBufferGetBaseAddress(pb) {
                memset(base, 0, CVPixelBufferGetBytesPerRow(pb) * CVPixelBufferGetHeight(pb))
                fillAlpha(base, width: 320, height: 180, stride: CVPixelBufferGetBytesPerRow(pb))
            }
            CVPixelBufferUnlockBaseAddress(pb, [])
            enqueue(pb)
        }
    }

    // MARK: Rendering (render queue)

    private func schedule() {
        lock.lock()
        if pending { lock.unlock(); return }
        pending = true
        lock.unlock()
        queue.async { [self] in render() }
    }

    private func render() {
        lock.lock()
        pending = false
        let c = ctx, w = width, h = height, feed = feeding
        lock.unlock()
        guard let c else { return }
        let flags = mpv_render_context_update(c)
        guard flags & UInt64(MPV_RENDER_UPDATE_FRAME.rawValue) != 0 else { return }
        guard let pb = makeBuffer(width: w, height: h) else { return }
        CVPixelBufferLockBaseAddress(pb, [])
        guard let base = CVPixelBufferGetBaseAddress(pb) else { CVPixelBufferUnlockBaseAddress(pb, []); return }
        var stride = CVPixelBufferGetBytesPerRow(pb)
        var size: [Int32] = [Int32(w), Int32(h)]
        // Frames are shown at once (DisplayImmediately): never block in render() for mpv's target time, so a
        // detach() waiting on this queue can't stall.
        var block: Int32 = 0
        let r: Int32 = "bgr0".withCString { fmt in
            size.withUnsafeMutableBufferPointer { sp in
                withUnsafeMutablePointer(to: &stride) { st in
                    withUnsafeMutablePointer(to: &block) { bp in
                        var params = [
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_SIZE, data: UnsafeMutableRawPointer(sp.baseAddress)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_FORMAT, data: UnsafeMutableRawPointer(mutating: fmt)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_STRIDE, data: UnsafeMutableRawPointer(st)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_POINTER, data: base),
                            mpv_render_param(type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, data: UnsafeMutableRawPointer(bp)),
                            mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                        ]
                        return params.withUnsafeMutableBufferPointer { mpv_render_context_render(c, $0.baseAddress) }
                    }
                }
            }
        }
        // "bgr0" leaves the fourth byte undefined; make it opaque so the layer never sees a see-through frame.
        if r >= 0 { fillAlpha(base, width: w, height: h, stride: stride) }
        CVPixelBufferUnlockBaseAddress(pb, [])
        mpv_render_context_report_swap(c)
        if r < 0 {
            lock.lock(); let n = renderErrors; renderErrors += 1; lock.unlock()
            if n < 3 { Log.player.error("PiP: render failed: \(String(cString: mpv_error_string(r)), privacy: .public)") }
            return
        }
        guard feed else { return }
        enqueue(pb)
        lock.lock()
        let first = !firstSent
        firstSent = true
        framesSent += 1
        let cb = onFirstFrame
        let waited = CACurrentMediaTime() - feedStartedAt
        lock.unlock()
        if first {
            Log.player.info("PiP: first software frame \(w, privacy: .public)x\(h, privacy: .public) after \(Int(waited * 1000), privacy: .public) ms")
            if let cb { DispatchQueue.main.async { cb() } }
        }
    }
    private var renderErrors = 0

    private func fillAlpha(_ base: UnsafeMutableRawPointer, width w: Int, height h: Int, stride: Int) {
        var buf = vImage_Buffer(data: base, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: stride)
        // BGRA in memory: alpha is the fourth channel (mask bit 0x1).
        vImageOverwriteChannelsWithScalar_ARGB8888(255, &buf, &buf, 0x1, vImage_Flags(kvImageNoFlags))
    }

    private func makeBuffer(width w: Int, height h: Int) -> CVPixelBuffer? {
        if pool == nil || poolW != w || poolH != h {
            let attrs: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: w,
                kCVPixelBufferHeightKey: h,
                kCVPixelBufferBytesPerRowAlignmentKey: 64,
                kCVPixelBufferIOSurfacePropertiesKey: [String: Any](),
            ]
            var p: CVPixelBufferPool?
            CVPixelBufferPoolCreate(kCFAllocatorDefault, [kCVPixelBufferPoolMinimumBufferCountKey: 4] as CFDictionary, attrs as CFDictionary, &p)
            pool = p; poolW = w; poolH = h; format = nil
        }
        guard let pool else { return nil }
        var pb: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pb)
        return pb
    }

    private func enqueue(_ pb: CVPixelBuffer) {
        if format == nil || !(format.map { CMVideoFormatDescriptionMatchesImageBuffer($0, imageBuffer: pb) } ?? false) {
            var f: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescriptionOut: &f)
            format = f
        }
        guard let format else { return }
        let pts = timebase.map { CMTimebaseGetTime($0) } ?? CMClockGetTime(CMClockGetHostTimeClock())
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescription: format,
                                                 sampleTiming: &timing, sampleBufferOut: &sb)
        guard let sb else { return }
        if let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(atts) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(atts, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding { renderer.flush() }
        renderer.enqueue(sb)
    }
}
#endif
