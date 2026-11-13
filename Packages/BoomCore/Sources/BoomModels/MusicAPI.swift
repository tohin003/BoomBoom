import Foundation

/// Everything the screens and the player ask YouTube Music for. `InnerTube.InnerTubeClient` is the real one;
/// screens are built against this protocol so they can run on canned data.
///
/// Every call may throw `MusicAPIError`. Calls marked "signed in" throw `.signedOut` without an account.
public protocol MusicAPI: Sendable {
    // MARK: Search

    /// Suggestions while typing. Fast and cancellable: callers debounce and cancel the task of the previous keystroke.
    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion]
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults
    /// More rows of a filtered search.
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation

    // MARK: Browse

    /// Home. `chip` is one of the pills of an earlier Home page, or nil for the default feed.
    func home(chip: Chip?) async throws -> BrowsePage
    /// Explore: new releases, charts, moods and genres.
    func explore() async throws -> BrowsePage
    /// Charts for a country ("US", "IN"; "ZZ" is global).
    func charts(country: String?) async throws -> BrowsePage
    /// The mood and genre tiles (`Chip` with colours), grouped in shelves titled "Moods & moments", "Genres".
    func moodsAndGenres() async throws -> [ChipGroup]
    /// Any other page of shelves: a "More" button, a mood tile, "New releases".
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage
    /// More shelves of a page that scrolls on (Home).
    func browseContinuation(_ token: String) async throws -> BrowsePage

    func album(id: String) async throws -> AlbumPage
    func artist(id: String) async throws -> ArtistPage
    func playlist(id: String) async throws -> PlaylistPage
    func playlistContinuation(_ token: String) async throws -> SongsContinuation

    // MARK: Playing

    /// The "Up next" queue for a song. With only `videoId` set, YouTube Music returns a radio based on the song.
    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue
    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation
    func lyrics(browseId: String) async throws -> Lyrics
    /// The player's "Related" tab.
    func related(browseId: String) async throws -> [Shelf]

    // MARK: The listener's library (signed in)

    func account() async throws -> Account?
    func library(_ section: LibrarySection) async throws -> BrowsePage
    func likedSongs() async throws -> PlaylistPage
    func history() async throws -> BrowsePage
    func rate(videoId: String, _ status: LikeStatus) async throws
    func ratePlaylist(id: String, _ status: LikeStatus) async throws
    func subscribe(channelId: String, _ subscribed: Bool) async throws
    /// Sends one of an item's `LibraryTokens`.
    func sendLibraryFeedback(token: String) async throws
    /// Returns the new playlist's id.
    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String])
        async throws -> String
    func deletePlaylist(id: String) async throws
    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws
    func addToPlaylist(id: String, videoIds: [String]) async throws
    /// `songs` need their `setVideoId`.
    func removeFromPlaylist(id: String, songs: [Song]) async throws
}

/// A titled row of tiles (moods, genres).
public struct ChipGroup: Codable, Hashable, Sendable, Identifiable {
    public var id: String { title }
    public var title: String
    public var chips: [Chip]

    public init(title: String, chips: [Chip]) {
        self.title = title
        self.chips = chips
    }
}

/// More rows of a list that mixes kinds (filtered search, a library tab).
public struct SongsOrItemsContinuation: Codable, Hashable, Sendable {
    public var items: [MusicItem]
    public var continuation: String?

    public init(items: [MusicItem], continuation: String?) {
        self.items = items
        self.continuation = continuation
    }
}

public enum MusicAPIError: Error, Sendable, Equatable {
    /// No connection, a timeout, or a non-2xx answer. The text is for the log, not for the listener.
    case network(String)
    /// YouTube Music answered, but not in a shape this client reads.
    case unreadable(String)
    /// The call needs an account and nobody is signed in.
    case signedOut
    /// YouTube Music refused (401/403): the sign-in has run out.
    case unauthorized
}
