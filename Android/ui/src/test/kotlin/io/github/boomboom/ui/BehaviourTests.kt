package io.github.boomboom.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.preview.PreviewMusicAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

/** The screens' logic, driven through the screens themselves. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class BehaviourTests : ScreenTest() {
    private val router get() = controller.router!!

    @Test
    fun aFilterChipTogglesTheFilteredList() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Search("juniper"))
        val songs = compose.onNode(hasTestTag("chip") and hasText("Songs"))
        songs.assertIsNotSelected()
        compose.onNodeWithText("Top result").assertExists()

        songs.performClick()
        songs.assertIsSelected()
        compose.onNodeWithText("Top result").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithTag("row.song").fetchSemanticsNodes().size >= 5)
        compose.onAllNodesWithTag("row.album").assertCountEquals(0)

        songs.performClick()
        songs.assertIsNotSelected()
        compose.onNodeWithText("Top result").assertExists()
    }

    @Test
    fun endlessLoadingShowsRetryWhenAPartFailsAndRetryLoadsIt() {
        var failing = true
        val api = PreviewMusicAPI(delays = false, failOverride = { call, _ -> failing && call == "playlistContinuation" })
        showApp(services(api), BrowseStart.Page(BrowseEndpoint("PLbb_latenightdrive", pageType = BrowseEndpoint.PageType.PLAYLIST)))
        val list = compose.onNodeWithTag("playlist.list")
        list.performScrollToNode(hasTestTag("loadMore.retry"))
        compose.onNodeWithTag("loadMore.retry").assertExists()
        assertEquals(1, api.log.count("playlistContinuation"))

        failing = false
        compose.onNodeWithTag("loadMore.retry").performClick()
        compose.waitForIdle()
        assertTrue(api.log.count("playlistContinuation") >= 2)
        compose.onNodeWithTag("loadMore.retry").assertDoesNotExist()
        // All 70 songs are in: the header, then 70 rows (only 25 were there before).
        list.performScrollToIndex(70)
        compose.onNodeWithTag("loadMore").assertDoesNotExist()
    }

    @Test
    fun homeLoadsMoreShelvesAtTheEnd() {
        val api = PreviewMusicAPI(delays = false)
        showApp(services(api))
        assertEquals(0, api.log.count("browseContinuation"))
        compose.onNodeWithTag("home.list").performScrollToNode(hasText("Fresh finds"))
        compose.onNodeWithText("Fresh finds").assertExists()
        assertEquals(1, api.log.count("browseContinuation:home|0|1"))
    }

    @Test
    fun eachTabKeepsItsStackAndASecondTapPopsToTheRoot() {
        showApp(services(PreviewMusicAPI(delays = false)))
        // Home: open an album from a card.
        compose.onAllNodesWithTag("card.album").onFirst().performClick()
        compose.onNodeWithTag("album.list").assertExists()

        // Explore: open the charts.
        compose.onNodeWithTag(BrowseTab.EXPLORE.tag).performClick()
        compose.onNodeWithText("Charts").performClick()
        compose.onNodeWithTag("page.list").assertExists()
        assertEquals(listOf(BrowseRoute.Charts), router.path(BrowseTab.EXPLORE))

        // Back to Home: the album is still there, and so is Explore's stack.
        compose.onNodeWithTag(BrowseTab.HOME.tag).performClick()
        compose.onNodeWithTag("album.list").assertExists()
        assertEquals(1, router.path(BrowseTab.HOME).size)
        assertEquals(listOf(BrowseRoute.Charts), router.path(BrowseTab.EXPLORE))

        // A second tap on Home: back to its root. Explore keeps its page.
        compose.onNodeWithTag(BrowseTab.HOME.tag).performClick()
        compose.onNodeWithTag("home.list").assertExists()
        assertEquals(emptyList<BrowseRoute>(), router.path(BrowseTab.HOME))
        assertEquals(listOf(BrowseRoute.Charts), router.path(BrowseTab.EXPLORE))
    }

    @Test
    fun aTapOnTheShownTabAtItsRootScrollsToTheTop() {
        showApp(services(PreviewMusicAPI(delays = false)))
        compose.onNodeWithTag("home.list").performScrollToIndex(5)
        compose.onNodeWithText("Quick picks").assertDoesNotExist()
        compose.onNodeWithTag(BrowseTab.HOME.tag).performClick()
        compose.onNodeWithText("Quick picks").assertExists()
    }

    @Test
    fun backFromAPushedPageReturnsToTheOneBelowWithItsPlaceKept() {
        val api = PreviewMusicAPI(delays = false)
        showApp(services(api))
        compose.onNodeWithTag("home.list").performScrollToIndex(3)
        compose.onAllNodesWithTag("card.album").onFirst().performClick()
        compose.onNodeWithTag("album.list").assertExists()
        val homeLoads = api.log.count("home:")

        // The bar's back button.
        compose.onNodeWithTag("page.back").performClick()
        compose.onNodeWithTag("home.list").assertExists()
        compose.onNodeWithText("Quick picks").assertDoesNotExist()
        assertEquals("Home was kept, not loaded again", homeLoads, api.log.count("home:"))

        // The system back gesture.
        compose.onAllNodesWithTag("card.album").onFirst().performClick()
        compose.onNodeWithTag("album.list").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home.list").assertExists()
        assertEquals(emptyList<BrowseRoute>(), router.path(BrowseTab.HOME))
    }

    @Test
    fun backAtAnotherTabsRootGoesHome() {
        showApp(services(PreviewMusicAPI(delays = false)), BrowseStart.Tab(BrowseTab.EXPLORE))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home.list").assertExists()
        assertEquals(BrowseTab.HOME, router.selected)
    }

    @Test
    fun theItemMenuPutsSongsInTheQueue() {
        val api = PreviewMusicAPI(delays = false)
        val services = services(api)
        val playing = api.catalog.songs[0]
        services.player.play(playing)
        showApp(services, BrowseStart.Page(BrowseEndpoint("MPREb_harborlights", pageType = BrowseEndpoint.PageType.ALBUM)))
        val before = services.player.queue.size
        val tracks = api.catalog.tracksByAlbum.getValue("MPREb_harborlights")

        // "Play next" on the second track: it lands right after the current song.
        compose.onAllNodesWithTag("row.more")[1].performClick()
        compose.onNodeWithTag("menu.playNext").performClick()
        compose.waitForIdle()
        val afterNext = services.player.state.value
        assertEquals(before + 1, afterNext.queue.size)
        assertEquals(playing.videoId, afterNext.current?.videoId)
        assertEquals(tracks[1].videoId, afterNext.queue[afterNext.currentIndex!! + 1].song.videoId)

        // "Add to queue" on the third: it goes to the end.
        compose.onAllNodesWithTag("row.more")[2].performClick()
        compose.onNodeWithTag("menu.addToQueue").performClick()
        compose.waitForIdle()
        val afterAdd = services.player.state.value
        assertEquals(before + 2, afterAdd.queue.size)
        assertEquals(tracks[2].videoId, afterAdd.queue.last().song.videoId)

        // "Play next" on the album itself (its menu in the header): every track, after the current song.
        compose.onNodeWithTag("collection.more").performClick()
        compose.onNodeWithTag("menu.playNext").performClick()
        compose.waitForIdle()
        val afterAlbum = services.player.state.value
        assertEquals(before + 2 + tracks.count { it.isAvailable }, afterAlbum.queue.size)
        assertEquals(tracks[0].videoId, afterAlbum.queue[afterAlbum.currentIndex!! + 1].song.videoId)
    }

    @Test
    fun theDownloadButtonHasFourLooks() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeDownloadBackend()
        showApp(services(api, backend), BrowseStart.Page(BrowseEndpoint("MPREb_harborlights", pageType = BrowseEndpoint.PageType.ALBUM)))
        val button = compose.onNodeWithTag("collection.download")
        button.assertContentDescriptionEquals("Download")

        button.performClick()
        compose.waitForIdle()
        val ids = backend.started.toList()
        assertEquals(api.catalog.tracksByAlbum.getValue("MPREb_harborlights").count { it.isAvailable }, ids.size)
        button.assertContentDescriptionEquals("Downloading")
        button.assert(stateDescription("0 of ${ids.size}"))

        backend.send(DownloadEvent.Finished(ids[0], 5_000_000, null))
        compose.waitForIdle()
        button.assert(stateDescription("1 of ${ids.size}"))

        ids.drop(1).dropLast(1).forEach { backend.send(DownloadEvent.Finished(it, 5_000_000, null)) }
        backend.send(DownloadEvent.Failed(ids.last(), "No connection"))
        compose.waitForIdle()
        button.assertContentDescriptionEquals("Partly downloaded")

        // A tap asks first (the dialog comes from the button); retrying sets the failed song going again.
        button.performClick()
        compose.onNodeWithTag("collection.download.dialog").assertExists()
        compose.onNodeWithText("Try the missing songs again").performClick()
        backend.send(DownloadEvent.Finished(ids.last(), 5_000_000, null))
        compose.waitForIdle()
        button.assertContentDescriptionEquals("Downloaded")
        button.assert(stateDescription("${ids.size} songs"))

        // Removing it from the dialog brings the first look back.
        button.performClick()
        compose.onNodeWithText("Remove download").performClick()
        compose.waitForIdle()
        button.assertContentDescriptionEquals("Download")
    }

    @Test
    fun theLastRowScrollsFullyAboveThePlayerBar() {
        val api = PreviewMusicAPI(delays = false)
        val services = services(api)
        services.player.play(api.catalog.songs[0])
        // An artist's top songs: one list of rows and nothing after them.
        showApp(services, BrowseStart.Page(BrowseEndpoint("PLbb_topUCbbjunipervale", pageType = BrowseEndpoint.PageType.PLAYLIST)))
        val list = compose.onNodeWithTag("playlist.list")
        repeat(4) { list.performTouchInput { swipeUp() } }
        compose.waitForIdle()

        val bar = compose.onNodeWithTag("shell.playerBar").getBoundsInRoot()
        val lastRow = compose.onAllNodesWithTag("row.song").fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
        val density = compose.density
        val rowBottom = with(density) { lastRow.boundsInRoot.bottom.toDp() }
        assertEquals(BoomMetrics.miniPlayerHeight, bar.bottom - bar.top)
        assertTrue("the last row ($rowBottom) ends above the player bar (${bar.top})", rowBottom <= bar.top + 0.5.dp)
        val listBottom = list.getBoundsInRoot().bottom
        assertTrue("the list ($listBottom) ends where the bars begin (${bar.top})", listBottom <= bar.top + 0.5.dp)
    }
}

/** The search pause, through the field itself: nothing is asked before 180 ms of quiet, the answer shows after. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SearchFieldTests : ScreenTest() {
    override val dispatcher: TestDispatcher by lazy { StandardTestDispatcher(scheduler) }

    @Test
    fun suggestionsWaitForAPauseInTyping() {
        val api = PreviewMusicAPI(delays = false)
        showApp(services(api, signedIn = false), BrowseStart.Search(null))
        advance(0)
        val field = compose.onNodeWithTag("search.field")
        field.performTextReplacement("l")
        advance(100)
        field.performTextReplacement("lo")
        advance(179)
        assertEquals(0, api.log.count("suggestions:"))
        compose.onAllNodesWithTag("search.suggestion").assertCountEquals(0)

        advance(1)
        assertEquals(listOf("suggestions:lo"), api.log.all.filter { it.startsWith("suggestions:") })
        assertTrue(compose.onAllNodesWithTag("search.suggestion").fetchSemanticsNodes().isNotEmpty())
    }
}
