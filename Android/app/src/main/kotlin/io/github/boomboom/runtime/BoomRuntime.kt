package io.github.boomboom.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.boomboom.capture.AudioCache
import io.github.boomboom.capture.DownloadLibrary
import io.github.boomboom.capture.StreamBroker
import io.github.boomboom.innertube.InnerTubeClient
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.CacheStore
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.SessionStore
import io.github.boomboom.service.PlaybackService
import io.github.boomboom.web.HiddenBrowser
import io.github.boomboom.web.WebSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * The running app's machinery, put together once at process start: the hidden tab, the capture broker, the audio
 * engine, and the stores the screens use. It belongs to the process, not to an activity, so music and fetching go on
 * with every screen closed; the playback service keeps the process alive while they do.
 */
class BoomRuntime(private val context: Context) {
    /** The stores' thread: the main one, through a dispatcher that never runs a new coroutine inside the call. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val storage = PreferencesStore(context)
    val browser = HiddenBrowser(context, storage, scope)
    val cache = AudioCache(File(context.cacheDir, "BoomTracks"))
    val downloads = DownloadLibrary(File(context.filesDir, "BoomDownloads"))
    val broker = StreamBroker(browser, cache, downloads, storage, scope)
    val system = SystemAudio(context)
    val network = NetworkWatch(context)
    val engine = LiveEngine(broker, system, network, scope)
    val api = InnerTubeClient(WebSessionProvider(browser))
    val services: AppServices
    val player: PlayerStore get() = services.player

    /** Called when what the playback service shows may have changed (a fetch started or ended). */
    var onFetchingChange: (() -> Unit)? = null

    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val fetchWake: PowerManager.WakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoomBoom:fetch").apply {
        setReferenceCounted(false)
    }
    @Suppress("DEPRECATION")
    private val fetchWifi: WifiManager.WifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BoomBoom:fetch").apply {
        setReferenceCounted(false)
    }

    init {
        val player = PlayerStore(api, engine, storage, scope)
        val equalizer = EqualizerStore(engine, storage, scope)
        equalizer.attach(engine)
        services = AppServices(
            api = api,
            player = player,
            equalizer = equalizer,
            session = SessionStore(api, browser, storage, scope),
            cache = CacheStore(cache, storage),
            downloads = DownloadStore(engine, storage, scope, downloads.indexFile),
        )
        broker.onFetchingChange = { fetchingChanged() }

        // The service is started with the first song, while the app is in front; from then on it keeps itself in the
        // foreground while music plays.
        scope.launch {
            player.state.map { it.status }.distinctUntilChanged().collect { status ->
                if (status == PlaybackStatus.Loading || status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering) {
                    startService()
                }
            }
        }
        browser.start()
    }

    /**
     * While the page fetches, the processor and the Wi-Fi radio stay awake even with the screen off (the audio itself
     * keeps the phone awake while it sounds, but a song is fetched far faster than it plays).
     */
    @SuppressLint("WakelockTimeout")
    private fun fetchingChanged() {
        if (broker.isFetching) {
            fetchWake.acquire(10 * 60 * 1000L)
            fetchWifi.acquire()
        } else {
            if (fetchWake.isHeld) fetchWake.release()
            if (fetchWifi.isHeld) fetchWifi.release()
        }
        onFetchingChange?.invoke()
    }

    private var serviceStarted = false

    fun startService() {
        if (serviceStarted) return
        try {
            context.startService(Intent(context, PlaybackService::class.java))
            serviceStarted = true
        } catch (notAllowed: IllegalStateException) {
            // In the background the system refuses; the next song started from the screen starts it.
            BoomTrace.note("playback service could not be started from the background")
        }
    }

    /** The service stopped (the listener closed it, or nothing played): start it again with the next song. */
    fun serviceStopped() {
        serviceStarted = false
    }
}
