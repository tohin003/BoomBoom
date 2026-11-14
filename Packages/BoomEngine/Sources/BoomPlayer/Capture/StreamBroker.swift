import BoomAudio
import Foundation

/// One song's audio on its way in: the buffer the player reads from, and what is known about the stream.
@MainActor
public final class CapturedTrack {
    public let videoId: String
    public let buffer: TrackBuffer
    /// How much louder than YouTube Music's reference level the song is, in decibels (negative: quieter).
    public internal(set) var loudnessDb: Double?
    /// The audio format being captured ("Opus", 141 kbps).
    public internal(set) var format: AudioOffer?
    /// Set when the song cannot be fetched. The text is for the listener.
    public internal(set) var failure: String?
    public let isFromCache: Bool
    /// Called when `loudnessDb`, `format` or `failure` changes.
    var onUpdate: (@MainActor () -> Void)?

    init(videoId: String, buffer: TrackBuffer = TrackBuffer(), isFromCache: Bool = false) {
        self.videoId = videoId
        self.buffer = buffer
        self.isFromCache = isFromCache
    }
}

/// Gets songs' audio out of the hidden tab.
///
/// For each song it tells the page's player to load it, takes the audio the player appends to the browser's media
/// pipeline (passed on by `boom-tap.js`), demuxes it into the song's `TrackBuffer`, and keeps moving the player's
/// paused playhead forward so the whole song arrives in seconds rather than at listening speed. The page fetches one
/// song at a time; which one is decided afresh whenever something changes (see `mostUrgent()`). Finished songs go to
/// the on-disk cache.
@MainActor
final class StreamBroker {
    /// How soon a song is needed: being played, next in line, or further up the queue.
    enum Purpose { case playing, upcoming, later }

    /// Where one song's fetch stands. A job is in exactly one of these; `Job.move(to:)` is the only way to change.
    private enum Phase {
        /// Not asked of the page yet: the tab is loading, or its player has not appeared.
        case waitingForTab(since: Date)
        /// The page's player was told to load the song; no audio buffer has opened yet.
        case requested(at: Date)
        /// The tab had no player, so the song's own page is being opened in it. The song is claimed in the new page
        /// as soon as the script is there.
        case openingPage(at: Date)
        /// Audio is arriving.
        case receiving(Receiving)
    }

    /// What has been tried for a fetch whose audio stopped arriving. Each step is tried once, in this order.
    private enum Recovery {
        case none
        /// The page's player was told to load the song again.
        case askedAgain
        /// The song's own page was opened afresh in the tab.
        case reopenedPage
    }

    private struct Receiving {
        var bufferID: Int
        var demuxer: any AudioDemuxer
        /// Packets in hand at the last look, and when that number last went up.
        var held = 0
        var grewAt = Date()
        /// The last move of the page's playhead: not repeated for the same place within half a second.
        var lastNudge: (at: Date, target: TimeInterval)?
        /// How many pieces the page has passed on for this buffer, and their bytes: for the trace.
        var pieces = 0
        var bytes = 0
    }

    private final class Job {
        let token = UUID().uuidString
        let track: CapturedTrack
        let startedAt = Date()
        private(set) var phase: Phase
        /// The song's length as the player's answer states it.
        var expectedSeconds: Double?
        /// Buffers whose stream turned out not to be the song (wrong length): their bytes are ignored.
        var rejected: Set<Int> = []
        /// Where the listener is in the song.
        var playhead: TimeInterval
        /// The listener jumped: move the page's playhead at the next look, without waiting for a lull.
        var needsNudge: Bool
        /// The song's own page has been opened once for this job; it is not tried twice.
        var hasOpenedPage = false
        /// The tab had failed to load when this job needed it, and was loaded again for it once.
        var hasReloadedTab = false
        /// How far the job has gone in getting a stalled fetch moving again.
        var recovery = Recovery.none

        init(track: CapturedTrack, playhead: TimeInterval) {
            self.track = track
            self.playhead = playhead
            needsNudge = playhead > 5
            phase = .waitingForTab(since: Date())
        }

        func move(to next: Phase) {
            phase = next
        }

        /// Changes the receiving state in place. Does nothing in any other phase.
        func receiving(_ change: (inout Receiving) -> Void) {
            guard case .receiving(var state) = phase else { return }
            change(&state)
            phase = .receiving(state)
        }
    }

    private let browser: HiddenBrowser
    private let cache: AudioCache
    private let downloads: DownloadLibrary
    private let defaults: UserDefaults
    /// The songs the listener asked to keep that are not kept yet, in the order to fetch them. They are fetched
    /// only when nothing the listener is hearing or about to hear needs the page.
    private var downloadIds: [String] = []
    /// A short rest after each finished download, so a long playlist does not hammer the service.
    private var downloadsRestUntil = Date.distantPast
    private var lastDownloadProgress = (videoId: "", fraction: -1.0)
    /// False while downloads must wait (mobile data, and the listener has not allowed it).
    var allowsDownloads = true {
        didSet { if allowsDownloads != oldValue { schedule() } }
    }
    /// What happens to the songs being kept.
    var onDownload: (@MainActor (DownloadEvent) -> Void)?
    /// Songs in memory, most recently asked for last.
    private var tracks: [String: CapturedTrack] = [:]
    private var recency: [String] = []
    // What the engine has said it needs.
    private var playingId: String?
    private var upcomingId: String?
    private var laterIds: [String] = []
    /// Where the listener is in the playing song.
    private var playhead: (videoId: String, seconds: TimeInterval)?
    private var active: Job?
    private var timer: Task<Void, Never>?
    private var didWarmUp = false
    private static let songsKeptInMemory = 9
    private static let lastSongKey = "capture.lastVideoId"
    private static let bestBitrateKey = "capture.bestBitrate"

    /// The highest bitrate this account has been given so far, in bits per second (0: not known yet). A Premium
    /// account gets about twice what a free one does.
    private var bestBitrate: Int {
        get { defaults.integer(forKey: Self.bestBitrateKey) }
        set { defaults.set(newValue, forKey: Self.bestBitrateKey) }
    }
    /// The sign-in the last stream was fetched under: whether the tab was signed in, and as which profile.
    private var lastSignIn: (isSignedIn: Bool, profile: String)?

    /// True when a kept copy of a song is clearly poorer than what the account gets now (it was kept under a free
    /// account and this one has Premium): the song is then fetched afresh, and the new copy replaces the old.
    /// Only while the tab is up, though: without a connection the kept copy is the one that plays.
    private func isOutclassed(_ format: AudioOffer?) -> Bool {
        guard let format, format.bitrate > 0, bestBitrate > 0, browser.pageLoadedAt != nil else { return false }
        return Double(format.bitrate) < 0.75 * Double(bestBitrate)
    }

    init(
        browser: HiddenBrowser, cache: AudioCache, downloads: DownloadLibrary = DownloadLibrary(),
        defaults: UserDefaults = .standard
    ) {
        self.browser = browser
        self.cache = cache
        self.downloads = downloads
        self.defaults = defaults
        browser.onEvent = { [weak self] event in self?.handle(event) }
        browser.onPhaseChange = { [weak self] in self?.tabDidChange() }
    }

    // MARK: What the engine asks for

    /// The song's audio: from memory, from the cache, or freshly queued for fetching.
    func track(for videoId: String, purpose: Purpose) -> CapturedTrack {
        let track: CapturedTrack
        if let known = tracks[videoId], known.failure == nil {
            track = known
        } else if let kept = downloads.load(videoId) {
            // The listener's own copy: always used, with or without a connection.
            track = CapturedTrack(videoId: videoId, buffer: kept.buffer, isFromCache: true)
            track.loudnessDb = kept.details?.loudnessDb
            track.format = kept.details?.format
        } else if let kept = cache.load(videoId), !isOutclassed(kept.details?.format) {
            track = CapturedTrack(videoId: videoId, buffer: kept.buffer, isFromCache: true)
            track.loudnessDb = kept.details?.loudnessDb
            track.format = kept.details?.format
        } else {
            track = CapturedTrack(videoId: videoId)
        }
        tracks[videoId] = track
        recency.removeAll { $0 == videoId }
        recency.append(videoId)

        switch purpose {
        case .playing:
            playingId = videoId
            if upcomingId == videoId { upcomingId = nil }
            laterIds.removeAll { $0 == videoId }
            if playhead?.videoId != videoId { playhead = (videoId, 0) }
        case .upcoming:
            upcomingId = videoId
            laterIds.removeAll { $0 == videoId }
        case .later:
            if videoId != playingId, videoId != upcomingId, !laterIds.contains(videoId) { laterIds.append(videoId) }
        }
        forgetOldSongs()
        schedule()
        return track
    }

    /// Nothing follows the playing song any more.
    func clearUpcoming() {
        upcomingId = nil
        schedule()
    }

    /// The songs further up the queue, nearest first; any others queued for fetching ahead are dropped.
    func keepLater(only videoIds: [String]) {
        laterIds = videoIds.filter { laterIds.contains($0) }
        schedule()
    }

    /// Where the listener is in the playing song, so that fetching serves that place first.
    func setPlayhead(_ seconds: TimeInterval, for videoId: String) {
        let jumped = playhead.map { $0.videoId != videoId || abs($0.seconds - seconds) > 5 } ?? true
        playhead = (videoId, seconds)
        if let job = active, job.track.videoId == videoId {
            job.playhead = seconds
            if jumped { job.needsNudge = true }
        }
        // A jump can change which song matters most (into, or out of, a part that is not in hand).
        if jumped { schedule() }
    }

    private func forgetOldSongs() {
        let needed = Set([playingId, upcomingId, active?.track.videoId, downloadIds.first].compactMap { $0 } + laterIds)
        while recency.count > Self.songsKeptInMemory {
            guard let index = recency.firstIndex(where: { !needed.contains($0) }) else { break }
            tracks[recency.remove(at: index)] = nil
        }
    }

    // MARK: Choosing what to fetch

    private func needsFetching(_ videoId: String) -> Bool {
        guard let track = tracks[videoId] else { return false }
        return track.failure == nil && !track.buffer.isComplete
    }

    /// True when audio is missing at or after the listener's place in the song (rather than only behind it).
    private func isMissingAhead(_ videoId: String) -> Bool {
        guard let buffer = tracks[videoId]?.buffer else { return false }
        guard buffer.info != nil else { return true }
        let seconds = playhead?.videoId == videoId ? playhead?.seconds ?? 0 : 0
        let from = buffer.packetIndex(at: seconds)
        guard let gap = buffer.firstGap(from: from) else { return false }
        return gap >= from
    }

    /// The song the page should be fetching now:
    ///   1. the playing song, while audio from the listener's place onwards is missing;
    ///   2. the next song (its opening is needed before the parts of the playing song the listener skipped);
    ///   3. the rest of the playing song;
    ///   4. songs further up the queue, nearest first.
    private func mostUrgent() -> String? {
        if let id = playingId, needsFetching(id), isMissingAhead(id) { return id }
        if let id = upcomingId, needsFetching(id) { return id }
        if let id = playingId, needsFetching(id) { return id }
        if let id = laterIds.first(where: needsFetching) { return id }
        return downloadToFetch()
    }

    // MARK: Downloads

    /// Queues songs to be kept, in this order. Ones already kept are reported as finished at once.
    func startDownloads(_ videoIds: [String]) {
        for videoId in videoIds where !downloadIds.contains(videoId) {
            if downloads.contains(videoId) {
                reportKept(videoId)
            } else {
                downloadIds.append(videoId)
            }
        }
        schedule()
    }

    /// Stops fetching these for keeping. What is already kept stays.
    func cancelDownloads(_ videoIds: [String]) {
        let dropped = Set(videoIds)
        downloadIds.removeAll { dropped.contains($0) }
        // A fetch that only the download wanted is given up; one the listener is hearing carries on.
        if let job = active, dropped.contains(job.track.videoId) { schedule() }
    }

    /// Removes kept songs from the phone (and stops fetching them).
    func removeDownloads(_ videoIds: [String]) {
        cancelDownloads(videoIds)
        downloads.remove(videoIds)
    }

    /// True while songs are waiting to be fetched for keeping.
    var hasDownloadsWaiting: Bool { !downloadIds.isEmpty }

    /// The next song to fetch for keeping, once it is that song's turn; nil when none is due. On the way, songs at
    /// the head of the queue that need no fetching are dealt with: ones in hand are written out, failed ones
    /// reported.
    private func downloadToFetch() -> String? {
        guard allowsDownloads else { return nil }
        while let head = downloadIds.first {
            if downloads.contains(head) {
                downloadIds.removeFirst()
                reportKept(head)
                continue
            }
            let track: CapturedTrack
            if let known = tracks[head] {
                track = known
            } else if let kept = cache.load(head), !isOutclassed(kept.details?.format) {
                // Already fetched for listening, at the quality the account gets: keep that copy.
                track = CapturedTrack(videoId: head, buffer: kept.buffer, isFromCache: true)
                track.loudnessDb = kept.details?.loudnessDb
                track.format = kept.details?.format
                remember(track)
            } else {
                track = CapturedTrack(videoId: head)
                remember(track)
            }
            if let failure = track.failure {
                downloadIds.removeFirst()
                tracks[head] = nil
                onDownload?(.failed(videoId: head, message: failure))
                continue
            }
            if track.buffer.isComplete {
                downloadIds.removeFirst()
                keep(track)
                continue
            }
            return Date() >= downloadsRestUntil ? head : nil
        }
        return nil
    }

    private func remember(_ track: CapturedTrack) {
        tracks[track.videoId] = track
        recency.removeAll { $0 == track.videoId }
        recency.append(track.videoId)
    }

    /// Writes a complete song to the listener's downloads and reports it.
    private func keep(_ track: CapturedTrack) {
        let videoId = track.videoId, format = track.format
        let details = CachedTrackDetails(loudnessDb: track.loudnessDb, format: format, savedAt: Date())
        downloads.store(videoId, buffer: track.buffer, details: details) { [weak self] bytes in
            Task { @MainActor in
                if let bytes {
                    self?.onDownload?(.finished(videoId: videoId, bytes: bytes, quality: Self.quality(format)))
                } else {
                    self?.onDownload?(.failed(videoId: videoId, message: "There is not enough room on this iPhone."))
                }
            }
        }
        downloadsRestUntil = Date().addingTimeInterval(1.5)
    }

    private func reportKept(_ videoId: String) {
        let bytes = downloads.size(of: videoId)
        onDownload?(.finished(videoId: videoId, bytes: bytes, quality: Self.quality(downloads.details(of: videoId)?.format)))
    }

    /// "Opus 270 kbps", for the downloads list.
    private static func quality(_ format: AudioOffer?) -> String? {
        format.map { "\($0.codecName) \($0.bitrate / 1000) kbps" }
    }

    private func schedule() {
        // A fetch that has just brought in its song's last missing packet is finished (and kept on disk) before
        // anything else is decided: after that its song no longer counts as wanted.
        if let job = active, job.track.buffer.isComplete {
            finish(job)
            return
        }
        let wanted = mostUrgent()
        if let job = active {
            if job.track.videoId == wanted {
                advance(job)
                return
            }
            BoomTrace.note("capture of \(job.track.videoId) set aside for \(wanted ?? "nothing")")
            release(job)
        }
        guard let videoId = wanted, let track = tracks[videoId] else {
            // Between two downloads there is a short rest: the timer stays on to start the next one.
            if downloadIds.isEmpty || !allowsDownloads { stopTimer() } else { startTimer() }
            browser.setHeldAwake(false)
            return
        }
        let job = Job(track: track, playhead: playhead?.videoId == videoId ? playhead?.seconds ?? 0 : 0)
        active = job
        BoomTrace.note("capture start \(videoId) tab=\(self.browser.phase)")
        browser.setHeldAwake(true)
        startTimer()
        advance(job)
    }

    // MARK: A job's steps

    /// Takes the job's next step if its phase allows one now. Safe to call at any time.
    private func advance(_ job: Job) {
        let now = Date()
        switch job.phase {
        case .waitingForTab:
            switch browser.phase {
            case .failed(let message):
                // The tab's last load failed, perhaps long ago and for a reason that has passed (no signal in a
                // lift). The song gets one fresh load of the tab before it is given up on; with no connection that
                // load fails at once.
                if job.hasReloadedTab {
                    fail(job, message)
                } else {
                    job.hasReloadedTab = true
                    BoomTrace.note("capture loads the failed tab again for \(job.track.videoId)")
                    browser.reload()
                }
            case .loaded(_, hasPlayer: true):
                request(job)
            case .loaded(let at, hasPlayer: false):
                // A page without a player: the site makes one when a page is opened onto a song.
                if now.timeIntervalSince(at) > 1.2 { openPage(job) }
            case .loading, .switchingPage:
                break
            }
        case .requested(let at):
            // No audio buffer has opened. Try the song's own page once, then give up.
            let waited = now.timeIntervalSince(at)
            if waited > 14, !job.hasOpenedPage {
                openPage(job)
            } else if waited > 30 {
                fail(job, "Couldn't load this song. Check your connection.")
            }
        case .openingPage(let at):
            if now.timeIntervalSince(at) > 30 { fail(job, "Couldn't load this song. Check your connection.") }
        case .receiving:
            pull(job, now: now)
        }
    }

    /// Tells the page's player to load the song.
    private func request(_ job: Job) {
        job.move(to: .requested(at: Date()))
        let token = job.token, videoId = job.track.videoId
        defaults.set(videoId, forKey: Self.lastSongKey)
        Task {
            let answer = await browser.call("window.__boom.load(\(Self.quoted(token)), \(Self.quoted(videoId)))")
            guard active === job, case .requested = job.phase else { return }
            if answer as? String != "ok", !job.hasOpenedPage { openPage(job) }
        }
    }

    /// Opens the song's own page in the tab: the way in when the page has no player.
    private func openPage(_ job: Job) {
        guard !job.hasOpenedPage, let url = URL(string: "https://music.youtube.com/watch?v=\(job.track.videoId)")
        else { return }
        job.hasOpenedPage = true
        job.move(to: .openingPage(at: Date()))
        defaults.set(job.track.videoId, forKey: Self.lastSongKey)
        BoomTrace.note("capture opens the song's page \(job.track.videoId)")
        browser.open(url)
    }

    /// Claims the job's song in a page that was opened onto it.
    private func claim(_ job: Job) {
        let token = job.token, videoId = job.track.videoId
        Task { _ = await browser.call("window.__boom.adopt(\(Self.quoted(token)), \(Self.quoted(videoId)))") }
    }

    /// Stops the page fetching for a job and lets the job go. What it captured stays in the song's buffer.
    private func release(_ job: Job) {
        let token = job.token
        Task { _ = await browser.call("window.__boom.cancel(\(Self.quoted(token)))") }
        if active === job { active = nil }
    }

    private func finish(_ job: Job) {
        let track = job.track
        let seconds = Date().timeIntervalSince(job.startedAt)
        BoomTrace.note("capture done \(track.videoId) bytes=\(track.buffer.heldBytes) in \(String(format: "%.1f", seconds)) s format=\(track.format.map { "\($0.codecName) itag \($0.itag) \($0.bitrate / 1000)k" } ?? "-")")
        if let index = downloadIds.firstIndex(of: track.videoId) {
            downloadIds.remove(at: index)
            keep(track)
        } else {
            cache.store(
                track.videoId, buffer: track.buffer,
                details: CachedTrackDetails(loudnessDb: track.loudnessDb, format: track.format, savedAt: Date()))
        }
        release(job)
        schedule()
    }

    private func fail(_ job: Job, _ message: String) {
        BoomTrace.note("capture FAILED \(job.track.videoId): \(message)")
        job.track.failure = message
        job.track.onUpdate?()
        if let index = downloadIds.firstIndex(of: job.track.videoId) {
            downloadIds.remove(at: index)
            onDownload?(.failed(videoId: job.track.videoId, message: message))
        }
        release(job)
        schedule()
    }

    /// The tab started loading again, finished loading, got its player, or failed.
    private func tabDidChange() {
        // Another account (or none) may be given a different quality: what the last one got says nothing now.
        let signIn = (isSignedIn: browser.isSignedIn, profile: browser.activeProfileID)
        if lastSignIn.map({ $0 != signIn }) ?? true {
            if lastSignIn != nil { bestBitrate = 0 }
            lastSignIn = signIn
        }
        if let job = active {
            switch (job.phase, browser.phase) {
            case (.requested, .loading), (.receiving, .loading), (.openingPage, .loading):
                // The page under the fetch went away (a reload, or a new web view after an account switch). Ask
                // again when the tab is back.
                job.hasOpenedPage = false
                job.move(to: .waitingForTab(since: Date()))
            default:
                break
            }
        }
        schedule()
        if active == nil, case .loaded(_, hasPlayer: false) = browser.phase, !didWarmUp {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(2))
                self?.warmUp()
            }
        }
    }

    /// If the start page came without a player, the first song would pay for a page load. Opening the page of the
    /// song played last (without playing it: the script refuses that while nothing is asked for) gets a player ready
    /// while the listener is still choosing.
    private func warmUp() {
        guard !didWarmUp, active == nil, case .loaded(_, hasPlayer: false) = browser.phase,
              let last = defaults.string(forKey: Self.lastSongKey),
              let url = URL(string: "https://music.youtube.com/watch?v=\(last)") else { return }
        didWarmUp = true
        browser.open(url)
    }

    // MARK: What the page reports

    private func handle(_ event: TapEvent) {
        switch event {
        case .hello, .ready:
            // A fresh page, or its player. If it was opened for the job's song, claim that song in it.
            if let job = active, case .openingPage = job.phase { claim(job) }
            if case .ready = event { schedule() }
        case .player(let token, let videoId, let status, let reason, let seconds, let isLive, let loudness, _, let chosen):
            guard let job = active else { return }
            guard isAbout(job, token: token, videoId: videoId) else {
                // The page's player has turned to another song by itself (a signed-in page brings back the last
                // song of its own session a few seconds after it loads). Nothing more will come for the job's
                // song: ask for it again at once instead of waiting for the stall to show. Once only; if the page
                // insists, the slower recovery takes over.
                if case .receiving = job.phase, job.recovery == .none, let videoId, videoId != job.track.videoId,
                   !job.track.buffer.isComplete {
                    BoomTrace.note("the page turned to \(videoId) during \(job.track.videoId): asking the player again")
                    job.recovery = .askedAgain
                    job.needsNudge = true
                    request(job)
                }
                return
            }
            if status != "OK" {
                fail(job, Self.listenerText(status: status, reason: reason))
            } else if isLive {
                fail(job, "Live streams can't be played here.")
            } else {
                job.expectedSeconds = seconds
                if let seconds, seconds > 0 { job.track.buffer.setDuration(seconds) }
                job.track.loudnessDb = loudness
                job.track.format = chosen
                if let chosen, chosen.bitrate > bestBitrate { bestBitrate = chosen.bitrate }
                job.track.onUpdate?()
            }
        case .buffer(let token, let id, let mimeType):
            guard let job = active, token == job.token else { return }
            guard let demuxer = AudioDemuxers.make(mimeType: mimeType) else {
                fail(job, "This song's audio format isn't supported.")
                return
            }
            BoomTrace.note("capture buffer \(job.track.videoId) \(mimeType) after \(String(format: "%.2f", Date().timeIntervalSince(job.startedAt))) s")
            // A second buffer for the same song (the player started over) replaces the first.
            job.move(to: .receiving(Receiving(bufferID: id, demuxer: demuxer, held: job.track.buffer.heldPacketCount)))
            CaptureDump.begin()
        case .append(let token, let id, let data):
            guard let job = active, token == job.token, case .receiving(let state) = job.phase, id == state.bufferID,
                  !job.rejected.contains(id) else { return }
            if state.pieces == 0 {
                let head = data.prefix(24).map { String(format: "%02x", $0) }.joined()
                BoomTrace.note("capture first piece \(job.track.videoId): \(data.count) bytes, starts \(head)")
            }
            job.receiving {
                $0.pieces += 1
                $0.bytes += data.count
            }
            CaptureDump.add(data)
            feed(job, state.demuxer, data, id: id)
        case .restart(let token, let id):
            guard let job = active, token == job.token, case .receiving(let state) = job.phase, id == state.bufferID
            else { return }
            state.demuxer.reset()
        case .end(let token, let id, _):
            guard let job = active, token == job.token, case .receiving(let state) = job.phase, id == state.bufferID
            else { return }
            // The player appended the stream's last segment: the last packet in hand is the song's last.
            job.track.buffer.seal()
        case .unsupported(let reason):
            BoomTrace.note("page cannot capture: \(reason)")
            if let job = active { fail(job, "This iPhone's browser engine can't play this.") }
        case .scriptError(let place, let message):
            BoomTrace.note("script error in \(place): \(message)")
        }
    }

    /// True when a player answer belongs to the job: it carries the job's token, or it is about the job's song in a
    /// page that was opened onto that song and has not been claimed yet.
    private func isAbout(_ job: Job, token: String?, videoId: String?) -> Bool {
        if token == job.token { return true }
        if case .openingPage = job.phase { return videoId == job.track.videoId }
        return false
    }

    private func feed(_ job: Job, _ demuxer: any AudioDemuxer, _ data: Data, id: Int) {
        do {
            for event in try demuxer.feed(data) {
                switch event {
                case .track(let info):
                    // An advert or another video would have a different length than the song asked for.
                    if let stated = info.duration, let expected = job.expectedSeconds, abs(stated - expected) > 5 {
                        BoomTrace.note("buffer \(id) is \(stated) s long, the song \(expected) s: ignored")
                        job.rejected.insert(id)
                        return
                    }
                    BoomTrace.note("capture track \(job.track.videoId): \(info)")
                    job.track.buffer.setTrack(info, duration: job.expectedSeconds)
                case .packets(let packets):
                    job.track.buffer.append(packets)
                }
            }
        } catch {
            // The pieces after a jump may not line up; the next segment start puts the reader right again.
            BoomTrace.note("demux error \(String(describing: error))")
            demuxer.reset()
        }
    }

    private static func listenerText(status: String?, reason: String?) -> String {
        switch status {
        case "LOGIN_REQUIRED": return "Sign in to play this song."
        case "UNPLAYABLE", "ERROR": return reason.map { "This song can't be played: \($0)" } ?? "This song can't be played."
        case "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED": return "Sign in to confirm your age to play this."
        default: return reason ?? "This song can't be played right now."
        }
    }

    // MARK: Pulling the whole song in

    private func startTimer() {
        guard timer == nil else { return }
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, !Task.isCancelled else { return }
                // Looking again may show that another song has become more urgent, or take the job's next step.
                self.schedule()
            }
        }
    }

    private func stopTimer() {
        timer?.cancel()
        timer = nil
    }

    /// While receiving: notes growth, finishes when the song is whole, gives up on a long stall, and otherwise keeps
    /// the page fetching by moving its playhead.
    private func pull(_ job: Job, now: Date) {
        guard case .receiving(var state) = job.phase else { return }
        let buffer = job.track.buffer
        guard buffer.info != nil else {
            if now.timeIntervalSince(state.grewAt) > 30 {
                BoomTrace.note("capture of \(job.track.videoId) has no stream header after \(state.pieces) pieces, \(state.bytes) bytes")
                fail(job, "Couldn't load this song. Check your connection.")
            }
            return
        }
        if buffer.isComplete {
            finish(job)
            return
        }
        let held = buffer.heldPacketCount
        let grew = held != state.held
        if grew {
            state.held = held
            state.grewAt = now
            if downloadIds.contains(job.track.videoId) {
                let fraction = (buffer.coveredFraction * 50).rounded() / 50
                if lastDownloadProgress.videoId != job.track.videoId || lastDownloadProgress.fraction != fraction {
                    lastDownloadProgress = (job.track.videoId, fraction)
                    onDownload?(.progress(videoId: job.track.videoId, fraction: fraction))
                }
            }
        }
        let idle = now.timeIntervalSince(state.grewAt)
        if grew { job.recovery = .none }
        // Nothing has arrived for a while although the page was nudged: its player is stuck. Tell it to load the
        // song again; if that does not help, open the song's page afresh; only then give up. What is already in
        // hand is kept throughout, and fetching resumes at the first missing audio.
        if idle > 8 {
            switch job.recovery {
            case .none:
                BoomTrace.note("capture of \(job.track.videoId) stalled at \(held) packets: asking the player again")
                job.recovery = .askedAgain
                job.needsNudge = true
                request(job)
                return
            case .askedAgain where !job.hasOpenedPage:
                BoomTrace.note("capture of \(job.track.videoId) still stalled: reopening its page")
                job.recovery = .reopenedPage
                job.needsNudge = true
                openPage(job)
                return
            case .askedAgain, .reopenedPage:
                if idle > 25 {
                    fail(job, "This song stopped loading. Check your connection.")
                    return
                }
            }
        }
        defer { job.receiving { $0 = state } }
        // The player fetches only so far ahead of its playhead. When it has stopped (a lull), or the listener has
        // jumped, move its playhead to the first audio missing from the listener's place on (wrapping round to
        // earlier gaps), and it fetches on from there.
        guard job.needsNudge || (!grew && idle > 0.6),
              let gap = buffer.firstGap(from: buffer.packetIndex(at: job.playhead)) else { return }
        job.needsNudge = false
        let target = max(0, buffer.time(ofPacket: gap) - 0.4)
        if let last = state.lastNudge, last.target == target, now.timeIntervalSince(last.at) < 0.5 { return }
        state.lastNudge = (now, target)
        let token = job.token, videoId = job.track.videoId
        let isQuiet = idle > 3
        Task {
            let answer = await browser.call("window.__boom.pump(\(Self.quoted(token)), \(target))")
            // While audio flows the answer is of no interest; when it has stopped, it says what the page's player
            // is doing.
            if isQuiet, let answer = answer as? [String: Any] {
                let text = answer.keys.sorted().map { "\($0)=\(answer[$0] ?? "")" }.joined(separator: " ")
                BoomTrace.note("page while \(videoId) is quiet: \(text)")
            }
        }
    }

    /// A string as a JavaScript literal.
    private static func quoted(_ text: String) -> String {
        guard let data = try? JSONEncoder().encode(text), let literal = String(data: data, encoding: .utf8)
        else { return "\"\"" }
        return literal
    }
}

/// With the launch argument `-BoomDump 1`, keeps the first megabyte of the newest stream the page handed over, as
/// `boom-dump.bin` in the app's Documents folder, for looking into a stream the app cannot read. It holds audio
/// data only.
enum CaptureDump {
    private static let isOn = UserDefaults.standard.bool(forKey: "BoomDump")
    private static let limit = 1_000_000
    private static var url: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appending(path: "boom-dump.bin")
    }

    static func begin() {
        guard isOn, let url else { return }
        try? Data().write(to: url)
    }

    static func add(_ data: Data) {
        guard isOn, let url, let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        guard let size = try? handle.seekToEnd(), size < UInt64(limit) else { return }
        try? handle.write(contentsOf: data.prefix(limit - Int(size)))
    }
}

/// What happens to a song the listener asked to keep.
public enum DownloadEvent: Sendable, Equatable {
    /// How much of it has arrived, 0...1.
    case progress(videoId: String, fraction: Double)
    /// It is on the phone. `quality` is for the listener: "Opus 270 kbps".
    case finished(videoId: String, bytes: Int64, quality: String?)
    /// It could not be fetched or written. The text is for the listener.
    case failed(videoId: String, message: String)
}
