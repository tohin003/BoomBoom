#if canImport(AppKit)
import AppKit
import BoomAudio
import BoomModels
import Foundation
import Testing
@testable import BoomPlayer

/// The real thing, on the Mac: the hidden tab loads YouTube Music, the broker asks for a song, and the audio the
/// page's player fetches arrives in the track buffer. Needs the network, so it only runs when asked:
///   BOOM_LIVE=1 swift test --filter LiveCaptureTests
/// BOOM_LIVE_SONG=<videoId> picks the song; BOOM_LIVE_VIDEO=none|lowest|all how much picture to let through.
/// A queue of the two songs given, and nothing else: enough of a data source for the player.
private final class TwoSongAPI: MusicAPI, @unchecked Sendable {
    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue { WatchQueue() }
    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation { .init(songs: [], continuation: nil) }
    func lyrics(browseId: String) async throws -> Lyrics { Lyrics(lines: []) }
    func related(browseId: String) async throws -> [Shelf] { [] }
    func rate(videoId: String, _ status: LikeStatus) async throws {}
    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] { [] }
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults { SearchResults(query: query) }
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation { .init(items: [], continuation: nil) }
    func home(chip: Chip?) async throws -> BrowsePage { BrowsePage() }
    func explore() async throws -> BrowsePage { BrowsePage() }
    func charts(country: String?) async throws -> BrowsePage { BrowsePage() }
    func moodsAndGenres() async throws -> [ChipGroup] { [] }
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage { BrowsePage() }
    func browseContinuation(_ token: String) async throws -> BrowsePage { BrowsePage() }
    func album(id: String) async throws -> AlbumPage { AlbumPage(album: AlbumItem(id: id, title: id)) }
    func artist(id: String) async throws -> ArtistPage { ArtistPage(artist: ArtistItem(id: id, name: id)) }
    func playlist(id: String) async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: id, title: id)) }
    func playlistContinuation(_ token: String) async throws -> SongsContinuation { .init(songs: [], continuation: nil) }
    func account() async throws -> Account? { nil }
    func library(_ section: LibrarySection) async throws -> BrowsePage { BrowsePage() }
    func likedSongs() async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: "LM", title: "Liked")) }
    func history() async throws -> BrowsePage { BrowsePage() }
    func ratePlaylist(id: String, _ status: LikeStatus) async throws {}
    func subscribe(channelId: String, _ subscribed: Bool) async throws {}
    func sendLibraryFeedback(token: String) async throws {}
    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String]) async throws -> String { "PL" }
    func deletePlaylist(id: String) async throws {}
    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws {}
    func addToPlaylist(id: String, videoIds: [String]) async throws {}
    func removeFromPlaylist(id: String, songs: [Song]) async throws {}
}

@MainActor
@Suite(.serialized) struct LiveCaptureTests {
    /// Downloads for real: two songs are asked for, fetched through the hidden tab one after the other, and kept in
    /// the downloads folder; a song being played at the same time comes first. Afterwards a kept song loads complete
    /// from the folder alone.
    @Test func downloadsAreFetchedKeptAndGiveWayToPlayback() async throws {
        guard isEnabled else { return }
        let suite = "boom.live.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let browser = HiddenBrowser(defaults: defaults)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 300), styleMask: [.borderless],
            backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView?.addSubview(browser.webView)
        window.orderBack(nil)
        browser.start()
        let folder = FileManager.default.temporaryDirectory.appending(path: suite)
        let library = DownloadLibrary(folder: folder.appending(path: "downloads"))
        let broker = StreamBroker(
            browser: browser, cache: AudioCache(folder: folder.appending(path: "cache")), downloads: library, defaults: defaults)
        let engine = LiveEngine(broker: broker, system: SystemAudio(), rendersOffline: true)
        let store = DownloadStore(backend: engine, indexURL: library.indexURL, defaults: defaults)
        let player = PlayerStore(api: TwoSongAPI(), engine: engine, defaults: defaults)
        player.isAutoplayEnabled = false
        defer {
            player.stop()
            window.close()
            try? FileManager.default.removeItem(at: folder)
        }

        let started = Date()
        let list = DownloadCollection(id: "VLtest", kind: .playlist, title: "Test")
        store.download([Song(videoId: "kJQP7kiw5Fk", title: "One", duration: 282), Song(videoId: "9bZkp7q19f0", title: "Two", duration: 252)], as: list)
        // The listener starts another song while the downloads are queued: it must not wait for them.
        player.play(songs: [Song(videoId: "dQw4w9WgXcQ", title: "Playing", duration: 213)], startAt: 0)
        func waitUntil(_ seconds: Double, _ condition: () -> Bool) async -> Bool {
            let deadline = Date().addingTimeInterval(seconds)
            while Date() < deadline {
                if condition() { return true }
                try? await Task.sleep(for: .milliseconds(100))
            }
            return condition()
        }
        #expect(await waitUntil(60) { player.status == .playing })
        let toPlay = Date().timeIntervalSince(started)
        #expect(store.downloaded.isEmpty)          // playback came first
        #expect(await waitUntil(150) { store.progress(of: "VLtest").downloaded == 2 })
        let total = Date().timeIntervalSince(started)
        print("LIVE DOWNLOAD playing after \(String(format: "%.1f", toPlay)) s; both songs kept after \(String(format: "%.1f", total)) s; "
            + store.items.map { "\($0.id) \($0.state) \($0.bytes / 1000) kB \($0.quality ?? "-")" }.joined(separator: "; "))
        if store.progress(of: "VLtest").downloaded != 2, let trace = try? String(contentsOf: BoomTrace.fileURL, encoding: .utf8) {
            print(trace.split(separator: "\n").suffix(20).map { "LIVE TRACE \($0)" }.joined(separator: "\n"))
        }
        #expect(store.totalBytes > 4_000_000)
        #expect(library.contains("kJQP7kiw5Fk") && library.contains("9bZkp7q19f0"))
        let kept = try #require(library.load("9bZkp7q19f0"))
        #expect(kept.buffer.isComplete)
        #expect(abs(Double(kept.buffer.heldPacketCount * kept.buffer.packetDuration) / 48_000 - 252) < 3)
        // The playing song was not put among the downloads: nobody asked to keep it.
        #expect(!library.contains("dQw4w9WgXcQ"))
        // Removing one frees its file.
        store.remove(["kJQP7kiw5Fk"])
        #expect(await waitUntil(3) { !library.contains("kJQP7kiw5Fk") })
        #expect(store.collections.first?.songIDs == ["9bZkp7q19f0"])
    }

    /// The whole stack with nothing sent to the speakers: the player's own logic, the live capture, decoding and the
    /// audio engine, rendered offline at listening speed.
    @Test func thePlayerPlaysSeeksAndRunsIntoTheNextSong() async throws {
        guard isEnabled else { return }
        let suite = "boom.live.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let browser = HiddenBrowser(defaults: defaults)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 300), styleMask: [.borderless],
            backing: .buffered, defer: false)
        // Under ARC a window must not also release itself when closed.
        window.isReleasedWhenClosed = false
        window.contentView?.addSubview(browser.webView)
        window.orderBack(nil)
        browser.start()
        let folder = FileManager.default.temporaryDirectory.appending(path: suite)
        let broker = StreamBroker(browser: browser, cache: AudioCache(folder: folder), defaults: defaults)
        let engine = LiveEngine(broker: broker, system: SystemAudio(), rendersOffline: true)
        let player = PlayerStore(api: TwoSongAPI(), engine: engine, defaults: defaults)
        player.isAutoplayEnabled = false

        let first = Song(videoId: "dQw4w9WgXcQ", title: "First", duration: 213)
        let second = Song(videoId: "kJQP7kiw5Fk", title: "Second", duration: 282)
        func waitUntil(_ seconds: Double, _ what: String, _ condition: () -> Bool) async -> Bool {
            let deadline = Date().addingTimeInterval(seconds)
            while Date() < deadline {
                if condition() { return true }
                try? await Task.sleep(for: .milliseconds(50))
            }
            print("LIVE PLAYER timed out waiting for \(what): status=\(player.status) pos=\(player.clock.position) "
                + "current=\(player.current?.videoId ?? "-") buffered=\(Int(player.clock.bufferedFraction * 100))%")
            if let trace = try? String(contentsOf: BoomTrace.fileURL, encoding: .utf8) {
                print(trace.split(separator: "\n").suffix(14).map { "LIVE TRACE \($0)" }.joined(separator: "\n"))
            }
            return condition()
        }

        let started = Date()
        player.play(songs: [first, second], startAt: 0)
        #expect(player.status == .loading)
        #expect(await waitUntil(90, "the first song to play") { player.status == .playing })
        let toPlay = Date().timeIntervalSince(started)
        try await Task.sleep(for: .seconds(3))
        let heard = engine.heard.state.withLock { $0 }
        print("LIVE PLAYER playing after \(String(format: "%.1f", toPlay)) s; 3 s later pos=\(String(format: "%.2f", player.clock.position)) "
            + "heard=\(heard.frames) frames peak=\(heard.peak) format=\(player.audioFormat ?? "-") duration=\(player.clock.duration)")
        #expect(player.clock.position > 1.5 && player.clock.position < 4.5)
        #expect(heard.frames > 48_000)
        #expect(heard.peak > 0.02 && heard.peak <= 1.0)
        #expect(abs(player.clock.duration - 213) < 2)

        // Pause holds the place and the silence.
        player.pause()
        try await Task.sleep(for: .milliseconds(300))
        let held = player.clock.position
        let heardWhenPaused = engine.heard.state.withLock { $0.frames }
        try await Task.sleep(for: .milliseconds(700))
        #expect(player.status == .paused)
        #expect(abs(player.clock.position - held) < 0.05)
        #expect(engine.heard.state.withLock { $0.frames } == heardWhenPaused)
        player.resume()
        #expect(await waitUntil(5, "playback to resume") { player.status == .playing })

        // A jump to a part that may not have been fetched yet: it plays once that part is in.
        player.seek(to: 150)
        #expect(await waitUntil(60, "playback after the jump") { player.status == .playing && player.clock.position >= 150.5 })
        print("LIVE PLAYER after the jump pos=\(String(format: "%.2f", player.clock.position)) buffered=\(Int(player.clock.bufferedFraction * 100))%")

        // Near the end: the second song takes over by itself, without a gap in the queue's bookkeeping.
        player.seek(to: 209)
        #expect(await waitUntil(90, "the second song") { player.current?.videoId == second.videoId && player.status == .playing })
        try await Task.sleep(for: .seconds(2))
        print("LIVE PLAYER second song pos=\(String(format: "%.2f", player.clock.position)) status=\(player.status) "
            + "duration=\(player.clock.duration) format=\(player.audioFormat ?? "-")")
        #expect(player.clock.position > 0.5 && player.clock.position < 12)
        #expect(player.status == .playing)
        player.stop()
        window.close()
    }

    private var isEnabled: Bool { ProcessInfo.processInfo.environment["BOOM_LIVE"] == "1" }

    @Test func capturesAWholeSongAtFullQuality() async throws {
        guard isEnabled else { return }
        let environment = ProcessInfo.processInfo.environment
        let videoId = environment["BOOM_LIVE_SONG"] ?? "dQw4w9WgXcQ"
        let suite = "boom.live.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let folder = FileManager.default.temporaryDirectory.appending(path: suite)

        let browser = HiddenBrowser(defaults: defaults)
        // The page only runs properly with its view in a window.
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 300), styleMask: [.borderless],
            backing: .buffered, defer: false)
        // Under ARC a window must not also release itself when closed.
        window.isReleasedWhenClosed = false
        window.contentView?.addSubview(browser.webView)
        window.orderBack(nil)
        browser.start()

        let broker = StreamBroker(browser: browser, cache: AudioCache(folder: folder), defaults: defaults)
        if let mode = environment["BOOM_LIVE_VIDEO"] {
            // Set as soon as the script is in the page.
            Task {
                for _ in 0..<200 {
                    if await browser.call("window.__boom.setVideo('\(mode)')") != nil { break }
                    try? await Task.sleep(for: .milliseconds(100))
                }
            }
        }

        let started = Date()
        let track = broker.track(for: videoId, purpose: .playing)
        var firstAudioAt: TimeInterval?
        var playerReadyAt: TimeInterval?
        var lastReport = 0.0
        while Date().timeIntervalSince(started) < 150, !track.buffer.isComplete, track.failure == nil {
            try await Task.sleep(for: .milliseconds(100))
            let elapsed = Date().timeIntervalSince(started)
            if playerReadyAt == nil, browser.isPlayerReady { playerReadyAt = elapsed }
            if firstAudioAt == nil, track.buffer.heldPacketCount > 0 { firstAudioAt = elapsed }
            if elapsed - lastReport >= 5 {
                lastReport = elapsed
                print(String(
                    format: "LIVE t=%.0f tab=%@ player=%@ held=%d packets (%.0f%%)", elapsed,
                    String(describing: browser.state), browser.isPlayerReady ? "ready" : "no",
                    track.buffer.heldPacketCount, track.buffer.coveredFraction * 100))
            }
        }
        let took = Date().timeIntervalSince(started)
        let buffer = track.buffer
        let seconds = buffer.duration ?? 0
        let measured = seconds > 0 ? Double(buffer.heldBytes) * 8 / seconds / 1000 : 0
        print("""
            LIVE RESULT song=\(videoId) complete=\(buffer.isComplete) failure=\(track.failure ?? "-")
            LIVE RESULT format=\(track.format.map { "\($0.codecName) itag \($0.itag), offered at \($0.bitrate / 1000) kbps" } ?? "-")
            LIVE RESULT codec=\(buffer.info?.codec.rawValue ?? "-") rate=\(buffer.info?.sampleRate ?? 0) packets=\(buffer.heldPacketCount)/\(buffer.expectedPacketCount ?? -1)
            LIVE RESULT bytes=\(buffer.heldBytes) length=\(String(format: "%.1f", seconds)) s measured=\(String(format: "%.0f", measured)) kbps loudness=\(track.loudnessDb.map { String($0) } ?? "-") dB
            LIVE RESULT playerReady=\(playerReadyAt.map { String(format: "%.1f", $0) } ?? "-") s firstAudio=\(firstAudioAt.map { String(format: "%.1f", $0) } ?? "-") s whole song=\(String(format: "%.1f", took)) s
            """)
        #expect(track.failure == nil)
        #expect(buffer.isComplete)
        // The floor the app promises: never a low-bitrate stream.
        #expect(measured >= 115)

        // What was captured decodes and plays through the pipeline.
        let info = try #require(buffer.info)
        let decoder = try PacketDecoder(track: info)
        let pcm = try #require(try decoder.decode(buffer.packets(from: 500, limit: 100)))
        #expect(pcm.frameLength > 0)

        // A second song goes through the already loaded player, without a page load.
        let second = environment["BOOM_LIVE_SECOND"] ?? "kJQP7kiw5Fk"
        let secondStarted = Date()
        let next = broker.track(for: second, purpose: .playing)
        var secondFirstAudio: TimeInterval?
        while Date().timeIntervalSince(secondStarted) < 60, next.failure == nil,
              next.buffer.heldPacketCount < 1500 {
            try await Task.sleep(for: .milliseconds(50))
            if secondFirstAudio == nil, next.buffer.heldPacketCount > 0 {
                secondFirstAudio = Date().timeIntervalSince(secondStarted)
            }
        }
        print("LIVE RESULT second song firstAudio=\(secondFirstAudio.map { String(format: "%.2f", $0) } ?? "-") s, "
            + "30 s in hand after \(String(format: "%.1f", Date().timeIntervalSince(secondStarted))) s, "
            + "format=\(next.format.map { "\($0.codecName) \($0.bitrate / 1000) kbps" } ?? "-")")
        #expect(next.failure == nil)
        #expect(next.buffer.heldPacketCount >= 1500)
        window.close()
    }
}
#endif
