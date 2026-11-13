import BoomModels
import Foundation

/// The YouTube Music data client: calls the web app's own API (`WEB_REMIX`) and reads the answers into `BoomModels`.
///
/// Each call asks `sessionProvider` for the current session (client version, visitor id, cookies), so the listener's
/// sign-in applies as soon as the hidden browser tab has it. Every call honours task cancellation.
public final class InnerTubeClient: MusicAPI {
    let sessionProvider: any InnerTubeSessionProviding
    let urlSession: URLSession
    /// Artist pages subscribe through a channel id that can differ from the page's browse id.
    let channels = ChannelDirectory()

    public init(sessionProvider: any InnerTubeSessionProviding, urlSession: URLSession = .shared) {
        self.sessionProvider = sessionProvider
        self.urlSession = urlSession
    }

    // MARK: Search

    public func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] {
        guard !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        let response = try await send("music/get_search_suggestions", ["input": .string(input)], retries: 0, timeout: 8)
        return Parse.searchSuggestions(response)
    }

    public func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults {
        var body: JSON = ["query": .string(query)]
        if let filter { body.set("params", .string(Parse.searchParams(for: filter))) }
        let response = try await send("search", body)
        guard Parse.searchSectionList(response).exists || response["contents"].exists else {
            throw MusicAPIError.unreadable("search: no section list")
        }
        return Parse.searchResults(response, query: query, filter: filter)
    }

    public func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation {
        let response = try await send("search", ["continuation": .string(token)])
        guard let page = Parse.searchContinuation(response) else {
            throw MusicAPIError.unreadable("search continuation: no items")
        }
        return page
    }

    // MARK: Browse

    public func home(chip: Chip?) async throws -> BrowsePage {
        let endpoint = chip?.endpoint ?? BrowseEndpoint(browseId: "FEmusic_home")
        return try await browsePage(endpoint, what: "home")
    }

    public func explore() async throws -> BrowsePage {
        try await browsePage(BrowseEndpoint(browseId: "FEmusic_explore"), what: "explore")
    }

    public func charts(country: String?) async throws -> BrowsePage {
        var body: JSON = ["browseId": "FEmusic_charts"]
        if let country, !country.isEmpty { body.set("formData", ["selectedValues": [.string(country)]]) }
        let response = try await send("browse", body)
        let page = Parse.browsePage(response)
        guard !page.shelves.isEmpty else { throw MusicAPIError.unreadable("charts: no shelves") }
        return page
    }

    public func moodsAndGenres() async throws -> [ChipGroup] {
        let response = try await send("browse", ["browseId": "FEmusic_moods_and_genres"])
        let groups = Parse.chipGroups(response)
        guard !groups.isEmpty else { throw MusicAPIError.unreadable("moods and genres: no tiles") }
        return groups
    }

    /// Endpoints carry YouTube Music's own browse ids (`VL…`, `MPREb_…`, `UC…`). A playlist or artist endpoint built
    /// from an item's id (a bare `PL…`, a library artist's `MPLAUC…`) is put into that form first.
    public func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage {
        var endpoint = endpoint
        switch endpoint.pageType {
        case .playlist: endpoint.browseId = Parse.playlistBrowseId(endpoint.browseId)
        case .artist: endpoint.browseId = Parse.artistBrowseId(endpoint.browseId)
        default: break
        }
        return try await browsePage(endpoint, what: endpoint.browseId)
    }

    public func browseContinuation(_ token: String) async throws -> BrowsePage {
        let response = try await send("browse", ["continuation": .string(token)])
        guard let page = Parse.browseContinuation(response) else {
            throw MusicAPIError.unreadable("browse continuation: no shelves")
        }
        return page
    }

    public func album(id: String) async throws -> AlbumPage {
        let response = try await send("browse", ["browseId": .string(id)])
        guard let page = Parse.albumPage(response, browseId: id) else {
            throw MusicAPIError.unreadable("album \(id): no header")
        }
        return page
    }

    /// `id` is the artist's channel (`UC…`); a library artist's `MPLAUC…` is accepted too.
    public func artist(id: String) async throws -> ArtistPage {
        let browseId = Parse.artistBrowseId(id)
        let response = try await send("browse", ["browseId": .string(browseId)])
        guard let parsed = Parse.artistPage(response, browseId: browseId) else {
            throw MusicAPIError.unreadable("artist \(id): no header")
        }
        if let channelId = parsed.subscribeChannelId { await channels.remember(channelId, for: browseId) }
        return parsed.page
    }

    /// `id` is a playlist's browse id (`VLPL…`, as endpoints carry it) or its bare id (`PL…`, as items carry it).
    public func playlist(id: String) async throws -> PlaylistPage {
        let browseId = Parse.playlistBrowseId(id)
        let playlistId = Parse.playlistId(fromBrowseId: browseId)
        var response = try await send("browse", ["browseId": .string(browseId)])
        var parsed = Parse.playlistPage(response, playlistId: playlistId)
        if parsed == nil, playlistId.hasPrefix("PL") {
            // A podcast answers its `VL…` id with episodes but no header; its show page (`MPSP…`) has both.
            response = try await send("browse", ["browseId": .string("MPSP" + playlistId)], retries: 0)
            parsed = Parse.playlistPage(response, playlistId: playlistId)
        }
        guard var page = parsed else { throw MusicAPIError.unreadable("playlist \(id): no header") }
        // Related playlists or suggestions come as a continuation of the page's sections. Best effort.
        if page.shelves.isEmpty, let token = Parse.sectionContinuation(response) {
            if let more = try? await send("browse", ["continuation": .string(token)], retries: 0),
                let extra = Parse.browseContinuation(more)
            {
                page.shelves = extra.shelves
            }
            try Task.checkCancellation()
        }
        return page
    }

    public func playlistContinuation(_ token: String) async throws -> SongsContinuation {
        let response = try await send("browse", ["continuation": .string(token)])
        guard let page = Parse.songsContinuation(response) else {
            throw MusicAPIError.unreadable("playlist continuation: no rows")
        }
        return page
    }

    // MARK: Playing

    static let watchFlags: [(String, JSON)] = [
        ("enablePersistentPlaylistPanel", true),
        ("isAudioOnly", true),
        ("tunerSettingValue", "AUTOMIX_SETTING_NORMAL"),
    ]

    /// The `next` body for an endpoint. With only a video, YouTube Music's radio for it (ytmusicapi's
    /// `get_watch_playlist(videoId, radio=True)`).
    static func watchBody(_ endpoint: WatchEndpoint) -> JSON {
        var body: JSON = .object([:])
        for (key, value) in watchFlags { body.set(key, value) }
        if let videoId = endpoint.videoId { body.set("videoId", .string(videoId)) }
        if let playlistId = endpoint.playlistId {
            body.set("playlistId", .string(Parse.playlistId(fromBrowseId: playlistId)))
            if let params = endpoint.params { body.set("params", .string(params)) }
            if endpoint.params == nil, endpoint.videoId != nil {
                body.set("watchEndpointMusicSupportedConfigs", [
                    "watchEndpointMusicConfig": [
                        "hasPersistentPlaylistPanel": true,
                        "musicVideoType": "MUSIC_VIDEO_TYPE_ATV",
                    ],
                ])
            }
        } else if let videoId = endpoint.videoId {
            body.set("playlistId", .string("RDAMVM" + videoId))
            body.set("params", .string(endpoint.params ?? "wAEB"))
        }
        if let index = endpoint.index { body.set("index", .number(Double(index))) }
        return body
    }

    public func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue {
        guard endpoint.videoId != nil || endpoint.playlistId != nil else {
            throw MusicAPIError.unreadable("watch queue: no video or playlist")
        }
        let response = try await send("next", Self.watchBody(endpoint))
        guard let queue = Parse.watchQueue(response, requested: endpoint) else {
            throw MusicAPIError.unreadable("watch queue: no queue")
        }
        return queue
    }

    public func watchQueueContinuation(_ token: String) async throws -> SongsContinuation {
        var body: JSON = ["continuation": .string(token)]
        for (key, value) in Self.watchFlags { body.set(key, value) }
        let response = try await send("next", body)
        guard let page = Parse.songsContinuation(response) else {
            throw MusicAPIError.unreadable("watch queue continuation: no rows")
        }
        return page
    }

    public func lyrics(browseId: String) async throws -> Lyrics {
        let response = try await send("browse", ["browseId": .string(browseId)])
        guard let lyrics = Parse.lyrics(response) else { throw MusicAPIError.unreadable("lyrics: no text") }
        return lyrics
    }

    public func related(browseId: String) async throws -> [Shelf] {
        let response = try await send("browse", ["browseId": .string(browseId)])
        let shelves = Parse.shelves(Parse.sectionList(of: response)["contents"].array)
        guard !shelves.isEmpty else { throw MusicAPIError.unreadable("related: no shelves") }
        return shelves
    }

    // MARK: The listener's library

    /// Nil when nobody is signed in (no request is sent).
    public func account() async throws -> Account? {
        let session = try await currentSession()
        guard session.isSignedIn else { return nil }
        let response = try await send("account/account_menu", .object([:]), signedIn: true)
        guard let account = Parse.account(response) else { throw MusicAPIError.unreadable("account: no header") }
        return account
    }

    static func libraryBrowseId(_ section: LibrarySection) -> String {
        switch section {
        case .playlists: "FEmusic_liked_playlists"
        case .songs: "FEmusic_liked_videos"
        case .albums: "FEmusic_liked_albums"
        case .artists: "FEmusic_library_corpus_track_artists"
        case .subscriptions: "FEmusic_library_corpus_artists"
        }
    }

    public func library(_ section: LibrarySection) async throws -> BrowsePage {
        let response = try await send("browse", ["browseId": .string(Self.libraryBrowseId(section))], signedIn: true)
        var page = Parse.browsePage(response)
        if page.title == nil { page.title = section.title }
        // An empty library is a valid answer: it still has its section list.
        guard Parse.sectionList(of: response).exists || !page.shelves.isEmpty else {
            throw MusicAPIError.unreadable("library \(section.rawValue): no section list")
        }
        return page
    }

    public func likedSongs() async throws -> PlaylistPage {
        try await requireSignIn()
        return try await playlist(id: "LM")
    }

    public func history() async throws -> BrowsePage {
        let response = try await send("browse", ["browseId": "FEmusic_history"], signedIn: true)
        guard Parse.sectionList(of: response).exists else { throw MusicAPIError.unreadable("history: no list") }
        return Parse.browsePage(response)
    }

    static func likeEndpoint(_ status: LikeStatus) -> String {
        switch status {
        case .like: "like/like"
        case .dislike: "like/dislike"
        case .indifferent: "like/removelike"
        }
    }

    public func rate(videoId: String, _ status: LikeStatus) async throws {
        _ = try await send(Self.likeEndpoint(status), ["target": ["videoId": .string(videoId)]], signedIn: true)
    }

    public func ratePlaylist(id: String, _ status: LikeStatus) async throws {
        let playlistId = Parse.playlistId(fromBrowseId: id)
        _ = try await send(Self.likeEndpoint(status), ["target": ["playlistId": .string(playlistId)]], signedIn: true)
    }

    public func subscribe(channelId: String, _ subscribed: Bool) async throws {
        try await requireSignIn()
        // The button acts on the channel the artist page names, which can differ from the page's own id: read the
        // page once if it has not been opened yet.
        let artistId = Parse.artistBrowseId(channelId)
        var target = await channels.subscribeId(for: artistId)
        if target == nil, artistId.hasPrefix("UC") {
            do {
                _ = try await artist(id: artistId)
                target = await channels.subscribeId(for: artistId)
            } catch let error as MusicAPIError where error != .unauthorized {
                // An unreadable or unreachable page: subscribe with the id given.
            }
        }
        let channel = target ?? artistId
        let endpoint = subscribed ? "subscription/subscribe" : "subscription/unsubscribe"
        _ = try await send(endpoint, ["channelIds": [.string(channel)]], signedIn: true)
    }

    public func sendLibraryFeedback(token: String) async throws {
        let response = try await send("feedback", ["feedbackTokens": [.string(token)]], signedIn: true)
        if response["feedbackResponses", 0, "isProcessed"].bool == false {
            throw MusicAPIError.unreadable("feedback was not processed")
        }
    }

    public func createPlaylist(
        title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String]
    ) async throws -> String {
        var body: JSON = [
            "title": .string(Self.playlistText(title)),
            "description": .string(Self.playlistText(description)),
            "privacyStatus": .string(privacy.rawValue),
        ]
        if !videoIds.isEmpty { body.set("videoIds", .array(videoIds.map(JSON.string))) }
        let response = try await send("playlist/create", body, signedIn: true)
        guard let playlistId = response["playlistId"].text else {
            throw MusicAPIError.unreadable("create playlist: no id")
        }
        return playlistId
    }

    /// YouTube Music refuses playlist names and descriptions containing angle brackets; they are left out.
    static func playlistText(_ text: String) -> String {
        text.replacingOccurrences(of: "<", with: "").replacingOccurrences(of: ">", with: "")
    }

    public func deletePlaylist(id: String) async throws {
        _ = try await send(
            "playlist/delete", ["playlistId": .string(Parse.playlistId(fromBrowseId: id))], signedIn: true
        )
    }

    public func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?)
        async throws
    {
        try await requireSignIn()
        var actions: [JSON] = []
        if let title = title.map(Self.playlistText), !title.isEmpty {
            actions.append(["action": "ACTION_SET_PLAYLIST_NAME", "playlistName": .string(title)])
        }
        if let description = description.map(Self.playlistText) {
            actions.append(["action": "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription": .string(description)])
        }
        if let privacy {
            actions.append(["action": "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy": .string(privacy.rawValue)])
        }
        guard !actions.isEmpty else { return }
        try await editPlaylist(id: id, actions: actions)
    }

    public func addToPlaylist(id: String, videoIds: [String]) async throws {
        try await requireSignIn()
        let actions: [JSON] = videoIds.map { ["action": "ACTION_ADD_VIDEO", "addedVideoId": .string($0)] }
        guard !actions.isEmpty else { return }
        try await editPlaylist(id: id, actions: actions)
    }

    public func removeFromPlaylist(id: String, songs: [Song]) async throws {
        try await requireSignIn()
        let actions: [JSON] = songs.compactMap { song in
            guard let setVideoId = song.setVideoId else { return nil }
            return [
                "action": "ACTION_REMOVE_VIDEO",
                "setVideoId": .string(setVideoId),
                "removedVideoId": .string(song.videoId),
            ]
        }
        guard !actions.isEmpty else {
            if songs.isEmpty { return }
            throw MusicAPIError.unreadable("remove from playlist: the songs have no setVideoId")
        }
        try await editPlaylist(id: id, actions: actions)
    }

    func editPlaylist(id: String, actions: [JSON]) async throws {
        let body: JSON = ["playlistId": .string(Parse.playlistId(fromBrowseId: id)), "actions": .array(actions)]
        let response = try await send("browse/edit_playlist", body, signedIn: true)
        if let status = response["status"].string, !status.contains("SUCCEEDED") {
            throw MusicAPIError.unreadable("edit playlist: \(status)")
        }
    }

    // MARK: Pages

    func browsePage(_ endpoint: BrowseEndpoint, what: String) async throws -> BrowsePage {
        var body: JSON = ["browseId": .string(endpoint.browseId)]
        if let params = endpoint.params { body.set("params", .string(params)) }
        let response = try await send("browse", body)
        let page = Parse.browsePage(response)
        guard !page.shelves.isEmpty || !page.chips.isEmpty else {
            throw MusicAPIError.unreadable("\(what): no shelves")
        }
        return page
    }

    // MARK: Transport

    func currentSession() async throws -> InnerTubeSession {
        try Task.checkCancellation()
        do {
            return try await sessionProvider.currentSession()
        } catch let error as MusicAPIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw MusicAPIError.network("session: \(error.localizedDescription)")
        }
    }

    func requireSignIn() async throws {
        guard try await currentSession().isSignedIn else { throw MusicAPIError.signedOut }
    }

    /// Sends one call and returns the answer's JSON.
    ///
    /// Retries `retries` times on a dropped connection, a timeout or a 5xx answer, never after cancellation.
    func send(
        _ endpoint: String,
        _ body: JSON,
        signedIn: Bool = false,
        retries: Int = 1,
        timeout: TimeInterval = 20
    ) async throws -> JSON {
        let session = try await currentSession()
        if signedIn, !session.isSignedIn { throw MusicAPIError.signedOut }
        let request = try InnerTubeRequest.make(endpoint: endpoint, body: body, session: session, timeout: timeout)

        var attempt = 0
        while true {
            try Task.checkCancellation()
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await urlSession.data(for: request)
            } catch let error as URLError {
                if error.code == .cancelled || Task.isCancelled { throw CancellationError() }
                if attempt < retries, Self.isTransient(error) {
                    attempt += 1
                    try await Task.sleep(for: .milliseconds(300 * attempt))
                    continue
                }
                throw MusicAPIError.network("\(endpoint): \(error.code.rawValue) \(error.localizedDescription)")
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw MusicAPIError.network("\(endpoint): \(error.localizedDescription)")
            }
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse else {
                throw MusicAPIError.network("\(endpoint): no HTTP answer")
            }
            switch http.statusCode {
            case 200..<300:
                do {
                    return try JSON(data: data)
                } catch {
                    throw MusicAPIError.unreadable("\(endpoint): answer is not JSON")
                }
            case 401, 403:
                throw MusicAPIError.unauthorized
            case 500..<600 where attempt < retries:
                attempt += 1
                try await Task.sleep(for: .milliseconds(300 * attempt))
                continue
            default:
                let message = (try? JSON(data: data))?["error", "message"].text.map { " (\($0))" } ?? ""
                throw MusicAPIError.network("\(endpoint): HTTP \(http.statusCode)\(message)")
            }
        }
    }

    static func isTransient(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut, .networkConnectionLost, .cannotConnectToHost, .dnsLookupFailed, .notConnectedToInternet,
            .cannotFindHost:
            true
        default:
            false
        }
    }
}

/// Artist browse id → the channel id its "Subscribe" button acts on, learnt from artist pages.
actor ChannelDirectory {
    private var subscribeIds: [String: String] = [:]

    func remember(_ channelId: String, for browseId: String) {
        subscribeIds[browseId] = channelId
    }

    func subscribeId(for browseId: String) -> String? {
        subscribeIds[browseId]
    }
}
