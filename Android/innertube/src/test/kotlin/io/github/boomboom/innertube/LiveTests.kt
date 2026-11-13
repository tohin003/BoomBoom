package io.github.boomboom.innertube

import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.WatchEndpoint
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// End-to-end calls against the real service, signed out. Off by default; run with `BOOM_LIVE=1 ./gradlew
// :innertube:test`. Answers change from day to day, so these check shapes and sane values, not exact titles.

object Live {
    val provider = BootstrapSessionProvider(language = "en", region = "US", httpClient = recordingClient())
    val api = InnerTubeClient(provider, recordingClient())

    /**
     * With `BOOM_LIVE_RECORD=<folder>`, writes every answer there, numbered, so an answer that parsed oddly can be
     * read afterwards. Without it, a plain client.
     */
    private fun recordingClient(): OkHttpClient {
        val folder = System.getenv("BOOM_LIVE_RECORD")?.let { File(it) } ?: return InnerTubeClient.defaultHttpClient()
        val counter = AtomicInteger(0)
        return InnerTubeClient.defaultHttpClient().newBuilder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val response: Response = chain.proceed(request)
            val data = response.peekBody(Long.MAX_VALUE).bytes()
            val sentBody = Buffer().also { request.body?.writeTo(it) }.readByteArray()
            val body = runCatching { parseJson(sentBody) }.getOrDefault(kotlinx.serialization.json.JsonNull)
            val what = listOfNotNull(
                body["browseId"].string, body["query"].string, body["videoId"].string,
                body["continuation"].string?.let { "cont" },
            ).joinToString("_")
            val name = "%03d_".format(counter.incrementAndGet()) + (request.url.pathSegments.lastOrNull() ?: "x") +
                "_" + what.take(40).replace("/", "-") + ".json"
            runCatching { File(folder, name).writeBytes(data) }
            response
        }).build()
    }
}

private fun live(block: suspend () -> Unit): Unit = runBlocking { block() }

@EnabledIfEnvironmentVariable(named = "BOOM_LIVE", matches = "1")
class LiveTests {
    private val api = Live.api

    // Session

    @Test
    fun bootstrapReadsTheStartPage() = live {
        val session = Live.provider.currentSession()
        assertTrue(session.clientVersion.startsWith("1.2"), session.clientVersion)
        assertTrue(
            session.clientVersion != BootstrapSessionProvider.FALLBACK_CLIENT_VERSION || session.visitorData != null,
            "the page could not be read",
        )
        assertTrue(session.visitorData?.isNotEmpty() == true, "no visitor data")
        assertTrue(!session.isSignedIn)
    }

    // Search

    @Test
    fun suggestions() = live {
        val suggestions = api.searchSuggestions("cold")
        val queries = suggestions.filterIsInstance<io.github.boomboom.models.SearchSuggestion.Query>().map { it.query }
        assertTrue(queries.size >= 3, "${queries.size} queries")
        assertTrue(queries.all { it.text.lowercase().contains("cold") })
        // The typed part is plain, the rest bold.
        assertTrue(queries.any { query -> query.runs.any { it.isBold } && query.runs.joinToString("") { it.text } == query.text })
        assertTrue(queries.all { !it.isFromHistory })
        assertEquals(emptyList(), api.searchSuggestions(""))
        assertEquals(emptyList(), api.searchSuggestions("   "))
    }

    @Test
    fun unfilteredSearch() = live {
        val results = api.search("coldplay yellow", null)
        assertNull(results.filter)
        assertNull(results.continuation)
        val top = assertNotNull(results.shelves.firstOrNull())
        assertEquals(Shelf.Layout.TOP_RESULT, top.layout)
        assertTrue(top.items.isNotEmpty())
        val lists = results.shelves.drop(1)
        assertTrue(lists.size >= 3, "${lists.size} lists")
        assertTrue(lists.all { it.layout == Shelf.Layout.LIST && it.items.isNotEmpty() && it.title.isNotEmpty() })
        // Each kind's shelf says which filter its "Show all" applies.
        assertTrue(lists.any { it.moreFilter == SearchFilter.SONGS })
        // Unfiltered rows carry no duration ("Song • Coldplay", "2.4B plays"); filtered ones do.
        val songs = assertNotNull(lists.firstOrNull { it.moreFilter == SearchFilter.SONGS })
        for (item in songs.items) {
            val song = assertNotNull(item.asSong)
            assertEquals(Song.Kind.SONG, song.kind)
            assertNotNull(song.playsText)
            assertTrue(song.artists.isNotEmpty())
        }
        assertTrue(songs.items.any { item -> item.asSong?.artists?.any { it.id?.startsWith("UC") == true } == true })
        lists.firstOrNull { it.moreFilter == SearchFilter.VIDEOS }?.let { videos ->
            assertTrue(videos.items.all { it.asSong?.kind == Song.Kind.VIDEO })
        }
    }

    @Test
    fun correctedQuery() = live {
        val results = api.search("coldpaly yelow", null)
        assertEquals("coldplay yellow", results.correctedQuery)
        assertEquals(Shelf.Layout.TOP_RESULT, results.shelves.firstOrNull()?.layout)
    }

    @ParameterizedTest
    @EnumSource(value = SearchFilter::class, names = ["SONGS", "ALBUMS", "ARTISTS", "VIDEOS", "COMMUNITY_PLAYLISTS"])
    fun filteredSearch(filter: SearchFilter) = live {
        // "coldplay" finds only a handful of artists, without more to load.
        val results = api.search(if (filter == SearchFilter.ARTISTS) "john" else "coldplay", filter)
        assertEquals(filter, results.filter)
        assertEquals(1, results.shelves.size)
        val shelf = results.shelves[0]
        assertEquals(Shelf.Layout.LIST, shelf.layout)
        assertTrue(shelf.items.size >= 10, "${shelf.items.size} items")
        check(shelf.items, filter)
        // Artist results sometimes come as one page of about 20 without more to load (seen live, not a parse miss).
        if (filter == SearchFilter.ARTISTS && results.continuation == null) return@live
        val token = assertNotNull(results.continuation, "a filtered search scrolls on")
        val more = api.searchContinuation(token)
        assertTrue(more.items.size >= 5, "${more.items.size} more items")
        check(more.items, filter)
        assertNotNull(more.continuation)
    }

    @ParameterizedTest
    @EnumSource(value = SearchFilter::class, names = ["FEATURED_PLAYLISTS", "PODCASTS", "EPISODES", "PROFILES"])
    fun otherFilters(filter: SearchFilter) = live {
        val results = api.search("coldplay", filter)
        assertEquals(filter, results.filter)
        val items = assertNotNull(results.shelves.firstOrNull()?.items)
        assertTrue(items.size >= 3, "${items.size} items")
        for (item in items) {
            when (filter) {
                SearchFilter.FEATURED_PLAYLISTS, SearchFilter.PODCASTS -> assertNotNull(item.asPlaylist, item.title)
                SearchFilter.EPISODES -> assertEquals(Song.Kind.EPISODE, item.asSong?.kind, item.title)
                SearchFilter.PROFILES -> assertTrue(item.asArtist?.id?.startsWith("UC") == true, item.title)
                else -> {}
            }
        }
    }

    @Test
    fun podcastOpens() = live {
        val results = api.search("history podcast", SearchFilter.PODCASTS)
        val show = assertNotNull(results.shelves.firstOrNull()?.items?.mapNotNull { it.asPlaylist }?.firstOrNull())
        // As the router opens a playlist item, and as a `.podcast` link (`MPSP…`).
        val page = api.playlist(show.id)
        assertTrue(page.tracks.isNotEmpty())
        assertTrue(page.tracks.all { it.kind == Song.Kind.EPISODE })
        val browsed = api.browse(BrowseEndpoint("MPSP" + show.id, pageType = BrowseEndpoint.PageType.PODCAST))
        assertTrue(browsed.shelves.any { shelf -> shelf.items.any { it.asSong?.kind == Song.Kind.EPISODE } })
        assertNotNull(browsed.title)
    }

    private fun check(items: List<MusicItem>, filter: SearchFilter) {
        for (item in items) {
            when (filter) {
                SearchFilter.SONGS -> {
                    val song = assertNotNull(item.asSong, "${item.title} is not a song")
                    assertEquals(Song.Kind.SONG, song.kind)
                    assertTrue((song.duration ?: 0.0) > 0)
                    assertTrue(song.artists.isNotEmpty())
                    assertTrue(!song.artwork.isEmpty)
                }
                SearchFilter.VIDEOS -> {
                    val song = assertNotNull(item.asSong, "${item.title} is not a video")
                    assertEquals(Song.Kind.VIDEO, song.kind)
                    assertTrue((song.duration ?: 0.0) > 0)
                    assertTrue(song.artwork.aspectRatio > 1.2)
                }
                SearchFilter.ALBUMS -> {
                    val album = assertNotNull(item.asAlbum, "${item.title} is not an album")
                    assertTrue(album.id.startsWith("MPREb_"))
                    assertNotNull(album.typeText)
                    assertTrue(album.artists.isNotEmpty())
                }
                SearchFilter.ARTISTS -> {
                    val artist = assertNotNull(item.asArtist, "${item.title} is not an artist")
                    assertTrue(artist.id.startsWith("UC"))
                }
                SearchFilter.COMMUNITY_PLAYLISTS -> {
                    val playlist = assertNotNull(item.asPlaylist, "${item.title} is not a playlist")
                    assertTrue(!playlist.id.startsWith("VL"))
                    assertTrue(playlist.id.isNotEmpty())
                }
                else -> {}
            }
        }
    }

    // Browse

    @Test
    fun home() = live {
        val page = api.home(null)
        assertTrue(page.shelves.size >= 2, "${page.shelves.size} shelves")
        assertTrue(page.shelves.all { it.items.isNotEmpty() })
        page.chips.firstOrNull { !it.isSelected && it.endpoint != null }?.let { chip ->
            val filtered = api.home(chip)
            assertTrue(filtered.shelves.isNotEmpty())
        }
        val token = assertNotNull(page.continuation, "Home scrolls on")
        val more = api.browseContinuation(token)
        assertTrue(more.shelves.isNotEmpty())
    }

    @Test
    fun explore() = live {
        val page = api.explore()
        assertTrue(page.shelves.size >= 2, "${page.shelves.size} shelves")
        assertTrue(page.shelves.any { shelf -> shelf.items.any { it.asAlbum != null } })
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["US", "IN"])
    fun charts(country: String?) = live {
        val page = api.charts(country)
        assertTrue(page.shelves.isNotEmpty())
        assertTrue(page.shelves.any { it.items.isNotEmpty() })
    }

    @Test
    fun moodsAndGenres() = live {
        val groups = api.moodsAndGenres()
        assertTrue(groups.size >= 2, "${groups.size} groups")
        val chips = groups.flatMap { it.chips }
        assertTrue(chips.size >= 20, "${chips.size} chips")
        assertTrue(chips.all { it.endpoint?.params != null })
        assertTrue(chips.any { it.colorHex != null })
        val mood = assertNotNull(chips.firstOrNull()?.endpoint)
        val page = api.browse(mood)
        assertTrue(page.shelves.isNotEmpty())
        assertTrue(page.shelves.any { shelf -> shelf.items.any { it.asPlaylist != null } })
    }

    @Test
    fun album() = live {
        val search = api.search("coldplay parachutes", SearchFilter.ALBUMS)
        val found = assertNotNull(search.shelves.firstOrNull()?.items?.mapNotNull { it.asAlbum }?.firstOrNull())
        val page = api.album(found.id)
        assertEquals(found.id, page.album.id)
        assertTrue(page.album.title.isNotEmpty())
        assertTrue(page.album.playlistId?.startsWith("OLAK5uy_") == true)
        assertTrue(page.album.artists.any { it.id?.startsWith("UC") == true })
        assertTrue(!page.album.artwork.isEmpty)
        assertNotNull(page.statsText)
        assertTrue(page.tracks.size >= 5, "${page.tracks.size} tracks")
        for (track in page.tracks) {
            assertTrue((track.duration ?: 0.0) > 0, "${track.title} has no duration")
            assertTrue(track.artists.isNotEmpty())
            assertEquals(found.id, track.album?.id)
            // Albums list some tracks as their official music video (`MUSIC_VIDEO_TYPE_OMV`).
            assertTrue(track.kind == Song.Kind.SONG || track.kind == Song.Kind.VIDEO)
        }
        assertTrue(page.tracks.any { it.kind == Song.Kind.SONG })
    }

    @Test
    fun artistAndItsLinks() = live {
        val search = api.search("coldplay", SearchFilter.ARTISTS)
        val found = assertNotNull(search.shelves.firstOrNull()?.items?.mapNotNull { it.asArtist }?.firstOrNull())
        val page = api.artist(found.id)
        assertEquals(found.id, page.artist.id)
        assertTrue(page.artist.name.lowercase().contains("coldplay"))
        assertTrue(!page.banner.isEmpty)
        assertTrue(page.shuffle != null || page.radio != null)
        assertTrue(page.shelves.size >= 3, "${page.shelves.size} shelves")
        val top = assertNotNull(page.shelves.firstOrNull { it.layout == Shelf.Layout.LIST })
        assertTrue(top.items.all { it.asSong != null })

        // Every link a parsed endpoint carries opens with the call its page type names.
        val more = assertNotNull(top.more, "the top songs shelf links a playlist")
        assertEquals(BrowseEndpoint.PageType.PLAYLIST, more.pageType)
        val playlist = api.playlist(more.browseId)
        assertTrue(playlist.tracks.size >= 10, "${playlist.tracks.size} tracks")
        assertTrue(!playlist.playlist.id.startsWith("VL"))
        val viaBrowse = api.browse(more)
        assertTrue(viaBrowse.shelves.isNotEmpty())

        page.shelves.firstOrNull { shelf -> shelf.items.any { it.asAlbum != null } }?.let { albums ->
            val album = assertNotNull(albums.items.mapNotNull { it.asAlbum }.firstOrNull())
            val albumPage = api.album(album.id)
            assertTrue(albumPage.tracks.isNotEmpty())
            albums.more?.let { all ->
                val allPage = api.browse(all)
                assertTrue(allPage.shelves.any { shelf -> shelf.items.any { it.asAlbum != null } })
            }
        }
        val similar = page.shelves.firstOrNull { shelf -> shelf.items.all { it.asArtist != null } }
        val other = similar?.items?.mapNotNull { it.asArtist }?.firstOrNull()
        if (other != null) {
            val otherPage = api.artist(other.id)
            assertTrue(otherPage.shelves.isNotEmpty())
        }
        (page.radio ?: page.shuffle)?.let { radio ->
            val queue = api.watchQueue(radio)
            assertTrue(queue.songs.size >= 5, "${queue.songs.size} queued")
        }
    }

    /**
     * Opens an endpoint the way the app's router does: albums, artists and playlists by their own call with
     * `endpoint.browseId`, everything else with `browse`. Returns whether the page had content.
     */
    private suspend fun open(endpoint: BrowseEndpoint): Boolean = when (endpoint.pageType) {
        BrowseEndpoint.PageType.ALBUM -> api.album(endpoint.browseId).tracks.isNotEmpty()
        BrowseEndpoint.PageType.ARTIST, BrowseEndpoint.PageType.USER_CHANNEL -> api.artist(endpoint.browseId).shelves.isNotEmpty()
        BrowseEndpoint.PageType.PLAYLIST -> api.playlist(endpoint.browseId).tracks.isNotEmpty()
        BrowseEndpoint.PageType.PODCAST, BrowseEndpoint.PageType.GENERIC -> {
            val page = api.browse(endpoint)
            page.shelves.any { it.items.isNotEmpty() } || page.chips.isNotEmpty()
        }
    }

    @Test
    fun everyMoreLinkOpens() = live {
        val artist = api.artist("UCIaFw5VBEK8qaW6nRpx_qnw") // Coldplay
        val explore = api.explore()
        val queue = api.watchQueue(WatchEndpoint(videoId = "yKNxeF4KMsY"))
        val related = api.related(assertNotNull(queue.relatedBrowseId))
        val links = mutableListOf<Pair<String, BrowseEndpoint>>()
        for (shelf in artist.shelves + explore.shelves + related) shelf.more?.let { links += shelf.title to it }
        for (chip in explore.chips.take(6)) chip.endpoint?.let { links += chip.title to it }
        assertTrue(links.size >= 8, "${links.size} links")
        // The problems are collected, so one broken link does not hide the others.
        val problems = mutableListOf<String>()
        for ((title, endpoint) in links) {
            try {
                if (!open(endpoint)) problems += "$title → $endpoint opened empty"
            } catch (e: MusicAPIError) {
                problems += "$title → $endpoint failed: $e"
            }
        }
        // Items open the same way, with the ids they carry.
        for (shelf in artist.shelves) {
            val item = shelf.items.firstOrNull() ?: continue
            val endpoint = when (item) {
                is MusicItem.OfSong -> continue
                is MusicItem.OfAlbum -> BrowseEndpoint(item.album.id, pageType = BrowseEndpoint.PageType.ALBUM)
                is MusicItem.OfArtist -> BrowseEndpoint(item.artist.id, pageType = BrowseEndpoint.PageType.ARTIST)
                is MusicItem.OfPlaylist -> BrowseEndpoint(item.playlist.id, pageType = BrowseEndpoint.PageType.PLAYLIST)
            }
            try {
                if (!open(endpoint)) problems += "${shelf.title}: ${item.title} opened empty"
            } catch (e: MusicAPIError) {
                problems += "${shelf.title}: ${item.title} failed: $e"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun longPlaylistScrollsOn() = live {
        // A big editorial playlist of the artist's songs is linked from the top songs shelf; a search for a long
        // community playlist backs it up.
        val search = api.search("top 500 songs of all time", SearchFilter.COMMUNITY_PLAYLISTS)
        val candidates = search.shelves.firstOrNull()?.items?.mapNotNull { it.asPlaylist } ?: emptyList()
        var checked = false
        for (candidate in candidates.take(4)) {
            val page = api.playlist(candidate.id)
            val token = page.continuation ?: continue
            assertTrue(page.tracks.size >= 50, "${page.tracks.size} tracks")
            assertEquals(candidate.id, page.playlist.id)
            assertTrue(page.playlist.title.isNotEmpty())
            assertTrue(page.tracks.all { it.videoId.isNotEmpty() && it.title.isNotEmpty() })
            assertTrue(page.tracks.count { it.duration != null } >= page.tracks.size * 9 / 10)
            val more = api.playlistContinuation(token)
            assertTrue(more.songs.size >= 20, "${more.songs.size} more songs")
            assertTrue(more.songs.map { it.videoId }.toSet().intersect(page.tracks.take(20).map { it.videoId }.toSet()).isEmpty())
            checked = true
            break
        }
        assertTrue(checked, "no long playlist found to scroll")
    }

    // Playing

    @Test
    fun radioQueueLyricsAndRelated() = live {
        val videoId = "yKNxeF4KMsY" // Coldplay, "Yellow"
        val queue = api.watchQueue(WatchEndpoint(videoId = videoId))
        assertTrue(queue.songs.size >= 10, "${queue.songs.size} queued")
        assertEquals(videoId, queue.songs[queue.currentIndex].videoId)
        assertTrue(queue.playlistId?.startsWith("RDAMVM") == true)
        assertTrue(queue.songs.all { (it.duration ?: 0.0) > 0 && it.artists.isNotEmpty() })
        val token = assertNotNull(queue.continuation)
        val more = api.watchQueueContinuation(token)
        assertTrue(more.songs.size >= 5, "${more.songs.size} more songs")

        val lyricsId = assertNotNull(queue.lyricsBrowseId)
        val lyrics = api.lyrics(lyricsId)
        assertTrue(lyrics.lines.size >= 10, "${lyrics.lines.size} lines")
        // The song's own title turns up in its words (taken from the answer, so no song's words live in this file).
        val title = queue.songs[queue.currentIndex].title.lowercase()
        assertTrue(lyrics.lines.any { it.text.lowercase().contains(title) }, "the title is not in the lyrics")
        assertNotNull(lyrics.source)

        val relatedId = assertNotNull(queue.relatedBrowseId)
        val related = api.related(relatedId)
        assertTrue(related.isNotEmpty())
        assertTrue(related.any { it.items.isNotEmpty() })
    }

    @Test
    fun queueInsideAPlaylist() = live {
        val search = api.search("coldplay parachutes", SearchFilter.ALBUMS)
        val found: AlbumItem = assertNotNull(search.shelves.firstOrNull()?.items?.mapNotNull { it.asAlbum }?.firstOrNull())
        val album = api.album(found.id)
        val playlistId = assertNotNull(album.album.playlistId)
        val third = assertNotNull(album.tracks.drop(2).firstOrNull())
        val queue = api.watchQueue(WatchEndpoint(videoId = third.videoId, playlistId = playlistId))
        assertEquals(playlistId, queue.playlistId)
        assertEquals(third.videoId, queue.songs[queue.currentIndex].videoId)
        assertEquals(2, queue.currentIndex)
        assertEquals(album.tracks.map { it.videoId }, queue.songs.take(album.tracks.size).map { it.videoId })
        // The whole playlist from its start.
        val whole = api.watchQueue(WatchEndpoint(playlistId = playlistId))
        assertEquals(album.tracks.firstOrNull()?.videoId, whole.songs.firstOrNull()?.videoId)
        assertEquals(0, whole.currentIndex)
    }

    // Signed out

    @Test
    fun signedOutAccountCalls() = live {
        assertNull(api.account())
        assertFailsWith<MusicAPIError.SignedOut> { api.library(io.github.boomboom.models.LibrarySection.SONGS) }
        assertFailsWith<MusicAPIError.SignedOut> { api.likedSongs() }
        assertFailsWith<MusicAPIError.SignedOut> { api.rate("yKNxeF4KMsY", LikeStatus.LIKE) }
    }
}
