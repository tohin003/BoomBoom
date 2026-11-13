package io.github.boomboom.innertube

import io.github.boomboom.innertube.FakeTransport.Reply
import io.github.boomboom.models.Chip
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.PlaylistPrivacy
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.Song
import io.github.boomboom.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.CookieJar
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

// What goes over the wire: headers, bodies and error mapping, checked offline against a fake transport. The expected
// bodies are what ytmusicapi sends for the same calls.

private fun Request.bodyJson(): JsonElement {
    val buffer = Buffer()
    checkNotNull(body).writeTo(buffer)
    return parseJson(buffer.readByteArray())
}

class RequestBuilderTests {
    @Test
    fun signedOutRequest() {
        val request = InnerTubeRequest.make("browse", obj("browseId" to "FEmusic_home"), Sessions.signedOut)
        assertEquals("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", request.url.toString())
        assertEquals("POST", request.method)
        // The client the app builds never takes cookies from a jar: the session's go in the header, when signed in.
        assertSame(CookieJar.NO_COOKIES, InnerTubeClient.defaultHttpClient().cookieJar)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("X-Goog-AuthUser"))
        assertEquals("CgtWaXNpdG9yRGF0YQ%3D%3D", request.header("X-Goog-Visitor-Id"))
        assertEquals("67", request.header("X-Youtube-Client-Name"))
        assertEquals("1.20261110.04.00", request.header("X-Youtube-Client-Version"))
        assertEquals("https://music.youtube.com", request.header("Origin"))
        assertEquals("https://music.youtube.com", request.header("X-Origin"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals(InnerTubeSession.DESKTOP_SAFARI_USER_AGENT, request.header("User-Agent"))

        val body = request.bodyJson()
        assertEquals(json("FEmusic_home"), body["browseId"])
        assertEquals(
            obj(
                "clientName" to "WEB_REMIX", "clientVersion" to "1.20261110.04.00", "hl" to "en", "gl" to "US",
                "visitorData" to "CgtWaXNpdG9yRGF0YQ%3D%3D",
            ),
            body["context", "client"],
        )
        assertEquals(obj(), body["context", "user"])
    }

    @Test
    fun noVisitorIdHeaderWithoutVisitorData() {
        val session = InnerTubeSession(clientVersion = "1.2")
        val request = InnerTubeRequest.make("search", obj(), session)
        assertNull(request.header("X-Goog-Visitor-Id"))
        assertEquals(JsonNull, request.bodyJson()["context", "client", "visitorData"])
    }

    @Test
    fun signedInRequest() {
        val request = InnerTubeRequest.make("browse", obj(), Sessions.signedIn, nowSeconds = 1_700_000_000)
        // sha1("1700000000 AbCdEfGhIjKlMnOp/QrStUvWxYz012345 https://music.youtube.com"), computed with Python's
        // hashlib and `shasum`.
        assertEquals("SAPISIDHASH 1700000000_163e661c1ad4177128b563dfbe8b0ebfd1296a14", request.header("Authorization"))
        assertEquals("1", request.header("X-Goog-AuthUser"))
        val cookie = assertNotNull(request.header("Cookie"))
        assertEquals(Sessions.cookies.map { "${it.key}=${it.value}" }.toSet(), cookie.split("; ").toSet())
        assertEquals("CgtWaXNpdG9yRGF0YQ%3D%3D", request.header("X-Goog-Visitor-Id"))

        val body = request.bodyJson()
        assertEquals(obj("onBehalfOfUser" to "111222333444555666777"), body["context", "user"])
        assertEquals(json("IN"), body["context", "client", "gl"])
    }

    @Test
    fun authorizationVector() {
        assertEquals(
            "SAPISIDHASH 1731500000_94cff7c8b4baccd5e5cf9ef2ee3f4f9f38995fa9",
            InnerTubeRequest.authorization("xyz", 1_731_500_000),
        )
    }

    @Test
    fun secure3PAPISIDAloneSignsIn() {
        val session = Sessions.signedOut.copy(cookies = mapOf("__Secure-3PAPISID" to "xyz", "SID" to "s"))
        assertTrue(session.isSignedIn)
        val request = InnerTubeRequest.make("browse", obj(), session, nowSeconds = 1_731_500_000)
        assertEquals("SAPISIDHASH 1731500000_94cff7c8b4baccd5e5cf9ef2ee3f4f9f38995fa9", request.header("Authorization"))
        assertEquals("SID=s; __Secure-3PAPISID=xyz", request.header("Cookie"))
    }

    @Test
    fun cookiesWithoutSAPISIDAreNotSent() {
        val session = Sessions.signedOut.copy(cookies = mapOf("YSC" to "abc", "VISITOR_INFO1_LIVE" to "def"))
        assertFalse(session.isSignedIn)
        val request = InnerTubeRequest.make("browse", obj(), session)
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }
}

/** The call must fail with exactly this [MusicAPIError]. */
private suspend fun expectError(expected: MusicAPIError, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: MusicAPIError) {
        assertEquals(expected, e)
        return
    }
    fail("no error; expected $expected")
}

private class NamedCall(val name: String, val call: suspend () -> Unit) {
    operator fun component1() = name
    operator fun component2() = call
}

/** Runs a suspending test body to its end; the explicit Unit keeps JUnit from seeing a return value. */
private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Unit = runBlocking { block() }

class WireTests {
    // Read calls

    @Test
    fun searchBodies() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        runCatching { api.search("oasis", null) }
        runCatching { api.search("oasis", SearchFilter.SONGS) }
        runCatching { api.search("oasis", SearchFilter.COMMUNITY_PLAYLISTS) }
        runCatching { api.searchContinuation("TOKEN") }
        runCatching { api.searchSuggestions("oas") }
        val sent = transport.sent
        assertEquals(listOf("search", "search", "search", "search", "music/get_search_suggestions"), sent.map { it.endpoint })
        assertEquals(obj("query" to "oasis"), sent[0].payload)
        assertEquals(obj("query" to "oasis", "params" to "EgWKAQIIAWoMEA4QChADEAQQCRAF"), sent[1].payload)
        assertEquals(obj("query" to "oasis", "params" to "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"), sent[2].payload)
        assertEquals(obj("continuation" to "TOKEN"), sent[3].payload)
        assertEquals(obj("input" to "oas"), sent[4].payload)
    }

    @Test
    fun emptySuggestionInputSendsNothing() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        assertEquals(emptyList(), api.searchSuggestions(""))
        assertEquals(emptyList(), api.searchSuggestions("  \n"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun browseBodies() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        val chip = Chip("Relax", BrowseEndpoint("FEmusic_home", "ggM"))
        runCatching { api.home(null) }
        runCatching { api.home(chip) }
        runCatching { api.explore() }
        runCatching { api.charts("IN") }
        runCatching { api.moodsAndGenres() }
        runCatching { api.album("MPREb_abc") }
        runCatching { api.artist("MPLAUCabc") }
        runCatching { api.playlist("PLabc") }
        runCatching { api.playlist("VLPLabc") }
        runCatching { api.browse(BrowseEndpoint("PLabc", pageType = BrowseEndpoint.PageType.PLAYLIST)) }
        runCatching { api.browse(BrowseEndpoint("MPADUCabc", "ggMI", BrowseEndpoint.PageType.GENERIC)) }
        runCatching { api.browseContinuation("TOKEN") }
        runCatching { api.lyrics("MPLYt_abc") }
        runCatching { api.related("MPTRt_abc") }
        val sent = transport.sent
        assertTrue(sent.all { it.endpoint == "browse" })
        assertEquals(
            listOf(
                obj("browseId" to "FEmusic_home"),
                obj("browseId" to "FEmusic_home", "params" to "ggM"),
                obj("browseId" to "FEmusic_explore"),
                obj("browseId" to "FEmusic_charts", "formData" to obj("selectedValues" to arr("IN"))),
                obj("browseId" to "FEmusic_moods_and_genres"),
                obj("browseId" to "MPREb_abc"),
                obj("browseId" to "UCabc"),
                // A playlist answer without a header is retried as a podcast show page.
                obj("browseId" to "VLPLabc"), obj("browseId" to "MPSPPLabc"),
                obj("browseId" to "VLPLabc"), obj("browseId" to "MPSPPLabc"),
                obj("browseId" to "VLPLabc"),
                obj("browseId" to "MPADUCabc", "params" to "ggMI"),
                obj("continuation" to "TOKEN"),
                obj("browseId" to "MPLYt_abc"),
                obj("browseId" to "MPTRt_abc"),
            ),
            sent.map { it.payload },
        )
    }

    @Test
    fun watchBodies() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        val flags = mapOf<String, Any>(
            "enablePersistentPlaylistPanel" to true, "isAudioOnly" to true, "tunerSettingValue" to "AUTOMIX_SETTING_NORMAL",
        )
        fun with(extra: Map<String, Any>) = json(flags + extra)
        runCatching { api.watchQueue(WatchEndpoint(videoId = "vid")) }
        runCatching { api.watchQueue(WatchEndpoint(videoId = "vid", playlistId = "VLOLAK5uy_x")) }
        runCatching { api.watchQueue(WatchEndpoint(playlistId = "RDCLAK5uy_x")) }
        runCatching { api.watchQueue(WatchEndpoint(videoId = "vid", playlistId = "RDAOabc", params = "wAEB8gECGAE%3D")) }
        runCatching { api.watchQueueContinuation("TOKEN") }
        val sent = transport.sent
        assertTrue(sent.all { it.endpoint == "next" })
        // ytmusicapi `get_watch_playlist(videoId, radio=True)`.
        assertEquals(with(mapOf("videoId" to "vid", "playlistId" to "RDAMVMvid", "params" to "wAEB")), sent[0].payload)
        // ytmusicapi `get_watch_playlist(videoId, playlistId)`.
        assertEquals(
            with(
                mapOf(
                    "videoId" to "vid", "playlistId" to "OLAK5uy_x",
                    "watchEndpointMusicSupportedConfigs" to mapOf(
                        "watchEndpointMusicConfig" to mapOf(
                            "hasPersistentPlaylistPanel" to true, "musicVideoType" to "MUSIC_VIDEO_TYPE_ATV",
                        ),
                    ),
                ),
            ),
            sent[1].payload,
        )
        assertEquals(with(mapOf("playlistId" to "RDCLAK5uy_x")), sent[2].payload)
        // A button's own endpoint (an artist's shuffle) is sent as given.
        assertEquals(
            with(mapOf("videoId" to "vid", "playlistId" to "RDAOabc", "params" to "wAEB8gECGAE%3D")),
            sent[3].payload,
        )
        assertEquals(with(mapOf("continuation" to "TOKEN")), sent[4].payload)
    }

    // Mutations

    @Test
    fun rateBodies() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedIn, transport)
        api.rate("vid", LikeStatus.LIKE)
        api.rate("vid", LikeStatus.DISLIKE)
        api.rate("vid", LikeStatus.INDIFFERENT)
        api.ratePlaylist("VLPLabc", LikeStatus.LIKE)
        api.ratePlaylist("OLAK5uy_x", LikeStatus.INDIFFERENT)
        val sent = transport.sent
        assertEquals(
            listOf("like/like", "like/dislike", "like/removelike", "like/like", "like/removelike"),
            sent.map { it.endpoint },
        )
        assertEquals(obj("target" to obj("videoId" to "vid")), sent[0].payload)
        assertEquals(obj("target" to obj("videoId" to "vid")), sent[2].payload)
        assertEquals(obj("target" to obj("playlistId" to "PLabc")), sent[3].payload)
        assertEquals(obj("target" to obj("playlistId" to "OLAK5uy_x")), sent[4].payload)
        assertTrue(sent.all { it.request.header("Authorization")?.startsWith("SAPISIDHASH ") == true })
    }

    @Test
    fun subscribeUsesTheChannelTheArtistPageNames() = test {
        // The page of `UCpage` says its button subscribes to `UCbutton`.
        val artistPage = """
            {"header": {"musicImmersiveHeaderRenderer": {
                "title": {"runs": [{"text": "Band"}]},
                "subscriptionButton": {"subscribeButtonRenderer": {"channelId": "UCbutton", "subscribed": false}}
            }}}
        """.trimIndent()
        val transport = FakeTransport(mapOf("browse" to Reply.Json(200, artistPage)))
        val api = stubbedClient(Sessions.signedIn, transport)
        api.subscribe("UCpage", true)
        api.subscribe("UCpage", false)
        val sent = transport.sent
        // The page is read once, then remembered.
        assertEquals(listOf("browse", "subscription/subscribe", "subscription/unsubscribe"), sent.map { it.endpoint })
        assertEquals(obj("channelIds" to arr("UCbutton")), sent[1].payload)
        assertEquals(obj("channelIds" to arr("UCbutton")), sent[2].payload)
    }

    @Test
    fun subscribeFallsBackToTheGivenId() = test {
        val transport = FakeTransport(mapOf("browse" to Reply.Json(200, "{}")))
        val api = stubbedClient(Sessions.signedIn, transport)
        api.subscribe("MPLAUCother", true)
        assertEquals(obj("channelIds" to arr("UCother")), transport.sent.last().payload)
    }

    @Test
    fun libraryFeedbackBody() = test {
        val transport = FakeTransport(mapOf("feedback" to Reply.Json(200, """{"feedbackResponses": [{"isProcessed": true}]}""")))
        val api = stubbedClient(Sessions.signedIn, transport)
        api.sendLibraryFeedback("AB12_token")
        assertEquals(listOf("feedback"), transport.sent.map { it.endpoint })
        assertEquals(obj("feedbackTokens" to arr("AB12_token")), transport.sent[0].payload)

        transport.reset(mapOf("feedback" to Reply.Json(200, """{"feedbackResponses": [{"isProcessed": false}]}""")))
        assertFailsWith<MusicAPIError.Unreadable> { api.sendLibraryFeedback("x") }
    }

    @Test
    fun playlistEditBodies() = test {
        val transport = FakeTransport(
            mapOf(
                "playlist/create" to Reply.Json(200, """{"playlistId": "PLnew"}"""),
                "browse/edit_playlist" to Reply.Json(200, """{"status": "STATUS_SUCCEEDED"}"""),
                "playlist/delete" to Reply.Json(200, "{}"),
            ),
        )
        val api = stubbedClient(Sessions.signedIn, transport)
        val id = api.createPlaylist("Mix", "For runs", PlaylistPrivacy.UNLISTED, listOf("a", "b"))
        assertEquals("PLnew", id)
        api.createPlaylist("Empty", "", PlaylistPrivacy.PRIVATE, emptyList())
        api.editPlaylist("VLPLnew", "Mix 2", "New words", PlaylistPrivacy.PUBLIC)
        api.editPlaylist("PLnew", null, null, PlaylistPrivacy.PRIVATE)
        api.addToPlaylist("PLnew", listOf("c", "d"))
        api.removeFromPlaylist(
            "VLPLnew",
            listOf(Song("a", "A", setVideoId = "SET_A"), Song("b", "B", setVideoId = "SET_B")),
        )
        api.deletePlaylist("VLPLnew")
        val sent = transport.sent
        assertEquals(
            listOf(
                "playlist/create", "playlist/create", "browse/edit_playlist", "browse/edit_playlist",
                "browse/edit_playlist", "browse/edit_playlist", "playlist/delete",
            ),
            sent.map { it.endpoint },
        )
        assertEquals(
            obj("title" to "Mix", "description" to "For runs", "privacyStatus" to "UNLISTED", "videoIds" to arr("a", "b")),
            sent[0].payload,
        )
        assertEquals(obj("title" to "Empty", "description" to "", "privacyStatus" to "PRIVATE"), sent[1].payload)
        assertEquals(
            obj(
                "playlistId" to "PLnew",
                "actions" to arr(
                    obj("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to "Mix 2"),
                    obj("action" to "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription" to "New words"),
                    obj("action" to "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy" to "PUBLIC"),
                ),
            ),
            sent[2].payload,
        )
        assertEquals(
            obj(
                "playlistId" to "PLnew",
                "actions" to arr(obj("action" to "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy" to "PRIVATE")),
            ),
            sent[3].payload,
        )
        assertEquals(
            obj(
                "playlistId" to "PLnew",
                "actions" to arr(
                    obj("action" to "ACTION_ADD_VIDEO", "addedVideoId" to "c"),
                    obj("action" to "ACTION_ADD_VIDEO", "addedVideoId" to "d"),
                ),
            ),
            sent[4].payload,
        )
        assertEquals(
            obj(
                "playlistId" to "PLnew",
                "actions" to arr(
                    obj("action" to "ACTION_REMOVE_VIDEO", "setVideoId" to "SET_A", "removedVideoId" to "a"),
                    obj("action" to "ACTION_REMOVE_VIDEO", "setVideoId" to "SET_B", "removedVideoId" to "b"),
                ),
            ),
            sent[5].payload,
        )
        assertEquals(obj("playlistId" to "PLnew"), sent[6].payload)
    }

    @Test
    fun angleBracketsAreLeftOutOfPlaylistTexts() = test {
        val transport = FakeTransport(
            mapOf(
                "playlist/create" to Reply.Json(200, """{"playlistId": "PLnew"}"""),
                "browse/edit_playlist" to Reply.Json(200, """{"status": "STATUS_SUCCEEDED"}"""),
            ),
        )
        val api = stubbedClient(Sessions.signedIn, transport)
        api.createPlaylist("<b>Mix</b>", "a > b < c", PlaylistPrivacy.PRIVATE, emptyList())
        api.editPlaylist("PLnew", "<Mix>", "<>", null)
        val sent = transport.sent
        assertEquals(obj("title" to "bMix/b", "description" to "a  b  c", "privacyStatus" to "PRIVATE"), sent[0].payload)
        assertEquals(
            obj(
                "playlistId" to "PLnew",
                "actions" to arr(
                    obj("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to "Mix"),
                    obj("action" to "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription" to ""),
                ),
            ),
            sent[1].payload,
        )
    }

    @Test
    fun failedPlaylistEditThrows() = test {
        val transport = FakeTransport(mapOf("browse/edit_playlist" to Reply.Json(200, """{"status": "STATUS_FAILED"}""")))
        val api = stubbedClient(Sessions.signedIn, transport)
        assertFailsWith<MusicAPIError.Unreadable> { api.addToPlaylist("PLx", listOf("a")) }
    }

    @Test
    fun removingSongsWithoutSetVideoIdThrows() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedIn, transport)
        assertFailsWith<MusicAPIError.Unreadable> { api.removeFromPlaylist("PLx", listOf(Song("a", "A"))) }
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun libraryBodies() = test {
        val empty = """{"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": []}}}}]}}}"""
        val transport = FakeTransport(fallback = Reply.Json(200, empty))
        val api = stubbedClient(Sessions.signedIn, transport)
        for (section in LibrarySection.entries) api.library(section)
        api.history()
        runCatching { api.likedSongs() }
        assertEquals(
            listOf(
                obj("browseId" to "FEmusic_liked_playlists"),
                obj("browseId" to "FEmusic_liked_videos"),
                obj("browseId" to "FEmusic_liked_albums"),
                obj("browseId" to "FEmusic_library_corpus_track_artists"),
                obj("browseId" to "FEmusic_library_corpus_artists"),
                obj("browseId" to "FEmusic_history"),
                obj("browseId" to "VLLM"),
            ),
            transport.sent.map { it.payload },
        )
    }

    @Test
    fun account() = test {
        val menu = """
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
        """.trimIndent()
        val transport = FakeTransport(mapOf("account/account_menu" to Reply.Json(200, menu)))
        val account = assertNotNull(stubbedClient(Sessions.signedIn, transport).account())
        assertEquals(listOf("account/account_menu"), transport.sent.map { it.endpoint })
        assertEquals(obj(), transport.sent[0].payload)
        assertEquals("Listener Name", account.name)
        assertEquals("@listener", account.handle)
        assertEquals(listOf(48, 88), account.artwork.thumbnails.map { it.width })
        assertFalse(account.isPremium)

        val signedOutTransport = FakeTransport()
        assertNull(stubbedClient(Sessions.signedOut, signedOutTransport).account())
        assertTrue(signedOutTransport.sent.isEmpty())
    }

    // Guards and errors

    @Test
    fun signedInCallsThrowSignedOutBeforeAnyRequest() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        val song = Song("a", "A", setVideoId = "S")
        val calls = listOf(
            NamedCall("library") { api.library(LibrarySection.PLAYLISTS); Unit },
            NamedCall("likedSongs") { api.likedSongs(); Unit },
            NamedCall("history") { api.history(); Unit },
            NamedCall("rate") { api.rate("a", LikeStatus.LIKE) },
            NamedCall("ratePlaylist") { api.ratePlaylist("PLx", LikeStatus.LIKE) },
            NamedCall("subscribe") { api.subscribe("UCx", true) },
            NamedCall("unsubscribe") { api.subscribe("UCx", false) },
            NamedCall("feedback") { api.sendLibraryFeedback("t") },
            NamedCall("create") { api.createPlaylist("t", "", PlaylistPrivacy.PRIVATE, emptyList()); Unit },
            NamedCall("delete") { api.deletePlaylist("PLx") },
            NamedCall("edit") { api.editPlaylist("PLx", "t", null, null) },
            NamedCall("edit nothing") { api.editPlaylist("PLx", null, null, null) },
            NamedCall("add") { api.addToPlaylist("PLx", listOf("a")) },
            NamedCall("add nothing") { api.addToPlaylist("PLx", emptyList()) },
            NamedCall("remove") { api.removeFromPlaylist("PLx", listOf(song)) },
        )
        for ((name, call) in calls) {
            try {
                call()
                fail("$name did not throw")
            } catch (e: MusicAPIError) {
                assertEquals(MusicAPIError.SignedOut, e, "$name threw $e")
            }
        }
        assertTrue(transport.sent.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun refusedMapsToUnauthorized(status: Int) = test {
        val transport = FakeTransport(fallback = Reply.Json(status, """{"error": {"code": 401, "message": "denied"}}"""))
        val api = stubbedClient(Sessions.signedIn, transport)
        expectError(MusicAPIError.Unauthorized) { api.rate("a", LikeStatus.LIKE) }
        expectError(MusicAPIError.Unauthorized) { api.home(null) }
    }

    @Test
    fun serverErrorsRetryOnceThenFail() = test {
        val transport = FakeTransport(fallback = Reply.Json(503, "{}"))
        val api = stubbedClient(Sessions.signedOut, transport)
        assertFailsWith<MusicAPIError.Network> { api.explore() }
        assertEquals(2, transport.sent.size)
        try {
            api.explore()
            fail("no error")
        } catch (e: MusicAPIError.Network) {
            assertTrue(e.detail.contains("503"), e.detail)
        }
    }

    @Test
    fun clientErrorsAreNotRetried() = test {
        val transport = FakeTransport(fallback = Reply.Json(400, """{"error": {"message": "Request contains an invalid argument."}}"""))
        val api = stubbedClient(Sessions.signedOut, transport)
        try {
            api.explore()
            fail("no error")
        } catch (e: MusicAPIError.Network) {
            assertTrue(e.detail.contains("400"), e.detail)
            assertTrue(e.detail.contains("invalid argument"), e.detail)
        }
        assertEquals(1, transport.sent.size)
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    fun transportErrorsMapToNetwork(failure: IOException) = test {
        val transport = FakeTransport(fallback = Reply.Failure(failure))
        val api = stubbedClient(Sessions.signedOut, transport)
        try {
            api.explore()
            fail("no error")
        } catch (e: MusicAPIError) {
            assertIs<MusicAPIError.Network>(e)
        }
        // Dropped connections and timeouts are tried once more.
        assertEquals(if (InnerTubeClient.isTransient(failure)) 2 else 1, transport.sent.size)
    }

    @Test
    fun unreadableAnswer() = test {
        val transport = FakeTransport(fallback = Reply.Data(200, "<html>".toByteArray()))
        val api = stubbedClient(Sessions.signedOut, transport)
        expectError(MusicAPIError.Unreadable("browse: answer is not JSON")) { api.explore() }
        transport.reset(fallback = Reply.Json(200, "{}"))
        try {
            api.explore()
            fail("no error")
        } catch (e: MusicAPIError) {
            assertIs<MusicAPIError.Unreadable>(e)
        }
    }

    @Test
    fun cancellationIsNotWrapped() = test {
        val transport = FakeTransport(fallback = Reply.Hang)
        val api = stubbedClient(Sessions.signedOut, transport)
        val job = async(Dispatchers.Default) { api.search("slow", null) }
        delay(200)
        job.cancel()
        // The caller gets a cancellation, not a MusicAPIError…
        val error = try {
            job.await()
            fail("no error")
        } catch (e: CancellationException) {
            e
        }
        assertFalse(error is MusicAPIError)
        // …and the call underneath was cancelled too, so it does not go on holding a connection.
        assertTrue(transport.sawCancellation.await(5, TimeUnit.SECONDS), "the request was not cancelled")
    }

    @Test
    fun cancelledBeforeSendingSendsNothing() = test {
        val transport = FakeTransport()
        val api = stubbedClient(Sessions.signedOut, transport)
        var thrown: Throwable? = null
        val job = launch {
            // The job cancels itself first; the next suspension point must notice.
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            try {
                api.explore()
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.join()
        assertTrue(thrown is CancellationException, "threw $thrown")
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun sessionProviderErrors() = test {
        val broken = object : InnerTubeSessionProviding {
            override suspend fun currentSession(): InnerTubeSession = throw ConnectException("no connection")
        }
        val api = InnerTubeClient(broken, okhttp3.OkHttpClient.Builder().addInterceptor(FakeTransport()).build())
        try {
            api.explore()
            fail("no error")
        } catch (e: MusicAPIError) {
            assertIs<MusicAPIError.Network>(e)
        }
    }

    companion object {
        /** A no-connection failure, a timeout, an unknown host, and an answer that is no HTTP at all (not retried). */
        @JvmStatic
        fun transportFailures(): List<IOException> = listOf(
            ConnectException("Network is unreachable"),
            SocketTimeoutException("timeout"),
            UnknownHostException("music.youtube.com"),
            IOException("bad server response"),
        )
    }
}
