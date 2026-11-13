package io.github.boomboom.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkTests {
    @Test
    fun googleImagesAreResized() {
        val art = Artwork(
            listOf(
                Thumbnail("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 60, 60),
                Thumbnail("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 120, 120),
            ),
        )
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", art.url(pixelWidth = 544))
    }

    @Test
    fun sizeOnlyGoogleImagesAreResized() {
        val art = Artwork(listOf(Thumbnail("https://yt3.ggpht.com/xyz=s88-c-k-c0x00ffffff-no-rj", 88, 88)))
        assertEquals("https://yt3.ggpht.com/xyz=s300-c-k-c0x00ffffff-no-rj", art.url(pixelWidth = 300))
    }

    @Test
    fun videoStillsPickTheSmallestThatFits() {
        val art = Artwork(
            listOf(
                Thumbnail("https://i.ytimg.com/vi/x/sddefault.jpg?sqp=a", 640, 480),
                Thumbnail("https://i.ytimg.com/vi/x/default.jpg", 120, 90),
                Thumbnail("https://i.ytimg.com/vi/x/hqdefault.jpg", 480, 360),
            ),
        )
        fun lastPathComponent(url: String?) = url?.substringBefore('?')?.substringAfterLast('/')
        assertEquals("hqdefault.jpg", lastPathComponent(art.url(pixelWidth = 300)))
        assertEquals("sddefault.jpg", lastPathComponent(art.url(pixelWidth = 2000)))
        assertNull(Artwork.EMPTY.url(pixelWidth = 100))
    }

    @Test
    fun artistLineJoinsNames() {
        val song = Song(videoId = "v", title = "t", artists = listOf(ArtistRef("A"), ArtistRef("B"), ArtistRef("C")))
        assertEquals("A, B & C", song.artistLine)
    }

    // The same four, plus what the Kotlin version adds: saving to disk and the sort order.

    @Test
    fun thumbnailsAreSortedSmallestFirstWhateverTheOrder() {
        val art = Artwork(listOf(Thumbnail("https://a/2", 200, 200), Thumbnail("https://a/1", 100, 100)))
        assertEquals(listOf(100, 200), art.thumbnails.map { it.width })
        assertEquals("https://a/2", art.largestUrl)
        assertTrue(Artwork.EMPTY.isEmpty)
    }

    @Test
    fun aSongSurvivesBeingSavedAndLoaded() {
        val song = Song(
            videoId = "abc",
            title = "A title",
            artists = listOf(ArtistRef("An artist", "UCx")),
            album = AlbumRef("An album", "MPREb_x"),
            duration = 183.0,
            artwork = Artwork(listOf(Thumbnail("https://a/2", 200, 200), Thumbnail("https://a/1", 100, 100))),
            isExplicit = true,
            kind = Song.Kind.VIDEO,
            playsText = "1.2M views",
            likeStatus = LikeStatus.LIKE,
            setVideoId = "SET",
            libraryTokens = LibraryTokens("add", null, false),
            isAvailable = false,
        )
        val text = Json.encodeToString(Song.serializer(), song)
        assertEquals(song, Json.decodeFromString(Song.serializer(), text))
        // The saved form keeps the iPhone app's spelling of the enum values.
        assertTrue(text.contains("\"kind\":\"video\""), text)
        assertTrue(text.contains("\"likeStatus\":\"LIKE\""), text)
    }

    @Test
    fun itemsAndPagesSurviveBeingSavedAndLoaded() {
        val items = listOf<MusicItem>(
            MusicItem.OfSong(Song("v", "t")),
            MusicItem.OfAlbum(AlbumItem("MPREb_x", title = "a", year = "2020")),
            MusicItem.OfArtist(ArtistItem("UCx", "n", detailText = "1M monthly audience")),
            MusicItem.OfPlaylist(PlaylistItem("PLx", "p")),
        )
        val page = BrowsePage(
            title = "Home",
            chips = listOf(Chip("Relax", BrowseEndpoint("FEmusic_home", "ggM"), colorHex = 0xA4C5FF)),
            shelves = listOf(Shelf("0:x", "x", layout = Shelf.Layout.TOP_RESULT, items = items, moreFilter = SearchFilter.SONGS)),
            continuation = "TOKEN",
        )
        assertEquals(page, Json.decodeFromString(BrowsePage.serializer(), Json.encodeToString(BrowsePage.serializer(), page)))
        val suggestion = SearchSuggestion.Query(SuggestedQuery("oasis"))
        assertEquals(
            suggestion,
            Json.decodeFromString(SearchSuggestion.serializer(), Json.encodeToString(SearchSuggestion.serializer(), suggestion)),
        )
    }

    @Test
    fun itemSubtitlesAreWordedLikeTheService() {
        val song = Song("v", "t", artists = listOf(ArtistRef("A"), ArtistRef("B")), kind = Song.Kind.VIDEO)
        assertEquals("Video • A & B", MusicItem.OfSong(song).subtitle)
        val album = AlbumItem("MPREb_x", title = "a", artists = listOf(ArtistRef("A"), ArtistRef("B")), year = "2024")
        assertEquals("Album • A, B • 2024", MusicItem.OfAlbum(album).subtitle)
        assertEquals("Artist", MusicItem.OfArtist(ArtistItem("UCx", "n")).subtitle)
        assertEquals("Playlist", MusicItem.OfPlaylist(PlaylistItem("PLx", "p")).subtitle)
        assertEquals("song:v:", MusicItem.OfSong(Song("v", "t")).id)
    }
}
