import Compression
import Foundation

/// A small XML tree (Foundation's XMLParser) for addons.xml, addon.xml and settings.xml.
final class XNode {
    let name: String
    let attrs: [String: String]
    var text = ""
    var children: [XNode] = []
    weak var parent: XNode?

    init(name: String, attrs: [String: String]) { self.name = name; self.attrs = attrs }

    subscript(_ key: String) -> String? { attrs[key] }
    func all(_ name: String) -> [XNode] { children.filter { $0.name == name } }
    func first(_ name: String) -> XNode? { children.first { $0.name == name } }
    /// Every descendant (depth first) named `name`.
    func descendants(_ name: String) -> [XNode] {
        var out: [XNode] = []
        for c in children {
            if c.name == name { out.append(c) }
            out += c.descendants(name)
        }
        return out
    }

    /// Parses `data`; nil when it isn't well-formed XML.
    static func parse(_ data: Data) -> XNode? {
        let p = XMLParser(data: data)
        let d = Builder()
        p.delegate = d
        p.shouldProcessNamespaces = false
        p.shouldResolveExternalEntities = false
        guard p.parse() || d.root != nil else { return nil }
        return d.root
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XNode?
        var stack: [XNode] = []
        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                    attributes: [String: String] = [:]) {
            let n = XNode(name: name, attrs: attributes)
            if let top = stack.last { n.parent = top; top.children.append(n) } else if root == nil { root = n }
            stack.append(n)
        }
        func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
        func parser(_ parser: XMLParser, foundCDATA data: Data) { stack.last?.text += String(decoding: data, as: UTF8.self) }
        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            _ = stack.popLast()
        }
    }
}

enum ArchiveError: Error { case bad, tooLarge, unsupported }

/// Reads a ZIP archive's entries (stored or deflated; no zip64, no encryption).
struct ZipArchive {
    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let size: Int
        let localOffset: Int
        var isDirectory: Bool { name.hasSuffix("/") }
    }

    let data: Data
    let entries: [Entry]

    init(_ data: Data, maxEntries: Int) throws {
        self.data = data
        let bytes = [UInt8](data)
        func u16(_ o: Int) -> Int { Int(bytes[o]) | Int(bytes[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }
        // End of central directory: last 22 bytes + up to 64 KB comment.
        guard bytes.count >= 22 else { throw ArchiveError.bad }
        var eocd = -1
        var i = bytes.count - 22
        let stop = max(0, bytes.count - 22 - 65_535)
        while i >= stop {
            if bytes[i] == 0x50, bytes[i + 1] == 0x4b, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ArchiveError.bad }
        let count = u16(eocd + 10)
        let cdOffset = u32(eocd + 16)
        if count == 0xFFFF || cdOffset == 0xFFFF_FFFF { throw ArchiveError.unsupported }
        guard count <= maxEntries else { throw ArchiveError.tooLarge }
        var list: [Entry] = []
        var p = cdOffset
        for _ in 0..<count {
            guard p + 46 <= bytes.count, u32(p) == 0x0201_4b50 else { throw ArchiveError.bad }
            let flags = u16(p + 8)
            let method = UInt16(u16(p + 10))
            let csize = u32(p + 20), usize = u32(p + 24)
            let nlen = u16(p + 28), xlen = u16(p + 30), clen = u16(p + 32)
            let local = u32(p + 42)
            guard p + 46 + nlen <= bytes.count else { throw ArchiveError.bad }
            if flags & 1 != 0 { throw ArchiveError.unsupported }
            let name = String(decoding: bytes[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
            list.append(Entry(name: name, method: method, compressedSize: csize, size: usize, localOffset: local))
            p += 46 + nlen + xlen + clen
        }
        entries = list
    }

    /// The entry's bytes (at most `max`).
    func read(_ e: Entry, max: Int) throws -> Data {
        guard e.size <= max else { throw ArchiveError.tooLarge }
        let o = e.localOffset
        guard o + 30 <= data.count else { throw ArchiveError.bad }
        let b = data.startIndex
        func u16(_ x: Int) -> Int { Int(data[b + x]) | Int(data[b + x + 1]) << 8 }
        guard data[b + o] == 0x50, data[b + o + 1] == 0x4b, data[b + o + 2] == 0x03, data[b + o + 3] == 0x04 else { throw ArchiveError.bad }
        let start = o + 30 + u16(o + 26) + u16(o + 28)
        guard start + e.compressedSize <= data.count else { throw ArchiveError.bad }
        let raw = data.subdata(in: (b + start)..<(b + start + e.compressedSize))
        switch e.method {
        case 0: return raw
        case 8: return try Inflate.raw(raw, expected: e.size)
        default: throw ArchiveError.unsupported
        }
    }
}

enum Inflate {
    /// Raw DEFLATE (RFC 1951) of known output size.
    static func raw(_ input: Data, expected: Int) throws -> Data {
        if expected == 0 { return Data() }
        var out = Data(count: expected)
        let n = out.withUnsafeMutableBytes { dst in
            input.withUnsafeBytes { src in
                compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, expected,
                                          src.bindMemory(to: UInt8.self).baseAddress!, input.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n == expected else { throw ArchiveError.bad }
        return out
    }

    /// A .gz file's content (at most `max` bytes).
    static func gunzip(_ d: Data, max: Int) throws -> Data {
        let b = [UInt8](d)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { throw ArchiveError.bad }
        let flags = b[3]
        var p = 10
        if flags & 4 != 0 { guard p + 2 <= b.count else { throw ArchiveError.bad }; p += 2 + (Int(b[p]) | Int(b[p + 1]) << 8) }
        if flags & 8 != 0 { while p < b.count, b[p] != 0 { p += 1 }; p += 1 }
        if flags & 16 != 0 { while p < b.count, b[p] != 0 { p += 1 }; p += 1 }
        if flags & 2 != 0 { p += 2 }
        guard p < b.count - 8 else { throw ArchiveError.bad }
        let n = b.count
        let isize = Int(b[n - 4]) | Int(b[n - 3]) << 8 | Int(b[n - 2]) << 16 | Int(b[n - 1]) << 24
        guard isize <= max else { throw ArchiveError.tooLarge }
        return try raw(Data(b[p..<(n - 8)]), expected: isize)
    }
}
