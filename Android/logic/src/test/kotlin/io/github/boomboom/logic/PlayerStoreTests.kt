package io.github.boomboom.logic

import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.Song
import io.github.boomboom.models.SongsContinuation
import io.github.boomboom.models.WatchEndpoint
import io.github.boomboom.models.WatchQueue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A data source with just enough behaviour for the queue: every song's radio is five numbered followers. */
private class StubAPI : StubMusicAPI() {
    override suspend fun watchQueue(endpoint: WatchEndpoint): WatchQueue {
        val seed = endpoint.videoId ?: endpoint.playlistId ?: "x"
        val first = Song(videoId = seed, title = "Song $seed", duration = 200.0)
        val rest = (1..5).map { Song(videoId = "$seed-r$it", title = "Radio $it", duration = 180.0) }
        return WatchQueue(
            songs = listOf(first) + rest, currentIndex = 0, continuation = "more-$seed",
            lyricsBrowseId = "lyrics-$seed", relatedBrowseId = "related-$seed",
        )
    }

    override suspend fun watchQueueContinuation(token: String) = SongsContinuation(
        (1..3).map { Song(videoId = "$token-$it", title = "More $it", duration = 150.0) }, null,
    )

    override suspend fun lyrics(browseId: String) = Lyrics(lines = listOf(Lyrics.Line(text = browseId)))
}

private data class Rig(val store: PlayerStore, val engine: SimulatedEngine)

private fun TestScope.makeStore(api: StubAPI = StubAPI()): Rig {
    val time = virtualClock()
    val engine = SimulatedEngine(backgroundScope, time)
    engine.loadDelay = 5.milliseconds
    return Rig(PlayerStore(api, engine, InMemoryKeyValueStore(), backgroundScope, time), engine)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerStoreTests {
    @Test
    fun playingASongFillsTheQueueWithItsRadio() = runTest {
        val (store, _) = makeStore()
        store.play(Song(videoId = "abc", title = "Abc", duration = 200.0))
        assertEquals("abc", store.current?.videoId)
        assertEquals(PlaybackStatus.Loading, store.status)
        assertTrue(eventually { store.status == PlaybackStatus.Playing && store.queue.size == 6 })
        assertEquals(
            listOf("abc", "abc-r1", "abc-r2", "abc-r3", "abc-r4", "abc-r5"), store.queue.map { it.song.videoId },
        )
        assertEquals(QueueSource.Kind.RADIO, store.source?.kind)
        assertEquals("lyrics-abc", store.lyrics()?.lines?.first()?.text)
    }

    @Test
    fun playingAListStartsAtTheChosenSong() = runTest {
        val (store, _) = makeStore()
        store.play(songs(5), startAt = 2, source = QueueSource(QueueSource.Kind.ALBUM, "Album", "A"))
        assertEquals("v2", store.current?.videoId)
        assertTrue(store.hasPrevious && store.hasNext)
        store.next()
        assertEquals("v3", store.current?.videoId)
        store.previous()
        assertEquals("v2", store.current?.videoId)
        assertEquals(listOf("v3", "v4"), store.upNext.map { it.song.videoId })
    }

    @Test
    fun previousRestartsTheSongWhenWellIntoIt() = runTest {
        val (store, _) = makeStore()
        store.play(songs(3), startAt = 1)
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
        store.seek(50.0)
        store.previous()
        assertEquals("v1", store.current?.videoId)
        assertEquals(0.0, store.clock.position)
    }

    @Test
    fun queueEdits() = runTest {
        val (store, _) = makeStore()
        store.play(songs(4), startAt = 0)
        store.playNext(listOf(Song(videoId = "n", title = "Next")))
        store.addToQueue(listOf(Song(videoId = "z", title = "Last")))
        assertEquals(listOf("v0", "n", "v1", "v2", "v3", "z"), store.queue.map { it.song.videoId })
        store.moveQueue(listOf(5), toOffset = 1)
        assertEquals(listOf("v0", "z", "n", "v1", "v2", "v3"), store.queue.map { it.song.videoId })
        val removed = store.queue[2].id
        store.removeFromQueue(setOf(removed))
        assertEquals(listOf("v0", "z", "v1", "v2", "v3"), store.queue.map { it.song.videoId })
        // Removing the current song moves on to the next.
        store.removeFromQueue(setOf(store.queue[0].id))
        assertEquals("z", store.current?.videoId)
        store.clearUpNext()
        assertEquals(listOf("z"), store.queue.map { it.song.videoId })
    }

    @Test
    fun shuffleKeepsTheCurrentSongAndCanBeUndone() = runTest {
        val (store, _) = makeStore()
        store.play(songs(30), startAt = 3)
        val before = store.queue.map { it.song.videoId }
        store.toggleShuffle()
        assertTrue(store.isShuffled)
        assertEquals("v3", store.current?.videoId)
        assertEquals(before.take(4), store.queue.map { it.song.videoId }.take(4))
        assertEquals(before.toSet(), store.queue.map { it.song.videoId }.toSet())
        assertNotEquals(before, store.queue.map { it.song.videoId })
        store.toggleShuffle()
        assertFalse(store.isShuffled)
        assertEquals(before, store.queue.map { it.song.videoId })
    }

    @Test
    fun songsFollowOnAndRepeatAllWraps() = runTest {
        val (store, _) = makeStore()
        val short = (0 until 2).map { Song(videoId = "s$it", title = "S$it", duration = 0.25) }
        store.setRepeatMode(RepeatMode.ALL)
        store.play(short, startAt = 0)
        assertTrue(eventually { store.current?.videoId == "s1" })
        assertTrue(eventually { store.current?.videoId == "s0" })
    }

    @Test
    fun aRadioCarriesOnPastTheEndOfAList() = runTest {
        val (store, _) = makeStore()
        val short = listOf(Song(videoId = "only", title = "Only", duration = 0.2))
        store.play(short, startAt = 0, source = QueueSource(QueueSource.Kind.ALBUM, "Album", "A"))
        assertTrue(eventually { store.current?.videoId == "only-r1" })
        assertTrue(store.queue.size > 1)
    }

    @Test
    fun likingTheCurrentSongStaysWithThatSong() = runTest {
        val (store, _) = makeStore()
        store.play(songs(3), startAt = 0)
        assertEquals(LikeStatus.INDIFFERENT, store.likeStatus)
        store.setLike(LikeStatus.LIKE)
        assertEquals(LikeStatus.LIKE, store.likeStatus)
        store.next()
        assertEquals(LikeStatus.INDIFFERENT, store.likeStatus)
        store.previous()
        assertEquals("v0", store.current?.videoId)
        assertEquals(LikeStatus.LIKE, store.likeStatus)
    }

    @Test
    fun reorderingWhileShuffledKeepsTheListenersOrder() = runTest {
        val (store, _) = makeStore()
        store.play(songs(8), startAt = 0)
        store.toggleShuffle()
        store.moveQueue(listOf(7), toOffset = 1)
        val chosen = store.queue.map { it.song.videoId }
        store.toggleShuffle()
        assertFalse(store.isShuffled)
        assertEquals(chosen, store.queue.map { it.song.videoId })
    }

    @Test
    fun anUnplayableSongInTheQueueIsPassedOver() = runTest {
        val (store, engine) = makeStore()
        engine.unplayable = setOf("v1")
        val short = (0 until 3).map { Song(videoId = "v$it", title = "T$it", duration = 0.2) }
        store.play(short, startAt = 0)
        // v0 plays to its end, v1 fails and is passed over, v2 plays.
        assertTrue(eventually(5.0) { store.current?.videoId == "v2" && store.status == PlaybackStatus.Playing })
    }

    @Test
    fun anUnplayableSongTheListenerPickedShowsItsFailure() = runTest {
        val (store, engine) = makeStore()
        engine.unplayable = setOf("v1")
        store.play(songs(3), startAt = 1)
        assertTrue(eventually { store.status.isFailure })
        wait(1500)
        assertEquals("v1", store.current?.videoId)
        assertTrue(store.status.isFailure)
        // Trying again once it can be played works.
        engine.unplayable = emptySet()
        store.retry()
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
    }

    @Test
    fun aRunOfUnplayableSongsStopsThePassingOver() = runTest {
        val (store, engine) = makeStore()
        engine.unplayable = setOf("v1", "v2", "v3", "v4", "v5")
        store.play(songs(8), startAt = 0)
        store.next()
        // Passed over v1 and v2; on the third failure in a row it stays put (most likely the network is down).
        assertTrue(eventually(6.0) { store.current?.videoId == "v3" && store.status.isFailure })
        wait(1600)
        assertEquals("v3", store.current?.videoId)
    }

    @Test
    fun sleepTimerAtEndOfSongPauses() = runTest {
        val (store, _) = makeStore()
        val short = (0 until 2).map { Song(videoId = "s$it", title = "S$it", duration = 0.25) }
        store.play(short, startAt = 0)
        store.setSleepTimer(SleepTimer.EndOfSong)
        assertTrue(eventually { store.status == PlaybackStatus.Paused && store.sleepTimer == null })
        assertNull(store.sleepTimer)
    }

    // Tests of my own: parts of the Swift store that its own tests do not reach.

    @Test
    fun anUnplayableSongThatTheQueueMovesOnToIsPassedOverAfterAPause() = runTest {
        val (store, engine) = makeStore()
        engine.unplayable = setOf("v1")
        store.play(songs(3), startAt = 0)
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
        store.next()
        assertTrue(eventually { store.status.isFailure })
        assertEquals("v1", store.current?.videoId)
        // The failure is on show for a moment (1.2 seconds) before the queue moves on.
        wait(1000)
        assertEquals("v1", store.current?.videoId)
        assertTrue(eventually { store.current?.videoId == "v2" && store.status == PlaybackStatus.Playing })
    }

    @Test
    fun theSleepTimerAtAMomentPausesThen() = runTest {
        val (store, _) = makeStore()
        store.play(songs(3), startAt = 0)
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
        store.setSleepTimer(SleepTimer.At(virtualClock().nowMillis() + 30_000))
        wait(29_000)
        assertEquals(PlaybackStatus.Playing, store.status)
        wait(1_100)
        assertEquals(PlaybackStatus.Paused, store.status)
        assertNull(store.sleepTimer)
        // A timer that was cancelled does not fire.
        store.resume()
        store.setSleepTimer(SleepTimer.At(virtualClock().nowMillis() + 5_000))
        store.setSleepTimer(null)
        wait(10_000)
        assertEquals(PlaybackStatus.Playing, store.status)
    }

    @Test
    fun theClockFollowsTheEngineAndPlayAtTheEndStartsAgain() = runTest {
        val (store, _) = makeStore()
        store.play(listOf(Song(videoId = "a", title = "A", duration = 10.0)), startAt = 0)
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
        wait(4_000)
        assertTrue(store.clock.position in 3.5..4.5, "position was ${store.clock.position}")
        assertEquals(10.0, store.clock.duration)
        assertTrue(store.clock.progress in 0.3..0.5)
        store.pause()
        store.seek(10.0)
        assertEquals(0.0, store.clock.remaining)
        store.resume()
        assertTrue(store.clock.position < 1.0)
    }

    @Test
    fun repeatModeAndAutoplayAreRemembered() = runTest {
        val storage = InMemoryKeyValueStore()
        val time = virtualClock()
        val first = PlayerStore(StubAPI(), SimulatedEngine(backgroundScope, time), storage, backgroundScope, time)
        first.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, first.repeatMode)
        first.cycleRepeatMode()
        first.isAutoplayEnabled = false
        val second = PlayerStore(StubAPI(), SimulatedEngine(backgroundScope, time), storage, backgroundScope, time)
        assertEquals(RepeatMode.ONE, second.repeatMode)
        assertFalse(second.isAutoplayEnabled)
        second.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, second.repeatMode)
    }

    @Test
    fun aRefusedRatingIsPutBack() = runTest {
        val api = object : StubMusicAPI() {
            override suspend fun rate(videoId: String, status: LikeStatus) = throw io.github.boomboom.models.MusicAPIError.Unauthorized
        }
        val time = virtualClock()
        val store = PlayerStore(api, SimulatedEngine(backgroundScope, time), InMemoryKeyValueStore(), backgroundScope, time)
        store.play(songs(2), startAt = 0)
        store.setLike(LikeStatus.LIKE)
        assertEquals(LikeStatus.LIKE, store.likeStatus)
        runCurrent()
        assertEquals(LikeStatus.INDIFFERENT, store.likeStatus)
    }

    @Test
    fun playingAnEndpointReplacesTheQueueAndAFailureOnAnEmptyQueueIsShown() = runTest {
        val (store, _) = makeStore()
        store.play(WatchEndpoint(videoId = "e"), source = QueueSource(QueueSource.Kind.OTHER, "Radio", "E"))
        assertTrue(store.isLoadingQueue)
        assertTrue(eventually { store.status == PlaybackStatus.Playing })
        assertEquals("e", store.current?.videoId)
        assertEquals(6, store.queue.size)
        assertFalse(store.isLoadingQueue)

        val failing = object : StubMusicAPI() {
            override suspend fun watchQueue(endpoint: WatchEndpoint): WatchQueue =
                throw io.github.boomboom.models.MusicAPIError.Network("down")
        }
        val time = virtualClock()
        val empty = PlayerStore(failing, SimulatedEngine(backgroundScope, time), InMemoryKeyValueStore(), backgroundScope, time)
        empty.play(WatchEndpoint(videoId = "e"))
        runCurrent()
        assertEquals(PlaybackStatus.Failed("Couldn't load this. Check your connection."), empty.status)
        // Pressing play on a failure tries again.
        assertTrue(empty.state.value.status.isFailure)
    }

    @Test
    fun aSeededShuffleIsRepeatable() = runTest {
        val time = virtualClock()
        fun shuffled(seed: Int): List<String> {
            val store = PlayerStore(
                StubAPI(), SimulatedEngine(backgroundScope, time), InMemoryKeyValueStore(), backgroundScope, time,
                Random(seed),
            )
            store.play(songs(20), startAt = 0)
            store.toggleShuffle()
            return store.queue.map { it.song.videoId }
        }
        assertEquals(shuffled(7), shuffled(7))
        assertNotEquals(shuffled(7), shuffled(8))
    }
}
