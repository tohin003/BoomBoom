package io.github.boomboom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Songs of the catalogue that can be played (some are greyed out). */
private fun PreviewMusicAPI.playable(count: Int, from: Int = 0) = catalog.songs.filter { it.isAvailable }.drop(from).take(count)

/** A queue as a real one might look: kept songs of different sizes, one arriving, one waiting, one failed. */
private val mixed: List<(String) -> DownloadEvent?> = listOf(
    { id -> DownloadEvent.Progress(id, 0.42) },
    { null },
    { id -> DownloadEvent.Failed(id, "No connection") },
    { id -> DownloadEvent.Finished(id, 8_420_000, "Opus 256 kbps") },
    { id -> DownloadEvent.Finished(id, 5_130_000, "Opus 141 kbps") },
    { id -> DownloadEvent.Finished(id, 6_870_000, "Opus 256 kbps") },
    { id -> DownloadEvent.Finished(id, 4_200_000, "AAC 128 kbps") },
    { id -> DownloadEvent.Finished(id, 9_960_000, "Opus 256 kbps") },
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class DownloadsScreenshots : PlacesTest() {
    private fun mixedServices(api: PreviewMusicAPI, backend: FakeDownloadBackend): AppServices {
        val services = build(api, downloads = backend)
        backend.stage(services, api.playable(8), mixed)
        // A whole album, all in.
        downloadAlbum(services, api, "MPREb_harborlights")
        backend.started.filter { services.downloads.stateOf(it) == DownloadItem.State.Waiting }
            .drop(1).forEach { backend.send(DownloadEvent.Finished(it, 7_300_000, "Opus 256 kbps")) }
        return services
    }

    @Test
    fun mixedStates() {
        val api = PreviewMusicAPI(delays = false)
        showApp(mixedServices(api, FakeDownloadBackend()), BrowseStart.Downloads)
        shot("downloads_mixed")
        compose.onNodeWithTag("downloads.list").performScrollToNode(hasTestTag("downloads.removeAll"))
        shot("downloads_mixed_end")
    }

    @Test
    fun collections() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeDownloadBackend()
        val services = mixedServices(api, backend)
        // A second album, still arriving.
        val second = api.catalog.albums.first { it.title == "Low Orbit" }.id
        downloadAlbum(services, api, second)
        services.downloads.songsOf(second).take(3).forEach { backend.send(DownloadEvent.Finished(it.id, 6_900_000, "Opus 256 kbps")) }
        showApp(services, BrowseStart.Downloads)
        compose.onNode(hasTestTag("chip") and hasText("Albums & playlists")).performClick()
        shot("downloads_collections")
    }

    @Test
    fun empty() {
        showApp(build(), BrowseStart.Downloads)
        shot("downloads_empty")
    }

    @Test
    fun selecting() {
        val api = PreviewMusicAPI(delays = false)
        showApp(mixedServices(api, FakeDownloadBackend()), BrowseStart.Downloads)
        compose.onNodeWithTag("downloads.select").performClick()
        val rows = compose.onAllNodesWithTag("downloads.row")
        rows[1].performClick()
        rows[2].performClick()
        shot("downloads_selecting")
        compose.onNodeWithTag("downloads.delete").performClick()
        shot("downloads_delete_question")
    }

    @Test
    fun midSwipe() {
        val api = PreviewMusicAPI(delays = false)
        showApp(mixedServices(api, FakeDownloadBackend()), BrowseStart.Downloads)
        compose.onAllNodesWithTag("downloads.swipe")[4].performTouchInput {
            down(Offset(width * 0.85f, centerY))
            repeat(6) { moveBy(Offset(-width * 0.05f, 0f)) }
        }
        shot("downloads_mid_swipe")
        compose.onAllNodesWithTag("downloads.swipe")[4].performTouchInput { up() }
    }

    @Test
    fun waitingForWiFi() {
        val api = PreviewMusicAPI(delays = false)
        val backend = HoldingDownloadBackend()
        backend.isOnMobileData = true
        val services = build(api, downloads = backend)
        api.playable(4).forEach { services.downloads.download(it) }
        backend.send(DownloadEvent.Finished(api.playable(4)[0].videoId, 6_100_000, "Opus 256 kbps"))
        showApp(services, BrowseStart.Downloads)
        shot("downloads_waiting_for_wifi")
        compose.onNodeWithTag("downloads.mobileData").performClick()
        shot("downloads_mobile_data_question")
    }

    @Test
    fun sortMenu() {
        val api = PreviewMusicAPI(delays = false)
        showApp(mixedServices(api, FakeDownloadBackend()), BrowseStart.Downloads)
        compose.onNodeWithTag("downloads.sort").performClick()
        shot("downloads_sort_menu")
    }

    @Test
    fun downloadedAlbum() {
        val api = PreviewMusicAPI(delays = false)
        val services = build(api)
        downloadAlbum(services, api, "MPREb_harborlights")
        showApp(services, BrowseStart.Downloads)
        compose.runOnIdle { controller.router!!.push(BrowseRoute.DownloadedCollection("MPREb_harborlights")) }
        shot("downloaded_album")
        compose.onNodeWithTag("downloadedCollection.list").performScrollToNode(hasTestTag("downloads.removeCollectionButton"))
        compose.onNodeWithTag("downloads.removeCollectionButton").performClick()
        shot("downloaded_album_remove_question")
    }

    @Test
    fun fromLibraryWithAPlayerBar() {
        val api = PreviewMusicAPI(delays = false)
        val services = mixedServices(api, FakeDownloadBackend())
        services.player.play(api.playable(1, from = 30)[0])
        showApp(services, BrowseStart.Tab(BrowseTab.LIBRARY))
        compose.onNodeWithTag("library.downloads").performClick()
        compose.onNodeWithTag("downloads.list").performScrollToNode(hasTestTag("downloads.removeAll"))
        shot("downloads_end_above_player_bar")
    }
}

/** The Downloads screen under a finger: real pointer input on the rows, checked against the store. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class DownloadsBehaviourTests : PlacesTest() {
    private lateinit var services: AppServices
    private val ids get() = services.downloads.items.map { it.id }

    /** [count] songs, all on the phone (6.4 MB each, as previews keep them). */
    private fun open(count: Int = 6) {
        val api = PreviewMusicAPI(delays = false)
        services = build(api)
        api.playable(count).reversed().forEach { services.downloads.download(it) }
        showApp(services, BrowseStart.Downloads)
    }

    private fun swipe(row: Int, fraction: Float, steps: Int = 10) {
        compose.onAllNodesWithTag("downloads.swipe")[row].performTouchInput {
            down(Offset(width * 0.9f, centerY))
            repeat(steps) { moveBy(Offset(-width * fraction / steps, 0f)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun swipingARowRemovesExactlyThatOne() {
        open()
        val before = ids
        swipe(2, 0.7f)
        assertEquals(before - before[2], ids)
        assertNull("the swipe did not play the row", services.player.state.value.current)
    }

    @Test
    fun aShortSwipeSpringsBack() {
        open()
        val before = ids
        val left = compose.onAllNodesWithTag("downloads.row")[2].getBoundsInRoot().left
        swipe(2, 0.12f, steps = 4)
        assertEquals(before, ids)
        assertEquals(left, compose.onAllNodesWithTag("downloads.row")[2].getBoundsInRoot().left)
        assertNull("the swipe did not play the row", services.player.state.value.current)
    }

    @Test
    fun aVerticalDragOverTheRowsScrollsAndRemovesNothing() {
        open(count = 20)
        val before = ids
        val top = compose.onNodeWithTag("downloads.count").fetchSemanticsNode().boundsInRoot.top
        compose.onAllNodesWithTag("downloads.swipe")[1].performTouchInput {
            down(Offset(width * 0.5f, centerY))
            // Mostly upward, a little to the left: a scroll, not a removal.
            repeat(10) { moveBy(Offset(-3f, -40f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(before, ids)
        val moved = compose.onAllNodesWithTag("downloads.count").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        assertTrue("the list scrolled ($top -> $moved)", moved == null || moved < top - 100f)
        assertNull("the drag did not play a row", services.player.state.value.current)
    }

    @Test
    fun selectModePicksRowsAndDeleteAsksFirstThenRemovesExactlyThose() {
        open()
        val before = ids
        compose.onNodeWithTag("downloads.select").performClick()
        val rows = compose.onAllNodesWithTag("downloads.row")
        rows[1].performClick()
        rows[3].performClick()
        assertNull("a tap in select mode does not play", services.player.state.value.current)
        compose.onNodeWithTag("downloads.selected").assertTextEquals("2 songs selected")
        compose.onNodeWithTag("downloads.frees").assertTextEquals("Frees ${DownloadSize.text(2 * 6_400_000L)}")

        // Delete asks; Cancel leaves everything as it was, still selecting.
        compose.onNodeWithTag("downloads.delete").performClick()
        compose.onNodeWithTag("downloads.deleteQuestion").assertIsDisplayed()
        assertEquals(before, ids)
        compose.onNodeWithTag("downloads.deleteQuestion.cancel").performClick()
        compose.onNodeWithTag("downloads.deleteQuestion").assertDoesNotExist()
        assertEquals(before, ids)
        compose.onNodeWithTag("downloads.selected").assertTextEquals("2 songs selected")

        // Asked again and confirmed: exactly the two picked go.
        compose.onNodeWithTag("downloads.delete").performClick()
        compose.onNodeWithTag("downloads.deleteQuestion.confirm").performClick()
        compose.waitForIdle()
        assertEquals(before - setOf(before[1], before[3]), ids)
        compose.onNodeWithTag("downloads.selectionBar").assertDoesNotExist()
    }

    @Test
    fun selectAllPicksEveryRowAndAgainPicksNone() {
        open()
        compose.onNodeWithTag("downloads.select").performClick()
        compose.onNodeWithTag("downloads.selectAll").performClick()
        compose.onNodeWithTag("downloads.selected").assertTextEquals("6 songs selected")
        compose.onNodeWithTag("downloads.selectAll").performClick()
        compose.onNodeWithTag("downloads.selected").assertTextEquals("Pick what to remove")
        compose.onNodeWithTag("downloads.done").performClick()
        compose.onNodeWithTag("downloads.selectionBar").assertDoesNotExist()
        assertEquals(6, ids.size)
    }

    @Test
    fun aRowsOwnButtonRemovesThatSong() {
        open()
        val before = ids
        compose.onAllNodesWithTag("downloads.row.remove")[4].performClick()
        assertEquals(before - before[4], ids)
    }

    @Test
    fun retryOnAFailedSongReachesTheStore() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeDownloadBackend()
        services = build(api, downloads = backend)
        val song = api.playable(1)[0]
        services.downloads.download(song)
        backend.send(DownloadEvent.Failed(song.videoId, "No connection"))
        showApp(services, BrowseStart.Downloads)
        assertEquals(1, backend.started.count { it == song.videoId })

        compose.onNodeWithTag("downloads.row.retry").performClick()
        assertEquals(DownloadItem.State.Waiting, services.downloads.stateOf(song.videoId))
        assertEquals("asked of the backend again", 2, backend.started.count { it == song.videoId })
        compose.onNodeWithTag("downloads.row.retry").assertDoesNotExist()
    }

    @Test
    fun theSpaceUsedIsTheSumOfWhatIsDownloadedAndFollowsADelete() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeDownloadBackend()
        services = build(api, downloads = backend)
        val sizes = listOf(3_000_000L, 4_560_000L, 7_250_000L)
        val songs = api.playable(4)
        backend.stage(
            services, songs,
            sizes.map { size -> { id: String -> DownloadEvent.Finished(id, size, "Opus 256 kbps") } } + listOf({ id -> DownloadEvent.Progress(id, 0.5) }),
        )
        showApp(services, BrowseStart.Downloads)
        fun space() = compose.onNodeWithTag("downloads.space").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        assertEquals("${DownloadSize.text(sizes.sum())} on this phone", space())
        assertEquals(sizes.sum(), services.downloads.totalBytes)

        compose.onAllNodesWithTag("downloads.row.remove")[1].performClick()
        assertEquals("${DownloadSize.text(sizes.sum() - sizes[1])} on this phone", space())
    }

    @Test
    fun swipingAnAlbumAwayAsksFirst() {
        val api = PreviewMusicAPI(delays = false)
        services = build(api)
        downloadAlbum(services, api, "MPREb_harborlights")
        showApp(services, BrowseStart.Downloads)
        compose.onNode(hasTestTag("chip") and hasText("Albums & playlists")).performClick()
        compose.onNodeWithTag("downloads.collection").performTouchInput {
            down(Offset(width * 0.9f, centerY))
            repeat(10) { moveBy(Offset(-width * 0.07f, 0f)) }
            up()
        }
        compose.onNodeWithTag("downloads.removeCollection").assertIsDisplayed()
        assertEquals(1, services.downloads.collections.size)
        compose.onNodeWithTag("downloads.removeCollection.confirm").performClick()
        assertEquals(0, services.downloads.collections.size)
        assertEquals(0, services.downloads.items.size)
    }

    @Test
    fun aTapPlaysTheDownloadsFromThatSong() {
        open()
        compose.onAllNodesWithTag("downloads.row")[2].performClick()
        val player = services.player.state.value
        assertEquals(ids[2], player.current?.videoId)
        assertEquals(6, player.queue.size)
        assertEquals("Downloads", player.source?.title)
    }
}
