import AVFoundation
import Foundation
import Testing
@testable import BoomAudio

// The fixtures are 12-second stereo test tones made with ffmpeg in the same shapes YouTube serves:
//   sine-opus.webm      Opus, 48 kHz, 20 ms packets, WebM clusters (ffprobe: 601 packets, 247,614 bytes)
//   sine-aac.frag.mp4   AAC-LC, 44.1 kHz, fragmented MP4 (ffprobe: 518 packets, 192,034 bytes)
// Left channel 440 Hz, right channel 660 Hz.

private struct Demuxed {
    var track: AudioTrackInfo
    var packets: [AudioPacket]
}

private func fixture(_ name: String) throws -> Data {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

private func demux(_ demuxer: any AudioDemuxer, pieces: [Data]) throws -> Demuxed {
    var track: AudioTrackInfo?
    var packets: [AudioPacket] = []
    for piece in pieces {
        for event in try demuxer.feed(piece) {
            switch event {
            case .track(let info): track = info
            case .packets(let more): packets.append(contentsOf: more)
            }
        }
    }
    return Demuxed(track: try #require(track), packets: packets)
}

/// Cuts `data` into pieces of random sizes, the way a network delivers it.
private func shred(_ data: Data, seed: UInt64, largest: Int) -> [Data] {
    var state = seed
    func next() -> Int {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Int(state >> 33)
    }
    var pieces: [Data] = []
    var offset = 0
    while offset < data.count {
        let size = min(data.count - offset, 1 + next() % largest)
        pieces.append(data.subdata(in: offset..<offset + size))
        offset += size
    }
    return pieces
}

/// Counts upward zero crossings per second: the tone's frequency.
private func frequency(of samples: UnsafeBufferPointer<Float>, sampleRate: Double) -> Double {
    var crossings = 0
    for index in 1..<samples.count where samples[index - 1] < 0 && samples[index] >= 0 { crossings += 1 }
    return Double(crossings) / (Double(samples.count) / sampleRate)
}

@Suite struct WebMOpusTests {
    @Test func readsTheHeaderAndEveryPacket() throws {
        let data = try fixture("sine-opus.webm")
        let result = try demux(WebMDemuxer(), pieces: [data])
        #expect(result.track.codec == .opus)
        #expect(result.track.timescale == 48_000)
        #expect(result.track.channels == 2)
        #expect(result.track.leadIn == 312)
        #expect(result.track.magicCookie.starts(with: Data("OpusHead".utf8)))
        let duration = try #require(result.track.duration)
        #expect(abs(duration - 12.0) < 0.1)
        #expect(result.packets.count == 601)
        #expect(result.packets.reduce(0) { $0 + $1.data.count } == 247_614)
    }

    @Test func packetTimesAreExactAndGapless() throws {
        let result = try demux(WebMDemuxer(), pieces: [try fixture("sine-opus.webm")])
        for (index, packet) in result.packets.enumerated() {
            #expect(packet.duration == 960)
            #expect(packet.decodeTime == Int64(index) * 960)
        }
    }

    @Test func piecesOfAnySizeGiveTheSamePackets() throws {
        let data = try fixture("sine-opus.webm")
        let whole = try demux(WebMDemuxer(), pieces: [data])
        for seed in [1, 2, 3] as [UInt64] {
            let shredded = try demux(WebMDemuxer(), pieces: shred(data, seed: seed, largest: seed == 1 ? 7 : 5000))
            #expect(shredded.packets == whole.packets)
            #expect(shredded.track == whole.track)
        }
    }

    @Test func aResetMidClusterPicksUpAtTheNextCluster() throws {
        let data = try fixture("sine-opus.webm")
        let whole = try demux(WebMDemuxer(), pieces: [data])
        // Find where the third cluster starts (the element id 1F 43 B6 75).
        let marker = Data([0x1F, 0x43, 0xB6, 0x75])
        var starts: [Int] = []
        var search = data.startIndex..<data.endIndex
        while let found = data.range(of: marker, in: search) {
            starts.append(found.lowerBound)
            search = found.upperBound..<data.endIndex
        }
        try #require(starts.count >= 3)
        let demuxer = WebMDemuxer()
        // The player appends the header and part of the first cluster, then jumps.
        _ = try demuxer.feed(data.subdata(in: 0..<starts[0] + 5000))
        demuxer.reset()
        var later: [AudioPacket] = []
        for event in try demuxer.feed(data.subdata(in: starts[2]..<data.count)) {
            if case .packets(let packets) = event { later.append(contentsOf: packets) }
        }
        let first = try #require(later.first)
        let expected = whole.packets.filter { $0.decodeTime >= first.decodeTime }
        #expect(first.decodeTime > 0)
        #expect(later == expected)
    }

    @Test func decodesToTheTestTones() throws {
        let result = try demux(WebMDemuxer(), pieces: [try fixture("sine-opus.webm")])
        let decoder = try PacketDecoder(track: result.track)
        #expect(decoder.outputFormat.sampleRate == 48_000)
        #expect(decoder.framesPerPacket == 960)
        var left: [Float] = []
        var right: [Float] = []
        // In batches, as the player will.
        for start in stride(from: 0, to: result.packets.count, by: 50) {
            let batch = result.packets[start..<min(start + 50, result.packets.count)].map(\.data)
            guard let pcm = try decoder.decode(batch), let channels = pcm.floatChannelData else { continue }
            left.append(contentsOf: UnsafeBufferPointer(start: channels[0], count: Int(pcm.frameLength)))
            right.append(contentsOf: UnsafeBufferPointer(start: channels[1], count: Int(pcm.frameLength)))
        }
        #expect(abs(left.count - 601 * 960) <= 960)
        let body = 48_000..<(48_000 * 11)
        let leftTone = left[body].withUnsafeBufferPointer { frequency(of: $0, sampleRate: 48_000) }
        let rightTone = right[body].withUnsafeBufferPointer { frequency(of: $0, sampleRate: 48_000) }
        #expect(abs(leftTone - 440) < 1)
        #expect(abs(rightTone - 660) < 1)
        let peak = left[body].map(abs).max() ?? 0
        #expect(peak > 0.05 && peak <= 1.0)
    }
}

@Suite struct FragmentedMP4AACTests {
    @Test func readsTheHeaderAndEveryPacket() throws {
        let result = try demux(FragmentedMP4Demuxer(), pieces: [try fixture("sine-aac.frag.mp4")])
        #expect(result.track.codec == .aac)
        #expect(result.track.timescale == 44_100)
        #expect(result.track.sampleRate == 44_100)
        #expect(result.track.channels == 2)
        // AAC-LC, 44.1 kHz, stereo (ffmpeg adds an extension after these two bytes).
        #expect(result.track.audioSpecificConfig.prefix(2) == Data([0x12, 0x10]))
        #expect(result.packets.count == 518)
        #expect(result.packets.reduce(0) { $0 + $1.data.count } == 192_034)
    }

    @Test func packetTimesAreGapless() throws {
        let result = try demux(FragmentedMP4Demuxer(), pieces: [try fixture("sine-aac.frag.mp4")])
        var expected: Int64 = 0
        for packet in result.packets {
            #expect(packet.decodeTime == expected)
            expected += Int64(packet.duration)
        }
        #expect(result.packets.dropLast().allSatisfy { $0.duration == 1024 })
    }

    @Test func piecesOfAnySizeGiveTheSamePackets() throws {
        let data = try fixture("sine-aac.frag.mp4")
        let whole = try demux(FragmentedMP4Demuxer(), pieces: [data])
        for seed in [1, 2, 3] as [UInt64] {
            let pieces = shred(data, seed: seed, largest: seed == 1 ? 7 : 5000)
            let shredded = try demux(FragmentedMP4Demuxer(), pieces: pieces)
            #expect(shredded.packets == whole.packets)
            #expect(shredded.track == whole.track)
        }
    }

    @Test func aResetMidFragmentPicksUpAtTheNextFragment() throws {
        let data = try fixture("sine-aac.frag.mp4")
        let whole = try demux(FragmentedMP4Demuxer(), pieces: [data])
        let marker = Data("moof".utf8)
        var starts: [Int] = []
        var search = data.startIndex..<data.endIndex
        while let found = data.range(of: marker, in: search) {
            starts.append(found.lowerBound - 4)
            search = found.upperBound..<data.endIndex
        }
        try #require(starts.count >= 3)
        let demuxer = FragmentedMP4Demuxer()
        _ = try demuxer.feed(data.subdata(in: 0..<starts[0] + 3000))
        demuxer.reset()
        var later: [AudioPacket] = []
        for event in try demuxer.feed(data.subdata(in: starts[2]..<data.count)) {
            if case .packets(let packets) = event { later.append(contentsOf: packets) }
        }
        let first = try #require(later.first)
        #expect(first.decodeTime > 0)
        #expect(later == whole.packets.filter { $0.decodeTime >= first.decodeTime })
    }

    @Test func garbageIsRefusedNotCrashedOn() throws {
        let demuxer = FragmentedMP4Demuxer()
        #expect(throws: DemuxError.self) {
            _ = try demuxer.feed(Data([0xFF, 0xFF, 0xFF, 0xFF, 0x6D, 0x64, 0x61, 0x74, 0, 0, 0, 0]))
        }
        // After a refusal the demuxer starts clean.
        let result = try demux(demuxer, pieces: [try fixture("sine-aac.frag.mp4")])
        #expect(result.packets.count == 518)
    }

    @Test func decodesToTheTestTones() throws {
        let result = try demux(FragmentedMP4Demuxer(), pieces: [try fixture("sine-aac.frag.mp4")])
        let decoder = try PacketDecoder(track: result.track)
        #expect(decoder.outputFormat.sampleRate == 44_100)
        #expect(decoder.framesPerPacket == 1024)
        var left: [Float] = []
        var right: [Float] = []
        for start in stride(from: 0, to: result.packets.count, by: 43) {
            let batch = result.packets[start..<min(start + 43, result.packets.count)].map(\.data)
            guard let pcm = try decoder.decode(batch), let channels = pcm.floatChannelData else { continue }
            left.append(contentsOf: UnsafeBufferPointer(start: channels[0], count: Int(pcm.frameLength)))
            right.append(contentsOf: UnsafeBufferPointer(start: channels[1], count: Int(pcm.frameLength)))
        }
        #expect(abs(left.count - 518 * 1024) <= 2 * 1024)
        let body = 44_100..<(44_100 * 11)
        let leftTone = left[body].withUnsafeBufferPointer { frequency(of: $0, sampleRate: 44_100) }
        let rightTone = right[body].withUnsafeBufferPointer { frequency(of: $0, sampleRate: 44_100) }
        #expect(abs(leftTone - 440) < 1)
        #expect(abs(rightTone - 660) < 1)
    }
}

@Suite struct OpusPacketLengthTests {
    @Test func lengthsFollowTheFirstByte() {
        // 0xFC: CELT full band, 20 ms, stereo, one frame: what YouTube sends.
        #expect(WebMDemuxer.opusPacketSamples(toc: 0xFC, second: 0) == 960)
        // SILK 60 ms, one frame.
        #expect(WebMDemuxer.opusPacketSamples(toc: 0x18, second: 0) == 2880)
        // CELT 10 ms, two frames.
        #expect(WebMDemuxer.opusPacketSamples(toc: 0xF1, second: 0) == 960)
        // CELT 2.5 ms, a counted packet of 8 frames.
        #expect(WebMDemuxer.opusPacketSamples(toc: 0xE3, second: 8) == 960)
    }
}

/// Checks against streams captured from the real player, when a folder of them is at hand:
///   BOOM_CAPTURES=/path/with/real-opus.webm+real-aac.mp4 swift test
/// The captures are music and are never added to the repository.
@Suite struct RealCaptureTests {
    private static var folder: URL? {
        ProcessInfo.processInfo.environment["BOOM_CAPTURES"].map { URL(fileURLWithPath: $0) }
    }

    private func check(_ name: String, demuxer: any AudioDemuxer) throws {
        guard let folder = Self.folder else { return }
        let url = folder.appending(path: name)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let data = try Data(contentsOf: url)
        let whole = try demux(demuxer, pieces: shred(data, seed: 9, largest: 200_000))
        let track = whole.track
        var expected: Int64 = 0
        var gaps = 0
        for packet in whole.packets {
            if packet.decodeTime != expected { gaps += 1 }
            expected = packet.decodeTime + Int64(packet.duration)
        }
        let seconds = Double(expected) / Double(track.timescale)
        print("REAL \(name): \(track.codec) \(track.sampleRate) Hz, \(whole.packets.count) packets, "
            + "\(whole.packets.reduce(0) { $0 + $1.data.count }) bytes, \(seconds) s, gaps \(gaps), "
            + "leadIn \(track.leadIn), stated \(track.duration ?? -1) s, default \(track.defaultPacketDuration ?? -1)")
        #expect(gaps == 0)
        #expect(whole.packets.count > 1000)

        let decoder = try PacketDecoder(track: track)
        var frames = 0
        var peak: Float = 0
        for start in stride(from: 0, to: min(whole.packets.count, 3000), by: 100) {
            let batch = whole.packets[start..<min(start + 100, whole.packets.count)].map(\.data)
            guard let pcm = try decoder.decode(batch), let channels = pcm.floatChannelData else { continue }
            frames += Int(pcm.frameLength)
            for sample in UnsafeBufferPointer(start: channels[0], count: Int(pcm.frameLength)) { peak = max(peak, abs(sample)) }
        }
        print("REAL \(name): decoded \(frames) frames at \(decoder.outputFormat.sampleRate) Hz, peak \(peak)")
        #expect(frames > 0)
        #expect(peak > 0.05)
    }

    /// The app's decoding against the reference Opus decoder (libopus), sample for sample. Make the reference with
    ///   ffmpeg -request_sample_fmt flt -c:a libopus -i real-opus.webm -t 60 -f f32le -ac 2 -ar 48000 real-opus.reference.f32
    /// (`-request_sample_fmt flt` matters: without it ffmpeg has libopus answer in 16 bits, and the comparison then
    /// measures that rounding, about -85 dB.) What the listener hears is what the stream holds: nothing is lost or
    /// coloured between the stream and the equaliser. Measured: -136.6 dB, the largest difference one step of a
    /// 32-bit float.
    @Test func decodingMatchesTheReferenceDecoder() throws {
        guard let folder = Self.folder else { return }
        let stream = folder.appending(path: "real-opus.webm"), wanted = folder.appending(path: "real-opus.reference.f32")
        guard FileManager.default.fileExists(atPath: stream.path), FileManager.default.fileExists(atPath: wanted.path)
        else { return }
        let whole = try demux(WebMDemuxer(), pieces: [try Data(contentsOf: stream)])
        let decoder = try PacketDecoder(track: whole.track)
        var left: [Float] = [], right: [Float] = []
        // A minute of the song, as the reference holds.
        let packets = whole.packets.prefix(3100)
        for start in stride(from: 0, to: packets.count, by: 50) {
            let batch = packets[start..<min(start + 50, packets.count)].map(\.data)
            guard let pcm = try decoder.decode(batch), let channels = pcm.floatChannelData else { continue }
            left.append(contentsOf: UnsafeBufferPointer(start: channels[0], count: Int(pcm.frameLength)))
            right.append(contentsOf: UnsafeBufferPointer(start: channels[1], count: Int(pcm.frameLength)))
        }
        let reference: [Float] = try Data(contentsOf: wanted).withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        let frames = reference.count / 2
        // The two decoders may drop a different number of start-up frames: find how they line up, then compare.
        func difference(shift: Int, from: Int, count: Int) -> (error: Double, signal: Double, worst: Float) {
            var error = 0.0, signal = 0.0
            var worst: Float = 0
            for index in from..<(from + count) {
                let mine = left[index + shift], theirs = reference[2 * index]
                error += Double(mine - theirs) * Double(mine - theirs)
                signal += Double(theirs) * Double(theirs)
                worst = max(worst, abs(mine - theirs))
                let mineRight = right[index + shift], theirsRight = reference[2 * index + 1]
                error += Double(mineRight - theirsRight) * Double(mineRight - theirsRight)
                signal += Double(theirsRight) * Double(theirsRight)
                worst = max(worst, abs(mineRight - theirsRight))
            }
            return (error, signal, worst)
        }
        var shift = 0
        var least = Double.infinity
        for candidate in 0...2000 {
            let trial = difference(shift: candidate, from: 48_000, count: 4_800).error
            if trial < least {
                least = trial
                shift = candidate
            }
        }
        let count = min(frames, left.count - shift) - 96_000
        let result = difference(shift: shift, from: 48_000, count: count)
        let decibels = 10 * log10(max(result.error, 1e-30) / result.signal)
        print("REAL decode: \(count) frames compared from frame \(shift) on; the difference from the reference decoder is "
            + "\(String(format: "%.1f", decibels)) dB below the music, largest single difference \(result.worst)")
        #expect(count > 2_000_000)
        // Far below what even 24-bit audio can hold (-146 dB is its floor; 16-bit's is -98 dB): the same samples.
        #expect(decibels < -120)
    }

    @Test func realOpus() throws { try check("real-opus.webm", demuxer: WebMDemuxer()) }
    @Test func realAAC() throws { try check("real-aac.mp4", demuxer: FragmentedMP4Demuxer()) }
}
