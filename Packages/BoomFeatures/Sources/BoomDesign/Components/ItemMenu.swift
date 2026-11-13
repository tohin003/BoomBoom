import BoomModels
import BoomPlayer
import SwiftUI

/// The entries of an item's menu: Play next, Add to queue, Start radio, Save to playlist, Go to album, Go to artist,
/// Share, and Like for songs. Used by `ItemMoreButton` (the ellipsis) and by `.itemContextMenu(_:)` (long press).
public struct ItemMenu: View {
    private let item: MusicItem
    private let likeOverride: Binding<LikeStatus?>?

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator

    /// - Parameter likeOverride: Where a row keeps a rating made from this menu, so the next opening shows it.
    public init(_ item: MusicItem, likeOverride: Binding<LikeStatus?>? = nil) {
        self.item = item
        self.likeOverride = likeOverride
    }

    private var actions: ItemActions { ItemActions(services: services, navigator: navigator) }

    public var body: some View {
        Section {
            Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                actions.enqueue(item, next: true)
            }
            Button("Add to queue", systemImage: "text.line.last.and.arrowtriangle.forward") {
                actions.enqueue(item, next: false)
            }
            Button("Start radio", systemImage: "dot.radiowaves.left.and.right") {
                actions.startRadio(item)
            }
        }
        Section {
            if item.song != nil || isAlbumOrPlaylist {
                Button("Save to playlist", systemImage: "text.badge.plus") {
                    let item = item
                    let actions = actions
                    let navigator = navigator
                    Task {
                        let songs = await actions.tracks(of: item)
                        if !songs.isEmpty { navigator.addToPlaylist(songs) }
                    }
                }
            }
            if let song = item.song {
                likeButton(song)
            }
            downloadButtons
        }
        Section {
            if let album = albumRef, let id = album.id {
                Button("Go to album", systemImage: "square.stack") {
                    navigator.open(BrowseEndpoint(browseId: id, pageType: .album))
                }
            }
            let artists = linkedArtists
            if artists.count == 1, let artist = artists.first, let id = artist.id {
                Button("Go to artist", systemImage: "person") {
                    navigator.open(BrowseEndpoint(browseId: id, pageType: .artist))
                }
            } else if artists.count > 1 {
                Menu("Go to artist", systemImage: "person") {
                    ForEach(artists, id: \.self) { artist in
                        Button(artist.name) {
                            if let id = artist.id { navigator.open(BrowseEndpoint(browseId: id, pageType: .artist)) }
                        }
                    }
                }
            }
            if let url = item.shareURL {
                ShareLink(item: url, subject: Text(item.title)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            }
        }
    }

    private var isAlbumOrPlaylist: Bool {
        switch item {
        case .album, .playlist: true
        case .song, .artist: false
        }
    }

    /// "Download", or "Remove download" once the song (or the album or playlist) is kept or on its way.
    @ViewBuilder
    private var downloadButtons: some View {
        let downloads = services.downloads
        switch item {
        case .song(let song) where song.isAvailable:
            switch downloads.state(of: song.videoId) {
            case nil:
                Button("Download", systemImage: "arrow.down.circle") { downloads.download(song) }
            case .failed:
                Button("Try the download again", systemImage: "arrow.clockwise") { downloads.download(song) }
                Button("Remove download", systemImage: "trash", role: .destructive) {
                    downloads.remove([song.videoId])
                }
            case .waiting, .downloading, .downloaded:
                Button("Remove download", systemImage: "trash", role: .destructive) {
                    downloads.remove([song.videoId])
                }
            }
        case .album(let album):
            collectionButtons(.album, id: album.id)
        case .playlist(let playlist):
            collectionButtons(.playlist, id: playlist.id)
        case .song, .artist:
            EmptyView()
        }
    }

    @ViewBuilder
    private func collectionButtons(_ kind: DownloadCollection.Kind, id: String) -> some View {
        let downloads = services.downloads
        let collector = DownloadCollector.shared
        let id = DownloadCollector.key(id)
        if collector.isCollecting(id) {
            Button("Stop downloading", systemImage: "xmark.circle") { collector.cancel(id) }
        } else if downloads.collections.contains(where: { $0.id == id }) {
            Button("Remove download", systemImage: "trash", role: .destructive) {
                downloads.removeCollection(id)
            }
        } else {
            Button("Download", systemImage: "arrow.down.circle") {
                collector.download(kind, id: id, api: services.api, store: downloads)
            }
        }
    }

    private var albumRef: AlbumRef? {
        item.song?.album
    }

    /// The artists that have a page to open.
    private var linkedArtists: [ArtistRef] {
        switch item {
        case .song(let song): song.artists.filter { $0.id != nil }
        case .album(let album): album.artists.filter { $0.id != nil }
        case .artist, .playlist: []
        }
    }

    @ViewBuilder
    private func likeButton(_ song: Song) -> some View {
        let isCurrent = services.player.current?.videoId == song.videoId
        let status = isCurrent ? services.player.likeStatus : (likeOverride?.wrappedValue ?? song.likeStatus)
        let liked = status == .like
        Button(liked ? "Remove like" : "Like", systemImage: liked ? "hand.thumbsup.fill" : "hand.thumbsup") {
            // A rating needs an account: signed out, it would only flip and silently flip back.
            guard services.session.isSignedIn else { return navigator.signIn() }
            let newStatus: LikeStatus = liked ? .indifferent : .like
            likeOverride?.wrappedValue = newStatus
            if isCurrent {
                services.player.setLike(newStatus)
            } else {
                let api = services.api
                let override = likeOverride
                let request = RatingRequests.begin(song.videoId)
                Task {
                    do {
                        try await api.rate(videoId: song.videoId, newStatus)
                    } catch {
                        // A newer rating of the song decides what shows; only the newest refusal puts this one back.
                        if RatingRequests.isNewest(request, for: song.videoId) { override?.wrappedValue = status }
                    }
                    RatingRequests.end(request, for: song.videoId)
                }
            }
        }
    }
}

/// The newest rating sent from a menu for each song that has one on its way.
@MainActor
private enum RatingRequests {
    private static var newest: [String: Int] = [:]
    private static var count = 0

    static func begin(_ videoId: String) -> Int {
        count += 1
        newest[videoId] = count
        return count
    }

    static func isNewest(_ request: Int, for videoId: String) -> Bool {
        newest[videoId] == request
    }

    static func end(_ request: Int, for videoId: String) {
        if newest[videoId] == request { newest[videoId] = nil }
    }
}

/// The trailing ellipsis of a row or a card: opens `ItemMenu`.
public struct ItemMoreButton: View {
    private let item: MusicItem
    private let likeOverride: Binding<LikeStatus?>?

    public init(_ item: MusicItem, likeOverride: Binding<LikeStatus?>? = nil) {
        self.item = item
        self.likeOverride = likeOverride
    }

    public var body: some View {
        Menu {
            ItemMenu(item, likeOverride: likeOverride)
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .rotationEffect(.degrees(90))
                .foregroundStyle(BoomColor.textSecondary)
                .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("More actions for \(item.title)")
    }
}

extension View {
    /// Long press shows `ItemMenu` for the item.
    public func itemContextMenu(_ item: MusicItem, likeOverride: Binding<LikeStatus?>? = nil) -> some View {
        contextMenu {
            ItemMenu(item, likeOverride: likeOverride)
        }
    }
}
