package io.github.boomboom.logic

import io.github.boomboom.models.Account
import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.ArtistItem
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.Chip
import io.github.boomboom.models.ChipGroup
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.PlaylistItem
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent

/** A data source that answers every call with nothing; tests override the calls they care about. */
open class StubMusicAPI : MusicAPI {
    override suspend fun searchSuggestions(input: String): List<SearchSuggestion> = emptyList()
    override suspend fun search(query: String, filter: SearchFilter?) = SearchResults(query = query)
    override suspend fun searchContinuation(token: String) = SongsOrItemsContinuation(emptyList(), null)
    override suspend fun home(chip: Chip?) = BrowsePage()
    override suspend fun explore() = BrowsePage()
    override suspend fun charts(country: String?) = BrowsePage()
    override suspend fun moodsAndGenres(): List<ChipGroup> = emptyList()
    override suspend fun browse(endpoint: BrowseEndpoint) = BrowsePage()
    override suspend fun browseContinuation(token: String) = BrowsePage()
    override suspend fun album(id: String) = AlbumPage(album = AlbumItem(id = id, title = id))
    override suspend fun artist(id: String) = ArtistPage(artist = ArtistItem(id = id, name = id))
    override suspend fun playlist(id: String) = PlaylistPage(playlist = PlaylistItem(id = id, title = id))
    override suspend fun playlistContinuation(token: String) = SongsContinuation(emptyList(), null)
    override suspend fun watchQueue(endpoint: WatchEndpoint) = WatchQueue()
    override suspend fun watchQueueContinuation(token: String) = SongsContinuation(emptyList(), null)
    override suspend fun lyrics(browseId: String) = Lyrics(lines = emptyList())
    override suspend fun related(browseId: String): List<Shelf> = emptyList()
    override suspend fun account(): Account? = null
    override suspend fun library(section: LibrarySection) = BrowsePage()
    override suspend fun likedSongs() = PlaylistPage(playlist = PlaylistItem(id = "LM", title = "Liked"))
    override suspend fun history() = BrowsePage()
    override suspend fun rate(videoId: String, status: LikeStatus) {}
    override suspend fun ratePlaylist(id: String, status: LikeStatus) {}
    override suspend fun subscribe(channelId: String, subscribed: Boolean) {}
    override suspend fun sendLibraryFeedback(token: String) {}
    override suspend fun createPlaylist(
        title: String, description: String, privacy: PlaylistPrivacy, videoIds: List<String>,
    ) = "PL"
    override suspend fun deletePlaylist(id: String) {}
    override suspend fun editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) {}
    override suspend fun addToPlaylist(id: String, videoIds: List<String>) {}
    override suspend fun removeFromPlaylist(id: String, songs: List<Song>) {}
}

/** The test scheduler's virtual time as the stores' clock: what `delay` waits for is what "now" says. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.virtualClock(): Clock = Clock { testScheduler.currentTime }

/**
 * Waits until [condition] holds, for at most [seconds] of virtual time, looking every 10 ms: no wall-clock waiting, so
 * the tests do not depend on how busy the machine is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.eventually(seconds: Double = 3.0, condition: () -> Boolean): Boolean {
    val deadline = currentTime + (seconds * 1000).toLong()
    runCurrent()
    while (currentTime < deadline) {
        if (condition()) return true
        advanceTimeBy(10)
        runCurrent()
    }
    return condition()
}

/** Lets virtual time run on by [millis]. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.wait(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}

fun songs(count: Int): List<Song> = (0 until count).map { Song(videoId = "v$it", title = "Track $it", duration = 100.0) }
