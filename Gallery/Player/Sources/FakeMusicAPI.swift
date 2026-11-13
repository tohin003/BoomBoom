import BoomModels
import Foundation

/// Canned, invented music for the gallery: a long queue, timed and plain lyrics, related shelves.
struct FakeMusicAPI: MusicAPI {
    static let songs: [Song] = makeSongs()

    private static let firstWords = [
        "Neon", "Paper", "Velvet", "Static", "Golden", "Hollow", "Midnight", "Copper", "Silver", "Quiet", "Electric",
        "Lonely", "Crystal", "Faded", "Wild", "Northern", "Broken", "Sugar", "Distant", "Burning",
    ]
    private static let secondWords = [
        "Harbour", "Lanterns", "Satellites", "Rivers", "Avenue", "Echoes", "Gardens", "Signals", "Tides", "Mirrors",
        "Horizon", "Weather", "Kites", "Summer", "Engines",
    ]
    private static let artists: [ArtistRef] = [
        ArtistRef(name: "Mira Vale", id: "UCmira"), ArtistRef(name: "The Lowlight Club", id: "UClowlight"),
        ArtistRef(name: "Kasper Rhodes", id: "UCkasper"), ArtistRef(name: "Juniper & Ash", id: "UCjuniper"),
        ArtistRef(name: "Oda Finch", id: "UCoda"), ArtistRef(name: "Polar Arcade", id: "UCpolar"),
        ArtistRef(name: "Sable Monroe", id: "UCsable"), ArtistRef(name: "Tidewater"),
    ]

    static func artwork(_ seed: String, width: Int = 600, height: Int = 600) -> Artwork {
        CoverArt.artwork(seed, width: width, height: height)
    }

    private static func makeSongs() -> [Song] {
        var songs: [Song] = [
            Song(
                videoId: "boom0001", title: "Lanterns Over the Harbour",
                artists: [artists[0]], album: AlbumRef(name: "Harbour Lights", id: "MPREb_harbour"),
                duration: 214, artwork: artwork("boom0001"), likeStatus: .like),
            Song(
                videoId: "boom0002",
                title: "Everything We Said at the Edge of the Northern Sea (Extended Night Version)",
                artists: [artists[1], artists[4]], album: AlbumRef(name: "Northern Sea", id: "MPREb_north"),
                duration: 263, artwork: artwork("boom0002")),
            Song(
                videoId: "boom0003", title: "Paper Satellites", artists: [artists[2]],
                album: AlbumRef(name: "Orbit", id: "MPREb_orbit"), duration: 187, artwork: artwork("boom0003"),
                isExplicit: true),
            Song(
                videoId: "boom0004", title: "Copper Weather (Live Video)", artists: [artists[3]], duration: 241,
                artwork: artwork("boom0004", width: 1280, height: 720), kind: .video, playsText: "2.1M views"),
            Song(
                videoId: "boom0005", title: "Sugar Tides", artists: [artists[7]], duration: 198,
                artwork: .empty),
        ]
        for index in 6...220 {
            let first = firstWords[(index * 7) % firstWords.count]
            let second = secondWords[(index * 11) % secondWords.count]
            let artist = artists[(index * 3) % artists.count]
            songs.append(Song(
                videoId: String(format: "boom%04d", index), title: "\(first) \(second)", artists: [artist],
                album: AlbumRef(name: "\(second) Sessions", id: "MPREb_\(index)"),
                duration: TimeInterval(150 + (index * 37) % 140), artwork: artwork(String(format: "boom%04d", index))))
        }
        return songs
    }

    // MARK: Playing

    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue {
        let videoId = endpoint.videoId ?? Self.songs[0].videoId
        let start = Self.songs.firstIndex { $0.videoId == videoId } ?? 0
        let radio = Array(Self.songs[start...].prefix(40))
        let number = Int(videoId.dropFirst(4)) ?? 0
        return WatchQueue(
            songs: radio, currentIndex: 0, playlistId: "RDAMVM\(videoId)", continuation: nil,
            lyricsBrowseId: number % 5 == 0 ? nil : "lyrics:\(videoId)", relatedBrowseId: "related:\(videoId)")
    }

    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation {
        SongsContinuation(songs: [], continuation: nil)
    }

    func lyrics(browseId: String) async throws -> Lyrics {
        try await Task.sleep(for: .milliseconds(300))
        let videoId = String(browseId.dropFirst("lyrics:".count))
        let number = Int(videoId.dropFirst(4)) ?? 0
        let song = Self.songs.first { $0.videoId == videoId }
        let duration = song?.duration ?? 200
        let verses = [
            "The lanterns hum along the water", "Every window holds a different light",
            "I counted boats until the morning", "And you were humming something out of sight", "",
            "Hold on, hold on to the harbour", "Hold on until the tide lets go",
            "We were paper in the weather", "Folding into something we don't know", "",
            "Static on the late-night radio", "Satellites are spelling out your name",
            "I'll keep the porch light burning", "Till the evening comes around again",
        ]
        if number % 2 == 1 {
            return Lyrics(lines: verses.map { Lyrics.Line(text: $0) }, source: "Source: Invented Lyrics Co.")
        }
        var lines: [Lyrics.Line] = []
        var time: TimeInterval = 6
        var verse = 0
        while time < duration - 4 {
            lines.append(Lyrics.Line(text: verses[verse % verses.count], start: time))
            time += verses[verse % verses.count].isEmpty ? 3 : 3.6
            verse += 1
        }
        return Lyrics(lines: lines, source: "Source: Invented Lyrics Co.")
    }

    func related(browseId: String) async throws -> [Shelf] {
        try await Task.sleep(for: .milliseconds(400))
        let picks = Array(Self.songs[20..<36]).map(MusicItem.song)
        let playlists = (1...6).map { index in
            MusicItem.playlist(PlaylistItem(
                id: "PLmix\(index)", title: ["Night Drive", "Focus Flow", "Rainy Window", "Weekend Lift",
                                             "Slow Burn", "Deep Cuts"][index - 1],
                detailText: "Playlist • BoomBoom", artwork: Self.artwork("pl\(index)")))
        }
        let artistsShelf = Self.artists.compactMap { ref -> MusicItem? in
            guard let id = ref.id else { return nil }
            return .artist(ArtistItem(id: id, name: ref.name, artwork: Self.artwork("ar\(id)"),
                                      detailText: "1.2M monthly audience"))
        }
        let albums = (1...6).map { index in
            MusicItem.album(AlbumItem(
                id: "MPREb_rel\(index)", title: "Album Number \(index)", artists: [Self.artists[0]],
                year: "202\(index % 6)", typeText: index % 3 == 0 ? "Single" : "Album",
                artwork: Self.artwork("al\(index)")))
        }
        return [
            Shelf(id: "quick", title: "You might also like", layout: .columns, items: picks),
            Shelf(id: "playlists", title: "Recommended playlists", layout: .carousel, items: playlists),
            Shelf(id: "artists", title: "Similar artists", layout: .carousel, items: artistsShelf),
            Shelf(id: "albums", title: "More from Mira Vale", layout: .carousel, items: albums),
            Shelf(
                id: "about", title: "About the artist", layout: .text,
                text: "Mira Vale writes small songs about big harbours. An invented artist for this gallery."),
        ]
    }

    // MARK: Everything else the gallery does not use

    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] { [] }
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults { SearchResults(query: query) }
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation {
        SongsOrItemsContinuation(items: [], continuation: nil)
    }
    func home(chip: Chip?) async throws -> BrowsePage { BrowsePage() }
    func explore() async throws -> BrowsePage { BrowsePage() }
    func charts(country: String?) async throws -> BrowsePage { BrowsePage() }
    func moodsAndGenres() async throws -> [ChipGroup] { [] }
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage { BrowsePage() }
    func browseContinuation(_ token: String) async throws -> BrowsePage { BrowsePage() }
    func album(id: String) async throws -> AlbumPage { throw MusicAPIError.unreadable("not in the gallery") }
    func artist(id: String) async throws -> ArtistPage { throw MusicAPIError.unreadable("not in the gallery") }
    func playlist(id: String) async throws -> PlaylistPage { throw MusicAPIError.unreadable("not in the gallery") }
    func playlistContinuation(_ token: String) async throws -> SongsContinuation {
        SongsContinuation(songs: [], continuation: nil)
    }
    func account() async throws -> Account? { nil }
    func library(_ section: LibrarySection) async throws -> BrowsePage { BrowsePage() }
    func likedSongs() async throws -> PlaylistPage { throw MusicAPIError.signedOut }
    func history() async throws -> BrowsePage { BrowsePage() }
    func rate(videoId: String, _ status: LikeStatus) async throws {}
    func ratePlaylist(id: String, _ status: LikeStatus) async throws {}
    func subscribe(channelId: String, _ subscribed: Bool) async throws {}
    func sendLibraryFeedback(token: String) async throws {}
    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String])
        async throws -> String { "PLnew" }
    func deletePlaylist(id: String) async throws {}
    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws {}
    func addToPlaylist(id: String, videoIds: [String]) async throws {}
    func removeFromPlaylist(id: String, songs: [Song]) async throws {}
}
