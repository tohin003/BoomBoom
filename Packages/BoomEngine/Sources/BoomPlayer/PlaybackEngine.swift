import BoomModels
import Foundation

/// What `PlayerStore` needs from the thing that makes sound. The real one captures the hidden tab's audio and plays
/// it through the equaliser; `SimulatedEngine` only keeps time, for previews and tests.
@MainActor
public protocol PlaybackEngine: AnyObject {
    var delegate: (any PlaybackEngineDelegate)? { get set }

    /// Starts on a new song, dropping whatever was playing. `startAt` is seconds into the song.
    func load(_ song: Song, autoplay: Bool, startAt: TimeInterval)
    /// Says which song comes next, so it can be fetched ahead and joined on without a gap. Nil: nothing follows.
    func prepareNext(_ song: Song?)
    func play()
    func pause()
    func seek(to seconds: TimeInterval)
    /// Stops and lets go of everything.
    func stop()

    /// Seconds into the current song, as heard now.
    var position: TimeInterval { get }
    /// The current song's length in seconds, 0 until known.
    var duration: TimeInterval { get }
    /// How much of the current song is in hand, 0...1.
    var bufferedFraction: Double { get }
    /// The audio format being heard, for a details line: "Opus · 141 kbps · 48 kHz". Nil when not known.
    var formatDescription: String? { get }
    /// Where the sound is going, for the listener: "AirPods Pro · 48 kHz · not resampled". Nil when unknown.
    var outputDescription: String? { get }
    /// The songs that follow the next one, nearest first, so their audio can be fetched ahead. With the screen
    /// locked the hidden tab may be slowed down or stopped; what is already in hand keeps playing regardless.
    func fetchAhead(_ songs: [Song])
}

extension PlaybackEngine {
    public var formatDescription: String? { nil }
    public var outputDescription: String? { nil }
    public func fetchAhead(_ songs: [Song]) {}
}

public enum EngineState: Sendable, Equatable {
    case loading
    case playing
    case paused
    case buffering
    case failed(String)
}

@MainActor
public protocol PlaybackEngineDelegate: AnyObject {
    func engine(_ engine: any PlaybackEngine, didChange state: EngineState)
    /// The song given to `prepareNext` has started (joined on without a gap).
    func engineDidAdvanceToPreparedSong(_ engine: any PlaybackEngine)
    /// The current song ended and nothing was prepared to follow it.
    func engineDidFinishSong(_ engine: any PlaybackEngine)
}

/// Keeps time without making sound: plays every song as if its audio were there. For previews and tests.
@MainActor
public final class SimulatedEngine: PlaybackEngine {
    public weak var delegate: (any PlaybackEngineDelegate)?

    private var song: Song?
    private var prepared: Song?
    private var isPlaying = false
    private var anchorPosition: TimeInterval = 0
    private var anchorDate = Date()
    private var ticker: Task<Void, Never>?
    /// How long a song takes to "load" before it plays.
    public var loadDelay: Duration = .milliseconds(250)
    /// Songs (by video id) that fail to load, to try out how the player deals with unplayable ones.
    public var unplayable: Set<String> = []
    private var loadTask: Task<Void, Never>?

    public init() {}

    public var duration: TimeInterval { song?.duration ?? 0 }
    public var bufferedFraction: Double { song == nil ? 0 : 1 }

    public var position: TimeInterval {
        guard isPlaying else { return anchorPosition }
        let now = anchorPosition + Date().timeIntervalSince(anchorDate)
        return duration > 0 ? min(now, duration) : now
    }

    public func load(_ song: Song, autoplay: Bool, startAt: TimeInterval) {
        loadTask?.cancel()
        self.song = song
        prepared = nil
        isPlaying = false
        anchorPosition = max(0, startAt)
        delegate?.engine(self, didChange: .loading)
        loadTask = Task { [weak self, loadDelay] in
            try? await Task.sleep(for: loadDelay)
            guard let self, !Task.isCancelled else { return }
            if self.unplayable.contains(song.videoId) {
                self.delegate?.engine(self, didChange: .failed("This song can't be played."))
            } else if autoplay {
                self.play()
            } else {
                self.delegate?.engine(self, didChange: .paused)
            }
        }
    }

    public func prepareNext(_ song: Song?) {
        prepared = song
    }

    public func play() {
        guard song != nil, !isPlaying else { return }
        anchorDate = Date()
        isPlaying = true
        delegate?.engine(self, didChange: .playing)
        startTicker()
    }

    public func pause() {
        guard isPlaying else { return }
        anchorPosition = position
        isPlaying = false
        ticker?.cancel()
        delegate?.engine(self, didChange: .paused)
    }

    public func seek(to seconds: TimeInterval) {
        anchorPosition = max(0, duration > 0 ? min(seconds, duration) : seconds)
        anchorDate = Date()
    }

    public func stop() {
        loadTask?.cancel()
        ticker?.cancel()
        song = nil
        prepared = nil
        isPlaying = false
        anchorPosition = 0
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, !Task.isCancelled, self.isPlaying else { return }
                guard self.duration > 0, self.position >= self.duration else { continue }
                if let next = self.prepared {
                    self.song = next
                    self.prepared = nil
                    self.anchorPosition = 0
                    self.anchorDate = Date()
                    self.delegate?.engineDidAdvanceToPreparedSong(self)
                } else {
                    self.anchorPosition = self.duration
                    self.isPlaying = false
                    self.delegate?.engineDidFinishSong(self)
                    return
                }
            }
        }
    }
}
