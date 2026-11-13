package io.github.boomboom.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

private fun albumPage(id: String = "MPREb_harborlights") = BrowseStart.Page(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.ALBUM))
private fun artistPage(id: String = "UCbbjunipervale") = BrowseStart.Page(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.ARTIST))
private fun playlistPage(id: String) = BrowseStart.Page(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.PLAYLIST))

/** The canned data with one album renamed to something very long, to see how titles wrap and cut. */
private class LongTitles(private val base: PreviewMusicAPI) : MusicAPI by base {
    override suspend fun album(id: String): AlbumPage {
        val page = base.album(id)
        return page.copy(
            album = page.album.copy(
                title = "The Extraordinarily Long Title of an Album That Goes On and On Through Several Lines of Text",
            ),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class BrowseScreenshots : ScreenTest() {
    @Test
    fun explore() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Tab(BrowseTab.EXPLORE))
        shot("explore")
    }

    @Test
    fun charts() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Charts)
        shot("charts")
    }

    @Test
    fun moods() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Moods)
        shot("moods")
    }

    @Test
    fun newReleases() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Page(BrowseEndpoint("FEmusic_new_releases")))
        shot("browse_new_releases")
    }

    @Test
    fun moodPage() {
        showApp(
            services(PreviewMusicAPI(delays = false)),
            BrowseStart.Page(BrowseEndpoint("FEmusic_moods_and_genres_category", params = "Chill")),
        )
        compose.onNodeWithTag("page.list").performScrollToIndex(2)
        shot("browse_mood_scrolled")
    }

    @Test
    fun browseCouldNotLoad() {
        showApp(services(PreviewMusicAPI(delays = false, failsBrowsing = true)), BrowseStart.Charts)
        shot("browse_could_not_load")
    }

    @Test
    fun libraryComing() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Tab(BrowseTab.LIBRARY))
        shot("library_coming")
    }

    @Test
    fun accountMenu() {
        val api = PreviewMusicAPI(delays = false)
        val others = listOf(PreviewMusicAPI.previewAccount.copy(name = "Sam Okafor", handle = "@samokafor"))
        showApp(services(api, others = others))
        compose.onNodeWithTag("topbar.account").performClick()
        shot("home_account_menu")
    }

    @Test
    fun saveToPlaylist() {
        val api = PreviewMusicAPI(delays = false)
        showApp(services(api))
        val song = api.catalog.songs[5]
        compose.runOnIdle {
            // The sheet the menu's "Save to playlist" opens.
            controller.open(BrowseEndpoint(song.album!!.id!!, pageType = BrowseEndpoint.PageType.ALBUM))
        }
        compose.onNodeWithTag("collection.more").performClick()
        compose.onNodeWithTag("menu.saveToPlaylist").performClick()
        shot("save_to_playlist")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class CollectionScreenshots : ScreenTest() {
    @Test
    fun albumLoaded() {
        showApp(services(PreviewMusicAPI(delays = false)), albumPage())
        shot("album_loaded")
    }

    @Test
    fun albumScrolled() {
        showApp(services(PreviewMusicAPI(delays = false)), albumPage())
        compose.onNodeWithTag("album.list").performScrollToIndex(5)
        shot("album_scrolled")
    }

    @Test
    fun albumLoading() {
        showApp(services(PreviewMusicAPI(delayOverride = { _, _ -> 10.minutes })), albumPage())
        shot("album_loading")
    }

    @Test
    fun albumCouldNotLoad() {
        showApp(services(PreviewMusicAPI(delays = false, failsBrowsing = true)), albumPage())
        shot("album_could_not_load")
    }

    @Test
    fun albumLongTitle() {
        val api = PreviewMusicAPI(delays = false)
        val services = services(api)
        showApp(AppServices(LongTitles(api), services.player, services.equalizer, services.session, services.cache, services.downloads), albumPage())
        shot("album_long_title")
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 1.3f)
    fun albumLargeFont() {
        showApp(services(PreviewMusicAPI(delays = false)), albumPage())
        shot("album_font_1.3")
    }

    @Test
    fun artist() {
        showApp(services(PreviewMusicAPI(delays = false)), artistPage())
        shot("artist_loaded")
    }

    @Test
    fun artistTopSongs() {
        showApp(services(PreviewMusicAPI(delays = false)), artistPage())
        compose.onNodeWithTag("artist.list").performScrollToIndex(1)
        shot("artist_scrolled")
    }

    @Test
    fun artistSignedOut() {
        showApp(services(PreviewMusicAPI(delays = false, signedIn = false), signedIn = false), artistPage("UCbbmirasolace"))
        shot("artist_signed_out")
    }

    @Test
    fun artistLoading() {
        showApp(services(PreviewMusicAPI(delayOverride = { _, _ -> 10.minutes })), artistPage())
        shot("artist_loading")
    }

    @Test
    fun playlist() {
        showApp(services(PreviewMusicAPI(delays = false)), playlistPage("PLbb_latenightdrive"))
        shot("playlist_loaded")
    }

    @Test
    fun playlistLoadingMore() {
        val api = PreviewMusicAPI(delays = false, delayOverride = { call, _ -> if (call == "playlistContinuation") 10.minutes else null })
        showApp(services(api), playlistPage("PLbb_latenightdrive"))
        compose.onNodeWithTag("playlist.list").performScrollToNode(hasTestTag("loadMore"))
        shot("playlist_loading_more")
    }

    @Test
    fun playlistMoreFailed() {
        val api = PreviewMusicAPI(delays = false, failOverride = { call, _ -> call == "playlistContinuation" })
        showApp(services(api), playlistPage("PLbb_latenightdrive"))
        compose.onNodeWithTag("playlist.list").performScrollToNode(hasTestTag("loadMore"))
        shot("playlist_more_failed")
    }

    @Test
    fun ownPlaylistEditor() {
        showApp(services(PreviewMusicAPI(delays = false)), playlistPage("LM"))
        compose.onNodeWithTag("playlist.edit").performClick()
        shot("playlist_editor")
    }

    @Test
    fun downloadLooks() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeDownloadBackend()
        val services = services(api, backend)
        showApp(services, albumPage())
        shot("download_1_not_downloaded")

        compose.onNodeWithTag("collection.download").performClick()
        compose.waitForIdle()
        val ids = backend.started.toList()
        backend.send(DownloadEvent.Finished(ids[0], 6_400_000, "Opus 256 kbps"))
        backend.send(DownloadEvent.Progress(ids[1], 0.4))
        compose.waitForIdle()
        shot("download_2_arriving")

        ids.drop(1).dropLast(1).forEach { backend.send(DownloadEvent.Finished(it, 7_100_000, "Opus 256 kbps")) }
        backend.send(DownloadEvent.Failed(ids.last(), "No connection"))
        compose.waitForIdle()
        shot("download_3_partly_failed")

        compose.onNodeWithTag("collection.download").performClick()
        shot("download_3_partly_failed_dialog")
        compose.onNodeWithText("Try the missing songs again").performClick()
        backend.send(DownloadEvent.Finished(ids.last(), 6_900_000, "Opus 256 kbps"))
        compose.waitForIdle()
        shot("download_4_downloaded")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SearchScreenshots : ScreenTest() {
    @Test
    fun empty() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search(null))
        shot("search_empty")
    }

    @Test
    fun recents() {
        val api = PreviewMusicAPI(delays = false)
        showApp(services(api), BrowseStart.Search(null))
        for (words in listOf("night bus hymns", "juniper vale", "low orbit")) {
            searchFor(words)
        }
        compose.onNodeWithTag("search.clear").performClick()
        shot("search_recents")
    }

    @Test
    fun suggestions() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("lo", submitted = false))
        advance(200)
        shot("search_suggestions")
    }

    @Test
    fun results() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("juniper"))
        shot("search_results")
    }

    @Test
    fun resultsScrolled() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("juniper"))
        compose.onNodeWithTag("search.results").performScrollToIndex(4)
        shot("search_results_scrolled")
    }

    @Test
    fun filtered() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("juniper"))
        compose.onNode(hasTestTag("chip") and hasText(SearchFilter.SONGS.title)).performClick()
        shot("search_filtered_songs")
    }

    @Test
    fun corrected() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("junipr vail"))
        shot("search_corrected")
    }

    @Test
    fun noResults() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("juniper"))
        compose.onNodeWithTag("chipBar").performScrollToNode(hasText(SearchFilter.PODCASTS.title))
        compose.onNode(hasTestTag("chip") and hasText(SearchFilter.PODCASTS.title)).performClick()
        shot("search_no_results")
    }

    /** Types the words and presses the keyboard's search key: each search goes into the recent list. */
    private fun searchFor(words: String) {
        compose.onNodeWithTag("search.field").performTextReplacement(words)
        compose.onNodeWithTag("search.field").performImeAction()
    }
}
