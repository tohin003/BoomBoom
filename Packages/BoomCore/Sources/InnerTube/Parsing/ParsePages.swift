import BoomModels
import Foundation

// Whole pages with a header: albums, artists, playlists; and the player's queue, lyrics and the account menu.
extension Parse {
    // MARK: Album

    static func albumPage(_ response: JSON, browseId: String) -> AlbumPage? {
        // Two-column layout (2024 on): header in the first tab, tracks in `secondaryContents`.
        var header = twoColumnHeader(of: response)["musicResponsiveHeaderRenderer"]
        var buttons = header["buttons"].array
        var artistRuns = header["straplineTextOne"].runs
        if !header.exists {
            // Single-column layout: `header.musicDetailHeaderRenderer`, buttons in its menu.
            header = response["header", "musicDetailHeaderRenderer"]
            buttons = header["menu", "menuRenderer", "topLevelButtons"].array
            artistRuns = []
        }
        guard let title = header["title"].runsText else { return nil }

        let subtitleRuns = header["subtitle"].runs
        let info = songInfo(subtitleRuns, skipTypeSpec: true)
        var artists = songInfo(artistRuns, skipTypeSpec: false).artists
        if artists.isEmpty { artists = info.artists }
        let typeText = info.typeText ?? (subtitleRuns.count == 1 ? subtitleRuns.first?["text"].text : nil)

        var playlistId: String?
        for button in buttons {
            let play = button["musicPlayButtonRenderer", "playNavigationEndpoint"].exists
                ? button["musicPlayButtonRenderer", "playNavigationEndpoint"]
                : button["buttonRenderer", "navigationEndpoint"]
            if let id = watchEndpoint(in: play)?.playlistId {
                playlistId = id
                break
            }
        }

        let list = sectionList(of: response)
        var tracks: [Song] = []
        var shelfSections: [JSON] = []
        for section in list["contents"].array {
            let shelf = section["musicShelfRenderer"]
            if tracks.isEmpty, shelf.exists {
                tracks = shelf["contents"].array.compactMap { wrapper in
                    guard case .song(let song)? = item(wrapper) else { return nil }
                    return song
                }
            } else {
                shelfSections.append(section)
            }
        }
        if playlistId == nil {
            playlistId = response["microformat", "microformatDataRenderer", "urlCanonical"].string
                .flatMap { URLComponents(string: $0)?.queryItems?.first { $0.name == "list" }?.value }
        }

        let artwork = artwork(of: header)
        let album = AlbumItem(
            id: browseId,
            playlistId: playlistId,
            title: title,
            artists: artists,
            year: info.year,
            typeText: typeText,
            artwork: artwork,
            isExplicit: isExplicit(header)
        )
        // Album rows leave out what the header says: fill it in.
        let albumRef = AlbumRef(name: title, id: browseId)
        tracks = tracks.map { track in
            var track = track
            if track.artists.isEmpty { track.artists = artists }
            if track.album == nil { track.album = albumRef }
            if track.artwork.isEmpty { track.artwork = artwork }
            return track
        }
        let description = header["description", "musicDescriptionShelfRenderer", "description"].runsText
            ?? header["description"].runsText
        return AlbumPage(
            album: album,
            description: description,
            statsText: header["secondSubtitle"].runsText,
            tracks: tracks,
            shelves: shelves(shelfSections),
            likeStatus: toggleLikeStatus(buttons),
            libraryTokens: nil
        )
    }

    // MARK: Artist

    static func artistPage(_ response: JSON, browseId: String) -> (page: ArtistPage, subscribeChannelId: String?)? {
        let headerRoot = response["header"]
        guard let (_, header) = headerRoot.soleEntry, let name = header["title"].runsText else { return nil }
        let subscribe = header["subscriptionButton", "subscribeButtonRenderer"]
        let banner = artwork(of: header)
        let foreground = artwork(list: header["foregroundThumbnail", "musicThumbnailRenderer", "thumbnail"])
        var subscribers = subscribe["longSubscriberCountText"].runsText ?? subscribe["subscriberCountText"].runsText
        // The button shows a bare count ("4.68M").
        if let count = subscribers, count.allSatisfy({ !$0.isLetter || "KMBkmb".contains($0) }) {
            subscribers = count + " subscribers"
        }
        let monthly = header["monthlyListenerCount"].runsText

        let list = sectionList(of: response)
        // Album and single cards on an artist page name only the year: they are this artist's.
        let pageArtist = ArtistRef(name: name, id: browseId)
        let shelves = shelves(list["contents"].array).map { shelf in
            var shelf = shelf
            shelf.items = shelf.items.map { item in
                guard case .album(var album) = item, album.artists.isEmpty else { return item }
                album.artists = [pageArtist]
                return .album(album)
            }
            return shelf
        }
        let description = header["description"].runsText
            ?? shelves.first { $0.layout == .text }?.text

        let artist = ArtistItem(
            id: browseId,
            name: name,
            artwork: foreground.isEmpty ? banner : foreground,
            detailText: monthly ?? subscribers
        )
        let page = ArtistPage(
            artist: artist,
            banner: banner,
            description: description,
            subscribersText: subscribers,
            monthlyListenersText: monthly,
            isSubscribed: subscribe["subscribed"].bool ?? false,
            shuffle: watchEndpoint(in: header["playButton", "buttonRenderer", "navigationEndpoint"]),
            radio: watchEndpoint(in: header["startRadioButton", "buttonRenderer", "navigationEndpoint"]),
            shelves: shelves
        )
        return (page, subscribe["channelId"].text)
    }

    // MARK: Playlist

    static func playlistPage(_ response: JSON, playlistId: String) -> PlaylistPage? {
        let headerSection = twoColumnHeader(of: response)
        var isEditable = false
        var header = headerSection["musicResponsiveHeaderRenderer"]
        if !header.exists {
            let editable = headerSection["musicEditablePlaylistDetailHeaderRenderer"]
            if editable.exists {
                isEditable = true
                header = editable["header", "musicResponsiveHeaderRenderer"]
            }
        }
        if !header.exists {
            // Single-column layout.
            let editable = response["header", "musicEditablePlaylistDetailHeaderRenderer"]
            isEditable = editable.exists
            header = editable.exists ? editable["header", "musicDetailHeaderRenderer"]
                : response["header", "musicDetailHeaderRenderer"]
        }

        let list = sectionList(of: response)
        var trackShelf = JSON.null
        var shelfSections: [JSON] = []
        for section in list["contents"].array {
            let shelf = section["musicPlaylistShelfRenderer"].exists
                ? section["musicPlaylistShelfRenderer"] : section["musicShelfRenderer"]
            if !trackShelf.exists, shelf.exists {
                trackShelf = shelf
            } else {
                shelfSections.append(section)
            }
        }
        let rows = trackShelf["contents"].array
        let tracks = songs(rows)
        guard let title = header["title"].runsText ?? (tracks.isEmpty ? nil : pageTitle(response)) else { return nil }

        let facepile = header["facepile", "avatarStackViewModel"]
        let authorName = header["straplineTextOne"].runsText ?? facepile["text", "content"].text
            ?? header["subtitle"].runs.first { $0["navigationEndpoint", "browseEndpoint"].exists }?["text"].text
        var authorArtwork = artwork(of: header["straplineThumbnail"])
        if authorArtwork.isEmpty {
            authorArtwork = Artwork(thumbnails: facepile["avatars"].array.flatMap { avatar in
                avatar["avatarViewModel", "image", "sources"].array.compactMap { source -> Thumbnail? in
                    guard let url = source["url"].text.flatMap(URL.init(string:)) else { return nil }
                    return Thumbnail(url: url, width: source["width"].int ?? 0, height: source["height"].int ?? 0)
                }
            })
        }
        let id = trackShelf["playlistId"].text ?? playlistId
        return PlaylistPage(
            playlist: PlaylistItem(
                id: id, title: title, detailText: header["subtitle"].runsText, artwork: artwork(of: header)
            ),
            description: header["description", "musicDescriptionShelfRenderer", "description"].runsText
                ?? header["description"].runsText,
            authorName: authorName,
            authorArtwork: authorArtwork.isEmpty ? nil : authorArtwork,
            statsText: header["secondSubtitle"].runsText,
            tracks: tracks,
            continuation: continuation(of: trackShelf, items: rows),
            isEditable: isEditable,
            likeStatus: toggleLikeStatus(header["buttons"].array),
            shelves: shelves(shelfSections)
        )
    }

    static func songs(_ wrappers: [JSON]) -> [Song] {
        wrappers.compactMap { wrapper in
            guard case .song(let song)? = item(wrapper) else { return nil }
            return song
        }
    }

    /// The next token of a playlist page's section list (related playlists, suggestions), if any.
    static func sectionContinuation(_ response: JSON) -> String? {
        let list = sectionList(of: response)
        for entry in list["continuations"].array {
            if let token = entry["nextContinuationData", "continuation"].text { return token }
        }
        return nil
    }

    static func songsContinuation(_ response: JSON) -> SongsContinuation? {
        guard let page = continuationPage(response) else { return nil }
        return SongsContinuation(songs: songs(page.items), continuation: page.token)
    }

    // MARK: Watch queue

    static func watchQueue(_ response: JSON, requested: WatchEndpoint) -> WatchQueue? {
        let tabs = response["contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer", "tabs"].array
        guard !tabs.isEmpty else { return nil }
        var lyrics: String?
        var related: String?
        for tab in tabs {
            let renderer = tab["tabRenderer"]
            guard renderer["unselectable"].bool != true,
                let browse = browseEndpoint(in: renderer["endpoint"])
            else { continue }
            let rawType = renderer["endpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType"].string
            if rawType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" || browse.browseId.hasPrefix("MPLY") {
                lyrics = browse.browseId
            } else if rawType == "MUSIC_PAGE_TYPE_TRACK_RELATED" || browse.browseId.hasPrefix("MPTR") {
                related = browse.browseId
            }
        }
        let panel = tabs[0]["tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer"]
        let rows = panel["contents"].array
        let songs = rows.compactMap { row -> Song? in
            if case .song(let song)? = item(row) { return song }
            return nil
        }
        let selected = rows.compactMap { row -> Bool? in
            let renderer = row["playlistPanelVideoRenderer"].exists ? row["playlistPanelVideoRenderer"]
                : row["playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer"]
            guard renderer.exists else { return nil }
            return renderer["selected"].bool ?? false
        }
        let currentVideoId = response["currentVideoEndpoint", "watchEndpoint", "videoId"].text ?? requested.videoId
        let currentIndex = selected.firstIndex(of: true)
            ?? currentVideoId.flatMap { id in songs.firstIndex { $0.videoId == id } }
            ?? 0
        let playlistId = panel["playlistId"].text
            ?? response["currentVideoEndpoint", "watchEndpoint", "playlistId"].text
            ?? requested.playlistId
        return WatchQueue(
            songs: songs,
            currentIndex: min(currentIndex, max(songs.count - 1, 0)),
            playlistId: playlistId,
            continuation: continuation(of: panel, items: rows),
            lyricsBrowseId: lyrics,
            relatedBrowseId: related
        )
    }

    // MARK: Lyrics

    static func lyrics(_ response: JSON) -> Lyrics? {
        // Timed lyrics, when an answer carries them (the mobile clients do; the web client has so far not).
        let timed = response["contents", "elementRenderer", "newElement", "type", "componentType", "model",
            "timedLyricsModel", "lyricsData"]
        let timedLines = timed["timedLyricsData"].array.compactMap { line -> Lyrics.Line? in
            guard let text = line["lyricLine"].string else { return nil }
            let start = line["cueRange", "startTimeMilliseconds"].double.map { $0 / 1000 }
            return Lyrics.Line(text: text, start: start)
        }
        if !timedLines.isEmpty {
            return Lyrics(lines: timedLines, source: timed["sourceMessage"].text)
        }

        for section in sectionList(of: response)["contents"].array {
            let shelf = section["musicDescriptionShelfRenderer"]
            if let text = shelf["description"].runsText {
                let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
                    .split(separator: "\n", omittingEmptySubsequences: false)
                    .map { Lyrics.Line(text: String($0)) }
                return Lyrics(lines: lines, source: shelf["footer"].runsText)
            }
            if section["messageRenderer"].exists
                || section["itemSectionRenderer", "contents", 0, "messageRenderer"].exists
            {
                return Lyrics(lines: [])
            }
        }
        return nil
    }

    // MARK: Moods and genres

    static func chipGroups(_ response: JSON) -> [ChipGroup] {
        sectionList(of: response)["contents"].array.compactMap { section in
            guard let (_, body) = section.soleEntry else { return nil }
            let chips = navigationChips(section)
            guard !chips.isEmpty else { return nil }
            let title = body["header", "gridHeaderRenderer", "title"].runsText
                ?? body["header", "musicCarouselShelfBasicHeaderRenderer", "title"].runsText ?? ""
            return ChipGroup(title: title, chips: chips)
        }
    }

    // MARK: Account

    static func account(_ response: JSON) -> Account? {
        let popup = response["actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer"]
        let header = popup["header", "activeAccountHeaderRenderer"]
        guard let name = header["accountName"].runsText else { return nil }
        return Account(
            name: name,
            handle: header["channelHandle"].runsText,
            artwork: artwork(list: header["accountPhoto"]),
            // The account menu does not say whether the listener has Premium in a way this client can rely on.
            isPremium: false
        )
    }
}
