import BoomModels
import Foundation

// Search answers: suggestions while typing, the unfiltered results (top result card, then rows by kind) and the
// filtered results (one list that scrolls on).
extension Parse {
    // MARK: Filters

    /// The `params` that select a filter (ytmusicapi's `get_search_params`, spelling correction on).
    static func searchParams(for filter: SearchFilter) -> String {
        let tail = "AWoMEA4QChADEAQQCRAF"
        switch filter {
        case .songs: return "EgWKAQII" + tail
        case .videos: return "EgWKAQIQ" + tail
        case .albums: return "EgWKAQIY" + tail
        case .artists: return "EgWKAQIg" + tail
        case .profiles: return "EgWKAQJY" + tail
        case .podcasts: return "EgWKAQJQ" + tail
        case .episodes: return "EgWKAQJI" + tail
        case .featuredPlaylists: return "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"
        case .communityPlaylists: return "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"
        }
    }

    /// Reads the filter back out of a search `params` (a base64 protobuf: field 2 → field 17 → the kind's field).
    static func searchFilter(params: String) -> SearchFilter? {
        var text = params.removingPercentEncoding ?? params
        text = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while text.count % 4 != 0 { text += "=" }
        guard let data = Data(base64Encoded: text) else { return nil }
        let bytes = [UInt8](data)
        guard let start = bytes.indices.dropLast(2).first(where: { bytes[$0] == 0x8A && bytes[$0 + 1] == 0x01 })
        else { return nil }
        let length = Int(bytes[start + 2])
        let inner = bytes[(start + 3)..<min(bytes.count, start + 3 + length)]
        guard let tag = inner.first else { return nil }
        switch tag {
        case 0x08: return .songs
        case 0x10: return .videos
        case 0x18: return .albums
        case 0x20: return .artists
        case 0x28: return inner.contains(0x38) ? .featuredPlaylists : .communityPlaylists
        case 0x48: return .episodes
        case 0x50: return .podcasts
        case 0x58: return .profiles
        default: return nil
        }
    }

    // MARK: Results

    static func searchSectionList(_ response: JSON) -> JSON {
        let tabbed = response["contents", "tabbedSearchResultsRenderer", "tabs"].array
        if let tab = tabbed.first(where: { $0["tabRenderer", "selected"].bool == true }) ?? tabbed.first {
            return tab["tabRenderer", "content", "sectionListRenderer"]
        }
        return response["contents", "sectionListRenderer"]
    }

    static func searchResults(_ response: JSON, query: String, filter: SearchFilter?) -> SearchResults {
        let list = searchSectionList(response)
        var corrected: String?
        var shelves: [Shelf] = []
        var flat: [(group: SearchFilter, item: MusicItem)] = []
        var continuation: String?

        for (index, section) in list["contents"].array.enumerated() {
            guard let (key, body) = section.soleEntry else { continue }
            switch key {
            case "musicCardShelfRenderer":
                if let shelf = topResultShelf(body, index: index) { shelves.append(shelf) }
            case "musicShelfRenderer":
                if var shelf = listShelf(body, index: index) {
                    shelf.moreFilter = shelf.moreFilter ?? filter
                    if filter != nil {
                        shelf.title = shelf.title.isEmpty ? (filter?.title ?? "") : shelf.title
                        continuation = continuation ?? shelf.continuation
                    } else {
                        shelf.continuation = nil
                    }
                    shelves.append(shelf)
                }
            case "itemSectionRenderer":
                for inner in body["contents"].array {
                    if let text = inner["showingResultsForRenderer", "correctedQueryEndpoint", "searchEndpoint", "query"].text
                        ?? inner["showingResultsForRenderer", "correctedQuery"].runsText
                        ?? inner["didYouMeanRenderer", "correctedQueryEndpoint", "searchEndpoint", "query"].text
                        ?? inner["didYouMeanRenderer", "correctedQuery"].runsText
                    {
                        corrected = text
                    }
                    let row = inner["musicResponsiveListItemRenderer"]
                    if row.exists, let item = responsiveItem(row, typeWordFirst: filter == nil) {
                        flat.append((filter ?? searchGroup(of: item, row: row), item))
                    }
                    if let token = continuationItemToken(inner) { continuation = continuation ?? token }
                }
            default:
                continue
            }
        }

        // The flat layout (one row per section, no headers): one shelf per kind, in order of first appearance.
        var order: [SearchFilter] = []
        var groups: [SearchFilter: [MusicItem]] = [:]
        for entry in flat {
            if groups[entry.group] == nil { order.append(entry.group) }
            groups[entry.group, default: []].append(entry.item)
        }
        for group in order {
            shelves.append(Shelf(
                id: "search:\(group.rawValue)",
                title: group.title,
                layout: .list,
                items: groups[group] ?? [],
                moreFilter: group
            ))
        }
        if filter != nil {
            continuation = continuation ?? Parse.continuation(of: list)
        }
        return SearchResults(
            query: query,
            correctedQuery: corrected,
            filter: filter,
            shelves: shelves,
            continuation: filter == nil ? nil : continuation
        )
    }

    /// Which "Show all" filter a result row belongs to.
    static func searchGroup(of item: MusicItem, row: JSON) -> SearchFilter {
        switch item {
        case .song(let song):
            switch song.kind {
            case .song: return .songs
            case .video: return .videos
            case .episode: return .episodes
            }
        case .album: return .albums
        case .artist:
            let pageType = row["navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType"].string
            return pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ? .profiles : .artists
        case .playlist(let playlist):
            let browseId = row["navigationEndpoint", "browseEndpoint", "browseId"].string ?? ""
            if browseId.hasPrefix("MPSP") { return .podcasts }
            return playlist.id.hasPrefix("RDCLAK") ? .featuredPlaylists : .communityPlaylists
        }
    }

    /// The big "Top result" card: its item first, then the rows under it.
    static func topResultShelf(_ card: JSON, index: Int) -> Shelf? {
        var items: [MusicItem] = []
        let top = topResultItem(card)
        if let top { items.append(top) }
        // Under an artist's card the songs' own lines leave the artist out ("Song • 5:55"): it is the card's.
        var cardArtist: ArtistRef?
        if case .artist(let artist)? = top { cardArtist = ArtistRef(name: artist.name, id: artist.id) }
        for wrapper in card["contents"].array {
            guard var item = item(wrapper) else { continue }
            if let cardArtist, case .song(var song) = item, song.artists.isEmpty {
                song.artists = [cardArtist]
                item = .song(song)
            }
            items.append(item)
        }
        guard !items.isEmpty else { return nil }
        let title = card["header", "musicCardShelfHeaderBasicRenderer", "title"].runsText ?? "Top result"
        return Shelf(id: "\(index):top", title: title, layout: .topResult, items: items)
    }

    static func topResultItem(_ card: JSON) -> MusicItem? {
        guard let title = card["title"].runsText else { return nil }
        let titleNavigation = card["title", "runs", 0, "navigationEndpoint"]
        let subtitleRuns = card["subtitle"].runs
        let artwork = artwork(of: card)
        // The card's line always starts with the kind ("Video • Coldplay • 1.3B views").
        let info = songInfo(subtitleRuns, skipTypeSpec: true, anyTypeWord: true)

        if let browse = browseEndpoint(in: titleNavigation) ?? browseEndpoint(in: card["onTap"]) {
            switch browse.pageType {
            case .artist, .userChannel:
                var runs = subtitleRuns
                if runs.count > 2, runs[1]["text"].string == dotSeparator { runs.removeFirst(2) }
                let detail = runs.compactMap { $0["text"].string }.joined()
                return .artist(ArtistItem(
                    id: browse.browseId, name: title, artwork: artwork, detailText: detail.isEmpty ? nil : detail
                ))
            case .album:
                var playlistId: String?
                for button in card["buttons"].array {
                    if let id = watchEndpoint(in: button["buttonRenderer", "command"])?.playlistId {
                        playlistId = id
                        break
                    }
                }
                return .album(AlbumItem(
                    id: browse.browseId, playlistId: playlistId, title: title, artists: info.artists,
                    year: info.year, typeText: info.typeText, artwork: artwork, isExplicit: isExplicit(card)
                ))
            case .playlist, .podcast:
                return .playlist(PlaylistItem(
                    id: playlistId(fromBrowseId: browse.browseId), title: title,
                    detailText: card["subtitle"].runsText, artwork: artwork
                ))
            case .generic:
                break
            }
        }
        let watch = titleNavigation["watchEndpoint"].exists ? titleNavigation : card["onTap"]
        guard let videoId = watch["watchEndpoint", "videoId"].text
            ?? playNavigation(of: card)["watchEndpoint", "videoId"].text
        else { return nil }
        var kind = songKind(videoType: musicVideoType(in: watch), artwork: artwork)
        if musicVideoType(in: watch) == nil, info.typeText?.lowercased() == "video" { kind = .video }
        let menu = menuInfo(card)
        return .song(Song(
            videoId: videoId,
            title: title,
            artists: info.artists,
            album: info.album,
            duration: info.duration,
            artwork: artwork,
            isExplicit: isExplicit(card),
            kind: kind,
            playsText: info.countText,
            likeStatus: menu.likeStatus,
            libraryTokens: menu.libraryTokens
        ))
    }

    static func searchContinuation(_ response: JSON) -> SongsOrItemsContinuation? {
        guard let page = continuationPage(response) else { return nil }
        var items: [MusicItem] = []
        for wrapper in page.items {
            if let item = item(wrapper) {
                items.append(item)
            } else {
                // Newer answers wrap each row in its own item section.
                items.append(contentsOf: wrapper["itemSectionRenderer", "contents"].array.compactMap(item))
            }
        }
        var token = page.token
        if token == nil, let last = page.items.last {
            token = last["itemSectionRenderer", "contents"].array.last.flatMap(continuationItemToken)
        }
        return SongsOrItemsContinuation(items: items, continuation: token)
    }

    // MARK: Suggestions

    static func searchSuggestions(_ response: JSON) -> [SearchSuggestion] {
        var queries: [SearchSuggestion] = []
        var items: [SearchSuggestion] = []
        for section in response["contents"].array {
            for entry in section["searchSuggestionsSectionRenderer", "contents"].array {
                let history = entry["historySuggestionRenderer"]
                let plain = entry["searchSuggestionRenderer"]
                if history.exists || plain.exists {
                    let renderer = history.exists ? history : plain
                    let runs = renderer["suggestion"].runs.compactMap { run -> SuggestedQuery.Run? in
                        guard let text = run["text"].text else { return nil }
                        return SuggestedQuery.Run(text: text, isBold: run["bold"].bool ?? false)
                    }
                    guard let text = renderer["navigationEndpoint", "searchEndpoint", "query"].text
                        ?? renderer["suggestion"].runsText
                    else { continue }
                    queries.append(.query(SuggestedQuery(text: text, runs: runs, isFromHistory: history.exists)))
                } else if let item = item(entry) {
                    items.append(.item(item))
                }
            }
        }
        return queries + items
    }
}
