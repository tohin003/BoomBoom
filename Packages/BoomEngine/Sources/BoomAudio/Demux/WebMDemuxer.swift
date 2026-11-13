import Foundation

/// Reads WebM (Matroska) Opus audio, the form YouTube's best audio streams take: a header (`EBML`, `Segment` with
/// `Info` and `Tracks`) followed by `Cluster`s of `SimpleBlock`s, one Opus packet per block.
///
/// The player may append a cluster in several pieces, and after a jump it starts again at a cluster boundary, so the
/// parser walks elements flat: it steps into `Segment` and `Cluster` as soon as their headers are in, and handles
/// every other element once it has arrived whole.
public final class WebMDemuxer: AudioDemuxer {
    public private(set) var track: AudioTrackInfo?

    private var pending = Data()
    /// Nanoseconds per timestamp tick (`TimestampScale`).
    private var timestampScale: UInt64 = 1_000_000
    /// The stream's length in ticks (`Info.Duration`).
    private var durationTicks: Double?
    private var trackNumber: UInt64?
    /// The current cluster's timestamp in ticks. Nil until a cluster's `Timestamp` has been read.
    private var clusterTime: Int64?

    private enum ID {
        static let ebml: UInt32 = 0x1A45_DFA3
        static let segment: UInt32 = 0x1853_8067
        static let info: UInt32 = 0x1549_A966
        static let timestampScale: UInt32 = 0x2A_D7B1
        static let duration: UInt32 = 0x4489
        static let tracks: UInt32 = 0x1654_AE6B
        static let trackEntry: UInt32 = 0xAE
        static let trackNumber: UInt32 = 0xD7
        static let trackType: UInt32 = 0x83
        static let codecID: UInt32 = 0x86
        static let codecPrivate: UInt32 = 0x63A2
        static let codecDelay: UInt32 = 0x56AA
        static let audio: UInt32 = 0xE1
        static let samplingFrequency: UInt32 = 0xB5
        static let channels: UInt32 = 0x9F
        static let cluster: UInt32 = 0x1F43_B675
        static let timestamp: UInt32 = 0xE7
        static let simpleBlock: UInt32 = 0xA3
        static let blockGroup: UInt32 = 0xA0
        static let block: UInt32 = 0xA1
    }

    private enum ElementSize {
        case known(Int)
        case unknown
    }

    /// Nothing but a cluster comes near this, and clusters are stepped into, not held whole.
    private static let largestElement = 64 << 20
    /// Opus always decodes at 48 kHz; packet times are counted in its samples.
    private static let timescale = 48_000

    public init() {}

    public func reset() {
        pending.removeAll(keepingCapacity: true)
        clusterTime = nil
    }

    public func feed(_ data: Data) throws -> [DemuxEvent] {
        pending.append(data)
        var events: [DemuxEvent] = []
        var packets: [AudioPacket] = []
        var consumed = 0
        do {
            try pending.withUnsafeBytes { raw in
                parsing: while true {
                    var cursor = ByteCursor(raw, offset: consumed)
                    guard let id = try Self.readID(&cursor), let size = try Self.readSize(&cursor) else { break }
                    let headerLength = cursor.offset - consumed

                    if id == ID.segment || id == ID.cluster {
                        // Step inside: the children follow as if they were at the top level.
                        if id == ID.cluster { clusterTime = nil }
                        consumed += headerLength
                        continue
                    }
                    guard case .known(let length) = size else {
                        throw DemuxError.corrupt("element \(String(id, radix: 16)) of unknown size")
                    }
                    guard length <= Self.largestElement else {
                        throw DemuxError.corrupt("element \(String(id, radix: 16)) claims \(length) bytes")
                    }
                    guard cursor.remaining >= length else { break parsing }
                    var body = try cursor.sub(length)

                    switch id {
                    case ID.info:
                        try parseInfo(&body)
                    case ID.tracks:
                        let info = try parseTracks(&body)
                        track = info
                        events.append(.track(info))
                    case ID.timestamp:
                        clusterTime = Int64(clamping: try Self.readUnsigned(&body))
                    case ID.simpleBlock:
                        try readBlock(&body, into: &packets)
                    case ID.blockGroup:
                        try Self.forEachElement(&body) { id, child in
                            if id == ID.block { try readBlock(&child, into: &packets) }
                        }
                    default:
                        break
                    }
                    consumed += headerLength + length
                }
            }
        } catch {
            reset()
            throw error
        }
        if consumed > 0 { pending.removeSubrange(pending.startIndex..<pending.startIndex + consumed) }
        if !packets.isEmpty { events.append(.packets(packets)) }
        return events
    }

    // MARK: Header

    private func parseInfo(_ info: inout ByteCursor) throws {
        try Self.forEachElement(&info) { id, body in
            switch id {
            case ID.timestampScale:
                let scale = try Self.readUnsigned(&body)
                if scale > 0 { timestampScale = scale }
            case ID.duration:
                durationTicks = try Self.readFloat(&body)
            default:
                break
            }
        }
    }

    private func parseTracks(_ tracks: inout ByteCursor) throws -> AudioTrackInfo {
        var found: AudioTrackInfo?
        var codecsSeen: [String] = []
        try Self.forEachElement(&tracks) { id, entry in
            guard id == ID.trackEntry, found == nil else { return }
            var number: UInt64 = 0
            var type: UInt64 = 0
            var codec = ""
            var head = Data()
            var codecDelay: UInt64?
            var channels = 2
            try Self.forEachElement(&entry) { id, body in
                switch id {
                case ID.trackNumber: number = try Self.readUnsigned(&body)
                case ID.trackType: type = try Self.readUnsigned(&body)
                case ID.codecID: codec = String(decoding: try body.rest(), as: UTF8.self)
                case ID.codecPrivate: head = try body.rest()
                case ID.codecDelay: codecDelay = try Self.readUnsigned(&body)
                case ID.audio:
                    try Self.forEachElement(&body) { id, audio in
                        if id == ID.channels { channels = Int(clamping: try Self.readUnsigned(&audio)) }
                    }
                default: break
                }
            }
            guard type == 2 else { return }
            codecsSeen.append(codec)
            // "OpusHead", version, channel count, pre-skip (little-endian), ...
            guard codec == "A_OPUS", head.count >= 19, head.starts(with: Data("OpusHead".utf8)) else { return }
            let bytes = [UInt8](head)
            let preSkip = Int(bytes[10]) | Int(bytes[11]) << 8
            let leadIn = codecDelay.map { Int64(($0 * UInt64(Self.timescale) + 500_000_000) / 1_000_000_000) }
            trackNumber = number
            found = AudioTrackInfo(
                codec: .opus,
                timescale: Self.timescale,
                sampleRate: Double(Self.timescale),
                channels: bytes[9] > 0 ? Int(bytes[9]) : channels,
                magicCookie: head,
                leadIn: leadIn ?? Int64(preSkip),
                defaultPacketDuration: nil,
                duration: durationTicks.map { $0 * Double(timestampScale) / 1_000_000_000 }
            )
        }
        guard let found else {
            throw DemuxError.unsupported("no Opus track (found: \(codecsSeen.joined(separator: ", ")))")
        }
        return found
    }

    // MARK: Blocks

    private func readBlock(_ body: inout ByteCursor, into packets: inout [AudioPacket]) throws {
        guard let number = try Self.readVInt(&body) else { throw DemuxError.corrupt("a block without a track") }
        let relative = Int16(bitPattern: try body.u16())
        let flags = try body.u8()
        guard let trackNumber else { throw DemuxError.missingHeader }
        guard number == trackNumber, let clusterTime else { return }
        guard flags & 0x06 == 0 else { throw DemuxError.unsupported("laced WebM blocks") }
        let payload = try body.rest()
        guard let first = payload.first else { return }

        let samples = Self.opusPacketSamples(toc: first, second: payload.count > 1 ? payload[payload.startIndex + 1] : 0)
        let ticks = clusterTime + Int64(relative)
        // Ticks to 48 kHz samples. With the usual millisecond ticks this is exact to 48 samples only, so the time is
        // snapped to a whole number of packets: YouTube stamps packet k at about 20k + 1 ms.
        let nanos = Double(ticks) * Double(timestampScale)
        let raw = Int64((nanos * Double(Self.timescale) / 1_000_000_000).rounded())
        let length = Int64(samples)
        let time = max(0, (raw + length / 4) / length) * length
        packets.append(AudioPacket(decodeTime: time, duration: samples, data: payload))
    }

    /// How many 48 kHz samples an Opus packet decodes to, from its first byte (RFC 6716, section 3.1).
    static func opusPacketSamples(toc: UInt8, second: UInt8) -> Int {
        let config = Int(toc >> 3)
        let frame: Int
        switch config {
        case 0..<12: frame = [480, 960, 1920, 2880][config % 4]
        case 12..<16: frame = [480, 960][config % 2]
        default: frame = [120, 240, 480, 960][config % 4]
        }
        let count: Int
        switch toc & 0x03 {
        case 0: count = 1
        case 1, 2: count = 2
        default: count = max(1, Int(second & 0x3F))
        }
        return frame * count
    }

    // MARK: EBML

    private static func forEachElement(
        _ parent: inout ByteCursor, _ visit: (UInt32, inout ByteCursor) throws -> Void
    ) throws {
        while !parent.isAtEnd {
            guard let id = try readID(&parent), let size = try readSize(&parent) else {
                throw DemuxError.corrupt("a cut-off element")
            }
            guard case .known(let length) = size, length <= parent.remaining else {
                throw DemuxError.corrupt("element \(String(id, radix: 16)) overruns its parent")
            }
            var body = try parent.sub(length)
            try visit(id, &body)
        }
    }

    /// An element id: 1 to 4 bytes, the length marker kept. Nil when more bytes are needed.
    private static func readID(_ cursor: inout ByteCursor) throws -> UInt32? {
        guard cursor.remaining >= 1 else { return nil }
        var peek = cursor
        let first = try peek.u8()
        let length = first.leadingZeroBitCount + 1
        guard length <= 4 else { throw DemuxError.corrupt("not an element id: \(first)") }
        guard cursor.remaining >= length else { return nil }
        var value: UInt32 = 0
        for _ in 0..<length { value = value << 8 | UInt32(try cursor.u8()) }
        return value
    }

    /// An element size: 1 to 8 bytes, the length marker removed. Nil when more bytes are needed.
    private static func readSize(_ cursor: inout ByteCursor) throws -> ElementSize? {
        guard cursor.remaining >= 1 else { return nil }
        var peek = cursor
        let first = try peek.u8()
        let length = first.leadingZeroBitCount + 1
        guard length <= 8 else { throw DemuxError.corrupt("not an element size") }
        guard cursor.remaining >= length else { return nil }
        var value = UInt64(try cursor.u8()) & (0xFF >> UInt64(length))
        for _ in 1..<length { value = value << 8 | UInt64(try cursor.u8()) }
        let allOnes = (UInt64(1) << UInt64(7 * length)) - 1
        return value == allOnes ? .unknown : .known(Int(clamping: value))
    }

    /// A variable-length number inside a block (the track number).
    private static func readVInt(_ cursor: inout ByteCursor) throws -> UInt64? {
        guard let size = try readSize(&cursor) else { return nil }
        if case .known(let value) = size { return UInt64(value) }
        return nil
    }

    private static func readUnsigned(_ body: inout ByteCursor) throws -> UInt64 {
        guard body.remaining <= 8 else { throw DemuxError.corrupt("an integer of \(body.remaining) bytes") }
        var value: UInt64 = 0
        while !body.isAtEnd { value = value << 8 | UInt64(try body.u8()) }
        return value
    }

    private static func readFloat(_ body: inout ByteCursor) throws -> Double {
        switch body.remaining {
        case 4: return Double(Float(bitPattern: try body.u32()))
        case 8: return Double(bitPattern: try body.u64())
        default: throw DemuxError.corrupt("a float of \(body.remaining) bytes")
        }
    }
}
