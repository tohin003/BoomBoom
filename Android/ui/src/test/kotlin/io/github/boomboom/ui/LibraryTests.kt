package io.github.boomboom.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import io.github.boomboom.logic.AppServices
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

/** The canned data with nothing in the library. */
private class EmptyLibrary(private val base: PreviewMusicAPI) : MusicAPI by base {
    override suspend fun library(section: LibrarySection): BrowsePage = BrowsePage(title = section.title)
}

private val library = BrowseStart.Tab(BrowseTab.LIBRARY)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class LibraryScreenshots : PlacesTest() {
    private fun chip(title: String) = compose.onNode(hasTestTag("chip") and hasText(title)).performClick()

    @Test
    fun signedOut() {
        val api = PreviewMusicAPI(delays = false, signedIn = false)
        val services = build(api, signedIn = false)
        services.downloads.download(api.catalog.songs[2])
        showApp(services, library)
        shot("library_signed_out")
    }

    @Test
    fun loading() {
        showApp(build(PreviewMusicAPI(delayOverride = { _, _ -> 10.minutes })), library)
        shot("library_loading")
    }

    @Test
    fun sections() {
        showApp(build(), library)
        shot("library_playlists")
        chip("Songs")
        shot("library_songs")
        chip("Albums")
        shot("library_albums")
        chip("Artists")
        shot("library_artists")
        chip("Subscriptions")
        shot("library_subscriptions")
    }

    @Test
    fun grid() {
        showApp(build(), library)
        chip("Albums")
        compose.onNodeWithTag("library.layout").performClick()
        shot("library_albums_grid")
        chip("Playlists")
        shot("library_playlists_grid")
    }

    @Test
    fun empty() {
        val api = PreviewMusicAPI(delays = false)
        val services = build(api)
        showApp(AppServices(EmptyLibrary(api), services.player, services.equalizer, services.session, services.cache, services.downloads), library)
        shot("library_empty_playlists")
        chip("Albums")
        shot("library_empty_albums")
    }

    @Test
    fun couldNotLoad() {
        val api = PreviewMusicAPI(delays = false, failsBrowsing = true)
        val services = build(api)
        services.downloads.download(api.catalog.songs[2])
        showApp(services, library)
        shot("library_could_not_load")
    }

    @Test
    fun newPlaylist() {
        showApp(build(), library)
        compose.onNodeWithTag("library.newPlaylist").performClick()
        shot("library_new_playlist_empty")
        compose.onNodeWithTag("newPlaylist.name").performTextInput("Road trip")
        shot("library_new_playlist")
    }

    @Test
    fun ownPlaylistMenu() {
        showApp(build(), library)
        val own = compose.onAllNodesWithTag("row.more").fetchSemanticsNodes().size
        assertTrue(own > 3)
        // The third playlist row is "Late Night Drive", one of the listener's own (its id starts with PL).
        compose.onAllNodesWithTag("row.more")[2].performClick()
        compose.onNodeWithTag("menu").performScrollToNode(hasTestTag("menu.delete"))
        shot("library_own_playlist_menu")
        compose.onNodeWithTag("menu.delete").performClick()
        compose.waitForIdle()
        shot("library_delete_question")
    }

    @Test
    fun history() {
        showApp(build(), library)
        compose.onNodeWithTag("library.history").performClick()
        shot("history")
    }

    @Test
    fun historySignedOut() {
        showApp(build(PreviewMusicAPI(delays = false, signedIn = false), signedIn = false), library)
        compose.onNodeWithTag("library.history").performClick()
        shot("history_signed_out")
    }
}

/** Records what the screens ask of the keyboard. */
private class RecordingKeyboard : SoftwareKeyboardController {
    val calls = ArrayList<String>()
    override fun show() {
        calls.add("show")
    }

    override fun hide() {
        calls.add("hide")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class LibraryBehaviourTests : PlacesTest() {
    private val router get() = controller.router!!

    @Test
    fun signedOutTheLibraryOffersSignInAndStillReachesDownloads() {
        val api = PreviewMusicAPI(delays = false, signedIn = false)
        val signIns = ArrayList<String?>()
        showApp(build(api, signedIn = false), library, onSignIn = { signIns.add(it) })
        assertEquals("nothing personal is asked for", 0, api.log.count("library:"))
        compose.onNodeWithTag("signedOut.signIn").performClick()
        assertEquals(listOf<String?>(null), signIns)
        compose.onNodeWithTag("library.downloads").performClick()
        assertEquals(listOf(BrowseRoute.Downloads), router.path(BrowseTab.LIBRARY))
    }

    @Test
    fun theLibraryReloadsWhenTheAccountChanges() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeSessionBackend(count = 2)
        showApp(build(api, session = sessionOver(backend, api)), library)
        assertEquals(1, api.log.count("library:playlists"))

        // Another account becomes the active one (a switch made elsewhere): its own library is loaded.
        compose.runOnUiThread { backend.change { list = list.map { it.copy(isActive = it.id == "profile-2") } } }
        compose.waitForIdle()
        assertEquals(2, api.log.count("library:playlists"))

        // Signed out entirely: the invitation, and nothing asked for.
        compose.runOnUiThread { backend.change { list = list.map { it.copy(isSignedIn = false) } } }
        compose.waitForIdle()
        compose.onNodeWithTag("signedOut").assertIsDisplayed()
        assertEquals(2, api.log.count("library:"))
    }

    @Test
    fun sectionsKeepTheirOwnStateAndLoadOnce() {
        val api = PreviewMusicAPI(delays = false)
        showApp(build(api), library)
        compose.onNode(hasTestTag("chip") and hasText("Songs")).performClick()
        compose.onNode(hasTestTag("chip") and hasText("Playlists")).performClick()
        compose.onNode(hasTestTag("chip") and hasText("Songs")).performClick()
        assertEquals(1, api.log.count("library:playlists"))
        assertEquals(1, api.log.count("library:songs"))
    }

    @Test
    fun likedMusicOpensItsPlaylist() {
        showApp(build(), library)
        compose.onNodeWithTag("library.liked").performClick()
        assertEquals(
            listOf(BrowseRoute.Page(BrowseEndpoint("LM", pageType = BrowseEndpoint.PageType.PLAYLIST))),
            router.path(BrowseTab.LIBRARY),
        )
    }

    @Test
    fun deletingAPlaylistAsksFirstAndTakesItOut() {
        val api = PreviewMusicAPI(delays = false)
        showApp(build(api), library)
        val before = compose.onAllNodesWithTag("row.playlist").fetchSemanticsNodes().size
        compose.onAllNodesWithTag("row.more")[2].performClick()
        compose.onNodeWithTag("menu").performScrollToNode(hasTestTag("menu.delete"))
        compose.onNodeWithTag("menu.delete").performClick()
        compose.onNodeWithTag("library.delete").assertIsDisplayed()
        assertEquals(0, api.log.count("deletePlaylist:"))
        compose.onNodeWithTag("library.delete.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, api.log.count("deletePlaylist:"))
        assertEquals(before - 1, compose.onAllNodesWithTag("row.playlist").fetchSemanticsNodes().size)
    }

    /** The app as [showApp] puts it on screen, with a keyboard that records what it is asked. */
    private fun showWithKeyboard(services: AppServices, keyboard: RecordingKeyboard) {
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                BoomApp(
                    services, playerBar = { StandInPlayerBar(services) }, onOpenEqualizer = {}, onSignIn = {},
                    start = library, controller = controller, workContext = dispatcher,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun makingAPlaylistWithANameCreatesItClosesTheKeyboardAndOpensIt() {
        val api = PreviewMusicAPI(delays = false)
        val keyboard = RecordingKeyboard()
        showWithKeyboard(build(api), keyboard)
        compose.onNodeWithTag("library.newPlaylist").performClick()
        // Nothing to create without a name.
        compose.onNodeWithTag("newPlaylist.create").assertIsNotEnabled()

        compose.onNodeWithTag("newPlaylist.name").performTextInput("Road trip")
        keyboard.calls.clear()
        compose.onNodeWithTag("newPlaylist.create").performClick()
        compose.waitForIdle()
        assertEquals(listOf("createPlaylist:Road trip"), api.log.all.filter { it.startsWith("createPlaylist:") })
        assertEquals("the keyboard was told to go", "hide", keyboard.calls.firstOrNull())
        assertTrue("and not brought back", "show" !in keyboard.calls)
        compose.onNodeWithTag("newPlaylist").assertDoesNotExist()
        assertEquals(
            listOf(BrowseRoute.Page(BrowseEndpoint("PLbb_newroadtrip", pageType = BrowseEndpoint.PageType.PLAYLIST))),
            router.path(BrowseTab.LIBRARY),
        )
        // The playlists were asked for again, so the new one shows on the way back.
        assertEquals(2, api.log.count("library:playlists"))
    }

    @Test
    fun theKeyboardsOwnKeyCreatesThePlaylistToo() {
        val api = PreviewMusicAPI(delays = false)
        val keyboard = RecordingKeyboard()
        showWithKeyboard(build(api), keyboard)
        compose.onNodeWithTag("library.newPlaylist").performClick()
        compose.onNodeWithTag("newPlaylist.name").performTextInput("Late swims")
        keyboard.calls.clear()
        compose.onNodeWithTag("newPlaylist.name").performImeAction()
        compose.waitForIdle()
        assertEquals(1, api.log.count("createPlaylist:Late swims"))
        assertEquals("hide", keyboard.calls.firstOrNull())
        compose.onNodeWithTag("newPlaylist").assertDoesNotExist()
    }

    @Test
    fun cancellingANewPlaylistMakesNothing() {
        val api = PreviewMusicAPI(delays = false)
        showApp(build(api), library)
        compose.onNodeWithTag("library.newPlaylist").performClick()
        compose.onNodeWithTag("newPlaylist.name").performTextInput("Not this one")
        compose.onNodeWithTag("newPlaylist.cancel").performClick()
        compose.onNodeWithTag("newPlaylist").assertDoesNotExist()
        assertEquals(0, api.log.count("createPlaylist:"))
    }

    @Test
    fun historyOpensFromTheBarAndShowsItsDays() {
        showApp(build(), library)
        compose.onNodeWithTag("library.history").performClick()
        assertEquals(listOf(BrowseRoute.History), router.path(BrowseTab.LIBRARY))
        compose.onAllNodesWithTag("row.song").onFirst().assertIsDisplayed()
        compose.onNode(hasText("Today")).assertIsDisplayed()
    }
}
