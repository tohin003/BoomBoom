import Foundation

// The things a listener sees in YouTube Music: songs, albums, artists, playlists. Plain values, safe to pass between
// tasks, stable as the contract between the data client (InnerTube), the player and the screens.

/// A named artist, with a channel to open when YouTube Music links one.
public struct ArtistRef: Codable, Hashable, Sendable {
    public var name: String
    /// The artist's channel (`UC…`), or nil when the name is not a link.
    public var id: String?

    public init(name: String, id: String? = nil) {
        self.name = name
        self.id = id
    }
}

/// A named album, with a page to open when YouTube Music links one.
public struct AlbumRef: Codable, Hashable, Sendable {
    public var name: String
    /// The album's browse id (`MPREb_…`), or nil when the name is not a link.
    public var id: String?

    public init(name: String, id: String? = nil) {
        self.name = name
        self.id = id
    }
}

public enum LikeStatus: String, Codable, Hashable, Sendable {
    case like = "LIKE"
    case dislike = "DISLIKE"
    case indifferent = "INDIFFERENT"
}

/// The tokens YouTube Music gives for "Save to library" and "Remove from library".
public struct LibraryTokens: Codable, Hashable, Sendable {
    public var add: String?
    public var remove: String?
    /// True when the item is in the library now (so `remove` is the token to send).
    public var isInLibrary: Bool

    public init(add: String?, remove: String?, isInLibrary: Bool) {
        self.add = add
        self.remove = remove
        self.isInLibrary = isInLibrary
    }
}

/// Anything that plays: a song (an album's audio), a music video, or a podcast episode.
public struct Song: Codable, Hashable, Sendable, Identifiable {
    public enum Kind: String, Codable, Hashable, Sendable {
        /// An album track with a cover (YouTube Music's "ATV").
        case song
        /// A music video or an upload, with a wide still.
        case video
        case episode
    }

    public var id: String { videoId }
    public var videoId: String
    public var title: String
    public var artists: [ArtistRef]
    public var album: AlbumRef?
    /// Seconds, when the listing says.
    public var duration: TimeInterval?
    public var artwork: Artwork
    public var isExplicit: Bool
    public var kind: Kind
    /// "1.2M plays", "3.4M views": shown where YouTube Music shows it.
    public var playsText: String?
    public var likeStatus: LikeStatus?
    /// This entry's id inside a playlist. Needed to remove or move it there.
    public var setVideoId: String?
    public var libraryTokens: LibraryTokens?
    /// False for greyed-out entries that cannot play.
    public var isAvailable: Bool

    public init(
        videoId: String,
        title: String,
        artists: [ArtistRef] = [],
        album: AlbumRef? = nil,
        duration: TimeInterval? = nil,
        artwork: Artwork = .empty,
        isExplicit: Bool = false,
        kind: Kind = .song,
        playsText: String? = nil,
        likeStatus: LikeStatus? = nil,
        setVideoId: String? = nil,
        libraryTokens: LibraryTokens? = nil,
        isAvailable: Bool = true
    ) {
        self.videoId = videoId
        self.title = title
        self.artists = artists
        self.album = album
        self.duration = duration
        self.artwork = artwork
        self.isExplicit = isExplicit
        self.kind = kind
        self.playsText = playsText
        self.likeStatus = likeStatus
        self.setVideoId = setVideoId
        self.libraryTokens = libraryTokens
        self.isAvailable = isAvailable
    }

    /// "Artist A, Artist B & Artist C".
    public var artistLine: String {
        let names = artists.map(\.name)
        switch names.count {
        case 0: return ""
        case 1: return names[0]
        default: return names.dropLast().joined(separator: ", ") + " & " + names[names.count - 1]
        }
    }
}

public struct AlbumItem: Codable, Hashable, Sendable, Identifiable {
    /// The album's browse id (`MPREb_…`).
    public var id: String
    /// The playlist that plays the album (`OLAK5uy_…`), when the listing has it.
    public var playlistId: String?
    public var title: String
    public var artists: [ArtistRef]
    public var year: String?
    /// "Album", "Single", "EP".
    public var typeText: String?
    public var artwork: Artwork
    public var isExplicit: Bool

    public init(
        id: String,
        playlistId: String? = nil,
        title: String,
        artists: [ArtistRef] = [],
        year: String? = nil,
        typeText: String? = nil,
        artwork: Artwork = .empty,
        isExplicit: Bool = false
    ) {
        self.id = id
        self.playlistId = playlistId
        self.title = title
        self.artists = artists
        self.year = year
        self.typeText = typeText
        self.artwork = artwork
        self.isExplicit = isExplicit
    }
}

public struct ArtistItem: Codable, Hashable, Sendable, Identifiable {
    /// The artist's channel (`UC…`).
    public var id: String
    public var name: String
    public var artwork: Artwork
    /// "12.3M subscribers", "4.5M monthly audience".
    public var detailText: String?

    public init(id: String, name: String, artwork: Artwork = .empty, detailText: String? = nil) {
        self.id = id
        self.name = name
        self.artwork = artwork
        self.detailText = detailText
    }
}

public struct PlaylistItem: Codable, Hashable, Sendable, Identifiable {
    /// The playlist id without the `VL` browse prefix (`PL…`, `RDCLAK5uy_…`, `LM` for liked music).
    public var id: String
    public var title: String
    /// "Playlist • YouTube Music", "Tohin • 42 songs".
    public var detailText: String?
    public var artwork: Artwork

    public init(id: String, title: String, detailText: String? = nil, artwork: Artwork = .empty) {
        self.id = id
        self.title = title
        self.detailText = detailText
        self.artwork = artwork
    }
}

/// One entry in a shelf, a search result or the library.
public enum MusicItem: Codable, Hashable, Sendable, Identifiable {
    case song(Song)
    case album(AlbumItem)
    case artist(ArtistItem)
    case playlist(PlaylistItem)

    public var id: String {
        switch self {
        case .song(let song): "song:\(song.videoId):\(song.setVideoId ?? "")"
        case .album(let album): "album:\(album.id)"
        case .artist(let artist): "artist:\(artist.id)"
        case .playlist(let playlist): "playlist:\(playlist.id)"
        }
    }

    public var title: String {
        switch self {
        case .song(let song): song.title
        case .album(let album): album.title
        case .artist(let artist): artist.name
        case .playlist(let playlist): playlist.title
        }
    }

    public var artwork: Artwork {
        switch self {
        case .song(let song): song.artwork
        case .album(let album): album.artwork
        case .artist(let artist): artist.artwork
        case .playlist(let playlist): playlist.artwork
        }
    }

    /// The second line under the title, the way YouTube Music words it: "Song • Artist", "Album • Artist • 2024".
    public var subtitle: String {
        switch self {
        case .song(let song):
            let kind = song.kind == .video ? "Video" : song.kind == .episode ? "Episode" : "Song"
            return [kind, song.artistLine].filter { !$0.isEmpty }.joined(separator: " • ")
        case .album(let album):
            let artists = album.artists.map(\.name).joined(separator: ", ")
            return [album.typeText ?? "Album", artists, album.year ?? ""].filter { !$0.isEmpty }
                .joined(separator: " • ")
        case .artist(let artist):
            return ["Artist", artist.detailText ?? ""].filter { !$0.isEmpty }.joined(separator: " • ")
        case .playlist(let playlist):
            return playlist.detailText ?? "Playlist"
        }
    }
}
