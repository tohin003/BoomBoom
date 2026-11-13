import BoomDesign
import BoomModels
import BoomPlayer
import Foundation

// The Downloads screen's states and sums, kept free of SwiftUI so they read plainly and can be checked alone.

/// The two ways of looking at downloads.
enum DownloadsView: String, CaseIterable, Hashable, Sendable {
    case songs, collections

    var title: String {
        switch self {
        case .songs: "Songs"
        case .collections: "Albums & playlists"
        }
    }
}

/// The order of the Songs view.
enum DownloadSort: String, CaseIterable, Hashable, Sendable {
    /// Newest first: the store's own order.
    case recent
    case title
    /// Largest first: the one for freeing space.
    case size

    var title: String {
        switch self {
        case .recent: "Recently added"
        case .title: "Title"
        case .size: "Size"
        }
    }

    func sorted(_ items: [DownloadItem]) -> [DownloadItem] {
        switch self {
        case .recent:
            return items
        case .title:
            return items.sorted { $0.song.title.localizedStandardCompare($1.song.title) == .orderedAscending }
        case .size:
            // Songs still to come weigh nothing yet: they go last, newest first among themselves.
            return items.enumerated().sorted { a, b in
                a.element.bytes != b.element.bytes ? a.element.bytes > b.element.bytes : a.offset < b.offset
            }.map(\.element)
        }
    }
}

/// Browsing, or picking rows to delete. The one place select mode lives.
enum DownloadsMode: Equatable {
    case browsing
    /// The ids picked: songs' video ids in the Songs view, collections' ids in the other.
    case selecting(Set<String>)

    var isSelecting: Bool { self != .browsing }

    var selection: Set<String> {
        if case .selecting(let ids) = self { return ids }
        return []
    }

    /// A tap on a row while selecting.
    func toggling(_ id: String) -> DownloadsMode {
        guard case .selecting(var ids) = self else { return self }
        if ids.remove(id) == nil { ids.insert(id) }
        return .selecting(ids)
    }
}

/// What the Downloads screen may be asking. Only one question shows at a time.
enum DownloadsQuestion: Hashable {
    case deleteSelection
    case removeAll
    /// "Remove all" asked a second time: it is the destructive one.
    case removeAllAgain
    case mobileData
}

/// Sums for the screen.
@MainActor
enum DownloadsMath {
    /// Space freed by removing these songs.
    static func freedBytes(songs ids: Set<String>, in store: DownloadStore) -> Int64 {
        store.items.reduce(0) { $0 + (ids.contains($1.id) && $1.isDownloaded ? $1.bytes : 0) }
    }

    /// Space freed by removing these albums and playlists: their songs, less those the listener downloaded on their
    /// own or another downloaded collection still holds (as `DownloadStore.removeCollection` keeps them).
    static func freedBytes(collections ids: Set<String>, in store: DownloadStore) -> Int64 {
        let kept = Set(store.collections.filter { !ids.contains($0.id) }.flatMap(\.songIDs))
            .union(store.items.filter(\.isStandalone).map(\.id))
        let gone = Set(store.collections.filter { ids.contains($0.id) }.flatMap(\.songIDs)).subtracting(kept)
        return freedBytes(songs: gone, in: store)
    }

    /// "40 songs", "1 song".
    static func songs(_ count: Int) -> String {
        count == 1 ? "1 song" : "\(count) songs"
    }

    /// The line under a pending or failed song, or its size and quality once downloaded.
    static func detail(of item: DownloadItem, waitingForWiFi: Bool) -> String {
        switch item.state {
        case .downloaded:
            return [DownloadSize.text(item.bytes), item.quality ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
        case .waiting:
            return waitingForWiFi ? "Waiting for Wi-Fi" : "Waiting to download"
        case .downloading(let fraction):
            return "Downloading \(Int((fraction * 100).rounded()))%"
        case .failed(let message):
            return message.isEmpty ? "Couldn't download" : "Couldn't download: \(message)"
        }
    }
}

/// The looks of an album's or playlist's download button. Decided in one place (`of`) from the collector and the
/// store, so the button and its status line can never disagree.
enum CollectionDownloadLook: Equatable {
    case notDownloaded
    /// Its songs are being gathered (a long playlist comes in parts).
    case collecting(found: Int)
    /// Songs still to come.
    case arriving(done: Int, total: Int)
    case downloaded(total: Int, bytes: Int64)
    /// Nothing left to come, but some songs could not be fetched.
    case partlyFailed(done: Int, total: Int)

    @MainActor
    static func of(_ id: String, store: DownloadStore, collector: DownloadCollector) -> CollectionDownloadLook {
        if let found = collector.collecting[id] { return .collecting(found: found) }
        guard store.collections.contains(where: { $0.id == id }) else { return .notDownloaded }
        let songs = store.songs(of: id)
        var done = 0, pending = 0
        for song in songs {
            switch song.state {
            case .downloaded: done += 1
            case .waiting, .downloading: pending += 1
            case .failed: break
            }
        }
        if pending > 0 { return .arriving(done: done, total: songs.count) }
        if done < songs.count { return .partlyFailed(done: done, total: songs.count) }
        return .downloaded(total: songs.count, bytes: store.bytes(of: id))
    }
}
