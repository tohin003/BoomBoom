import Foundation

/// Reads fragmented MP4 audio (ISO BMFF: `ftyp moov` then `moof mdat` pairs), the form YouTube's AAC streams take
/// when the player appends them to a media source buffer.
public final class FragmentedMP4Demuxer: AudioDemuxer {
    public private(set) var track: AudioTrackInfo?

    /// Bytes received but not yet parsed (an incomplete box at the end).
    private var pending = Data()
    /// Where `pending` starts, counted from the last `reset()`. Fragment offsets are relative, so any origin works.
    private var streamOffset: Int64 = 0
    private var trackID: UInt32?
    private var defaultDuration: UInt32?
    private var defaultSize: UInt32?
    private var fragment: Fragment?

    private struct Run {
        /// Where the run's first sample starts, counted from the start of its `moof`. Nil: right after the last run.
        var dataOffset: Int64?
        var samples: [(duration: Int, size: Int)]
    }

    private struct Fragment {
        var moofStart: Int64
        var baseDecodeTime: Int64
        var runs: [Run]
    }

    /// No audio box comes near this; a larger size means the bytes are not what they claim.
    private static let largestBox = 256 << 20

    public init() {}

    public func reset() {
        pending.removeAll(keepingCapacity: true)
        streamOffset = 0
        fragment = nil
    }

    public func feed(_ data: Data) throws -> [DemuxEvent] {
        pending.append(data)
        var events: [DemuxEvent] = []
        var consumed = 0
        do {
            try pending.withUnsafeBytes { raw in
                while raw.count - consumed >= 8 {
                    var header = ByteCursor(raw, offset: consumed)
                    var size = Int(try header.u32())
                    let type = try header.fourCC()
                    var headerLength = 8
                    if size == 1 {
                        guard header.remaining >= 8 else { break }
                        let large = try header.u64()
                        guard large <= UInt64(Self.largestBox) else {
                            throw DemuxError.corrupt("box '\(type)' claims \(large) bytes")
                        }
                        size = Int(large)
                        headerLength = 16
                    } else if size == 0 {
                        throw DemuxError.unsupported("box '\(type)' runs to the end of the stream")
                    }
                    guard size >= headerLength, size <= Self.largestBox else {
                        throw DemuxError.corrupt("box '\(type)' claims \(size) bytes")
                    }
                    guard raw.count - consumed >= size else { break }

                    var body = ByteCursor(raw, offset: consumed + headerLength, end: consumed + size)
                    let boxStart = streamOffset + Int64(consumed)
                    switch type {
                    case "moov":
                        let info = try parseMovie(&body)
                        track = info
                        events.append(.track(info))
                    case "moof":
                        fragment = try parseFragment(&body, moofStart: boxStart)
                    case "mdat":
                        let packets = try extractPackets(body, payloadStart: boxStart + Int64(headerLength))
                        if !packets.isEmpty { events.append(.packets(packets)) }
                    default:
                        break
                    }
                    consumed += size
                }
            }
        } catch {
            // What is pending cannot be trusted any more. The next segment starts clean.
            reset()
            throw error
        }
        if consumed > 0 {
            pending.removeSubrange(pending.startIndex..<pending.startIndex + consumed)
            streamOffset += Int64(consumed)
        }
        return events
    }

    // MARK: Boxes

    /// Visits each child box of `parent` with its type and a cursor over its body.
    private func forEachBox(
        _ parent: inout ByteCursor, _ visit: (String, inout ByteCursor) throws -> Void
    ) throws {
        while parent.remaining >= 8 {
            let size = Int(try parent.u32())
            let type = try parent.fourCC()
            var headerLength = 8
            var total = size
            if size == 1 {
                total = Int(clamping: try parent.u64())
                headerLength = 16
            } else if size == 0 {
                total = parent.remaining + 8
            }
            guard total >= headerLength, total - headerLength <= parent.remaining else {
                throw DemuxError.corrupt("box '\(type)' overruns its parent")
            }
            var body = try parent.sub(total - headerLength)
            try visit(type, &body)
        }
    }

    // MARK: The header (moov)

    private struct SampleEntry {
        var codec: AudioTrackInfo.Codec
        var channels: Int
        var sampleRate: Double
        var magicCookie: Data
        var audioSpecificConfig: Data
    }

    private struct TrackHeader {
        var id: UInt32 = 0
        var timescale = 0
        var leadIn: Int64 = 0
        var isAudio = false
        var entry: SampleEntry?
    }

    private func parseMovie(_ moov: inout ByteCursor) throws -> AudioTrackInfo {
        var audio: TrackHeader?
        var extendsDefaults: [UInt32: (duration: UInt32, size: UInt32)] = [:]
        var movieTimescale: UInt32 = 0
        var movieDuration: UInt64 = 0
        try forEachBox(&moov) { type, body in
            switch type {
            case "mvhd":
                let version = try body.u8()
                try body.skip(3)
                try body.skip(version == 1 ? 16 : 8)
                movieTimescale = try body.u32()
                movieDuration = version == 1 ? try body.u64() : UInt64(try body.u32())
            case "trak":
                let header = try parseTrack(&body)
                if audio == nil, header.isAudio, header.entry != nil { audio = header }
            case "mvex":
                try forEachBox(&body) { type, trex in
                    if type == "mehd" {
                        // A fragmented movie states its whole length here; mvhd's is then zero.
                        let version = try trex.u8()
                        try trex.skip(3)
                        movieDuration = version == 1 ? try trex.u64() : UInt64(try trex.u32())
                        return
                    }
                    guard type == "trex" else { return }
                    try trex.skip(4)
                    let id = try trex.u32()
                    try trex.skip(4)
                    let duration = try trex.u32()
                    let size = try trex.u32()
                    extendsDefaults[id] = (duration, size)
                }
            default:
                break
            }
        }
        guard let audio, let entry = audio.entry, audio.timescale > 0 else {
            throw DemuxError.unsupported("no playable audio track in the header")
        }
        trackID = audio.id
        let defaults = extendsDefaults[audio.id]
        defaultDuration = defaults.flatMap { $0.duration > 0 ? $0.duration : nil }
        defaultSize = defaults.flatMap { $0.size > 0 ? $0.size : nil }
        return AudioTrackInfo(
            codec: entry.codec,
            timescale: audio.timescale,
            sampleRate: entry.sampleRate > 0 ? entry.sampleRate : Double(audio.timescale),
            channels: entry.channels,
            magicCookie: entry.magicCookie,
            audioSpecificConfig: entry.audioSpecificConfig,
            leadIn: audio.leadIn,
            defaultPacketDuration: defaultDuration.map(Int.init),
            duration: movieTimescale > 0 && movieDuration > 0 && movieDuration != UInt64(UInt32.max)
                ? Double(movieDuration) / Double(movieTimescale) : nil
        )
    }

    private func parseTrack(_ trak: inout ByteCursor) throws -> TrackHeader {
        var header = TrackHeader()
        try forEachBox(&trak) { type, body in
            switch type {
            case "tkhd":
                let version = try body.u8()
                try body.skip(3)
                try body.skip(version == 1 ? 16 : 8)
                header.id = try body.u32()
            case "edts":
                try forEachBox(&body) { type, list in
                    guard type == "elst" else { return }
                    let version = try list.u8()
                    try list.skip(3)
                    let count = try list.u32()
                    for _ in 0..<min(count, 8) {
                        let mediaTime: Int64
                        if version == 1 {
                            try list.skip(8)
                            mediaTime = try list.i64()
                        } else {
                            try list.skip(4)
                            mediaTime = Int64(try list.i32())
                        }
                        try list.skip(4)
                        // A media time of -1 is an empty edit (silence before the track); the first real one counts.
                        if mediaTime >= 0 {
                            header.leadIn = mediaTime
                            break
                        }
                    }
                }
            case "mdia":
                try forEachBox(&body) { type, media in
                    switch type {
                    case "mdhd":
                        let version = try media.u8()
                        try media.skip(3)
                        try media.skip(version == 1 ? 16 : 8)
                        header.timescale = Int(try media.u32())
                    case "hdlr":
                        try media.skip(8)
                        header.isAudio = try media.fourCC() == "soun"
                    case "minf":
                        try forEachBox(&media) { type, info in
                            guard type == "stbl" else { return }
                            try forEachBox(&info) { type, table in
                                guard type == "stsd" else { return }
                                header.entry = try parseSampleDescriptions(&table)
                            }
                        }
                    default:
                        break
                    }
                }
            default:
                break
            }
        }
        return header
    }

    private func parseSampleDescriptions(_ stsd: inout ByteCursor) throws -> SampleEntry? {
        try stsd.skip(8)
        var entry: SampleEntry?
        try forEachBox(&stsd) { type, body in
            guard entry == nil else { return }
            switch type {
            case "mp4a", "Opus":
                entry = try parseAudioEntry(type, &body)
            case "enca":
                throw DemuxError.unsupported("the audio is encrypted")
            default:
                break
            }
        }
        return entry
    }

    private func parseAudioEntry(_ type: String, _ body: inout ByteCursor) throws -> SampleEntry {
        try body.skip(8)
        let version = try body.u16()
        try body.skip(6)
        var channels = Int(try body.u16())
        try body.skip(6)
        var sampleRate = Double(try body.u32()) / 65536
        // QuickTime's longer sound descriptions, in case a file uses them.
        if version == 1 { try body.skip(16) } else if version == 2 { try body.skip(36) }

        var entry: SampleEntry?
        func visit(_ type: String, _ box: inout ByteCursor) throws {
            switch type {
            case "esds":
                try box.skip(4)
                let cookie = try box.rest()
                guard let config = Self.audioSpecificConfig(inDescriptor: cookie) else {
                    throw DemuxError.unsupported("unreadable AAC setup")
                }
                if let parsed = Self.parseAudioSpecificConfig(config) {
                    sampleRate = parsed.sampleRate
                    if parsed.channels > 0 { channels = parsed.channels }
                }
                entry = SampleEntry(
                    codec: .aac, channels: channels, sampleRate: sampleRate, magicCookie: cookie,
                    audioSpecificConfig: config)
            case "dOps":
                let head = try Self.opusHead(fromSpecificBox: &box)
                entry = SampleEntry(
                    codec: .opus, channels: Int(head[9]), sampleRate: 48_000, magicCookie: head,
                    audioSpecificConfig: Data())
            case "wave":
                try forEachBox(&box, visit)
            default:
                break
            }
        }
        try forEachBox(&body, visit)
        guard let entry else { throw DemuxError.unsupported("'\(type)' audio without codec setup") }
        return entry
    }

    /// Finds the AudioSpecificConfig inside an MPEG-4 elementary stream descriptor.
    static func audioSpecificConfig(inDescriptor descriptor: Data) -> Data? {
        descriptor.withUnsafeBytes { raw -> Data? in
            var cursor = ByteCursor(raw)
            func length() throws -> Int {
                var value = 0
                for _ in 0..<4 {
                    let byte = try cursor.u8()
                    value = value << 7 | Int(byte & 0x7F)
                    if byte & 0x80 == 0 { break }
                }
                return value
            }
            do {
                guard try cursor.u8() == 0x03 else { return nil }
                _ = try length()
                try cursor.skip(2)
                let flags = try cursor.u8()
                if flags & 0x80 != 0 { try cursor.skip(2) }
                if flags & 0x40 != 0 { try cursor.skip(Int(try cursor.u8())) }
                if flags & 0x20 != 0 { try cursor.skip(2) }
                guard try cursor.u8() == 0x04 else { return nil }
                _ = try length()
                // Object type, stream type, buffer size, maximum and average bit rate.
                try cursor.skip(13)
                guard try cursor.u8() == 0x05 else { return nil }
                let count = try length()
                return try cursor.data(count)
            } catch {
                return nil
            }
        }
    }

    /// The output sample rate and channel count an AudioSpecificConfig states.
    static func parseAudioSpecificConfig(_ config: Data) -> (sampleRate: Double, channels: Int)? {
        let rates: [Double] = [
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
        ]
        var bits = BitReader(config)
        func objectType() -> UInt32? {
            guard let short = bits.read(5) else { return nil }
            guard short == 31 else { return short }
            return bits.read(6).map { $0 + 32 }
        }
        func rate() -> Double? {
            guard let index = bits.read(4) else { return nil }
            if index == 15 { return bits.read(24).map(Double.init) }
            return Int(index) < rates.count ? rates[Int(index)] : nil
        }
        guard let type = objectType(), var sampleRate = rate(), let channelConfig = bits.read(4) else { return nil }
        // Explicit SBR / PS (HE-AAC): the rate that follows is the one the decoder puts out.
        if type == 5 || type == 29, let extended = rate() { sampleRate = extended }
        let channels = channelConfig == 7 ? 8 : Int(channelConfig)
        return (sampleRate, channels)
    }

    /// Builds the `OpusHead` record from an MP4 `dOps` box (same fields, big-endian there, little-endian here).
    static func opusHead(fromSpecificBox box: inout ByteCursor) throws -> Data {
        try box.skip(1)
        let channels = try box.u8()
        let preSkip = try box.u16()
        let inputRate = try box.u32()
        let gain = try box.u16()
        let family = try box.u8()
        var head = Data("OpusHead".utf8)
        head.append(1)
        head.append(channels)
        head.append(contentsOf: [UInt8(preSkip & 0xFF), UInt8(preSkip >> 8)])
        head.append(contentsOf: (0..<4).map { UInt8((inputRate >> (8 * $0)) & 0xFF) })
        head.append(contentsOf: [UInt8(gain & 0xFF), UInt8(gain >> 8)])
        head.append(family)
        if family != 0 { head.append(try box.rest()) }
        return head
    }

    // MARK: Fragments (moof + mdat)

    private func parseFragment(_ moof: inout ByteCursor, moofStart: Int64) throws -> Fragment? {
        guard let trackID else { throw DemuxError.missingHeader }
        var result: Fragment?
        try forEachBox(&moof) { type, traf in
            guard type == "traf", result == nil else { return }
            var id: UInt32 = 0
            var duration = defaultDuration
            var size = defaultSize
            var baseDecodeTime: Int64 = 0
            var hasAbsoluteBase = false
            var runs: [Run] = []
            try forEachBox(&traf) { type, body in
                switch type {
                case "tfhd":
                    let flags = try body.u32() & 0xFF_FFFF
                    id = try body.u32()
                    if flags & 0x1 != 0 {
                        // A file offset: meaningless in a stream. The samples then start where the mdat does.
                        try body.skip(8)
                        hasAbsoluteBase = true
                    }
                    if flags & 0x2 != 0 { try body.skip(4) }
                    if flags & 0x8 != 0 { duration = try body.u32() }
                    if flags & 0x10 != 0 { size = try body.u32() }
                case "tfdt":
                    let version = try body.u8()
                    try body.skip(3)
                    baseDecodeTime = version == 1 ? Int64(clamping: try body.u64()) : Int64(try body.u32())
                case "trun":
                    let flags = try body.u32() & 0xFF_FFFF
                    let count = Int(try body.u32())
                    guard count <= 1_000_000 else { throw DemuxError.corrupt("a run of \(count) samples") }
                    var dataOffset: Int64?
                    if flags & 0x1 != 0 { dataOffset = Int64(try body.i32()) }
                    if flags & 0x4 != 0 { try body.skip(4) }
                    var samples: [(duration: Int, size: Int)] = []
                    samples.reserveCapacity(count)
                    for _ in 0..<count {
                        var sampleDuration = duration
                        var sampleSize = size
                        if flags & 0x100 != 0 { sampleDuration = try body.u32() }
                        if flags & 0x200 != 0 { sampleSize = try body.u32() }
                        if flags & 0x400 != 0 { try body.skip(4) }
                        if flags & 0x800 != 0 { try body.skip(4) }
                        guard let sampleSize else { throw DemuxError.corrupt("a sample without a size") }
                        samples.append((Int(sampleDuration ?? 0), Int(sampleSize)))
                    }
                    runs.append(Run(dataOffset: hasAbsoluteBase ? nil : dataOffset, samples: samples))
                default:
                    break
                }
            }
            guard id == trackID else { return }
            result = Fragment(moofStart: moofStart, baseDecodeTime: baseDecodeTime, runs: runs)
        }
        return result
    }

    private func extractPackets(_ payload: ByteCursor, payloadStart: Int64) throws -> [AudioPacket] {
        guard let fragment else { return [] }
        self.fragment = nil
        let fallbackDuration = track?.defaultPacketDuration ?? 1024
        var packets: [AudioPacket] = []
        var time = fragment.baseDecodeTime
        var position = 0
        for run in fragment.runs {
            if let dataOffset = run.dataOffset {
                let start = fragment.moofStart + dataOffset - payloadStart
                guard start >= 0, start <= Int64(payload.remaining) else {
                    throw DemuxError.corrupt("a run starts outside its mdat")
                }
                position = Int(start)
            }
            packets.reserveCapacity(packets.count + run.samples.count)
            for sample in run.samples {
                guard sample.size >= 0, sample.size <= payload.remaining - position else {
                    throw DemuxError.corrupt("a sample overruns its mdat")
                }
                var cursor = payload
                try cursor.skip(position)
                let duration = sample.duration > 0 ? sample.duration : fallbackDuration
                if sample.size > 0 {
                    packets.append(AudioPacket(decodeTime: time, duration: duration, data: try cursor.data(sample.size)))
                }
                position += sample.size
                time += Int64(duration)
            }
        }
        return packets
    }
}
