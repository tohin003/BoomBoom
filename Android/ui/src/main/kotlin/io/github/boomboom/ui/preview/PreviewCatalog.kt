package io.github.boomboom.ui.preview

import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.AlbumRef
import io.github.boomboom.models.ArtistItem
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.PreviewArt
import java.util.Locale
import kotlin.math.abs

/**
 * The invented artists, albums, songs, videos and playlists behind [PreviewMusicAPI]. Everything is derived from fixed
 * word lists, so the same ids give the same items on every launch. Nothing here is a real artist, song or playlist.
 * The same catalogue as the iPhone app's preview data.
 */
class PreviewCatalog {
    val artists: List<ArtistItem>
    val albums: List<AlbumItem>

    /** Album id → its tracks. */
    val tracksByAlbum: Map<String, List<Song>>

    /** Every album track, in catalogue order. */
    val songs: List<Song>
    val videos: List<Song>
    val playlists: List<PlaylistItem>

    /** Video id → song or video. */
    val songsById: Map<String, Song>
    val artistsById: Map<String, ArtistItem>
    val albumsById: Map<String, AlbumItem>
    val playlistsById: Map<String, PlaylistItem>

    init {
        val artists = ArrayList<ArtistItem>()
        val albums = ArrayList<AlbumItem>()
        val tracksByAlbum = LinkedHashMap<String, List<Song>>()
        val songs = ArrayList<Song>()
        val videos = ArrayList<Song>()
        var titleIndex = 0
        for ((artistIndex, name) in ARTIST_NAMES.withIndex()) {
            val artistId = "UCbb" + slug(name)
            val millions = (3 + (artistIndex * 37) % 90) / 10.0
            val artist = ArtistItem(
                id = artistId, name = name, artwork = square(artistId),
                detailText = String.format(Locale.UK, "%.1fM subscribers", millions),
            )
            artists.add(artist)
            val ref = ArtistRef(name, artistId)
            for (albumIndex in 0 until 3) {
                val title = ALBUM_TITLES[(artistIndex * 3 + albumIndex) % ALBUM_TITLES.size]
                val albumId = "MPREb_" + slug(title)
                val types = listOf("Album", "Album", "EP", "Single")
                val type = if (albumIndex == 2) types[(artistIndex + 2) % 4] else "Album"
                val album = AlbumItem(
                    id = albumId, playlistId = "OLAK5uy_" + slug(title), title = title, artists = listOf(ref),
                    year = (2026 - (artistIndex + albumIndex * 3) % 9).toString(), typeText = type,
                    artwork = square(albumId), isExplicit = (artistIndex + albumIndex) % 5 == 1,
                )
                albums.add(album)
                val count = when (type) {
                    "Single" -> 2
                    "EP" -> 5
                    else -> 9 + (artistIndex + albumIndex) % 5
                }
                val tracks = ArrayList<Song>()
                repeat(count) {
                    val credited = ArrayList<ArtistRef>().apply { add(ref) }
                    if (titleIndex % 6 == 4) {
                        val guest = ARTIST_NAMES[(artistIndex + 5) % ARTIST_NAMES.size]
                        credited.add(ArtistRef(guest, "UCbb" + slug(guest)))
                    }
                    tracks.add(
                        Song(
                            videoId = "bb" + String.format(Locale.UK, "%05d", titleIndex), title = songTitle(titleIndex),
                            artists = credited, album = AlbumRef(title, albumId),
                            duration = (148 + (titleIndex * 37) % 150).toDouble(), artwork = album.artwork,
                            isExplicit = titleIndex % 7 == 3, kind = Song.Kind.SONG,
                            playsText = countText(titleIndex, "plays"),
                            likeStatus = if (titleIndex % 9 == 0) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
                            isAvailable = titleIndex % 41 != 40,
                        ),
                    )
                    titleIndex += 1
                }
                tracksByAlbum[albumId] = tracks
                songs.addAll(tracks)
            }
            for (videoIndex in 0 until 3) {
                val source = songs[songs.size - 1 - videoIndex * 3]
                val videoId = "bv" + String.format(Locale.UK, "%05d", artistIndex * 3 + videoIndex)
                val suffix = listOf("(Official Video)", "(Live Session)", "(Visualizer)")[videoIndex]
                videos.add(
                    Song(
                        videoId = videoId, title = "${source.title} $suffix", artists = listOf(ref),
                        duration = (source.duration ?: 200.0) + 12, artwork = wide(videoId), isExplicit = source.isExplicit,
                        kind = Song.Kind.VIDEO, playsText = countText(artistIndex * 3 + videoIndex + 5, "views"),
                    ),
                )
            }
        }
        this.artists = artists
        this.albums = albums
        this.tracksByAlbum = tracksByAlbum
        this.songs = songs
        this.videos = videos
        playlists = PLAYLIST_TITLES.mapIndexed { index, (title, detail) ->
            PlaylistItem(
                id = (if (index < 4) "RDTMAK5uy_bb" else "PLbb_") + slug(title), title = title, detailText = detail,
                artwork = square("pl" + slug(title)),
            )
        }
        songsById = (songs + videos).associateBy { it.videoId }
        artistsById = artists.associateBy { it.id }
        albumsById = albums.reversed().associateBy { it.id }
        playlistsById = playlists.associateBy { it.id }
    }

    // Picking

    /** [count] songs starting at a seed-dependent place, wrapping around, skipping greyed-out ones. */
    fun songs(seed: Int, count: Int): List<Song> {
        val pool = songs.filter { it.isAvailable }
        val start = abs(seed * 31) % pool.size
        return (0 until count).map { pool[(start + it * 7) % pool.size] }
    }

    fun videos(seed: Int, count: Int): List<Song> = (0 until count).map { videos[(abs(seed) + it * 5) % videos.size] }

    fun albums(seed: Int, count: Int): List<AlbumItem> = (0 until count).map { albums[(abs(seed) * 3 + it * 4) % albums.size] }

    fun artists(seed: Int, count: Int): List<ArtistItem> =
        (0 until minOf(count, artists.size)).map { artists[(abs(seed) + it * 3) % artists.size] }

    fun playlists(seed: Int, count: Int): List<PlaylistItem> =
        (0 until minOf(count, playlists.size)).map { playlists[(abs(seed) + it) % playlists.size] }

    fun albumsOf(artistId: String): List<AlbumItem> = albums.filter { it.artists.firstOrNull()?.id == artistId }

    fun songsOf(artistId: String): List<Song> = songs.filter { song -> song.artists.any { it.id == artistId } && song.isAvailable }

    fun videosOf(artistId: String): List<Song> = videos.filter { it.artists.firstOrNull()?.id == artistId }

    companion object {
        val ARTIST_NAMES = listOf(
            "Juniper Vale", "Neon Harbor", "Mira Solace", "Kaito Rivers", "Velvet Static", "Sable & Pine",
            "Lumen Drive", "Asha Monroe", "Cobalt Youth", "Marlowe Grey", "Sunday Arcade", "Tidewater Choir",
            "Oro Lune", "Fennel Park",
        )

        val ALBUM_TITLES = listOf(
            "Afterhours Atlas", "Tin Roof Psalms", "Kodachrome Weather", "Small Hours", "Field Notes for Lovers",
            "The Long Exhale", "Harbor Lights", "Sodium Glow", "Weekend Cartography", "Blue Hour Radio",
            "Paper Planets", "Analog Heart", "Distant Shores", "Coastline Theory", "Night Bus Hymns",
            "Saltwater Diaries", "Bright Static", "Gardens of Concrete", "Heatwave Postcards", "Glow in the Margins",
            "Second Summer", "Low Orbit", "Porchlight", "Wide Awake in Lisbon", "Soft Focus", "Velour",
            "The Quiet Year", "Holograms & Honey", "Ferris Wheel Season", "Lemon Light", "Signal Fires",
            "Room Tone", "Telephone Weather", "Arcadia Lane", "Midnight Errands", "Cloud Atlas Club",
            "Overgrown", "Seaglass", "Rooftop Static", "Late Bloomer", "Every Shade of Blue", "Northbound",
        )

        val FIRST_WORDS = listOf(
            "Paper", "Neon", "Velvet", "Golden", "Silver", "Midnight", "Hollow", "Static", "Copper", "Glass",
            "Electric", "Quiet", "Wild", "Lucid", "Amber", "Crimson", "Salt", "Cherry", "Lonely", "Summer",
            "Winter", "Northern", "Broken", "Slow",
        )

        val SECOND_WORDS = listOf(
            "Moons", "Hearts", "Rivers", "Skies", "Lights", "Arcade", "Bloom", "Highway", "Letters", "Fever",
            "Tide", "Garden", "Satellites", "Echoes", "Parade", "Signals", "Dreams", "Waves", "Ghosts", "Avenue",
            "Weather", "Season", "Machines", "Horizon",
        )

        val PLAYLIST_TITLES = listOf(
            "Supermix" to "Mix • Juniper Vale, Neon Harbor and more",
            "Discover Mix" to "Mix • Fresh picks for you",
            "Chill Mix" to "Mix • Mira Solace, Oro Lune and more",
            "New Release Mix" to "Mix • Updated every Friday",
            "Late Night Drive" to "Playlist • BoomBoom • 64 songs",
            "Sunday Slow-down" to "Playlist • BoomBoom • 48 songs",
            "Indie Lift" to "Playlist • BoomBoom • 70 songs",
            "Focus Flow" to "Playlist • BoomBoom • 55 songs",
            "Workout Fuel" to "Playlist • BoomBoom • 60 songs",
            "Rainy Window" to "Playlist • Nadia K • 41 songs",
            "Golden Hour Pop" to "Playlist • BoomBoom • 52 songs",
            "Throwback Anthems" to "Playlist • Theo M • 87 songs",
            "Coffeehouse Acoustic" to "Playlist • BoomBoom • 45 songs",
            "Synthwave Nights" to "Playlist • Ravi P • 66 songs",
        )

        fun songTitle(index: Int): String {
            val count = FIRST_WORDS.size
            val first = index % count
            val second = (index / count + first * 5) % SECOND_WORDS.size
            return "${FIRST_WORDS[first]} ${SECOND_WORDS[second]}"
        }

        fun slug(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        fun stableHash(text: String): Int = PreviewArt.stableHash(text)

        fun countText(seed: Int, unit: String): String {
            val value = 1 + (seed * 53) % 480
            return if (value >= 100) "${value / 10}M $unit" else "${value * 10}K $unit"
        }

        fun square(seed: String): Artwork = PreviewArt.artwork(seed, 24 to 24, 400 to 400)
        fun wide(seed: String): Artwork = PreviewArt.artwork(seed, 32 to 18, 640 to 360)
        fun banner(seed: String): Artwork = PreviewArt.artwork(seed, 36 to 24, 1080 to 720)
    }
}
