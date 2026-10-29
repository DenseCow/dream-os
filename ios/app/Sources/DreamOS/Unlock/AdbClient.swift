import Foundation
import Security
@preconcurrency import Network
import DreamCore

/// One TCP connection to a Fire TV's ADB (port 5555), with whole-message reads and a timeout on each wait.
final class AdbLink: @unchecked Sendable {
    enum Failure: Error, Equatable { case refused, unreachable, localNetworkDenied, closed, timedOut }

    private let conn: NWConnection
    private let queue = DispatchQueue(label: "com.dreamos.adb")

    init(_ endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 8
        conn = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
    }

    /// Opens the connection. A refused connection means the TV is there with ADB Debugging off. While iOS asks for
    /// Local Network access the connection waits, so only the timeout ends that wait.
    func open(timeout: Double) async throws {
        let once = AdbOnce<Void>()
        let deniedSeen = AdbFlag()
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            once.set(c)
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: once.resume(.success(()))
                case .waiting(let e):
                    if Self.refused(e) { once.resume(.failure(Failure.refused)) }
                    else if Self.denied(e) { deniedSeen.set() }
                case .failed(let e): once.resume(.failure(Self.refused(e) ? Failure.refused : Failure.unreachable))
                case .cancelled: once.resume(.failure(Failure.closed))
                default: break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [conn] in
                if once.resume(.failure(deniedSeen.value ? Failure.localNetworkDenied : Failure.unreachable)) { conn.cancel() }
            }
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if error != nil { c.resume(throwing: Failure.closed) } else { c.resume() }
            })
        }
    }

    /// Exactly [count] bytes; `closed` when the TV hangs up first, `timedOut` (and the link closed) after [timeout] s.
    func read(_ count: Int, timeout: Double) async throws -> Data {
        if count == 0 { return Data() }
        let once = AdbOnce<Data>()
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<Data, Error>) in
            once.set(c)
            conn.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, _ in
                if let data, data.count == count { once.resume(.success(data)) } else { once.resume(.failure(Failure.closed)) }
            }
            queue.asyncAfter(deadline: .now() + timeout) { [conn] in
                if once.resume(.failure(Failure.timedOut)) { conn.cancel() }
            }
        }
    }

    func close() { conn.cancel() }

    private static func refused(_ e: NWError) -> Bool {
        if case .posix(let code) = e { return code == .ECONNREFUSED }
        return false
    }

    /// kDNSServiceErr_PolicyDenied: Local Network access is off (or still being asked).
    private static func denied(_ e: NWError) -> Bool {
        if case .dns(let code) = e { return code == -65570 }
        return false
    }
}

/// A continuation that is resumed once, whichever of the answer or the timeout comes first.
private final class AdbOnce<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var c: CheckedContinuation<T, Error>?
    private var early: Result<T, Error>?

    func set(_ c: CheckedContinuation<T, Error>) {
        lock.lock()
        if let early { lock.unlock(); c.resume(with: early); return }
        self.c = c
        lock.unlock()
    }

    /// True for the first call only.
    @discardableResult func resume(_ r: Result<T, Error>) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if let c { self.c = nil; early = r; c.resume(with: r); return true }
        if early != nil { return false }
        early = r
        return true
    }
}

private final class AdbFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var on = false
    func set() { lock.lock(); on = true; lock.unlock() }
    var value: Bool { lock.lock(); defer { lock.unlock() }; return on }
}

/// adb's wire protocol over an [AdbLink]: CNXN, AUTH (the signature, then the public key so the TV asks
/// "Allow USB debugging?"), then shell commands. Used from one task at a time.
final class AdbSession: @unchecked Sendable {
    enum Failure: Error { case notAllowed, allowTimedOut }

    private static let cnxn: UInt32 = 0x4E58_4E43
    private static let auth: UInt32 = 0x4854_5541
    private static let open: UInt32 = 0x4E45_504F
    private static let okay: UInt32 = 0x5941_4B4F
    private static let clse: UInt32 = 0x4553_4C43
    private static let wrte: UInt32 = 0x4554_5257

    private struct Message { let command, arg0, arg1: UInt32; let payload: Data }

    private let link: AdbLink
    private var nextId: UInt32 = 1

    init(_ link: AdbLink) { self.link = link }

    /// Signs in. [askingAllow] runs when the TV shows its Allow prompt (a key it doesn't know yet).
    func connect(key: SecKey, name: String, askingAllow: @Sendable () -> Void) async throws {
        try await write(Self.cnxn, 0x0100_0000, 256 * 1024, Data("host::\u{0}".utf8))
        var signed = false
        var offered = false
        while true {
            let m: Message
            do {
                m = try await read(timeout: offered ? 90 : 8)
            } catch AdbLink.Failure.closed where offered {
                throw Failure.notAllowed
            } catch AdbLink.Failure.timedOut where offered {
                throw Failure.allowTimedOut
            }
            switch m.command {
            case Self.cnxn:
                return
            case Self.auth where m.arg0 == 1: // TOKEN
                if !signed {
                    signed = true
                    try await write(Self.auth, 2, 0, AdbKey.sign(m.payload, with: key)) // SIGNATURE
                } else if !offered {
                    offered = true
                    var pub = try AdbKey.androidPublicKey(key, name: name)
                    pub.append(0)
                    try await write(Self.auth, 3, 0, pub) // RSAPUBLICKEY: the TV asks the viewer
                    askingAllow()
                } else {
                    throw Failure.notAllowed
                }
            default:
                continue
            }
        }
    }

    /// Runs [command] in the TV's shell and returns what it printed.
    func shell(_ command: String, timeout: Double = 20) async throws -> String {
        let local = nextId
        nextId += 1
        try await write(Self.open, local, 0, Data("shell:\(command)\u{0}".utf8))
        var remote: UInt32 = 0
        var out = Data()
        while true {
            let m = try await read(timeout: timeout)
            guard m.arg1 == local else { continue }
            switch m.command {
            case Self.okay:
                remote = m.arg0
            case Self.wrte:
                remote = m.arg0
                out.append(m.payload)
                try await write(Self.okay, local, remote)
            case Self.clse:
                if remote != 0 { try? await write(Self.clse, local, remote) }
                return String(decoding: out, as: UTF8.self)
            default:
                continue
            }
        }
    }

    private func write(_ command: UInt32, _ arg0: UInt32, _ arg1: UInt32, _ payload: Data = Data()) async throws {
        var d = Data(capacity: 24 + payload.count)
        let sum = payload.reduce(UInt32(0)) { $0 &+ UInt32($1) }
        for v in [command, arg0, arg1, UInt32(payload.count), sum, command ^ 0xFFFF_FFFF] {
            withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) }
        }
        d.append(payload)
        try await link.send(d)
    }

    private func read(timeout: Double) async throws -> Message {
        let h = try await link.read(24, timeout: timeout)
        func word(_ i: Int) -> UInt32 {
            h.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: i * 4, as: UInt32.self)) }
        }
        let length = Int(word(3))
        guard length <= 1 << 20 else { throw AdbLink.Failure.closed }
        let payload = try await link.read(length, timeout: timeout)
        return Message(command: word(0), arg0: word(1), arg1: word(2), payload: payload)
    }
}
