package io.github.boomboom.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.Song
import kotlinx.coroutines.CoroutineScope

/**
 * What a screen asks the app's frame to do when the answer lies outside that screen. The frame provides one through
 * [LocalBoomNavigator]; screens call its actions from buttons.
 */
data class BoomNavigator(
    /** Shows an album, an artist, a playlist or another page of shelves in the current tab. */
    val open: (BrowseEndpoint) -> Unit = {},
    /** Shows the equaliser and amplifier. */
    val openEqualizer: () -> Unit = {},
    /** Shows the "Save to playlist" sheet for these songs. */
    val addToPlaylist: (List<Song>) -> Unit = {},
    /** Shows the sign-in for the active account. */
    val signIn: () -> Unit = {},
    /** Shows the sign-in for another account, kept apart from the ones already signed in. */
    val addAccount: () -> Unit = {},
)

/** The app's stores and data source. Every screen reads it; the frame provides it. */
val LocalAppServices = staticCompositionLocalOf<AppServices> { error("BoomApp provides AppServices") }

val LocalBoomNavigator = staticCompositionLocalOf { BoomNavigator() }

/**
 * A scope that lives as long as the frame: for things the listener asked for that must finish even if the page that
 * asked is closed (a rating, "Play next" on an album, a save to the library).
 */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope> { error("BoomApp provides the app scope") }

/** Where the screens keep their own small saved values (recent searches). The app passes its real storage. */
val LocalSettingsStore = staticCompositionLocalOf<KeyValueStore> { InMemoryKeyValueStore() }

/** The page behind a card or a row. Songs have none: they play. */
fun MusicItem.endpoint(): BrowseEndpoint? = when (this) {
    is MusicItem.OfSong -> null
    is MusicItem.OfAlbum -> BrowseEndpoint(album.id, pageType = BrowseEndpoint.PageType.ALBUM)
    is MusicItem.OfArtist -> BrowseEndpoint(artist.id, pageType = BrowseEndpoint.PageType.ARTIST)
    is MusicItem.OfPlaylist -> BrowseEndpoint(playlist.id, pageType = BrowseEndpoint.PageType.PLAYLIST)
}

/** True for artists, whose pictures are round. */
val MusicItem.isArtist: Boolean get() = this is MusicItem.OfArtist

/** True for music videos, whose pictures are wide. */
val MusicItem.isVideo: Boolean get() = (this as? MusicItem.OfSong)?.song?.kind == Song.Kind.VIDEO

val MusicItem.isExplicit: Boolean
    get() = when (this) {
        is MusicItem.OfSong -> song.isExplicit
        is MusicItem.OfAlbum -> album.isExplicit
        is MusicItem.OfArtist, is MusicItem.OfPlaylist -> false
    }

/** "song", "album", "artist" or "playlist": the end of a row's or card's test tag ("row.song"). */
val MusicItem.kindTag: String
    get() = when (this) {
        is MusicItem.OfSong -> "song"
        is MusicItem.OfAlbum -> "album"
        is MusicItem.OfArtist -> "artist"
        is MusicItem.OfPlaylist -> "playlist"
    }

/** The page to share: a song's watch page, or the album's, playlist's or artist's. */
val MusicItem.shareUrl: String
    get() = when (this) {
        is MusicItem.OfSong -> "https://music.youtube.com/watch?v=${song.videoId}"
        is MusicItem.OfAlbum -> "https://music.youtube.com/browse/${album.id}"
        is MusicItem.OfPlaylist -> "https://music.youtube.com/playlist?list=${playlist.id}"
        is MusicItem.OfArtist -> "https://music.youtube.com/channel/${artist.id}"
    }
