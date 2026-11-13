package io.github.boomboom.ui.preview

import io.github.boomboom.models.Account
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.Chip
import io.github.boomboom.models.ChipGroup
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.LibraryTokens
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.MusicItem
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
import io.github.boomboom.models.SuggestedQuery
import io.github.boomboom.models.WatchEndpoint
import io.github.boomboom.models.WatchQueue
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The calls a [PreviewMusicAPI] received, in order, safe to read from any thread. */
class CallLog {
    private val entries = ArrayList<String>()

    @Synchronized
    fun append(entry: String) {
        entries.add(entry)
    }

    val all: List<String> @Synchronized get() = entries.toList()

    @Synchronized
    fun count(prefix: String): Int = entries.count { it.startsWith(prefix) }
}

/**
 * A complete [MusicAPI] on invented data, for previews, the screens' tests and UI work. Answers arrive after a short
 * random delay (100–300 ms) so loading states show, and honour cancellation. The waiting runs on the caller's
 * dispatcher, so a test dispatcher makes it virtual. Nothing here is a real artist or song.
 *
 * @param delays Wait before answering.
 * @param signedIn Library calls answer (otherwise they throw `SignedOut`) and `account()` returns someone.
 * @param failsBrowsing Every page request throws a network error, to see error states.
 * @param latency The range of the delay, in milliseconds.
 * @param delayOverride Fixed delays per call for tests: `(call, input) -> delay`, null for the normal delay.
 * @param failOverride Calls that fail for tests: `(call, input) -> fails`, decided when the call answers.
 */
class PreviewMusicAPI(
    private val delays: Boolean = true,
    private val signedIn: Boolean = true,
    private val failsBrowsing: Boolean = false,
    private val latency: IntRange = 100..300,
    private val delayOverride: ((String, String) -> Duration?)? = null,
    private val failOverride: ((String, String) -> Boolean)? = null,
) : MusicAPI {
    val catalog = PreviewCatalog()

    /** Every call, in order, for tests. */
    val log = CallLog()

    companion object {
        /** The listener the preview pretends is signed in. */
        val previewAccount = Account(
            name = "Alex Rivera", handle = "@alexrivera", artwork = PreviewCatalog.square("listener"), isPremium = false,
        )

        val HOME_CHIPS = listOf("Energize", "Relax", "Workout", "Focus", "Commute", "Feel good", "Party", "Romance", "Sad", "Sleep")
    }

    private suspend fun wait(call: String, input: String = "", page: Boolean = false) {
        log.append("$call:$input")
        val fixed = delayOverride?.invoke(call, input)
        if (fixed != null) {
            delay(fixed)
        } else if (delays) {
            delay(Random.nextInt(latency.first, latency.last + 1).milliseconds)
        }
        if (page && failsBrowsing) throw MusicAPIError.Network("Preview: browsing is set to fail.")
        if (failOverride?.invoke(call, input) == true) throw MusicAPIError.Network("Preview: this call is set to fail.")
    }

    private fun requireSignIn() {
        if (!signedIn) throw MusicAPIError.SignedOut
    }

    // Search

    override suspend fun searchSuggestions(input: String): List<SearchSuggestion> {
        wait("suggestions", input)
        val typed = input.trim().lowercase(Locale.ROOT)
        if (typed.isEmpty()) return emptyList()
        val history = setOf("juniper vale", "low orbit", "lofi beats to study")
        val phrases = listOf(
            "lofi beats to study", "love songs", "lo-fi chill", "lonely nights playlist", "live sessions",
            "late night drive", "summer hits", "sad songs", "workout mix", "acoustic covers", "jazz for rainy days",
            "new music friday", "party anthems", "piano focus",
        )
        val corpus = catalog.artists.map { it.name } + catalog.albums.map { it.title } +
            catalog.songs.map { it.title }.toSet().sorted() + phrases
        val seen = HashSet<String>()
        val queries = ArrayList<SuggestedQuery>()
        for (text in corpus.map { it.lowercase(Locale.ROOT) }) {
            if (text in seen) continue
            if (!(text.startsWith(typed) || text.split(' ').any { it.startsWith(typed) })) continue
            seen.add(text)
            val runs = if (text.startsWith(typed)) {
                listOf(SuggestedQuery.Run(text.take(typed.length), false), SuggestedQuery.Run(text.drop(typed.length), true))
                    .filter { it.text.isNotEmpty() }
            } else {
                listOf(SuggestedQuery.Run(text, true))
            }
            queries.add(SuggestedQuery.of(text, runs, isFromHistory = text in history))
        }
        val sorted = queries.sortedWith(
            compareBy<SuggestedQuery> { !it.isFromHistory }.thenBy { !it.text.startsWith(typed) },
        )
        val items = ArrayList<SearchSuggestion>()
        catalog.artists.filter { it.name.lowercase(Locale.ROOT).startsWith(typed) }.take(1)
            .forEach { items.add(SearchSuggestion.Item(MusicItem.OfArtist(it))) }
        catalog.songs.filter { it.title.lowercase(Locale.ROOT).startsWith(typed) }.take(2)
            .forEach { items.add(SearchSuggestion.Item(MusicItem.OfSong(it))) }
        catalog.albums.filter { it.title.lowercase(Locale.ROOT).startsWith(typed) }.take(1)
            .forEach { items.add(SearchSuggestion.Item(MusicItem.OfAlbum(it))) }
        return sorted.take(7).map { SearchSuggestion.Query(it) } + items.take(3)
    }

    private class Matches(
        val songs: List<Song> = emptyList(),
        val videos: List<Song> = emptyList(),
        val albums: List<io.github.boomboom.models.AlbumItem> = emptyList(),
        val artists: List<io.github.boomboom.models.ArtistItem> = emptyList(),
    ) {
        val isEmpty: Boolean get() = songs.isEmpty() && videos.isEmpty() && albums.isEmpty() && artists.isEmpty()
    }

    private fun matches(words: String): Matches {
        if (words.isEmpty()) return Matches()
        fun hit(text: String) = text.lowercase(Locale.ROOT).contains(words)
        return Matches(
            songs = catalog.songs.filter { song -> hit(song.title) || song.artists.any { hit(it.name) } },
            videos = catalog.videos.filter { song -> hit(song.title) || song.artists.any { hit(it.name) } },
            albums = catalog.albums.filter { album -> hit(album.title) || album.artists.any { hit(it.name) } },
            artists = catalog.artists.filter { hit(it.name) },
        )
    }

    private fun <T> padded(first: List<T>, filler: List<T>, count: Int, id: (T) -> String): List<T> {
        val seen = first.map(id).toHashSet()
        val result = first.toMutableList()
        for (item in filler) {
            if (result.size >= count) break
            if (seen.add(id(item))) result.add(item)
        }
        return result
    }

    private fun commonPrefix(a: String, b: String): Int = a.zip(b).takeWhile { it.first == it.second }.size

    private fun items(
        filter: SearchFilter,
        songs: List<Song>,
        videos: List<Song>,
        albums: List<io.github.boomboom.models.AlbumItem>,
        artists: List<io.github.boomboom.models.ArtistItem>,
        playlists: List<PlaylistItem>,
    ): List<MusicItem> = when (filter) {
        SearchFilter.SONGS -> songs.map { MusicItem.OfSong(it) }
        SearchFilter.VIDEOS -> videos.map { MusicItem.OfSong(it) }
        SearchFilter.ALBUMS -> albums.map { MusicItem.OfAlbum(it) }
        SearchFilter.ARTISTS -> artists.map { MusicItem.OfArtist(it) }
        SearchFilter.COMMUNITY_PLAYLISTS -> playlists.drop(4).map { MusicItem.OfPlaylist(it) }
        SearchFilter.FEATURED_PLAYLISTS -> playlists.take(8).map { MusicItem.OfPlaylist(it) }
        SearchFilter.PODCASTS, SearchFilter.EPISODES, SearchFilter.PROFILES -> emptyList()
    }

    override suspend fun search(query: String, filter: SearchFilter?): SearchResults {
        wait("search", "$query|${filter?.rawValue ?: ""}", page = true)
        var words = query.trim().lowercase(Locale.ROOT)
        var corrected: String? = null
        if (words.isNotEmpty() && matches(words).isEmpty) {
            // Pretend to fix a typo: the artist that shares the most leading letters.
            val best = catalog.artists.maxByOrNull { commonPrefix(it.name.lowercase(Locale.ROOT), words) }
            if (best != null && commonPrefix(best.name.lowercase(Locale.ROOT), words) >= 2) {
                corrected = best.name
                words = best.name.lowercase(Locale.ROOT)
            }
        }
        val seed = PreviewCatalog.stableHash(words)
        val found = matches(words)
        val songs = padded(found.songs, catalog.songs(seed, 30), 30) { it.videoId }
        val videos = padded(found.videos, catalog.videos(seed, 20), 20) { it.videoId }
        val albums = padded(found.albums, catalog.albums(seed, 20), 20) { it.id }
        val artists = padded(found.artists, catalog.artists(seed, 14), 14) { it.id }
        val playlists = catalog.playlists(seed, 14)

        if (filter != null) {
            val all = items(filter, songs, videos, albums, artists, playlists)
            val shelf = Shelf("filtered-${filter.rawValue}", filter.title, layout = Shelf.Layout.LIST, items = all.take(20))
            val token = if (all.isNotEmpty()) "s|${filter.rawValue}|$words|1" else null
            return SearchResults(query, corrected, filter, listOf(shelf), token)
        }

        val top: MusicItem
        val quick: List<MusicItem>
        val artist = found.artists.firstOrNull()
        val song = found.songs.firstOrNull()
        val album = found.albums.firstOrNull()
        when {
            artist != null -> {
                top = MusicItem.OfArtist(artist)
                quick = catalog.songsOf(artist.id).take(3).map { MusicItem.OfSong(it) }
            }
            song != null -> {
                top = MusicItem.OfSong(song)
                quick = songs.drop(1).take(3).map { MusicItem.OfSong(it) }
            }
            album != null -> {
                top = MusicItem.OfAlbum(album)
                quick = (catalog.tracksByAlbum[album.id] ?: emptyList()).take(3).map { MusicItem.OfSong(it) }
            }
            else -> {
                top = MusicItem.OfArtist(artists[0])
                quick = catalog.songsOf(artists[0].id).take(3).map { MusicItem.OfSong(it) }
            }
        }
        val shelves = listOf(
            Shelf("top", "Top result", layout = Shelf.Layout.TOP_RESULT, items = listOf(top) + quick),
            Shelf("songs", "Songs", layout = Shelf.Layout.LIST, items = songs.take(4).map { MusicItem.OfSong(it) }, moreFilter = SearchFilter.SONGS),
            Shelf("videos", "Videos", layout = Shelf.Layout.LIST, items = videos.take(3).map { MusicItem.OfSong(it) }, moreFilter = SearchFilter.VIDEOS),
            Shelf("albums", "Albums", layout = Shelf.Layout.LIST, items = albums.take(3).map { MusicItem.OfAlbum(it) }, moreFilter = SearchFilter.ALBUMS),
            Shelf("artists", "Artists", layout = Shelf.Layout.LIST, items = artists.take(3).map { MusicItem.OfArtist(it) }, moreFilter = SearchFilter.ARTISTS),
            Shelf(
                "community", "Community playlists", layout = Shelf.Layout.LIST,
                items = playlists.drop(4).take(3).map { MusicItem.OfPlaylist(it) }, moreFilter = SearchFilter.COMMUNITY_PLAYLISTS,
            ),
            Shelf(
                "featured", "Featured playlists", layout = Shelf.Layout.LIST,
                items = playlists.take(2).map { MusicItem.OfPlaylist(it) }, moreFilter = SearchFilter.FEATURED_PLAYLISTS,
            ),
        )
        return SearchResults(query, corrected, null, shelves)
    }

    override suspend fun searchContinuation(token: String): SongsOrItemsContinuation {
        wait("searchContinuation", token, page = true)
        val parts = token.split('|')
        val filter = parts.getOrNull(1)?.let { raw -> SearchFilter.entries.firstOrNull { it.rawValue == raw } }
        val page = parts.getOrNull(3)?.toIntOrNull()
        if (parts.size != 4 || filter == null || page == null) throw MusicAPIError.Unreadable("Preview: bad search token")
        val seed = PreviewCatalog.stableHash(parts[2]) + page * 17
        val all = items(
            filter, catalog.songs(seed, 20), catalog.videos(seed, 20), catalog.albums(seed, 20), catalog.artists(seed, 14),
            catalog.playlists(seed, 14),
        )
        val next = if (page < 3) "s|${parts[1]}|${parts[2]}|${page + 1}" else null
        return SongsOrItemsContinuation(all.take(20), next)
    }

    // Browse

    override suspend fun home(chip: Chip?): BrowsePage {
        wait("home", chip?.title ?: "", page = true)
        val chips = HOME_CHIPS.map { title ->
            Chip(title, BrowseEndpoint("FEmusic_home", params = "chip:$title"), isSelected = title == chip?.title)
        }
        val seed = chip?.let { PreviewCatalog.stableHash(it.title) % 97 } ?: 0
        val similar = catalog.artists[seed % catalog.artists.size]
        val shelves = mutableListOf(
            Shelf(
                "quick", "Quick picks", strapline = "Start radio from a song", layout = Shelf.Layout.COLUMNS,
                items = catalog.songs(seed + 1, 20).map { MusicItem.OfSong(it) },
            ),
            Shelf("again", if (chip == null) "Listen again" else "${chip.title} favourites", layout = Shelf.Layout.CAROUSEL, items = mixed(seed + 2, 10)),
            Shelf(
                "similar", similar.name, strapline = "Similar to", headerArtwork = similar.artwork, layout = Shelf.Layout.CAROUSEL,
                items = catalog.albums(seed + 3, 8).map { MusicItem.OfAlbum(it) },
                more = BrowseEndpoint(similar.id, pageType = BrowseEndpoint.PageType.ARTIST),
            ),
            Shelf(
                "mixes", "Mixed for you", layout = Shelf.Layout.CAROUSEL,
                items = catalog.playlists(seed, 6).map { MusicItem.OfPlaylist(it) }, more = BrowseEndpoint("FEmusic_mixed_for_you"),
            ),
            Shelf("videos", "Recommended music videos", layout = Shelf.Layout.CAROUSEL, items = catalog.videos(seed + 4, 8).map { MusicItem.OfSong(it) }),
            Shelf(
                "trending", "Trending songs", layout = Shelf.Layout.RANKED,
                items = catalog.songs(seed + 5, 6).map { MusicItem.OfSong(it) }, more = BrowseEndpoint("FEmusic_charts"),
            ),
            Shelf("albums", "Albums for you", layout = Shelf.Layout.GRID, items = catalog.albums(seed + 6, 4).map { MusicItem.OfAlbum(it) }),
            Shelf("artists", "Artists you might like", layout = Shelf.Layout.CAROUSEL, items = catalog.artists(seed + 7, 8).map { MusicItem.OfArtist(it) }),
        )
        if (chip != null) repeat(2) { shelves.removeAt(shelves.lastIndex) }
        return BrowsePage(chips = chips, shelves = shelves, continuation = "home|$seed|1")
    }

    override suspend fun explore(): BrowsePage {
        wait("explore", page = true)
        return BrowsePage(
            title = "Explore",
            shelves = listOf(
                Shelf(
                    "new", "New albums & singles", layout = Shelf.Layout.CAROUSEL,
                    items = catalog.albums(11, 10).map { MusicItem.OfAlbum(it) }, more = BrowseEndpoint("FEmusic_new_releases_albums"),
                ),
                Shelf(
                    "trending", "Trending", layout = Shelf.Layout.RANKED,
                    items = catalog.songs(12, 8).map { MusicItem.OfSong(it) }, more = BrowseEndpoint("FEmusic_charts"),
                ),
                Shelf(
                    "videos", "New music videos", layout = Shelf.Layout.CAROUSEL,
                    items = catalog.videos(13, 8).map { MusicItem.OfSong(it) }, more = BrowseEndpoint("FEmusic_new_releases_videos"),
                ),
                Shelf("popular", "Popular playlists", layout = Shelf.Layout.CAROUSEL, items = catalog.playlists(4, 8).map { MusicItem.OfPlaylist(it) }),
            ),
        )
    }

    override suspend fun charts(country: String?): BrowsePage {
        wait("charts", country ?: "", page = true)
        val seed = PreviewCatalog.stableHash(country ?: "ZZ") % 50
        val countries = listOf("Global" to "ZZ", "United States" to "US", "India" to "IN", "United Kingdom" to "GB")
        return BrowsePage(
            title = "Charts",
            chips = countries.map { (name, code) ->
                Chip(name, BrowseEndpoint("FEmusic_charts", params = code), isSelected = code == (country ?: "ZZ"))
            },
            shelves = listOf(
                Shelf(
                    "top-songs", "Top songs", layout = Shelf.Layout.RANKED,
                    items = catalog.songs(seed + 20, 10).map { MusicItem.OfSong(it) },
                    more = BrowseEndpoint("PLbb_topsongs", pageType = BrowseEndpoint.PageType.PLAYLIST),
                ),
                Shelf("top-videos", "Top music videos", layout = Shelf.Layout.CAROUSEL, items = catalog.videos(seed + 21, 10).map { MusicItem.OfSong(it) }),
                Shelf("trending", "Trending", layout = Shelf.Layout.RANKED, items = catalog.songs(seed + 22, 10).map { MusicItem.OfSong(it) }),
                Shelf("top-artists", "Top artists", layout = Shelf.Layout.RANKED, items = catalog.artists(seed + 23, 10).map { MusicItem.OfArtist(it) }),
            ),
        )
    }

    override suspend fun moodsAndGenres(): List<ChipGroup> {
        wait("moods", page = true)
        fun chips(entries: List<Pair<String, Int>>) = entries.map { (title, colour) ->
            Chip(title, BrowseEndpoint("FEmusic_moods_and_genres_category", params = title), colorHex = colour)
        }
        return listOf(
            ChipGroup(
                "Moods & moments",
                chips(
                    listOf(
                        "Chill" to 0x3E8E7E, "Commute" to 0xD08A3E, "Energy boosters" to 0xE85D4A, "Feel good" to 0xF2C14E,
                        "Focus" to 0x5B7DB1, "Party" to 0xC0479A, "Romance" to 0xE0607E, "Sad" to 0x6A6FA8,
                        "Sleep" to 0x2F4B7C, "Workout" to 0xD9534F,
                    ),
                ),
            ),
            ChipGroup(
                "Genres",
                chips(
                    listOf(
                        "Pop" to 0xE86FB0, "Hip-hop" to 0xA36BE0, "Rock" to 0xC94B3C, "Indie & alternative" to 0x58A55C,
                        "Electronic" to 0x3FA7D6, "R&B & soul" to 0x9B5DE5, "Jazz" to 0xD4A373, "Folk & acoustic" to 0x8AA162,
                        "Classical" to 0xB8A07E, "Country & americana" to 0xC77D3A, "Latin" to 0xF06543, "Metal" to 0x707070,
                    ),
                ),
            ),
        )
    }

    override suspend fun browse(endpoint: BrowseEndpoint): BrowsePage {
        wait("browse", endpoint.browseId + (endpoint.params ?: ""), page = true)
        val seed = PreviewCatalog.stableHash(endpoint.browseId + (endpoint.params ?: "")) % 101
        when (endpoint.browseId) {
            "FEmusic_charts" -> return charts(endpoint.params)
            "FEmusic_new_releases" -> return BrowsePage(
                title = "New releases",
                shelves = listOf(
                    Shelf(
                        "albums", "New albums & singles", layout = Shelf.Layout.GRID,
                        items = catalog.albums(30, 8).map { MusicItem.OfAlbum(it) }, more = BrowseEndpoint("FEmusic_new_releases_albums"),
                    ),
                    Shelf("videos", "New music videos", layout = Shelf.Layout.CAROUSEL, items = catalog.videos(31, 8).map { MusicItem.OfSong(it) }),
                    Shelf("fresh", "Fresh this week", layout = Shelf.Layout.LIST, items = catalog.songs(32, 8).map { MusicItem.OfSong(it) }),
                ),
                continuation = "page|new|1",
            )
            "FEmusic_moods_and_genres_category" -> {
                val title = endpoint.params ?: "Mood"
                return BrowsePage(
                    title = title,
                    shelves = listOf(
                        Shelf("featured", "Featured playlists", layout = Shelf.Layout.GRID, items = catalog.playlists(seed, 6).map { MusicItem.OfPlaylist(it) }),
                        Shelf("community", "Community playlists", layout = Shelf.Layout.CAROUSEL, items = catalog.playlists(seed + 6, 8).map { MusicItem.OfPlaylist(it) }),
                        Shelf("songs", "$title songs", layout = Shelf.Layout.LIST, items = catalog.songs(seed, 8).map { MusicItem.OfSong(it) }),
                    ),
                )
            }
        }
        if (endpoint.browseId.startsWith("MPAD")) {
            val artist = catalog.artistsById[endpoint.browseId.drop(4)]
            if (artist != null) {
                return BrowsePage(
                    title = "${artist.name}: Albums",
                    shelves = listOf(Shelf("all", "Albums", layout = Shelf.Layout.GRID, items = catalog.albumsOf(artist.id).map { MusicItem.OfAlbum(it) })),
                )
            }
        }
        val title = endpoint.browseId.replace("FEmusic_", "").replace("_", " ")
            .split(' ').joinToString(" ") { word -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        return BrowsePage(
            title = title,
            shelves = listOf(
                Shelf(
                    "grid", "For you", layout = Shelf.Layout.GRID,
                    items = catalog.playlists(seed, 4).map { MusicItem.OfPlaylist(it) } + catalog.albums(seed, 4).map { MusicItem.OfAlbum(it) },
                ),
                Shelf("list", "Songs", layout = Shelf.Layout.LIST, items = catalog.songs(seed, 6).map { MusicItem.OfSong(it) }),
            ),
            continuation = "page|$seed|1",
        )
    }

    override suspend fun browseContinuation(token: String): BrowsePage {
        wait("browseContinuation", token, page = true)
        val parts = token.split('|')
        val page = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 3 || page == null) throw MusicAPIError.Unreadable("Preview: bad browse token")
        val seed = PreviewCatalog.stableHash(parts[1]) % 89 + page * 13
        if (parts[0] == "home") {
            val pages = listOf(
                listOf(
                    Shelf("fresh-$page", "Fresh finds", layout = Shelf.Layout.CAROUSEL, items = catalog.albums(seed, 8).map { MusicItem.OfAlbum(it) }),
                    Shelf("covers-$page", "Covers & remixes", layout = Shelf.Layout.LIST, items = catalog.songs(seed + 1, 4).map { MusicItem.OfSong(it) }),
                ),
                listOf(
                    Shelf("live-$page", "Live performances", layout = Shelf.Layout.CAROUSEL, items = catalog.videos(seed + 2, 8).map { MusicItem.OfSong(it) }),
                    Shelf("long-$page", "Long listens", layout = Shelf.Layout.CAROUSEL, items = catalog.playlists(seed + 3, 8).map { MusicItem.OfPlaylist(it) }),
                ),
            )
            return BrowsePage(shelves = pages[(page - 1) % pages.size], continuation = if (page < 2) "home|${parts[1]}|${page + 1}" else null)
        }
        return BrowsePage(
            shelves = listOf(Shelf("more-$page", "More to explore", layout = Shelf.Layout.CAROUSEL, items = catalog.albums(seed, 8).map { MusicItem.OfAlbum(it) })),
        )
    }

    private fun mixed(seed: Int, count: Int): List<MusicItem> = (0 until count).map { index ->
        when (index % 4) {
            0 -> MusicItem.OfAlbum(catalog.albums(seed + index, 1)[0])
            1 -> MusicItem.OfArtist(catalog.artists(seed + index, 1)[0])
            2 -> MusicItem.OfPlaylist(catalog.playlists(seed + index, 1)[0])
            else -> MusicItem.OfSong(catalog.songs(seed + index, 1)[0])
        }
    }

    // Pages

    override suspend fun album(id: String): AlbumPage {
        wait("album", id, page = true)
        val album = catalog.albumsById[id] ?: catalog.albums[PreviewCatalog.stableHash(id) % catalog.albums.size]
        val tracks = catalog.tracksByAlbum[album.id] ?: emptyList()
        val minutes = (tracks.sumOf { it.duration ?: 0.0 } / 60).toInt()
        val artist = album.artists.firstOrNull()
        val others = artist?.id?.let { artistId -> catalog.albumsOf(artistId).filter { it.id != album.id } } ?: emptyList()
        return AlbumPage(
            album = album,
            description = "${album.title} is the ${album.year ?: "latest"} ${(album.typeText ?: "album").lowercase(Locale.ROOT)} by ${artist?.name ?: "the artist"}. " +
                "Recorded over a long winter in a converted boathouse, it trades the polish of earlier records for " +
                "warm tape hiss, close-miked vocals and songs that unfold slowly. Critics called it a quiet triumph; " +
                "fans called it the soundtrack to every late drive home.",
            statsText = "${tracks.size} song${if (tracks.size == 1) "" else "s"} • $minutes minutes",
            tracks = tracks,
            shelves = listOf(
                Shelf(
                    "more", "More from ${artist?.name ?: "this artist"}", layout = Shelf.Layout.CAROUSEL,
                    items = others.map { MusicItem.OfAlbum(it) },
                    more = artist?.id?.let { BrowseEndpoint(it, pageType = BrowseEndpoint.PageType.ARTIST) },
                ),
                Shelf(
                    "releases", "Releases for you", layout = Shelf.Layout.CAROUSEL,
                    items = catalog.albums(PreviewCatalog.stableHash(id) % 40, 8).map { MusicItem.OfAlbum(it) },
                ),
            ).filter { it.items.isNotEmpty() },
            likeStatus = LikeStatus.INDIFFERENT,
            libraryTokens = LibraryTokens("add-${album.id}", "remove-${album.id}", isInLibrary = false),
        )
    }

    override suspend fun artist(id: String): ArtistPage {
        wait("artist", id, page = true)
        val artist = catalog.artistsById[id] ?: catalog.artists[PreviewCatalog.stableHash(id) % catalog.artists.size]
        val albums = catalog.albumsOf(artist.id)
        val seed = PreviewCatalog.stableHash(artist.id) % 60
        return ArtistPage(
            artist = artist,
            banner = PreviewCatalog.banner("banner" + artist.id),
            description = "${artist.name} make bright, restless music somewhere between bedroom pop and festival " +
                "indie. Formed in a shared flat above a laundrette, they built a following one late-night upload at " +
                "a time before their debut found its way onto playlists around the world. Their live shows are " +
                "loud, sweaty and joyful, and their records reward headphones.",
            subscribersText = artist.detailText,
            monthlyListenersText = PreviewCatalog.countText(seed + 9, "monthly audience"),
            isSubscribed = false,
            shuffle = WatchEndpoint(playlistId = "RDAO" + artist.id),
            radio = WatchEndpoint(playlistId = "RDEM" + artist.id),
            shelves = listOf(
                Shelf(
                    "top", "Top songs", layout = Shelf.Layout.LIST,
                    items = catalog.songsOf(artist.id).take(10).map { MusicItem.OfSong(it) },
                    more = BrowseEndpoint("PLbb_top" + artist.id, pageType = BrowseEndpoint.PageType.PLAYLIST),
                ),
                Shelf(
                    "albums", "Albums", layout = Shelf.Layout.CAROUSEL,
                    items = albums.filter { it.typeText == "Album" }.map { MusicItem.OfAlbum(it) }, more = BrowseEndpoint("MPAD" + artist.id),
                ),
                Shelf(
                    "singles", "Singles & EPs", layout = Shelf.Layout.CAROUSEL,
                    items = albums.filter { it.typeText != "Album" }.map { MusicItem.OfAlbum(it) } + catalog.albums(seed, 3).map { MusicItem.OfAlbum(it) },
                ),
                Shelf("videos", "Videos", layout = Shelf.Layout.CAROUSEL, items = catalog.videosOf(artist.id).map { MusicItem.OfSong(it) }),
                Shelf("featured", "Featured on", layout = Shelf.Layout.CAROUSEL, items = catalog.playlists(seed, 5).map { MusicItem.OfPlaylist(it) }),
                Shelf(
                    "similar", "Fans might also like", layout = Shelf.Layout.CAROUSEL,
                    items = catalog.artists(seed + 1, 8).filter { it.id != artist.id }.map { MusicItem.OfArtist(it) },
                ),
            ).filter { it.items.isNotEmpty() },
        )
    }

    override suspend fun playlist(id: String): PlaylistPage {
        wait("playlist", id, page = true)
        val bare = if (id.startsWith("VL")) id.drop(2) else id
        val all = playlistSongs(bare)
        val item = playlistItem(bare)
        val minutes = (all.sumOf { it.duration ?: 0.0 } / 60).toInt()
        val isTop = bare.startsWith("PLbb_top")
        return PlaylistPage(
            playlist = item,
            description = if (isTop) null else "Handpicked tracks for exactly this mood, refreshed every week. Press play and let it run.",
            authorName = if (bare == "LM") "You" else item.detailText?.split('•')?.drop(1)?.firstOrNull()?.trim() ?: "BoomBoom",
            authorArtwork = PreviewCatalog.square("author"),
            statsText = "${all.size} songs • ${minutes / 60} hours, ${minutes % 60} minutes",
            tracks = all.take(25),
            continuation = if (all.size > 25) "pl|$bare|1" else null,
            isEditable = bare == "LM",
            likeStatus = LikeStatus.INDIFFERENT,
            shelves = if (isTop) {
                emptyList()
            } else {
                listOf(
                    Shelf(
                        "related", "Related playlists", layout = Shelf.Layout.CAROUSEL,
                        items = catalog.playlists(PreviewCatalog.stableHash(bare) % 20, 6).filter { it.id != bare }.map { MusicItem.OfPlaylist(it) },
                    ),
                )
            },
        )
    }

    override suspend fun playlistContinuation(token: String): SongsContinuation {
        wait("playlistContinuation", token, page = true)
        val parts = token.split('|')
        val page = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 3 || page == null) throw MusicAPIError.Unreadable("Preview: bad playlist token")
        val all = playlistSongs(parts[1])
        val start = page * 25
        val slice = all.drop(start).take(25)
        val next = if (start + 25 < all.size) "pl|${parts[1]}|${page + 1}" else null
        return SongsContinuation(slice, next)
    }

    private fun playlistItem(id: String): PlaylistItem {
        catalog.playlistsById[id]?.let { return it }
        if (id == "LM") return PlaylistItem("LM", "Liked music", "Auto playlist", PreviewCatalog.square("liked"))
        if (id.startsWith("PLbb_top")) {
            catalog.artistsById[id.drop(8)]?.let { artist ->
                return PlaylistItem(id, "${artist.name}: Top songs", "Playlist • BoomBoom", artist.artwork)
            }
        }
        if (id == "PLbb_topsongs") return PlaylistItem(id, "Top 100 songs", "Chart • BoomBoom", PreviewCatalog.square("top100"))
        return PlaylistItem(id, "Untitled playlist", "Playlist", PreviewCatalog.square(id))
    }

    private fun playlistSongs(id: String): List<Song> {
        if (id.startsWith("PLbb_top")) {
            catalog.artistsById[id.drop(8)]?.let { return catalog.songsOf(it.id) }
        }
        val seed = PreviewCatalog.stableHash(id) % 300
        val songs = catalog.songs(seed, if (id == "LM") 40 else 70)
            .mapIndexed { index, song -> song.copy(setVideoId = "set-$id-$index") }.toMutableList()
        // A greyed-out entry, as real playlists have.
        if (songs.size > 12) songs[11] = songs[11].copy(isAvailable = false)
        return songs
    }

    // Playing

    override suspend fun watchQueue(endpoint: WatchEndpoint): WatchQueue {
        wait("watchQueue", (endpoint.videoId ?: "") + "|" + (endpoint.playlistId ?: ""))
        var songs: MutableList<Song>
        var current = 0
        val playlistId = endpoint.playlistId
        songs = when {
            playlistId != null && (!playlistId.startsWith("RD") || playlistId.startsWith("RDTMAK")) -> playlistSongs(playlistId).toMutableList()
            playlistId != null && playlistId.startsWith("RDAMPLOLAK5uy_") -> {
                val albumSlug = playlistId.drop("RDAMPLOLAK5uy_".length)
                ((catalog.tracksByAlbum["MPREb_$albumSlug"] ?: catalog.songs(3, 25)) + catalog.songs(albumSlug.length, 15)).toMutableList()
            }
            playlistId != null && (playlistId.startsWith("RDAO") || playlistId.startsWith("RDEM")) -> {
                val artistId = playlistId.drop(4)
                val own = catalog.songsOf(artistId)
                (if (playlistId.startsWith("RDAO")) own.shuffled() else own + catalog.songs(artistId.length, 15)).toMutableList()
            }
            else -> catalog.songs(PreviewCatalog.stableHash(endpoint.videoId ?: playlistId ?: "radio"), 25).toMutableList()
        }
        val videoId = endpoint.videoId
        if (videoId != null) {
            val index = songs.indexOfFirst { it.videoId == videoId }
            if (index >= 0) {
                current = index
            } else {
                catalog.songsById[videoId]?.let {
                    songs.add(0, it)
                    current = 0
                }
            }
        } else {
            val index = endpoint.index
            if (index != null && index in songs.indices) current = index
        }
        val lead = songs.getOrNull(current)?.videoId ?: ""
        return WatchQueue(
            songs = songs, currentIndex = current, playlistId = playlistId ?: "RDAMVM$lead",
            continuation = "wq|${PreviewCatalog.stableHash(lead) % 50}|1",
            lyricsBrowseId = "MPLYt_$lead", relatedBrowseId = "MPTRt_$lead",
        )
    }

    override suspend fun watchQueueContinuation(token: String): SongsContinuation {
        wait("watchQueueContinuation", token)
        val parts = token.split('|')
        val seed = parts.getOrNull(1)?.toIntOrNull()
        val page = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 3 || seed == null || page == null) throw MusicAPIError.Unreadable("Preview: bad queue token")
        return SongsContinuation(catalog.songs(seed + page * 11, 20), if (page < 5) "wq|$seed|${page + 1}" else null)
    }

    override suspend fun lyrics(browseId: String): Lyrics {
        wait("lyrics", browseId)
        val verses = listOf(
            "Streetlights hum a song I almost know", "Every window glowing soft and low",
            "I keep your number written on my hand", "Like a map to somewhere I can't stand",
            "", "And oh, the night is wide awake", "Every promise that we didn't make",
            "Turn it up until the windows shake", "We were young enough to never break",
            "", "Paper boats along the gutter stream", "Half a summer stitched into a dream",
            "You said the stars were only satellites", "But they still looked like a thousand lights",
            "", "And oh, the night is wide awake", "Every promise that we didn't make",
            "Turn it up until the windows shake", "We were young enough to never break",
            "", "So drive me home the long way round", "Keep the radio and lose the sound",
            "We'll be fine, we'll be fine", "Just hold on till the morning line",
        )
        var time = 8.0
        val lines = verses.map { text ->
            val line = Lyrics.Line(text, time)
            time += if (text.isEmpty()) 2.0 else 4.2
            line
        }
        return Lyrics(lines, "Source: Preview lyrics")
    }

    override suspend fun related(browseId: String): List<Shelf> {
        wait("related", browseId)
        val seed = PreviewCatalog.stableHash(browseId) % 70
        val artist = catalog.artists[seed % catalog.artists.size]
        return listOf(
            Shelf("like", "You might also like", layout = Shelf.Layout.LIST, items = catalog.songs(seed, 6).map { MusicItem.OfSong(it) }),
            Shelf("playlists", "Recommended playlists", layout = Shelf.Layout.CAROUSEL, items = catalog.playlists(seed, 6).map { MusicItem.OfPlaylist(it) }),
            Shelf("artists", "Similar artists", layout = Shelf.Layout.CAROUSEL, items = catalog.artists(seed + 1, 6).map { MusicItem.OfArtist(it) }),
            Shelf(
                "about", "About the artist", layout = Shelf.Layout.TEXT,
                text = "${artist.name} write songs for long drives and longer nights. Their latest record was made " +
                    "in a week, live to tape, with the windows open.",
            ),
        )
    }

    // Library

    override suspend fun account(): Account? {
        wait("account")
        return if (signedIn) previewAccount else null
    }

    override suspend fun library(section: LibrarySection): BrowsePage {
        wait("library", section.rawValue, page = true)
        requireSignIn()
        val items: List<MusicItem> = when (section) {
            LibrarySection.PLAYLISTS -> listOf(MusicItem.OfPlaylist(playlistItem("LM"))) + catalog.playlists(2, 8).map { MusicItem.OfPlaylist(it) }
            LibrarySection.SONGS -> catalog.songs(77, 30).map { MusicItem.OfSong(it) }
            LibrarySection.ALBUMS -> catalog.albums(5, 12).map { MusicItem.OfAlbum(it) }
            LibrarySection.ARTISTS, LibrarySection.SUBSCRIPTIONS -> catalog.artists(3, 10).map { MusicItem.OfArtist(it) }
        }
        return BrowsePage(
            title = section.title,
            shelves = listOf(
                Shelf(
                    "library-${section.rawValue}", section.title,
                    layout = if (section == LibrarySection.ALBUMS) Shelf.Layout.GRID else Shelf.Layout.LIST, items = items,
                ),
            ),
        )
    }

    override suspend fun likedSongs(): PlaylistPage {
        requireSignIn()
        return playlist("LM")
    }

    override suspend fun history(): BrowsePage {
        wait("history", page = true)
        requireSignIn()
        return BrowsePage(
            title = "History",
            shelves = listOf(
                Shelf("today", "Today", layout = Shelf.Layout.LIST, items = catalog.songs(91, 6).map { MusicItem.OfSong(it) }),
                Shelf("yesterday", "Yesterday", layout = Shelf.Layout.LIST, items = catalog.songs(92, 8).map { MusicItem.OfSong(it) }),
                Shelf("week", "This week", layout = Shelf.Layout.LIST, items = catalog.songs(93, 10).map { MusicItem.OfSong(it) }),
            ),
        )
    }

    override suspend fun rate(videoId: String, status: LikeStatus) {
        wait("rate", "$videoId|${status.rawValue}")
        requireSignIn()
    }

    override suspend fun ratePlaylist(id: String, status: LikeStatus) {
        wait("ratePlaylist", id)
        requireSignIn()
    }

    override suspend fun subscribe(channelId: String, subscribed: Boolean) {
        wait("subscribe", "$channelId|$subscribed")
        requireSignIn()
    }

    override suspend fun sendLibraryFeedback(token: String) {
        wait("libraryFeedback", token)
        requireSignIn()
    }

    override suspend fun createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: List<String>): String {
        wait("createPlaylist", title)
        requireSignIn()
        return "PLbb_new" + PreviewCatalog.slug(title)
    }

    override suspend fun deletePlaylist(id: String) {
        wait("deletePlaylist", id)
        requireSignIn()
    }

    override suspend fun editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) {
        wait("editPlaylist", id)
        requireSignIn()
    }

    override suspend fun addToPlaylist(id: String, videoIds: List<String>) {
        wait("addToPlaylist", id)
        requireSignIn()
    }

    override suspend fun removeFromPlaylist(id: String, songs: List<Song>) {
        wait("removeFromPlaylist", id)
        requireSignIn()
    }
}
