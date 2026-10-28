#if canImport(Libmpv)
import Foundation
import CoreGraphics
import QuartzCore
import Libmpv
import DreamCore

/// The scrub previews' decoder: a second, small libmpv that opens the same stream with no sound, no
/// subtitles and no read-ahead, jumps to keyframes (no exact seeks) and draws each one with mpv's software
/// renderer at preview size. It runs on its own utility-priority thread (mpv's own threads, created from it,
/// start at that priority too) and only grabs while `gate` allows: the main player says when it has plenty
/// buffered. HDR frames are brought down to normal colours here (the software renderer doesn't tone map).
final class MPVThumbnailer: @unchecked Sendable {
    /// The main player's health: closed = no grabs, wants = only spots the viewer is dragging over, open = also
    /// the background walk over the whole timeline.
    enum Gate: Sendable { case closed, wants, open }

    struct Config: Sendable {
        var url: URL
        var headers: [String: String]
        var userAgent: String
        var duration: Double
        var grid: Int
        var interval: Double
        /// Grab width in pixels (height follows the picture).
        var width: Int
        /// Walk the whole timeline in the background (Wi-Fi), or only grab what the viewer drags over.
        var walk: Bool
        /// Where playback is (the walk starts around it).
        var near: Double
        /// A video filter for this stream ("format=..." for Dolby Vision with an HDR10 base).
        var filter: String?
    }

    private let config: Config
    private let cond = NSCondition()
    private var wants: [Int] = []
    private var order: [Int] = []
    private var done = Set<Int>()
    private var gate: Gate = .closed
    private var cancelled = false
    private var handle: OpaquePointer?
    private var ctx: OpaquePointer?
    private var thread: Thread?
    private var outW = 288, outH = 162
    private var tone: ThumbTone = .sdr
    private var colourChecked = false

    /// A frame is ready (worker thread).
    var onFrame: (@Sendable (Int, CGImage, Double, Double) -> Void)?
    /// The worker stopped by itself (worker thread): the reason, for the log.
    var onEnd: (@Sendable (String) -> Void)?

    init(_ config: Config) {
        self.config = config
        order = Self.walkOrder(grid: config.grid, near: config.grid > 0 ? min(config.grid - 1, Int(config.near / config.interval)) : 0)
    }

    func start() {
        let t = Thread { [self] in run() }
        t.name = "dreamos.thumbs"
        t.qualityOfService = .utility
        t.stackSize = 1 << 20
        thread = t
        t.start()
    }

    func setGate(_ g: Gate) {
        cond.lock(); gate = g; cond.signal(); cond.unlock()
    }

    func want(_ i: Int) {
        cond.lock()
        if !done.contains(i) {
            wants.removeAll { $0 == i }
            wants.append(i)
            if wants.count > 8 { wants.removeFirst(wants.count - 8) }
            cond.signal()
        }
        cond.unlock()
    }

    /// Stops the worker; mpv is torn down on its thread.
    func cancel() {
        cond.lock(); cancelled = true; cond.signal(); cond.unlock()
    }

    // MARK: Order

    /// The spots around playback first (the likeliest jumps), then coarse to fine over the whole timeline,
    /// so early previews already cover everything roughly.
    static func walkOrder(grid: Int, near: Int) -> [Int] {
        guard grid > 0 else { return [] }
        var out: [Int] = []
        var seen = Set<Int>()
        func add(_ i: Int) { if i >= 0, i < grid, seen.insert(i).inserted { out.append(i) } }
        for d in [0, -1, 1, -2, 2, -3, 3, -5, 5] { add(near + d) }
        var step = 1
        while step * 2 < grid { step *= 2 }
        while step >= 1 {
            var i = step / 2
            while i < grid { add(i); i += step }
            step /= 2
        }
        for i in 0..<grid { add(i) }
        return out
    }

    // MARK: Worker thread

    private func run() {
        let began = CACurrentMediaTime()
        guard open() else {
            onEnd?("couldn't open the stream")
            teardown()
            return
        }
        // The file opened at the first spot: its first frame is the first grab.
        let first = order.first ?? 0
        var firstOK = land(first)
        if !firstOK, config.filter != nil, !isCancelled {
            // The Dolby Vision clean-up filter was refused: try once without it.
            Log.player.info("Thumbs: no frame with the Dolby Vision filter, trying without it")
            command(["set", "vf", ""])
            firstOK = grab(first)
        }
        guard firstOK else {
            onEnd?("no first frame")
            teardown()
            return
        }
        cond.lock(); done.insert(first); cond.unlock()
        Log.player.info("Thumbs: first frame \(Int((CACurrentMediaTime() - began) * 1000), privacy: .public) ms after opening")
        var grabbed = 1, failures = 0, wanted = 0
        var lastNote = CACurrentMediaTime()
        var spent: Double = 0
        var walkDoneNoted = false
        while true {
            cond.lock()
            var job: Int?
            var fromWant = false
            while !cancelled {
                if gate != .closed, let w = wants.popLast() {
                    if done.contains(w) { continue }
                    job = w; fromWant = true; break
                }
                if gate == .open, config.walk {
                    while let n = order.first, done.contains(n) { order.removeFirst() }
                    if let n = order.first { order.removeFirst(); job = n; break }
                }
                cond.wait(until: Date().addingTimeInterval(1))
            }
            let stop = cancelled
            cond.unlock()
            if stop { break }
            guard let i = job else { continue }
            let t0 = CACurrentMediaTime()
            let ok = grab(i)
            spent += CACurrentMediaTime() - t0
            cond.lock(); done.insert(i); let left = config.walk ? order.count : 0; cond.unlock()
            if ok {
                grabbed += 1; failures = 0
                if fromWant { wanted += 1 }
            } else {
                failures += 1
                if failures >= 6 { onEnd?("6 grabs in a row failed"); break }
            }
            let walkDone = config.walk && left == 0 && !walkDoneNoted
            if walkDone { walkDoneNoted = true }
            if CACurrentMediaTime() - lastNote > 60 || walkDone {
                lastNote = CACurrentMediaTime()
                Log.player.info("Thumbs: \(grabbed, privacy: .public)/\(self.config.grid, privacy: .public) frames (\(wanted, privacy: .public) asked for while dragging), avg \(Int(spent / Double(max(1, grabbed + failures)) * 1000), privacy: .public) ms each, \(Int(CACurrentMediaTime() - began), privacy: .public) s in")
            }
            // Background walk: a short rest between grabs so the main picture's decoder and network stay first.
            if !fromWant {
                cond.lock()
                if wants.isEmpty && !cancelled { cond.wait(until: Date().addingTimeInterval(0.35)) }
                cond.unlock()
            }
        }
        teardown()
    }

    private func open() -> Bool {
        guard let h = mpv_create() else { return false }
        handle = h
        let opts: [(String, String)] = [
            ("vo", "libmpv"), ("ao", "null"), ("aid", "no"), ("sid", "no"),
            ("hwdec", "videotoolbox-copy"), ("hwdec-codecs", "h264,hevc,mpeg1video,mpeg2video,mpeg4,av1,prores"),
            ("pause", "yes"), ("keep-open", "always"), ("idle", "yes"), ("hr-seek", "no"),
            ("cache", "no"), ("demuxer-readahead-secs", "0"), ("demuxer-max-bytes", "4MiB"), ("demuxer-max-back-bytes", "0"),
            ("vd-lavc-skiploopfilter", "all"), ("vd-lavc-fast", "yes"), ("vd-lavc-threads", "2"),
            ("sws-scaler", "fast-bilinear"), ("video-rotate", "no"),
            ("config", "no"), ("load-scripts", "no"), ("ytdl", "no"), ("osc", "no"), ("osd-level", "0"),
            ("input-default-bindings", "no"), ("input-vo-keyboard", "no"), ("terminal", "no"),
            ("sub-auto", "no"), ("audio-file-auto", "no"), ("network-timeout", "12"), ("force-seekable", "yes"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=2"),
            ("user-agent", config.userAgent),
        ]
        for (k, v) in opts where mpv_set_option_string(h, k, v) < 0 {
            Log.player.error("Thumbs: option \(k, privacy: .public) refused")
        }
        if let f = config.filter { _ = mpv_set_option_string(h, "vf", f) }
        mpv_request_log_messages(h, "error")
        guard mpv_initialize(h) >= 0 else { return false }
        // The software render context must exist before the video output starts (vo=libmpv needs it).
        var out: OpaquePointer?
        let r: Int32 = "sw".withCString { api in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(mutating: api)),
                mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
            ]
            return params.withUnsafeMutableBufferPointer { mpv_render_context_create(&out, h, $0.baseAddress) }
        }
        guard r >= 0, let c = out else { return false }
        ctx = c
        for (k, v) in config.headers { command(["change-list", "http-header-fields", "append", "\(k): \(v)"]) }
        let first = order.first ?? 0
        command(["loadfile", config.url.absoluteString, "replace", "-1", "start=\(String(format: "%.3f", spot(first)))"])
        // Wait for the file (its first frame is the first grab's).
        let deadline = CACurrentMediaTime() + 20
        while CACurrentMediaTime() < deadline {
            if isCancelled { return false }
            guard let ev = mpv_wait_event(h, 0.1)?.pointee else { continue }
            switch ev.event_id {
            case MPV_EVENT_FILE_LOADED: return true
            case MPV_EVENT_END_FILE, MPV_EVENT_SHUTDOWN: return false
            case MPV_EVENT_LOG_MESSAGE: logMessage(ev)
            default: break
            }
        }
        return false
    }

    private var isCancelled: Bool { cond.lock(); defer { cond.unlock() }; return cancelled }

    private func spot(_ i: Int) -> Double {
        min(max(0, config.duration - 1), (Double(i) + 0.5) * config.interval)
    }

    /// One frame: keyframe seek, then the frame mpv lands on.
    private func grab(_ i: Int) -> Bool {
        command(["seek", String(format: "%.3f", spot(i)), "absolute+keyframes"])
        return land(i)
    }

    /// Waits until mpv has restarted near spot `i` and handed its frame over, then draws it. A restart that lands
    /// far from the spot belongs to an earlier seek that timed out; it is skipped and the wait goes on.
    private func land(_ i: Int) -> Bool {
        guard let h = handle, let c = ctx else { return false }
        let t = spot(i)
        let slack = max(12, config.interval)
        let deadline = CACurrentMediaTime() + 8
        var restarted = false
        var frameSince = false
        while CACurrentMediaTime() < deadline {
            if isCancelled { return false }
            if let ev = mpv_wait_event(h, 0.03)?.pointee {
                switch ev.event_id {
                case MPV_EVENT_PLAYBACK_RESTART: restarted = true
                case MPV_EVENT_END_FILE:
                    if let e = ev.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee,
                       e.reason == MPV_END_FILE_REASON_ERROR { return false }
                case MPV_EVENT_SHUTDOWN: return false
                case MPV_EVENT_LOG_MESSAGE: logMessage(ev)
                default: break
                }
            }
            if mpv_render_context_update(c) & UInt64(MPV_RENDER_UPDATE_FRAME.rawValue) != 0 {
                frameSince = true
                if !restarted {
                    // Consume it so mpv's video thread moves on; it is drawn again below.
                    _ = draw(c, w: 16, h: 16)
                }
            }
            if restarted && frameSince {
                let at = doubleProperty(h, "time-pos") ?? t
                guard abs(at - t) <= slack else { restarted = false; frameSince = false; continue }
                if !colourChecked { checkColour(h) }
                guard let img = draw(c, w: outW, h: outH) else { return false }
                onFrame?(i, img, at, Double(outW) / Double(outH))
                return true
            }
        }
        return false
    }

    /// The picture's shape and colours, read once from the first frame.
    private func checkColour(_ h: OpaquePointer) {
        colourChecked = true
        let dw = doubleProperty(h, "video-params/dw") ?? 16
        let dh = doubleProperty(h, "video-params/dh") ?? 9
        if dw > 0, dh > 0 {
            outW = max(64, config.width & ~1)
            outH = max(36, min(Int((Double(outW) * dh / dw).rounded()), outW) & ~1)
        }
        let gamma = stringProperty(h, "video-params/gamma") ?? ""
        let prim = stringProperty(h, "video-params/primaries") ?? ""
        let peak = doubleProperty(h, "video-params/max-cll").flatMap { $0 > 0 ? $0 : nil }
            ?? doubleProperty(h, "video-params/max-luma").flatMap { $0 > 0 ? $0 : nil } ?? 1000
        let wide = prim.contains("2020")
        switch gamma {
        case "pq": tone = .pq(peak: min(10000, max(400, peak)), wide: wide)
        case "hlg": tone = .hlg(wide: wide)
        default: tone = .sdr
        }
        Log.player.info("Thumbs: frames \(self.outW, privacy: .public)x\(self.outH, privacy: .public), colours \(gamma.isEmpty ? "?" : gamma, privacy: .public) / \(prim.isEmpty ? "?" : prim, privacy: .public)\(self.config.filter != nil ? " (Dolby Vision base layer)" : "", privacy: .public)")
    }

    /// Software render of the current frame (bgr0) into a CGImage, HDR toned down.
    private func draw(_ c: OpaquePointer, w: Int, h: Int) -> CGImage? {
        var stride = w * 4
        let buf = UnsafeMutableRawPointer.allocate(byteCount: stride * h, alignment: 64)
        var size: [Int32] = [Int32(w), Int32(h)]
        var block: Int32 = 0
        let r: Int32 = "bgr0".withCString { fmt in
            size.withUnsafeMutableBufferPointer { sp in
                withUnsafeMutablePointer(to: &stride) { st in
                    withUnsafeMutablePointer(to: &block) { bp in
                        var params = [
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_SIZE, data: UnsafeMutableRawPointer(sp.baseAddress)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_FORMAT, data: UnsafeMutableRawPointer(mutating: fmt)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_STRIDE, data: UnsafeMutableRawPointer(st)),
                            mpv_render_param(type: MPV_RENDER_PARAM_SW_POINTER, data: buf),
                            mpv_render_param(type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, data: UnsafeMutableRawPointer(bp)),
                            mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                        ]
                        return params.withUnsafeMutableBufferPointer { mpv_render_context_render(c, $0.baseAddress) }
                    }
                }
            }
        }
        mpv_render_context_report_swap(c)
        guard r >= 0, w > 16 else { buf.deallocate(); return nil }
        tone.apply(buf.assumingMemoryBound(to: UInt8.self), width: w, height: h, stride: stride)
        let data = Data(bytesNoCopy: buf, count: stride * h, deallocator: .custom { p, _ in p.deallocate() })
        guard let provider = CGDataProvider(data: data as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        // bgr0 in memory = 32-bit little-endian xRGB.
        let info = CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride, space: space,
                       bitmapInfo: info, provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    private func command(_ args: [String]) {
        guard let h = handle else { return }
        var cargs: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
        cargs.append(nil)
        defer { for p in cargs { free(UnsafeMutablePointer(mutating: p)) } }
        _ = cargs.withUnsafeMutableBufferPointer { mpv_command(h, $0.baseAddress) }
    }

    private func doubleProperty(_ h: OpaquePointer, _ name: String) -> Double? {
        var v: Double = 0
        return mpv_get_property(h, name, MPV_FORMAT_DOUBLE, &v) >= 0 ? v : nil
    }

    private func stringProperty(_ h: OpaquePointer, _ name: String) -> String? {
        guard let p = mpv_get_property_string(h, name) else { return nil }
        defer { mpv_free(p) }
        return String(cString: p)
    }

    private var logged = 0
    private func logMessage(_ ev: mpv_event) {
        guard logged < 6, let m = ev.data?.assumingMemoryBound(to: mpv_event_log_message.self).pointee else { return }
        logged += 1
        let text = String(cString: m.text).trimmingCharacters(in: .whitespacesAndNewlines)
        Log.player.info("Thumbs mpv[\(String(cString: m.prefix), privacy: .public)]: \(Self.redact(text), privacy: .public)")
    }

    /// Never the stream URL in the log: hosts only.
    static func redact(_ s: String) -> String {
        guard s.contains("http") else { return s }
        var out = s
        while let r = out.range(of: #"https?://[^\s'"]+"#, options: .regularExpression) {
            out.replaceSubrange(r, with: "<\(URL(string: String(out[r]))?.host() ?? "link")>")
        }
        return out
    }

    private func teardown() {
        if let c = ctx { mpv_render_context_free(c); ctx = nil }
        if let h = handle { mpv_terminate_destroy(h); handle = nil }
    }
}

/// Hands a finished (immutable) frame from the worker thread to the main queue.
struct ThumbBox: @unchecked Sendable { let image: CGImage }

// MARK: - HDR frames down to normal colours

/// The software renderer hands HDR frames over still in their HDR code values (PQ or HLG, BT.2020 colours),
/// which look washed out. This maps them to normal sRGB: decode to light, BT.2020 -> BT.709, soft roll-off of
/// the highlights above normal white, encode. Thumbnail-sized, so a few lookup tables are plenty.
enum ThumbTone: Sendable {
    case sdr
    case pq(peak: Double, wide: Bool)
    case hlg(wide: Bool)

    func apply(_ p: UnsafeMutablePointer<UInt8>, width: Int, height: Int, stride: Int) {
        let (lin, wide, peak): ([Float], Bool, Double)
        switch self {
        case .sdr: return
        case .pq(let pk, let w): lin = Self.pqTable; wide = w; peak = pk
        case .hlg(let w): lin = Self.hlgTable; wide = w; peak = 1000
        }
        // Light where 1.0 = normal (SDR) white, 203 nits.
        let white: Float = Float(peak / 203)
        let knee: Float = 0.75
        let enc = Self.srgbTable
        let n = Float(enc.count - 1)
        for y in 0..<height {
            let row = p + y * stride
            for x in 0..<width {
                let px = row + x * 4
                // bgr0
                var b = lin[Int(px[0])], g = lin[Int(px[1])], r = lin[Int(px[2])]
                if wide {
                    let r2 = 1.6605 * r - 0.5876 * g - 0.0728 * b
                    let g2 = -0.1246 * r + 1.1329 * g - 0.0083 * b
                    let b2 = -0.0182 * r - 0.1006 * g + 1.1187 * b
                    r = max(0, r2); g = max(0, g2); b = max(0, b2)
                }
                let m = max(r, max(g, b))
                if m > knee {
                    // Highlights roll off smoothly toward the content's peak instead of clipping.
                    let span = max(0.01, white - knee)
                    let t = min(1, (m - knee) / span)
                    let mm = knee + (1 - knee) * (1 - (1 - t) * (1 - t))
                    let s = mm / m
                    r *= s; g *= s; b *= s
                }
                px[0] = enc[Int(min(1, b) * n)]
                px[1] = enc[Int(min(1, g) * n)]
                px[2] = enc[Int(min(1, r) * n)]
            }
        }
    }

    /// PQ code value -> light (1.0 = 203 nits).
    static let pqTable: [Float] = (0..<256).map { i in
        let e = pow(Double(i) / 255, 1 / 78.84375)
        let l = pow(max(e - 0.8359375, 0) / (18.8515625 - 18.6875 * e), 1 / 0.1593017578125) * 10000
        return Float(l / 203)
    }

    /// HLG code value -> display light on a 1000-nit screen (1.0 = 203 nits).
    static let hlgTable: [Float] = (0..<256).map { i in
        let e = Double(i) / 255
        let a = 0.17883277, b = 0.28466892, c = 0.55991073
        let scene = e <= 0.5 ? e * e / 3 : (exp((e - c) / a) + b) / 12
        return Float(1000 * pow(scene, 1.2) / 203)
    }

    /// Light 0...1 -> sRGB byte.
    static let srgbTable: [UInt8] = (0...1023).map { i in
        let v = Double(i) / 1023
        let s = v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1 / 2.4) - 0.055
        return UInt8(min(255, max(0, (s * 255).rounded())))
    }
}
#endif
