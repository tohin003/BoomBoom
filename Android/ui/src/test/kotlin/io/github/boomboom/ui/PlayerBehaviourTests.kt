package io.github.boomboom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.Clock
import io.github.boomboom.logic.PlaybackEngine
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.SimulatedEngine
import io.github.boomboom.logic.SleepTimer
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.boomTime
import io.github.boomboom.ui.player.activeLine
import io.github.boomboom.ui.preview.PreviewMusicAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * An engine that, like the real one, takes a moment to move after a seek: for [lagMillis] it still reports the old
 * place (and plays on from it), then jumps to where it was asked.
 */
class LaggySeekEngine(private val inner: SimulatedEngine, private val time: Clock, private val lagMillis: Long = 600) :
    PlaybackEngine by inner {
    private var pending: Pair<Double, Long>? = null
    private var before = 0.0

    override fun seek(seconds: Double) {
        before = inner.position
        pending = seconds to time.nowMillis()
    }

    override val position: Double
        get() {
            val (target, at) = pending ?: return inner.position
            val waited = time.nowMillis() - at
            if (waited < lagMillis) return before + waited / 1000.0
            pending = null
            inner.seek(target)
            return inner.position
        }
}

/** The mini and full player under a finger, checked against [io.github.boomboom.logic.PlayerStore]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class PlayerBehaviourTests : PlayerTest() {
    private val api = PreviewMusicAPI(delays = false)
    private val album: List<Song> get() = api.catalog.tracksByAlbum.values.first { it.size >= 8 }
    private lateinit var services: AppServices
    private val player get() = services.player

    private fun start(startAt: Int = 2, wrap: ((SimulatedEngine) -> PlaybackEngine)? = null) {
        services = if (wrap != null) playerServices(api, wrap) else playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt)
    }

    /** Every song the player made current, in order, from now on. */
    private fun watchSongs(): List<UUID?> {
        val seen = ArrayList<UUID?>()
        CoroutineScope(dispatcher).launch {
            player.state.collect { if (seen.lastOrNull() != it.currentEntryId || seen.isEmpty()) seen += it.currentEntryId }
        }
        return seen
    }

    @Test
    fun theFullPlayersControlsReachThePlayer() {
        start()
        expand()
        compose.onNodeWithTag("player.playPause").performClick()
        assertEquals(PlaybackStatus.Paused, player.status)
        compose.onNodeWithTag("player.playPause").performClick()
        assertEquals(PlaybackStatus.Playing, player.status)
        compose.onNodeWithTag("player.next").performClick()
        assertEquals(3, player.currentIndex)
        advance(400)
        compose.onNodeWithTag("player.previous").performClick()
        assertEquals(2, player.currentIndex)
        compose.onNodeWithTag("player.shuffle").performClick()
        assertTrue(player.isShuffled)
        compose.onNodeWithTag("player.repeat").performClick()
        assertEquals(io.github.boomboom.logic.RepeatMode.ALL, player.repeatMode)
        compose.onNodeWithTag("player.like").performClick()
        assertEquals(LikeStatus.LIKE, player.likeStatus)
    }

    @Test
    fun theMiniPlayersControlsReachThePlayer() {
        start()
        compose.onNodeWithTag("miniplayer.playPause").performClick()
        assertEquals(PlaybackStatus.Paused, player.status)
        compose.onNodeWithTag("miniplayer.next").performClick()
        assertEquals(3, player.currentIndex)
    }

    @Test
    fun theMiniPlayerOpensOnATapAndOnASwipeUpAndSkipsOnASidewaysSwipe() {
        start()
        compose.onNodeWithTag("miniplayer.song").performClick()
        assertTrue(isExpanded.value)
        compose.onNodeWithTag("player.close").performClick()
        assertFalse(isExpanded.value)
        compose.onNodeWithTag("player").assertDoesNotExist()

        compose.onNodeWithTag("miniplayer.song").performTouchInput { swipeUp() }
        assertTrue(isExpanded.value)
        compose.onNodeWithTag("player").assertExists()
        compose.onNodeWithTag("player.close").performClick()

        val songs = watchSongs()
        compose.onNodeWithTag("miniplayer.song").performTouchInput { swipeLeft() }
        assertEquals(3, player.currentIndex)
        assertEquals("one song change", 2, songs.size)
        assertFalse("a swipe is not a tap", isExpanded.value)
    }

    @Test
    fun theElapsedTimeFollowsTheClock() {
        start()
        expand()
        advance(5_000)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals("0:05")
        advance(60_000)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals("1:05")
        // A tap on the length shows the time left instead.
        compose.onNodeWithTag("scrubber.length").assert(hasText(boomTime(player.clock.duration)))
        compose.onNodeWithTag("scrubber.length").performClick()
        compose.onNodeWithTag("scrubber.length").assert(hasText("-" + boomTime(player.clock.remaining)))
    }

    @Test
    fun theScrubberDoesNotFlickBackAfterASeek() {
        start(wrap = { LaggySeekEngine(it, time) })
        expand()
        val duration = player.clock.duration
        val target = 0.75 * duration
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("scrubber").performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveTo(Offset(width * 0.75f, centerY))
            up()
        }
        val expected = boomTime(target)
        fun step(millis: Long) {
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
            compose.mainClock.advanceTimeBy(millis)
        }
        step(16)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals(expected)
        // The engine still reports the old place for a while; the thumb holds where it was let go.
        step(150)
        assertTrue("the engine lags (${player.clock.position})", player.clock.position < 5)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals(expected)
        step(300)
        assertTrue(player.clock.position < 5)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals(expected)
        // The engine gets there: from now on the thumb follows the clock again, from the new place.
        step(400)
        assertTrue(player.clock.position > target - 1)
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals(expected)
        repeat(30) { step(100) }
        compose.onNodeWithTag("scrubber.elapsed").assertTextEquals(boomTime(player.clock.position))
        assertTrue(player.clock.position > target + 2)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun aSwipeOnTheCoverChangesSongOnceEachWay() {
        start()
        expand()
        val songs = watchSongs()
        val first = player.currentEntryId
        compose.onNodeWithTag("player.cover").performTouchInput { swipeLeft() }
        advance(2_000)
        assertEquals(3, player.currentIndex)
        assertEquals("exactly one change", listOf(first, player.queue[3].id), songs)

        compose.onNodeWithTag("player.cover").performTouchInput { swipeRight() }
        advance(2_000)
        assertEquals(2, player.currentIndex)
        assertEquals(3, songs.size)
    }

    @Test
    fun aShortSidewaysDragOnTheCoverSpringsBack() {
        start()
        expand()
        compose.onNodeWithTag("player.cover").performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(-12f, 0f)) }
            up()
        }
        advance(1_000)
        assertEquals(2, player.currentIndex)
    }

    @Test
    fun draggingThePlayerDownClosesItAndASmallDragSpringsBack() {
        start()
        expand()
        val top = compose.onNodeWithTag("player").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithTag("player.title").performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(0f, 30f)) }
            up()
        }
        assertTrue("a small drag leaves it open", isExpanded.value)
        assertEquals(top, compose.onNodeWithTag("player").fetchSemanticsNode().positionInRoot.y)

        compose.onNodeWithTag("player.title").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 60f)) }
            up()
        }
        assertFalse(isExpanded.value)
        compose.onNodeWithTag("player").assertDoesNotExist()
    }

    @Test
    fun aDownwardDragStartingOnTheCoverMovesThePlayerNotTheSong() {
        start()
        expand()
        compose.onNodeWithTag("player.cover").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(2f, 60f)) }
            up()
        }
        assertFalse(isExpanded.value)
        assertEquals(2, player.currentIndex)
    }

    @Test
    fun aDragOnTheScrubberMovesTheSongNotThePlayer() {
        start()
        expand()
        compose.onNodeWithTag("scrubber").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            repeat(6) { moveBy(Offset(10f, 50f)) }
            up()
        }
        assertTrue(isExpanded.value)
        compose.onNodeWithTag("player").assertExists()
        assertTrue(player.clock.position > 1)
    }

    @Test
    fun theChevronAndBackClose() {
        start()
        expand()
        compose.onNodeWithTag("player.close").performClick()
        assertFalse(isExpanded.value)

        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
        compose.onNodeWithTag("panel").assertExists()
        // Back closes the panel first, then the player.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("panel").assertDoesNotExist()
        assertTrue(isExpanded.value)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(isExpanded.value)
    }

    @Test
    fun draggingAQueueEntryByItsHandleMovesExactlyThatEntryOnePlace() {
        start()
        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
        val before = player.queue.map { it.id }
        val handle = compose.onAllNodesWithTag("queue.handle")[4]
        val rowHeight = handle.fetchSemanticsNode().size.height + with(compose.density) { 12f * density }
        handle.performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, rowHeight / 6)) }
            up()
        }
        val expected = before.toMutableList().apply { add(5, removeAt(4)) }
        assertEquals(expected, player.queue.map { it.id })
        assertEquals("the playing entry is still playing", before[2], player.currentEntryId)
    }

    @Test
    fun aSwipeRemovesOneEntryButNeverThePlayingOne() {
        start()
        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
        val before = player.queue.map { it.id }

        compose.onNodeWithTag("queue.row.current").performTouchInput { swipeLeft() }
        assertEquals("the playing entry stays", before, player.queue.map { it.id })

        // The rows not playing, in order: the two played ones, then the first one up next.
        compose.onAllNodesWithTag("queue.row")[2].performTouchInput { swipeLeft() }
        assertEquals(before - before[3], player.queue.map { it.id })
        assertEquals(before[2], player.currentEntryId)
    }

    @Test
    fun aTapOnAQueueEntryPlaysIt() {
        start()
        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
        compose.onAllNodesWithTag("queue.row")[4].performClick()
        assertEquals(5, player.currentIndex)
    }

    @Test
    fun timedLyricsFollowTheSong() {
        start()
        expand()
        compose.onNodeWithTag("player.tab.lyrics").performClick()
        advance(47_000)
        compose.onNodeWithTag("lyrics.line.active").assertTextEquals("Paper boats along the gutter stream")
        advance(5_000)
        compose.onNodeWithTag("lyrics.line.active").assertTextEquals("Half a summer stitched into a dream")
        // A tap on a line plays from there (this one starts at 45.6 s).
        compose.onNodeWithText("Paper boats along the gutter stream").performClick()
        assertEquals(45.6, player.clock.position, 0.3)
        compose.onNodeWithTag("lyrics.line.active").assertTextEquals("Paper boats along the gutter stream")
    }

    @Test
    fun activeLineIsTheLastThatHasStarted() {
        val lines = listOf(
            io.github.boomboom.models.Lyrics.Line("a", 1.0), io.github.boomboom.models.Lyrics.Line("b", 5.0),
            io.github.boomboom.models.Lyrics.Line("c", 9.0),
        )
        assertEquals(null, activeLine(0.5, lines))
        assertEquals(0, activeLine(4.0, lines))
        assertEquals(1, activeLine(4.9, lines))
        assertEquals(2, activeLine(100.0, lines))
    }

    @Test
    fun theSleepTimerIsSetFromItsChoices() {
        start()
        expand()
        compose.onNodeWithTag("player.sleepTimer").performClick()
        val now = scheduler.currentTime
        compose.onNodeWithTag("player.sleep.15").performClick()
        assertEquals(SleepTimer.At(now + 15 * 60_000L), player.sleepTimer)
        compose.onNodeWithTag("player.sleepTimer").performClick()
        compose.onNodeWithTag("player.sleep.off").performClick()
        assertEquals(null, player.sleepTimer)
    }

    @Test
    fun goToAlbumClosesThePlayerAndOpensTheAlbum() {
        start()
        expand()
        compose.onNodeWithTag("player.more").performClick()
        compose.onNodeWithTag("player.menu.album").performClick()
        assertEquals(BrowseEndpoint.PageType.ALBUM, opened.single().pageType)
        assertEquals(album[2].album?.id, opened.single().browseId)
        assertFalse(isExpanded.value)
        compose.onNodeWithTag("album.list").assertExists()
    }

    @Test
    fun theSoundButtonOpensTheSoundScreen() {
        start()
        expand()
        compose.onNodeWithTag("player.sound").performClick()
        assertTrue(isSoundShown.value)
        compose.onNodeWithTag("sound").assertExists()
    }
}
