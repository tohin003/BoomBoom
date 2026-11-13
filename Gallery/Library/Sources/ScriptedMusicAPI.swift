import BoomModels
import FeatureBrowse
import Foundation

/// What the demo's data source does differently from `PreviewMusicAPI`.
enum GalleryMode: String {
    case normal
    /// Library and history are empty.
    case empty
    /// Library and history requests fail.
    case failreads
    /// Creating, editing, deleting and removing fail.
    case failwrites
}

/// `PreviewMusicAPI` with a few states it has no switch for: an empty library, failing reads, failing writes.
struct ScriptedMusicAPI: MusicAPI {
    let base: PreviewMusicAPI
    let mode: GalleryMode

    private func failWrite() async throws {
        try await Task.sleep(for: .milliseconds(400))
        if mode == .failwrites { throw MusicAPIError.network("scripted") }
    }

    func library(_ section: LibrarySection) async throws -> BrowsePage {
        switch mode {
        case .empty:
            try await Task.sleep(for: .milliseconds(300))
            return BrowsePage(title: section.title, shelves: [])
        case .failreads:
            try await Task.sleep(for: .milliseconds(300))
            throw MusicAPIError.network("scripted")
        default:
            return try await base.library(section)
        }
    }

    func history() async throws -> BrowsePage {
        switch mode {
        case .empty:
            try await Task.sleep(for: .milliseconds(300))
            return BrowsePage(title: "History", shelves: [])
        case .failreads:
            try await Task.sleep(for: .milliseconds(300))
            throw MusicAPIError.network("scripted")
        default:
            return try await base.history()
        }
    }

    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String])
        async throws -> String
    {
        try await failWrite()
        return try await base.createPlaylist(title: title, description: description, privacy: privacy, videoIds: videoIds)
    }

    func deletePlaylist(id: String) async throws {
        try await failWrite()
        try await base.deletePlaylist(id: id)
    }

    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws {
        try await failWrite()
        try await base.editPlaylist(id: id, title: title, description: description, privacy: privacy)
    }

    func removeFromPlaylist(id: String, songs: [Song]) async throws {
        try await failWrite()
        try await base.removeFromPlaylist(id: id, songs: songs)
    }

    // MARK: Everything else is the preview's

    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] { try await base.searchSuggestions(input) }
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults {
        try await base.search(query, filter: filter)
    }
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation {
        try await base.searchContinuation(token)
    }
    func home(chip: Chip?) async throws -> BrowsePage { try await base.home(chip: chip) }
    func explore() async throws -> BrowsePage { try await base.explore() }
    func charts(country: String?) async throws -> BrowsePage { try await base.charts(country: country) }
    func moodsAndGenres() async throws -> [ChipGroup] { try await base.moodsAndGenres() }
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage { try await base.browse(endpoint) }
    func browseContinuation(_ token: String) async throws -> BrowsePage { try await base.browseContinuation(token) }
    func album(id: String) async throws -> AlbumPage { try await base.album(id: id) }
    func artist(id: String) async throws -> ArtistPage { try await base.artist(id: id) }
    func playlist(id: String) async throws -> PlaylistPage { try await base.playlist(id: id) }
    func playlistContinuation(_ token: String) async throws -> SongsContinuation {
        try await base.playlistContinuation(token)
    }
    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue { try await base.watchQueue(endpoint) }
    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation {
        try await base.watchQueueContinuation(token)
    }
    func lyrics(browseId: String) async throws -> Lyrics { try await base.lyrics(browseId: browseId) }
    func related(browseId: String) async throws -> [Shelf] { try await base.related(browseId: browseId) }
    func account() async throws -> Account? { try await base.account() }
    func likedSongs() async throws -> PlaylistPage { try await base.likedSongs() }
    func rate(videoId: String, _ status: LikeStatus) async throws { try await base.rate(videoId: videoId, status) }
    func ratePlaylist(id: String, _ status: LikeStatus) async throws { try await base.ratePlaylist(id: id, status) }
    func subscribe(channelId: String, _ subscribed: Bool) async throws {
        try await base.subscribe(channelId: channelId, subscribed)
    }
    func sendLibraryFeedback(token: String) async throws { try await base.sendLibraryFeedback(token: token) }
    func addToPlaylist(id: String, videoIds: [String]) async throws {
        try await base.addToPlaylist(id: id, videoIds: videoIds)
    }
}
