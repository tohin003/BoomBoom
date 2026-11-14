package io.github.boomboom.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.boomboom.ui.design.BoomTheme
import io.github.boomboom.ui.design.ErrorView
import io.github.boomboom.ui.design.LocalNetworkReturns
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A page that could not load tries again by itself when the phone's connection comes back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ErrorViewTests : ScreenTest() {
    @Test
    fun aFailedPageTriesAgainOnceWhenTheConnectionReturns() {
        var returns by mutableIntStateOf(3)
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalNetworkReturns provides returns) {
                BoomTheme { ErrorView(retry = { retries += 1 }) }
            }
        }
        // Nothing has changed since the page failed: it waits for the listener.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(0, retries)

        compose.runOnUiThread { returns = 4 }
        compose.waitForIdle()
        // Not at once: a network that has just come up needs a moment.
        assertEquals(0, retries)
        compose.waitUntil(timeoutMillis = 10_000) { retries == 1 }
        // And once only.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(1, retries)

        // The button still works by hand.
        compose.onNodeWithTag("error.retry").performClick()
        assertEquals(2, retries)
    }
}
