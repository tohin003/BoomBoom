package io.github.boomboom.ui

import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.AudioCacheBackend
import io.github.boomboom.logic.BackendState
import io.github.boomboom.logic.CacheStore
import io.github.boomboom.logic.Clock
import io.github.boomboom.logic.DownloadBackend
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.logic.KotlinSoundMath
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.SessionBackend
import io.github.boomboom.logic.SessionProfile
import io.github.boomboom.logic.SessionStore
import io.github.boomboom.logic.SimulatedEngine
import io.github.boomboom.models.Account
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.preview.FormatReportingEngine
import io.github.boomboom.ui.preview.PreviewMusicAPI

/**
 * What the Library, Downloads and Settings tests share: the app put together with stand-ins the test drives (a
 * browser with up to three profiles, a song cache with sizes in it, downloads that arrive when the test says).
 */
abstract class PlacesTest : ScreenTest() {
    val storage = InMemoryKeyValueStore()
    val time = Clock { scheduler.currentTime }

    /**
     * @param session A store over a fake browser, or null for the preview's fixed one ([signedIn], [others]).
     * @param downloads Null: downloads "finish" at once (6.4 MB each).
     */
    fun build(
        api: MusicAPI = PreviewMusicAPI(delays = false),
        session: SessionStore? = null,
        downloads: DownloadBackend? = null,
        cache: AudioCacheBackend? = null,
        signedIn: Boolean = true,
        others: List<Account> = emptyList(),
    ): AppServices = AppServices(
        api = api,
        player = PlayerStore(api, FormatReportingEngine(SimulatedEngine(storeScope, time)), storage, storeScope, time),
        equalizer = EqualizerStore(KotlinSoundMath, storage, storeScope, time),
        session = session ?: SessionStore.preview(api, storeScope, if (signedIn) PreviewMusicAPI.previewAccount else null, others),
        cache = CacheStore(cache, storage),
        downloads = DownloadStore(downloads, storage, storeScope, time = time),
    )

    /** A session store over [backend]; the names of accounts not active are remembered as the real store keeps them. */
    fun sessionOver(backend: FakeSessionBackend, api: MusicAPI, names: Map<String, Account> = emptyMap()): SessionStore {
        if (names.isNotEmpty()) {
            val json = names.entries.joinToString(",", "{", "}") { (id, account) ->
                "\"$id\":{\"name\":\"${account.name}\"" + (account.handle?.let { ",\"handle\":\"$it\"" } ?: "") + "}"
            }
            storage.putString("session.accounts", json)
        }
        return SessionStore(api, backend, storage, storeScope)
    }

    /** Downloads [songs] one by one, then reports each as [events] says (in order): what a real queue might show. */
    fun FakeDownloadBackend.stage(services: AppServices, songs: List<Song>, events: List<(String) -> DownloadEvent?>) {
        songs.reversed().forEach { services.downloads.download(it) }
        songs.zip(events).forEach { (song, event) -> event(song.videoId)?.let { send(it) } }
    }

    /** Downloads an album of the catalogue as a whole, as its page's button does. */
    fun downloadAlbum(services: AppServices, api: PreviewMusicAPI, albumId: String) {
        val album = api.catalog.albumsById.getValue(albumId)
        val tracks = api.catalog.tracksByAlbum.getValue(albumId)
        services.downloads.download(
            tracks,
            DownloadCollection(
                DownloadCollector.key(album.id), DownloadCollection.Kind.ALBUM, album.title,
                album.artists.joinToString(", ") { it.name }, album.artwork,
            ),
        )
    }

    companion object {
        /** Invented, like everything in the preview. */
        val second = Account("Sam Okafor", "@samokafor", io.github.boomboom.ui.preview.PreviewCatalog.square("second"))
        val third = Account("Priya Nair", "@priyanair", io.github.boomboom.ui.preview.PreviewCatalog.square("third"))
    }
}

/** A browser with profiles the test sets up and changes. Lives on the main thread, as the real one does. */
class FakeSessionBackend(count: Int = 2, activeIndex: Int = 0) : SessionBackend {
    var list: List<SessionProfile> = (0 until count).map { SessionProfile("profile-${it + 1}", isSignedIn = true, isActive = it == activeIndex) }
    override var state: BackendState = BackendState.Ready
    override val isSignedIn: Boolean get() = list.firstOrNull { it.isActive }?.isSignedIn ?: false
    override var onChange: (() -> Unit)? = null
    override val profiles: List<SessionProfile> get() = list
    override val profileLimit = 3
    val removed = ArrayList<String>()
    val switched = ArrayList<String>()
    var reloads = 0
    var added = 0

    override fun switchProfile(id: String) {
        switched.add(id)
        list = list.map { it.copy(isActive = it.id == id) }
    }

    override fun addProfile(): String? {
        if (list.size >= profileLimit) return null
        added += 1
        return "profile-new"
    }

    override suspend fun removeProfile(id: String) {
        removed.add(id)
        val wasActive = list.firstOrNull { it.id == id }?.isActive == true
        list = list.filter { it.id != id }
        if (wasActive && list.isNotEmpty()) list = list.mapIndexed { index, profile -> profile.copy(isActive = index == 0) }
    }

    override suspend fun didFinishSignIn(profile: String) {}

    override suspend fun signOut() {
        list = list.map { if (it.isActive) it.copy(isSignedIn = false) else it }
    }

    override fun reload() {
        reloads += 1
    }

    /** A change the browser makes by itself (a sign-in elsewhere, a failure), reported as the real one reports it. */
    fun change(block: FakeSessionBackend.() -> Unit) {
        block()
        onChange?.invoke()
    }
}

/** A song cache with something in it. */
class FakeCacheBackend(var used: Long = 1_240_000_000, var count: Int = 212) : AudioCacheBackend {
    var cleared = 0
    override var limitBytes: Long = CacheStore.LIMIT_CHOICES[2]
    override suspend fun usedBytes(): Long = used
    override suspend fun songCount(): Int = count
    override suspend fun clear() {
        cleared += 1
        used = 0
        count = 0
    }
}

/** Downloads that wait for the test, and a phone the test can put on mobile data. */
class HoldingDownloadBackend : DownloadBackend {
    override var onDownloadEvent: ((DownloadEvent) -> Unit)? = null
    override var allowsDownloadsOnMobileData = false
    var isOnMobileData = false
    override val isHoldingDownloadsForWiFi: Boolean get() = isOnMobileData && !allowsDownloadsOnMobileData
    override var onDownloadHoldChange: (() -> Unit)? = null
    val started = ArrayList<String>()

    override fun startDownloads(videoIds: List<String>) {
        started.addAll(videoIds)
    }

    override fun removeDownloads(videoIds: List<String>) {}

    fun send(event: DownloadEvent) {
        onDownloadEvent?.invoke(event)
    }
}
