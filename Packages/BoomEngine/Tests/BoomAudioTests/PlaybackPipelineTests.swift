import Foundation
import Synchronization
import Testing
@testable import BoomAudio

// The pipeline is driven the way the hardware drives it: a thread pulls 480 frames (10 ms) at a time.

private func fixtureBuffer(_ name: String, demuxer: any AudioDemuxer, feedAll: Bool = true) throws -> (TrackBuffer, [AudioPacket]) {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures"))
    let data = try Data(contentsOf: url)
    let buffer = TrackBuffer()
    var all: [AudioPacket] = []
    for event in try demuxer.feed(data) {
        switch event {
        case .track(let info): buffer.setTrack(info)
        case .packets(let packets): all.append(contentsOf: packets)
        }
    }
    if feedAll {
        buffer.append(all)
        buffer.seal()
    }
    return (buffer, all)
}

private final class Heard: Sendable {
    let state = Mutex((left: [Float](), right: [Float](), events: [PipelineEvent](), stop: false))
}

/// Stands in for the audio hardware: pulls from the pipeline and keeps what it heard.
private final class Listener: @unchecked Sendable {
    private let pipeline: PlaybackPipeline
    private let heard = Heard()
    private let thread: Thread

    init(_ pipeline: PlaybackPipeline) {
        self.pipeline = pipeline
        let box = heard
        pipeline.setEventHandler { event in box.state.withLock { $0.events.append(event) } }
        let frames = 480
        thread = Thread {
            let left = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            let right = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            defer { left.deallocate(); right.deallocate() }
            while !box.state.withLock({ $0.stop }) {
                let silent = pipeline.render(left: left, right: right, frames: UInt32(frames))
                if !silent {
                    box.state.withLock {
                        $0.left.append(contentsOf: UnsafeBufferPointer(start: left, count: frames))
                        $0.right.append(contentsOf: UnsafeBufferPointer(start: right, count: frames))
                    }
                }
                // Each pull is 10 ms of audio; resting 0.6 ms makes the listener run about 16 times real time,
                // which the decode thread outruns comfortably, as it does the real hardware.
                usleep(600)
            }
        }
        thread.start()
    }

    var events: [PipelineEvent] { heard.state.withLock { $0.events } }
    var left: [Float] { heard.state.withLock { $0.left } }
    var heardFrames: Int { heard.state.withLock { $0.left.count } }

    func stop() { heard.state.withLock { $0.stop = true } }

    /// Waits until `condition` holds, for at most `seconds`.
    func wait(_ seconds: Double, until condition: @Sendable (Listener) -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition(self) { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return condition(self)
    }
}

private func tone(_ samples: ArraySlice<Float>, sampleRate: Double = 48_000) -> Double {
    var crossings = 0
    var previous: Float = 0
    for (offset, sample) in samples.enumerated() {
        if offset > 0, previous < 0, sample >= 0 { crossings += 1 }
        previous = sample
    }
    return Double(crossings) / (Double(samples.count) / sampleRate)
}

/// The longest run of near-silence inside `samples`, in frames.
private func longestQuietRun(_ samples: ArraySlice<Float>) -> Int {
    var longest = 0, run = 0
    for sample in samples {
        if abs(sample) < 0.002 { run += 1; longest = max(longest, run) } else { run = 0 }
    }
    return longest
}

@Suite(.serialized) struct PlaybackPipelineTests {
    @Test func playsASongFromStartToEnd() async throws {
        let (buffer, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        pipeline.load(item, at: 0, autoplay: true)
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        #expect(listener.events.first == .playing(item.id))
        // 601 packets of 960 frames, less the 312-frame lead-in; the hardware's last pull is padded with silence.
        let expected = 601 * 960 - 312
        #expect(abs(listener.heardFrames - expected) <= 1_500)
        let heard = listener.left
        #expect(abs(tone(heard[48_000..<480_000]) - 440) < 1)
        // The lead-in is gone: the tone is there from the first milliseconds.
        #expect((heard[0..<960].map(abs).max() ?? 0) > 0.02)
        #expect(abs(pipeline.position - 12.0) < 0.1)
    }

    @Test func jumpsLandOnTheAskedMoment() async throws {
        let (buffer, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        // Loaded but held, so that what is heard afterwards is exactly what follows the jump.
        pipeline.load(item, at: 0, autoplay: false)
        pipeline.seek(to: 9.0)
        // The new place is reported at once, not only after the decode thread has got there.
        #expect(pipeline.position == 9.0)
        #expect(pipeline.currentItemID == item.id)
        try await Task.sleep(for: .milliseconds(60))
        #expect(listener.heardFrames == 0)
        #expect(abs(pipeline.position - 9.0) < 0.001)
        pipeline.play()
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        // Three seconds remain after the nine-second mark (12.01 s less the lead-in).
        let expected = 601 * 960 - 312 - 9 * 48_000
        #expect(abs(listener.heardFrames - expected) <= 1_500)
        #expect(abs(pipeline.position - 12.0) < 0.1)
        #expect(abs(tone(listener.left[4_800..<120_000]) - 440) < 2)
        // The jump's run-up is discarded: no burst of decoder noise at the start.
        #expect((listener.left[0..<480].map(abs).max() ?? 0) < 1.0)
    }

    @Test func jumpsWhilePlayingCarryOnFromTheNewPlace() async throws {
        let (buffer, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        pipeline.load(item, at: 0, autoplay: true)
        #expect(await listener.wait(2) { $0.heardFrames > 24_000 })
        pipeline.seek(to: 2.0)
        try await Task.sleep(for: .milliseconds(30))
        pipeline.seek(to: 10.5)
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        #expect(abs(pipeline.position - 12.0) < 0.1)
        #expect(listener.events.last == .ended(item.id))
        #expect(!listener.events.contains { if case .failed = $0 { true } else { false } })
    }

    @Test func startsPartWayThroughWhenAsked() async throws {
        let (buffer, _) = try fixtureBuffer("sine-aac.frag.mp4", demuxer: FragmentedMP4Demuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        pipeline.load(item, at: 10.0, autoplay: true)
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        // The 44.1 kHz song comes out at 48 kHz: two seconds remain.
        #expect(listener.heardFrames > Int(1.9 * 48_000) && listener.heardFrames < Int(2.2 * 48_000))
        #expect(abs(tone(listener.left[4_800..<86_400]) - 440) < 2)
    }

    @Test func theNextSongJoinsOnWithoutAGap() async throws {
        let (first, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let (second, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let a = PipelineItem(buffer: first)
        let b = PipelineItem(buffer: second)
        pipeline.load(a, at: 9.0, autoplay: true)
        pipeline.setNext(b)
        #expect(await listener.wait(8) { $0.events.contains(.ended(b.id)) })
        #expect(listener.events == [.playing(a.id), .advanced(b.id), .playing(b.id), .ended(b.id)])
        // Three seconds of the first song, then all of the second.
        let expected = Int(3.0 * 48_000) + 601 * 960 - 312
        #expect(abs(listener.heardFrames - expected) <= 2_400)
        // Around the join: no dropout longer than the codec's own padding (well under 30 ms).
        let join = Int(3.0 * 48_000)
        let around = listener.left[(join - 9_600)..<(join + 9_600)]
        #expect(longestQuietRun(around) < 1_440)
    }

    @Test func waitsWhenAudioRunsOutAndCarriesOnWhenItArrives() async throws {
        let (buffer, packets) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer(), feedAll: false)
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        pipeline.load(item, at: 0, autoplay: true)
        // Nothing yet: no sound, and the pipeline says it is waiting.
        try await Task.sleep(for: .milliseconds(80))
        #expect(listener.events == [.waiting(item.id)])
        #expect(listener.heardFrames == 0)
        buffer.append(Array(packets[0..<150]))
        #expect(await listener.wait(3) { $0.events.filter { $0 == .waiting(item.id) }.count == 2 })
        #expect(listener.events.dropFirst().first == .playing(item.id))
        buffer.append(Array(packets[150...]))
        buffer.seal()
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        #expect(listener.events == [
            .waiting(item.id), .playing(item.id), .waiting(item.id), .playing(item.id), .ended(item.id),
        ])
        #expect(abs(listener.heardFrames - (601 * 960 - 312)) <= 1_500)
    }

    @Test func pauseHoldsThePlace() async throws {
        let (buffer, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let item = PipelineItem(buffer: buffer)
        pipeline.load(item, at: 0, autoplay: true)
        #expect(await listener.wait(2) { $0.heardFrames > 48_000 })
        pipeline.pause()
        try await Task.sleep(for: .milliseconds(120))
        let held = pipeline.position
        let heard = listener.heardFrames
        try await Task.sleep(for: .milliseconds(150))
        #expect(pipeline.position == held)
        #expect(listener.heardFrames == heard)
        pipeline.play()
        #expect(await listener.wait(5) { $0.events.contains(.ended(item.id)) })
        #expect(abs(listener.heardFrames - (601 * 960 - 312)) <= 2_000)
    }
}

extension PlaybackPipelineTests {
    /// The listener jumped ahead in a song: the skipped part is not in hand, the end is. Playback must run to the
    /// end and into the next song without waiting for the skipped part.
    @Test func theEndOfASongDoesNotWaitForItsSkippedMiddle() async throws {
        let (_, packets) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let (first, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer(), feedAll: false)
        first.append(Array(packets[0..<100]))
        first.append(Array(packets[500...]))
        first.seal()
        #expect(!first.isComplete)
        #expect(first.hasReachedEnd(at: 601))
        let (second, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let pipeline = PlaybackPipeline()
        let listener = Listener(pipeline)
        defer { listener.stop(); pipeline.stop() }
        let a = PipelineItem(buffer: first)
        let b = PipelineItem(buffer: second)
        pipeline.load(a, at: 10.5, autoplay: true)
        pipeline.setNext(b)
        #expect(await listener.wait(8) { $0.events.contains(.ended(b.id)) })
        #expect(listener.events == [.playing(a.id), .advanced(b.id), .playing(b.id), .ended(b.id)])
        // A second and a half of the first song, then all of the second.
        let expected = Int(1.5 * 48_000) + 601 * 960 - 312
        #expect(abs(listener.heardFrames - expected) <= 2_400)
    }
}

@Suite struct TrackBufferTests {
    @Test func knowsWhatItHoldsAndWhatIsMissing() throws {
        let (_, packets) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer())
        let (shell, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer(), feedAll: false)
        shell.append(Array(packets[0..<100]))
        // Twelve seconds of 20 ms packets, give or take the last one.
        #expect(shell.expectedPacketCount == 600 || shell.expectedPacketCount == 601)
        shell.append(Array(packets[300..<400]))
        shell.append(Array(packets[50..<120]))
        #expect(shell.heldPacketCount == 220)
        #expect(shell.contiguousEnd(from: 0) == 120)
        #expect(shell.contiguousEnd(from: 350) == 400)
        #expect(shell.contiguousEnd(from: 200) == 200)
        #expect(shell.firstGap(from: 0) == 120)
        #expect(shell.firstGap(from: 320) == 400)
        #expect(!shell.isComplete)
        #expect(shell.packets(from: 118, limit: 10).count == 2)
        #expect(shell.packets(from: 120, limit: 10).isEmpty)
        shell.append(packets)
        shell.seal()
        #expect(shell.isComplete)
        #expect(shell.firstGap(from: 0) == nil)
        #expect(shell.hasReachedEnd(at: 601))
        #expect(!shell.hasReachedEnd(at: 600))

        // The end is known as soon as the tail is in and sealed, whatever is missing before it.
        let (holed, _) = try fixtureBuffer("sine-opus.webm", demuxer: WebMDemuxer(), feedAll: false)
        holed.append(Array(packets[0..<10]))
        // Inside the song nothing counts as its end, however little is in hand.
        #expect(!holed.hasReachedEnd(at: 10))
        #expect(!holed.hasReachedEnd(at: 599))
        holed.append(Array(packets[590...]))
        holed.seal()
        #expect(!holed.isComplete)
        #expect(holed.hasReachedEnd(at: 601))
        #expect(!holed.hasReachedEnd(at: 300))
        #expect(holed.firstGap(from: 595) == 10)
    }

    @Test func survivesBeingWrittenToDiskAndReadBack() throws {
        let (buffer, packets) = try fixtureBuffer("sine-aac.frag.mp4", demuxer: FragmentedMP4Demuxer())
        let file = try #require(buffer.serialized())
        let restored = try #require(TrackBuffer(serialized: file))
        #expect(restored.isComplete)
        #expect(restored.info == buffer.info)
        #expect(restored.heldPacketCount == packets.count)
        #expect(restored.packets(from: 0, limit: 5) == packets.prefix(5).map(\.data))
        #expect(restored.packets(from: packets.count - 1, limit: 5) == [packets[packets.count - 1].data])
        #expect(TrackBuffer(serialized: file.dropLast(10)) == nil)
        #expect(TrackBuffer(serialized: Data("nonsense".utf8)) == nil)
    }
}
