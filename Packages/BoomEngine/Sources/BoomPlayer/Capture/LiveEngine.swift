import Accelerate
import BoomAudio
import BoomModels
import Foundation
import Network
import Synchronization

/// The real player: fetches each song's audio through the hidden tab and plays it through the audio engine.
@MainActor
public final class LiveEngine: PlaybackEngine, SoundProcessing, DownloadBackend {
    public weak var delegate: (any PlaybackEngineDelegate)?

    private struct Loaded {
        var song: Song
        var track: CapturedTrack
        var item: PipelineItem
    }

    /// Whether there is audio at the listener's place in the current song. Together with `wantsPlay` (what the
    /// listener asked for) this decides the state the screens see; `report()` is the one place that works it out.
    private enum Audio: Equatable {
        /// No song is loaded.
        case none
        /// Not in hand yet. `heardBefore` tells a song that has not started (loading) from one that has (buffering).
        case awaited(heardBefore: Bool)
        /// In hand: playing, or ready to.
        case ready
        /// The song cannot be played. The text is for the listener.
        case failed(String)
    }

    private let broker: StreamBroker
    private let pipeline = PlaybackPipeline()
    private let output: AudioOutput
    private let system: SystemAudio
    private var current: Loaded?
    private var next: Loaded?
    private var audio = Audio.none
    private var wantsPlay = false
    private var reported: EngineState?
    /// The system took the audio away (a call) while the listener wanted it playing: resume when it is given back.
    private var resumesAfterInterruption = false
    private var settings = SoundSettings()
    private lazy var headTracker = HeadTracker { [output] turn in output.setHeadTurn(turn) }
    private var playheadTask: Task<Void, Never>?
    private let network = NWPathMonitor()
    /// On mobile data (or Low Data Mode) only the next song is fetched ahead, not the ones after it, and downloads
    /// wait unless the listener allowed them there.
    private var isMetered = false {
        didSet { if isMetered != oldValue { updateDownloadHold() } }
    }
    public var onDownloadEvent: (@MainActor (DownloadEvent) -> Void)?
    public var onDownloadHoldChange: (@MainActor () -> Void)?
    public var allowsDownloadsOnMobileData = false {
        didSet { if allowsDownloadsOnMobileData != oldValue { updateDownloadHold() } }
    }
    public private(set) var isHoldingDownloadsForWiFi = false

    /// What came out of the engine while rendering without hardware (tests only).
    final class Heard: Sendable {
        let state = Mutex((frames: 0, peak: Float(0)))
        let isStopped = Atomic<Bool>(false)
    }
    let heard = Heard()

    /// - Parameter rendersOffline: For tests: nothing is sent to the speakers; a thread pulls the audio through
    ///   the whole engine at listening speed instead, as the hardware would.
    init(broker: StreamBroker, system: SystemAudio, rendersOffline: Bool = false) {
        self.broker = broker
        self.system = system
        output = AudioOutput(pipeline: pipeline)
        if rendersOffline {
            try? output.enableOfflineRendering(maximumFrames: 480)
            let output = output, heard = heard
            Thread {
                while !heard.isStopped.load(ordering: .relaxed) {
                    if output.isRunning, let buffer = try? output.renderOffline(frames: 480),
                       let channels = buffer.floatChannelData, buffer.frameLength > 0 {
                        var peak: Float = 0
                        vDSP_maxmgv(channels[0], 1, &peak, vDSP_Length(buffer.frameLength))
                        if peak > 0 {
                            heard.state.withLock {
                                $0.frames += Int(buffer.frameLength)
                                $0.peak = max($0.peak, peak)
                            }
                        }
                    }
                    usleep(10_000)
                }
            }.start()
        }
        pipeline.setEventHandler { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        system.onInterruption = { [weak self] began, shouldResume in
            guard let self else { return }
            if began {
                self.resumesAfterInterruption = self.wantsPlay
                self.pause()
            } else if shouldResume, self.resumesAfterInterruption {
                self.resumesAfterInterruption = false
                self.play()
            }
        }
        system.onOutputLost = { [weak self] in self?.pause() }
        network.pathUpdateHandler = { [weak self] path in
            let metered = path.isExpensive || path.isConstrained
            Task { @MainActor in self?.isMetered = metered }
        }
        network.start(queue: .global(qos: .utility))
    }

    deinit {
        network.cancel()
        heard.isStopped.store(true, ordering: .relaxed)
    }

    // MARK: PlaybackEngine

    public func load(_ song: Song, autoplay: Bool, startAt: TimeInterval) {
        let track = broker.track(for: song.videoId, purpose: .playing)
        let item = PipelineItem(buffer: track.buffer, gain: songGain(for: track))
        BoomTrace.note("engine load \(song.videoId) at \(startAt) autoplay=\(autoplay) cached=\(track.isFromCache) held=\(track.buffer.heldPacketCount)")
        current = Loaded(song: song, track: track, item: item)
        next = nil
        wantsPlay = autoplay
        reported = nil
        watch(track)
        broker.setPlayhead(startAt, for: song.videoId)
        if let failure = track.failure {
            audio = .failed(failure)
            report()
            return
        }
        audio = .awaited(heardBefore: false)
        report()
        startOutput()
        pipeline.load(item, at: startAt, autoplay: autoplay)
        startPlayheadReports()
    }

    public func prepareNext(_ song: Song?) {
        guard let song else {
            next = nil
            pipeline.setNext(nil)
            broker.clearUpcoming()
            return
        }
        if next?.song.videoId == song.videoId { return }
        let track = broker.track(for: song.videoId, purpose: .upcoming)
        // A song that cannot be fetched is not joined on; the queue deals with it when its turn comes.
        guard track.failure == nil else {
            next = nil
            pipeline.setNext(nil)
            return
        }
        let item = PipelineItem(buffer: track.buffer, gain: songGain(for: track))
        next = Loaded(song: song, track: track, item: item)
        watch(track)
        pipeline.setNext(item)
    }

    public func fetchAhead(_ songs: [Song]) {
        let wanted = isMetered ? [] : songs.map(\.videoId)
        broker.keepLater(only: wanted)
        for videoId in wanted where videoId != current?.song.videoId && videoId != next?.song.videoId {
            _ = broker.track(for: videoId, purpose: .later)
        }
    }

    public func play() {
        guard current != nil, !isFailed else { return }
        wantsPlay = true
        startOutput()
        pipeline.play()
        report()
    }

    public func pause() {
        guard current != nil else { return }
        wantsPlay = false
        pipeline.pause()
        report()
    }

    public func seek(to seconds: TimeInterval) {
        guard let current, !isFailed else { return }
        broker.setPlayhead(seconds, for: current.song.videoId)
        pipeline.seek(to: seconds)
    }

    public func stop() {
        wantsPlay = false
        playheadTask?.cancel()
        pipeline.stop()
        current = nil
        next = nil
        audio = .none
        reported = nil
    }

    public var position: TimeInterval {
        guard current != nil else { return 0 }
        let heard = pipeline.position
        return duration > 0 ? min(heard, duration) : heard
    }

    public var duration: TimeInterval {
        guard let current else { return 0 }
        return current.track.buffer.duration ?? current.song.duration ?? 0
    }

    public var bufferedFraction: Double {
        current?.track.buffer.coveredFraction ?? 0
    }

    /// The format of the song being heard.
    public var currentFormat: AudioOffer? { current?.track.format }

    /// For diagnostic runs: plays through the whole chain with the output turned down to nothing.
    public func setOutputVolume(_ volume: Float) {
        output.setOutputVolume(volume)
    }

    /// The device the sound goes to and the rate it runs at, and whether the stream had to be converted to it.
    public var outputDescription: String? {
        guard let route = system.outputDescription else { return nil }
        let rate = system.hardwareSampleRate
        guard rate > 0 else { return route }
        return route + (rate == PlaybackPipeline.sampleRate ? " · not resampled" : " · resampled from 48 kHz")
    }

    public var formatDescription: String? {
        guard let current, let info = current.track.buffer.info else { return nil }
        let codec = info.codec == .opus ? "Opus" : "AAC"
        // What was really captured, measured from the bytes in hand, not what was advertised.
        let buffer = current.track.buffer
        var parts = [codec]
        if buffer.packetDuration > 0, buffer.heldPacketCount > 50 {
            let seconds = Double(buffer.heldPacketCount * buffer.packetDuration) / Double(info.timescale)
            parts.append("\(Int((Double(buffer.heldBytes) * 8 / seconds / 1000).rounded())) kbps")
        } else if let offer = current.track.format, offer.bitrate > 0 {
            parts.append("\(offer.bitrate / 1000) kbps")
        }
        parts.append(String(format: "%g kHz", info.sampleRate / 1000))
        return parts.joined(separator: " · ")
    }

    // MARK: State

    private var isFailed: Bool {
        if case .failed = audio { return true }
        return false
    }

    /// Works out what the screens should see from `audio` and `wantsPlay`, and says so when it has changed.
    private func report() {
        let state: EngineState
        switch audio {
        case .none: return
        case .failed(let message): state = .failed(message)
        case .ready: state = wantsPlay ? .playing : .paused
        case .awaited(let heardBefore): state = !wantsPlay ? .paused : heardBefore ? .buffering : .loading
        }
        guard state != reported else { return }
        reported = state
        delegate?.engine(self, didChange: state)
    }

    private func fail(_ message: String) {
        audio = .failed(message)
        wantsPlay = false
        pipeline.pause()
        report()
    }

    private func handle(_ event: PipelineEvent) {
        BoomTrace.note("pipeline \(Self.describe(event)) at \(String(format: "%.1f", self.pipeline.position)) s")
        switch event {
        case .playing(let id):
            guard id == current?.item.id, !isFailed else { return }
            audio = .ready
            report()
        case .waiting(let id):
            guard id == current?.item.id, !isFailed else { return }
            if let failure = current?.track.failure {
                fail(failure)
                return
            }
            if case .awaited = audio { return }
            audio = .awaited(heardBefore: audio == .ready)
            report()
        case .advanced(let id):
            guard let next, id == next.item.id else { return }
            current = next
            self.next = nil
            // Whether the new song is being heard or awaited follows at once as its own event.
            audio = .awaited(heardBefore: false)
            reported = nil
            _ = broker.track(for: next.song.videoId, purpose: .playing)
            broker.setPlayhead(0, for: next.song.videoId)
            delegate?.engineDidAdvanceToPreparedSong(self)
        case .ended(let id):
            guard id == current?.item.id else { return }
            // The song is over and nothing is sounding: say "paused" first, then that it finished, so whoever
            // listens can start it again or move on from a known state.
            wantsPlay = false
            if !isFailed { audio = .ready }
            report()
            delegate?.engineDidFinishSong(self)
        case .failed(let id, let message):
            guard id == current?.item.id else { return }
            fail(message)
        }
    }

    private static func describe(_ event: PipelineEvent) -> String {
        switch event {
        case .playing: "playing"
        case .advanced: "advanced to the next song"
        case .waiting: "waiting for audio"
        case .ended: "ended"
        case .failed(_, let message): "failed: \(message)"
        }
    }

    private func watch(_ track: CapturedTrack) {
        track.onUpdate = { [weak self, weak track] in
            guard let self, let track else { return }
            if let failure = track.failure, track === self.current?.track, case .awaited = self.audio {
                // Audio already in hand keeps playing; the failure shows once the listener is waiting on more.
                self.fail(failure)
            }
            if track === self.next?.track, track.failure != nil {
                self.next = nil
                self.pipeline.setNext(nil)
            }
            // The song's loudness arrives with the player's answer, a moment after the request.
            self.applySongGains()
        }
    }

    private func startOutput() {
        system.activate()
        guard !output.isRunning else { return }
        do {
            try output.start()
        } catch {
            BoomTrace.note("audio engine failed to start: \(String(describing: error))")
        }
    }

    private func startPlayheadReports() {
        playheadTask?.cancel()
        playheadTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, !Task.isCancelled, let current = self.current else { return }
                self.broker.setPlayhead(self.pipeline.position, for: current.song.videoId)
            }
        }
    }

    // MARK: SoundProcessing

    public func apply(_ settings: SoundSettings) {
        let normalizationChanged = settings.isLoudnessNormalizationEnabled != self.settings.isLoudnessNormalizationEnabled
        self.settings = settings
        output.apply(settings.profile)
        if settings.wantsHeadTracking { headTracker.start() } else { headTracker.stop() }
        if normalizationChanged { applySongGains() }
    }

    public var isHeadTrackingAvailable: Bool { headTracker.isAvailable }

    /// Turns a louder-than-reference song down by the amount YouTube Music reports. Quieter songs are left alone,
    /// as YouTube Music itself does: turning them up would need the limiter.
    private func songGain(for track: CapturedTrack) -> Float {
        guard settings.isLoudnessNormalizationEnabled, let loudness = track.loudnessDb, loudness > 0 else { return 1 }
        return Float(pow(10, -loudness / 20))
    }

    private func applySongGains() {
        if var loaded = current {
            let gain = songGain(for: loaded.track)
            if gain != loaded.item.gain {
                loaded.item.gain = gain
                current = loaded
                pipeline.setItemGain(gain, for: loaded.item.id)
            }
        }
        if var loaded = next {
            let gain = songGain(for: loaded.track)
            if gain != loaded.item.gain {
                loaded.item.gain = gain
                next = loaded
                pipeline.setItemGain(gain, for: loaded.item.id)
            }
        }
    }

    // MARK: DownloadBackend

    public func startDownloads(_ videoIds: [String]) {
        broker.onDownload = { [weak self] event in self?.onDownloadEvent?(event) }
        broker.startDownloads(videoIds)
    }

    public func removeDownloads(_ videoIds: [String]) {
        broker.removeDownloads(videoIds)
    }

    private func updateDownloadHold() {
        let holding = isMetered && !allowsDownloadsOnMobileData
        broker.allowsDownloads = !holding
        guard holding != isHoldingDownloadsForWiFi else { return }
        isHoldingDownloadsForWiFi = holding
        BoomTrace.note("downloads \(holding ? "wait for Wi-Fi" : "may run")")
        onDownloadHoldChange?()
    }

    public func takeLevels() -> SoundLevels {
        output.takeLevels()
    }

    public func spectrum(bands: Int) -> [Float] {
        output.spectrum(bands: bands)
    }
}
