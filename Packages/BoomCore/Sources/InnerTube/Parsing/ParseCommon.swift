import BoomModels
import Foundation

/// Readers for the small pieces every InnerTube renderer is built from: pictures, links, text runs, menus,
/// continuation tokens. Everything here is forgiving: a missing piece gives nil, never an error.
enum Parse {
    static let dotSeparator = " • "

    // MARK: Pictures

    /// A `{"thumbnails": [...]}` list as `Artwork`.
    static func artwork(list: JSON) -> Artwork {
        let thumbnails = list["thumbnails"].array.compactMap { entry -> Thumbnail? in
            guard var text = entry["url"].text else { return nil }
            if text.hasPrefix("//") { text = "https:" + text }
            guard let url = URL(string: text), url.scheme != nil else { return nil }
            return Thumbnail(url: url, width: entry["width"].int ?? 0, height: entry["height"].int ?? 0)
        }
        return Artwork(thumbnails: thumbnails)
    }

    /// The picture of a renderer, wherever this kind of renderer keeps it.
    static func artwork(of renderer: JSON) -> Artwork {
        let candidates: [JSON] = [
            renderer["thumbnail", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnailRenderer", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnail", "croppedSquareThumbnailRenderer", "thumbnail"],
            renderer["thumbnailRenderer", "croppedSquareThumbnailRenderer", "thumbnail"],
            renderer["foregroundThumbnail", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnail"],
        ]
        for candidate in candidates {
            let artwork = artwork(list: candidate)
            if !artwork.isEmpty { return artwork }
        }
        return .empty
    }

    // MARK: Links

    static func pageType(_ raw: String?, browseId: String) -> BrowseEndpoint.PageType {
        switch raw {
        case "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK": return .album
        case "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_LIBRARY_ARTIST": return .artist
        case "MUSIC_PAGE_TYPE_USER_CHANNEL": return .userChannel
        case "MUSIC_PAGE_TYPE_PLAYLIST": return .playlist
        case "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE": return .podcast
        case .some: return .generic
        case .none:
            if browseId.hasPrefix("MPRE") { return .album }
            if browseId.hasPrefix("MPSP") { return .podcast }
            if browseId.hasPrefix("VL") { return .playlist }
            if browseId.hasPrefix("UC") { return .artist }
            return .generic
        }
    }

    /// A `browseEndpoint` node as `BrowseEndpoint`. `browseId` stays exactly as YouTube Music links it: `VL…` for
    /// playlists, `MPREb_…` for albums, `UC…` for artists.
    static func browseEndpoint(_ node: JSON) -> BrowseEndpoint? {
        guard let browseId = node["browseId"].text else { return nil }
        let rawType = node["browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"].text
        return BrowseEndpoint(
            browseId: browseId,
            params: node["params"].text,
            pageType: pageType(rawType, browseId: browseId)
        )
    }

    /// Where a shelf's "More" button leads. A channel link with `params` opens one section of the channel ("Live
    /// performances", "Playlists by …"), not the artist page, so it is typed `.generic` and opened with `browse`.
    static func moreEndpoint(in navigation: JSON) -> BrowseEndpoint? {
        guard var endpoint = browseEndpoint(in: navigation) else { return nil }
        if endpoint.params != nil, endpoint.pageType == .artist || endpoint.pageType == .userChannel {
            endpoint.pageType = .generic
        }
        return endpoint
    }

    /// The `browseEndpoint` inside a navigation endpoint or command.
    static func browseEndpoint(in navigation: JSON) -> BrowseEndpoint? {
        browseEndpoint(navigation["browseEndpoint"])
    }

    /// A `watchEndpoint` or `watchPlaylistEndpoint` node as `WatchEndpoint`.
    static func watchEndpoint(_ node: JSON) -> WatchEndpoint? {
        let videoId = node["videoId"].text
        let playlistId = node["playlistId"].text
        guard videoId != nil || playlistId != nil else { return nil }
        return WatchEndpoint(
            videoId: videoId, playlistId: playlistId, params: node["params"].text, index: node["index"].int
        )
    }

    /// The watch endpoint inside a navigation endpoint or command (either kind).
    static func watchEndpoint(in navigation: JSON) -> WatchEndpoint? {
        watchEndpoint(navigation["watchEndpoint"]) ?? watchEndpoint(navigation["watchPlaylistEndpoint"])
    }

    static func musicVideoType(in navigation: JSON) -> String? {
        navigation["watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"]
            .text
    }

    /// The play button's endpoint on a list row or card (`overlay` / `thumbnailOverlay`).
    static func playNavigation(of renderer: JSON) -> JSON {
        for key in ["overlay", "thumbnailOverlay"] {
            let endpoint = renderer[.key(key), "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint"]
            if endpoint.exists { return endpoint }
        }
        return .null
    }

    static func songKind(videoType: String?, artwork: Artwork) -> Song.Kind {
        switch videoType {
        case "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK": return .song
        case "MUSIC_VIDEO_TYPE_PODCAST_EPISODE": return .episode
        case .some: return .video
        case .none: return artwork.aspectRatio > 1.2 ? .video : .song
        }
    }

    /// The playlist id behind a playlist browse id (`VLPL…` → `PL…`, `MPSPPL…` → `PL…`).
    static func playlistId(fromBrowseId browseId: String) -> String {
        if browseId.hasPrefix("VL") { return String(browseId.dropFirst(2)) }
        if browseId.hasPrefix("MPSP") { return String(browseId.dropFirst(4)) }
        return browseId
    }

    /// The browse id of a playlist page from either form callers hold: the browse id an endpoint carries
    /// (`VLPL…`), a bare playlist id (`PL…`, `LM`), or a podcast's (`MPSPPL…`).
    static func playlistBrowseId(_ id: String) -> String {
        "VL" + playlistId(fromBrowseId: id)
    }

    /// The browse id of an artist page: the channel (`UC…`), also when given a library artist's `MPLAUC…`.
    static func artistBrowseId(_ id: String) -> String {
        id.hasPrefix("MPLAUC") ? String(id.dropFirst(4)) : id
    }

    // MARK: Badges and text

    static func isExplicit(_ renderer: JSON) -> Bool {
        for key in ["badges", "subtitleBadges", "subtitleBadge"] {
            for badge in renderer[.key(key)].array
            where badge["musicInlineBadgeRenderer", "icon", "iconType"].string == "MUSIC_EXPLICIT_BADGE" {
                return true
            }
        }
        return false
    }

    /// Seconds from "3:45", "1:02:03", or the podcast forms "1 hr 40 min", "52 min", "45 sec".
    static func duration(_ text: String?) -> TimeInterval? {
        guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        if parts.count >= 2, parts.count <= 3, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isASCIIDigit) }) {
            return parts.reduce(0) { total, part in total * 60 + (TimeInterval(part) ?? 0) }
        }
        // "1 hr 40 min", "52 min", "30 sec"
        let words = text.lowercased().split(separator: " ")
        var total: TimeInterval = 0
        var matched = false
        var index = 0
        while index + 1 < words.count {
            if let value = TimeInterval(words[index]) {
                let unit = words[index + 1]
                if unit.hasPrefix("hr") || unit.hasPrefix("hour") {
                    total += value * 3600; matched = true
                } else if unit.hasPrefix("min") {
                    total += value * 60; matched = true
                } else if unit.hasPrefix("sec") {
                    total += value; matched = true
                }
                index += 2
            } else {
                index += 1
            }
        }
        return matched ? total : nil
    }

    static func isDurationText(_ text: String) -> Bool {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        return parts.count >= 2 && parts.count <= 3 && parts.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isASCIIDigit) }
    }

    static func isYearText(_ text: String) -> Bool {
        text.count == 4 && text.allSatisfy(\.isASCIIDigit)
    }

    /// "1.7B plays", "716M views", "58 views", "3406万回視聴": a count followed by a word.
    static func isCountText(_ text: String) -> Bool {
        guard let first = text.first, first.isASCIIDigit else { return false }
        if isDurationText(text) || isYearText(text) { return false }
        // A bare token like "2Pac" is a name, not a count.
        return text.contains(" ") || text.contains("\u{00A0}") || !text.allSatisfy(\.isASCII)
    }

    static func isSeparator(_ run: JSON) -> Bool {
        guard run["navigationEndpoint"].exists == false, let text = run["text"].string else { return false }
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        return trimmed == "•" || trimmed == "&" || trimmed == "," || trimmed == "·" || text == ", "
            || text == " & " || text == " and " || text == " x "
    }

    // MARK: The "Artist • Album • 3:45" line

    struct SongInfo: Equatable {
        var artists: [ArtistRef] = []
        var album: AlbumRef?
        var duration: TimeInterval?
        var year: String?
        var countText: String?
        /// The leading type word that was skipped ("Song", "Video", "Album", "Single").
        var typeText: String?
    }

    /// The words YouTube Music (in English) puts first on a result line: "Song • Oasis", "Single • Oasis • 1995".
    static let typeWords: Set<String> = [
        "song", "video", "album", "single", "ep", "artist", "playlist", "station", "profile", "podcast", "episode",
        "audiobook",
    ]

    /// Whether `runs` start with a type word and its " • ": an unlinked first run that is not a duration, year or
    /// count. With `anyWord` (unfiltered search rows, where every line starts with one, in any language) any such
    /// run counts; otherwise only a known English type word, so an artist without a link ("Oasis • 3:45") stays.
    static func startsWithTypeWord(_ runs: [JSON], anyWord: Bool) -> Bool {
        guard runs.count > 2, !runs[0]["navigationEndpoint"].exists, runs[1]["text"].string == dotSeparator,
            let first = runs[0]["text"].text, !isDurationText(first), !isYearText(first), !isCountText(first)
        else { return false }
        return anyWord || typeWords.contains(first.lowercased())
    }

    /// Reads a subtitle line of runs. Linked runs are artists (`UC…`) or albums (`MPRE…`); unlinked runs are a
    /// duration, a year, a play count, or an artist without a page.
    ///
    /// With `skipTypeSpec`, a leading type word ("Song • Oasis") is skipped instead of read as an artist, the way
    /// ytmusicapi does it (see `startsWithTypeWord`).
    static func songInfo(_ runs: [JSON], skipTypeSpec: Bool, anyTypeWord: Bool = false) -> SongInfo {
        var info = SongInfo()
        var runs = runs
        if skipTypeSpec, startsWithTypeWord(runs, anyWord: anyTypeWord), let first = runs[0]["text"].text {
            info.typeText = first
            runs.removeFirst(2)
        }
        for run in runs {
            guard let text = run["text"].string, !isSeparator(run),
                !text.trimmingCharacters(in: .whitespaces).isEmpty
            else { continue }
            let navigation = run["navigationEndpoint"]
            if navigation.exists {
                let browseId = navigation["browseEndpoint", "browseId"].text
                if let browseId, browseId.hasPrefix("MPRE") || browseId.contains("release_detail") {
                    info.album = AlbumRef(name: text, id: browseId)
                } else if let browseId, browseId.hasPrefix("MPSP") || browseId.hasPrefix("FEmusic") {
                    // A podcast show or a chart link: shown as the "artist" line without a channel.
                    info.artists.append(ArtistRef(name: text, id: nil))
                } else {
                    info.artists.append(ArtistRef(name: text, id: browseId))
                }
            } else if isDurationText(text) {
                info.duration = duration(text)
            } else if isYearText(text) {
                info.year = text
            } else if isCountText(text) {
                // "1.3B views • 6.9M likes": the first count is the plays.
                info.countText = info.countText ?? text
            } else {
                info.artists.append(ArtistRef(name: text, id: nil))
            }
        }
        return info
    }

    // MARK: Menus

    struct MenuInfo: Equatable {
        var likeStatus: LikeStatus?
        var libraryTokens: LibraryTokens?
        var setVideoId: String?
        var removedVideoId: String?
    }

    /// Like status, "Save to library" tokens and the playlist entry id from a row's `menu`.
    static func menuInfo(_ renderer: JSON) -> MenuInfo {
        var info = MenuInfo()
        let menu = renderer["menu", "menuRenderer"]
        for button in menu["topLevelButtons"].array {
            if let status = button["likeButtonRenderer", "likeStatus"].text {
                info.likeStatus = LikeStatus(rawValue: status)
            }
        }
        for item in menu["items"].array {
            let toggle = item["toggleMenuServiceItemRenderer"]
            if toggle.exists {
                let icon = toggle["defaultIcon", "iconType"].string ?? ""
                let isToggled = toggle["isToggled"].bool ?? false
                let defaultToken = toggle["defaultServiceEndpoint", "feedbackEndpoint", "feedbackToken"].text
                let toggledToken = toggle["toggledServiceEndpoint", "feedbackEndpoint", "feedbackToken"].text
                switch icon {
                case "BOOKMARK_BORDER", "LIBRARY_ADD":
                    info.libraryTokens = LibraryTokens(add: defaultToken, remove: toggledToken, isInLibrary: isToggled)
                case "BOOKMARK", "LIBRARY_SAVED", "LIBRARY_REMOVE":
                    info.libraryTokens = LibraryTokens(add: toggledToken, remove: defaultToken, isInLibrary: true)
                default:
                    break
                }
                if info.likeStatus == nil,
                    let status = toggle["defaultServiceEndpoint", "likeEndpoint", "status"].text
                {
                    // The default action is the opposite of the current state.
                    info.likeStatus = status == "LIKE" ? .indifferent : .like
                }
            }
            let service = item["menuServiceItemRenderer", "serviceEndpoint", "playlistEditEndpoint", "actions", 0]
            if let setVideoId = service["setVideoId"].text {
                info.setVideoId = setVideoId
                info.removedVideoId = service["removedVideoId"].text
            }
        }
        return info
    }

    /// The like state of a header's toggle button (albums, playlists).
    static func toggleLikeStatus(_ buttons: [JSON]) -> LikeStatus? {
        for button in buttons {
            let toggle = button["toggleButtonRenderer"]
            guard toggle.exists else { continue }
            if let status = toggle["defaultServiceEndpoint", "likeEndpoint", "status"].text {
                if let isToggled = toggle["isToggled"].bool, toggle["toggledServiceEndpoint"].exists {
                    return isToggled ? .like : .indifferent
                }
                return status == "LIKE" ? .indifferent : .like
            }
        }
        return nil
    }

    // MARK: Continuations

    /// The token of a list that scrolls on, in either form YouTube Music uses: the legacy
    /// `continuations[0].nextContinuationData.continuation` on the container, or a trailing
    /// `continuationItemRenderer` in its items.
    static func continuation(of container: JSON, items: [JSON]? = nil) -> String? {
        for entry in container["continuations"].array {
            for key in ["nextContinuationData", "nextRadioContinuationData", "reloadContinuationData"] {
                if let token = entry[.key(key), "continuation"].text { return token }
            }
        }
        let list = items ?? (container["contents"].array.isEmpty ? container["items"].array : container["contents"].array)
        if let last = list.last {
            return continuationItemToken(last)
        }
        return nil
    }

    static func continuationItemToken(_ item: JSON) -> String? {
        let renderer = item["continuationItemRenderer"]
        guard renderer.exists else { return nil }
        let endpoint = renderer["continuationEndpoint"]
        if let token = endpoint["continuationCommand", "token"].text { return token }
        for command in endpoint["commandExecutorCommand", "commands"].array {
            if let token = command["continuationCommand", "token"].text { return token }
        }
        return renderer["button", "buttonRenderer", "command", "continuationCommand", "token"].text
    }
}

extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}
