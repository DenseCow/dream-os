import Foundation
import Security
import DreamCore

/// Dream's own ADB key (RSA 2048), made once and kept in the Keychain (`SecureStore`). A Fire TV remembers it once
/// the viewer chooses OK with "Always allow from this computer" checked, so a later unlock asks nothing.
enum AdbKey {
    enum Failure: Error { case key }

    private static let storeKey = "adb.rsa"
    private static let words = 64 // 2048 bits in 32-bit words, as adbd expects

    static func privateKey() throws -> SecKey {
        let attrs: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
                                    kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
                                    kSecAttrKeySizeInBits as String: 2048]
        if let saved = SecureStore.get(storeKey), let der = Data(base64Encoded: saved),
           let key = SecKeyCreateWithData(der as CFData, attrs as CFDictionary, nil) {
            return key
        }
        var error: Unmanaged<CFError>?
        let make: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048]
        guard let key = SecKeyCreateRandomKey(make as CFDictionary, &error),
              let der = SecKeyCopyExternalRepresentation(key, &error) as Data? else { throw Failure.key }
        SecureStore.set(storeKey, der.base64EncodedString())
        return key
    }

    /// adbd's challenge is a 20-byte token it verifies as a SHA-1 digest, so it is signed as one (PKCS#1 v1.5, no hashing).
    static func sign(_ token: Data, with key: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(key, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, &error) as Data? else {
            throw Failure.key
        }
        return sig
    }

    /// The public key the way adb sends it: base64 of {64, n0inv, n, R² mod n, e} in little-endian 32-bit words,
    /// then a space and a name; the TV shows its fingerprint on the Allow prompt.
    static func androidPublicKey(_ key: SecKey, name: String) throws -> Data {
        guard let pub = SecKeyCopyPublicKey(key), let der = SecKeyCopyExternalRepresentation(pub, nil) as Data?,
              let (modulusBE, exponent) = parsePKCS1(der) else { throw Failure.key }
        let bytes = Array(modulusBE.drop(while: { $0 == 0 }))
        guard bytes.count <= words * 4, bytes.count > (words - 1) * 4 else { throw Failure.key }

        var n = [UInt32](repeating: 0, count: words)
        for (i, b) in bytes.reversed().enumerated() { n[i / 4] |= UInt32(b) << (8 * UInt32(i % 4)) }

        // n0inv = -1 / n[0] mod 2^32 (Newton's iteration doubles the correct low bits each round).
        var inv: UInt32 = 1
        for _ in 0..<6 { inv = inv &* (2 &- n[0] &* inv) }
        let n0inv = 0 &- inv

        // R² mod n with R = 2^2048: double 1 modulo n, 4096 times.
        var rr = [UInt32](repeating: 0, count: words)
        rr[0] = 1
        for _ in 0..<(2 * words * 32) {
            var carry: UInt32 = 0
            for i in 0..<words {
                let v = rr[i]
                rr[i] = (v << 1) | carry
                carry = v >> 31
            }
            if carry == 1 || !less(rr, n) { subtract(&rr, n) }
        }

        var blob = Data()
        func put(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { blob.append(contentsOf: $0) } }
        put(UInt32(words))
        put(n0inv)
        n.forEach(put)
        rr.forEach(put)
        put(exponent)
        return Data((blob.base64EncodedString() + " " + name).utf8)
    }

    private static func less(_ a: [UInt32], _ b: [UInt32]) -> Bool {
        for i in stride(from: a.count - 1, through: 0, by: -1) where a[i] != b[i] { return a[i] < b[i] }
        return false
    }

    /// a -= b, wrapping (the caller knows the true result fits).
    private static func subtract(_ a: inout [UInt32], _ b: [UInt32]) {
        var borrow: UInt64 = 0
        for i in 0..<a.count {
            let d = UInt64(a[i]) &- UInt64(b[i]) &- borrow
            a[i] = UInt32(truncatingIfNeeded: d)
            borrow = (d >> 63) & 1
        }
    }

    /// RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER } (what SecKey exports).
    private static func parsePKCS1(_ der: Data) -> ([UInt8], UInt32)? {
        let p = [UInt8](der)
        var i = 0
        func length() -> Int? {
            guard i < p.count else { return nil }
            let first = p[i]; i += 1
            if first < 0x80 { return Int(first) }
            let count = Int(first & 0x7F)
            guard count <= 4, i + count <= p.count else { return nil }
            var v = 0
            for _ in 0..<count { v = v << 8 | Int(p[i]); i += 1 }
            return v
        }
        func integer() -> [UInt8]? {
            guard i < p.count, p[i] == 0x02 else { return nil }
            i += 1
            guard let l = length(), i + l <= p.count else { return nil }
            defer { i += l }
            return Array(p[i..<(i + l)])
        }
        guard p.first == 0x30 else { return nil }
        i = 1
        guard length() != nil, let modulus = integer(), let e = integer(), e.count <= 5 else { return nil }
        let exponent = e.reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard exponent <= UInt64(UInt32.max) else { return nil }
        return (modulus, UInt32(exponent))
    }
}
