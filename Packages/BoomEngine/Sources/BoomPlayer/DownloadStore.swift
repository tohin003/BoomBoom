import BoomModels
import Foundation
import Observation

/// One song the listener asked to keep on the phone.
public struct DownloadItem: Identifiable, Codable, Sendable, Equatable {
    public enum State: Codable, Sendable, Equatable {
        /// In the queue.
        case waiting
        /// Arriving; the fraction in hand, 0...1.
        case downloading(Double)
        /// On the phone: plays at once and without a connection.
        case downloaded
        /// Could not be fetched. The text is for the listener.
        case failed(String)
    }

    public var song: Song
    public var state: State
    /// The file's size once downloaded, 0 before.
    public var bytes: Int64
    /// "Opus 270 kbps", once downloaded.
    public var quality: String?
    public var addedAt: Date
    /// True when the listener downloaded this song by itself (rather than only as part of an album or playlist):
    /// it then stays when such a collection is removed.
    public var isStandalone: Bool

    public var id: String { song.videoId }

    public var isDownloaded: Bool { state == .downloaded }
}

/// An album or playlist the listener downloaded as a whole.
public struct DownloadCollection: Identifiable, Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable { case album, playlist }

    /// The album's or playlist's own id (its browse id).
    public var id: String
    public var kind: Kind
    public var title: String
    /// The artist, or who made the playlist.
    public var subtitle: String?
    public var artwork: Artwork
    public var songIDs: [String]
    public var addedAt: Date

    public init(
        id: String, kind: Kind, title: String, subtitle: String? = nil, artwork: Artwork = .empty,
        songIDs: [String] = [], addedAt: Date = Date()
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.subtitle = subtitle
        self.artwork = artwork
        self.songIDs = songIDs
        self.addedAt = addedAt
    }
}

/// The fetching and the files behind `DownloadStore`. The audio engine implements it.
@MainActor
public protocol DownloadBackend: AnyObject {
    var onDownloadEvent: (@MainActor (DownloadEvent) -> Void)? { get set }
    /// Queues songs to be kept, in this order. Ones already on the phone answer `.finished` at once.
    func startDownloads(_ videoIds: [String])
    /// Removes kept songs from the phone, and stops fetching them.
    func removeDownloads(_ videoIds: [String])
    /// Whether songs may be fetched for keeping over mobile data (or in Low Data Mode).
    var allowsDownloadsOnMobileData: Bool { get set }
    /// True while downloads are held back because the phone is on mobile data and that is not allowed.
    var isHoldingDownloadsForWiFi: Bool { get }
    /// Called when `isHoldingDownloadsForWiFi` changes.
    var onDownloadHoldChange: (@MainActor () -> Void)? { get set }
}

/// The listener's downloads: songs, albums and playlists kept on the phone at the best quality the account gets,
/// until the listener removes them. They play with no connection and never count against the automatic cache.
///
/// Fetching shares the hidden tab with playback and always gives way to it, so a long playlist takes its time while
/// music plays. iOS stops an app that is neither on screen nor playing; the queue is remembered and carries on the
/// next time the app is open.
@MainActor
@Observable
public final class DownloadStore {
    /// Every song asked for, newest first: downloaded, on its way, waiting or failed.
    public private(set) var items: [DownloadItem] = []
    /// Albums and playlists downloaded as a whole, newest first.
    public private(set) var collections: [DownloadCollection] = []
    /// True while downloads wait for Wi-Fi because mobile data is not allowed for them.
    public private(set) var isWaitingForWiFi = false

    /// Whether to download over mobile data too. Off unless the listener turns it on.
    public var isMobileDataAllowed: Bool {
        didSet {
            guard isMobileDataAllowed != oldValue else { return }
            defaults.set(isMobileDataAllowed, forKey: Self.mobileDataKey)
            backend?.allowsDownloadsOnMobileData = isMobileDataAllowed
            refreshHold()
        }
    }

    @ObservationIgnored private weak var backend: (any DownloadBackend)?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let indexURL: URL?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    private static let mobileDataKey = "downloads.mobileData"

    private struct Index: Codable {
        var items: [DownloadItem]
        var collections: [DownloadCollection]
    }

    /// - Parameters:
    ///   - backend: What fetches and stores. Nil for previews: songs then "download" instantly, in memory.
    ///   - indexURL: Where the list is kept between launches. Nil keeps it in memory only.
    public init(backend: (any DownloadBackend)?, indexURL: URL? = nil, defaults: UserDefaults = .standard) {
        self.backend = backend
        self.indexURL = indexURL
        self.defaults = defaults
        isMobileDataAllowed = defaults.bool(forKey: Self.mobileDataKey)
        if let indexURL, let data = try? Data(contentsOf: indexURL),
           let index = try? JSONDecoder().decode(Index.self, from: data) {
            items = index.items.map { item in
                var item = item
                // What was arriving when the app last closed starts over from what is in hand.
                if case .downloading = item.state { item.state = .waiting }
                return item
            }
            collections = index.collections
        }
        backend?.allowsDownloadsOnMobileData = isMobileDataAllowed
        backend?.onDownloadEvent = { [weak self] event in self?.handle(event) }
        backend?.onDownloadHoldChange = { [weak self] in self?.refreshHold() }
        // Carry on with what was still to do. Kept songs are confirmed against the files (one may have been lost).
        let pending = items.filter { $0.state == .waiting }.map(\.id)
        let kept = items.filter(\.isDownloaded).map(\.id)
        if !(pending + kept).isEmpty { backend?.startDownloads(kept + pending) }
        refreshHold()
    }

    // MARK: Looking

    /// The state of a song, nil when the listener never asked to keep it.
    public func state(of videoId: String) -> DownloadItem.State? {
        items.first { $0.id == videoId }?.state
    }

    /// The downloaded songs only, newest first.
    public var downloaded: [DownloadItem] { items.filter(\.isDownloaded) }

    /// Bytes on the phone taken by downloads.
    public var totalBytes: Int64 { items.reduce(0) { $0 + ($1.isDownloaded ? $1.bytes : 0) } }

    /// How many songs are still to come (waiting or arriving).
    public var pendingCount: Int {
        items.reduce(0) { count, item in
            switch item.state {
            case .waiting, .downloading: count + 1
            case .downloaded, .failed: count
            }
        }
    }

    public var failedCount: Int {
        items.reduce(0) { count, item in
            if case .failed = item.state { return count + 1 }
            return count
        }
    }

    /// The songs of a downloaded album or playlist, in its order.
    public func songs(of collectionID: String) -> [DownloadItem] {
        guard let collection = collections.first(where: { $0.id == collectionID }) else { return [] }
        return collection.songIDs.compactMap { id in items.first { $0.id == id } }
    }

    /// How far a downloaded album or playlist has come: songs on the phone, and songs in all.
    public func progress(of collectionID: String) -> (downloaded: Int, total: Int) {
        let songs = songs(of: collectionID)
        return (songs.filter(\.isDownloaded).count, songs.count)
    }

    public func bytes(of collectionID: String) -> Int64 {
        songs(of: collectionID).reduce(0) { $0 + ($1.isDownloaded ? $1.bytes : 0) }
    }

    // MARK: Downloading

    /// Keeps one song.
    public func download(_ song: Song) {
        add([song], standalone: true)
    }

    /// Keeps an album or a playlist: its songs in order, and the collection itself so that it can be found and
    /// removed as a whole. Downloading it again adds songs that have joined it since.
    public func download(_ songs: [Song], as collection: DownloadCollection) {
        let playable = songs.filter(\.isAvailable)
        var entry = collection
        entry.songIDs = playable.map(\.videoId)
        if let index = collections.firstIndex(where: { $0.id == collection.id }) {
            entry.addedAt = collections[index].addedAt
            collections[index] = entry
        } else {
            collections.insert(entry, at: 0)
        }
        add(playable, standalone: false)
    }

    private func add(_ songs: [Song], standalone: Bool) {
        var started: [String] = []
        // Newest first in the list, while the songs of one request keep their own order.
        var fresh: [DownloadItem] = []
        for song in songs where song.isAvailable {
            if let index = items.firstIndex(where: { $0.id == song.videoId }) {
                if standalone { items[index].isStandalone = true }
                if case .failed = items[index].state {
                    items[index].state = .waiting
                    started.append(song.videoId)
                }
            } else if !fresh.contains(where: { $0.id == song.videoId }) {
                fresh.append(DownloadItem(
                    song: song, state: .waiting, bytes: 0, quality: nil, addedAt: Date(), isStandalone: standalone))
                started.append(song.videoId)
            }
        }
        items.insert(contentsOf: fresh, at: 0)
        save()
        guard !started.isEmpty else { return }
        if let backend {
            backend.startDownloads(started)
        } else {
            // Previews have nothing to fetch from.
            for id in started { handle(.finished(videoId: id, bytes: 6_400_000, quality: "Opus 256 kbps")) }
        }
        refreshHold()
    }

    /// Tries the failed ones again.
    public func retryFailed() {
        var again: [String] = []
        for index in items.indices {
            if case .failed = items[index].state {
                items[index].state = .waiting
                again.append(items[index].id)
            }
        }
        guard !again.isEmpty else { return }
        save()
        backend?.startDownloads(again)
        refreshHold()
    }

    // MARK: Removing

    /// Takes songs off the phone (downloaded or not yet) and out of every downloaded album and playlist.
    public func remove(_ videoIds: Set<String>) {
        guard !videoIds.isEmpty else { return }
        items.removeAll { videoIds.contains($0.id) }
        for index in collections.indices {
            collections[index].songIDs.removeAll { videoIds.contains($0) }
        }
        collections.removeAll { $0.songIDs.isEmpty }
        backend?.removeDownloads(Array(videoIds))
        save()
        refreshHold()
    }

    /// Removes a downloaded album or playlist: its songs go too, except ones the listener also downloaded on their
    /// own or that belong to another downloaded album or playlist.
    public func removeCollection(_ collectionID: String) {
        guard let index = collections.firstIndex(where: { $0.id == collectionID }) else { return }
        let gone = collections.remove(at: index)
        let elsewhere = Set(collections.flatMap(\.songIDs))
        let standalone = Set(items.filter(\.isStandalone).map(\.id))
        let orphans = Set(gone.songIDs).subtracting(elsewhere).subtracting(standalone)
        if orphans.isEmpty {
            save()
        } else {
            remove(orphans)
        }
    }

    public func removeAll() {
        let all = Set(items.map(\.id))
        collections = []
        remove(all)
        save()
    }

    // MARK: What the backend reports

    private func handle(_ event: DownloadEvent) {
        switch event {
        case .progress(let videoId, let fraction):
            guard let index = items.firstIndex(where: { $0.id == videoId }), !items[index].isDownloaded else { return }
            items[index].state = .downloading(fraction)
        case .finished(let videoId, let bytes, let quality):
            guard let index = items.firstIndex(where: { $0.id == videoId }) else { return }
            items[index].state = .downloaded
            items[index].bytes = bytes
            items[index].quality = quality ?? items[index].quality
            save()
        case .failed(let videoId, let message):
            guard let index = items.firstIndex(where: { $0.id == videoId }) else { return }
            items[index].state = .failed(message)
            items[index].bytes = 0
            save()
        }
        refreshHold()
    }

    private func refreshHold() {
        let holding = (backend?.isHoldingDownloadsForWiFi ?? false) && pendingCount > 0
        if holding != isWaitingForWiFi { isWaitingForWiFi = holding }
    }

    /// Writes the list a moment from now (events come in bursts).
    private func save() {
        guard let indexURL else { return }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard let self, !Task.isCancelled else { return }
            let index = Index(items: self.items, collections: self.collections)
            guard let data = try? JSONEncoder().encode(index) else { return }
            try? data.write(to: indexURL, options: .atomic)
        }
    }

    /// Writes the list now. For tests, and before the app goes away.
    public func saveNow() {
        guard let indexURL else { return }
        saveTask?.cancel()
        if let data = try? JSONEncoder().encode(Index(items: items, collections: collections)) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }
}
