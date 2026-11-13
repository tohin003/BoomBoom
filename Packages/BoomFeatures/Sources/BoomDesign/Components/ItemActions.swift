import BoomModels
import BoomPlayer
import Foundation

/// How a tapped song plays, so that taps behave the same on every screen.
public enum PlayContext: Equatable, Sendable {
    /// A lone song (search, Home): it plays with a radio of similar songs.
    case single
    /// A song inside a tracklist (an album, a playlist, "Top songs"): the list plays from that song.
    case list([Song], source: QueueSource?)
}

/// What a tap, a Play button or a menu entry does with an item. Build one from the environment in a view:
/// `ItemActions(services: services, navigator: navigator)`.
@MainActor
public struct ItemActions {
    public let services: AppServices
    public let navigator: BoomNavigator

    public init(services: AppServices, navigator: BoomNavigator) {
        self.services = services
        self.navigator = navigator
    }

    private var player: PlayerStore { services.player }
    private var api: any MusicAPI { services.api }

    /// A tap on a row or a card: songs play (from the list, or with their radio); everything else opens.
    public func tap(_ item: MusicItem, context: PlayContext = .single) {
        switch item {
        case .song(let song):
            guard song.isAvailable else { return }
            switch context {
            case .single:
                player.play(song)
            case .list(let songs, let source):
                let index = songs.firstIndex { $0.videoId == song.videoId && $0.setVideoId == song.setVideoId }
                    ?? songs.firstIndex { $0.videoId == song.videoId }
                if let index {
                    player.play(songs: songs, startAt: index, source: source)
                } else {
                    player.play(song)
                }
            }
        case .album, .artist, .playlist:
            if let endpoint = BrowseEndpoint(item) { navigator.open(endpoint) }
        }
    }

    /// The big Play button of a card or a top result: the song, the album, the playlist, or the artist's shuffle.
    public func play(_ item: MusicItem, shuffled: Bool = false) {
        switch item {
        case .song(let song):
            player.play(song)
        case .album(let album):
            Task {
                guard let page = try? await api.album(id: album.id) else { return }
                playList(page.tracks, shuffled: shuffled, source: Self.source(for: page.album))
            }
        case .playlist(let playlist):
            Task {
                guard let page = try? await api.playlist(id: playlist.id) else { return }
                playList(page.tracks, shuffled: shuffled, source: Self.source(for: page.playlist))
            }
        case .artist(let artist):
            Task {
                guard let page = try? await api.artist(id: artist.id) else { return }
                let source = QueueSource(
                    kind: .artist, label: "Artist", title: artist.name,
                    endpoint: BrowseEndpoint(browseId: artist.id, pageType: .artist))
                if let endpoint = shuffled ? (page.shuffle ?? page.radio) : (page.radio ?? page.shuffle) {
                    player.play(endpoint, source: source)
                } else if let top = page.shelves.first(where: { $0.layout == .list }) {
                    playList(top.items.compactMap(\.song), shuffled: shuffled, source: source)
                }
            }
        }
    }

    /// Plays a tracklist from the start, or shuffled.
    public func playList(_ songs: [Song], shuffled: Bool, source: QueueSource?) {
        let playable = songs.filter(\.isAvailable)
        guard !playable.isEmpty else { return }
        player.play(songs: shuffled ? playable.shuffled() : playable, startAt: 0, source: source)
    }

    /// "Start radio": songs like this one, or like this album, playlist or artist.
    public func startRadio(_ item: MusicItem) {
        let source = QueueSource(kind: .radio, label: "Radio", title: item.title)
        switch item {
        case .song(let song):
            player.startRadio(from: song)
        case .album(let album):
            if let playlistId = album.playlistId {
                player.play(WatchEndpoint(playlistId: "RDAMPL" + playlistId), source: source)
            } else {
                Task {
                    guard let page = try? await api.album(id: album.id), let first = page.tracks.first else { return }
                    player.startRadio(from: first)
                }
            }
        case .playlist(let playlist):
            player.play(WatchEndpoint(playlistId: "RDAMPL" + playlist.id), source: source)
        case .artist(let artist):
            Task {
                guard let page = try? await api.artist(id: artist.id) else { return }
                if let radio = page.radio ?? page.shuffle { player.play(radio, source: source) }
            }
        }
    }

    /// "Play next" and "Add to queue": a song at once, an album's or playlist's tracks once loaded.
    public func enqueue(_ item: MusicItem, next: Bool) {
        Task {
            let songs = await tracks(of: item)
            guard !songs.isEmpty else { return }
            if next { player.playNext(songs) } else { player.addToQueue(songs) }
        }
    }

    /// The songs an item stands for: itself, or the tracks of its album or playlist.
    public func tracks(of item: MusicItem) async -> [Song] {
        switch item {
        case .song(let song): return [song]
        case .album(let album): return (try? await api.album(id: album.id).tracks) ?? []
        case .playlist(let playlist): return (try? await api.playlist(id: playlist.id).tracks) ?? []
        case .artist(let artist):
            let page = try? await api.artist(id: artist.id)
            return page?.shelves.first { $0.layout == .list }?.items.compactMap(\.song) ?? []
        }
    }

    public static func source(for album: AlbumItem) -> QueueSource {
        QueueSource(
            kind: .album, label: "Album", title: album.title,
            endpoint: BrowseEndpoint(browseId: album.id, pageType: .album))
    }

    public static func source(for playlist: PlaylistItem) -> QueueSource {
        QueueSource(
            kind: .playlist, label: "Playlist", title: playlist.title,
            endpoint: BrowseEndpoint(browseId: playlist.id, pageType: .playlist))
    }
}

extension MusicItem {
    /// The song inside, or nil.
    public var song: Song? {
        if case .song(let song) = self { return song }
        return nil
    }

    /// True for artists, whose pictures are round.
    public var isArtist: Bool {
        if case .artist = self { return true }
        return false
    }

    /// True for music videos, whose pictures are wide.
    public var isVideo: Bool {
        if case .song(let song) = self { return song.kind == .video }
        return false
    }

    public var isExplicit: Bool {
        switch self {
        case .song(let song): song.isExplicit
        case .album(let album): album.isExplicit
        case .artist, .playlist: false
        }
    }

    /// The page to share: a song's watch page, or the album's, playlist's or artist's.
    public var shareURL: URL? {
        switch self {
        case .song(let song): URL(string: "https://music.youtube.com/watch?v=\(song.videoId)")
        case .album(let album): URL(string: "https://music.youtube.com/browse/\(album.id)")
        case .playlist(let playlist): URL(string: "https://music.youtube.com/playlist?list=\(playlist.id)")
        case .artist(let artist): URL(string: "https://music.youtube.com/channel/\(artist.id)")
        }
    }
}
