import Foundation

public enum DemuxError: Error, Sendable, Equatable {
    /// A box or element claims more bytes than it has, or a size that makes no sense.
    case corrupt(String)
    /// Media arrived before the stream's header (the init segment).
    case missingHeader
    /// The stream is something this app does not play (encrypted, or an unknown codec).
    case unsupported(String)
}

/// Reads big-endian fields from a byte range, with every read bounds-checked.
struct ByteCursor {
    private let base: UnsafeRawBufferPointer
    private(set) var offset: Int
    let end: Int

    init(_ base: UnsafeRawBufferPointer, offset: Int = 0, end: Int? = nil) {
        self.base = base
        self.offset = offset
        self.end = min(end ?? base.count, base.count)
    }

    var remaining: Int { end - offset }
    var isAtEnd: Bool { offset >= end }

    private mutating func take(_ count: Int) throws -> Int {
        guard count >= 0, remaining >= count else { throw DemuxError.corrupt("read past the end") }
        defer { offset += count }
        return offset
    }

    mutating func u8() throws -> UInt8 {
        base[try take(1)]
    }

    mutating func u16() throws -> UInt16 {
        let at = try take(2)
        return UInt16(base[at]) << 8 | UInt16(base[at + 1])
    }

    mutating func u24() throws -> UInt32 {
        let at = try take(3)
        return UInt32(base[at]) << 16 | UInt32(base[at + 1]) << 8 | UInt32(base[at + 2])
    }

    mutating func u32() throws -> UInt32 {
        let at = try take(4)
        return UInt32(base[at]) << 24 | UInt32(base[at + 1]) << 16 | UInt32(base[at + 2]) << 8 | UInt32(base[at + 3])
    }

    mutating func u64() throws -> UInt64 {
        let high = try u32()
        let low = try u32()
        return UInt64(high) << 32 | UInt64(low)
    }

    mutating func i32() throws -> Int32 { Int32(bitPattern: try u32()) }
    mutating func i64() throws -> Int64 { Int64(bitPattern: try u64()) }

    mutating func skip(_ count: Int) throws {
        _ = try take(count)
    }

    mutating func fourCC() throws -> String {
        let at = try take(4)
        return String(decoding: UnsafeRawBufferPointer(rebasing: base[at..<at + 4]), as: UTF8.self)
    }

    /// A cursor over the next `count` bytes; this cursor moves past them.
    mutating func sub(_ count: Int) throws -> ByteCursor {
        let at = try take(count)
        return ByteCursor(base, offset: at, end: at + count)
    }

    /// Copies the next `count` bytes out.
    mutating func data(_ count: Int) throws -> Data {
        let at = try take(count)
        guard count > 0, let start = base.baseAddress else { return Data() }
        return Data(bytes: start + at, count: count)
    }

    /// Copies everything left.
    mutating func rest() throws -> Data {
        try data(remaining)
    }
}

/// Reads bit fields (most significant bit first), for codec setup records.
struct BitReader {
    private let bytes: [UInt8]
    private var position = 0

    init(_ data: Data) {
        bytes = [UInt8](data)
    }

    var bitsLeft: Int { bytes.count * 8 - position }

    mutating func read(_ count: Int) -> UInt32? {
        guard count >= 0, count <= 32, bitsLeft >= count else { return nil }
        var value: UInt32 = 0
        for _ in 0..<count {
            let byte = bytes[position >> 3]
            let bit = (byte >> (7 - UInt8(position & 7))) & 1
            value = value << 1 | UInt32(bit)
            position += 1
        }
        return value
    }
}
