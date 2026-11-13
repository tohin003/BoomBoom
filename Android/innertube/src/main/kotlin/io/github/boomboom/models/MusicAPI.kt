package io.github.boomboom.models

import kotlinx.serialization.Serializable

/**
 * Everything the screens and the player ask YouTube Music for. `io.github.boomboom.innertube.InnerTubeClient` is
 * the real one; screens are built against this interface so they can run on canned data.
 *
 * Every call may throw [MusicAPIError]. Calls marked "signed in" throw [MusicAPIError.SignedOut] without an account.
 * Calls honour coroutine cancellation (and then throw `CancellationException`, never a [MusicAPIError]).
 */
interface MusicAPI {
    // Search

    /** Suggestions while typing. Fast and cancellable: callers debounce and cancel the job of the previous keystroke. */
    suspend fun searchSuggestions(input: String): List<SearchSuggestion>
    suspend fun search(query: String, filter: SearchFilter?): SearchResults

    /** More rows of a filtered search. */
    suspend fun searchContinuation(token: String): SongsOrItemsContinuation

    // Browse

    /** Home. [chip] is one of the pills of an earlier Home page, or null for the default feed. */
    suspend fun home(chip: Chip?): BrowsePage

    /** Explore: new releases, charts, moods and genres. */
    suspend fun explore(): BrowsePage

    /** Charts for a country ("US", "IN"; "ZZ" is global). */
    suspend fun charts(country: String?): BrowsePage

    /** The mood and genre tiles ([Chip] with colours), grouped in shelves titled "Moods & moments", "Genres". */
    suspend fun moodsAndGenres(): List<ChipGroup>

    /** Any other page of shelves: a "More" button, a mood tile, "New releases". */
    suspend fun browse(endpoint: BrowseEndpoint): BrowsePage

    /** More shelves of a page that scrolls on (Home). */
    suspend fun browseContinuation(token: String): BrowsePage

    suspend fun album(id: String): AlbumPage
    suspend fun artist(id: String): ArtistPage
    suspend fun playlist(id: String): PlaylistPage
    suspend fun playlistContinuation(token: String): SongsContinuation

    // Playing

    /** The "Up next" queue for a song. With only `videoId` set, YouTube Music returns a radio based on the song. */
    suspend fun watchQueue(endpoint: WatchEndpoint): WatchQueue
    suspend fun watchQueueContinuation(token: String): SongsContinuation
    suspend fun lyrics(browseId: String): Lyrics

    /** The player's "Related" tab. */
    suspend fun related(browseId: String): List<Shelf>

    // The listener's library (signed in)

    suspend fun account(): Account?
    suspend fun library(section: LibrarySection): BrowsePage
    suspend fun likedSongs(): PlaylistPage
    suspend fun history(): BrowsePage
    suspend fun rate(videoId: String, status: LikeStatus)
    suspend fun ratePlaylist(id: String, status: LikeStatus)
    suspend fun subscribe(channelId: String, subscribed: Boolean)

    /** Sends one of an item's [LibraryTokens]. */
    suspend fun sendLibraryFeedback(token: String)

    /** Returns the new playlist's id. */
    suspend fun createPlaylist(
        title: String,
        description: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): String

    suspend fun deletePlaylist(id: String)
    suspend fun editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?)
    suspend fun addToPlaylist(id: String, videoIds: List<String>)

    /** [songs] need their `setVideoId`. */
    suspend fun removeFromPlaylist(id: String, songs: List<Song>)
}

/** A titled row of tiles (moods, genres). */
@Serializable
data class ChipGroup(val title: String, val chips: List<Chip>) {
    val id: String get() = title
}

/** More rows of a list that mixes kinds (filtered search, a library tab). */
@Serializable
data class SongsOrItemsContinuation(val items: List<MusicItem>, val continuation: String?)

/** What can go wrong with a call. Equal errors compare equal, so tests can say `assertEquals(SignedOut, error)`. */
sealed class MusicAPIError(message: String) : Exception(message) {
    /** No connection, a timeout, or a non-2xx answer. The text is for the log, not for the listener. */
    data class Network(val detail: String) : MusicAPIError("network: $detail")

    /** YouTube Music answered, but not in a shape this client reads. */
    data class Unreadable(val detail: String) : MusicAPIError("unreadable: $detail")

    /** The call needs an account and nobody is signed in. */
    data object SignedOut : MusicAPIError("signed out")

    /** YouTube Music refused (401/403): the sign-in has run out. */
    data object Unauthorized : MusicAPIError("unauthorized")
}
