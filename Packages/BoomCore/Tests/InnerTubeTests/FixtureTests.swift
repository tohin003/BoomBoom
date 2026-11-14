import BoomModels
import Foundation
import Testing
@testable import InnerTube

// Parsers against real answers recorded from the live service (signed out, hl=en, gl=US, from an Indian IP).

extension MusicItem {
    var song: Song? { if case .song(let value) = self { value } else { nil } }
    var album: AlbumItem? { if case .album(let value) = self { value } else { nil } }
    var artist: ArtistItem? { if case .artist(let value) = self { value } else { nil } }
    var playlist: PlaylistItem? { if case .playlist(let value) = self { value } else { nil } }
}

@Suite("Search fixtures")
struct SearchFixtureTests {
    @Test func suggestions() throws {
        let suggestions = Parse.searchSuggestions(try fixture("suggestions"))
        let queries = suggestions.compactMap { if case .query(let q) = $0 { q } else { nil } }
        let items = suggestions.compactMap { if case .item(let i) = $0 { i } else { nil } }
        #expect(queries.map(\.text) == [
            "taylor swift", "taylor swift songs", "taylor swift love story", "taylor swift ophelia",
            "taylor swift lover", "taylor swift playlist",
        ])
        #expect(queries[0].runs == [.init(text: "taylor sw", isBold: true), .init(text: "ift", isBold: false)])
        #expect(queries.allSatisfy { !$0.isFromHistory })
        #expect(items.count == 5)
        let artist = try #require(items[0].artist)
        #expect(artist.id == "UCPC0L1d253x-KuMNwa05TpA")
        #expect(artist.name == "Taylor Swift")
        #expect(artist.detailText == "408M monthly audience")
        let song = try #require(items[1].song)
        #expect(song.videoId == "aZ1hziFhj1o")
        #expect(song.title == "Love Story")
        #expect(song.kind == .song)
        #expect(song.artists == [ArtistRef(name: "Taylor Swift", id: "UCPC0L1d253x-KuMNwa05TpA")])
        #expect(song.album == AlbumRef(name: "Fearless (Platinum Edition)", id: "MPREb_u721jSRXVRq"))
        #expect(song.playsText == "1.2B plays")
        let album = try #require(items[3].album)
        #expect(album.title == "Lover")
        #expect(album.year == "2019")
        #expect(album.typeText == "Album")
        // Queries come first, then the items, as YouTube Music lists them.
        #expect(suggestions.firstIndex { if case .item = $0 { true } else { false } } == 6)
    }

    @Test func historySuggestionIsFlagged() throws {
        let response: JSON = ["contents": [["searchSuggestionsSectionRenderer": ["contents": [
            ["historySuggestionRenderer": [
                "suggestion": ["runs": [["text": "oasis"]]],
                "navigationEndpoint": ["searchEndpoint": ["query": "oasis"]],
            ]],
            ["someNewRenderer": ["whatever": 1]],
        ]]]]]
        let suggestions = Parse.searchSuggestions(response)
        #expect(suggestions == [.query(SuggestedQuery(text: "oasis", runs: [.init(text: "oasis", isBold: false)], isFromHistory: true))])
    }

    @Test func songsUnderAnArtistCardAreThatArtists() throws {
        func artistLink(_ name: String, _ id: String) -> String {
            #"{"text": "\#(name)", "navigationEndpoint": {"browseEndpoint": {"browseId": "\#(id)", "#
                + #""browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": "#
                + #"{"pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}}"#
        }
        func row(_ title: String, _ videoId: String, line: String) -> String {
            #"{"musicResponsiveListItemRenderer": {"flexColumns": ["#
                + #"{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "\#(title)", "#
                + #""navigationEndpoint": {"watchEndpoint": {"videoId": "\#(videoId)"}}}]}}}, "#
                + #"{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [\#(line)]}}}], "#
                + #""playlistItemData": {"videoId": "\#(videoId)"}}}"#
        }
        let dot = #"{"text": " \u2022 "}"#
        // The service leaves the artist out of the first line: the card says whose the song is.
        let first = row("First Song", "aaaaaaaaaaa", line: #"{"text": "Song"}, \#(dot), {"text": "3:05"}"#)
        let second = row(
            "Second Song", "bbbbbbbbbbb",
            line: #"{"text": "Song"}, \#(dot), \#(artistLink("Somebody Else", "UCsomebodyelse")), \#(dot), {"text": "4:10"}"#
        )
        let card = try JSON(data: Data(
            (#"{"title": {"runs": [\#(artistLink("The Example Band", "UCexampleband"))]}, "#
                + #""subtitle": {"runs": [{"text": "Artist"}, \#(dot), {"text": "1M monthly audience"}]}, "#
                + #""contents": [\#(first), \#(second)]}"#).utf8
        ))
        let shelf = try #require(Parse.topResultShelf(card, index: 0))
        #expect(shelf.items.count == 3)
        #expect(shelf.items[0].artist?.id == "UCexampleband")
        let firstSong = try #require(shelf.items[1].song)
        #expect(firstSong.artists == [ArtistRef(name: "The Example Band", id: "UCexampleband")])
        #expect(firstSong.duration == 185)
        let secondSong = try #require(shelf.items[2].song)
        #expect(secondSong.artists == [ArtistRef(name: "Somebody Else", id: "UCsomebodyelse")])
    }

    @Test func unfilteredSearchFlatLayout() throws {
        let results = Parse.searchResults(try fixture("search_all"), query: "oasis wonderwall", filter: nil)
        #expect(results.continuation == nil)
        #expect(results.shelves.map(\.title) == [
            "Top result", "Artists", "Songs", "Videos", "Albums", "Community playlists", "Episodes", "Profiles",
            "Featured playlists", "Podcasts",
        ])
        let top = results.shelves[0]
        #expect(top.layout == .topResult)
        #expect(top.items.count == 4)
        let topSong = try #require(top.items[0].song)
        #expect(topSong.videoId == "6hzrDeceEKc")
        #expect(topSong.title == "Wonderwall")
        #expect(topSong.kind == .video)
        #expect(topSong.duration == 280)
        #expect(topSong.playsText == "716M views")
        #expect(topSong.artists == [ArtistRef(name: "Oasis", id: "UCmMUZbaYdNH0bEd1PAlAqsA")])

        let songs = results.shelves[2]
        #expect(songs.layout == .list)
        #expect(songs.moreFilter == .songs)
        #expect(songs.items.count == 5)
        let first = try #require(songs.items[0].song)
        #expect(first.videoId == "rj5wZqReXQE")
        #expect(first.kind == .song)
        #expect(first.playsText == "1.7B plays")
        #expect(first.artists.map(\.id) == ["UCmMUZbaYdNH0bEd1PAlAqsA"])

        let album = try #require(results.shelves[4].items[0].album)
        #expect(album.id == "MPREb_9nqEki4ZDpp")
        #expect(album.typeText == "Album")
        #expect(album.year == "1995")
        #expect(album.playlistId == "OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y")
        #expect(try #require(results.shelves[4].items[1].album).typeText == "Single")

        let episode = try #require(results.shelves[6].items[0].song)
        #expect(episode.kind == .episode)
        #expect(episode.artists.map(\.name) == ["100 головних пісень"])
        #expect(try #require(results.shelves[7].items[0].artist).detailText == "@VitalyLvov")
        #expect(try #require(results.shelves[8].items[0].playlist).id == "RDCLAK5uy_mgHrXs_5F6wPwPFA47S8yrzCfjCi4AXDE")
        #expect(try #require(results.shelves[9].items[0].playlist).id == "PLEkYVqCNcvxg")
    }

    @Test func songsFilteredSearch() throws {
        let results = Parse.searchResults(try fixture("search_songs"), query: "oasis wonderwall", filter: .songs)
        #expect(results.shelves.count == 1)
        let shelf = results.shelves[0]
        #expect(shelf.title == "Songs")
        #expect(shelf.items.count == 20)
        #expect(shelf.items.allSatisfy { $0.song?.kind == .song })
        #expect(shelf.items.allSatisfy { $0.song?.duration != nil })
        #expect(results.continuation?.hasPrefix("EqkDEhBvYXNpcyB3b25kZXJ3YWxs") == true)
        let first = try #require(shelf.items[0].song)
        #expect(first.title == "Wonderwall")
        #expect(first.duration == 259)
        #expect(first.album == AlbumRef(name: "(What's The Story) Morning Glory? (Remastered)", id: "MPREb_9nqEki4ZDpp"))
    }

    @Test func searchContinuation() throws {
        let page = try #require(Parse.searchContinuation(try fixture("search_cont")))
        #expect(page.items.count == 20)
        #expect(page.continuation != nil)
        #expect(page.items.allSatisfy { $0.song != nil })
    }

    @Test func filterParamsRoundTrip() {
        for filter in SearchFilter.allCases {
            #expect(Parse.searchFilter(params: Parse.searchParams(for: filter)) == filter)
        }
        // The chips of a live answer use other trailing bytes.
        #expect(Parse.searchFilter(params: "EgWKAQIIAWoSEAQQBRADEAkQChAQEBUQDhAR") == .songs)
        #expect(Parse.searchFilter(params: "EgWKAQIgAWoSEAQQBRADEAkQChAQEBUQDhAR") == .artists)
        #expect(Parse.searchFilter(params: "not base64!") == nil)
    }
}

@Suite("Browse fixtures")
struct BrowseFixtureTests {
    @Test func home() throws {
        let page = Parse.browsePage(try fixture("home"))
        #expect(page.chips.map(\.title) == [
            "Podcasts", "Romance", "Relax", "Feel good", "Energize", "Commute", "Party", "Workout", "Sad", "Focus", "Sleep",
        ])
        #expect(page.chips.allSatisfy { $0.endpoint?.browseId == "FEmusic_home" && $0.endpoint?.params != nil })
        #expect(page.continuation != nil)
        #expect(page.shelves.count == 2)
        let shelf = page.shelves[0]
        #expect(shelf.title == "India’s biggest hits")
        #expect(shelf.strapline == "MUSIC THAT'S HOT AND HAPPENING!")
        #expect(shelf.layout == .carousel)
        #expect(shelf.items.count == 10)
        let playlist = try #require(shelf.items[0].playlist)
        #expect(playlist.id == "RDCLAK5uy_kCicKSTh7ylcZSwvrN0vV4dI3eqEpXR4A")
        #expect(playlist.title == "Hits of 2026 (So Far)")
        #expect(playlist.artwork.thumbnails.count >= 2)
    }

    @Test func homeContinuationHasQuickPicksColumns() throws {
        let page = try #require(Parse.browseContinuation(try fixture("home_cont")))
        #expect(page.continuation != nil)
        let quickPicks = page.shelves[0]
        #expect(quickPicks.title == "Quick picks")
        #expect(quickPicks.layout == .columns)
        #expect(quickPicks.items.count == 20)
        let song = try #require(quickPicks.items[0].song)
        #expect(song.title == "Choosin' Texas")
        #expect(song.album?.id == "MPREb_QC0L98k1tGe")
        #expect(song.likeStatus == .indifferent)
        // Signed out, "Save to library" opens a sign-in prompt, but the remove token is there.
        #expect(song.libraryTokens?.add == nil)
        #expect(song.libraryTokens?.remove != nil)
        #expect(song.libraryTokens?.isInLibrary == false)
        #expect(page.shelves.contains { $0.title == "Featured playlists for you" && $0.more?.browseId == "FEmusic_moods_and_genres" })
    }

    @Test func explore() throws {
        let page = Parse.browsePage(try fixture("explore"))
        #expect(page.chips.prefix(4).map(\.title) == ["New releases", "Charts", "Moods & genres", "Podcasts"])
        #expect(page.chips[0].endpoint?.browseId == "FEmusic_new_releases")
        #expect(page.chips[1].endpoint?.browseId == "FEmusic_charts")
        let chill = try #require(page.chips.first { $0.title == "Chill" })
        #expect(chill.colorHex == 0xA4C5FF)
        #expect(chill.endpoint?.browseId == "FEmusic_moods_and_genres_category")

        #expect(page.shelves.map(\.title) == ["New albums & singles", "Popular episodes", "Trending", "New music videos"])
        let releases = page.shelves[0]
        #expect(releases.more?.browseId == "FEmusic_new_releases_albums")
        #expect(releases.items.count == 24)
        #expect(releases.items.allSatisfy { $0.album != nil })
        let episode = try #require(page.shelves[1].items[0].song)
        #expect(episode.kind == .episode)
        #expect(episode.duration == 6000)
        #expect(episode.artists.map(\.name) == ["Club Shay Shay"])
        #expect(page.shelves[2].layout == .ranked)
        #expect(page.shelves[3].items.allSatisfy { $0.song?.kind == .video })
    }

    @Test func charts() throws {
        let page = Parse.browsePage(try fixture("charts"))
        #expect(page.title == "Charts")
        #expect(page.shelves.map(\.title) == ["Video charts", "Genres", "Top artists", "Weekly top podcast shows"])
        let artists = page.shelves[2]
        #expect(artists.layout == .ranked)
        #expect(artists.items.count == 40)
        let first = try #require(artists.items[0].artist)
        #expect(first.id == "UCR28YDxjDE3ogQROaNdnRbQ")
        #expect(first.name == "YoungBoy Never Broke Again")
        #expect(first.detailText == "15.8M subscribers")
        #expect(try #require(page.shelves[0].items[0].playlist).id == "PL4fGSI1pDJn4yCNzulPkUbxgr4pl0gmI-")
    }

    @Test func moodsAndGenres() throws {
        let groups = Parse.chipGroups(try fixture("moods"))
        #expect(groups.map(\.title) == ["Moods & moments", "Genres"])
        #expect(groups.map(\.chips.count) == [12, 38])
        let chill = groups[0].chips[0]
        #expect(chill.title == "Chill")
        #expect(chill.endpoint == BrowseEndpoint(browseId: "FEmusic_moods_and_genres_category", params: "ggMPOg1uX1JOQWZFeDByc2Jm"))
        #expect(chill.colorHex == 0xA4C5FF)
    }

    @Test func related() throws {
        let shelves = Parse.shelves(Parse.sectionList(of: try fixture("related"))["contents"].array)
        #expect(shelves.map(\.title) == [
            "You might also like", "Recommended playlists", "Other performances", "Similar artists", "Oasis", "About the artist",
        ])
        #expect(shelves.map(\.layout) == [.columns, .carousel, .columns, .carousel, .carousel, .text])
        #expect(shelves[4].strapline == "MORE FROM")
        #expect(shelves[4].more?.browseId == "UCmMUZbaYdNH0bEd1PAlAqsA")
        #expect(shelves[2].items.contains { $0.song?.isExplicit == true })
        #expect(shelves[5].text?.hasPrefix("Placeholder artist description") == true)
    }
}

@Suite("Page fixtures")
struct PageFixtureTests {
    @Test func album() throws {
        let page = try #require(Parse.albumPage(try fixture("album"), browseId: "MPREb_9nqEki4ZDpp"))
        let album = page.album
        #expect(album.title == "(What's The Story) Morning Glory? (Remastered)")
        #expect(album.artists == [ArtistRef(name: "Oasis", id: "UCmMUZbaYdNH0bEd1PAlAqsA")])
        #expect(album.year == "1995")
        #expect(album.typeText == "Album")
        #expect(album.playlistId == "OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y")
        #expect(album.artwork.thumbnails.map(\.width) == [60, 120, 226, 544])
        #expect(page.statsText == "40 songs • 2 hours, 49 minutes")
        #expect(page.description?.hasPrefix("Placeholder album description") == true)
        #expect(page.tracks.count == 40)
        #expect(page.tracks.allSatisfy { $0.duration != nil && $0.setVideoId != nil })
        let hello = page.tracks[0]
        #expect(hello.videoId == "7HF1Sfos3v4")
        #expect(hello.title == "Hello")
        #expect(hello.duration == 204)
        #expect(hello.setVideoId == "AFA972E043EDE82F")
        #expect(hello.playsText == "14M plays")
        #expect(hello.artists == album.artists)
        #expect(hello.album == AlbumRef(name: album.title, id: "MPREb_9nqEki4ZDpp"))
        #expect(hello.artwork == album.artwork)
        #expect(page.shelves.map(\.title) == ["Other versions", "Releases for you"])
        #expect(page.shelves[1].items.contains { $0.album?.isExplicit == true })
    }

    @Test func artist() throws {
        let (page, channelId) = try #require(Parse.artistPage(try fixture("artist"), browseId: "UCmMUZbaYdNH0bEd1PAlAqsA"))
        #expect(page.artist.name == "Oasis")
        #expect(page.artist.id == "UCmMUZbaYdNH0bEd1PAlAqsA")
        #expect(channelId == "UCUDVBtnOQi4c7E8jebpjc9Q")
        #expect(page.subscribersText == "4.68M subscribers")
        #expect(page.monthlyListenersText == "31.6M monthly audience")
        #expect(page.isSubscribed == false)
        #expect(page.banner.aspectRatio > 2)
        #expect(page.shuffle?.playlistId == "RDAOkjHYJjL1a3xspEyVkhHAsg")
        #expect(page.radio == WatchEndpoint(videoId: "t0BFye8j43s", playlistId: "RDEMkjHYJjL1a3xspEyVkhHAsg", params: "wAEB"))
        #expect(page.description?.hasPrefix("Placeholder artist description") == true)
        #expect(page.shelves.map(\.title) == [
            "Top songs", "Albums", "Singles & EPs", "Videos", "Latest episodes", "Podcasts", "Live performances",
            "Featured on", "Playlists by Oasis", "Fans might also like", "About",
        ])
        let top = page.shelves[0]
        #expect(top.layout == .list)
        // The link as YouTube Music gives it: the playlist's browse id (`VL…`) with the page's params.
        #expect(top.more == BrowseEndpoint(
            browseId: "VLOLAK5uy_kGykcqHKDHRHuJ2sE8EcduYEr1FtafoQs", params: "ggMCCAI%3D", pageType: .playlist
        ))
        #expect(top.items.compactMap(\.song?.title).prefix(2) == ["Wonderwall", "Don't Look Back in Anger"])
        #expect(page.shelves[1].more?.browseId == "MPADUCmMUZbaYdNH0bEd1PAlAqsA")
        #expect(try #require(page.shelves[2].items[1].album).typeText == "EP")
        #expect(page.shelves[3].items.allSatisfy { $0.song?.kind == .video })
        #expect(page.shelves[9].items.allSatisfy { $0.artist != nil })
        #expect(page.shelves.last?.layout == .text)
    }

    @Test func playlist() throws {
        let page = try #require(Parse.playlistPage(try fixture("playlist"), playlistId: "RDCLAK5uy_kIHyENe_MJL0ztoFsyFrKTZFQMsVmXzWo"))
        #expect(page.playlist.id == "RDCLAK5uy_kIHyENe_MJL0ztoFsyFrKTZFQMsVmXzWo")
        #expect(page.playlist.title == "Presenting Oasis")
        #expect(page.authorName == "YouTube Music")
        #expect(page.statsText == "51 songs • 4 hours, 5 minutes")
        #expect(page.description == "The most played hits and essential tracks.")
        #expect(page.isEditable == false)
        #expect(page.tracks.count == 51)
        #expect(page.continuation == nil)
        #expect(Parse.sectionContinuation(try fixture("playlist")) != nil)
        let first = page.tracks[0]
        #expect(first.videoId == "P7yS26LVcLs")
        #expect(first.title == "Slide Away")
        #expect(first.duration == 383)
        #expect(first.setVideoId == "56B44F6D10557CC6")
        #expect(first.artists.map(\.id) == ["UCmMUZbaYdNH0bEd1PAlAqsA"])
        #expect(page.tracks.contains { $0.isExplicit })
    }

    @Test func playlistTracksScrollOn() throws {
        let page = try #require(Parse.playlistPage(try fixture("playlist_big"), playlistId: "RDCLAK5uy_mgHrXs_5F6wPwPFA47S8yrzCfjCi4AXDE"))
        #expect(page.playlist.title == "All-Time Alt")
        #expect(page.statsText == "160 songs • 6+ hours")
        #expect(page.tracks.count == 40)
        // The newer token form: a trailing continuationItemRenderer.
        #expect(page.continuation?.hasPrefix("4qmFsgKNARItVkxSRENMQUs1dXlf") == true)

        let more = try #require(Parse.songsContinuation(try fixture("playlist_tracks_cont")))
        #expect(more.songs.count == 60)
        #expect(more.continuation == nil)
        #expect(more.songs[0].title == "Lonely Boy")
        #expect(more.songs.allSatisfy { $0.setVideoId != nil })

        let related = try #require(Parse.browseContinuation(try fixture("playlist_cont")))
        #expect(related.shelves.map(\.title) == ["Related playlists"])
        #expect(related.shelves[0].items.count == 10)
    }

    @Test func radioQueue() throws {
        let queue = try #require(Parse.watchQueue(try fixture("watch_radio"), requested: WatchEndpoint(videoId: "rj5wZqReXQE")))
        #expect(queue.songs.count == 50)
        #expect(queue.currentIndex == 0)
        #expect(queue.playlistId == "RDAMVMrj5wZqReXQE")
        #expect(queue.lyricsBrowseId == "MPLYt_9nqEki4ZDpp-3")
        #expect(queue.relatedBrowseId == "MPTRt_9nqEki4ZDpp-3")
        // The legacy radio form: continuations[0].nextRadioContinuationData.
        #expect(queue.continuation?.hasPrefix("CDISPhILd0poV3d0MU9tVDgi") == true)
        let first = queue.songs[0]
        #expect(first.videoId == "rj5wZqReXQE")
        #expect(first.kind == .song)
        #expect(first.duration == 259)
        #expect(first.album?.id == "MPREb_9nqEki4ZDpp")
        #expect(first.artists == [ArtistRef(name: "Oasis", id: "UCmMUZbaYdNH0bEd1PAlAqsA")])
        #expect(first.setVideoId == "56B44F6D10557CC6")
        #expect(first.artwork.thumbnails.count == 6)

        let more = try #require(Parse.songsContinuation(try fixture("watch_cont")))
        #expect(more.songs.count == 49)
        #expect(more.continuation != nil)
    }

    @Test func playlistQueue() throws {
        let queue = try #require(Parse.watchQueue(
            try fixture("watch_playlist"), requested: WatchEndpoint(playlistId: "OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y")
        ))
        #expect(queue.songs.count == 40)
        #expect(queue.currentIndex == 0)
        #expect(queue.playlistId == "OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y")
        #expect(queue.continuation == nil)
        #expect(queue.lyricsBrowseId == "MPLYt_kEaQ8Pm2AvV")
        #expect(queue.relatedBrowseId == "MPTRt_kEaQ8Pm2AvV")
        #expect(queue.songs[2].title == "Wonderwall")
        #expect(queue.songs[2].duration == 280)
        // Video bylines read "Oasis • 7.1M views • 49K likes": the views are kept, the likes are not plays.
        #expect(queue.songs[0].playsText == "7.1M views")
        #expect(queue.songs.allSatisfy { $0.playsText.map { !$0.contains("like") } ?? true })
    }

    /// The lyrics in the test file are placeholder lines (no song's words are kept in the repository); the shape of
    /// the answer is the real one.
    @Test func lyrics() throws {
        let lyrics = try #require(Parse.lyrics(try fixture("lyrics")))
        #expect(lyrics.source == "Source: Musixmatch")
        #expect(lyrics.isTimed == false)
        #expect(lyrics.lines.first?.text == "Placeholder lyric line 1")
        #expect(lyrics.lines.count == 50)
        #expect(lyrics.lines[6].text.isEmpty)
        #expect(lyrics.lines.last?.text == "Placeholder lyric line 42")
    }

    @Test func timedLyrics() throws {
        let response: JSON = ["contents": ["elementRenderer": ["newElement": ["type": ["componentType": ["model": [
            "timedLyricsModel": ["lyricsData": [
                "timedLyricsData": [
                    ["lyricLine": "Placeholder timed line 1", "cueRange": ["startTimeMilliseconds": "15200"]],
                    ["lyricLine": "Placeholder timed line 2", "cueRange": ["startTimeMilliseconds": "19000"]],
                ],
                "sourceMessage": "Source: LyricFind",
            ]],
        ]]]]]]]
        let lyrics = try #require(Parse.lyrics(response))
        #expect(lyrics.isTimed)
        #expect(lyrics.lines.map(\.start) == [15.2, 19])
        #expect(lyrics.source == "Source: LyricFind")
    }
}

@Suite("Parsing pieces")
struct ParsePieceTests {
    @Test(arguments: [
        ("3:45", 225.0), ("0:07", 7), ("1:02:03", 3723), ("1 hr 40 min", 6000), ("52 min", 3120), ("45 sec", 45),
    ])
    func durations(text: String, seconds: Double) {
        #expect(Parse.duration(text) == seconds)
    }

    @Test(arguments: ["", " ", "2,343", "abc", "3:4a", "1995"])
    func notDurations(text: String) {
        #expect(Parse.duration(text) == nil)
    }

    @Test func songLine() {
        let runs: [JSON] = [
            ["text": "Song"], ["text": " • "],
            ["text": "Artist A", "navigationEndpoint": ["browseEndpoint": ["browseId": "UCa"]]], ["text": " & "],
            ["text": "Artist B"], ["text": " • "],
            ["text": "The Album", "navigationEndpoint": ["browseEndpoint": ["browseId": "MPREb_x"]]], ["text": " • "],
            ["text": "3:05"], ["text": " • "], ["text": "2019"], ["text": " • "], ["text": "12M plays"],
        ]
        let info = Parse.songInfo(runs, skipTypeSpec: true)
        #expect(info.typeText == "Song")
        #expect(info.artists == [ArtistRef(name: "Artist A", id: "UCa"), ArtistRef(name: "Artist B", id: nil)])
        #expect(info.album == AlbumRef(name: "The Album", id: "MPREb_x"))
        #expect(info.duration == 185)
        #expect(info.year == "2019")
        #expect(info.countText == "12M plays")
        // Without the type word, an unlinked first name stays an artist.
        #expect(Parse.songInfo(Array(runs.dropFirst(2)), skipTypeSpec: true).artists.count == 2)
        // A filtered result line "Oasis • Album • 3:05" whose artist is not a link keeps the artist.
        let unlinked: [JSON] = [
            ["text": "Oasis"], ["text": " • "],
            ["text": "The Album", "navigationEndpoint": ["browseEndpoint": ["browseId": "MPREb_x"]]], ["text": " • "],
            ["text": "3:05"],
        ]
        let filtered = Parse.songInfo(unlinked, skipTypeSpec: true)
        #expect(filtered.artists == [ArtistRef(name: "Oasis")])
        #expect(filtered.typeText == nil)
        // Unfiltered rows always start with the kind, in whatever language: any first word is skipped there.
        let localized: [JSON] = [["text": "गाना"], ["text": " • "], ["text": "Oasis"]]
        #expect(Parse.songInfo(localized, skipTypeSpec: true, anyTypeWord: true).artists == [ArtistRef(name: "Oasis")])
    }

    @Test func hostileNumbersDoNotTrap() throws {
        let json = try JSON(data: Data(#"{"big": 1e300, "neg": -5.7, "frac": 2.5, "int": 42}"#.utf8))
        #expect(json["big"].int == nil)
        #expect(json["big"].string != nil)
        #expect(json["neg"].int == -5)
        #expect(json["frac"].string == "2.5")
        #expect(json["int"].string == "42")
        let tile: JSON = ["buttonText": ["runs": [["text": "Chill"]]], "solid": ["leftStripeColor": -1]]
        #expect(Parse.navigationChip(tile)?.colorHex == nil)
        let huge: JSON = ["buttonText": ["runs": [["text": "Chill"]]], "solid": ["leftStripeColor": .number(1e30)]]
        #expect(Parse.navigationChip(huge)?.colorHex == nil)
        let argb: JSON = ["buttonText": ["runs": [["text": "Chill"]]], "solid": ["leftStripeColor": .number(Double(0xFFFF_9800))]]
        #expect(Parse.navigationChip(argb)?.colorHex == 0xFF9800)
        #expect(Parse.searchFilter(params: "EgWKAQ") == nil)
        #expect(Parse.searchFilter(params: "") == nil)
        #expect(Parse.searchFilter(params: "!!!") == nil)
    }

    @Test func oneListPageScrollsOnThroughThePage() throws {
        func row(_ id: String) -> JSON {
            ["musicResponsiveListItemRenderer": [
                "flexColumns": [["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [["text": .string("Song \(id)")]]]]]],
                "playlistItemData": ["videoId": .string(id)],
            ]]
        }
        // A library tab: one shelf whose token is on the shelf, not on the section list.
        let page = Parse.browsePage(["contents": ["singleColumnBrowseResultsRenderer": ["tabs": [["tabRenderer": [
            "content": ["sectionListRenderer": ["contents": [["musicShelfRenderer": [
                "contents": [row("a"), row("b")],
                "continuations": [["nextContinuationData": ["continuation": "NEXT1"]]],
            ]]]]],
        ]]]]]])
        #expect(page.shelves.count == 1)
        #expect(page.continuation == "NEXT1")

        let more = try #require(Parse.browseContinuation(["continuationContents": ["musicShelfContinuation": [
            "contents": [row("c"), row("d")],
            "continuations": [["nextContinuationData": ["continuation": "NEXT2"]]],
        ]]]))
        #expect(more.continuation == "NEXT2")
        #expect(more.shelves.count == 1)
        #expect(more.shelves[0].layout == .list)
        #expect(more.shelves[0].items.compactMap(\.song?.videoId) == ["c", "d"])
        #expect(more.shelves[0].id != page.shelves[0].id)

        let grid = try #require(Parse.browseContinuation(["continuationContents": ["gridContinuation": ["items": [
            ["musicTwoRowItemRenderer": [
                "title": ["runs": [["text": "An album"]]],
                "navigationEndpoint": ["browseEndpoint": ["browseId": "MPREb_x"]],
            ]],
        ]]]]))
        #expect(grid.shelves.first?.layout == .grid)
        #expect(grid.shelves.first?.items.first?.album?.id == "MPREb_x")
        #expect(grid.continuation == nil)
    }

    @Test func twoColumnPagesReadBothLists() {
        func carousel(_ title: String, _ album: String) -> JSON {
            ["musicCarouselShelfRenderer": [
                "header": ["musicCarouselShelfBasicHeaderRenderer": ["title": ["runs": [["text": .string(title)]]]]],
                "contents": [["musicTwoRowItemRenderer": [
                    "title": ["runs": [["text": "An album"]]],
                    "navigationEndpoint": ["browseEndpoint": ["browseId": .string(album)]],
                ]]],
            ]]
        }
        // Shelves in the tab, more in `secondaryContents`, the token on the secondary list.
        let response: JSON = ["contents": ["twoColumnBrowseResultsRenderer": [
            "tabs": [["tabRenderer": ["content": ["sectionListRenderer": ["contents": [
                ["musicResponsiveHeaderRenderer": ["title": ["runs": [["text": "Header"]]]]],
                carousel("In the tab", "MPREb_a"),
            ]]]]]],
            "secondaryContents": ["sectionListRenderer": [
                "contents": [carousel("Secondary", "MPREb_b")],
                "continuations": [["nextContinuationData": ["continuation": "MORE"]]],
            ]],
        ]]]
        let page = Parse.browsePage(response)
        #expect(page.shelves.map(\.title) == ["In the tab", "Secondary"])
        #expect(page.continuation == "MORE")
        #expect(page.title == "Header")
    }

    @Test func idConventions() {
        #expect(Parse.playlistBrowseId("PLabc") == "VLPLabc")
        #expect(Parse.playlistBrowseId("VLPLabc") == "VLPLabc")
        #expect(Parse.playlistBrowseId("LM") == "VLLM")
        #expect(Parse.playlistBrowseId("MPSPPLabc") == "VLPLabc")
        #expect(Parse.artistBrowseId("MPLAUCabc") == "UCabc")
        #expect(Parse.artistBrowseId("UCabc") == "UCabc")
        // A channel section ("Live performances") opens with `browse`, the channel itself with `artist(id:)`.
        let section: JSON = ["browseEndpoint": ["browseId": "UCabc", "params": "6gP", "browseEndpointContextSupportedConfigs": [
            "browseEndpointContextMusicConfig": ["pageType": "MUSIC_PAGE_TYPE_ARTIST"],
        ]]]
        #expect(Parse.moreEndpoint(in: section)?.pageType == .generic)
        #expect(Parse.browseEndpoint(in: section)?.pageType == .artist)
        let channel: JSON = ["browseEndpoint": ["browseId": "UCabc", "browseEndpointContextSupportedConfigs": [
            "browseEndpointContextMusicConfig": ["pageType": "MUSIC_PAGE_TYPE_ARTIST"],
        ]]]
        #expect(Parse.moreEndpoint(in: channel) == BrowseEndpoint(browseId: "UCabc", pageType: .artist))
    }

    @Test func unknownRenderersAreSkipped() {
        let response: JSON = ["contents": ["singleColumnBrowseResultsRenderer": ["tabs": [["tabRenderer": ["content": [
            "sectionListRenderer": ["contents": [
                ["musicBrandNewShelfRenderer": ["contents": [["x": 1]]]],
                ["musicCarouselShelfRenderer": [
                    "header": ["musicCarouselShelfBasicHeaderRenderer": ["title": ["runs": [["text": "Mixed"]]]]],
                    "contents": [
                        ["musicFutureItemRenderer": ["title": "?"]],
                        ["musicTwoRowItemRenderer": ["title": ["runs": [["text": "No link"]]]]],
                        ["musicTwoRowItemRenderer": [
                            "title": ["runs": [["text": "An album"]]],
                            "navigationEndpoint": ["browseEndpoint": ["browseId": "MPREb_abc"]],
                            "subtitle": ["runs": [["text": "Album"], ["text": " • "], ["text": "2024"]]],
                        ]],
                    ],
                ]],
                ["musicCarouselShelfRenderer": ["contents": "not an array"]],
            ]],
        ]]]]]]]
        let page = Parse.browsePage(response)
        #expect(page.shelves.count == 1)
        #expect(page.shelves[0].items == [.album(AlbumItem(id: "MPREb_abc", title: "An album", year: "2024", typeText: "Album"))])
        #expect(Parse.browsePage(["unexpected": true]).shelves.isEmpty)
        #expect(Parse.albumPage(.null, browseId: "x") == nil)
        #expect(Parse.watchQueue(["contents": 3], requested: WatchEndpoint(videoId: "a")) == nil)
    }

    @Test func artworkPrefixesSchemeAndSorts() {
        let artwork = Parse.artwork(list: ["thumbnails": [
            ["url": "//yt3.ggpht.com/a=s176", "width": 176, "height": 176],
            ["url": "https://yt3.ggpht.com/a=s88", "width": 88, "height": 88],
            ["url": "", "width": 1, "height": 1],
        ]])
        #expect(artwork.thumbnails.map(\.width) == [88, 176])
        #expect(artwork.thumbnails[1].url.absoluteString == "https://yt3.ggpht.com/a=s176")
    }

    @Test func bootstrapReadsYtcfg() {
        let html = """
            <script>ytcfg.set({"CSI_SERVICE_NAME":"youtube_music","X":"a}b\\"c"});</script>
            <script nonce="n">ytcfg.set({"INNERTUBE_CLIENT_VERSION":"1.20261110.04.00","VISITOR_DATA":"Cgt\\u0041bc%3D%3D","N":{"o":1}}); window.x = {};</script>
            """
        let config = BootstrapSessionProvider.parseConfig(html: html)
        #expect(config.clientVersion == "1.20261110.04.00")
        #expect(config.visitorData == "CgtAbc%3D%3D")
        #expect(BootstrapSessionProvider.parseConfig(html: "<html>consent</html>").clientVersion == nil)
    }
}
