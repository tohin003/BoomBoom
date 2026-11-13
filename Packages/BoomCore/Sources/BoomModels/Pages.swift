import Foundation

// Pages and their parts: what a screen draws after one request.

/// Where a "More" button, a chip or a card leads: a `browse` request.
public struct BrowseEndpoint: Codable, Hashable, Sendable {
    public enum PageType: String, Codable, Hashable, Sendable {
        case album, artist, playlist, userChannel, podcast, generic
    }

    public var browseId: String
    public var params: String?
    public var pageType: PageType

    public init(browseId: String, params: String? = nil, pageType: PageType = .generic) {
        self.browseId = browseId
        self.params = params
        self.pageType = pageType
    }
}

/// What a play button starts: a `next` request for a video, optionally inside a playlist or as a radio.
public struct WatchEndpoint: Codable, Hashable, Sendable {
    public var videoId: String?
    public var playlistId: String?
    public var params: String?
    public var index: Int?

    public init(videoId: String? = nil, playlistId: String? = nil, params: String? = nil, index: Int? = nil) {
        self.videoId = videoId
        self.playlistId = playlistId
        self.params = params
        self.index = index
    }
}

/// A filter pill at the top of Home ("Relax", "Workout") or a mood or genre tile in Explore.
public struct Chip: Codable, Hashable, Sendable, Identifiable {
    public var id: String { title + (endpoint?.params ?? "") }
    public var title: String
    public var endpoint: BrowseEndpoint?
    public var isSelected: Bool
    /// The tile's edge colour for moods and genres, as 0xRRGGBB.
    public var colorHex: UInt32?

    public init(title: String, endpoint: BrowseEndpoint?, isSelected: Bool = false, colorHex: UInt32? = nil) {
        self.title = title
        self.endpoint = endpoint
        self.isSelected = isSelected
        self.colorHex = colorHex
    }
}

public enum SearchFilter: String, Codable, Hashable, Sendable, CaseIterable {
    case songs, videos, albums, artists
    case communityPlaylists, featuredPlaylists
    case podcasts, episodes, profiles

    /// The pill's words in YouTube Music.
    public var title: String {
        switch self {
        case .songs: "Songs"
        case .videos: "Videos"
        case .albums: "Albums"
        case .artists: "Artists"
        case .communityPlaylists: "Community playlists"
        case .featuredPlaylists: "Featured playlists"
        case .podcasts: "Podcasts"
        case .episodes: "Episodes"
        case .profiles: "Profiles"
        }
    }
}

/// A titled group of items: a carousel on Home, a section of search results, an artist's albums.
public struct Shelf: Codable, Hashable, Sendable, Identifiable {
    public enum Layout: String, Codable, Hashable, Sendable {
        /// Side-scrolling cards (covers with two lines of text).
        case carousel
        /// Rows: small cover, title, second line.
        case list
        /// Rows in side-scrolling columns of four ("Quick picks").
        case columns
        /// A two- or three-across grid.
        case grid
        /// The big "Top result" card in search. `items` holds the top item first, then its quick rows.
        case topResult
        /// Numbered rows with a trend arrow (charts).
        case ranked
        /// A block of text (an artist's "About", a description). The text is in `text`.
        case text
    }

    public var id: String
    public var title: String
    /// The small line above the title ("START RADIO FROM A SONG", "SIMILAR TO").
    public var strapline: String?
    /// The round picture next to the header on "Similar to …" shelves.
    public var headerArtwork: Artwork?
    public var layout: Layout
    public var items: [MusicItem]
    /// Where the header's "More" button leads.
    public var more: BrowseEndpoint?
    /// In search: the filter that "Show all" applies.
    public var moreFilter: SearchFilter?
    /// For `layout == .text`.
    public var text: String?
    /// Loads more items of this shelf.
    public var continuation: String?

    public init(
        id: String,
        title: String,
        strapline: String? = nil,
        headerArtwork: Artwork? = nil,
        layout: Layout,
        items: [MusicItem] = [],
        more: BrowseEndpoint? = nil,
        moreFilter: SearchFilter? = nil,
        text: String? = nil,
        continuation: String? = nil
    ) {
        self.id = id
        self.title = title
        self.strapline = strapline
        self.headerArtwork = headerArtwork
        self.layout = layout
        self.items = items
        self.more = more
        self.moreFilter = moreFilter
        self.text = text
        self.continuation = continuation
    }
}

/// A page of shelves: Home, Explore, charts, a mood, a "More" page, a library tab.
public struct BrowsePage: Codable, Hashable, Sendable {
    public var title: String?
    public var chips: [Chip]
    public var shelves: [Shelf]
    /// Loads more shelves (Home scrolls on).
    public var continuation: String?

    public init(title: String? = nil, chips: [Chip] = [], shelves: [Shelf] = [], continuation: String? = nil) {
        self.title = title
        self.chips = chips
        self.shelves = shelves
        self.continuation = continuation
    }
}

/// One line under the search field while typing.
public enum SearchSuggestion: Codable, Hashable, Sendable, Identifiable {
    /// Words to search for. `runs` marks the part the listener has not typed yet in bold.
    case query(SuggestedQuery)
    /// A song, artist, album or playlist to open at once.
    case item(MusicItem)

    public var id: String {
        switch self {
        case .query(let query): "q:\(query.text)"
        case .item(let item): "i:\(item.id)"
        }
    }
}

public struct SuggestedQuery: Codable, Hashable, Sendable {
    public struct Run: Codable, Hashable, Sendable {
        public var text: String
        public var isBold: Bool

        public init(text: String, isBold: Bool) {
            self.text = text
            self.isBold = isBold
        }
    }

    public var text: String
    public var runs: [Run]
    /// True for the listener's own earlier searches (a clock icon instead of a magnifier).
    public var isFromHistory: Bool

    public init(text: String, runs: [Run] = [], isFromHistory: Bool = false) {
        self.text = text
        self.runs = runs.isEmpty ? [Run(text: text, isBold: false)] : runs
        self.isFromHistory = isFromHistory
    }
}

public struct SearchResults: Codable, Hashable, Sendable {
    public var query: String
    /// "Showing results for …" when YouTube Music corrected the words.
    public var correctedQuery: String?
    public var filter: SearchFilter?
    /// Unfiltered: a `.topResult` shelf, then one `.list` shelf per kind. Filtered: one `.list` shelf.
    public var shelves: [Shelf]
    /// Loads more rows of a filtered search.
    public var continuation: String?

    public init(
        query: String,
        correctedQuery: String? = nil,
        filter: SearchFilter? = nil,
        shelves: [Shelf] = [],
        continuation: String? = nil
    ) {
        self.query = query
        self.correctedQuery = correctedQuery
        self.filter = filter
        self.shelves = shelves
        self.continuation = continuation
    }
}

public struct AlbumPage: Codable, Hashable, Sendable {
    public var album: AlbumItem
    public var description: String?
    /// "12 songs • 45 minutes".
    public var statsText: String?
    public var tracks: [Song]
    /// "Other versions", "Releases for you".
    public var shelves: [Shelf]
    public var likeStatus: LikeStatus?
    public var libraryTokens: LibraryTokens?

    public init(
        album: AlbumItem,
        description: String? = nil,
        statsText: String? = nil,
        tracks: [Song] = [],
        shelves: [Shelf] = [],
        likeStatus: LikeStatus? = nil,
        libraryTokens: LibraryTokens? = nil
    ) {
        self.album = album
        self.description = description
        self.statsText = statsText
        self.tracks = tracks
        self.shelves = shelves
        self.likeStatus = likeStatus
        self.libraryTokens = libraryTokens
    }
}

public struct ArtistPage: Codable, Hashable, Sendable {
    public var artist: ArtistItem
    /// The wide header picture.
    public var banner: Artwork
    public var description: String?
    public var subscribersText: String?
    public var monthlyListenersText: String?
    public var isSubscribed: Bool
    /// The "Shuffle" and "Radio" buttons.
    public var shuffle: WatchEndpoint?
    public var radio: WatchEndpoint?
    /// "Top songs" (`.list`), "Albums", "Singles & EPs", "Videos", "Featured on", "Fans might also like".
    public var shelves: [Shelf]

    public init(
        artist: ArtistItem,
        banner: Artwork = .empty,
        description: String? = nil,
        subscribersText: String? = nil,
        monthlyListenersText: String? = nil,
        isSubscribed: Bool = false,
        shuffle: WatchEndpoint? = nil,
        radio: WatchEndpoint? = nil,
        shelves: [Shelf] = []
    ) {
        self.artist = artist
        self.banner = banner
        self.description = description
        self.subscribersText = subscribersText
        self.monthlyListenersText = monthlyListenersText
        self.isSubscribed = isSubscribed
        self.shuffle = shuffle
        self.radio = radio
        self.shelves = shelves
    }
}

public struct PlaylistPage: Codable, Hashable, Sendable {
    public var playlist: PlaylistItem
    public var description: String?
    public var authorName: String?
    public var authorArtwork: Artwork?
    /// "50 songs • 3 hours, 2 minutes".
    public var statsText: String?
    public var tracks: [Song]
    /// Loads more tracks.
    public var continuation: String?
    /// True for the listener's own playlists (rename, reorder, remove).
    public var isEditable: Bool
    public var likeStatus: LikeStatus?
    /// "Related playlists", suggestions.
    public var shelves: [Shelf]

    public init(
        playlist: PlaylistItem,
        description: String? = nil,
        authorName: String? = nil,
        authorArtwork: Artwork? = nil,
        statsText: String? = nil,
        tracks: [Song] = [],
        continuation: String? = nil,
        isEditable: Bool = false,
        likeStatus: LikeStatus? = nil,
        shelves: [Shelf] = []
    ) {
        self.playlist = playlist
        self.description = description
        self.authorName = authorName
        self.authorArtwork = authorArtwork
        self.statsText = statsText
        self.tracks = tracks
        self.continuation = continuation
        self.isEditable = isEditable
        self.likeStatus = likeStatus
        self.shelves = shelves
    }
}

/// More rows for a list that scrolls on (playlist tracks, filtered search, a queue).
public struct SongsContinuation: Codable, Hashable, Sendable {
    public var songs: [Song]
    public var continuation: String?

    public init(songs: [Song], continuation: String?) {
        self.songs = songs
        self.continuation = continuation
    }
}

/// The "Up next" queue YouTube Music builds for a song: the rest of the playlist, or a radio.
public struct WatchQueue: Codable, Hashable, Sendable {
    public var songs: [Song]
    /// The position of the requested song in `songs`.
    public var currentIndex: Int
    /// The playlist behind the queue (`RDAMVM…` for a radio).
    public var playlistId: String?
    /// Loads more of the queue.
    public var continuation: String?
    /// For the player's "Lyrics" tab. Nil when the song has none.
    public var lyricsBrowseId: String?
    /// For the player's "Related" tab.
    public var relatedBrowseId: String?

    public init(
        songs: [Song] = [],
        currentIndex: Int = 0,
        playlistId: String? = nil,
        continuation: String? = nil,
        lyricsBrowseId: String? = nil,
        relatedBrowseId: String? = nil
    ) {
        self.songs = songs
        self.currentIndex = currentIndex
        self.playlistId = playlistId
        self.continuation = continuation
        self.lyricsBrowseId = lyricsBrowseId
        self.relatedBrowseId = relatedBrowseId
    }
}

public struct Lyrics: Codable, Hashable, Sendable {
    public struct Line: Codable, Hashable, Sendable {
        public var text: String
        /// Seconds from the start, when the lyrics are timed.
        public var start: TimeInterval?

        public init(text: String, start: TimeInterval? = nil) {
            self.text = text
            self.start = start
        }
    }

    public var lines: [Line]
    /// "Source: Musixmatch".
    public var source: String?
    public var isTimed: Bool { lines.contains { $0.start != nil } }

    public init(lines: [Line], source: String? = nil) {
        self.lines = lines
        self.source = source
    }
}

/// The signed-in listener.
public struct Account: Codable, Hashable, Sendable {
    public var name: String
    public var handle: String?
    public var artwork: Artwork
    /// True with YouTube Music Premium (256 kbps audio).
    public var isPremium: Bool

    public init(name: String, handle: String? = nil, artwork: Artwork = .empty, isPremium: Bool = false) {
        self.name = name
        self.handle = handle
        self.artwork = artwork
        self.isPremium = isPremium
    }
}

public enum PlaylistPrivacy: String, Codable, Hashable, Sendable, CaseIterable {
    case `public` = "PUBLIC"
    case unlisted = "UNLISTED"
    case `private` = "PRIVATE"
}

/// The lists under Library.
public enum LibrarySection: String, Codable, Hashable, Sendable, CaseIterable {
    case playlists, songs, albums, artists, subscriptions

    public var title: String {
        switch self {
        case .playlists: "Playlists"
        case .songs: "Songs"
        case .albums: "Albums"
        case .artists: "Artists"
        case .subscriptions: "Subscriptions"
        }
    }
}
