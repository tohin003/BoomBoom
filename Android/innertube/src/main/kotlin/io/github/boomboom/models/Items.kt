package io.github.boomboom.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The things a listener sees in YouTube Music: songs, albums, artists, playlists. Plain immutable values, stable as
// the contract between the data client (innertube), the player and the screens.

/** A named artist, with a channel to open when YouTube Music links one. */
@Serializable
data class ArtistRef(
    val name: String,
    /** The artist's channel (`UC…`), or null when the name is not a link. */
    val id: String? = null,
)

/** A named album, with a page to open when YouTube Music links one. */
@Serializable
data class AlbumRef(
    val name: String,
    /** The album's browse id (`MPREb_…`), or null when the name is not a link. */
    val id: String? = null,
)

@Serializable
enum class LikeStatus(val rawValue: String) {
    @SerialName("LIKE") LIKE("LIKE"),
    @SerialName("DISLIKE") DISLIKE("DISLIKE"),
    @SerialName("INDIFFERENT") INDIFFERENT("INDIFFERENT");

    companion object {
        fun fromRawValue(raw: String): LikeStatus? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The tokens YouTube Music gives for "Save to library" and "Remove from library". */
@Serializable
data class LibraryTokens(
    val add: String?,
    val remove: String?,
    /** True when the item is in the library now (so [remove] is the token to send). */
    val isInLibrary: Boolean,
)

/** Anything that plays: a song (an album's audio), a music video, or a podcast episode. */
@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    /** Seconds, when the listing says. */
    val duration: Double? = null,
    val artwork: Artwork = Artwork.EMPTY,
    val isExplicit: Boolean = false,
    val kind: Kind = Kind.SONG,
    /** "1.2M plays", "3.4M views": shown where YouTube Music shows it. */
    val playsText: String? = null,
    val likeStatus: LikeStatus? = null,
    /** This entry's id inside a playlist. Needed to remove or move it there. */
    val setVideoId: String? = null,
    val libraryTokens: LibraryTokens? = null,
    /** False for greyed-out entries that cannot play. */
    val isAvailable: Boolean = true,
) {
    @Serializable
    enum class Kind {
        /** An album track with a cover (YouTube Music's "ATV"). */
        @SerialName("song") SONG,

        /** A music video or an upload, with a wide still. */
        @SerialName("video") VIDEO,

        @SerialName("episode") EPISODE,
    }

    val id: String get() = videoId

    /** "Artist A, Artist B & Artist C". */
    val artistLine: String
        get() {
            val names = artists.map { it.name }
            return when (names.size) {
                0 -> ""
                1 -> names[0]
                else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
            }
        }
}

@Serializable
data class AlbumItem(
    /** The album's browse id (`MPREb_…`). */
    val id: String,
    /** The playlist that plays the album (`OLAK5uy_…`), when the listing has it. */
    val playlistId: String? = null,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: String? = null,
    /** "Album", "Single", "EP". */
    val typeText: String? = null,
    val artwork: Artwork = Artwork.EMPTY,
    val isExplicit: Boolean = false,
)

@Serializable
data class ArtistItem(
    /** The artist's channel (`UC…`). */
    val id: String,
    val name: String,
    val artwork: Artwork = Artwork.EMPTY,
    /** "12.3M subscribers", "4.5M monthly audience". */
    val detailText: String? = null,
)

@Serializable
data class PlaylistItem(
    /** The playlist id without the `VL` browse prefix (`PL…`, `RDCLAK5uy_…`, `LM` for liked music). */
    val id: String,
    val title: String,
    /** "Playlist • YouTube Music", "Tohin • 42 songs". */
    val detailText: String? = null,
    val artwork: Artwork = Artwork.EMPTY,
)

/**
 * One entry in a shelf, a search result or the library. The four kinds are exclusive; the subclasses only carry the
 * item (named `OfSong`… because `Song`, `AlbumItem`… are the models themselves).
 */
@Serializable
sealed class MusicItem {
    @Serializable
    @SerialName("song")
    data class OfSong(val song: Song) : MusicItem()

    @Serializable
    @SerialName("album")
    data class OfAlbum(val album: AlbumItem) : MusicItem()

    @Serializable
    @SerialName("artist")
    data class OfArtist(val artist: ArtistItem) : MusicItem()

    @Serializable
    @SerialName("playlist")
    data class OfPlaylist(val playlist: PlaylistItem) : MusicItem()

    val asSong: Song? get() = (this as? OfSong)?.song
    val asAlbum: AlbumItem? get() = (this as? OfAlbum)?.album
    val asArtist: ArtistItem? get() = (this as? OfArtist)?.artist
    val asPlaylist: PlaylistItem? get() = (this as? OfPlaylist)?.playlist

    val id: String
        get() = when (this) {
            is OfSong -> "song:${song.videoId}:${song.setVideoId ?: ""}"
            is OfAlbum -> "album:${album.id}"
            is OfArtist -> "artist:${artist.id}"
            is OfPlaylist -> "playlist:${playlist.id}"
        }

    val title: String
        get() = when (this) {
            is OfSong -> song.title
            is OfAlbum -> album.title
            is OfArtist -> artist.name
            is OfPlaylist -> playlist.title
        }

    val artwork: Artwork
        get() = when (this) {
            is OfSong -> song.artwork
            is OfAlbum -> album.artwork
            is OfArtist -> artist.artwork
            is OfPlaylist -> playlist.artwork
        }

    /** The second line under the title, the way YouTube Music words it: "Song • Artist", "Album • Artist • 2024". */
    val subtitle: String
        get() = when (this) {
            is OfSong -> {
                val kind = when (song.kind) {
                    Song.Kind.VIDEO -> "Video"
                    Song.Kind.EPISODE -> "Episode"
                    Song.Kind.SONG -> "Song"
                }
                listOf(kind, song.artistLine).filter { it.isNotEmpty() }.joinToString(" • ")
            }
            is OfAlbum -> {
                val artists = album.artists.joinToString(", ") { it.name }
                listOf(album.typeText ?: "Album", artists, album.year ?: "").filter { it.isNotEmpty() }
                    .joinToString(" • ")
            }
            is OfArtist -> listOf("Artist", artist.detailText ?: "").filter { it.isNotEmpty() }.joinToString(" • ")
            is OfPlaylist -> playlist.detailText ?: "Playlist"
        }
}
