import Accelerate
import AVFoundation
import CBoomAudio
import Foundation
import Synchronization

/// A song handed to the pipeline: its audio (which may still be arriving) and how loud to play it.
public struct PipelineItem: Sendable {
    public let id: UUID
    public let buffer: TrackBuffer
    /// A linear factor applied to this song only (loudness normalisation). 1 leaves it as recorded.
    public var gain: Float

    public init(id: UUID = UUID(), buffer: TrackBuffer, gain: Float = 1) {
        self.id = id
        self.buffer = buffer
        self.gain = gain
    }
}

public enum PipelineEvent: Sendable, Equatable {
    /// The song's audio is now being heard (after a load, a jump, or a wait for more audio).
    case playing(UUID)
    /// Playback moved on to the next song. Always followed at once by `.playing` (it ran in without a gap) or
    /// `.waiting` (its audio is not in hand yet) for that song.
    case advanced(UUID)
    /// There is nothing to hear at the song's current place yet (after a load or a jump), or the audio in hand ran
    /// out. Playback starts by itself when the audio arrives.
    case waiting(UUID)
    /// The last song played to its end.
    case ended(UUID)
    case failed(UUID, String)
}

/// Turns songs' compressed packets into the stream of samples the audio hardware pulls.
///
/// A decode thread keeps a ring buffer a few seconds ahead of the render thread. Everything is converted to one
/// fixed format (48 kHz stereo float): YouTube's Opus is already 48 kHz, so it passes through unresampled, and one
/// song can run into the next without the output ever being reconfigured.
public final class PlaybackPipeline: @unchecked Sendable {
    public static let sampleRate: Double = 48_000
    /// About 5.5 seconds: generous, since jumps swap in a fresh ring rather than waiting for this one to drain.
    private static let ringFrames: UInt32 = 1 << 18
    /// Audio that must be in hand before playback starts or restarts after running dry.
    private static let startFrames: UInt32 = 4_800
    private static let restartFrames: UInt32 = 19_200

    /// Decoding state of one song.
    private final class Deck {
        /// Where the deck is in its life. It starts (and restarts, after a jump) unplaced.
        enum Stage {
            /// Not decoding yet: waiting to be set to start at this many seconds into the song, which needs the
            /// stream's header and first packet to have arrived.
            case unplaced(startAt: TimeInterval)
            /// Decoding packets into the ring.
            case decoding
            /// Every packet of the song has been decoded (or it could not be decoded at all).
            case finished
        }

        let item: PipelineItem
        var stage: Stage
        // Made once the stream's header is in, and kept for the deck's life.
        var decoder: PacketDecoder?
        var resampler: AVAudioConverter?
        var resampled: AVAudioPCMBuffer?
        var nextPacket = 0
        /// Frames (at 48 kHz) still to drop before writing: the encoder's lead-in, or the run-up to a jump target.
        var discard = 0
        /// Where in the song, in 48 kHz frames, this deck's first written frame lies.
        var startOffset: Int64 = 0
        /// The ring's write count when this deck's first frame went in.
        var ringStart: UInt64 = 0
        var framesWritten: UInt64 = 0

        init(item: PipelineItem, startAt: TimeInterval) {
            self.item = item
            stage = .unplaced(startAt: startAt)
        }

        var isFinished: Bool {
            if case .finished = stage { return true }
            return false
        }

        /// Back to the start of its life, to begin at `seconds` in a fresh ring.
        func restart(at seconds: TimeInterval) {
            stage = .unplaced(startAt: seconds)
            framesWritten = 0
            ringStart = 0
            decoder?.reset()
            resampler?.reset()
        }
    }

    /// A ring pointer that may be handed between threads: the ring's own functions are what make that safe.
    private struct RingRef: @unchecked Sendable {
        let raw: OpaquePointer
    }

    private struct Timeline: Sendable {
        var ring: RingRef?
        var itemID: UUID?
        var startOffset: Int64 = 0
        var ringStart: UInt64 = 0
        var held: TimeInterval = 0
    }

    private let queue = DispatchQueue(label: "boom.pipeline", qos: .userInitiated)
    private let renderer: OpaquePointer
    private let timeline = Mutex(Timeline())
    private var timer: DispatchSourceTimer?

    // Everything below is touched on `queue` only.
    private var decks: [Deck] = []
    private var ring: OpaquePointer?
    /// What is coming out. Changed only in `setOutput(_:)`.
    private enum Output {
        /// Paused by the listener: silent, and the place is kept.
        case held
        /// Meant to play, but the audio at this place is not in hand yet: silent until it arrives.
        case waiting
        /// Audio is being heard.
        case sounding
        /// The last song played to its end.
        case ended
    }
    private var output = Output.held
    private var eventHandler: (@Sendable (PipelineEvent) -> Void)?
    private let outputFormat = AVAudioFormat(standardFormatWithSampleRate: PlaybackPipeline.sampleRate, channels: 2)!

    public init() {
        guard let renderer = boom_renderer_create() else { fatalError("out of memory creating the renderer") }
        self.renderer = renderer
    }

    deinit {
        timer?.cancel()
        if let ring = boom_renderer_swap_ring(renderer, nil) { boom_ring_destroy(ring) }
        boom_renderer_destroy(renderer)
    }

    // MARK: The render thread

    /// Fills the hardware's buffers. Real-time safe. Returns true when it wrote only silence.
    @inline(__always)
    public func render(left: UnsafeMutablePointer<Float>, right: UnsafeMutablePointer<Float>, frames: UInt32) -> Bool {
        boom_renderer_render(renderer, left, right, frames)
    }

    // MARK: Control (any thread)

    public func setEventHandler(_ handler: (@Sendable (PipelineEvent) -> Void)?) {
        queue.async { self.eventHandler = handler }
    }

    /// Starts on a song, dropping whatever was playing.
    public func load(_ item: PipelineItem, at seconds: TimeInterval, autoplay: Bool) {
        // The new song and place are reported from this moment, before the decode thread has caught up.
        let start = max(0, seconds)
        timeline.withLock { $0 = Timeline(ring: nil, itemID: item.id, startOffset: 0, ringStart: 0, held: start) }
        queue.async {
            self.decks = [Deck(item: item, startAt: 0)]
            self.rebuild(at: max(0, seconds), then: autoplay ? .waiting : .held)
            self.startTimer()
        }
    }

    /// Says what follows the current song, so it joins on without a gap. Nil: nothing follows.
    public func setNext(_ item: PipelineItem?) {
        queue.async {
            guard let current = self.decks.first else { return }
            if self.decks.count > 1, self.decks[1].item.id == item?.id, self.decks.count == 2 { return }
            // Some of the old follower may already be queued in the ring behind the current song.
            let alreadyQueued = self.decks.dropFirst().contains { $0.framesWritten > 0 }
            self.decks = [current] + (item.map { [Deck(item: $0, startAt: 0)] } ?? [])
            if alreadyQueued {
                self.rebuild(at: self.currentSeconds(), then: self.output == .held ? .held : .waiting)
            } else if let ring = self.ring, item != nil, self.output != .ended {
                // Reopen a ring that was closed because nothing followed.
                boom_ring_set_finished(ring, false)
            }
        }
    }

    public func play() {
        queue.async {
            guard self.output == .held else { return }
            self.setOutput(.waiting)
            self.tick()
        }
    }

    public func pause() {
        queue.async {
            guard self.output == .waiting || self.output == .sounding else { return }
            self.setOutput(.held)
        }
    }

    public func seek(to seconds: TimeInterval) {
        // `position` answers with the target at once, so a scrubber never sees the old place after letting go.
        let target = max(0, seconds)
        timeline.withLock { timeline in
            timeline.ring = nil
            timeline.held = target
        }
        queue.async {
            guard !self.decks.isEmpty else { return }
            // A jump keeps the listener's intent: playing carries on from the new place, paused stays paused.
            // After the end, the jump leaves it paused; `play()` starts it.
            self.rebuild(
                at: max(0, seconds), then: self.output == .waiting || self.output == .sounding ? .waiting : .held)
        }
    }

    public func stop() {
        queue.async {
            self.setOutput(.held)
            self.decks = []
            self.timer?.cancel()
            self.timer = nil
            self.retire(boom_renderer_swap_ring(self.renderer, nil))
            self.ring = nil
            self.timeline.withLock { $0 = Timeline() }
        }
    }

    /// The overall level as a linear factor (preamp, amplifier, headroom).
    public func setGain(_ gain: Float) {
        boom_renderer_set_gain(renderer, gain)
    }

    /// Changes the current or next song's own level (loudness normalisation switched on or off).
    public func setItemGain(_ gain: Float, for id: UUID) {
        queue.async {
            guard let index = self.decks.firstIndex(where: { $0.item.id == id }) else { return }
            let old = self.decks[index]
            guard old.item.gain != gain else { return }
            var item = old.item
            item.gain = gain
            let needsRebuild = old.framesWritten > 0
            self.decks[index] = Deck(item: item, startAt: 0)
            if needsRebuild {
                self.rebuild(
                    at: self.currentSeconds(),
                    then: self.output == .waiting || self.output == .sounding ? .waiting : .held)
            }
        }
    }

    /// Seconds into the song being heard.
    public var position: TimeInterval {
        timeline.withLock { timeline in
            guard let ring = timeline.ring?.raw else { return timeline.held }
            let read = boom_ring_frames_read(ring)
            let frames = timeline.startOffset + Int64(read) - Int64(timeline.ringStart)
            return max(0, Double(frames) / Self.sampleRate)
        }
    }

    /// The song being heard.
    public var currentItemID: UUID? { timeline.withLock { $0.itemID } }

    // MARK: Inside (on `queue`)

    private func emit(_ event: PipelineEvent) {
        eventHandler?(event)
    }

    private func currentSeconds() -> TimeInterval {
        position
    }

    private func startTimer() {
        guard timer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(10), repeating: .milliseconds(20), leeway: .milliseconds(4))
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        self.timer = timer
    }

    /// Frees a ring the render thread may still be reading for one more call.
    private func retire(_ ring: OpaquePointer?) {
        guard let ring else { return }
        let reference = RingRef(raw: ring)
        queue.asyncAfter(deadline: .now() + 1) { boom_ring_destroy(reference.raw) }
    }

    /// The one place the output state changes: it also tells the render thread to sound or hold.
    private func setOutput(_ next: Output) {
        output = next
        boom_renderer_set_playing(renderer, next == .sounding)
    }

    /// Starts afresh with the first deck at `start` seconds: a new ring, every deck back to unplaced, then swapped
    /// in with a fade. `next` is the state to carry on in: `.waiting` to play as soon as audio is there, `.held` to
    /// stay paused.
    private func rebuild(at start: TimeInterval, then next: Output) {
        guard let first = decks.first, let fresh = boom_ring_create(Self.ringFrames, 2) else { return }
        for deck in decks { deck.restart(at: deck === first ? start : 0) }

        // Fade out what is sounding before the swap, so a jump never clicks.
        if output == .sounding {
            setOutput(.held)
            let deadline = DispatchTime.now() + .milliseconds(40)
            while !boom_renderer_is_idle(renderer), DispatchTime.now() < deadline { usleep(1_000) }
        }
        setOutput(next)
        ring = fresh
        let snapshot = Timeline(
            ring: RingRef(raw: fresh), itemID: first.item.id, startOffset: Int64(start * Self.sampleRate),
            ringStart: 0, held: start)
        timeline.withLock { $0 = snapshot }
        decodeAhead(budget: 24_000)
        retire(boom_renderer_swap_ring(renderer, fresh))
        tick()
        // Nothing to hear at the new place yet: the audio there is still on its way.
        if output == .waiting { emit(.waiting(first.item.id)) }
    }

    private func tick() {
        guard let ring, !decks.isEmpty else { return }
        decodeAhead(budget: 48_000)

        // Has the render thread crossed into the next song?
        let read = boom_ring_frames_read(ring)
        while decks.count > 1 {
            let next = decks[1]
            let crossed = next.framesWritten > 0 && read >= next.ringStart
            let waitingAtTheJoin = decks[0].isFinished && next.framesWritten == 0
                && boom_ring_available_read(ring) == 0
            guard crossed || waitingAtTheJoin else { break }
            decks.removeFirst()
            let snapshot = Timeline(
                ring: RingRef(raw: ring), itemID: next.item.id, startOffset: next.startOffset,
                ringStart: next.framesWritten > 0 ? next.ringStart : boom_ring_frames_written(ring), held: 0)
            timeline.withLock { $0 = snapshot }
            emit(.advanced(next.item.id))
            // Say at once whether the new song is being heard or still awaited.
            if waitingAtTheJoin, output == .sounding { setOutput(.waiting) }
            switch output {
            case .sounding: emit(.playing(next.item.id))
            case .waiting: emit(.waiting(next.item.id))
            case .held, .ended: break
            }
        }
        guard let heard = decks.first else { return }

        let available = boom_ring_available_read(ring)
        let finished = boom_ring_is_finished(ring)
        switch output {
        case .held, .ended:
            break
        case .sounding:
            if available == 0 {
                if finished {
                    setOutput(.ended)
                    emit(.ended(heard.item.id))
                } else {
                    setOutput(.waiting)
                    emit(.waiting(heard.item.id))
                }
            }
        case .waiting:
            if finished, available == 0 {
                // Nothing left to play at all (a jump to the very end).
                setOutput(.ended)
                emit(.ended(heard.item.id))
                return
            }
            let enough = heard.framesWritten == 0 ? Self.startFrames : Self.restartFrames
            let everythingIsIn = finished || decks.allSatisfy(\.isFinished)
            if available >= enough || (everythingIsIn && available > 0) {
                setOutput(.sounding)
                emit(.playing(heard.item.id))
            }
        }
    }

    /// Decodes into the ring until it is full, the audio in hand runs out, or `budget` frames were written.
    private func decodeAhead(budget: Int) {
        guard let ring else { return }
        var produced = 0
        while produced < budget {
            guard let deck = decks.first(where: { !$0.isFinished }) else {
                if !boom_ring_is_finished(ring) { boom_ring_set_finished(ring, true) }
                return
            }
            guard place(deck), let decoder = deck.decoder else { return }

            let ratio = Self.sampleRate / decoder.outputFormat.sampleRate
            let framesPerPacket = Int((Double(decoder.framesPerPacket) * ratio).rounded(.up)) + 8
            let room = Int(boom_ring_available_write(ring))
            let count = min(12, (room - framesPerPacket) / framesPerPacket)
            guard count >= 1 else { return }

            let packets = deck.item.buffer.packets(from: deck.nextPacket, limit: count)
            if packets.isEmpty {
                guard deck.item.buffer.hasReachedEnd(at: deck.nextPacket) else { return }
                deck.stage = .finished
                continue
            }
            do {
                deck.nextPacket += packets.count
                guard let decoded = try decoder.decode(packets), let pcm = try convert(decoded, for: deck)
                else { continue }
                produced += write(pcm, from: deck, into: ring)
            } catch {
                deck.stage = .finished
                emit(.failed(deck.item.id, "This song's audio couldn't be decoded."))
                return
            }
        }
    }

    /// Gets an unplaced deck ready to decode from its start position. That needs the stream's header (to make the
    /// decoder) and its first packet (to know the packet length); until both are in, the answer is false.
    /// A deck that is already decoding is left as it is.
    private func place(_ deck: Deck) -> Bool {
        guard case .unplaced(let start) = deck.stage else { return !deck.isFinished }
        guard let info = deck.item.buffer.info else { return false }
        if deck.decoder == nil {
            do {
                let decoder = try PacketDecoder(track: info)
                if decoder.outputFormat.sampleRate != Self.sampleRate || decoder.outputFormat.channelCount != 2 {
                    guard let resampler = AVAudioConverter(from: decoder.outputFormat, to: outputFormat) else {
                        throw DecodeError.unsupported("no converter to 48 kHz")
                    }
                    resampler.sampleRateConverterQuality = AVAudioQuality.max.rawValue
                    resampler.sampleRateConverterAlgorithm = AVSampleRateConverterAlgorithm_Mastering
                    deck.resampler = resampler
                }
                deck.decoder = decoder
            } catch {
                deck.stage = .finished
                emit(.failed(deck.item.id, "This song's audio format isn't supported."))
                return false
            }
        }
        guard let decoder = deck.decoder, deck.item.buffer.packetDuration > 0 else { return false }

        // In the decoder's output, the moment `start` sits after the encoder's lead-in.
        let rate = decoder.outputFormat.sampleRate
        let leadIn = Double(info.leadIn) / Double(info.timescale) * rate
        let target = Int((start * rate + leadIn).rounded())
        // The packet length is the stream's own (learnt from its first packets), not the codec's usual one: Opus
        // packets are 20 ms as a rule, but a stream may use 40 or 60.
        let packetFrames = max(1, Int((Double(deck.item.buffer.packetDuration) / Double(info.timescale) * rate).rounded()))
        let wantedPacket = target / packetFrames
        let firstPacket = max(0, wantedPacket - decoder.prerollPackets)
        let ratio = Self.sampleRate / rate
        deck.nextPacket = firstPacket
        deck.discard = Int((Double(target - firstPacket * packetFrames) * ratio).rounded())
        deck.startOffset = Int64((start * Self.sampleRate).rounded())
        decoder.reset()
        deck.resampler?.reset()
        deck.stage = .decoding
        return true
    }

    /// Brings decoded audio to 48 kHz stereo when the stream is anything else.
    private func convert(_ decoded: AVAudioPCMBuffer, for deck: Deck) throws -> AVAudioPCMBuffer? {
        guard let resampler = deck.resampler else { return decoded }
        let ratio = Self.sampleRate / decoded.format.sampleRate
        let capacity = AVAudioFrameCount((Double(decoded.frameLength) * ratio).rounded(.up)) + 64
        if deck.resampled == nil || deck.resampled!.frameCapacity < capacity {
            deck.resampled = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity * 2)
        }
        guard let output = deck.resampled else { throw DecodeError.failed("no room to convert") }
        output.frameLength = 0
        var handedOver = false
        var failure: NSError?
        let status = resampler.convert(to: output, error: &failure) { _, inputStatus in
            if handedOver {
                inputStatus.pointee = .noDataNow
                return nil
            }
            handedOver = true
            inputStatus.pointee = .haveData
            return decoded
        }
        if status == .error { throw DecodeError.failed(failure?.localizedDescription ?? "conversion failed") }
        return output.frameLength > 0 ? output : nil
    }

    /// Writes decoded audio to the ring, less whatever the deck still has to drop. Returns the frames written.
    private func write(_ pcm: AVAudioPCMBuffer, from deck: Deck, into ring: OpaquePointer) -> Int {
        guard let channels = pcm.floatChannelData else { return 0 }
        var frames = Int(pcm.frameLength)
        var skip = 0
        if deck.discard > 0 {
            skip = min(deck.discard, frames)
            deck.discard -= skip
            frames -= skip
        }
        guard frames > 0 else { return 0 }
        let left = channels[0] + skip
        let right = (pcm.format.channelCount > 1 ? channels[1] : channels[0]) + skip
        if deck.item.gain != 1 {
            var gain = deck.item.gain
            vDSP_vsmul(left, 1, &gain, left, 1, vDSP_Length(frames))
            if right != left { vDSP_vsmul(right, 1, &gain, right, 1, vDSP_Length(frames)) }
        }
        if deck.framesWritten == 0 { deck.ringStart = boom_ring_frames_written(ring) }
        var planes: [UnsafePointer<Float>?] = [UnsafePointer(left), UnsafePointer(right)]
        let written = planes.withUnsafeMutableBufferPointer { pointer -> UInt32 in
            pointer.baseAddress.map { base in
                base.withMemoryRebound(to: UnsafePointer<Float>.self, capacity: 2) {
                    boom_ring_write(ring, $0, UInt32(frames))
                }
            } ?? 0
        }
        deck.framesWritten += UInt64(written)
        return Int(written)
    }
}
