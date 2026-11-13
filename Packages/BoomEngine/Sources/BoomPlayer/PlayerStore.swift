import BoomModels
import Foundation
import Observation

/// Where the current song is. Kept apart from `PlayerStore` so that only the views showing time redraw as it runs.
@MainActor
@Observable
public final class PlaybackClock {
    /// Seconds into the song.
    public internal(set) var position: TimeInterval = 0
    /// The song's length in seconds, 0 until known.
    public internal(set) var duration: TimeInterval = 0
    /// How much of the song has been fetched, 0...1 (the lighter part of the scrubber's track).
    public internal(set) var bufferedFraction: Double = 0

    public init() {}

    /// 0...1 for a progress bar.
    public var progress: Double { duration > 0 ? min(1, max(0, position / duration)) : 0 }
    public var remaining: TimeInterval { max(0, duration - position) }
}

/// The player as the screens see it: the queue, what is playing, and every control.
///
/// Read its properties from SwiftUI views (it is `@Observable`); call its methods from buttons. All of it lives on
/// the main actor. Time lives in `clock`.
@MainActor
@Observable
public final class PlayerStore: PlaybackEngineDelegate {
    // MARK: What the screens read

    /// Everything queued: songs already played, the current one, and what is up next.
    /// How the player came to the current song. It decides what happens when that song cannot be played.
    private enum Arrival {
        /// The listener picked this very song (a tap on it): a failure is shown, with a way to try again.
        case chosen
        /// The queue moved on to it (the song before ended, or the listener skipped): an unplayable song is passed
        /// over, as long as that does not happen `failuresInARow` times running (then the trouble is not the song).
        case passing(failuresInARow: Int)
    }

    /// Shuffle is either off, or on together with the order to put back when it is turned off.
    private enum Shuffle {
        case off
        case on(orderBefore: [UUID])
    }

    public private(set) var queue: [QueueEntry] = []
    public private(set) var currentEntryID: UUID?
    private var shuffle = Shuffle.off
    @ObservationIgnored private var arrival = Arrival.chosen
    @ObservationIgnored private var skipTask: Task<Void, Never>?
    /// After this many unplayable songs in a row the player stops passing over them.
    private static let mostSkipsInARow = 3
    public private(set) var status: PlaybackStatus = .idle
    /// What the queue was started from ("Playing from …").
    public private(set) var source: QueueSource?
    public private(set) var repeatMode: RepeatMode
    /// True while the songs up next are in shuffled order.
    public var isShuffled: Bool {
        if case .on = shuffle { return true }
        return false
    }

    /// The listener's rating of the current song.
    public var likeStatus: LikeStatus { current?.likeStatus ?? .indifferent }
    public private(set) var sleepTimer: SleepTimer?
    /// True while the queue for a new song or radio is being fetched.
    public private(set) var isLoadingQueue = false
    /// The audio format being heard: "Opus · 141 kbps · 48 kHz". Nil until known.
    public private(set) var audioFormat: String?
    /// Where the sound is going: "AirPods Pro · 48 kHz · not resampled". Nil until something plays.
    public private(set) var audioOutput: String?
    /// When on, a radio of similar songs carries on after the last queued song.
    public var isAutoplayEnabled: Bool {
        didSet {
            defaults.set(isAutoplayEnabled, forKey: Keys.autoplay)
            prepareNext()
        }
    }
    public let clock = PlaybackClock()

    public var currentIndex: Int? {
        guard let currentEntryID else { return nil }
        return queue.firstIndex { $0.id == currentEntryID }
    }

    public var current: Song? { currentIndex.map { queue[$0].song } }

    /// The entries after the current one.
    public var upNext: [QueueEntry] {
        guard let index = currentIndex, index + 1 < queue.count else { return [] }
        return Array(queue[(index + 1)...])
    }

    /// True when the play button should show "pause": playing, or about to.
    public var isPlayingOrAboutTo: Bool {
        switch status {
        case .playing, .loading, .buffering: true
        case .idle, .paused, .failed: false
        }
    }

    public var hasNext: Bool {
        guard let index = currentIndex else { return false }
        return index + 1 < queue.count || repeatMode == .all || continuation != nil
    }

    public var hasPrevious: Bool { (currentIndex ?? 0) > 0 }

    // MARK: Inside

    @ObservationIgnored private let api: any MusicAPI
    @ObservationIgnored private let engine: any PlaybackEngine
    @ObservationIgnored private let defaults: UserDefaults
    /// Loads more of a radio queue.
    /// Where the radio carries on from. Observed, because whether there is a next song depends on it.
    private var continuation: String?
    /// Goes up whenever the queue is replaced, so answers to older requests are dropped.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var isFetchingMore = false
    @ObservationIgnored private var extras: [String: (lyrics: String?, related: String?)] = [:]
    /// Called after anything a Lock Screen would show has changed (song, playing or paused, a jump in time).
    @ObservationIgnored public var onChange: (@MainActor (PlayerStore) -> Void)?

    private enum Keys {
        static let repeatMode = "player.repeatMode"
        static let autoplay = "player.autoplay"
    }

    public init(api: any MusicAPI, engine: any PlaybackEngine, defaults: UserDefaults = .standard) {
        self.api = api
        self.engine = engine
        self.defaults = defaults
        repeatMode = defaults.string(forKey: Keys.repeatMode).flatMap(RepeatMode.init(rawValue:)) ?? .off
        isAutoplayEnabled = defaults.object(forKey: Keys.autoplay) as? Bool ?? true
        engine.delegate = self
    }

    // MARK: Starting playback

    /// Plays one song now and fills the queue with a radio based on it (a tap on a song in search or on Home).
    public func play(_ song: Song) {
        let entry = QueueEntry(song: song)
        replaceQueue(
            [entry], current: entry.id,
            source: QueueSource(kind: .radio, label: "Radio", title: song.title))
        let request = generation
        isLoadingQueue = true
        Task {
            defer { if request == generation { isLoadingQueue = false } }
            guard let radio = try? await api.watchQueue(WatchEndpoint(videoId: song.videoId)),
                  request == generation else { return }
            rememberExtras(for: song.videoId, from: radio)
            // The radio starts with the song itself; everything after it goes up next.
            let start = radio.songs.firstIndex { $0.videoId == song.videoId }.map { $0 + 1 } ?? 0
            let rest = radio.songs[min(start, radio.songs.count)...].filter(\.isAvailable)
            queue.append(contentsOf: rest.map { QueueEntry(song: $0) })
            continuation = radio.continuation
            prepareNext()
        }
    }

    /// Plays a list (an album, a playlist, "Top songs") from the song at `index`.
    public func play(songs: [Song], startAt index: Int = 0, source: QueueSource? = nil) {
        let playable = songs.enumerated().filter { $0.element.isAvailable || $0.offset == index }
        guard !playable.isEmpty else { return }
        let entries = playable.map { QueueEntry(song: $0.element) }
        let position = playable.firstIndex { $0.offset == index } ?? 0
        replaceQueue(entries, current: entries[position].id, source: source)
    }

    /// Plays what a button's endpoint describes: "Shuffle", "Radio", "Play" on a playlist card.
    public func play(_ endpoint: WatchEndpoint, source: QueueSource? = nil) {
        generation += 1
        let request = generation
        isLoadingQueue = true
        if queue.isEmpty { status = .loading }
        Task {
            defer { if request == generation { isLoadingQueue = false } }
            do {
                let result = try await api.watchQueue(endpoint)
                guard request == generation else { return }
                let songs = result.songs.filter(\.isAvailable)
                guard !songs.isEmpty else {
                    if queue.isEmpty { status = .failed("Nothing to play here.") }
                    return
                }
                let entries = songs.map { QueueEntry(song: $0) }
                let wanted = result.songs.indices.contains(result.currentIndex)
                    ? result.songs[result.currentIndex].videoId : songs[0].videoId
                let start = entries.first { $0.song.videoId == wanted } ?? entries[0]
                if let videoId = endpoint.videoId { rememberExtras(for: videoId, from: result) }
                generation -= 1
                replaceQueue(entries, current: start.id, source: source)
                continuation = result.continuation
            } catch {
                guard request == generation else { return }
                if queue.isEmpty { status = .failed("Couldn't load this. Check your connection.") }
            }
        }
    }

    /// Starts a radio of songs like `song`.
    public func startRadio(from song: Song) {
        play(song)
    }

    // MARK: Transport

    public func togglePlayPause() {
        switch status {
        case .playing, .buffering, .loading: pause()
        case .paused: resume()
        case .failed: retry()
        case .idle: break
        }
    }

    public func resume() {
        guard current != nil else { return }
        // At the very end of the last song, "play" starts it again.
        if clock.duration > 0, clock.position >= clock.duration - 0.25 { seek(to: 0) }
        engine.play()
    }

    public func pause() {
        engine.pause()
    }

    /// Tries the current song again after a failure.
    public func retry() {
        guard let song = current else { return }
        skipTask?.cancel()
        arrival = .chosen
        start(song, at: 0)
    }

    public func seek(to seconds: TimeInterval) {
        guard current != nil else { return }
        let target = max(0, clock.duration > 0 ? min(seconds, clock.duration) : seconds)
        engine.seek(to: target)
        clock.position = target
        onChange?(self)
    }

    /// The next song. At the end of the queue: wraps when repeating all, or loads more of a radio.
    public func next() {
        guard let index = currentIndex else { return }
        if index + 1 < queue.count {
            move(to: queue[index + 1].id)
        } else if repeatMode == .all, let first = queue.first {
            move(to: first.id)
        } else {
            fetchMore(thenAdvance: true)
        }
    }

    /// Back to the start of the song, or to the song before when already near the start.
    public func previous() {
        guard let index = currentIndex else { return }
        if clock.position > 3 || index == 0 {
            seek(to: 0)
            if status == .paused { resume() }
        } else {
            move(to: queue[index - 1].id)
        }
    }

    /// Plays a queued entry now (a tap in "Up next").
    public func jump(to entryID: UUID) {
        guard queue.contains(where: { $0.id == entryID }) else { return }
        move(to: entryID, arrival: .chosen)
    }

    public func stop() {
        generation += 1
        skipTask?.cancel()
        engine.stop()
        ticker?.cancel()
        queue = []
        currentEntryID = nil
        continuation = nil
        source = nil
        status = .idle
        clock.position = 0
        clock.duration = 0
        clock.bufferedFraction = 0
        setSleepTimer(nil)
        onChange?(self)
    }

    // MARK: Queue

    /// Puts songs right after the current one.
    public func playNext(_ songs: [Song]) {
        let entries = songs.filter(\.isAvailable).map { QueueEntry(song: $0) }
        guard !entries.isEmpty else { return }
        guard let index = currentIndex else {
            play(songs: songs)
            return
        }
        queue.insert(contentsOf: entries, at: index + 1)
        prepareNext()
    }

    /// Puts songs at the end of the queue.
    public func addToQueue(_ songs: [Song]) {
        let entries = songs.filter(\.isAvailable).map { QueueEntry(song: $0) }
        guard !entries.isEmpty else { return }
        guard currentIndex != nil else {
            play(songs: songs)
            return
        }
        queue.append(contentsOf: entries)
        prepareNext()
    }

    /// Reorders the queue (drag in "Up next"). Offsets are positions in `queue`.
    public func moveQueue(fromOffsets source: IndexSet, toOffset destination: Int) {
        let moving = source.filter { queue.indices.contains($0) }.map { queue[$0] }
        guard !moving.isEmpty else { return }
        let ids = Set(moving.map(\.id))
        let before = queue[..<min(max(destination, 0), queue.count)].filter { !ids.contains($0.id) }.count
        queue.removeAll { ids.contains($0.id) }
        queue.insert(contentsOf: moving, at: min(before, queue.count))
        // The listener has put the queue in an order of their own: that is now the order to keep.
        if isShuffled { shuffle = .on(orderBefore: []) }
        prepareNext()
    }

    public func removeFromQueue(_ entryIDs: Set<UUID>) {
        guard !entryIDs.isEmpty else { return }
        if let currentEntryID, entryIDs.contains(currentEntryID), let index = currentIndex {
            // The current song goes: carry on with the next that stays, or stop.
            if let successor = queue[(index + 1)...].first(where: { !entryIDs.contains($0.id) }) {
                queue.removeAll { entryIDs.contains($0.id) }
                move(to: successor.id)
            } else {
                stop()
            }
            return
        }
        queue.removeAll { entryIDs.contains($0.id) }
        prepareNext()
    }

    /// Empties "Up next", keeping what has played and the current song.
    public func clearUpNext() {
        guard let index = currentIndex else { return }
        queue.removeSubrange((index + 1)...)
        continuation = nil
        prepareNext()
    }

    public func toggleShuffle() {
        defer { prepareNext() }
        let index = currentIndex ?? -1
        let upcoming = index + 1 < queue.count ? Array(queue[(index + 1)...]) : []
        switch shuffle {
        case .off:
            shuffle = .on(orderBefore: upcoming.map(\.id))
            if !upcoming.isEmpty { queue.replaceSubrange((index + 1)..., with: upcoming.shuffled()) }
        case .on(let orderBefore):
            shuffle = .off
            guard !upcoming.isEmpty, !orderBefore.isEmpty else { return }
            // Back to the order from before, for the entries that are still queued; newer ones keep their place
            // after them.
            let rank = Dictionary(orderBefore.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
            let restored = upcoming.enumerated().sorted { a, b in
                let left = rank[a.element.id] ?? Int.max, right = rank[b.element.id] ?? Int.max
                return left == right ? a.offset < b.offset : left < right
            }.map(\.element)
            queue.replaceSubrange((index + 1)..., with: restored)
        }
    }

    public func cycleRepeatMode() {
        switch repeatMode {
        case .off: setRepeatMode(.all)
        case .all: setRepeatMode(.one)
        case .one: setRepeatMode(.off)
        }
    }

    public func setRepeatMode(_ mode: RepeatMode) {
        repeatMode = mode
        defaults.set(mode.rawValue, forKey: Keys.repeatMode)
        prepareNext()
    }

    // MARK: The current song's extras

    /// Rates the current song. Shows at once; put back if YouTube Music refuses.
    public func setLike(_ newStatus: LikeStatus) {
        guard let index = currentIndex else { return }
        let entry = queue[index]
        let before = entry.song.likeStatus
        queue[index].song.likeStatus = newStatus
        onChange?(self)
        Task {
            do {
                try await api.rate(videoId: entry.song.videoId, newStatus)
            } catch {
                // Refused: put the rating back on that entry, wherever in the queue it is now.
                guard let index = queue.firstIndex(where: { $0.id == entry.id }),
                      queue[index].song.likeStatus == newStatus else { return }
                queue[index].song.likeStatus = before
                onChange?(self)
            }
        }
    }

    /// The current song's lyrics, or nil when it has none.
    public func lyrics() async throws -> Lyrics? {
        guard let song = current else { return nil }
        guard let browseId = try await extras(for: song).lyrics else { return nil }
        return try await api.lyrics(browseId: browseId)
    }

    /// The shelves of the player's "Related" tab for the current song.
    public func related() async throws -> [Shelf] {
        guard let song = current else { return [] }
        guard let browseId = try await extras(for: song).related else { return [] }
        return try await api.related(browseId: browseId)
    }

    /// Pauses after a while, or at the end of the song. Nil turns the timer off.
    public func setSleepTimer(_ timer: SleepTimer?) {
        sleepTask?.cancel()
        sleepTimer = timer
        guard case .at(let date) = timer else { return }
        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, date.timeIntervalSinceNow)))
            guard let self, !Task.isCancelled else { return }
            self.pause()
            self.sleepTimer = nil
        }
    }

    // MARK: PlaybackEngineDelegate

    public func engine(_ engine: any PlaybackEngine, didChange state: EngineState) {
        switch state {
        case .loading: status = .loading
        case .playing: status = .playing
        case .paused: status = .paused
        case .buffering: status = .buffering
        case .failed(let message):
            status = .failed(message)
            passOverIfUnplayable()
        }
        if status == .playing, case .passing = arrival { arrival = .passing(failuresInARow: 0) }
        syncClock()
        if status == .playing || status == .buffering || status == .loading { startTicker() } else { ticker?.cancel() }
        onChange?(self)
    }

    public func engineDidAdvanceToPreparedSong(_ engine: any PlaybackEngine) {
        guard let successor = successorForAutoAdvance() else { return }
        currentEntryID = successor.id
        didChangeSong()
        if sleepTimer == .endOfSong {
            pause()
            sleepTimer = nil
        }
    }

    public func engineDidFinishSong(_ engine: any PlaybackEngine) {
        if sleepTimer == .endOfSong {
            sleepTimer = nil
            status = .paused
            syncClock()
            onChange?(self)
            return
        }
        if repeatMode == .one, let song = current {
            start(song, at: 0)
        } else if let successor = successorForAutoAdvance() {
            move(to: successor.id)
        } else if isAutoplayEnabled, continuation != nil || source?.kind != .radio {
            fetchMore(thenAdvance: true)
        } else {
            status = .paused
            syncClock()
            onChange?(self)
        }
    }

    // MARK: Inside: moving through the queue

    private func replaceQueue(_ entries: [QueueEntry], current: UUID, source: QueueSource?) {
        generation += 1
        queue = entries
        continuation = nil
        shuffle = .off
        self.source = source
        move(to: current, arrival: .chosen)
    }

    /// Makes an entry the current song and starts it. `arrival` says whether the listener picked it or the queue
    /// moved on to it; passing over keeps count of failures in a row.
    private func move(to entryID: UUID, arrival next: Arrival = .passing(failuresInARow: 0)) {
        guard let entry = queue.first(where: { $0.id == entryID }) else { return }
        skipTask?.cancel()
        switch (arrival, next) {
        case (.passing(let failures), .passing) where status.isFailure:
            arrival = .passing(failuresInARow: failures)
        default:
            arrival = next
        }
        currentEntryID = entryID
        start(entry.song, at: 0)
        didChangeSong()
    }

    /// The current song cannot be played. If the queue was passing through it, move on after a moment.
    private func passOverIfUnplayable() {
        guard case .passing(let failures) = arrival, failures + 1 < Self.mostSkipsInARow,
              let index = currentIndex, index + 1 < queue.count else { return }
        arrival = .passing(failuresInARow: failures + 1)
        let failed = currentEntryID
        skipTask?.cancel()
        skipTask = Task { [weak self] in
            // Long enough to read what went wrong.
            try? await Task.sleep(for: .milliseconds(1200))
            guard let self, !Task.isCancelled, self.currentEntryID == failed, self.status.isFailure,
                  let index = self.currentIndex, index + 1 < self.queue.count else { return }
            self.move(to: self.queue[index + 1].id)
        }
    }

    private func start(_ song: Song, at seconds: TimeInterval) {
        clock.position = seconds
        clock.duration = song.duration ?? 0
        clock.bufferedFraction = 0
        status = .loading
        engine.load(song, autoplay: true, startAt: seconds)
    }

    private func didChangeSong() {
        syncClock()
        prepareNext()
        if let index = currentIndex, index >= queue.count - 3 { fetchMore(thenAdvance: false) }
        onChange?(self)
    }

    /// The entry that follows by itself when the current song ends.
    private func successorForAutoAdvance() -> QueueEntry? {
        guard let index = currentIndex else { return nil }
        switch repeatMode {
        case .one: return nil
        case .all: return index + 1 < queue.count ? queue[index + 1] : queue.first
        case .off: return index + 1 < queue.count ? queue[index + 1] : nil
        }
    }

    private func prepareNext() {
        let successor = successorForAutoAdvance()
        engine.prepareNext(successor?.song)
        // And the few after that, for fetching ahead.
        guard let successor, let index = queue.firstIndex(where: { $0.id == successor.id }) else {
            engine.fetchAhead([])
            return
        }
        engine.fetchAhead(queue[(index + 1)...].prefix(3).map(\.song))
    }

    /// Loads more of the radio. At the end of a plain list, starts a radio from its last song when autoplay is on.
    private func fetchMore(thenAdvance: Bool) {
        guard !isFetchingMore, let last = queue.last else { return }
        let token = continuation
        guard token != nil || (isAutoplayEnabled && thenAdvance) else { return }
        isFetchingMore = true
        let request = generation
        let known = Set(queue.map(\.song.videoId))
        Task {
            defer { isFetchingMore = false }
            var songs: [Song] = []
            var nextToken: String?
            if let token {
                guard let more = try? await api.watchQueueContinuation(token) else {
                    // The request failed (a lost connection): the radio's place is kept, so that it carries on
                    // the next time more is asked for, instead of ending here.
                    return
                }
                songs = more.songs
                nextToken = more.continuation
            } else if let radio = try? await api.watchQueue(WatchEndpoint(videoId: last.song.videoId)) {
                songs = radio.songs
                nextToken = radio.continuation
            }
            guard request == generation else { return }
            continuation = nextToken
            let fresh = songs.filter { $0.isAvailable && !known.contains($0.videoId) }
            queue.append(contentsOf: fresh.map { QueueEntry(song: $0) })
            prepareNext()
            if thenAdvance {
                if let index = currentIndex, index + 1 < queue.count {
                    move(to: queue[index + 1].id)
                } else {
                    status = .paused
                    onChange?(self)
                }
            }
        }
    }

    private func rememberExtras(for videoId: String, from queue: WatchQueue) {
        extras[videoId] = (queue.lyricsBrowseId, queue.relatedBrowseId)
    }

    private func extras(for song: Song) async throws -> (lyrics: String?, related: String?) {
        if let known = extras[song.videoId] { return known }
        let result = try await api.watchQueue(WatchEndpoint(videoId: song.videoId))
        rememberExtras(for: song.videoId, from: result)
        return (result.lyricsBrowseId, result.relatedBrowseId)
    }

    // MARK: Inside: time

    private func syncClock() {
        clock.position = engine.position
        let duration = engine.duration
        if duration > 0 { clock.duration = duration }
        clock.bufferedFraction = engine.bufferedFraction
        let format = engine.formatDescription
        if format != audioFormat { audioFormat = format }
        let output = format == nil ? nil : engine.outputDescription
        if output != audioOutput { audioOutput = output }
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, !Task.isCancelled else { return }
                self.syncClock()
            }
        }
    }
}
