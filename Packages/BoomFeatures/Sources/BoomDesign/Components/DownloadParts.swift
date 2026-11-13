import BoomModels
import BoomPlayer
import SwiftUI

// The small pieces every screen uses for downloads: the sign of a song's state, the sizes in words, and the
// collector that loads a whole album or playlist before handing it to `DownloadStore`.

/// Sizes as the listener reads them: "6.4 MB", "1.24 GB".
public enum DownloadSize {
    public static func text(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false
        return formatter.string(fromByteCount: bytes)
    }
}

extension DownloadStore {
    /// An album's or playlist's download as one state: arriving while any song is still to come, failed when some
    /// could not be fetched, downloaded when all are in. Nil when it was not downloaded as a whole.
    public func collectionState(_ id: String) -> DownloadItem.State? {
        let id = DownloadCollector.key(id)
        guard collections.contains(where: { $0.id == id }) else { return nil }
        let songs = songs(of: id)
        guard !songs.isEmpty else { return nil }
        var done = 0, failed = 0
        for song in songs {
            switch song.state {
            case .downloaded: done += 1
            case .failed: failed += 1
            case .waiting, .downloading: break
            }
        }
        if done == songs.count { return .downloaded }
        if done + failed == songs.count { return .failed("\(failed) couldn't be downloaded") }
        return .downloading(Double(done) / Double(songs.count))
    }
}

/// A ring that fills as a download arrives.
public struct DownloadRing: View {
    private let fraction: Double
    private let lineWidth: CGFloat

    public init(fraction: Double, lineWidth: CGFloat = 2) {
        self.fraction = fraction
        self.lineWidth = lineWidth
    }

    public var body: some View {
        ZStack {
            Circle().stroke(BoomColor.textTertiary.opacity(0.6), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0.03, min(1, fraction)))
                .stroke(BoomColor.accent, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.25), value: fraction)
        }
    }
}

/// The small sign of a song's download beside its title: arriving (a ring), waiting, downloaded (a filled mark in
/// the accent) or failed. Nothing when the song was never asked for.
public struct DownloadBadge: View {
    private let state: DownloadItem.State?
    private let size: CGFloat

    public init(_ state: DownloadItem.State?, size: CGFloat = 13) {
        self.state = state
        self.size = size
    }

    public var body: some View {
        if let state {
            Group {
                switch state {
                case .waiting:
                    Image(systemName: "arrow.down.circle.dotted")
                        .font(.system(size: size, weight: .medium))
                        .foregroundStyle(BoomColor.textTertiary)
                case .downloading(let fraction):
                    DownloadRing(fraction: fraction, lineWidth: max(1.5, size / 8))
                        .frame(width: size - 1, height: size - 1)
                case .downloaded:
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: size, weight: .medium))
                        .foregroundStyle(BoomColor.accent)
                case .failed:
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: size, weight: .medium))
                        .foregroundStyle(BoomColor.destructive)
                }
            }
            .frame(width: size + 1, height: size + 1)
            .accessibilityElement()
            .accessibilityLabel(Self.words(for: state))
            .accessibilityIdentifier("download.badge")
        }
    }

    /// What the sign says, for VoiceOver and for lines of text.
    public static func words(for state: DownloadItem.State) -> String {
        switch state {
        case .waiting: "Waiting to download"
        case .downloading(let fraction): "Downloading, \(Int((fraction * 100).rounded())) percent"
        case .downloaded: "Downloaded"
        case .failed: "Couldn't download"
        }
    }
}

/// Loads every song of an album or a playlist (a long playlist comes in parts) and then downloads them as one
/// collection. Shared by the album and playlist pages and by the menus elsewhere, so a page shows "collecting" even
/// when the download was started from a shelf's menu.
@MainActor
@Observable
public final class DownloadCollector {
    public static let shared = DownloadCollector()

    /// The most songs one album or playlist downloads: beyond it, a playlist would fill the phone by surprise.
    public static let songLimit = 1000

    /// A message for the listener about a collection: the cap was hit, or the list could not be loaded.
    public struct Notice: Identifiable, Equatable, Sendable {
        public let id = UUID()
        public var title: String
        public var message: String
    }

    /// Collections whose songs are being gathered, with how many have been found so far.
    public private(set) var collecting: [String: Int] = [:]
    /// Collections cut at `songLimit` in this session.
    public private(set) var capped: Set<String> = []
    /// The newest message; the frame shows it and clears it.
    public var notice: Notice?

    @ObservationIgnored private var tasks: [String: Task<Void, Never>] = [:]
    /// Which run of each collection is the current one: a run cancelled and started again must not clear the new one.
    @ObservationIgnored private var runs: [String: UUID] = [:]

    public init() {}

    public func isCollecting(_ id: String) -> Bool { collecting[Self.key(id)] != nil }

    /// The one form of an album's or playlist's id that downloads are kept under: a playlist page reached through a
    /// link carries "VLPL…" where shelves carry "PL…".
    public static func key(_ id: String) -> String {
        if id.hasPrefix("VL") { return String(id.dropFirst(2)) }
        if id.hasPrefix("MPSP") { return String(id.dropFirst(4)) }
        return id
    }

    /// Downloads a whole album or playlist.
    /// - Parameter seed: What a page already holds (its songs and the token of the next part), so they are not asked
    ///   for again.
    public func download(
        _ kind: DownloadCollection.Kind, id: String, seed: (DownloadCollection, [Song], String?)? = nil,
        api: any MusicAPI, store: DownloadStore
    ) {
        let id = Self.key(id)
        guard tasks[id] == nil else { return }
        let run = UUID()
        runs[id] = run
        collecting[id] = seed?.1.count ?? 0
        tasks[id] = Task { [weak self] in
            do {
                var entry: DownloadCollection
                var songs: [Song]
                var token: String?
                if let seed {
                    (entry, songs, token) = seed
                } else {
                    switch kind {
                    case .album:
                        let page = try await api.album(id: id)
                        entry = Self.entry(for: page)
                        songs = page.tracks
                        token = nil
                    case .playlist:
                        let page = try await api.playlist(id: id)
                        entry = Self.entry(for: page)
                        songs = page.tracks
                        token = page.continuation
                    }
                }
                self?.update(id, run: run, found: songs.count)
                while let next = token, songs.count < Self.songLimit {
                    try Task.checkCancellation()
                    let part = try await api.playlistContinuation(next)
                    songs += part.songs
                    token = part.songs.isEmpty ? nil : part.continuation
                    self?.update(id, run: run, found: songs.count)
                }
                try Task.checkCancellation()
                // A playlist may hold a song twice; the download keeps it once, in its first place.
                var seen = Set<String>()
                let unique = songs.filter { seen.insert($0.videoId).inserted }
                let isCapped = unique.count > Self.songLimit || token != nil
                guard let self, self.runs[id] == run else { return }
                self.finish(id)
                entry.id = id
                guard unique.contains(where: \.isAvailable) else {
                    self.notice = Notice(
                        title: "Nothing to download", message: "None of the songs of “\(entry.title)” can be played.")
                    return
                }
                store.download(Array(unique.prefix(Self.songLimit)), as: entry)
                if isCapped {
                    self.capped.insert(id)
                    self.notice = Notice(
                        title: "Only the first \(Self.songLimit) songs",
                        message: "“\(entry.title)” is longer than \(Self.songLimit) songs. Its first "
                            + "\(Self.songLimit) are being downloaded.")
                }
            } catch {
                guard let self, self.runs[id] == run else { return }
                self.finish(id)
                guard !(error is CancellationError) else { return }
                self.notice = Notice(
                    title: "Couldn't download this \(kind == .album ? "album" : "playlist")",
                    message: Self.message(for: error))
            }
        }
    }

    /// Stops gathering a collection's songs; nothing is downloaded.
    public func cancel(_ id: String) {
        let id = Self.key(id)
        tasks[id]?.cancel()
        finish(id)
    }

    private func update(_ id: String, run: UUID, found: Int) {
        guard runs[id] == run else { return }
        collecting[id] = found
    }

    private func finish(_ id: String) {
        tasks[id] = nil
        runs[id] = nil
        collecting[id] = nil
    }

    public static func entry(for page: AlbumPage) -> DownloadCollection {
        DownloadCollection(
            id: key(page.album.id), kind: .album, title: page.album.title,
            subtitle: page.album.artists.map(\.name).joined(separator: ", "), artwork: page.album.artwork)
    }

    public static func entry(for page: PlaylistPage) -> DownloadCollection {
        DownloadCollection(
            id: key(page.playlist.id), kind: .playlist, title: page.playlist.title,
            subtitle: page.authorName ?? page.playlist.detailText, artwork: page.playlist.artwork)
    }

    private static func message(for error: any Error) -> String {
        switch error as? MusicAPIError {
        case .network: "Couldn't connect. Check your connection and try again."
        case .signedOut: "Sign in to download this."
        case .unauthorized: "Your sign-in has run out. Sign in again to continue."
        case .unreadable, nil: "Couldn't load its songs right now. Try again."
        }
    }
}
