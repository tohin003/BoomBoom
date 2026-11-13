package io.github.boomboom.innertube

import io.github.boomboom.models.Account
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.Chip
import io.github.boomboom.models.ChipGroup
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.models.PlaylistPrivacy
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchResults
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.SongsContinuation
import io.github.boomboom.models.SongsOrItemsContinuation
import io.github.boomboom.models.WatchEndpoint
import io.github.boomboom.models.WatchQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

/**
 * The YouTube Music data client: calls the web app's own API (`WEB_REMIX`) and reads the answers into the models.
 *
 * Each call asks [sessionProvider] for the current session (client version, visitor id, cookies), so the listener's
 * sign-in applies as soon as the hidden browser has it. Every call honours coroutine cancellation. The answers are
 * read on `Dispatchers.Default`, so a call from the main thread never parses on it.
 */
class InnerTubeClient(
    private val sessionProvider: InnerTubeSessionProviding,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : MusicAPI {
    /** Artist pages subscribe through a channel id that can differ from the page's browse id. */
    private val channels = ChannelDirectory()

    // Search

    override suspend fun searchSuggestions(input: String): List<SearchSuggestion> {
        if (input.isBlank()) return emptyList()
        val response = send("music/get_search_suggestions", obj("input" to input), retries = 0, timeoutSeconds = 8)
        return parse { Parse.searchSuggestions(response) }
    }

    override suspend fun search(query: String, filter: SearchFilter?): SearchResults {
        val body = body {
            this["query"] = query
            if (filter != null) this["params"] = Parse.searchParams(filter)
        }
        val response = send("search", body)
        return parse {
            if (!Parse.searchSectionList(response).exists && !response["contents"].exists) {
                throw MusicAPIError.Unreadable("search: no section list")
            }
            Parse.searchResults(response, query, filter)
        }
    }

    override suspend fun searchContinuation(token: String): SongsOrItemsContinuation {
        val response = send("search", obj("continuation" to token))
        return parse {
            Parse.searchContinuation(response) ?: throw MusicAPIError.Unreadable("search continuation: no items")
        }
    }

    // Browse

    override suspend fun home(chip: Chip?): BrowsePage {
        val endpoint = chip?.endpoint ?: BrowseEndpoint(browseId = "FEmusic_home")
        return browsePage(endpoint, what = "home")
    }

    override suspend fun explore(): BrowsePage = browsePage(BrowseEndpoint(browseId = "FEmusic_explore"), what = "explore")

    override suspend fun charts(country: String?): BrowsePage {
        val body = body {
            this["browseId"] = "FEmusic_charts"
            if (!country.isNullOrEmpty()) this["formData"] = obj("selectedValues" to arr(country))
        }
        val response = send("browse", body)
        return parse {
            val page = Parse.browsePage(response)
            if (page.shelves.isEmpty()) throw MusicAPIError.Unreadable("charts: no shelves")
            page
        }
    }

    override suspend fun moodsAndGenres(): List<ChipGroup> {
        val response = send("browse", obj("browseId" to "FEmusic_moods_and_genres"))
        return parse {
            val groups = Parse.chipGroups(response)
            if (groups.isEmpty()) throw MusicAPIError.Unreadable("moods and genres: no tiles")
            groups
        }
    }

    /**
     * Endpoints carry YouTube Music's own browse ids (`VL…`, `MPREb_…`, `UC…`). A playlist or artist endpoint built
     * from an item's id (a bare `PL…`, a library artist's `MPLAUC…`) is put into that form first.
     */
    override suspend fun browse(endpoint: BrowseEndpoint): BrowsePage {
        val fixed = when (endpoint.pageType) {
            BrowseEndpoint.PageType.PLAYLIST -> endpoint.copy(browseId = Parse.playlistBrowseId(endpoint.browseId))
            BrowseEndpoint.PageType.ARTIST -> endpoint.copy(browseId = Parse.artistBrowseId(endpoint.browseId))
            else -> endpoint
        }
        return browsePage(fixed, what = fixed.browseId)
    }

    override suspend fun browseContinuation(token: String): BrowsePage {
        val response = send("browse", obj("continuation" to token))
        return parse {
            Parse.browseContinuation(response) ?: throw MusicAPIError.Unreadable("browse continuation: no shelves")
        }
    }

    override suspend fun album(id: String): AlbumPage {
        val response = send("browse", obj("browseId" to id))
        return parse { Parse.albumPage(response, id) ?: throw MusicAPIError.Unreadable("album $id: no header") }
    }

    /** [id] is the artist's channel (`UC…`); a library artist's `MPLAUC…` is accepted too. */
    override suspend fun artist(id: String): ArtistPage {
        val browseId = Parse.artistBrowseId(id)
        val response = send("browse", obj("browseId" to browseId))
        val parsed = parse { Parse.artistPage(response, browseId) ?: throw MusicAPIError.Unreadable("artist $id: no header") }
        parsed.subscribeChannelId?.let { channels.remember(it, browseId) }
        return parsed.page
    }

    /** [id] is a playlist's browse id (`VLPL…`, as endpoints carry it) or its bare id (`PL…`, as items carry it). */
    override suspend fun playlist(id: String): PlaylistPage {
        val browseId = Parse.playlistBrowseId(id)
        val playlistId = Parse.playlistId(browseId)
        var response = send("browse", obj("browseId" to browseId))
        var parsed = parse { Parse.playlistPage(response, playlistId) }
        if (parsed == null && playlistId.startsWith("PL")) {
            // A podcast answers its `VL…` id with episodes but no header; its show page (`MPSP…`) has both.
            response = send("browse", obj("browseId" to "MPSP$playlistId"), retries = 0)
            parsed = parse { Parse.playlistPage(response, playlistId) }
        }
        var page = parsed ?: throw MusicAPIError.Unreadable("playlist $id: no header")
        // Related playlists or suggestions come as a continuation of the page's sections. Best effort.
        if (page.shelves.isEmpty()) {
            val token = parse { Parse.sectionContinuation(response) }
            if (token != null) {
                val extra = try {
                    val more = send("browse", obj("continuation" to token), retries = 0)
                    parse { Parse.browseContinuation(more) }
                } catch (e: MusicAPIError) {
                    null
                }
                if (extra != null) page = page.copy(shelves = extra.shelves)
                currentCoroutineContext().ensureActive()
            }
        }
        return page
    }

    override suspend fun playlistContinuation(token: String): SongsContinuation {
        val response = send("browse", obj("continuation" to token))
        return parse { Parse.songsContinuation(response) ?: throw MusicAPIError.Unreadable("playlist continuation: no rows") }
    }

    // Playing

    override suspend fun watchQueue(endpoint: WatchEndpoint): WatchQueue {
        if (endpoint.videoId == null && endpoint.playlistId == null) {
            throw MusicAPIError.Unreadable("watch queue: no video or playlist")
        }
        val response = send("next", watchBody(endpoint))
        return parse { Parse.watchQueue(response, endpoint) ?: throw MusicAPIError.Unreadable("watch queue: no queue") }
    }

    override suspend fun watchQueueContinuation(token: String): SongsContinuation {
        val body = body {
            this["continuation"] = token
            for ((key, value) in WATCH_FLAGS) this[key] = value
        }
        val response = send("next", body)
        return parse { Parse.songsContinuation(response) ?: throw MusicAPIError.Unreadable("watch queue continuation: no rows") }
    }

    override suspend fun lyrics(browseId: String): Lyrics {
        val response = send("browse", obj("browseId" to browseId))
        return parse { Parse.lyrics(response) ?: throw MusicAPIError.Unreadable("lyrics: no text") }
    }

    override suspend fun related(browseId: String): List<Shelf> {
        val response = send("browse", obj("browseId" to browseId))
        return parse {
            val shelves = Parse.shelves(Parse.sectionList(response)["contents"].array)
            if (shelves.isEmpty()) throw MusicAPIError.Unreadable("related: no shelves")
            shelves
        }
    }

    // The listener's library

    /** Null when nobody is signed in (no request is sent). */
    override suspend fun account(): Account? {
        val session = currentSession()
        if (!session.isSignedIn) return null
        val response = send("account/account_menu", obj(), signedIn = true)
        return parse { Parse.account(response) ?: throw MusicAPIError.Unreadable("account: no header") }
    }

    override suspend fun library(section: LibrarySection): BrowsePage {
        val response = send("browse", obj("browseId" to libraryBrowseId(section)), signedIn = true)
        return parse {
            var page = Parse.browsePage(response)
            if (page.title == null) page = page.copy(title = section.title)
            // An empty library is a valid answer: it still has its section list.
            if (!Parse.sectionList(response).exists && page.shelves.isEmpty()) {
                throw MusicAPIError.Unreadable("library ${section.rawValue}: no section list")
            }
            page
        }
    }

    override suspend fun likedSongs(): PlaylistPage {
        requireSignIn()
        return playlist("LM")
    }

    override suspend fun history(): BrowsePage {
        val response = send("browse", obj("browseId" to "FEmusic_history"), signedIn = true)
        return parse {
            if (!Parse.sectionList(response).exists) throw MusicAPIError.Unreadable("history: no list")
            Parse.browsePage(response)
        }
    }

    override suspend fun rate(videoId: String, status: LikeStatus) {
        send(likeEndpoint(status), obj("target" to obj("videoId" to videoId)), signedIn = true)
    }

    override suspend fun ratePlaylist(id: String, status: LikeStatus) {
        val playlistId = Parse.playlistId(id)
        send(likeEndpoint(status), obj("target" to obj("playlistId" to playlistId)), signedIn = true)
    }

    override suspend fun subscribe(channelId: String, subscribed: Boolean) {
        requireSignIn()
        // The button acts on the channel the artist page names, which can differ from the page's own id: read the
        // page once if it has not been opened yet.
        val artistId = Parse.artistBrowseId(channelId)
        var target = channels.subscribeId(artistId)
        if (target == null && artistId.startsWith("UC")) {
            try {
                artist(artistId)
                target = channels.subscribeId(artistId)
            } catch (e: MusicAPIError) {
                // An unreadable or unreachable page: subscribe with the id given. A refused sign-in is not that.
                if (e == MusicAPIError.Unauthorized) throw e
            }
        }
        val channel = target ?: artistId
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        send(endpoint, obj("channelIds" to arr(channel)), signedIn = true)
    }

    override suspend fun sendLibraryFeedback(token: String) {
        val response = send("feedback", obj("feedbackTokens" to arr(token)), signedIn = true)
        if (response["feedbackResponses", 0, "isProcessed"].bool == false) {
            throw MusicAPIError.Unreadable("feedback was not processed")
        }
    }

    override suspend fun createPlaylist(
        title: String,
        description: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): String {
        val body = body {
            this["title"] = playlistText(title)
            this["description"] = playlistText(description)
            this["privacyStatus"] = privacy.rawValue
            if (videoIds.isNotEmpty()) this["videoIds"] = videoIds
        }
        val response = send("playlist/create", body, signedIn = true)
        return response["playlistId"].text ?: throw MusicAPIError.Unreadable("create playlist: no id")
    }

    override suspend fun deletePlaylist(id: String) {
        send("playlist/delete", obj("playlistId" to Parse.playlistId(id)), signedIn = true)
    }

    override suspend fun editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) {
        requireSignIn()
        val actions = mutableListOf<JsonElement>()
        title?.let { playlistText(it) }?.takeIf { it.isNotEmpty() }?.let {
            actions += obj("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to it)
        }
        description?.let { playlistText(it) }?.let {
            actions += obj("action" to "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription" to it)
        }
        if (privacy != null) {
            actions += obj("action" to "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy" to privacy.rawValue)
        }
        if (actions.isEmpty()) return
        sendPlaylistEdit(id, actions)
    }

    override suspend fun addToPlaylist(id: String, videoIds: List<String>) {
        requireSignIn()
        val actions = videoIds.map { obj("action" to "ACTION_ADD_VIDEO", "addedVideoId" to it) }
        if (actions.isEmpty()) return
        sendPlaylistEdit(id, actions)
    }

    override suspend fun removeFromPlaylist(id: String, songs: List<Song>) {
        requireSignIn()
        val actions = songs.mapNotNull { song ->
            val setVideoId = song.setVideoId ?: return@mapNotNull null
            obj("action" to "ACTION_REMOVE_VIDEO", "setVideoId" to setVideoId, "removedVideoId" to song.videoId)
        }
        if (actions.isEmpty()) {
            if (songs.isEmpty()) return
            throw MusicAPIError.Unreadable("remove from playlist: the songs have no setVideoId")
        }
        sendPlaylistEdit(id, actions)
    }

    private suspend fun sendPlaylistEdit(id: String, actions: List<JsonElement>) {
        val body = obj("playlistId" to Parse.playlistId(id), "actions" to actions)
        val response = send("browse/edit_playlist", body, signedIn = true)
        val status = response["status"].string
        if (status != null && !status.contains("SUCCEEDED")) {
            throw MusicAPIError.Unreadable("edit playlist: $status")
        }
    }

    // Pages

    private suspend fun browsePage(endpoint: BrowseEndpoint, what: String): BrowsePage {
        val body = body {
            this["browseId"] = endpoint.browseId
            endpoint.params?.let { this["params"] = it }
        }
        val response = send("browse", body)
        return parse {
            val page = Parse.browsePage(response)
            if (page.shelves.isEmpty() && page.chips.isEmpty()) throw MusicAPIError.Unreadable("$what: no shelves")
            page
        }
    }

    // Transport

    private suspend fun currentSession(): InnerTubeSession {
        currentCoroutineContext().ensureActive()
        try {
            return sessionProvider.currentSession()
        } catch (e: MusicAPIError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MusicAPIError.Network("session: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun requireSignIn() {
        if (!currentSession().isSignedIn) throw MusicAPIError.SignedOut
    }

    /** Reads an answer into models off the caller's thread. Errors thrown inside reach the caller unchanged. */
    private suspend fun <T> parse(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    /**
     * Sends one call and returns the answer's JSON.
     *
     * Retries [retries] times on a dropped connection, a timeout or a 5xx answer, never after cancellation.
     */
    internal suspend fun send(
        endpoint: String,
        body: JsonElement,
        signedIn: Boolean = false,
        retries: Int = 1,
        timeoutSeconds: Long = 20,
    ): JsonElement {
        val session = currentSession()
        if (signedIn && !session.isSignedIn) throw MusicAPIError.SignedOut
        val request = try {
            InnerTubeRequest.make(endpoint, body, session)
        } catch (e: IllegalArgumentException) {
            // A header value OkHttp refuses (a stray line break or control character in a cookie).
            throw MusicAPIError.Unreadable("$endpoint: bad request header")
        }
        val client = httpClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val answer: Pair<Int, ByteArray> = try {
                client.newCall(request).await().use { response ->
                    response.code to withContext(Dispatchers.IO) { response.body?.bytes() ?: ByteArray(0) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt < retries && isTransient(e)) {
                    attempt += 1
                    delay(300L * attempt)
                    continue
                }
                throw MusicAPIError.Network("$endpoint: ${e::class.simpleName}: ${e.message}")
            } catch (e: Exception) {
                throw MusicAPIError.Network("$endpoint: ${e::class.simpleName}: ${e.message}")
            }
            currentCoroutineContext().ensureActive()
            val (status, data) = answer
            if (status in 200..299) {
                return try {
                    withContext(Dispatchers.Default) { parseJson(data) }
                } catch (e: IllegalArgumentException) {
                    throw MusicAPIError.Unreadable("$endpoint: answer is not JSON")
                }
            }
            if (status == 401 || status == 403) throw MusicAPIError.Unauthorized
            if (status in 500..599 && attempt < retries) {
                attempt += 1
                delay(300L * attempt)
                continue
            }
            val message = try {
                parseJson(data)["error", "message"].text?.let { " ($it)" } ?: ""
            } catch (e: IllegalArgumentException) {
                ""
            }
            throw MusicAPIError.Network("$endpoint: HTTP $status$message")
        }
    }

    companion object {
        private val WATCH_FLAGS: List<Pair<String, Any>> = listOf(
            "enablePersistentPlaylistPanel" to true,
            "isAudioOnly" to true,
            "tunerSettingValue" to "AUTOMIX_SETTING_NORMAL",
        )

        /**
         * The `next` body for an endpoint. With only a video, YouTube Music's radio for it (ytmusicapi's
         * `get_watch_playlist(videoId, radio=True)`).
         */
        internal fun watchBody(endpoint: WatchEndpoint): JsonElement = body {
            for ((key, value) in WATCH_FLAGS) this[key] = value
            endpoint.videoId?.let { this["videoId"] = it }
            if (endpoint.playlistId != null) {
                this["playlistId"] = Parse.playlistId(endpoint.playlistId)
                endpoint.params?.let { this["params"] = it }
                if (endpoint.params == null && endpoint.videoId != null) {
                    this["watchEndpointMusicSupportedConfigs"] = obj(
                        "watchEndpointMusicConfig" to obj(
                            "hasPersistentPlaylistPanel" to true,
                            "musicVideoType" to "MUSIC_VIDEO_TYPE_ATV",
                        ),
                    )
                }
            } else if (endpoint.videoId != null) {
                this["playlistId"] = "RDAMVM" + endpoint.videoId
                this["params"] = endpoint.params ?: "wAEB"
            }
            endpoint.index?.let { this["index"] = it }
        }

        private val sharedHttpClient: OkHttpClient by lazy {
            // The session's cookies go in the request header; OkHttp must never add any of its own.
            OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).build()
        }

        /** One client for the whole app, so connections are reused. */
        fun defaultHttpClient(): OkHttpClient = sharedHttpClient

        internal fun libraryBrowseId(section: LibrarySection): String = when (section) {
            LibrarySection.PLAYLISTS -> "FEmusic_liked_playlists"
            LibrarySection.SONGS -> "FEmusic_liked_videos"
            LibrarySection.ALBUMS -> "FEmusic_liked_albums"
            LibrarySection.ARTISTS -> "FEmusic_library_corpus_track_artists"
            LibrarySection.SUBSCRIPTIONS -> "FEmusic_library_corpus_artists"
        }

        internal fun likeEndpoint(status: LikeStatus): String = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }

        /** YouTube Music refuses playlist names and descriptions containing angle brackets; they are left out. */
        internal fun playlistText(text: String): String = text.replace("<", "").replace(">", "")

        /** A dropped connection, a timeout or an unreachable host: worth one more try. */
        internal fun isTransient(error: IOException): Boolean = when (error) {
            is InterruptedIOException, is EOFException -> true
            // Not a network failure: a certificate or handshake problem will not mend in 300 ms.
            is SSLException -> false
            // Unreachable host, refused or reset connection, broken pipe.
            is SocketException -> true
            // OkHttp's wording when a server closes a reused connection mid-answer.
            else -> error.message?.contains("unexpected end of stream") == true
        }
    }
}

/** Artist browse id → the channel id its "Subscribe" button acts on, learnt from artist pages. */
internal class ChannelDirectory {
    private val subscribeIds = ConcurrentHashMap<String, String>()

    fun remember(channelId: String, browseId: String) {
        subscribeIds[browseId] = channelId
    }

    fun subscribeId(browseId: String): String? = subscribeIds[browseId]
}

/** Runs the call and resumes with its answer; cancelling the coroutine cancels the call. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // A coroutine cancelled while the answer arrived must not leave its connection open.
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}
