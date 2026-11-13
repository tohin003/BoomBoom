import BoomModels
import Foundation
import Synchronization
import Testing
@testable import InnerTube

// What goes over the wire: headers, bodies and error mapping, checked offline against a stubbed URL loader.
// The expected bodies are what ytmusicapi sends for the same calls.

// MARK: - Stub

/// Answers every request of a stubbed `URLSession` with `StubProtocol.reply` and records what was sent.
final class StubProtocol: URLProtocol, @unchecked Sendable {
    enum Reply: Sendable {
        case json(Int, String)
        case data(Int, Data)
        case failure(URLError.Code)
        /// Never answers, so the caller can cancel.
        case hang
    }

    struct Sent: Sendable {
        var request: URLRequest
        var body: Data
        var endpoint: String { request.url?.path().replacingOccurrences(of: "/youtubei/v1/", with: "") ?? "" }
        /// The body without the `context` every call carries.
        var payload: JSON {
            var json = (try? JSON(data: body)) ?? .null
            if case .object(var object) = json {
                object["context"] = nil
                json = .object(object)
            }
            return json
        }
        var context: JSON { ((try? JSON(data: body)) ?? .null)["context"] }
    }

    struct State {
        var replies: [String: Reply] = [:]
        var fallback: Reply = .json(200, "{}")
        var sent: [Sent] = []
    }

    static let state = Mutex(State())

    static func reset(_ replies: [String: Reply] = [:], fallback: Reply = .json(200, "{}")) {
        state.withLock { $0 = State(replies: replies, fallback: fallback) }
    }

    static var sent: [Sent] { state.withLock { $0.sent } }

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: configuration)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = request.httpBody ?? Self.read(request.httpBodyStream)
        let sent = Sent(request: request, body: body)
        let reply = Self.state.withLock { state -> Reply in
            state.sent.append(sent)
            return state.replies[sent.endpoint] ?? state.fallback
        }
        guard let url = request.url else { return }
        switch reply {
        case .json(let status, let text):
            answer(url: url, status: status, data: Data(text.utf8))
        case .data(let status, let data):
            answer(url: url, status: status, data: data)
        case .failure(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .hang:
            break
        }
    }

    override func stopLoading() {}

    private func answer(url: URL, status: Int, data: Data) {
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])
        if let response { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func read(_ stream: InputStream?) -> Data {
        guard let stream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

struct FixedSession: InnerTubeSessionProviding {
    let session: InnerTubeSession
    func currentSession() async throws -> InnerTubeSession { session }
}

enum Sessions {
    static let signedOut = InnerTubeSession(clientVersion: "1.20261110.04.00", visitorData: "CgtWaXNpdG9yRGF0YQ%3D%3D")
    static let cookies = [
        "SAPISID": "AbCdEfGhIjKlMnOp/QrStUvWxYz012345",
        "__Secure-3PAPISID": "AbCdEfGhIjKlMnOp/QrStUvWxYz012345",
        "SID": "sid-value",
        "HSID": "hsid-value",
        "LOGIN_INFO": "login:info=",
    ]
    static let signedIn = InnerTubeSession(
        clientVersion: "1.20261110.04.00",
        visitorData: "CgtWaXNpdG9yRGF0YQ%3D%3D",
        language: "en",
        region: "IN",
        cookies: cookies,
        authUser: "1",
        onBehalfOfUser: "111222333444555666777"
    )
}

func stubbedClient(_ session: InnerTubeSession) -> InnerTubeClient {
    InnerTubeClient(sessionProvider: FixedSession(session: session), urlSession: StubProtocol.session())
}

// MARK: - Headers and context

@Suite("Requests")
struct RequestBuilderTests {
    @Test func signedOutRequest() throws {
        let request = try InnerTubeRequest.make(endpoint: "browse", body: ["browseId": "FEmusic_home"], session: Sessions.signedOut)
        #expect(request.url?.absoluteString == "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
        #expect(request.httpMethod == "POST")
        #expect(request.httpShouldHandleCookies == false)
        let headers = request.allHTTPHeaderFields ?? [:]
        #expect(headers["Authorization"] == nil)
        #expect(headers["Cookie"] == nil)
        #expect(headers["X-Goog-AuthUser"] == nil)
        #expect(headers["X-Goog-Visitor-Id"] == "CgtWaXNpdG9yRGF0YQ%3D%3D")
        #expect(headers["X-Youtube-Client-Name"] == "67")
        #expect(headers["X-Youtube-Client-Version"] == "1.20261110.04.00")
        #expect(headers["Origin"] == "https://music.youtube.com")
        #expect(headers["X-Origin"] == "https://music.youtube.com")
        #expect(headers["Content-Type"] == "application/json")
        #expect(headers["User-Agent"] == InnerTubeSession.desktopSafariUserAgent)

        let body = try JSON(data: try #require(request.httpBody))
        #expect(body["browseId"] == "FEmusic_home")
        #expect(body["context", "client"] == [
            "clientName": "WEB_REMIX", "clientVersion": "1.20261110.04.00", "hl": "en", "gl": "US",
            "visitorData": "CgtWaXNpdG9yRGF0YQ%3D%3D",
        ])
        #expect(body["context", "user"] == .object([:]))
    }

    @Test func noVisitorIdHeaderWithoutVisitorData() throws {
        let session = InnerTubeSession(clientVersion: "1.2")
        let request = try InnerTubeRequest.make(endpoint: "search", body: [:], session: session)
        #expect(request.value(forHTTPHeaderField: "X-Goog-Visitor-Id") == nil)
        #expect(try JSON(data: try #require(request.httpBody))["context", "client", "visitorData"] == .null)
    }

    @Test func signedInRequest() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let request = try InnerTubeRequest.make(endpoint: "browse", body: [:], session: Sessions.signedIn, now: now)
        let headers = request.allHTTPHeaderFields ?? [:]
        // sha1("1700000000 AbCdEfGhIjKlMnOp/QrStUvWxYz012345 https://music.youtube.com"), computed with Python's
        // hashlib and `shasum`.
        #expect(headers["Authorization"] == "SAPISIDHASH 1700000000_163e661c1ad4177128b563dfbe8b0ebfd1296a14")
        #expect(headers["X-Goog-AuthUser"] == "1")
        let cookie = try #require(headers["Cookie"])
        let pairs = Set(cookie.components(separatedBy: "; "))
        #expect(pairs == Set(Sessions.cookies.map { "\($0.key)=\($0.value)" }))
        #expect(headers["X-Goog-Visitor-Id"] == "CgtWaXNpdG9yRGF0YQ%3D%3D")

        let body = try JSON(data: try #require(request.httpBody))
        #expect(body["context", "user"] == ["onBehalfOfUser": "111222333444555666777"])
        #expect(body["context", "client", "gl"] == "IN")
    }

    @Test func authorizationVector() {
        #expect(InnerTubeRequest.authorization(sapisid: "xyz", timestamp: 1_731_500_000)
            == "SAPISIDHASH 1731500000_94cff7c8b4baccd5e5cf9ef2ee3f4f9f38995fa9")
    }

    @Test func secure3PAPISIDAloneSignsIn() throws {
        var session = Sessions.signedOut
        session.cookies = ["__Secure-3PAPISID": "xyz", "SID": "s"]
        #expect(session.isSignedIn)
        let request = try InnerTubeRequest.make(
            endpoint: "browse", body: [:], session: session, now: Date(timeIntervalSince1970: 1_731_500_000)
        )
        #expect(request.value(forHTTPHeaderField: "Authorization")
            == "SAPISIDHASH 1731500000_94cff7c8b4baccd5e5cf9ef2ee3f4f9f38995fa9")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "SID=s; __Secure-3PAPISID=xyz")
    }

    @Test func cookiesWithoutSAPISIDAreNotSent() throws {
        var session = Sessions.signedOut
        session.cookies = ["YSC": "abc", "VISITOR_INFO1_LIVE": "def"]
        #expect(!session.isSignedIn)
        let request = try InnerTubeRequest.make(endpoint: "browse", body: [:], session: session)
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }
}

// MARK: - Bodies, guards and errors over a stubbed connection

@Suite("Wire", .serialized)
struct WireTests {
    // MARK: Read calls

    @Test func searchBodies() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        _ = try? await api.search("oasis", filter: nil)
        _ = try? await api.search("oasis", filter: .songs)
        _ = try? await api.search("oasis", filter: .communityPlaylists)
        _ = try? await api.searchContinuation("TOKEN")
        _ = try? await api.searchSuggestions("oas")
        let sent = StubProtocol.sent
        #expect(sent.map(\.endpoint) == ["search", "search", "search", "search", "music/get_search_suggestions"])
        #expect(sent[0].payload == ["query": "oasis"])
        #expect(sent[1].payload == ["query": "oasis", "params": "EgWKAQIIAWoMEA4QChADEAQQCRAF"])
        #expect(sent[2].payload == ["query": "oasis", "params": "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"])
        #expect(sent[3].payload == ["continuation": "TOKEN"])
        #expect(sent[4].payload == ["input": "oas"])
    }

    @Test func emptySuggestionInputSendsNothing() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        #expect(try await api.searchSuggestions("") == [])
        #expect(try await api.searchSuggestions("  \n") == [])
        #expect(StubProtocol.sent.isEmpty)
    }

    @Test func browseBodies() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        let chip = Chip(title: "Relax", endpoint: BrowseEndpoint(browseId: "FEmusic_home", params: "ggM"))
        _ = try? await api.home(chip: nil)
        _ = try? await api.home(chip: chip)
        _ = try? await api.explore()
        _ = try? await api.charts(country: "IN")
        _ = try? await api.moodsAndGenres()
        _ = try? await api.album(id: "MPREb_abc")
        _ = try? await api.artist(id: "MPLAUCabc")
        _ = try? await api.playlist(id: "PLabc")
        _ = try? await api.playlist(id: "VLPLabc")
        _ = try? await api.browse(BrowseEndpoint(browseId: "PLabc", pageType: .playlist))
        _ = try? await api.browse(BrowseEndpoint(browseId: "MPADUCabc", params: "ggMI", pageType: .generic))
        _ = try? await api.browseContinuation("TOKEN")
        _ = try? await api.lyrics(browseId: "MPLYt_abc")
        _ = try? await api.related(browseId: "MPTRt_abc")
        let payloads = StubProtocol.sent.map(\.payload)
        #expect(StubProtocol.sent.allSatisfy { $0.endpoint == "browse" })
        #expect(payloads == [
            ["browseId": "FEmusic_home"],
            ["browseId": "FEmusic_home", "params": "ggM"],
            ["browseId": "FEmusic_explore"],
            ["browseId": "FEmusic_charts", "formData": ["selectedValues": ["IN"]]],
            ["browseId": "FEmusic_moods_and_genres"],
            ["browseId": "MPREb_abc"],
            ["browseId": "UCabc"],
            // A playlist answer without a header is retried as a podcast show page.
            ["browseId": "VLPLabc"], ["browseId": "MPSPPLabc"],
            ["browseId": "VLPLabc"], ["browseId": "MPSPPLabc"],
            ["browseId": "VLPLabc"],
            ["browseId": "MPADUCabc", "params": "ggMI"],
            ["continuation": "TOKEN"],
            ["browseId": "MPLYt_abc"],
            ["browseId": "MPTRt_abc"],
        ])
    }

    @Test func watchBodies() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        let flags: [String: JSON] = [
            "enablePersistentPlaylistPanel": true, "isAudioOnly": true, "tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
        ]
        func with(_ extra: [String: JSON]) -> JSON { .object(flags.merging(extra) { $1 }) }
        _ = try? await api.watchQueue(WatchEndpoint(videoId: "vid"))
        _ = try? await api.watchQueue(WatchEndpoint(videoId: "vid", playlistId: "VLOLAK5uy_x"))
        _ = try? await api.watchQueue(WatchEndpoint(playlistId: "RDCLAK5uy_x"))
        _ = try? await api.watchQueue(WatchEndpoint(videoId: "vid", playlistId: "RDAOabc", params: "wAEB8gECGAE%3D"))
        _ = try? await api.watchQueueContinuation("TOKEN")
        let sent = StubProtocol.sent
        #expect(sent.allSatisfy { $0.endpoint == "next" })
        // ytmusicapi `get_watch_playlist(videoId, radio=True)`.
        #expect(sent[0].payload == with(["videoId": "vid", "playlistId": "RDAMVMvid", "params": "wAEB"]))
        // ytmusicapi `get_watch_playlist(videoId, playlistId)`.
        #expect(sent[1].payload == with([
            "videoId": "vid", "playlistId": "OLAK5uy_x",
            "watchEndpointMusicSupportedConfigs": [
                "watchEndpointMusicConfig": ["hasPersistentPlaylistPanel": true, "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"],
            ],
        ]))
        #expect(sent[2].payload == with(["playlistId": "RDCLAK5uy_x"]))
        // A button's own endpoint (an artist's shuffle) is sent as given.
        #expect(sent[3].payload == with(["videoId": "vid", "playlistId": "RDAOabc", "params": "wAEB8gECGAE%3D"]))
        #expect(sent[4].payload == with(["continuation": "TOKEN"]))
    }

    // MARK: Mutations

    @Test func rateBodies() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedIn)
        try await api.rate(videoId: "vid", .like)
        try await api.rate(videoId: "vid", .dislike)
        try await api.rate(videoId: "vid", .indifferent)
        try await api.ratePlaylist(id: "VLPLabc", .like)
        try await api.ratePlaylist(id: "OLAK5uy_x", .indifferent)
        let sent = StubProtocol.sent
        #expect(sent.map(\.endpoint) == ["like/like", "like/dislike", "like/removelike", "like/like", "like/removelike"])
        #expect(sent[0].payload == ["target": ["videoId": "vid"]])
        #expect(sent[2].payload == ["target": ["videoId": "vid"]])
        #expect(sent[3].payload == ["target": ["playlistId": "PLabc"]])
        #expect(sent[4].payload == ["target": ["playlistId": "OLAK5uy_x"]])
        #expect(sent.allSatisfy { $0.request.value(forHTTPHeaderField: "Authorization")?.hasPrefix("SAPISIDHASH ") == true })
    }

    @Test func subscribeUsesTheChannelTheArtistPageNames() async throws {
        // The page of `UCpage` says its button subscribes to `UCbutton`.
        let artistPage = """
            {"header": {"musicImmersiveHeaderRenderer": {
                "title": {"runs": [{"text": "Band"}]},
                "subscriptionButton": {"subscribeButtonRenderer": {"channelId": "UCbutton", "subscribed": false}}
            }}}
            """
        StubProtocol.reset(["browse": .json(200, artistPage)])
        let api = stubbedClient(Sessions.signedIn)
        try await api.subscribe(channelId: "UCpage", true)
        try await api.subscribe(channelId: "UCpage", false)
        let sent = StubProtocol.sent
        // The page is read once, then remembered.
        #expect(sent.map(\.endpoint) == ["browse", "subscription/subscribe", "subscription/unsubscribe"])
        #expect(sent[1].payload == ["channelIds": ["UCbutton"]])
        #expect(sent[2].payload == ["channelIds": ["UCbutton"]])
    }

    @Test func subscribeFallsBackToTheGivenId() async throws {
        StubProtocol.reset(["browse": .json(200, "{}")])
        let api = stubbedClient(Sessions.signedIn)
        try await api.subscribe(channelId: "MPLAUCother", true)
        #expect(StubProtocol.sent.last?.payload == ["channelIds": ["UCother"]])
    }

    @Test func libraryFeedbackBody() async throws {
        StubProtocol.reset(["feedback": .json(200, #"{"feedbackResponses": [{"isProcessed": true}]}"#)])
        let api = stubbedClient(Sessions.signedIn)
        try await api.sendLibraryFeedback(token: "AB12_token")
        #expect(StubProtocol.sent.map(\.endpoint) == ["feedback"])
        #expect(StubProtocol.sent[0].payload == ["feedbackTokens": ["AB12_token"]])

        StubProtocol.reset(["feedback": .json(200, #"{"feedbackResponses": [{"isProcessed": false}]}"#)])
        await #expect(throws: MusicAPIError.self) { try await api.sendLibraryFeedback(token: "x") }
    }

    @Test func playlistEditBodies() async throws {
        StubProtocol.reset([
            "playlist/create": .json(200, #"{"playlistId": "PLnew"}"#),
            "browse/edit_playlist": .json(200, #"{"status": "STATUS_SUCCEEDED"}"#),
            "playlist/delete": .json(200, "{}"),
        ])
        let api = stubbedClient(Sessions.signedIn)
        let id = try await api.createPlaylist(title: "Mix", description: "For runs", privacy: .unlisted, videoIds: ["a", "b"])
        #expect(id == "PLnew")
        _ = try await api.createPlaylist(title: "Empty", description: "", privacy: .private, videoIds: [])
        try await api.editPlaylist(id: "VLPLnew", title: "Mix 2", description: "New words", privacy: .public)
        try await api.editPlaylist(id: "PLnew", title: nil, description: nil, privacy: .private)
        try await api.addToPlaylist(id: "PLnew", videoIds: ["c", "d"])
        try await api.removeFromPlaylist(id: "VLPLnew", songs: [
            Song(videoId: "a", title: "A", setVideoId: "SET_A"),
            Song(videoId: "b", title: "B", setVideoId: "SET_B"),
        ])
        try await api.deletePlaylist(id: "VLPLnew")
        let sent = StubProtocol.sent
        #expect(sent.map(\.endpoint) == [
            "playlist/create", "playlist/create", "browse/edit_playlist", "browse/edit_playlist",
            "browse/edit_playlist", "browse/edit_playlist", "playlist/delete",
        ])
        #expect(sent[0].payload == [
            "title": "Mix", "description": "For runs", "privacyStatus": "UNLISTED", "videoIds": ["a", "b"],
        ])
        #expect(sent[1].payload == ["title": "Empty", "description": "", "privacyStatus": "PRIVATE"])
        #expect(sent[2].payload == ["playlistId": "PLnew", "actions": [
            ["action": "ACTION_SET_PLAYLIST_NAME", "playlistName": "Mix 2"],
            ["action": "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription": "New words"],
            ["action": "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy": "PUBLIC"],
        ]])
        #expect(sent[3].payload == ["playlistId": "PLnew", "actions": [
            ["action": "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy": "PRIVATE"],
        ]])
        #expect(sent[4].payload == ["playlistId": "PLnew", "actions": [
            ["action": "ACTION_ADD_VIDEO", "addedVideoId": "c"],
            ["action": "ACTION_ADD_VIDEO", "addedVideoId": "d"],
        ]])
        #expect(sent[5].payload == ["playlistId": "PLnew", "actions": [
            ["action": "ACTION_REMOVE_VIDEO", "setVideoId": "SET_A", "removedVideoId": "a"],
            ["action": "ACTION_REMOVE_VIDEO", "setVideoId": "SET_B", "removedVideoId": "b"],
        ]])
        #expect(sent[6].payload == ["playlistId": "PLnew"])
    }

    @Test func failedPlaylistEditThrows() async throws {
        StubProtocol.reset(["browse/edit_playlist": .json(200, #"{"status": "STATUS_FAILED"}"#)])
        let api = stubbedClient(Sessions.signedIn)
        await #expect(throws: MusicAPIError.self) { try await api.addToPlaylist(id: "PLx", videoIds: ["a"]) }
    }

    @Test func removingSongsWithoutSetVideoIdThrows() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedIn)
        await #expect(throws: MusicAPIError.self) {
            try await api.removeFromPlaylist(id: "PLx", songs: [Song(videoId: "a", title: "A")])
        }
        #expect(StubProtocol.sent.isEmpty)
    }

    @Test func libraryBodies() async throws {
        let empty = #"{"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": []}}}}]}}}"#
        StubProtocol.reset(fallback: .json(200, empty))
        let api = stubbedClient(Sessions.signedIn)
        for section in LibrarySection.allCases { _ = try await api.library(section) }
        _ = try await api.history()
        _ = try? await api.likedSongs()
        #expect(StubProtocol.sent.map(\.payload) == [
            ["browseId": "FEmusic_liked_playlists"],
            ["browseId": "FEmusic_liked_videos"],
            ["browseId": "FEmusic_liked_albums"],
            ["browseId": "FEmusic_library_corpus_track_artists"],
            ["browseId": "FEmusic_library_corpus_artists"],
            ["browseId": "FEmusic_history"],
            ["browseId": "VLLM"],
        ])
    }

    @Test func account() async throws {
        let menu = """
            {"actions": [{"openPopupAction": {"popup": {"multiPageMenuRenderer": {
                "header": {"activeAccountHeaderRenderer": {
                    "accountName": {"runs": [{"text": "Listener Name"}]},
                    "channelHandle": {"runs": [{"text": "@listener"}]},
                    "accountPhoto": {"thumbnails": [
                        {"url": "https://yt3.ggpht.com/photo=s88-c-k", "width": 88, "height": 88},
                        {"url": "https://yt3.ggpht.com/photo=s48-c-k", "width": 48, "height": 48}
                    ]}
                }},
                "sections": []
            }}}}]}
            """
        StubProtocol.reset(["account/account_menu": .json(200, menu)])
        let account = try #require(try await stubbedClient(Sessions.signedIn).account())
        #expect(StubProtocol.sent.map(\.endpoint) == ["account/account_menu"])
        #expect(StubProtocol.sent[0].payload == .object([:]))
        #expect(account.name == "Listener Name")
        #expect(account.handle == "@listener")
        #expect(account.artwork.thumbnails.map(\.width) == [48, 88])
        #expect(account.isPremium == false)

        StubProtocol.reset()
        #expect(try await stubbedClient(Sessions.signedOut).account() == nil)
        #expect(StubProtocol.sent.isEmpty)
    }

    // MARK: Guards and errors

    @Test func signedInCallsThrowSignedOutBeforeAnyRequest() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        let song = Song(videoId: "a", title: "A", setVideoId: "S")
        let calls: [(String, @Sendable () async throws -> Void)] = [
            ("library", { _ = try await api.library(.playlists) }),
            ("likedSongs", { _ = try await api.likedSongs() }),
            ("history", { _ = try await api.history() }),
            ("rate", { try await api.rate(videoId: "a", .like) }),
            ("ratePlaylist", { try await api.ratePlaylist(id: "PLx", .like) }),
            ("subscribe", { try await api.subscribe(channelId: "UCx", true) }),
            ("unsubscribe", { try await api.subscribe(channelId: "UCx", false) }),
            ("feedback", { try await api.sendLibraryFeedback(token: "t") }),
            ("create", { _ = try await api.createPlaylist(title: "t", description: "", privacy: .private, videoIds: []) }),
            ("delete", { try await api.deletePlaylist(id: "PLx") }),
            ("edit", { try await api.editPlaylist(id: "PLx", title: "t", description: nil, privacy: nil) }),
            ("edit nothing", { try await api.editPlaylist(id: "PLx", title: nil, description: nil, privacy: nil) }),
            ("add", { try await api.addToPlaylist(id: "PLx", videoIds: ["a"]) }),
            ("add nothing", { try await api.addToPlaylist(id: "PLx", videoIds: []) }),
            ("remove", { try await api.removeFromPlaylist(id: "PLx", songs: [song]) }),
        ]
        for (name, call) in calls {
            do {
                try await call()
                Issue.record("\(name) did not throw")
            } catch {
                #expect(error as? MusicAPIError == .signedOut, "\(name) threw \(error)")
            }
        }
        #expect(StubProtocol.sent.isEmpty)
    }

    @Test(arguments: [401, 403])
    func refusedMapsToUnauthorized(status: Int) async throws {
        StubProtocol.reset(fallback: .json(status, #"{"error": {"code": 401, "message": "denied"}}"#))
        let api = stubbedClient(Sessions.signedIn)
        await #expect(throws: MusicAPIError.unauthorized) { try await api.rate(videoId: "a", .like) }
        await #expect(throws: MusicAPIError.unauthorized) { _ = try await api.home(chip: nil) }
    }

    @Test func serverErrorsRetryOnceThenFail() async throws {
        StubProtocol.reset(fallback: .json(503, "{}"))
        let api = stubbedClient(Sessions.signedOut)
        await #expect(throws: MusicAPIError.self) { _ = try await api.explore() }
        #expect(StubProtocol.sent.count == 2)
        do {
            _ = try await api.explore()
        } catch let error as MusicAPIError {
            guard case .network(let message) = error else { Issue.record("not a network error: \(error)"); return }
            #expect(message.contains("503"))
        }
    }

    @Test func clientErrorsAreNotRetried() async throws {
        StubProtocol.reset(fallback: .json(400, #"{"error": {"message": "Request contains an invalid argument."}}"#))
        let api = stubbedClient(Sessions.signedOut)
        do {
            _ = try await api.explore()
            Issue.record("no error")
        } catch {
            guard case .network(let message)? = error as? MusicAPIError else { Issue.record("\(error)"); return }
            #expect(message.contains("400"))
            #expect(message.contains("invalid argument"))
        }
        #expect(StubProtocol.sent.count == 1)
    }

    @Test(arguments: [URLError.Code.notConnectedToInternet, .timedOut, .cannotFindHost, .badServerResponse])
    func transportErrorsMapToNetwork(code: URLError.Code) async throws {
        StubProtocol.reset(fallback: .failure(code))
        let api = stubbedClient(Sessions.signedOut)
        do {
            _ = try await api.explore()
            Issue.record("no error")
        } catch {
            guard case .network? = error as? MusicAPIError else { Issue.record("\(error)"); return }
        }
        // Dropped connections and timeouts are tried once more.
        #expect(StubProtocol.sent.count == (InnerTubeClient.isTransient(URLError(code)) ? 2 : 1))
    }

    @Test func unreadableAnswer() async throws {
        StubProtocol.reset(fallback: .data(200, Data("<html>".utf8)))
        let api = stubbedClient(Sessions.signedOut)
        await #expect(throws: MusicAPIError.unreadable("browse: answer is not JSON")) { _ = try await api.explore() }
        StubProtocol.reset(fallback: .json(200, "{}"))
        do {
            _ = try await api.explore()
            Issue.record("no error")
        } catch {
            guard case .unreadable? = error as? MusicAPIError else { Issue.record("\(error)"); return }
        }
    }

    @Test func cancellationIsNotWrapped() async throws {
        StubProtocol.reset(fallback: .hang)
        let api = stubbedClient(Sessions.signedOut)
        let task = Task { try await api.search("slow", filter: nil) }
        try await Task.sleep(for: .milliseconds(200))
        task.cancel()
        do {
            _ = try await task.value
            Issue.record("no error")
        } catch {
            #expect(error is CancellationError, "threw \(error)")
        }
    }

    @Test func cancelledBeforeSendingSendsNothing() async throws {
        StubProtocol.reset()
        let api = stubbedClient(Sessions.signedOut)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await api.explore()
        }
        do {
            _ = try await task.value
            Issue.record("no error")
        } catch {
            #expect(error is CancellationError, "threw \(error)")
        }
        #expect(StubProtocol.sent.isEmpty)
    }

    @Test func sessionProviderErrors() async throws {
        struct Broken: InnerTubeSessionProviding {
            func currentSession() async throws -> InnerTubeSession { throw URLError(.notConnectedToInternet) }
        }
        let api = InnerTubeClient(sessionProvider: Broken(), urlSession: StubProtocol.session())
        do {
            _ = try await api.explore()
            Issue.record("no error")
        } catch {
            guard case .network? = error as? MusicAPIError else { Issue.record("\(error)"); return }
        }
    }
}
