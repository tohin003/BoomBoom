package io.github.boomboom.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How a tapped song plays, so that taps behave the same on every screen. */
sealed class PlayContext {
    /** A lone song (search, Home): it plays with a radio of similar songs. */
    data object Single : PlayContext()

    /** A song inside a tracklist (an album, a playlist, "Top songs"): the list plays from that song. */
    data class InList(val songs: List<Song>, val source: QueueSource?) : PlayContext()
}

/**
 * What a tap, a Play button or a menu entry does with an item. Get one with [rememberItemActions].
 *
 * Work that loads first (an album's tracks for "Play next") runs in [scope], the app's own: the listener asked for it,
 * so it finishes even if the page that asked is closed.
 */
class ItemActions(
    val services: AppServices,
    val navigator: BoomNavigator,
    private val scope: CoroutineScope,
) {
    private val player: PlayerStore get() = services.player
    private val api get() = services.api

    /** A tap on a row or a card: songs play (from the list, or with their radio); everything else opens. */
    fun tap(item: MusicItem, context: PlayContext = PlayContext.Single) {
        when (item) {
            is MusicItem.OfSong -> {
                val song = item.song
                if (!song.isAvailable) return
                when (context) {
                    PlayContext.Single -> player.play(song)
                    is PlayContext.InList -> {
                        val songs = context.songs
                        val index = songs.indexOfFirst { it.videoId == song.videoId && it.setVideoId == song.setVideoId }
                            .takeIf { it >= 0 } ?: songs.indexOfFirst { it.videoId == song.videoId }.takeIf { it >= 0 }
                        if (index != null) player.play(songs, startAt = index, source = context.source) else player.play(song)
                    }
                }
            }
            else -> item.endpoint()?.let(navigator.open)
        }
    }

    /** The big Play button of a card or a top result: the song, the album, the playlist, or the artist's shuffle. */
    fun play(item: MusicItem, shuffled: Boolean = false) {
        when (item) {
            is MusicItem.OfSong -> player.play(item.song)
            is MusicItem.OfAlbum -> scope.launch {
                val page = quietly { api.album(item.album.id) } ?: return@launch
                playList(page.tracks, shuffled, source(page.album))
            }
            is MusicItem.OfPlaylist -> scope.launch {
                val page = quietly { api.playlist(item.playlist.id) } ?: return@launch
                playList(page.tracks, shuffled, source(page.playlist))
            }
            is MusicItem.OfArtist -> scope.launch {
                val artist = item.artist
                val page = quietly { api.artist(artist.id) } ?: return@launch
                val source = QueueSource(
                    QueueSource.Kind.ARTIST, "Artist", artist.name,
                    BrowseEndpoint(artist.id, pageType = BrowseEndpoint.PageType.ARTIST),
                )
                val endpoint = if (shuffled) page.shuffle ?: page.radio else page.radio ?: page.shuffle
                if (endpoint != null) {
                    player.play(endpoint, source)
                } else {
                    val top = page.shelves.firstOrNull { it.layout == Shelf.Layout.LIST }
                    if (top != null) playList(top.items.mapNotNull { it.asSong }, shuffled, source)
                }
            }
        }
    }

    /** Plays a tracklist from the start, or shuffled. */
    fun playList(songs: List<Song>, shuffled: Boolean, source: QueueSource?) {
        val playable = songs.filter { it.isAvailable }
        if (playable.isEmpty()) return
        player.play(if (shuffled) playable.shuffled() else playable, startAt = 0, source = source)
    }

    /** "Start radio": songs like this one, or like this album, playlist or artist. */
    fun startRadio(item: MusicItem) {
        val source = QueueSource(QueueSource.Kind.RADIO, "Radio", item.title)
        when (item) {
            is MusicItem.OfSong -> player.startRadio(item.song)
            is MusicItem.OfAlbum -> {
                val playlistId = item.album.playlistId
                if (playlistId != null) {
                    player.play(WatchEndpoint(playlistId = "RDAMPL$playlistId"), source)
                } else {
                    scope.launch {
                        val first = quietly { api.album(item.album.id) }?.tracks?.firstOrNull() ?: return@launch
                        player.startRadio(first)
                    }
                }
            }
            is MusicItem.OfPlaylist -> player.play(WatchEndpoint(playlistId = "RDAMPL${item.playlist.id}"), source)
            is MusicItem.OfArtist -> scope.launch {
                val page = quietly { api.artist(item.artist.id) } ?: return@launch
                (page.radio ?: page.shuffle)?.let { player.play(it, source) }
            }
        }
    }

    /** "Play next" and "Add to queue": a song at once, an album's or playlist's tracks once loaded. */
    fun enqueue(item: MusicItem, next: Boolean) {
        val song = item.asSong
        if (song != null) {
            // A song needs nothing loaded: it goes in now, so the queue is right the moment the menu closes.
            if (next) player.playNext(listOf(song)) else player.addToQueue(listOf(song))
            return
        }
        scope.launch {
            val songs = tracks(item)
            if (songs.isEmpty()) return@launch
            if (next) player.playNext(songs) else player.addToQueue(songs)
        }
    }

    /** The songs an item stands for: itself, or the tracks of its album or playlist, or an artist's top songs. */
    suspend fun tracks(item: MusicItem): List<Song> = when (item) {
        is MusicItem.OfSong -> listOf(item.song)
        is MusicItem.OfAlbum -> quietly { api.album(item.album.id).tracks } ?: emptyList()
        is MusicItem.OfPlaylist -> quietly { api.playlist(item.playlist.id).tracks } ?: emptyList()
        is MusicItem.OfArtist -> quietly { api.artist(item.artist.id) }
            ?.shelves?.firstOrNull { it.layout == Shelf.Layout.LIST }?.items?.mapNotNull { it.asSong } ?: emptyList()
    }

    /** Runs the work in the app's scope (for menu entries that load something first). */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    companion object {
        fun source(album: AlbumItem) = QueueSource(
            QueueSource.Kind.ALBUM, "Album", album.title,
            BrowseEndpoint(album.id, pageType = BrowseEndpoint.PageType.ALBUM),
        )

        fun source(playlist: PlaylistItem) = QueueSource(
            QueueSource.Kind.PLAYLIST, "Playlist", playlist.title,
            BrowseEndpoint(playlist.id, pageType = BrowseEndpoint.PageType.PLAYLIST),
        )
    }
}

/** A call whose failure the caller meets with "nothing came back"; cancellation still cancels. */
suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null
}

@Composable
fun rememberItemActions(): ItemActions {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val scope = LocalAppScope.current
    return remember(services, navigator, scope) { ItemActions(services, navigator, scope) }
}
