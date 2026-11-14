package io.github.boomboom.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The account button at the top right, signed out: Settings must be within reach of a listener who never signs in
 * (the sound, the cache, the downloads and "Report a problem" need no account).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class AccountMenuTests : ScreenTest() {
    private fun signedOut(signIns: MutableList<String?>) {
        showApp(services(PreviewMusicAPI(delays = false, signedIn = false), signedIn = false), onSignIn = { signIns.add(it) })
    }

    @Test
    fun signedOutTheMenuOffersSignInAndSettings() {
        val signIns = ArrayList<String?>()
        signedOut(signIns)
        compose.onNodeWithTag("topbar.account").performClick()
        compose.onNodeWithTag("account.signIn").assertIsDisplayed()
        compose.onNodeWithTag("account.settings").assertIsDisplayed()
        // "Another" account makes no sense before the first.
        compose.onNodeWithTag("account.add").assertDoesNotExist()
        compose.onNodeWithTag("account.entry").assertDoesNotExist()
        // Opening the menu signs nobody in.
        assertEquals(emptyList<String?>(), signIns)
    }

    @Test
    fun signedOutSettingsOpensFromTheMenu() {
        signedOut(ArrayList())
        compose.onNodeWithTag("topbar.account").performClick()
        compose.onNodeWithTag("account.settings").performClick()
        compose.waitForIdle()
        assertTrue(controller.router!!.current.route == BrowseRoute.Settings)
    }

    @Test
    fun signedOutSignInGoesThroughTheNavigator() {
        val signIns = ArrayList<String?>()
        signedOut(signIns)
        compose.onNodeWithTag("topbar.account").performClick()
        compose.onNodeWithTag("account.signIn").performClick()
        // Null: the account the tab is on, not a new profile.
        assertEquals(listOf<String?>(null), signIns)
    }
}
