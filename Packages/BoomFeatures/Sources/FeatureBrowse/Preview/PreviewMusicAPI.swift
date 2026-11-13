import BoomModels
import Foundation
import Synchronization

/// A complete `MusicAPI` on invented data, for previews, demo apps and UI work. Answers arrive after a short random
/// delay (100–300 ms) so loading states show, and honour task cancellation. Nothing here is a real artist or song.
public struct PreviewMusicAPI: MusicAPI {
    private let catalog: PreviewCatalog
    private let delays: Bool
    private let latency: ClosedRange<Int>
    private let signedIn: Bool
    private let failsBrowsing: Bool
    /// Fixed delays per call for tests: `(call, input) -> delay`, nil for the normal delay.
    private let delayOverride: (@Sendable (String, String) -> Duration?)?
    /// Calls that fail for tests: `(call, input) -> fails`, decided when the call answers.
    private let failOverride: (@Sendable (String, String) -> Bool)?
    /// Every call, in order, for tests.
    let log = CallLog()

    /// - Parameters:
    ///   - delays: Wait 100–300 ms before answering.
    ///   - signedIn: Library calls answer (otherwise they throw `.signedOut`) and `account()` returns someone.
    ///   - failsBrowsing: Every page request throws a network error, to see error states.
    ///   - latency: The range of the delay, in milliseconds (raise it to look at loading states).
    public init(
        delays: Bool = true, signedIn: Bool = true, failsBrowsing: Bool = false,
        latency: ClosedRange<Int> = 100...300
    ) {
        self.init(
            delays: delays, signedIn: signedIn, failsBrowsing: failsBrowsing, latency: latency, delayOverride: nil,
            failOverride: nil)
    }

    init(
        delays: Bool, signedIn: Bool, failsBrowsing: Bool, latency: ClosedRange<Int> = 100...300,
        delayOverride: (@Sendable (String, String) -> Duration?)?,
        failOverride: (@Sendable (String, String) -> Bool)? = nil
    ) {
        catalog = PreviewCatalog()
        self.delays = delays
        self.latency = latency
        self.signedIn = signedIn
        self.failsBrowsing = failsBrowsing
        self.delayOverride = delayOverride
        self.failOverride = failOverride
    }

    /// The listener the preview pretends is signed in.
    public static let previewAccount = Account(
        name: "Alex Rivera", handle: "@alexrivera", artwork: PreviewCatalog.square("listener"), isPremium: false)

    private func wait(_ call: String, _ input: String = "", page: Bool = false) async throws {
        log.append("\(call):\(input)")
        if let fixed = delayOverride?(call, input) {
            try await Task.sleep(for: fixed)
        } else if delays {
            try await Task.sleep(for: .milliseconds(Int.random(in: latency)))
        }
        if page, failsBrowsing { throw MusicAPIError.network("Preview: browsing is set to fail.") }
        if failOverride?(call, input) == true { throw MusicAPIError.network("Preview: this call is set to fail.") }
    }

    private func requireSignIn() throws {
        if !signedIn { throw MusicAPIError.signedOut }
    }

    // MARK: Search

    public func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] {
        try await wait("suggestions", input)
        let typed = input.trimmingCharacters(in: .whitespaces).lowercased()
        guard !typed.isEmpty else { return [] }
        let history: Set<String> = ["juniper vale", "low orbit", "lofi beats to study"]
        let phrases = ["lofi beats to study", "love songs", "lo-fi chill", "lonely nights playlist", "live sessions",
                       "late night drive", "summer hits", "sad songs", "workout mix", "acoustic covers",
                       "jazz for rainy days", "new music friday", "party anthems", "piano focus"]
        let corpus = catalog.artists.map(\.name) + catalog.albums.map(\.title)
            + Array(Set(catalog.songs.map(\.title))).sorted() + phrases
        var seen = Set<String>()
        var queries: [SearchSuggestion] = []
        for text in corpus.map({ $0.lowercased() }) where !seen.contains(text) {
            guard text.hasPrefix(typed) || text.split(separator: " ").contains(where: { $0.hasPrefix(typed) })
            else { continue }
            seen.insert(text)
            let runs: [SuggestedQuery.Run]
            if text.hasPrefix(typed) {
                runs = [.init(text: String(text.prefix(typed.count)), isBold: false),
                        .init(text: String(text.dropFirst(typed.count)), isBold: true)].filter { !$0.text.isEmpty }
            } else {
                runs = [.init(text: text, isBold: true)]
            }
            queries.append(.query(SuggestedQuery(text: text, runs: runs, isFromHistory: history.contains(text))))
        }
        queries.sort { lhs, rhs in
            guard case .query(let a) = lhs, case .query(let b) = rhs else { return false }
            if a.isFromHistory != b.isFromHistory { return a.isFromHistory }
            return a.text.hasPrefix(typed) && !b.text.hasPrefix(typed)
        }
        var items: [SearchSuggestion] = catalog.artists
            .filter { $0.name.lowercased().hasPrefix(typed) }.prefix(1).map { .item(.artist($0)) }
        items += catalog.songs.filter { $0.title.lowercased().hasPrefix(typed) }.prefix(2).map { .item(.song($0)) }
        items += catalog.albums.filter { $0.title.lowercased().hasPrefix(typed) }.prefix(1).map { .item(.album($0)) }
        return Array(queries.prefix(7)) + items.prefix(3)
    }

    public func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults {
        try await wait("search", "\(query)|\(filter?.rawValue ?? "")", page: true)
        var words = query.trimmingCharacters(in: .whitespaces).lowercased()
        var corrected: String?
        if !words.isEmpty, matches(words).isEmpty {
            // Pretend to fix a typo: the artist that shares the most leading letters.
            let best = catalog.artists.max { commonPrefix($0.name.lowercased(), words) < commonPrefix($1.name.lowercased(), words) }
            if let best, commonPrefix(best.name.lowercased(), words) >= 2 {
                corrected = best.name
                words = best.name.lowercased()
            }
        }
        let seed = PreviewCatalog.stableHash(words)
        let found = matches(words)
        let songs = padded(found.songs, catalog.songs(seed: seed, count: 30), to: 30)
        let videos = padded(found.videos, catalog.videos(seed: seed, count: 20), to: 20)
        let albums = padded(found.albums, catalog.albums(seed: seed, count: 20), to: 20)
        let artists = padded(found.artists, catalog.artists(seed: seed, count: 14), to: 14)
        let playlists = catalog.playlists(seed: seed, count: 14)

        if let filter {
            let all = items(for: filter, songs: songs, videos: videos, albums: albums, artists: artists,
                            playlists: playlists)
            let pageItems = Array(all.prefix(20))
            let shelf = Shelf(id: "filtered-\(filter.rawValue)", title: filter.title, layout: .list, items: pageItems)
            let token = all.count > 0 ? "s|\(filter.rawValue)|\(words)|1" : nil
            return SearchResults(query: query, correctedQuery: corrected, filter: filter, shelves: [shelf],
                                 continuation: token)
        }

        let top: MusicItem
        var quick: [MusicItem]
        if let artist = found.artists.first {
            top = .artist(artist)
            quick = catalog.songs(of: artist.id).prefix(3).map { .song($0) }
        } else if let song = found.songs.first {
            top = .song(song)
            quick = songs.dropFirst().prefix(3).map { .song($0) }
        } else if let album = found.albums.first {
            top = .album(album)
            quick = (catalog.tracksByAlbum[album.id] ?? []).prefix(3).map { .song($0) }
        } else {
            top = .artist(artists[0])
            quick = catalog.songs(of: artists[0].id).prefix(3).map { .song($0) }
        }
        let shelves = [
            Shelf(id: "top", title: "Top result", layout: .topResult, items: [top] + quick),
            Shelf(id: "songs", title: "Songs", layout: .list, items: songs.prefix(4).map { .song($0) },
                  moreFilter: .songs),
            Shelf(id: "videos", title: "Videos", layout: .list, items: videos.prefix(3).map { .song($0) },
                  moreFilter: .videos),
            Shelf(id: "albums", title: "Albums", layout: .list, items: albums.prefix(3).map { .album($0) },
                  moreFilter: .albums),
            Shelf(id: "artists", title: "Artists", layout: .list, items: artists.prefix(3).map { .artist($0) },
                  moreFilter: .artists),
            Shelf(id: "community", title: "Community playlists", layout: .list,
                  items: playlists.dropFirst(4).prefix(3).map { .playlist($0) }, moreFilter: .communityPlaylists),
            Shelf(id: "featured", title: "Featured playlists", layout: .list,
                  items: playlists.prefix(2).map { .playlist($0) }, moreFilter: .featuredPlaylists),
        ]
        return SearchResults(query: query, correctedQuery: corrected, shelves: shelves)
    }

    public func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation {
        try await wait("searchContinuation", token, page: true)
        let parts = token.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, let filter = SearchFilter(rawValue: parts[1]), let page = Int(parts[3]) else {
            throw MusicAPIError.unreadable("Preview: bad search token")
        }
        let seed = PreviewCatalog.stableHash(parts[2]) + page * 17
        let all = items(
            for: filter, songs: catalog.songs(seed: seed, count: 20), videos: catalog.videos(seed: seed, count: 20),
            albums: catalog.albums(seed: seed, count: 20), artists: catalog.artists(seed: seed, count: 14),
            playlists: catalog.playlists(seed: seed, count: 14))
        let next = page < 3 ? "s|\(parts[1])|\(parts[2])|\(page + 1)" : nil
        return SongsOrItemsContinuation(items: Array(all.prefix(20)), continuation: next)
    }

    private struct Matches {
        var songs: [Song] = []
        var videos: [Song] = []
        var albums: [AlbumItem] = []
        var artists: [ArtistItem] = []
        var isEmpty: Bool { songs.isEmpty && videos.isEmpty && albums.isEmpty && artists.isEmpty }
    }

    private func matches(_ words: String) -> Matches {
        guard !words.isEmpty else { return Matches() }
        func hit(_ text: String) -> Bool { text.lowercased().contains(words) }
        var found = Matches()
        found.artists = catalog.artists.filter { hit($0.name) }
        found.songs = catalog.songs.filter { hit($0.title) || $0.artists.contains { hit($0.name) } }
        found.videos = catalog.videos.filter { hit($0.title) || $0.artists.contains { hit($0.name) } }
        found.albums = catalog.albums.filter { hit($0.title) || $0.artists.contains { hit($0.name) } }
        return found
    }

    private func padded<T: Identifiable>(_ first: [T], _ filler: [T], to count: Int) -> [T] {
        var seen = Set(first.map(\.id))
        var result = first
        for item in filler where result.count < count && !seen.contains(item.id) {
            seen.insert(item.id)
            result.append(item)
        }
        return result
    }

    private func commonPrefix(_ a: String, _ b: String) -> Int {
        zip(a, b).prefix { $0 == $1 }.count
    }

    private func items(
        for filter: SearchFilter, songs: [Song], videos: [Song], albums: [AlbumItem], artists: [ArtistItem],
        playlists: [PlaylistItem]
    ) -> [MusicItem] {
        switch filter {
        case .songs: songs.map { .song($0) }
        case .videos: videos.map { .song($0) }
        case .albums: albums.map { .album($0) }
        case .artists: artists.map { .artist($0) }
        case .communityPlaylists: playlists.dropFirst(4).map { .playlist($0) }
        case .featuredPlaylists: playlists.prefix(8).map { .playlist($0) }
        case .podcasts, .episodes, .profiles: []
        }
    }

    // MARK: Browse

    public static let homeChips = [
        "Energize", "Relax", "Workout", "Focus", "Commute", "Feel good", "Party", "Romance", "Sad", "Sleep",
    ]

    public func home(chip: Chip?) async throws -> BrowsePage {
        try await wait("home", chip?.title ?? "", page: true)
        let chips = Self.homeChips.map { title in
            Chip(title: title, endpoint: BrowseEndpoint(browseId: "FEmusic_home", params: "chip:" + title),
                 isSelected: title == chip?.title)
        }
        let seed = chip.map { PreviewCatalog.stableHash($0.title) % 97 } ?? 0
        let similar = catalog.artists[(seed + 0) % catalog.artists.count]
        var shelves = [
            Shelf(id: "quick", title: "Quick picks", strapline: "Start radio from a song", layout: .columns,
                  items: catalog.songs(seed: seed + 1, count: 20).map { .song($0) }),
            Shelf(id: "again", title: chip == nil ? "Listen again" : "\(chip?.title ?? "") favourites",
                  layout: .carousel, items: mixed(seed: seed + 2, count: 10)),
            Shelf(id: "similar", title: similar.name, strapline: "Similar to", headerArtwork: similar.artwork,
                  layout: .carousel, items: catalog.albums(seed: seed + 3, count: 8).map { .album($0) },
                  more: BrowseEndpoint(browseId: similar.id, pageType: .artist)),
            Shelf(id: "mixes", title: "Mixed for you", layout: .carousel,
                  items: catalog.playlists(seed: seed, count: 6).map { .playlist($0) },
                  more: BrowseEndpoint(browseId: "FEmusic_mixed_for_you")),
            Shelf(id: "videos", title: "Recommended music videos", layout: .carousel,
                  items: catalog.videos(seed: seed + 4, count: 8).map { .song($0) }),
            Shelf(id: "trending", title: "Trending songs", layout: .ranked,
                  items: catalog.songs(seed: seed + 5, count: 6).map { .song($0) },
                  more: BrowseEndpoint(browseId: "FEmusic_charts")),
            Shelf(id: "albums", title: "Albums for you", layout: .grid,
                  items: catalog.albums(seed: seed + 6, count: 4).map { .album($0) }),
            Shelf(id: "artists", title: "Artists you might like", layout: .carousel,
                  items: catalog.artists(seed: seed + 7, count: 8).map { .artist($0) }),
        ]
        if chip != nil { shelves.removeLast(2) }
        return BrowsePage(chips: chips, shelves: shelves, continuation: "home|\(seed)|1")
    }

    public func explore() async throws -> BrowsePage {
        try await wait("explore", page: true)
        return BrowsePage(title: "Explore", shelves: [
            Shelf(id: "new", title: "New albums & singles", layout: .carousel,
                  items: catalog.albums(seed: 11, count: 10).map { .album($0) },
                  more: BrowseEndpoint(browseId: "FEmusic_new_releases_albums")),
            Shelf(id: "trending", title: "Trending", layout: .ranked,
                  items: catalog.songs(seed: 12, count: 8).map { .song($0) },
                  more: BrowseEndpoint(browseId: "FEmusic_charts")),
            Shelf(id: "videos", title: "New music videos", layout: .carousel,
                  items: catalog.videos(seed: 13, count: 8).map { .song($0) },
                  more: BrowseEndpoint(browseId: "FEmusic_new_releases_videos")),
            Shelf(id: "popular", title: "Popular playlists", layout: .carousel,
                  items: catalog.playlists(seed: 4, count: 8).map { .playlist($0) }),
        ])
    }

    public func charts(country: String?) async throws -> BrowsePage {
        try await wait("charts", country ?? "", page: true)
        let seed = PreviewCatalog.stableHash(country ?? "ZZ") % 50
        let countries = [("Global", "ZZ"), ("United States", "US"), ("India", "IN"), ("United Kingdom", "GB")]
        return BrowsePage(
            title: "Charts",
            chips: countries.map {
                Chip(title: $0.0, endpoint: BrowseEndpoint(browseId: "FEmusic_charts", params: $0.1),
                     isSelected: $0.1 == (country ?? "ZZ"))
            },
            shelves: [
                Shelf(id: "top-songs", title: "Top songs", layout: .ranked,
                      items: catalog.songs(seed: seed + 20, count: 10).map { .song($0) },
                      more: BrowseEndpoint(browseId: "PLbb_topsongs", pageType: .playlist)),
                Shelf(id: "top-videos", title: "Top music videos", layout: .carousel,
                      items: catalog.videos(seed: seed + 21, count: 10).map { .song($0) }),
                Shelf(id: "trending", title: "Trending", layout: .ranked,
                      items: catalog.songs(seed: seed + 22, count: 10).map { .song($0) }),
                Shelf(id: "top-artists", title: "Top artists", layout: .ranked,
                      items: catalog.artists(seed: seed + 23, count: 10).map { .artist($0) }),
            ])
    }

    public func moodsAndGenres() async throws -> [ChipGroup] {
        try await wait("moods", page: true)
        func chips(_ entries: [(String, UInt32)]) -> [Chip] {
            entries.map {
                Chip(title: $0.0, endpoint: BrowseEndpoint(browseId: "FEmusic_moods_and_genres_category",
                                                           params: $0.0),
                     colorHex: $0.1)
            }
        }
        return [
            ChipGroup(title: "Moods & moments", chips: chips([
                ("Chill", 0x3E8E7E), ("Commute", 0xD08A3E), ("Energy boosters", 0xE85D4A), ("Feel good", 0xF2C14E),
                ("Focus", 0x5B7DB1), ("Party", 0xC0479A), ("Romance", 0xE0607E), ("Sad", 0x6A6FA8),
                ("Sleep", 0x2F4B7C), ("Workout", 0xD9534F),
            ])),
            ChipGroup(title: "Genres", chips: chips([
                ("Pop", 0xE86FB0), ("Hip-hop", 0xA36BE0), ("Rock", 0xC94B3C), ("Indie & alternative", 0x58A55C),
                ("Electronic", 0x3FA7D6), ("R&B & soul", 0x9B5DE5), ("Jazz", 0xD4A373), ("Folk & acoustic", 0x8AA162),
                ("Classical", 0xB8A07E), ("Country & americana", 0xC77D3A), ("Latin", 0xF06543), ("Metal", 0x707070),
            ])),
        ]
    }

    public func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage {
        try await wait("browse", endpoint.browseId + (endpoint.params ?? ""), page: true)
        let seed = PreviewCatalog.stableHash(endpoint.browseId + (endpoint.params ?? "")) % 101
        switch endpoint.browseId {
        case "FEmusic_charts":
            return try await charts(country: endpoint.params)
        case "FEmusic_new_releases":
            return BrowsePage(title: "New releases", shelves: [
                Shelf(id: "albums", title: "New albums & singles", layout: .grid,
                      items: catalog.albums(seed: 30, count: 8).map { .album($0) },
                      more: BrowseEndpoint(browseId: "FEmusic_new_releases_albums")),
                Shelf(id: "videos", title: "New music videos", layout: .carousel,
                      items: catalog.videos(seed: 31, count: 8).map { .song($0) }),
                Shelf(id: "fresh", title: "Fresh this week", layout: .list,
                      items: catalog.songs(seed: 32, count: 8).map { .song($0) }),
            ], continuation: "page|new|1")
        case "FEmusic_moods_and_genres_category":
            let title = endpoint.params ?? "Mood"
            return BrowsePage(title: title, shelves: [
                Shelf(id: "featured", title: "Featured playlists", layout: .grid,
                      items: catalog.playlists(seed: seed, count: 6).map { .playlist($0) }),
                Shelf(id: "community", title: "Community playlists", layout: .carousel,
                      items: catalog.playlists(seed: seed + 6, count: 8).map { .playlist($0) }),
                Shelf(id: "songs", title: "\(title) songs", layout: .list,
                      items: catalog.songs(seed: seed, count: 8).map { .song($0) }),
            ])
        default:
            if endpoint.browseId.hasPrefix("MPAD"), let artist = catalog.artistsById[String(endpoint.browseId.dropFirst(4))] {
                return BrowsePage(title: "\(artist.name): Albums", shelves: [
                    Shelf(id: "all", title: "Albums", layout: .grid,
                          items: catalog.albums(of: artist.id).map { .album($0) }),
                ])
            }
            let title = endpoint.browseId
                .replacingOccurrences(of: "FEmusic_", with: "")
                .replacingOccurrences(of: "_", with: " ")
                .capitalized
            return BrowsePage(title: title, shelves: [
                Shelf(id: "grid", title: "For you", layout: .grid,
                      items: catalog.playlists(seed: seed, count: 4).map { .playlist($0) }
                        + catalog.albums(seed: seed, count: 4).map { .album($0) }),
                Shelf(id: "list", title: "Songs", layout: .list,
                      items: catalog.songs(seed: seed, count: 6).map { .song($0) }),
            ], continuation: "page|\(seed)|1")
        }
    }

    public func browseContinuation(_ token: String) async throws -> BrowsePage {
        try await wait("browseContinuation", token, page: true)
        let parts = token.split(separator: "|").map(String.init)
        guard parts.count == 3, let page = Int(parts[2]) else {
            throw MusicAPIError.unreadable("Preview: bad browse token")
        }
        let seed = PreviewCatalog.stableHash(parts[1]) % 89 + page * 13
        if parts[0] == "home" {
            let pages: [[Shelf]] = [
                [
                    Shelf(id: "fresh-\(page)", title: "Fresh finds", layout: .carousel,
                          items: catalog.albums(seed: seed, count: 8).map { .album($0) }),
                    Shelf(id: "covers-\(page)", title: "Covers & remixes", layout: .list,
                          items: catalog.songs(seed: seed + 1, count: 4).map { .song($0) }),
                ],
                [
                    Shelf(id: "live-\(page)", title: "Live performances", layout: .carousel,
                          items: catalog.videos(seed: seed + 2, count: 8).map { .song($0) }),
                    Shelf(id: "long-\(page)", title: "Long listens", layout: .carousel,
                          items: catalog.playlists(seed: seed + 3, count: 8).map { .playlist($0) }),
                ],
            ]
            let shelves = pages[(page - 1) % pages.count]
            return BrowsePage(shelves: shelves, continuation: page < 2 ? "home|\(parts[1])|\(page + 1)" : nil)
        }
        return BrowsePage(shelves: [
            Shelf(id: "more-\(page)", title: "More to explore", layout: .carousel,
                  items: catalog.albums(seed: seed, count: 8).map { .album($0) }),
        ], continuation: nil)
    }

    private func mixed(seed: Int, count: Int) -> [MusicItem] {
        (0..<count).map { index -> MusicItem in
            switch index % 4 {
            case 0: .album(catalog.albums(seed: seed + index, count: 1)[0])
            case 1: .artist(catalog.artists(seed: seed + index, count: 1)[0])
            case 2: .playlist(catalog.playlists(seed: seed + index, count: 1)[0])
            default: .song(catalog.songs(seed: seed + index, count: 1)[0])
            }
        }
    }

    // MARK: Pages

    public func album(id: String) async throws -> AlbumPage {
        try await wait("album", id, page: true)
        let album = catalog.albumsById[id] ?? catalog.albums[PreviewCatalog.stableHash(id) % catalog.albums.count]
        let tracks = catalog.tracksByAlbum[album.id] ?? []
        let minutes = Int(tracks.compactMap(\.duration).reduce(0, +) / 60)
        let artist = album.artists.first
        let others = artist?.id.map { catalog.albums(of: $0).filter { $0.id != album.id } } ?? []
        return AlbumPage(
            album: album,
            description: "\(album.title) is the \(album.year ?? "latest") \((album.typeText ?? "album").lowercased()) by \(artist?.name ?? "the artist"). "
                + "Recorded over a long winter in a converted boathouse, it trades the polish of earlier records for "
                + "warm tape hiss, close-miked vocals and songs that unfold slowly. Critics called it a quiet triumph; "
                + "fans called it the soundtrack to every late drive home.",
            statsText: "\(tracks.count) song\(tracks.count == 1 ? "" : "s") • \(minutes) minutes",
            tracks: tracks,
            shelves: [
                Shelf(id: "more", title: "More from \(artist?.name ?? "this artist")", layout: .carousel,
                      items: others.map { .album($0) },
                      more: artist?.id.map { BrowseEndpoint(browseId: $0, pageType: .artist) }),
                Shelf(id: "releases", title: "Releases for you", layout: .carousel,
                      items: catalog.albums(seed: PreviewCatalog.stableHash(id) % 40, count: 8).map { .album($0) }),
            ].filter { !$0.items.isEmpty },
            likeStatus: .indifferent,
            libraryTokens: LibraryTokens(add: "add-\(album.id)", remove: "remove-\(album.id)", isInLibrary: false))
    }

    public func artist(id: String) async throws -> ArtistPage {
        try await wait("artist", id, page: true)
        let artist = catalog.artistsById[id] ?? catalog.artists[PreviewCatalog.stableHash(id) % catalog.artists.count]
        let albums = catalog.albums(of: artist.id)
        let seed = PreviewCatalog.stableHash(artist.id) % 60
        return ArtistPage(
            artist: artist,
            banner: PreviewCatalog.banner("banner" + artist.id),
            description: "\(artist.name) make bright, restless music somewhere between bedroom pop and festival "
                + "indie. Formed in a shared flat above a laundrette, they built a following one late-night upload at "
                + "a time before their debut found its way onto playlists around the world. Their live shows are "
                + "loud, sweaty and joyful, and their records reward headphones.",
            subscribersText: artist.detailText,
            monthlyListenersText: PreviewCatalog.countText(seed + 9, unit: "monthly audience"),
            isSubscribed: false,
            shuffle: WatchEndpoint(playlistId: "RDAO" + artist.id),
            radio: WatchEndpoint(playlistId: "RDEM" + artist.id),
            shelves: [
                Shelf(id: "top", title: "Top songs", layout: .list,
                      items: catalog.songs(of: artist.id).prefix(10).map { .song($0) },
                      more: BrowseEndpoint(browseId: "PLbb_top" + artist.id, pageType: .playlist)),
                Shelf(id: "albums", title: "Albums", layout: .carousel,
                      items: albums.filter { $0.typeText == "Album" }.map { .album($0) },
                      more: BrowseEndpoint(browseId: "MPAD" + artist.id)),
                Shelf(id: "singles", title: "Singles & EPs", layout: .carousel,
                      items: albums.filter { $0.typeText != "Album" }.map { .album($0) }
                        + catalog.albums(seed: seed, count: 3).map { .album($0) }),
                Shelf(id: "videos", title: "Videos", layout: .carousel,
                      items: catalog.videos(of: artist.id).map { .song($0) }),
                Shelf(id: "featured", title: "Featured on", layout: .carousel,
                      items: catalog.playlists(seed: seed, count: 5).map { .playlist($0) }),
                Shelf(id: "similar", title: "Fans might also like", layout: .carousel,
                      items: catalog.artists(seed: seed + 1, count: 8).filter { $0.id != artist.id }
                        .map { .artist($0) }),
            ].filter { !$0.items.isEmpty })
    }

    public func playlist(id: String) async throws -> PlaylistPage {
        try await wait("playlist", id, page: true)
        let bare = id.hasPrefix("VL") ? String(id.dropFirst(2)) : id
        let all = playlistSongs(bare)
        let item = playlistItem(bare)
        let minutes = Int(all.compactMap(\.duration).reduce(0, +) / 60)
        let isTop = bare.hasPrefix("PLbb_top")
        return PlaylistPage(
            playlist: item,
            description: isTop ? nil
                : "Handpicked tracks for exactly this mood, refreshed every week. Press play and let it run.",
            authorName: bare == "LM" ? "You" : item.detailText?.split(separator: "•").dropFirst().first
                .map { $0.trimmingCharacters(in: .whitespaces) } ?? "BoomBoom",
            authorArtwork: PreviewCatalog.square("author"),
            statsText: "\(all.count) songs • \(minutes / 60) hours, \(minutes % 60) minutes",
            tracks: Array(all.prefix(25)),
            continuation: all.count > 25 ? "pl|\(bare)|1" : nil,
            isEditable: bare == "LM",
            likeStatus: .indifferent,
            shelves: isTop ? [] : [
                Shelf(id: "related", title: "Related playlists", layout: .carousel,
                      items: catalog.playlists(seed: PreviewCatalog.stableHash(bare) % 20, count: 6)
                        .filter { $0.id != bare }.map { .playlist($0) }),
            ])
    }

    public func playlistContinuation(_ token: String) async throws -> SongsContinuation {
        try await wait("playlistContinuation", token, page: true)
        let parts = token.split(separator: "|").map(String.init)
        guard parts.count == 3, let page = Int(parts[2]) else {
            throw MusicAPIError.unreadable("Preview: bad playlist token")
        }
        let all = playlistSongs(parts[1])
        let start = page * 25
        let slice = Array(all.dropFirst(start).prefix(25))
        let next = start + 25 < all.count ? "pl|\(parts[1])|\(page + 1)" : nil
        return SongsContinuation(songs: slice, continuation: next)
    }

    private func playlistItem(_ id: String) -> PlaylistItem {
        if let known = catalog.playlistsById[id] { return known }
        if id == "LM" {
            return PlaylistItem(id: "LM", title: "Liked music", detailText: "Auto playlist",
                                artwork: PreviewCatalog.square("liked"))
        }
        if id.hasPrefix("PLbb_top"), let artist = catalog.artistsById[String(id.dropFirst(8))] {
            return PlaylistItem(id: id, title: "\(artist.name): Top songs", detailText: "Playlist • BoomBoom",
                                artwork: artist.artwork)
        }
        if id == "PLbb_topsongs" {
            return PlaylistItem(id: id, title: "Top 100 songs", detailText: "Chart • BoomBoom",
                                artwork: PreviewCatalog.square("top100"))
        }
        return PlaylistItem(id: id, title: "Untitled playlist", detailText: "Playlist",
                            artwork: PreviewCatalog.square(id))
    }

    private func playlistSongs(_ id: String) -> [Song] {
        if id.hasPrefix("PLbb_top"), let artist = catalog.artistsById[String(id.dropFirst(8))] {
            return catalog.songs(of: artist.id)
        }
        let seed = PreviewCatalog.stableHash(id) % 300
        var songs = catalog.songs(seed: seed, count: id == "LM" ? 40 : 70)
        for index in songs.indices { songs[index].setVideoId = "set-\(id)-\(index)" }
        // Two greyed-out entries, as real playlists have.
        if songs.count > 12 { songs[11].isAvailable = false }
        return songs
    }

    // MARK: Playing

    public func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue {
        try await wait("watchQueue", (endpoint.videoId ?? "") + "|" + (endpoint.playlistId ?? ""))
        var songs: [Song]
        var current = 0
        let playlistId = endpoint.playlistId
        if let playlistId, !playlistId.hasPrefix("RD") || playlistId.hasPrefix("RDTMAK") {
            songs = playlistSongs(playlistId)
        } else if let playlistId, playlistId.hasPrefix("RDAMPLOLAK5uy_") {
            let albumSlug = String(playlistId.dropFirst("RDAMPLOLAK5uy_".count))
            songs = catalog.tracksByAlbum["MPREb_" + albumSlug] ?? catalog.songs(seed: 3, count: 25)
            songs += catalog.songs(seed: albumSlug.count, count: 15)
        } else if let playlistId, playlistId.hasPrefix("RDAO") || playlistId.hasPrefix("RDEM") {
            let artistId = String(playlistId.dropFirst(4))
            let own = catalog.songs(of: artistId)
            songs = playlistId.hasPrefix("RDAO") ? own.shuffled() : own + catalog.songs(seed: artistId.count, count: 15)
        } else {
            let seed = PreviewCatalog.stableHash(endpoint.videoId ?? playlistId ?? "radio")
            songs = catalog.songs(seed: seed, count: 25)
        }
        if let videoId = endpoint.videoId {
            if let index = songs.firstIndex(where: { $0.videoId == videoId }) {
                current = index
            } else if let song = catalog.songsById[videoId] {
                songs.insert(song, at: 0)
                current = 0
            }
        } else if let index = endpoint.index, songs.indices.contains(index) {
            current = index
        }
        let lead = songs.indices.contains(current) ? songs[current].videoId : ""
        return WatchQueue(
            songs: songs, currentIndex: current, playlistId: playlistId ?? "RDAMVM" + lead,
            continuation: "wq|\(PreviewCatalog.stableHash(lead) % 50)|1",
            lyricsBrowseId: "MPLYt_" + lead, relatedBrowseId: "MPTRt_" + lead)
    }

    public func watchQueueContinuation(_ token: String) async throws -> SongsContinuation {
        try await wait("watchQueueContinuation", token)
        let parts = token.split(separator: "|").map(String.init)
        guard parts.count == 3, let seed = Int(parts[1]), let page = Int(parts[2]) else {
            throw MusicAPIError.unreadable("Preview: bad queue token")
        }
        return SongsContinuation(
            songs: catalog.songs(seed: seed + page * 11, count: 20),
            continuation: page < 5 ? "wq|\(seed)|\(page + 1)" : nil)
    }

    public func lyrics(browseId: String) async throws -> Lyrics {
        try await wait("lyrics", browseId)
        let verses = [
            "Streetlights hum a song I almost know", "Every window glowing soft and low",
            "I keep your number written on my hand", "Like a map to somewhere I can't stand",
            "", "And oh, the night is wide awake", "Every promise that we didn't make",
            "Turn it up until the windows shake", "We were young enough to never break",
            "", "Paper boats along the gutter stream", "Half a summer stitched into a dream",
            "You said the stars were only satellites", "But they still looked like a thousand lights",
            "", "And oh, the night is wide awake", "Every promise that we didn't make",
            "Turn it up until the windows shake", "We were young enough to never break",
            "", "So drive me home the long way round", "Keep the radio and lose the sound",
            "We'll be fine, we'll be fine", "Just hold on till the morning line",
        ]
        var time: TimeInterval = 8
        let lines = verses.map { text -> Lyrics.Line in
            defer { time += text.isEmpty ? 2 : 4.2 }
            return Lyrics.Line(text: text, start: time)
        }
        return Lyrics(lines: lines, source: "Source: Preview lyrics")
    }

    public func related(browseId: String) async throws -> [Shelf] {
        try await wait("related", browseId)
        let seed = PreviewCatalog.stableHash(browseId) % 70
        let artist = catalog.artists[seed % catalog.artists.count]
        return [
            Shelf(id: "like", title: "You might also like", layout: .list,
                  items: catalog.songs(seed: seed, count: 6).map { .song($0) }),
            Shelf(id: "playlists", title: "Recommended playlists", layout: .carousel,
                  items: catalog.playlists(seed: seed, count: 6).map { .playlist($0) }),
            Shelf(id: "artists", title: "Similar artists", layout: .carousel,
                  items: catalog.artists(seed: seed + 1, count: 6).map { .artist($0) }),
            Shelf(id: "about", title: "About the artist", layout: .text,
                  text: "\(artist.name) write songs for long drives and longer nights. Their latest record was made "
                    + "in a week, live to tape, with the windows open."),
        ]
    }

    // MARK: Library

    public func account() async throws -> Account? {
        try await wait("account")
        return signedIn ? Self.previewAccount : nil
    }

    public func library(_ section: LibrarySection) async throws -> BrowsePage {
        try await wait("library", section.rawValue, page: true)
        try requireSignIn()
        let items: [MusicItem]
        switch section {
        case .playlists:
            items = [.playlist(playlistItem("LM"))] + catalog.playlists(seed: 2, count: 8).map { .playlist($0) }
        case .songs: items = catalog.songs(seed: 77, count: 30).map { .song($0) }
        case .albums: items = catalog.albums(seed: 5, count: 12).map { .album($0) }
        case .artists, .subscriptions: items = catalog.artists(seed: 3, count: 10).map { .artist($0) }
        }
        return BrowsePage(title: section.title, shelves: [
            Shelf(id: "library-\(section.rawValue)", title: section.title,
                  layout: section == .albums ? .grid : .list, items: items),
        ])
    }

    public func likedSongs() async throws -> PlaylistPage {
        try requireSignIn()
        return try await playlist(id: "LM")
    }

    public func history() async throws -> BrowsePage {
        try await wait("history", page: true)
        try requireSignIn()
        return BrowsePage(title: "History", shelves: [
            Shelf(id: "today", title: "Today", layout: .list,
                  items: catalog.songs(seed: 91, count: 6).map { .song($0) }),
            Shelf(id: "yesterday", title: "Yesterday", layout: .list,
                  items: catalog.songs(seed: 92, count: 8).map { .song($0) }),
            Shelf(id: "week", title: "This week", layout: .list,
                  items: catalog.songs(seed: 93, count: 10).map { .song($0) }),
        ])
    }

    public func rate(videoId: String, _ status: LikeStatus) async throws {
        try await wait("rate", "\(videoId)|\(status.rawValue)")
        try requireSignIn()
    }

    public func ratePlaylist(id: String, _ status: LikeStatus) async throws {
        try await wait("ratePlaylist", id)
        try requireSignIn()
    }

    public func subscribe(channelId: String, _ subscribed: Bool) async throws {
        try await wait("subscribe", "\(channelId)|\(subscribed)")
        try requireSignIn()
    }

    public func sendLibraryFeedback(token: String) async throws {
        try await wait("libraryFeedback", token)
        try requireSignIn()
    }

    public func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String])
        async throws -> String
    {
        try await wait("createPlaylist", title)
        try requireSignIn()
        return "PLbb_new" + PreviewCatalog.slug(title)
    }

    public func deletePlaylist(id: String) async throws {
        try await wait("deletePlaylist", id)
        try requireSignIn()
    }

    public func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?)
        async throws
    {
        try await wait("editPlaylist", id)
        try requireSignIn()
    }

    public func addToPlaylist(id: String, videoIds: [String]) async throws {
        try await wait("addToPlaylist", id)
        try requireSignIn()
    }

    public func removeFromPlaylist(id: String, songs: [Song]) async throws {
        try await wait("removeFromPlaylist", id)
        try requireSignIn()
    }
}

/// The calls a `PreviewMusicAPI` received, in order, safe to read from any task.
final class CallLog: Sendable {
    private let entries = Mutex<[String]>([])

    func append(_ entry: String) {
        entries.withLock { $0.append(entry) }
    }

    var all: [String] { entries.withLock { $0 } }

    func count(prefix: String) -> Int {
        entries.withLock { $0.filter { $0.hasPrefix(prefix) }.count }
    }
}
