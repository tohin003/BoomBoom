package io.github.boomboom.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Pages and their parts: what a screen draws after one request.

/**
 * Where a "More" button, a chip or a card leads: a `browse` request. [browseId] is YouTube Music's own id
 * (`VL…` for playlists, `MPREb_…` for albums, `UC…` for artists), exactly as the service links it.
 */
@Serializable
data class BrowseEndpoint(
    val browseId: String,
    val params: String? = null,
    val pageType: PageType = PageType.GENERIC,
) {
    @Serializable
    enum class PageType {
        @SerialName("album") ALBUM,
        @SerialName("artist") ARTIST,
        @SerialName("playlist") PLAYLIST,
        @SerialName("userChannel") USER_CHANNEL,
        @SerialName("podcast") PODCAST,
        @SerialName("generic") GENERIC,
    }
}

/** What a play button starts: a `next` request for a video, optionally inside a playlist or as a radio. */
@Serializable
data class WatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    val index: Int? = null,
)

/** A filter pill at the top of Home ("Relax", "Workout") or a mood or genre tile in Explore. */
@Serializable
data class Chip(
    val title: String,
    val endpoint: BrowseEndpoint?,
    val isSelected: Boolean = false,
    /** The tile's edge colour for moods and genres, as 0xRRGGBB. */
    val colorHex: Int? = null,
) {
    val id: String get() = title + (endpoint?.params ?: "")
}

@Serializable
enum class SearchFilter(val rawValue: String, val title: String) {
    @SerialName("songs") SONGS("songs", "Songs"),
    @SerialName("videos") VIDEOS("videos", "Videos"),
    @SerialName("albums") ALBUMS("albums", "Albums"),
    @SerialName("artists") ARTISTS("artists", "Artists"),
    @SerialName("communityPlaylists") COMMUNITY_PLAYLISTS("communityPlaylists", "Community playlists"),
    @SerialName("featuredPlaylists") FEATURED_PLAYLISTS("featuredPlaylists", "Featured playlists"),
    @SerialName("podcasts") PODCASTS("podcasts", "Podcasts"),
    @SerialName("episodes") EPISODES("episodes", "Episodes"),
    @SerialName("profiles") PROFILES("profiles", "Profiles"),
}

/** A titled group of items: a carousel on Home, a section of search results, an artist's albums. */
@Serializable
data class Shelf(
    val id: String,
    val title: String,
    /** The small line above the title ("START RADIO FROM A SONG", "SIMILAR TO"). */
    val strapline: String? = null,
    /** The round picture next to the header on "Similar to …" shelves. */
    val headerArtwork: Artwork? = null,
    val layout: Layout,
    val items: List<MusicItem> = emptyList(),
    /** Where the header's "More" button leads. */
    val more: BrowseEndpoint? = null,
    /** In search: the filter that "Show all" applies. */
    val moreFilter: SearchFilter? = null,
    /** For `layout == TEXT`. */
    val text: String? = null,
    /** Loads more items of this shelf. */
    val continuation: String? = null,
) {
    @Serializable
    enum class Layout {
        /** Side-scrolling cards (covers with two lines of text). */
        @SerialName("carousel") CAROUSEL,

        /** Rows: small cover, title, second line. */
        @SerialName("list") LIST,

        /** Rows in side-scrolling columns of four ("Quick picks"). */
        @SerialName("columns") COLUMNS,

        /** A two- or three-across grid. */
        @SerialName("grid") GRID,

        /** The big "Top result" card in search. `items` holds the top item first, then its quick rows. */
        @SerialName("topResult") TOP_RESULT,

        /** Numbered rows with a trend arrow (charts). */
        @SerialName("ranked") RANKED,

        /** A block of text (an artist's "About", a description). The text is in `text`. */
        @SerialName("text") TEXT,
    }
}

/** A page of shelves: Home, Explore, charts, a mood, a "More" page, a library tab. */
@Serializable
data class BrowsePage(
    val title: String? = null,
    val chips: List<Chip> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    /** Loads more shelves (Home scrolls on). */
    val continuation: String? = null,
)

/** One line under the search field while typing. */
@Serializable
sealed class SearchSuggestion {
    /** Words to search for. `runs` marks the part the listener has not typed yet in bold. */
    @Serializable
    @SerialName("query")
    data class Query(val query: SuggestedQuery) : SearchSuggestion()

    /** A song, artist, album or playlist to open at once. */
    @Serializable
    @SerialName("item")
    data class Item(val item: MusicItem) : SearchSuggestion()

    val id: String
        get() = when (this) {
            is Query -> "q:${query.text}"
            is Item -> "i:${item.id}"
        }
}

/** [runs] is never empty: a suggestion without styling is one plain run of its whole text (see [plain]). */
@Serializable
data class SuggestedQuery(
    val text: String,
    val runs: List<Run> = listOf(Run(text, isBold = false)),
    /** True for the listener's own earlier searches (a clock icon instead of a magnifier). */
    val isFromHistory: Boolean = false,
) {
    @Serializable
    data class Run(val text: String, val isBold: Boolean)

    init {
        require(runs.isNotEmpty()) { "a suggestion has at least one run; use SuggestedQuery.of for styling that may be missing" }
    }

    companion object {
        /** Like the constructor, but an empty [runs] means "the whole text, not bold". */
        fun of(text: String, runs: List<Run>, isFromHistory: Boolean = false): SuggestedQuery =
            SuggestedQuery(text, runs.ifEmpty { listOf(Run(text, isBold = false)) }, isFromHistory)
    }
}

@Serializable
data class SearchResults(
    val query: String,
    /** "Showing results for …" when YouTube Music corrected the words. */
    val correctedQuery: String? = null,
    val filter: SearchFilter? = null,
    /** Unfiltered: a `TOP_RESULT` shelf, then one `LIST` shelf per kind. Filtered: one `LIST` shelf. */
    val shelves: List<Shelf> = emptyList(),
    /** Loads more rows of a filtered search. */
    val continuation: String? = null,
)

@Serializable
data class AlbumPage(
    val album: AlbumItem,
    val description: String? = null,
    /** "12 songs • 45 minutes". */
    val statsText: String? = null,
    val tracks: List<Song> = emptyList(),
    /** "Other versions", "Releases for you". */
    val shelves: List<Shelf> = emptyList(),
    val likeStatus: LikeStatus? = null,
    val libraryTokens: LibraryTokens? = null,
)

@Serializable
data class ArtistPage(
    val artist: ArtistItem,
    /** The wide header picture. */
    val banner: Artwork = Artwork.EMPTY,
    val description: String? = null,
    val subscribersText: String? = null,
    val monthlyListenersText: String? = null,
    val isSubscribed: Boolean = false,
    /** The "Shuffle" and "Radio" buttons. */
    val shuffle: WatchEndpoint? = null,
    val radio: WatchEndpoint? = null,
    /** "Top songs" (`LIST`), "Albums", "Singles & EPs", "Videos", "Featured on", "Fans might also like". */
    val shelves: List<Shelf> = emptyList(),
)

@Serializable
data class PlaylistPage(
    val playlist: PlaylistItem,
    val description: String? = null,
    val authorName: String? = null,
    val authorArtwork: Artwork? = null,
    /** "50 songs • 3 hours, 2 minutes". */
    val statsText: String? = null,
    val tracks: List<Song> = emptyList(),
    /** Loads more tracks. */
    val continuation: String? = null,
    /** True for the listener's own playlists (rename, reorder, remove). */
    val isEditable: Boolean = false,
    val likeStatus: LikeStatus? = null,
    /** "Related playlists", suggestions. */
    val shelves: List<Shelf> = emptyList(),
)

/** More rows for a list that scrolls on (playlist tracks, filtered search, a queue). */
@Serializable
data class SongsContinuation(val songs: List<Song>, val continuation: String?)

/** The "Up next" queue YouTube Music builds for a song: the rest of the playlist, or a radio. */
@Serializable
data class WatchQueue(
    val songs: List<Song> = emptyList(),
    /** The position of the requested song in [songs]. */
    val currentIndex: Int = 0,
    /** The playlist behind the queue (`RDAMVM…` for a radio). */
    val playlistId: String? = null,
    /** Loads more of the queue. */
    val continuation: String? = null,
    /** For the player's "Lyrics" tab. Null when the song has none. */
    val lyricsBrowseId: String? = null,
    /** For the player's "Related" tab. */
    val relatedBrowseId: String? = null,
)

@Serializable
data class Lyrics(
    val lines: List<Line>,
    /** "Source: Musixmatch". */
    val source: String? = null,
) {
    @Serializable
    data class Line(
        val text: String,
        /** Seconds from the start, when the lyrics are timed. */
        val start: Double? = null,
    )

    val isTimed: Boolean get() = lines.any { it.start != null }
}

/** The signed-in listener. */
@Serializable
data class Account(
    val name: String,
    val handle: String? = null,
    val artwork: Artwork = Artwork.EMPTY,
    /** True with YouTube Music Premium (256 kbps audio). */
    val isPremium: Boolean = false,
)

@Serializable
enum class PlaylistPrivacy(val rawValue: String) {
    @SerialName("PUBLIC") PUBLIC("PUBLIC"),
    @SerialName("UNLISTED") UNLISTED("UNLISTED"),
    @SerialName("PRIVATE") PRIVATE("PRIVATE"),
}

/** The lists under Library. */
@Serializable
enum class LibrarySection(val rawValue: String, val title: String) {
    @SerialName("playlists") PLAYLISTS("playlists", "Playlists"),
    @SerialName("songs") SONGS("songs", "Songs"),
    @SerialName("albums") ALBUMS("albums", "Albums"),
    @SerialName("artists") ARTISTS("artists", "Artists"),
    @SerialName("subscriptions") SUBSCRIPTIONS("subscriptions", "Subscriptions"),
}
