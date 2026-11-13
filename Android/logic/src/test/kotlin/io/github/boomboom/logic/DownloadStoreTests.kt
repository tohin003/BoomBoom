package io.github.boomboom.logic

import io.github.boomboom.models.Song
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for the engine: remembers what it was asked and lets a test say what happens to each song. */
private class FakeDownloads : DownloadBackend {
    override var onDownloadEvent: ((DownloadEvent) -> Unit)? = null
    override var onDownloadHoldChange: (() -> Unit)? = null
    override var allowsDownloadsOnMobileData = false
        set(value) {
            field = value
            onDownloadHoldChange?.invoke()
        }
    var isOnMobileData = false
        set(value) {
            field = value
            onDownloadHoldChange?.invoke()
        }
    override val isHoldingDownloadsForWiFi: Boolean get() = isOnMobileData && !allowsDownloadsOnMobileData
    val started = ArrayList<String>()
    val removed = ArrayList<String>()

    override fun startDownloads(videoIds: List<String>) { started.addAll(videoIds) }
    override fun removeDownloads(videoIds: List<String>) { removed.addAll(videoIds) }

    fun finish(videoId: String, bytes: Long = 5_000_000) {
        onDownloadEvent?.invoke(DownloadEvent.Finished(videoId, bytes, "Opus 270 kbps"))
    }
}

private fun song(id: String, available: Boolean = true) = Song(videoId = id, title = "Song $id", isAvailable = available)

private fun TestScope.makeStore(backend: FakeDownloads, index: File? = null): DownloadStore =
    DownloadStore(backend, InMemoryKeyValueStore(), backgroundScope, index, virtualClock())

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadStoreTests {
    @Test
    fun aSongGoesFromWaitingToDownloaded() = runTest {
        val backend = FakeDownloads()
        val store = makeStore(backend)
        assertNull(store.stateOf("a"))
        store.download(song("a"))
        assertTrue(store.stateOf("a") == DownloadItem.State.Waiting && backend.started == listOf("a"))
        assertTrue(store.pendingCount == 1 && store.totalBytes == 0L)
        backend.onDownloadEvent?.invoke(DownloadEvent.Progress("a", 0.5))
        assertEquals(DownloadItem.State.Downloading(0.5), store.stateOf("a"))
        backend.finish("a", bytes = 6_000_000)
        assertEquals(DownloadItem.State.Downloaded, store.stateOf("a"))
        assertTrue(store.totalBytes == 6_000_000L && store.pendingCount == 0)
        assertEquals("Opus 270 kbps", store.items[0].quality)
        // Asking again changes nothing and fetches nothing.
        store.download(song("a"))
        assertTrue(backend.started == listOf("a") && store.items.size == 1)
    }

    @Test
    fun anAlbumIsKeptAsAWholeAndRemovedAsAWhole() = runTest {
        val backend = FakeDownloads()
        val store = makeStore(backend)
        store.download(song("solo"))
        val album = DownloadCollection(id = "MPREb_1", kind = DownloadCollection.Kind.ALBUM, title = "An album", subtitle = "Someone")
        store.download(listOf(song("t1"), song("gone", available = false), song("t2"), song("solo")), album)
        // The unplayable track is left out; the song already asked for is not fetched twice.
        assertEquals(listOf("t1", "t2", "solo"), store.collections.firstOrNull()?.songIds)
        assertEquals(listOf("solo", "t1", "t2"), backend.started)
        assertEquals(listOf("t1", "t2", "solo"), store.items.map { it.id })
        assertEquals(CollectionProgress(0, 3), store.progressOf("MPREb_1"))
        backend.finish("t1")
        backend.finish("t2", bytes = 3_000_000)
        backend.finish("solo")
        assertEquals(CollectionProgress(3, 3), store.progressOf("MPREb_1"))
        assertEquals(13_000_000L, store.bytesOf("MPREb_1"))
        // Removing the album takes its own songs and leaves the one the listener also downloaded by itself.
        store.removeCollection("MPREb_1")
        assertTrue(store.collections.isEmpty())
        assertEquals(setOf("t1", "t2"), backend.removed.toSet())
        assertTrue(store.items.map { it.id } == listOf("solo") && store.totalBytes == 5_000_000L)
    }

    @Test
    fun songsAreRemovedOneOrManyAtATime() = runTest {
        val backend = FakeDownloads()
        val store = makeStore(backend)
        val list = DownloadCollection(id = "VLPL1", kind = DownloadCollection.Kind.PLAYLIST, title = "A playlist")
        store.download(listOf(song("a"), song("b"), song("c")), list)
        for (id in listOf("a", "b", "c")) backend.finish(id)
        store.remove(setOf("a"))
        assertTrue(store.collections.firstOrNull()?.songIds == listOf("b", "c") && backend.removed == listOf("a"))
        store.remove(setOf("b", "c"))
        // A playlist with nothing left is gone too.
        assertTrue(store.collections.isEmpty() && store.items.isEmpty() && store.totalBytes == 0L)
        store.download(song("d"))
        store.removeAll()
        assertTrue(store.items.isEmpty() && "d" in backend.removed)
    }

    @Test
    fun aFailureCanBeTriedAgain() = runTest {
        val backend = FakeDownloads()
        val store = makeStore(backend)
        store.download(song("a"))
        backend.onDownloadEvent?.invoke(DownloadEvent.Failed("a", "This song is not available."))
        assertTrue(store.stateOf("a") == DownloadItem.State.Failed("This song is not available.") && store.failedCount == 1)
        store.retryFailed()
        assertTrue(store.stateOf("a") == DownloadItem.State.Waiting && backend.started == listOf("a", "a"))
    }

    @Test
    fun theListSurvivesARestartAndCarriesOn() = runTest {
        val folder = Files.createTempDirectory("boom-downloads-").toFile()
        try {
            val index = File(folder, "downloads.json")
            val backend = FakeDownloads()
            val first = makeStore(backend, index)
            val list = DownloadCollection(id = "VLPL1", kind = DownloadCollection.Kind.PLAYLIST, title = "A playlist")
            first.download(listOf(song("a"), song("b"), song("c")), list)
            backend.finish("a")
            backend.onDownloadEvent?.invoke(DownloadEvent.Progress("b", 0.4))
            first.saveNow()

            val later = FakeDownloads()
            val second = makeStore(later, index)
            assertEquals(listOf("a", "b", "c"), second.items.map { it.id })
            assertTrue(second.stateOf("a") == DownloadItem.State.Downloaded && second.stateOf("b") == DownloadItem.State.Waiting)
            assertEquals("A playlist", second.collections.firstOrNull()?.title)
            // The kept song is checked against its file, the others are asked for again.
            assertEquals(listOf("a", "b", "c"), later.started)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun downloadsWaitForWiFiUnlessMobileDataIsAllowed() = runTest {
        val backend = FakeDownloads()
        val store = makeStore(backend)
        backend.isOnMobileData = true
        assertFalse(store.isWaitingForWiFi)          // nothing to download yet
        store.download(song("a"))
        assertTrue(store.isWaitingForWiFi)
        store.isMobileDataAllowed = true
        assertTrue(backend.allowsDownloadsOnMobileData && !store.isWaitingForWiFi)
    }

    // Tests of my own.

    @Test
    fun theListIsWrittenAMomentAfterAChangeNotBefore() = runTest {
        val folder = Files.createTempDirectory("boom-downloads-").toFile()
        try {
            val index = File(folder, "nested/downloads.json")
            val store = makeStore(FakeDownloads(), index)
            store.download(song("a"))
            wait(200)
            assertFalse(index.exists(), "events come in bursts: the write waits 300 ms")
            wait(150)
            assertTrue(index.exists())
            assertTrue(index.readText().contains("\"a\""))
            assertFalse(File(folder, "nested/downloads.json.tmp").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun aListSavedByAnOlderVersionOrDamagedStillOpens() = runTest {
        val folder = Files.createTempDirectory("boom-downloads-").toFile()
        try {
            val index = File(folder, "downloads.json")
            // Written before `isStandalone`, `bytes` and `quality` existed, with a field of a later version.
            index.writeText(
                """{"items":[{"song":{"videoId":"x","title":"X"},"state":{"type":"downloaded"},"addedAt":5,"later":1}],""" +
                    """"collections":[]}""",
            )
            val store = makeStore(FakeDownloads(), index)
            assertEquals(DownloadItem.State.Downloaded, store.stateOf("x"))
            assertFalse(store.items[0].isStandalone)
            index.writeText("{ nonsense")
            assertTrue(makeStore(FakeDownloads(), index).items.isEmpty())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun withoutABackendSongsDownloadAtOnce() = runTest {
        val store = DownloadStore(null, InMemoryKeyValueStore(), backgroundScope, time = virtualClock())
        store.download(song("a"))
        assertEquals(DownloadItem.State.Downloaded, store.stateOf("a"))
        assertEquals(6_400_000L, store.totalBytes)
    }

    @Test
    fun theMobileDataChoiceIsRemembered() = runTest {
        val storage = InMemoryKeyValueStore()
        val first = DownloadStore(FakeDownloads(), storage, backgroundScope, time = virtualClock())
        assertFalse(first.isMobileDataAllowed)
        first.isMobileDataAllowed = true
        val backend = FakeDownloads()
        val second = DownloadStore(backend, storage, backgroundScope, time = virtualClock())
        assertTrue(second.isMobileDataAllowed && backend.allowsDownloadsOnMobileData)
    }
}
