package io.github.boomboom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.boomboom.logic.AppServices
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A queue longer than the screen: a row carried to the list's edge and held there scrolls the list on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class QueueDragTests : PlayerTest() {
    private val api = PreviewMusicAPI(delays = false)
    private lateinit var services: AppServices

    private fun open() {
        services = playerServices(api)
        showPlayerApp(services)
        playList(services, api.catalog.songs.filter { it.isAvailable }.take(40), startAt = 0)
        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
    }

    @Test
    fun aRowHeldAtTheBottomEdgeScrollsTheListAndLandsBeyondWhatShowed() {
        open()
        val before = services.player.queue.map { it.id }
        val shown = compose.onAllNodesWithTag("queue.handle").fetchSemanticsNodes()
        val handle = compose.onAllNodesWithTag("queue.handle")[1]
        val start = handle.fetchSemanticsNode().boundsInRoot.center.y
        val listBottom = compose.onNodeWithTag("queue.list").fetchSemanticsNode().boundsInRoot.bottom
        // Down on the second row's handle, then to just above the list's bottom edge, and held there.
        handle.performTouchInput {
            down(center)
            val distance = listBottom - start - 20f
            repeat(10) { moveBy(Offset(0f, distance / 10)) }
        }
        // Held still: the list scrolls on by itself until it can go no further.
        compose.waitForIdle()
        handle.performTouchInput { up() }
        compose.waitForIdle()

        val after = services.player.queue.map { it.id }
        val moved = before[1]
        val landed = after.indexOf(moved)
        println("queue edge drag: ${shown.size} rows showed, the row went from 1 to $landed of ${after.size}")
        assertTrue("it went past the ${shown.size} rows that showed (landed at $landed)", landed > shown.size)
        assertEquals("one entry moved, the rest kept their order", before - moved, after - moved)
        assertEquals("the playing entry is still playing", before[0], services.player.currentEntryId)
    }

    @Test
    fun aRowHeldInTheMiddleDoesNotScroll() {
        open()
        val before = services.player.queue.map { it.id }
        val handle = compose.onAllNodesWithTag("queue.handle")[1]
        val height = handle.fetchSemanticsNode().size.height.toFloat()
        handle.performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, height / 6)) }
        }
        compose.mainClock.advanceTimeBy(1000)
        handle.performTouchInput { up() }
        compose.waitForIdle()
        val landed = services.player.queue.map { it.id }.indexOf(before[1])
        assertTrue("a short drag moves it a place or two, no more (landed at $landed)", landed in 1..3)
    }
}
