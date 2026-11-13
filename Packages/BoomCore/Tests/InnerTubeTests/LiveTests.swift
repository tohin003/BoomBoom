import BoomModels
import Foundation
import Synchronization
import Testing
@testable import InnerTube

// End-to-end calls against the real service, signed out. Off by default; run with `BOOM_LIVE=1 swift test`.
// Answers change from day to day, so these check shapes and sane values, not exact titles.

let isLive = ProcessInfo.processInfo.environment["BOOM_LIVE"] == "1"

enum Live {
    static let provider = BootstrapSessionProvider(language: "en", region: "US")
    static let api = InnerTubeClient(sessionProvider: provider, urlSession: RecordingProtocol.session())
}

/// With `BOOM_LIVE_RECORD=<folder>`, passes every request through to the network and writes each answer there,
/// numbered, so an answer that parsed oddly can be read afterwards. Without it, a plain session.
final class RecordingProtocol: URLProtocol, @unchecked Sendable {
    static let folder = ProcessInfo.processInfo.environment["BOOM_LIVE_RECORD"].map { URL(fileURLWithPath: $0) }
    static let counter = Mutex(0)
    static let upstream = URLSession(configuration: .ephemeral)

    static func session() -> URLSession {
        guard folder != nil else { return .shared }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RecordingProtocol.self]
        return URLSession(configuration: configuration)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    private var upstreamTask: URLSessionDataTask?

    override func startLoading() {
        var outgoing = request
        if outgoing.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while true {
                let count = stream.read(&buffer, maxLength: buffer.count)
                guard count > 0 else { break }
                data.append(buffer, count: count)
            }
            stream.close()
            outgoing.httpBody = data
        }
        let request = outgoing
        let task = Self.upstream.dataTask(with: request) { [self] data, response, error in
            guard let data, let response else {
                client?.urlProtocol(self, didFailWithError: error ?? URLError(.unknown))
                return
            }
            if let folder = Self.folder {
                let number = Self.counter.withLock { value -> Int in value += 1; return value }
                let body = (try? JSON(data: request.httpBody ?? Data())) ?? .null
                let what = [body["browseId"].string, body["query"].string, body["videoId"].string,
                    body["continuation"].string.map { _ in "cont" }].compactMap { $0 }.joined(separator: "_")
                let name = String(format: "%03d_", number) + (request.url?.lastPathComponent ?? "x") + "_"
                    + what.prefix(40).replacingOccurrences(of: "/", with: "-") + ".json"
                try? data.write(to: folder.appendingPathComponent(name))
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
        upstreamTask = task
        task.resume()
    }

    override func stopLoading() { upstreamTask?.cancel() }
}

@Suite("Live", .enabled(if: isLive, "set BOOM_LIVE=1 to call the real service"), .serialized)
struct LiveTests {
    let api = Live.api

    // MARK: Session

    @Test func bootstrapReadsTheStartPage() async throws {
        let session = try await Live.provider.currentSession()
        #expect(session.clientVersion.hasPrefix("1.2"))
        #expect(session.clientVersion != BootstrapSessionProvider.fallbackClientVersion || session.visitorData != nil)
        #expect(session.visitorData?.isEmpty == false)
        #expect(!session.isSignedIn)
    }

    // MARK: Search

    @Test func suggestions() async throws {
        let suggestions = try await api.searchSuggestions("cold")
        let queries = suggestions.compactMap { if case .query(let q) = $0 { q } else { nil } }
        #expect(queries.count >= 3)
        #expect(queries.allSatisfy { $0.text.lowercased().contains("cold") })
        // The typed part is plain, the rest bold.
        #expect(queries.contains { query in
            query.runs.contains { $0.isBold } && query.runs.map(\.text).joined() == query.text
        })
        #expect(queries.allSatisfy { !$0.isFromHistory })
        #expect(try await api.searchSuggestions("") == [])
        #expect(try await api.searchSuggestions("   ") == [])
    }

    @Test func unfilteredSearch() async throws {
        let results = try await api.search("coldplay yellow", filter: nil)
        #expect(results.filter == nil)
        #expect(results.continuation == nil)
        let top = try #require(results.shelves.first)
        #expect(top.layout == .topResult)
        #expect(!top.items.isEmpty)
        let lists = results.shelves.dropFirst()
        #expect(lists.count >= 3)
        #expect(lists.allSatisfy { $0.layout == .list && !$0.items.isEmpty && !$0.title.isEmpty })
        // Each kind's shelf says which filter its "Show all" applies.
        #expect(lists.contains { $0.moreFilter == .songs })
        // Unfiltered rows carry no duration ("Song • Coldplay", "2.4B plays"); filtered ones do.
        let songs = try #require(lists.first { $0.moreFilter == .songs })
        for item in songs.items {
            let song = try #require(item.song)
            #expect(song.kind == .song)
            #expect(song.playsText != nil)
            #expect(!song.artists.isEmpty)
        }
        #expect(songs.items.contains { $0.song?.artists.contains { $0.id?.hasPrefix("UC") == true } == true })
        if let videos = lists.first(where: { $0.moreFilter == .videos }) {
            #expect(videos.items.allSatisfy { $0.song?.kind == .video })
        }
    }

    @Test func correctedQuery() async throws {
        let results = try await api.search("coldpaly yelow", filter: nil)
        #expect(results.correctedQuery == "coldplay yellow")
        #expect(results.shelves.first?.layout == .topResult)
    }

    @Test(arguments: [SearchFilter.songs, .albums, .artists, .videos, .communityPlaylists])
    func filteredSearch(filter: SearchFilter) async throws {
        // "coldplay" finds only a handful of artists, without more to load.
        let results = try await api.search(filter == .artists ? "john" : "coldplay", filter: filter)
        #expect(results.filter == filter)
        #expect(results.shelves.count == 1)
        let shelf = try #require(results.shelves.first)
        #expect(shelf.layout == .list)
        #expect(shelf.items.count >= 10)
        try Self.check(shelf.items, are: filter)
        // Artist results sometimes come as one page of about 20 without more to load (seen live, not a parse miss).
        if filter == .artists, results.continuation == nil { return }
        let token = try #require(results.continuation, "a filtered search scrolls on")
        let more = try await api.searchContinuation(token)
        #expect(more.items.count >= 5)
        try Self.check(more.items, are: filter)
        #expect(more.continuation != nil)
    }

    @Test(arguments: [SearchFilter.featuredPlaylists, .podcasts, .episodes, .profiles])
    func otherFilters(filter: SearchFilter) async throws {
        let results = try await api.search("coldplay", filter: filter)
        #expect(results.filter == filter)
        let items = try #require(results.shelves.first?.items)
        #expect(items.count >= 3)
        for item in items {
            switch filter {
            case .featuredPlaylists, .podcasts: #expect(item.playlist != nil, "\(item.title)")
            case .episodes: #expect(item.song?.kind == .episode, "\(item.title)")
            case .profiles: #expect(item.artist?.id.hasPrefix("UC") == true, "\(item.title)")
            default: break
            }
        }
    }

    @Test func podcastOpens() async throws {
        let results = try await api.search("history podcast", filter: .podcasts)
        let show = try #require(results.shelves.first?.items.compactMap(\.playlist).first)
        // As the router opens a playlist item, and as a `.podcast` link (`MPSP…`).
        let page = try await api.playlist(id: show.id)
        #expect(!page.tracks.isEmpty)
        #expect(page.tracks.allSatisfy { $0.kind == .episode })
        let browsed = try await api.browse(BrowseEndpoint(browseId: "MPSP" + show.id, pageType: .podcast))
        #expect(browsed.shelves.contains { shelf in shelf.items.contains { $0.song?.kind == .episode } })
        #expect(browsed.title != nil)
    }

    static func check(_ items: [MusicItem], are filter: SearchFilter) throws {
        for item in items {
            switch filter {
            case .songs:
                let song = try #require(item.song, "\(item.title) is not a song")
                #expect(song.kind == .song)
                #expect(song.duration ?? 0 > 0)
                #expect(!song.artists.isEmpty)
                #expect(!song.artwork.isEmpty)
            case .videos:
                let song = try #require(item.song, "\(item.title) is not a video")
                #expect(song.kind == .video)
                #expect(song.duration ?? 0 > 0)
                #expect(song.artwork.aspectRatio > 1.2)
            case .albums:
                let album = try #require(item.album, "\(item.title) is not an album")
                #expect(album.id.hasPrefix("MPREb_"))
                #expect(album.typeText != nil)
                #expect(!album.artists.isEmpty)
            case .artists:
                let artist = try #require(item.artist, "\(item.title) is not an artist")
                #expect(artist.id.hasPrefix("UC"))
            case .communityPlaylists:
                let playlist = try #require(item.playlist, "\(item.title) is not a playlist")
                #expect(!playlist.id.hasPrefix("VL"))
                #expect(!playlist.id.isEmpty)
            default:
                break
            }
        }
    }

    // MARK: Browse

    @Test func home() async throws {
        let page = try await api.home(chip: nil)
        #expect(page.shelves.count >= 2)
        #expect(page.shelves.allSatisfy { !$0.items.isEmpty })
        if let chip = page.chips.first(where: { !$0.isSelected && $0.endpoint != nil }) {
            let filtered = try await api.home(chip: chip)
            #expect(!filtered.shelves.isEmpty)
        }
        let token = try #require(page.continuation, "Home scrolls on")
        let more = try await api.browseContinuation(token)
        #expect(!more.shelves.isEmpty)
    }

    @Test func explore() async throws {
        let page = try await api.explore()
        #expect(page.shelves.count >= 2)
        #expect(page.shelves.contains { shelf in shelf.items.contains { $0.album != nil } })
    }

    @Test(arguments: [String?.none, "US", "IN"])
    func charts(country: String?) async throws {
        let page = try await api.charts(country: country)
        #expect(page.shelves.count >= 1)
        #expect(page.shelves.contains { !$0.items.isEmpty })
    }

    @Test func moodsAndGenres() async throws {
        let groups = try await api.moodsAndGenres()
        #expect(groups.count >= 2)
        let chips = groups.flatMap(\.chips)
        #expect(chips.count >= 20)
        #expect(chips.allSatisfy { $0.endpoint?.params != nil })
        #expect(chips.contains { $0.colorHex != nil })
        let mood = try #require(chips.first?.endpoint)
        let page = try await api.browse(mood)
        #expect(!page.shelves.isEmpty)
        #expect(page.shelves.contains { shelf in shelf.items.contains { $0.playlist != nil } })
    }

    @Test func album() async throws {
        let search = try await api.search("coldplay parachutes", filter: .albums)
        let found = try #require(search.shelves.first?.items.compactMap(\.album).first)
        let page = try await api.album(id: found.id)
        #expect(page.album.id == found.id)
        #expect(!page.album.title.isEmpty)
        #expect(page.album.playlistId?.hasPrefix("OLAK5uy_") == true)
        #expect(page.album.artists.contains { $0.id?.hasPrefix("UC") == true })
        #expect(!page.album.artwork.isEmpty)
        #expect(page.statsText != nil)
        #expect(page.tracks.count >= 5)
        for track in page.tracks {
            #expect(track.duration ?? 0 > 0, "\(track.title) has no duration")
            #expect(!track.artists.isEmpty)
            #expect(track.album?.id == found.id)
            // Albums list some tracks as their official music video (`MUSIC_VIDEO_TYPE_OMV`).
            #expect(track.kind == .song || track.kind == .video)
        }
        #expect(page.tracks.contains { $0.kind == .song })
    }

    @Test func artistAndItsLinks() async throws {
        let search = try await api.search("coldplay", filter: .artists)
        let found = try #require(search.shelves.first?.items.compactMap(\.artist).first)
        let page = try await api.artist(id: found.id)
        #expect(page.artist.id == found.id)
        #expect(page.artist.name.lowercased().contains("coldplay"))
        #expect(!page.banner.isEmpty)
        #expect(page.shuffle != nil || page.radio != nil)
        #expect(page.shelves.count >= 3)
        let top = try #require(page.shelves.first { $0.layout == .list })
        #expect(top.items.allSatisfy { $0.song != nil })

        // Every link a parsed endpoint carries opens with the call its page type names.
        let more = try #require(top.more, "the top songs shelf links a playlist")
        #expect(more.pageType == .playlist)
        let playlist = try await api.playlist(id: more.browseId)
        #expect(playlist.tracks.count >= 10)
        #expect(!playlist.playlist.id.hasPrefix("VL"))
        let viaBrowse = try await api.browse(more)
        #expect(!viaBrowse.shelves.isEmpty)

        if let albums = page.shelves.first(where: { shelf in shelf.items.contains { $0.album != nil } }) {
            let album = try #require(albums.items.compactMap(\.album).first)
            let albumPage = try await api.album(id: album.id)
            #expect(!albumPage.tracks.isEmpty)
            if let all = albums.more {
                let allPage = try await api.browse(all)
                #expect(allPage.shelves.contains { shelf in shelf.items.contains { $0.album != nil } })
            }
        }
        if let similar = page.shelves.first(where: { shelf in shelf.items.allSatisfy { $0.artist != nil } }),
            let other = similar.items.compactMap(\.artist).first
        {
            let otherPage = try await api.artist(id: other.id)
            #expect(!otherPage.shelves.isEmpty)
        }
        if let radio = page.radio ?? page.shuffle {
            let queue = try await api.watchQueue(radio)
            #expect(queue.songs.count >= 5)
        }
    }

    /// Opens an endpoint the way the app's router does: albums, artists and playlists by their own call with
    /// `endpoint.browseId`, everything else with `browse`. Returns whether the page had content.
    func open(_ endpoint: BrowseEndpoint) async throws -> Bool {
        switch endpoint.pageType {
        case .album: return try await !api.album(id: endpoint.browseId).tracks.isEmpty
        case .artist, .userChannel: return try await !api.artist(id: endpoint.browseId).shelves.isEmpty
        case .playlist: return try await !api.playlist(id: endpoint.browseId).tracks.isEmpty
        case .podcast, .generic:
            let page = try await api.browse(endpoint)
            return page.shelves.contains { !$0.items.isEmpty } || !page.chips.isEmpty
        }
    }

    @Test func everyMoreLinkOpens() async throws {
        let artist = try await api.artist(id: "UCIaFw5VBEK8qaW6nRpx_qnw") // Coldplay
        let explore = try await api.explore()
        let queue = try await api.watchQueue(WatchEndpoint(videoId: "yKNxeF4KMsY"))
        let related = try await api.related(browseId: try #require(queue.relatedBrowseId))
        var links = (artist.shelves + explore.shelves + related).compactMap { shelf in shelf.more.map { (shelf.title, $0) } }
        links += explore.chips.prefix(6).compactMap { chip in chip.endpoint.map { (chip.title, $0) } }
        #expect(links.count >= 8)
        for (title, endpoint) in links {
            do {
                #expect(try await open(endpoint), "\(title) → \(endpoint) opened empty")
            } catch {
                Issue.record("\(title) → \(endpoint) failed: \(error)")
            }
        }
        // Items open the same way, with the ids they carry.
        for shelf in artist.shelves {
            guard let item = shelf.items.first, case .song = item else {
                if let item = shelf.items.first {
                    let endpoint: BrowseEndpoint = switch item {
                    case .album(let album): BrowseEndpoint(browseId: album.id, pageType: .album)
                    case .artist(let artist): BrowseEndpoint(browseId: artist.id, pageType: .artist)
                    case .playlist(let playlist): BrowseEndpoint(browseId: playlist.id, pageType: .playlist)
                    case .song: BrowseEndpoint(browseId: "")
                    }
                    #expect(try await open(endpoint), "\(shelf.title): \(item.title) opened empty")
                }
                continue
            }
        }
    }

    @Test func longPlaylistScrollsOn() async throws {
        // A big editorial playlist of the artist's songs is linked from the top songs shelf; a search for a long
        // community playlist backs it up.
        let search = try await api.search("top 500 songs of all time", filter: .communityPlaylists)
        let candidates = search.shelves.first?.items.compactMap(\.playlist) ?? []
        var checked = false
        for candidate in candidates.prefix(4) {
            let page = try await api.playlist(id: candidate.id)
            guard let token = page.continuation else { continue }
            #expect(page.tracks.count >= 50)
            #expect(page.playlist.id == candidate.id)
            #expect(!page.playlist.title.isEmpty)
            #expect(page.tracks.allSatisfy { !$0.videoId.isEmpty && !$0.title.isEmpty })
            #expect(page.tracks.filter { $0.duration != nil }.count >= page.tracks.count * 9 / 10)
            let more = try await api.playlistContinuation(token)
            #expect(more.songs.count >= 20)
            #expect(Set(more.songs.map(\.videoId)).isDisjoint(with: page.tracks.prefix(20).map(\.videoId)))
            checked = true
            break
        }
        #expect(checked, "no long playlist found to scroll")
    }

    // MARK: Playing

    @Test func radioQueueLyricsAndRelated() async throws {
        let videoId = "yKNxeF4KMsY" // Coldplay, "Yellow"
        let queue = try await api.watchQueue(WatchEndpoint(videoId: videoId))
        #expect(queue.songs.count >= 10)
        #expect(queue.songs[queue.currentIndex].videoId == videoId)
        #expect(queue.playlistId?.hasPrefix("RDAMVM") == true)
        #expect(queue.songs.allSatisfy { $0.duration ?? 0 > 0 && !$0.artists.isEmpty })
        let token = try #require(queue.continuation)
        let more = try await api.watchQueueContinuation(token)
        #expect(more.songs.count >= 5)

        let lyricsId = try #require(queue.lyricsBrowseId)
        let lyrics = try await api.lyrics(browseId: lyricsId)
        #expect(lyrics.lines.count >= 10)
        #expect(lyrics.lines.contains { $0.text.lowercased().contains("yellow") })
        #expect(lyrics.source != nil)

        let relatedId = try #require(queue.relatedBrowseId)
        let related = try await api.related(browseId: relatedId)
        #expect(!related.isEmpty)
        #expect(related.contains { !$0.items.isEmpty })
    }

    @Test func queueInsideAPlaylist() async throws {
        let search = try await api.search("coldplay parachutes", filter: .albums)
        let found = try #require(search.shelves.first?.items.compactMap(\.album).first)
        let album = try await api.album(id: found.id)
        let playlistId = try #require(album.album.playlistId)
        let third = try #require(album.tracks.dropFirst(2).first)
        let queue = try await api.watchQueue(WatchEndpoint(videoId: third.videoId, playlistId: playlistId))
        #expect(queue.playlistId == playlistId)
        #expect(queue.songs[queue.currentIndex].videoId == third.videoId)
        #expect(queue.currentIndex == 2)
        #expect(queue.songs.prefix(album.tracks.count).map(\.videoId) == album.tracks.map(\.videoId))
        // The whole playlist from its start.
        let whole = try await api.watchQueue(WatchEndpoint(playlistId: playlistId))
        #expect(whole.songs.first?.videoId == album.tracks.first?.videoId)
        #expect(whole.currentIndex == 0)
    }

    // MARK: Signed out

    @Test func signedOutAccountCalls() async throws {
        #expect(try await api.account() == nil)
        await #expect(throws: MusicAPIError.signedOut) { try await api.library(.songs) }
        await #expect(throws: MusicAPIError.signedOut) { try await api.likedSongs() }
        await #expect(throws: MusicAPIError.signedOut) { try await api.rate(videoId: "yKNxeF4KMsY", .like) }
    }
}
