package io.github.boomboom.innertube

import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.AlbumRef
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.SuggestedQuery
import io.github.boomboom.models.WatchEndpoint
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Parsers against real answers recorded from the live service (signed out, hl=en, gl=US, from an Indian IP). The
// recorded files were edited afterwards: one song's lyrics and the long album and artist write-ups are short
// placeholder texts, and these tests assert the placeholders.

class SearchFixtureTests {
    @Test
    fun suggestions() {
        val suggestions = Parse.searchSuggestions(fixture("suggestions"))
        val queries = suggestions.filterIsInstance<SearchSuggestion.Query>().map { it.query }
        val items = suggestions.filterIsInstance<SearchSuggestion.Item>().map { it.item }
        assertEquals(
            listOf(
                "taylor swift", "taylor swift songs", "taylor swift love story", "taylor swift ophelia",
                "taylor swift lover", "taylor swift playlist",
            ),
            queries.map { it.text },
        )
        assertEquals(
            listOf(SuggestedQuery.Run("taylor sw", true), SuggestedQuery.Run("ift", false)),
            queries[0].runs,
        )
        assertTrue(queries.all { !it.isFromHistory })
        assertEquals(5, items.size)
        val artist = assertNotNull(items[0].asArtist)
        assertEquals("UCPC0L1d253x-KuMNwa05TpA", artist.id)
        assertEquals("Taylor Swift", artist.name)
        assertEquals("408M monthly audience", artist.detailText)
        val song = assertNotNull(items[1].asSong)
        assertEquals("aZ1hziFhj1o", song.videoId)
        assertEquals("Love Story", song.title)
        assertEquals(Song.Kind.SONG, song.kind)
        assertEquals(listOf(ArtistRef("Taylor Swift", "UCPC0L1d253x-KuMNwa05TpA")), song.artists)
        assertEquals(AlbumRef("Fearless (Platinum Edition)", "MPREb_u721jSRXVRq"), song.album)
        assertEquals("1.2B plays", song.playsText)
        val album = assertNotNull(items[3].asAlbum)
        assertEquals("Lover", album.title)
        assertEquals("2019", album.year)
        assertEquals("Album", album.typeText)
        // Queries come first, then the items, as YouTube Music lists them.
        assertEquals(6, suggestions.indexOfFirst { it is SearchSuggestion.Item })
    }

    @Test
    fun historySuggestionIsFlagged() {
        val response = obj(
            "contents" to arr(
                obj(
                    "searchSuggestionsSectionRenderer" to obj(
                        "contents" to arr(
                            obj(
                                "historySuggestionRenderer" to obj(
                                    "suggestion" to obj("runs" to arr(obj("text" to "oasis"))),
                                    "navigationEndpoint" to obj("searchEndpoint" to obj("query" to "oasis")),
                                ),
                            ),
                            obj("someNewRenderer" to obj("whatever" to 1)),
                        ),
                    ),
                ),
            ),
        )
        val suggestions = Parse.searchSuggestions(response)
        assertEquals(
            listOf<SearchSuggestion>(
                SearchSuggestion.Query(
                    SuggestedQuery("oasis", listOf(SuggestedQuery.Run("oasis", false)), isFromHistory = true),
                ),
            ),
            suggestions,
        )
    }

    @Test
    fun unfilteredSearchFlatLayout() {
        val results = Parse.searchResults(fixture("search_all"), "oasis wonderwall", null)
        assertNull(results.continuation)
        assertEquals(
            listOf(
                "Top result", "Artists", "Songs", "Videos", "Albums", "Community playlists", "Episodes", "Profiles",
                "Featured playlists", "Podcasts",
            ),
            results.shelves.map { it.title },
        )
        val top = results.shelves[0]
        assertEquals(Shelf.Layout.TOP_RESULT, top.layout)
        assertEquals(4, top.items.size)
        val topSong = assertNotNull(top.items[0].asSong)
        assertEquals("6hzrDeceEKc", topSong.videoId)
        assertEquals("Wonderwall", topSong.title)
        assertEquals(Song.Kind.VIDEO, topSong.kind)
        assertEquals(280.0, topSong.duration)
        assertEquals("716M views", topSong.playsText)
        assertEquals(listOf(ArtistRef("Oasis", "UCmMUZbaYdNH0bEd1PAlAqsA")), topSong.artists)

        val songs = results.shelves[2]
        assertEquals(Shelf.Layout.LIST, songs.layout)
        assertEquals(SearchFilter.SONGS, songs.moreFilter)
        assertEquals(5, songs.items.size)
        val first = assertNotNull(songs.items[0].asSong)
        assertEquals("rj5wZqReXQE", first.videoId)
        assertEquals(Song.Kind.SONG, first.kind)
        assertEquals("1.7B plays", first.playsText)
        assertEquals(listOf<String?>("UCmMUZbaYdNH0bEd1PAlAqsA"), first.artists.map { it.id })

        val album = assertNotNull(results.shelves[4].items[0].asAlbum)
        assertEquals("MPREb_9nqEki4ZDpp", album.id)
        assertEquals("Album", album.typeText)
        assertEquals("1995", album.year)
        assertEquals("OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y", album.playlistId)
        assertEquals("Single", assertNotNull(results.shelves[4].items[1].asAlbum).typeText)

        val episode = assertNotNull(results.shelves[6].items[0].asSong)
        assertEquals(Song.Kind.EPISODE, episode.kind)
        assertEquals(listOf("100 головних пісень"), episode.artists.map { it.name })
        assertEquals("@VitalyLvov", assertNotNull(results.shelves[7].items[0].asArtist).detailText)
        assertEquals(
            "RDCLAK5uy_mgHrXs_5F6wPwPFA47S8yrzCfjCi4AXDE",
            assertNotNull(results.shelves[8].items[0].asPlaylist).id,
        )
        assertEquals("PLEkYVqCNcvxg", assertNotNull(results.shelves[9].items[0].asPlaylist).id)
    }

    @Test
    fun songsFilteredSearch() {
        val results = Parse.searchResults(fixture("search_songs"), "oasis wonderwall", SearchFilter.SONGS)
        assertEquals(1, results.shelves.size)
        val shelf = results.shelves[0]
        assertEquals("Songs", shelf.title)
        assertEquals(20, shelf.items.size)
        assertTrue(shelf.items.all { it.asSong?.kind == Song.Kind.SONG })
        assertTrue(shelf.items.all { it.asSong?.duration != null })
        assertTrue(results.continuation?.startsWith("EqkDEhBvYXNpcyB3b25kZXJ3YWxs") == true)
        val first = assertNotNull(shelf.items[0].asSong)
        assertEquals("Wonderwall", first.title)
        assertEquals(259.0, first.duration)
        assertEquals(AlbumRef("(What's The Story) Morning Glory? (Remastered)", "MPREb_9nqEki4ZDpp"), first.album)
    }

    @Test
    fun searchContinuation() {
        val page = assertNotNull(Parse.searchContinuation(fixture("search_cont")))
        assertEquals(20, page.items.size)
        assertNotNull(page.continuation)
        assertTrue(page.items.all { it.asSong != null })
    }

    @Test
    fun filterParamsRoundTrip() {
        for (filter in SearchFilter.entries) {
            assertEquals(filter, Parse.searchFilter(Parse.searchParams(filter)))
        }
        // The chips of a live answer use other trailing bytes.
        assertEquals(SearchFilter.SONGS, Parse.searchFilter("EgWKAQIIAWoSEAQQBRADEAkQChAQEBUQDhAR"))
        assertEquals(SearchFilter.ARTISTS, Parse.searchFilter("EgWKAQIgAWoSEAQQBRADEAkQChAQEBUQDhAR"))
        assertNull(Parse.searchFilter("not base64!"))
    }
}

class BrowseFixtureTests {
    @Test
    fun home() {
        val page = Parse.browsePage(fixture("home"))
        assertEquals(
            listOf(
                "Podcasts", "Romance", "Relax", "Feel good", "Energize", "Commute", "Party", "Workout", "Sad", "Focus",
                "Sleep",
            ),
            page.chips.map { it.title },
        )
        assertTrue(page.chips.all { it.endpoint?.browseId == "FEmusic_home" && it.endpoint?.params != null })
        assertNotNull(page.continuation)
        assertEquals(2, page.shelves.size)
        val shelf = page.shelves[0]
        assertEquals("India’s biggest hits", shelf.title)
        assertEquals("MUSIC THAT'S HOT AND HAPPENING!", shelf.strapline)
        assertEquals(Shelf.Layout.CAROUSEL, shelf.layout)
        assertEquals(10, shelf.items.size)
        val playlist = assertNotNull(shelf.items[0].asPlaylist)
        assertEquals("RDCLAK5uy_kCicKSTh7ylcZSwvrN0vV4dI3eqEpXR4A", playlist.id)
        assertEquals("Hits of 2026 (So Far)", playlist.title)
        assertTrue(playlist.artwork.thumbnails.size >= 2)
    }

    @Test
    fun homeContinuationHasQuickPicksColumns() {
        val page = assertNotNull(Parse.browseContinuation(fixture("home_cont")))
        assertNotNull(page.continuation)
        val quickPicks = page.shelves[0]
        assertEquals("Quick picks", quickPicks.title)
        assertEquals(Shelf.Layout.COLUMNS, quickPicks.layout)
        assertEquals(20, quickPicks.items.size)
        val song = assertNotNull(quickPicks.items[0].asSong)
        assertEquals("Choosin' Texas", song.title)
        assertEquals("MPREb_QC0L98k1tGe", song.album?.id)
        assertEquals(io.github.boomboom.models.LikeStatus.INDIFFERENT, song.likeStatus)
        // Signed out, "Save to library" opens a sign-in prompt, but the remove token is there.
        assertNull(song.libraryTokens?.add)
        assertNotNull(song.libraryTokens?.remove)
        assertEquals(false, song.libraryTokens?.isInLibrary)
        assertTrue(page.shelves.any { it.title == "Featured playlists for you" && it.more?.browseId == "FEmusic_moods_and_genres" })
    }

    @Test
    fun explore() {
        val page = Parse.browsePage(fixture("explore"))
        assertEquals(listOf("New releases", "Charts", "Moods & genres", "Podcasts"), page.chips.take(4).map { it.title })
        assertEquals("FEmusic_new_releases", page.chips[0].endpoint?.browseId)
        assertEquals("FEmusic_charts", page.chips[1].endpoint?.browseId)
        val chill = assertNotNull(page.chips.firstOrNull { it.title == "Chill" })
        assertEquals(0xA4C5FF, chill.colorHex)
        assertEquals("FEmusic_moods_and_genres_category", chill.endpoint?.browseId)

        assertEquals(
            listOf("New albums & singles", "Popular episodes", "Trending", "New music videos"),
            page.shelves.map { it.title },
        )
        val releases = page.shelves[0]
        assertEquals("FEmusic_new_releases_albums", releases.more?.browseId)
        assertEquals(24, releases.items.size)
        assertTrue(releases.items.all { it.asAlbum != null })
        val episode = assertNotNull(page.shelves[1].items[0].asSong)
        assertEquals(Song.Kind.EPISODE, episode.kind)
        assertEquals(6000.0, episode.duration)
        assertEquals(listOf("Club Shay Shay"), episode.artists.map { it.name })
        assertEquals(Shelf.Layout.RANKED, page.shelves[2].layout)
        assertTrue(page.shelves[3].items.all { it.asSong?.kind == Song.Kind.VIDEO })
    }

    @Test
    fun charts() {
        val page = Parse.browsePage(fixture("charts"))
        assertEquals("Charts", page.title)
        assertEquals(
            listOf("Video charts", "Genres", "Top artists", "Weekly top podcast shows"),
            page.shelves.map { it.title },
        )
        val artists = page.shelves[2]
        assertEquals(Shelf.Layout.RANKED, artists.layout)
        assertEquals(40, artists.items.size)
        val first = assertNotNull(artists.items[0].asArtist)
        assertEquals("UCR28YDxjDE3ogQROaNdnRbQ", first.id)
        assertEquals("YoungBoy Never Broke Again", first.name)
        assertEquals("15.8M subscribers", first.detailText)
        assertEquals("PL4fGSI1pDJn4yCNzulPkUbxgr4pl0gmI-", assertNotNull(page.shelves[0].items[0].asPlaylist).id)
    }

    @Test
    fun moodsAndGenres() {
        val groups = Parse.chipGroups(fixture("moods"))
        assertEquals(listOf("Moods & moments", "Genres"), groups.map { it.title })
        assertEquals(listOf(12, 38), groups.map { it.chips.size })
        val chill = groups[0].chips[0]
        assertEquals("Chill", chill.title)
        assertEquals(BrowseEndpoint("FEmusic_moods_and_genres_category", "ggMPOg1uX1JOQWZFeDByc2Jm"), chill.endpoint)
        assertEquals(0xA4C5FF, chill.colorHex)
    }

    @Test
    fun related() {
        val shelves = Parse.shelves(Parse.sectionList(fixture("related"))["contents"].array)
        assertEquals(
            listOf(
                "You might also like", "Recommended playlists", "Other performances", "Similar artists", "Oasis",
                "About the artist",
            ),
            shelves.map { it.title },
        )
        assertEquals(
            listOf(
                Shelf.Layout.COLUMNS, Shelf.Layout.CAROUSEL, Shelf.Layout.COLUMNS, Shelf.Layout.CAROUSEL,
                Shelf.Layout.CAROUSEL, Shelf.Layout.TEXT,
            ),
            shelves.map { it.layout },
        )
        assertEquals("MORE FROM", shelves[4].strapline)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", shelves[4].more?.browseId)
        assertTrue(shelves[2].items.any { it.asSong?.isExplicit == true })
        assertTrue(shelves[5].text?.startsWith("Placeholder artist description") == true)
    }
}

class PageFixtureTests {
    @Test
    fun album() {
        val page = assertNotNull(Parse.albumPage(fixture("album"), "MPREb_9nqEki4ZDpp"))
        val album = page.album
        assertEquals("(What's The Story) Morning Glory? (Remastered)", album.title)
        assertEquals(listOf(ArtistRef("Oasis", "UCmMUZbaYdNH0bEd1PAlAqsA")), album.artists)
        assertEquals("1995", album.year)
        assertEquals("Album", album.typeText)
        assertEquals("OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y", album.playlistId)
        assertEquals(listOf(60, 120, 226, 544), album.artwork.thumbnails.map { it.width })
        assertEquals("40 songs • 2 hours, 49 minutes", page.statsText)
        assertTrue(page.description?.startsWith("Placeholder album description") == true)
        assertEquals(40, page.tracks.size)
        assertTrue(page.tracks.all { it.duration != null && it.setVideoId != null })
        val hello = page.tracks[0]
        assertEquals("7HF1Sfos3v4", hello.videoId)
        assertEquals("Hello", hello.title)
        assertEquals(204.0, hello.duration)
        assertEquals("AFA972E043EDE82F", hello.setVideoId)
        assertEquals("14M plays", hello.playsText)
        assertEquals(album.artists, hello.artists)
        assertEquals(AlbumRef(album.title, "MPREb_9nqEki4ZDpp"), hello.album)
        assertEquals(album.artwork, hello.artwork)
        assertEquals(listOf("Other versions", "Releases for you"), page.shelves.map { it.title })
        assertTrue(page.shelves[1].items.any { it.asAlbum?.isExplicit == true })
    }

    @Test
    fun artist() {
        val (page, channelId) = assertNotNull(Parse.artistPage(fixture("artist"), "UCmMUZbaYdNH0bEd1PAlAqsA"))
        assertEquals("Oasis", page.artist.name)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", page.artist.id)
        assertEquals("UCUDVBtnOQi4c7E8jebpjc9Q", channelId)
        assertEquals("4.68M subscribers", page.subscribersText)
        assertEquals("31.6M monthly audience", page.monthlyListenersText)
        assertFalse(page.isSubscribed)
        assertTrue(page.banner.aspectRatio > 2)
        assertEquals("RDAOkjHYJjL1a3xspEyVkhHAsg", page.shuffle?.playlistId)
        assertEquals(WatchEndpoint("t0BFye8j43s", "RDEMkjHYJjL1a3xspEyVkhHAsg", "wAEB"), page.radio)
        assertTrue(page.description?.startsWith("Placeholder artist description") == true)
        assertEquals(
            listOf(
                "Top songs", "Albums", "Singles & EPs", "Videos", "Latest episodes", "Podcasts", "Live performances",
                "Featured on", "Playlists by Oasis", "Fans might also like", "About",
            ),
            page.shelves.map { it.title },
        )
        val top = page.shelves[0]
        assertEquals(Shelf.Layout.LIST, top.layout)
        // The link as YouTube Music gives it: the playlist's browse id (`VL…`) with the page's params.
        assertEquals(
            BrowseEndpoint("VLOLAK5uy_kGykcqHKDHRHuJ2sE8EcduYEr1FtafoQs", "ggMCCAI%3D", BrowseEndpoint.PageType.PLAYLIST),
            top.more,
        )
        assertEquals(listOf("Wonderwall", "Don't Look Back in Anger"), top.items.mapNotNull { it.asSong?.title }.take(2))
        assertEquals("MPADUCmMUZbaYdNH0bEd1PAlAqsA", page.shelves[1].more?.browseId)
        assertEquals("EP", assertNotNull(page.shelves[2].items[1].asAlbum).typeText)
        assertTrue(page.shelves[3].items.all { it.asSong?.kind == Song.Kind.VIDEO })
        assertTrue(page.shelves[9].items.all { it.asArtist != null })
        assertEquals(Shelf.Layout.TEXT, page.shelves.last().layout)
    }

    @Test
    fun playlist() {
        val page = assertNotNull(
            Parse.playlistPage(fixture("playlist"), "RDCLAK5uy_kIHyENe_MJL0ztoFsyFrKTZFQMsVmXzWo"),
        )
        assertEquals("RDCLAK5uy_kIHyENe_MJL0ztoFsyFrKTZFQMsVmXzWo", page.playlist.id)
        assertEquals("Presenting Oasis", page.playlist.title)
        assertEquals("YouTube Music", page.authorName)
        assertEquals("51 songs • 4 hours, 5 minutes", page.statsText)
        assertEquals("The most played hits and essential tracks.", page.description)
        assertFalse(page.isEditable)
        assertEquals(51, page.tracks.size)
        assertNull(page.continuation)
        assertNotNull(Parse.sectionContinuation(fixture("playlist")))
        val first = page.tracks[0]
        assertEquals("P7yS26LVcLs", first.videoId)
        assertEquals("Slide Away", first.title)
        assertEquals(383.0, first.duration)
        assertEquals("56B44F6D10557CC6", first.setVideoId)
        assertEquals(listOf<String?>("UCmMUZbaYdNH0bEd1PAlAqsA"), first.artists.map { it.id })
        assertTrue(page.tracks.any { it.isExplicit })
    }

    @Test
    fun playlistTracksScrollOn() {
        val page = assertNotNull(
            Parse.playlistPage(fixture("playlist_big"), "RDCLAK5uy_mgHrXs_5F6wPwPFA47S8yrzCfjCi4AXDE"),
        )
        assertEquals("All-Time Alt", page.playlist.title)
        assertEquals("160 songs • 6+ hours", page.statsText)
        assertEquals(40, page.tracks.size)
        // The newer token form: a trailing continuationItemRenderer.
        assertTrue(page.continuation?.startsWith("4qmFsgKNARItVkxSRENMQUs1dXlf") == true)

        val more = assertNotNull(Parse.songsContinuation(fixture("playlist_tracks_cont")))
        assertEquals(60, more.songs.size)
        assertNull(more.continuation)
        assertEquals("Lonely Boy", more.songs[0].title)
        assertTrue(more.songs.all { it.setVideoId != null })

        val related = assertNotNull(Parse.browseContinuation(fixture("playlist_cont")))
        assertEquals(listOf("Related playlists"), related.shelves.map { it.title })
        assertEquals(10, related.shelves[0].items.size)
    }

    @Test
    fun radioQueue() {
        val queue = assertNotNull(Parse.watchQueue(fixture("watch_radio"), WatchEndpoint(videoId = "rj5wZqReXQE")))
        assertEquals(50, queue.songs.size)
        assertEquals(0, queue.currentIndex)
        assertEquals("RDAMVMrj5wZqReXQE", queue.playlistId)
        assertEquals("MPLYt_9nqEki4ZDpp-3", queue.lyricsBrowseId)
        assertEquals("MPTRt_9nqEki4ZDpp-3", queue.relatedBrowseId)
        // The legacy radio form: continuations[0].nextRadioContinuationData.
        assertTrue(queue.continuation?.startsWith("CDISPhILd0poV3d0MU9tVDgi") == true)
        val first = queue.songs[0]
        assertEquals("rj5wZqReXQE", first.videoId)
        assertEquals(Song.Kind.SONG, first.kind)
        assertEquals(259.0, first.duration)
        assertEquals("MPREb_9nqEki4ZDpp", first.album?.id)
        assertEquals(listOf(ArtistRef("Oasis", "UCmMUZbaYdNH0bEd1PAlAqsA")), first.artists)
        assertEquals("56B44F6D10557CC6", first.setVideoId)
        assertEquals(6, first.artwork.thumbnails.size)

        val more = assertNotNull(Parse.songsContinuation(fixture("watch_cont")))
        assertEquals(49, more.songs.size)
        assertNotNull(more.continuation)
    }

    @Test
    fun playlistQueue() {
        val queue = assertNotNull(
            Parse.watchQueue(
                fixture("watch_playlist"),
                WatchEndpoint(playlistId = "OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y"),
            ),
        )
        assertEquals(40, queue.songs.size)
        assertEquals(0, queue.currentIndex)
        assertEquals("OLAK5uy_lGQfnMNGvYCRdDq9ZLzJV2BJL2aHQsz9Y", queue.playlistId)
        assertNull(queue.continuation)
        assertEquals("MPLYt_kEaQ8Pm2AvV", queue.lyricsBrowseId)
        assertEquals("MPTRt_kEaQ8Pm2AvV", queue.relatedBrowseId)
        assertEquals("Wonderwall", queue.songs[2].title)
        assertEquals(280.0, queue.songs[2].duration)
        // Video bylines read "Oasis • 7.1M views • 49K likes": the views are kept, the likes are not plays.
        assertEquals("7.1M views", queue.songs[0].playsText)
        assertTrue(queue.songs.all { it.playsText?.contains("like")?.not() ?: true })
    }

    /**
     * The lyrics in the test file are placeholder lines (no song's words are kept in the repository); the shape of
     * the answer is the real one.
     */
    @Test
    fun lyrics() {
        val lyrics = assertNotNull(Parse.lyrics(fixture("lyrics")))
        assertEquals("Source: Musixmatch", lyrics.source)
        assertFalse(lyrics.isTimed)
        assertEquals("Placeholder lyric line 1", lyrics.lines.firstOrNull()?.text)
        assertEquals(50, lyrics.lines.size)
        assertTrue(lyrics.lines[6].text.isEmpty())
        assertEquals("Placeholder lyric line 42", lyrics.lines.last().text)
    }

    @Test
    fun timedLyrics() {
        val response = obj(
            "contents" to obj(
                "elementRenderer" to obj(
                    "newElement" to obj(
                        "type" to obj(
                            "componentType" to obj(
                                "model" to obj(
                                    "timedLyricsModel" to obj(
                                        "lyricsData" to obj(
                                            "timedLyricsData" to arr(
                                                obj(
                                                    "lyricLine" to "Placeholder timed line 1",
                                                    "cueRange" to obj("startTimeMilliseconds" to "15200"),
                                                ),
                                                obj(
                                                    "lyricLine" to "Placeholder timed line 2",
                                                    "cueRange" to obj("startTimeMilliseconds" to "19000"),
                                                ),
                                            ),
                                            "sourceMessage" to "Source: LyricFind",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val lyrics = assertNotNull(Parse.lyrics(response))
        assertTrue(lyrics.isTimed)
        assertEquals(listOf<Double?>(15.2, 19.0), lyrics.lines.map { it.start })
        assertEquals("Source: LyricFind", lyrics.source)
    }
}

class ParsePieceTests {
    @ParameterizedTest
    @CsvSource("3:45,225", "0:07,7", "1:02:03,3723", "1 hr 40 min,6000", "52 min,3120", "45 sec,45")
    fun durations(text: String, seconds: Double) {
        assertEquals(seconds, Parse.duration(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "2,343", "abc", "3:4a", "1995"])
    fun notDurations(text: String) {
        assertNull(Parse.duration(text))
    }

    @Test
    fun songLine() {
        fun run(text: String, browseId: String? = null) =
            if (browseId == null) {
                obj("text" to text)
            } else {
                obj("text" to text, "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to browseId)))
            }
        val runs = listOf(
            run("Song"), run(" • "),
            run("Artist A", "UCa"), run(" & "),
            run("Artist B"), run(" • "),
            run("The Album", "MPREb_x"), run(" • "),
            run("3:05"), run(" • "), run("2019"), run(" • "), run("12M plays"),
        )
        val info = Parse.songInfo(runs, skipTypeSpec = true)
        assertEquals("Song", info.typeText)
        assertEquals(listOf(ArtistRef("Artist A", "UCa"), ArtistRef("Artist B", null)), info.artists)
        assertEquals(AlbumRef("The Album", "MPREb_x"), info.album)
        assertEquals(185.0, info.duration)
        assertEquals("2019", info.year)
        assertEquals("12M plays", info.countText)
        // Without the type word, an unlinked first name stays an artist.
        assertEquals(2, Parse.songInfo(runs.drop(2), skipTypeSpec = true).artists.size)
        // A filtered result line "Oasis • Album • 3:05" whose artist is not a link keeps the artist.
        val unlinked = listOf(run("Oasis"), run(" • "), run("The Album", "MPREb_x"), run(" • "), run("3:05"))
        val filtered = Parse.songInfo(unlinked, skipTypeSpec = true)
        assertEquals(listOf(ArtistRef("Oasis")), filtered.artists)
        assertNull(filtered.typeText)
        // Unfiltered rows always start with the kind, in whatever language: any first word is skipped there.
        val localized = listOf(run("गाना"), run(" • "), run("Oasis"))
        assertEquals(
            listOf(ArtistRef("Oasis")),
            Parse.songInfo(localized, skipTypeSpec = true, anyTypeWord = true).artists,
        )
    }

    @Test
    fun hostileNumbersDoNotTrap() {
        val json = parseJson("""{"big": 1e300, "neg": -5.7, "frac": 2.5, "int": 42}""")
        assertNull(json["big"].int)
        assertNotNull(json["big"].string)
        assertEquals(-5, json["neg"].int)
        assertEquals("2.5", json["frac"].string)
        assertEquals("42", json["int"].string)
        fun tile(color: Any) =
            obj("buttonText" to obj("runs" to arr(obj("text" to "Chill"))), "solid" to obj("leftStripeColor" to color))
        assertNull(assertNotNull(Parse.navigationChip(tile(-1))).colorHex)
        assertNull(assertNotNull(Parse.navigationChip(tile(1e30))).colorHex)
        assertEquals(0xFF9800, assertNotNull(Parse.navigationChip(tile(0xFFFF_9800L.toDouble()))).colorHex)
        assertNull(Parse.searchFilter("EgWKAQ"))
        assertNull(Parse.searchFilter(""))
        assertNull(Parse.searchFilter("!!!"))
    }

    @Test
    fun theJsonReaderIsStrict() {
        // A web page where JSON was expected must not parse as a stray value.
        for (text in listOf("<html>", "{\"a\": foo}", "[1, 2, nope]", "", "{\"a\": 1} trailing", "NaN", "007")) {
            assertFailsWith<IllegalArgumentException>(text) { parseJson(text) }
        }
        assertEquals(json(3), parseJson("3"))
        assertEquals(obj("a" to arr(1, 2.5, true, null, "x")), parseJson("""{"a": [1, 2.5, true, null, "x"]}"""))
    }

    @Test
    fun oneListPageScrollsOnThroughThePage() {
        fun row(id: String) = obj(
            "musicResponsiveListItemRenderer" to obj(
                "flexColumns" to arr(
                    obj("musicResponsiveListItemFlexColumnRenderer" to obj("text" to obj("runs" to arr(obj("text" to "Song $id"))))),
                ),
                "playlistItemData" to obj("videoId" to id),
            ),
        )
        // A library tab: one shelf whose token is on the shelf, not on the section list.
        val page = Parse.browsePage(
            obj(
                "contents" to obj(
                    "singleColumnBrowseResultsRenderer" to obj(
                        "tabs" to arr(
                            obj(
                                "tabRenderer" to obj(
                                    "content" to obj(
                                        "sectionListRenderer" to obj(
                                            "contents" to arr(
                                                obj(
                                                    "musicShelfRenderer" to obj(
                                                        "contents" to arr(row("a"), row("b")),
                                                        "continuations" to arr(obj("nextContinuationData" to obj("continuation" to "NEXT1"))),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, page.shelves.size)
        assertEquals("NEXT1", page.continuation)

        val more = assertNotNull(
            Parse.browseContinuation(
                obj(
                    "continuationContents" to obj(
                        "musicShelfContinuation" to obj(
                            "contents" to arr(row("c"), row("d")),
                            "continuations" to arr(obj("nextContinuationData" to obj("continuation" to "NEXT2"))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("NEXT2", more.continuation)
        assertEquals(1, more.shelves.size)
        assertEquals(Shelf.Layout.LIST, more.shelves[0].layout)
        assertEquals(listOf("c", "d"), more.shelves[0].items.mapNotNull { it.asSong?.videoId })
        assertTrue(more.shelves[0].id != page.shelves[0].id)

        val grid = assertNotNull(
            Parse.browseContinuation(
                obj(
                    "continuationContents" to obj(
                        "gridContinuation" to obj(
                            "items" to arr(
                                obj(
                                    "musicTwoRowItemRenderer" to obj(
                                        "title" to obj("runs" to arr(obj("text" to "An album"))),
                                        "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to "MPREb_x")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(Shelf.Layout.GRID, grid.shelves.firstOrNull()?.layout)
        assertEquals("MPREb_x", grid.shelves.firstOrNull()?.items?.firstOrNull()?.asAlbum?.id)
        assertNull(grid.continuation)
    }

    @Test
    fun twoColumnPagesReadBothLists() {
        fun carousel(title: String, album: String) = obj(
            "musicCarouselShelfRenderer" to obj(
                "header" to obj("musicCarouselShelfBasicHeaderRenderer" to obj("title" to obj("runs" to arr(obj("text" to title))))),
                "contents" to arr(
                    obj(
                        "musicTwoRowItemRenderer" to obj(
                            "title" to obj("runs" to arr(obj("text" to "An album"))),
                            "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to album)),
                        ),
                    ),
                ),
            ),
        )
        // Shelves in the tab, more in `secondaryContents`, the token on the secondary list.
        val response = obj(
            "contents" to obj(
                "twoColumnBrowseResultsRenderer" to obj(
                    "tabs" to arr(
                        obj(
                            "tabRenderer" to obj(
                                "content" to obj(
                                    "sectionListRenderer" to obj(
                                        "contents" to arr(
                                            obj("musicResponsiveHeaderRenderer" to obj("title" to obj("runs" to arr(obj("text" to "Header"))))),
                                            carousel("In the tab", "MPREb_a"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "secondaryContents" to obj(
                        "sectionListRenderer" to obj(
                            "contents" to arr(carousel("Secondary", "MPREb_b")),
                            "continuations" to arr(obj("nextContinuationData" to obj("continuation" to "MORE"))),
                        ),
                    ),
                ),
            ),
        )
        val page = Parse.browsePage(response)
        assertEquals(listOf("In the tab", "Secondary"), page.shelves.map { it.title })
        assertEquals("MORE", page.continuation)
        assertEquals("Header", page.title)
    }

    @Test
    fun idConventions() {
        assertEquals("VLPLabc", Parse.playlistBrowseId("PLabc"))
        assertEquals("VLPLabc", Parse.playlistBrowseId("VLPLabc"))
        assertEquals("VLLM", Parse.playlistBrowseId("LM"))
        assertEquals("VLPLabc", Parse.playlistBrowseId("MPSPPLabc"))
        assertEquals("UCabc", Parse.artistBrowseId("MPLAUCabc"))
        assertEquals("UCabc", Parse.artistBrowseId("UCabc"))
        // A channel section ("Live performances") opens with `browse`, the channel itself with `artist(id:)`.
        val section = obj(
            "browseEndpoint" to obj(
                "browseId" to "UCabc",
                "params" to "6gP",
                "browseEndpointContextSupportedConfigs" to obj(
                    "browseEndpointContextMusicConfig" to obj("pageType" to "MUSIC_PAGE_TYPE_ARTIST"),
                ),
            ),
        )
        assertEquals(BrowseEndpoint.PageType.GENERIC, Parse.moreEndpoint(section)?.pageType)
        assertEquals(BrowseEndpoint.PageType.ARTIST, Parse.browseEndpointIn(section)?.pageType)
        val channel = obj(
            "browseEndpoint" to obj(
                "browseId" to "UCabc",
                "browseEndpointContextSupportedConfigs" to obj(
                    "browseEndpointContextMusicConfig" to obj("pageType" to "MUSIC_PAGE_TYPE_ARTIST"),
                ),
            ),
        )
        assertEquals(BrowseEndpoint("UCabc", pageType = BrowseEndpoint.PageType.ARTIST), Parse.moreEndpoint(channel))
    }

    @Test
    fun unknownRenderersAreSkipped() {
        val response = obj(
            "contents" to obj(
                "singleColumnBrowseResultsRenderer" to obj(
                    "tabs" to arr(
                        obj(
                            "tabRenderer" to obj(
                                "content" to obj(
                                    "sectionListRenderer" to obj(
                                        "contents" to arr(
                                            obj("musicBrandNewShelfRenderer" to obj("contents" to arr(obj("x" to 1)))),
                                            obj(
                                                "musicCarouselShelfRenderer" to obj(
                                                    "header" to obj(
                                                        "musicCarouselShelfBasicHeaderRenderer" to obj(
                                                            "title" to obj("runs" to arr(obj("text" to "Mixed"))),
                                                        ),
                                                    ),
                                                    "contents" to arr(
                                                        obj("musicFutureItemRenderer" to obj("title" to "?")),
                                                        obj("musicTwoRowItemRenderer" to obj("title" to obj("runs" to arr(obj("text" to "No link"))))),
                                                        obj(
                                                            "musicTwoRowItemRenderer" to obj(
                                                                "title" to obj("runs" to arr(obj("text" to "An album"))),
                                                                "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to "MPREb_abc")),
                                                                "subtitle" to obj("runs" to arr(obj("text" to "Album"), obj("text" to " • "), obj("text" to "2024"))),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                            obj("musicCarouselShelfRenderer" to obj("contents" to "not an array")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val page = Parse.browsePage(response)
        assertEquals(1, page.shelves.size)
        assertEquals(
            listOf<MusicItem>(MusicItem.OfAlbum(AlbumItem(id = "MPREb_abc", title = "An album", year = "2024", typeText = "Album"))),
            page.shelves[0].items,
        )
        assertTrue(Parse.browsePage(obj("unexpected" to true)).shelves.isEmpty())
        assertNull(Parse.albumPage(JsonNull, "x"))
        assertNull(Parse.watchQueue(obj("contents" to 3), WatchEndpoint(videoId = "a")))
    }

    @Test
    fun artworkPrefixesSchemeAndSorts() {
        val artwork = Parse.artwork(
            obj(
                "thumbnails" to arr(
                    obj("url" to "//yt3.ggpht.com/a=s176", "width" to 176, "height" to 176),
                    obj("url" to "https://yt3.ggpht.com/a=s88", "width" to 88, "height" to 88),
                    obj("url" to "", "width" to 1, "height" to 1),
                ),
            ),
        )
        assertEquals(listOf(88, 176), artwork.thumbnails.map { it.width })
        assertEquals("https://yt3.ggpht.com/a=s176", artwork.thumbnails[1].url)
    }

    @Test
    fun bootstrapReadsYtcfg() {
        val html = """
            <script>ytcfg.set({"CSI_SERVICE_NAME":"youtube_music","X":"a}b\"c"});</script>
            <script nonce="n">ytcfg.set({"INNERTUBE_CLIENT_VERSION":"1.20261110.04.00","VISITOR_DATA":"CgtAbc%3D%3D","N":{"o":1}}); window.x = {};</script>
        """.trimIndent()
        val config = BootstrapSessionProvider.parseConfig(html)
        assertEquals("1.20261110.04.00", config.clientVersion)
        assertEquals("CgtAbc%3D%3D", config.visitorData)
        assertNull(BootstrapSessionProvider.parseConfig("<html>consent</html>").clientVersion)
    }
}
