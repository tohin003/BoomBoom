import Foundation
import Synchronization

/// One song's compressed audio, as it arrives. Packets come in any order (the capture jumps around); the buffer
/// keeps each once, knows which stretches it has, and hands out runs for decoding. Safe to use from any thread.
public final class TrackBuffer: Sendable {
    private struct State {
        var info: AudioTrackInfo?
        /// Timeline units per packet (1024 for AAC, 960 for Opus).
        var packetDuration = 0
        /// How many packets the whole song has, once known.
        var expectedCount: Int?
        var storage = Data()
        /// Packet number to its place in `storage`.
        var places: [Int: Range<Int>] = [:]
        var covered = IndexSet()
        /// The capture reached the end of the stream.
        var isSealed = false
        var statedDuration: TimeInterval?
    }

    private let state = Mutex(State())

    public init() {}

    // MARK: Filling

    /// Sets the stream's description. `duration` (seconds) is what the page says the song lasts; the stream's own
    /// header wins when it states one.
    public func setTrack(_ info: AudioTrackInfo, duration: TimeInterval? = nil) {
        state.withLock { state in
            state.info = info
            if let packet = info.defaultPacketDuration, packet > 0 { state.packetDuration = packet }
            state.statedDuration = info.duration ?? duration ?? state.statedDuration
            Self.updateExpectedCount(&state)
        }
    }

    public func setDuration(_ duration: TimeInterval) {
        state.withLock { state in
            guard state.info?.duration == nil else { return }
            state.statedDuration = duration
            Self.updateExpectedCount(&state)
        }
    }

    /// Adds packets. Ones already held are skipped.
    public func append(_ packets: [AudioPacket]) {
        guard !packets.isEmpty else { return }
        state.withLock { state in
            if state.packetDuration == 0, let first = packets.first(where: { $0.duration > 0 }) {
                state.packetDuration = first.duration
                Self.updateExpectedCount(&state)
            }
            guard state.packetDuration > 0 else { return }
            let length = Int64(state.packetDuration)
            for packet in packets where !packet.data.isEmpty {
                let index = Int((packet.decodeTime + length / 2) / length)
                guard index >= 0, state.places[index] == nil else { continue }
                let start = state.storage.count
                state.storage.append(packet.data)
                state.places[index] = start..<state.storage.count
                state.covered.insert(index)
            }
        }
    }

    /// The capture reached the end of the stream: the last packet held is the song's last. (Earlier stretches may
    /// still be missing if the capture jumped ahead.)
    public func seal() {
        state.withLock { state in
            guard let last = state.covered.last else { return }
            state.isSealed = true
            state.expectedCount = last + 1
        }
    }

    private static func updateExpectedCount(_ state: inout State) {
        guard let info = state.info, state.packetDuration > 0, let duration = state.statedDuration, duration > 0
        else { return }
        let units = duration * Double(info.timescale)
        state.expectedCount = max(1, Int((units / Double(state.packetDuration)).rounded()))
    }

    // MARK: Reading

    public var info: AudioTrackInfo? { state.withLock { $0.info } }

    /// Timeline units per packet, 0 until the first packet is in.
    public var packetDuration: Int { state.withLock { $0.packetDuration } }

    /// Packets in the whole song, once known.
    public var expectedPacketCount: Int? { state.withLock { $0.expectedCount } }

    /// The song's length in seconds, once known.
    public var duration: TimeInterval? {
        state.withLock { state in
            if let stated = state.statedDuration { return stated }
            guard let info = state.info, let count = state.expectedCount, state.packetDuration > 0 else { return nil }
            return Double(count * state.packetDuration) / Double(info.timescale)
        }
    }

    /// Up to `limit` packets in a row starting exactly at `index`. Empty when that packet has not arrived.
    public func packets(from index: Int, limit: Int) -> [Data] {
        state.withLock { state in
            var result: [Data] = []
            result.reserveCapacity(limit)
            var next = index
            while result.count < limit, let place = state.places[next] {
                result.append(state.storage.subdata(in: place))
                next += 1
            }
            return result
        }
    }

    /// The first packet number at or after `index` that is missing.
    public func contiguousEnd(from index: Int) -> Int {
        state.withLock { state in
            guard index >= 0, state.covered.contains(index) else { return index }
            // The range of held packets that contains `index` ends just before the answer.
            let range = state.covered.rangeView(of: index...Int.max - 1).first
            return range.map(\.upperBound) ?? index
        }
    }

    /// True when `index` lies past the song's last packet: nothing will ever come there. This is about the song's
    /// end only; earlier stretches may still be missing (the listener jumped ahead).
    public func hasReachedEnd(at index: Int) -> Bool {
        state.withLock { state in
            guard let count = state.expectedCount, count > 0 else { return false }
            if state.isSealed { return index >= count }
            // Not sealed yet, so `count` comes from the stated length, which may round one packet past the real end.
            // If everything up to that doubtful last packet is in hand, the end is where the audio in hand stops.
            if index == count - 1, let last = state.covered.last, last == count - 2,
               state.covered.contains(integersIn: 0..<(count - 1)) { return true }
            return index >= count
        }
    }

    /// True when every packet of the song is held.
    public var isComplete: Bool {
        state.withLock { state in
            guard let count = state.expectedCount, count > 0 else { return false }
            // The stated length may round one packet past what the stream really has.
            let needed = state.isSealed ? count : max(1, count - 1)
            return state.covered.contains(integersIn: 0..<needed)
        }
    }

    /// The first missing packet at or after `index`, wrapping round to the start. Nil when nothing is missing.
    public func firstGap(from index: Int) -> Int? {
        state.withLock { state in
            guard let count = state.expectedCount, count > 0 else { return state.covered.contains(0) ? nil : 0 }
            let needed = state.isSealed ? count : max(1, count - 1)
            func gap(in range: Range<Int>) -> Int? {
                guard !range.isEmpty else { return nil }
                var missing = IndexSet(integersIn: range)
                missing.subtract(state.covered)
                return missing.first
            }
            let start = min(max(index, 0), needed)
            return gap(in: start..<needed) ?? gap(in: 0..<start)
        }
    }

    /// How much of the song is held, 0...1.
    public var coveredFraction: Double {
        state.withLock { state in
            guard let count = state.expectedCount, count > 0 else { return 0 }
            return min(1, Double(state.covered.count) / Double(count))
        }
    }

    public var heldPacketCount: Int { state.withLock { $0.covered.count } }
    public var heldBytes: Int { state.withLock { $0.storage.count } }

    /// Seconds from the start of the song to the start of a packet.
    public func time(ofPacket index: Int) -> TimeInterval {
        state.withLock { state in
            guard let info = state.info, state.packetDuration > 0 else { return 0 }
            return Double(index * state.packetDuration) / Double(info.timescale)
        }
    }

    /// The packet that holds a moment of the song.
    public func packetIndex(at seconds: TimeInterval) -> Int {
        state.withLock { state in
            guard let info = state.info, state.packetDuration > 0 else { return 0 }
            return max(0, Int(seconds * Double(info.timescale) / Double(state.packetDuration)))
        }
    }

    // MARK: Keeping on disk

    private static let magic = Data("BOOMTRK1".utf8)

    private struct Header: Codable {
        var codec: String
        var timescale: Int
        var sampleRate: Double
        var channels: Int
        var magicCookie: Data
        var audioSpecificConfig: Data
        var leadIn: Int64
        var packetDuration: Int
        /// The packet length the stream's own header stated, if it did.
        var statedPacketDuration: Int?
        var duration: TimeInterval?
        var sizes: [Int]
    }

    /// The whole song as one file. Nil unless every packet is held.
    public func serialized() -> Data? {
        guard isComplete else { return nil }
        return state.withLock { state -> Data? in
            guard let info = state.info, let last = state.covered.last,
                  state.covered.contains(integersIn: 0...last) else { return nil }
            var sizes: [Int] = []
            sizes.reserveCapacity(last + 1)
            var body = Data(capacity: state.storage.count)
            for index in 0...last {
                guard let place = state.places[index] else { return nil }
                sizes.append(place.count)
                body.append(state.storage.subdata(in: place))
            }
            let header = Header(
                codec: info.codec.rawValue, timescale: info.timescale, sampleRate: info.sampleRate,
                channels: info.channels, magicCookie: info.magicCookie,
                audioSpecificConfig: info.audioSpecificConfig, leadIn: info.leadIn,
                packetDuration: state.packetDuration, statedPacketDuration: info.defaultPacketDuration,
                duration: info.duration, sizes: sizes)
            guard let encoded = try? JSONEncoder().encode(header) else { return nil }
            var file = Self.magic
            withUnsafeBytes(of: UInt32(encoded.count).bigEndian) { file.append(contentsOf: $0) }
            file.append(encoded)
            file.append(body)
            return file
        }
    }

    /// Reads a file written by `serialized()`. Nil when it is not one or is cut short.
    public convenience init?(serialized file: Data) {
        let magic = Self.magic
        guard file.count > magic.count + 4, file.prefix(magic.count) == magic else { return nil }
        let base = file.startIndex + magic.count
        let length = file[base..<base + 4].reduce(0) { $0 << 8 | Int($1) }
        guard length > 0, file.count >= magic.count + 4 + length,
              let header = try? JSONDecoder().decode(Header.self, from: file.subdata(in: base + 4..<base + 4 + length)),
              let codec = AudioTrackInfo.Codec(rawValue: header.codec), header.packetDuration > 0
        else { return nil }
        let bodyStart = base + 4 + length
        guard file.count - (bodyStart - file.startIndex) == header.sizes.reduce(0, +) else { return nil }
        self.init()
        let info = AudioTrackInfo(
            codec: codec, timescale: header.timescale, sampleRate: header.sampleRate, channels: header.channels,
            magicCookie: header.magicCookie, audioSpecificConfig: header.audioSpecificConfig,
            leadIn: header.leadIn, defaultPacketDuration: header.statedPacketDuration, duration: header.duration)
        state.withLock { state in
            state.info = info
            state.packetDuration = header.packetDuration
            state.statedDuration = header.duration
                ?? Double(header.sizes.count * header.packetDuration) / Double(max(1, header.timescale))
            state.storage = file.subdata(in: bodyStart..<file.endIndex)
            var offset = 0
            for (index, size) in header.sizes.enumerated() {
                state.places[index] = offset..<offset + size
                offset += size
            }
            state.covered = IndexSet(integersIn: 0..<header.sizes.count)
            state.expectedCount = header.sizes.count
            state.isSealed = true
        }
    }
}
