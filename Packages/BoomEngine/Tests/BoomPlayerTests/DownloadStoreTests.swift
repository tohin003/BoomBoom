import BoomAudio
import BoomModels
import Foundation
import Testing
@testable import BoomPlayer

/// Stands in for the engine: remembers what it was asked and lets a test say what happens to each song.
@MainActor
private final class FakeDownloads: DownloadBackend {
    var onDownloadEvent: (@MainActor (DownloadEvent) -> Void)?
    var onDownloadHoldChange: (@MainActor () -> Void)?
    var allowsDownloadsOnMobileData = false {
        didSet { onDownloadHoldChange?() }
    }
    var isOnMobileData = false {
        didSet { onDownloadHoldChange?() }
    }
    var isHoldingDownloadsForWiFi: Bool { isOnMobileData && !allowsDownloadsOnMobileData }
    var started: [String] = []
    var removed: [String] = []

    func startDownloads(_ videoIds: [String]) { started.append(contentsOf: videoIds) }
    func removeDownloads(_ videoIds: [String]) { removed.append(contentsOf: videoIds) }

    func finish(_ videoId: String, bytes: Int64 = 5_000_000) {
        onDownloadEvent?(.finished(videoId: videoId, bytes: bytes, quality: "Opus 270 kbps"))
    }
}

private func song(_ id: String, available: Bool = true) -> Song {
    Song(videoId: id, title: "Song \(id)", isAvailable: available)
}

@MainActor
@Suite struct DownloadStoreTests {
    private func makeStore(_ backend: FakeDownloads, index: URL? = nil) -> DownloadStore {
        DownloadStore(backend: backend, indexURL: index, defaults: UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!)
    }

    @Test func aSongGoesFromWaitingToDownloaded() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        #expect(store.state(of: "a") == nil)
        store.download(song("a"))
        #expect(store.state(of: "a") == .waiting && backend.started == ["a"])
        #expect(store.pendingCount == 1 && store.totalBytes == 0)
        backend.onDownloadEvent?(.progress(videoId: "a", fraction: 0.5))
        #expect(store.state(of: "a") == .downloading(0.5))
        backend.finish("a", bytes: 6_000_000)
        #expect(store.state(of: "a") == .downloaded)
        #expect(store.totalBytes == 6_000_000 && store.pendingCount == 0)
        #expect(store.items[0].quality == "Opus 270 kbps")
        // Asking again changes nothing and fetches nothing.
        store.download(song("a"))
        #expect(backend.started == ["a"] && store.items.count == 1)
    }

    @Test func anAlbumIsKeptAsAWholeAndRemovedAsAWhole() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        store.download(song("solo"))
        let album = DownloadCollection(id: "MPREb_1", kind: .album, title: "An album", subtitle: "Someone")
        store.download([song("t1"), song("gone", available: false), song("t2"), song("solo")], as: album)
        // The unplayable track is left out; the song already asked for is not fetched twice.
        #expect(store.collections.first?.songIDs == ["t1", "t2", "solo"])
        #expect(backend.started == ["solo", "t1", "t2"])
        #expect(store.items.map(\.id) == ["t1", "t2", "solo"])
        #expect(store.progress(of: "MPREb_1") == (0, 3))
        backend.finish("t1")
        backend.finish("t2", bytes: 3_000_000)
        backend.finish("solo")
        #expect(store.progress(of: "MPREb_1") == (3, 3))
        #expect(store.bytes(of: "MPREb_1") == 13_000_000)
        // Removing the album takes its own songs and leaves the one the listener also downloaded by itself.
        store.removeCollection("MPREb_1")
        #expect(store.collections.isEmpty)
        #expect(Set(backend.removed) == ["t1", "t2"])
        #expect(store.items.map(\.id) == ["solo"] && store.totalBytes == 5_000_000)
    }

    @Test func songsAreRemovedOneOrManyAtATime() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        let list = DownloadCollection(id: "VLPL1", kind: .playlist, title: "A playlist")
        store.download([song("a"), song("b"), song("c")], as: list)
        for id in ["a", "b", "c"] { backend.finish(id) }
        store.remove(["a"])
        #expect(store.collections.first?.songIDs == ["b", "c"] && backend.removed == ["a"])
        store.remove(["b", "c"])
        // A playlist with nothing left is gone too.
        #expect(store.collections.isEmpty && store.items.isEmpty && store.totalBytes == 0)
        store.download(song("d"))
        store.removeAll()
        #expect(store.items.isEmpty && backend.removed.contains("d"))
    }

    @Test func aFailureCanBeTriedAgain() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        store.download(song("a"))
        backend.onDownloadEvent?(.failed(videoId: "a", message: "This song is not available."))
        #expect(store.state(of: "a") == .failed("This song is not available.") && store.failedCount == 1)
        store.retryFailed()
        #expect(store.state(of: "a") == .waiting && backend.started == ["a", "a"])
    }

    @Test func tryingAgainOnMobileDataShowsTheWaitForWiFi() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        store.download(song("a"))
        backend.onDownloadEvent?(.failed(videoId: "a", message: "No connection."))
        backend.isOnMobileData = true
        #expect(!store.isWaitingForWiFi)          // nothing is pending while it stands as failed
        store.retryFailed()
        #expect(store.isWaitingForWiFi)
    }

    @Test func theListSurvivesARestartAndCarriesOn() {
        let index = FileManager.default.temporaryDirectory.appending(path: "boom-downloads-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: index) }
        let backend = FakeDownloads()
        let first = makeStore(backend, index: index)
        let list = DownloadCollection(id: "VLPL1", kind: .playlist, title: "A playlist")
        first.download([song("a"), song("b"), song("c")], as: list)
        backend.finish("a")
        backend.onDownloadEvent?(.progress(videoId: "b", fraction: 0.4))
        first.saveNow()

        let later = FakeDownloads()
        let second = makeStore(later, index: index)
        #expect(second.items.map(\.id) == ["a", "b", "c"])
        #expect(second.state(of: "a") == .downloaded && second.state(of: "b") == .waiting)
        #expect(second.collections.first?.title == "A playlist")
        // The kept song is checked against its file, the others are asked for again.
        #expect(later.started == ["a", "b", "c"])
    }

    @Test func downloadsWaitForWiFiUnlessMobileDataIsAllowed() {
        let backend = FakeDownloads()
        let store = makeStore(backend)
        backend.isOnMobileData = true
        #expect(!store.isWaitingForWiFi)          // nothing to download yet
        store.download(song("a"))
        #expect(store.isWaitingForWiFi)
        store.isMobileDataAllowed = true
        #expect(backend.allowsDownloadsOnMobileData && !store.isWaitingForWiFi)
    }

    @Test func theDownloadsFolderKeepsASongAndGivesItBack() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "boom-library-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let library = DownloadLibrary(folder: folder)
        let buffer = TrackBuffer()
        buffer.setTrack(AudioTrackInfo(codec: .opus, timescale: 48_000, sampleRate: 48_000, channels: 2, magicCookie: Data([1, 2, 3])), duration: 0.1)
        buffer.append((0..<5).map { AudioPacket(decodeTime: Int64($0) * 960, duration: 960, data: Data(repeating: UInt8($0), count: 40)) })
        buffer.seal()
        #expect(buffer.isComplete)
        let bytes: Int64? = await withCheckedContinuation { continuation in
            library.store("abc-_123", buffer: buffer, details: CachedTrackDetails(loudnessDb: -3, format: nil, savedAt: Date())) {
                continuation.resume(returning: $0)
            }
        }
        #expect((bytes ?? 0) > 200)
        #expect(library.contains("abc-_123") && library.size(of: "abc-_123") == bytes)
        let back = try #require(library.load("abc-_123"))
        #expect(back.buffer.isComplete && back.buffer.heldPacketCount == 5 && back.details?.loudnessDb == -3)
        library.remove(["abc-_123"])
        for _ in 0..<50 where library.contains("abc-_123") { try await Task.sleep(for: .milliseconds(20)) }
        #expect(!library.contains("abc-_123"))
    }
}
