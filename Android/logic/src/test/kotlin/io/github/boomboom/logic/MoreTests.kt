package io.github.boomboom.logic

import io.github.boomboom.models.Account
import io.github.boomboom.models.Song
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Tests of my own, for the parts the iPhone package's tests do not reach. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MoreTests {
    private class FakeCache : AudioCacheBackend {
        var used = 123_000L
        var count = 4
        var cleared = 0
        override var limitBytes = 0L
        override suspend fun usedBytes() = used
        override suspend fun songCount() = count
        override suspend fun clear() { cleared += 1; used = 0; count = 0 }
    }

    @Test
    fun theCacheKeepsItsLimitAndReportsWhatTheBackendHolds() = runTest {
        val storage = InMemoryKeyValueStore()
        val backend = FakeCache()
        val store = CacheStore(backend, storage)
        assertEquals(2_000_000_000L, store.limitBytes)
        assertEquals(2_000_000_000L, backend.limitBytes)
        store.limitBytes = CacheStore.LIMIT_CHOICES.last()
        assertEquals(10_000_000_000L, backend.limitBytes, "ten gigabytes does not fit an Int")
        assertEquals(10_000_000_000L, CacheStore(FakeCache(), storage).limitBytes)
        store.refresh()
        assertTrue(store.usedBytes == 123_000L && store.songCount == 4)
        store.clear()
        assertTrue(store.usedBytes == 0L && store.songCount == 0 && backend.cleared == 1)
        // Without a backend there is nothing to ask.
        val bare = CacheStore(null, InMemoryKeyValueStore())
        bare.refresh()
        assertEquals(0L, bare.usedBytes)
    }

    @Test
    fun theKeyValueStoreAnswersNullForAnotherType() {
        val storage = InMemoryKeyValueStore()
        storage.putString("a", "x")
        storage.putBoolean("b", true)
        storage.putInt("c", 3)
        storage.putLong("d", 4L)
        assertNull(storage.getBoolean("a"))
        assertNull(storage.getString("b"))
        assertNull(storage.getLong("c"))
        assertNull(storage.getInt("d"))
        assertEquals(3, storage.getInt("c"))
        storage.remove("c")
        assertNull(storage.getInt("c"))
    }

    @Test
    fun theSimulatedEngineKeepsTimeAndJoinsOnToThePreparedSong() = runTest {
        val events = ArrayList<String>()
        val engine = SimulatedEngine(backgroundScope, virtualClock())
        engine.loadDelay = 50.milliseconds
        engine.delegate = object : PlaybackEngineDelegate {
            override fun engineDidChange(engine: PlaybackEngine, state: EngineState) { events.add(state.toString()) }
            override fun engineDidAdvanceToPreparedSong(engine: PlaybackEngine) { events.add("advanced") }
            override fun engineDidFinishSong(engine: PlaybackEngine) { events.add("finished") }
        }
        val a = Song(videoId = "a", title = "A", duration = 1.0)
        val b = Song(videoId = "b", title = "B", duration = 1.0)
        engine.load(a, autoplay = true, startAt = 0.25)
        engine.prepareNext(b)
        assertEquals(listOf("Loading"), events)
        wait(60)
        assertEquals(listOf("Loading", "Playing"), events)
        wait(400)
        assertTrue(engine.position in 0.6..0.7, "position ${engine.position}")
        engine.pause()
        wait(5_000)
        assertTrue(engine.position in 0.6..0.7, "paused time stands still")
        engine.play()
        wait(1_000)
        assertEquals("advanced", events.last(), events.toString())
        assertEquals(1.0, engine.duration)
        wait(1_200)
        assertEquals("finished", events.last())
        assertEquals(1.0, engine.position)
        engine.stop()
        assertEquals(0.0, engine.duration)
    }

    @Test
    fun aPreviewSetOfServicesPlaysSilentlyAndKeepsDownloadsInMemory() = runTest {
        val services = AppServices.preview(StubMusicAPI(), backgroundScope, Account(name = "Me"), time = virtualClock())
        services.player.play(songs(3), startAt = 1)
        assertTrue(eventually { services.player.status == PlaybackStatus.Playing })
        assertEquals("v1", services.player.current?.videoId)
        services.equalizer.setGain(3f, 0)
        assertTrue(services.equalizer.settings.isEnabled)
        services.downloads.download(songs(1)[0])
        assertTrue(services.downloads.items[0].isDownloaded)
        assertTrue(services.session.isSignedIn)
        assertEquals(2_000_000_000L, services.cache.limitBytes)
        assertFalse(services.player.isLoadingQueue)
        runCurrent()
    }

    @Test
    fun theProfileInTheEngineTermsCarriesTheWholeSound() {
        val settings = SoundSettings(
            isEnabled = true, gains = listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 2f), preamp = -1f, bass = 3f, treble = 2f,
            boost = 4f, isLimiterEnabled = false, isAutoHeadroomEnabled = true, balance = 0.5f, width = 1.5f,
            deepBass = 0.3f, air = 0.2f, crossfeed = 0.1f, isSpatialEnabled = true,
        )
        val profile = settings.profile(KotlinSoundMath)
        assertEquals(10, profile.bandGains.size)
        assertTrue(profile.isEnabled && profile.isSpatial && !profile.isLimiterEnabled)
        assertTrue(profile.bassGain == 3f && profile.trebleGain == 2f && profile.boost == 4f && profile.preamp == -1f)
        assertTrue(profile.balance == 0.5f && profile.width == 1.5f && profile.deepBass == 0.3f)
        assertTrue(profile.headroom > 2.5f && profile.headroom < 4.5f, "headroom ${profile.headroom}")
        assertFalse(profile.isTransparent)
        assertTrue(SoundSettings(isEnabled = true).profile(KotlinSoundMath).isTransparent)
        assertTrue(SoundProfile().isTransparent)
    }
}
