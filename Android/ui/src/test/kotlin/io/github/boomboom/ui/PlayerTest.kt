package io.github.boomboom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.CacheStore
import io.github.boomboom.logic.Clock
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.logic.KotlinSoundMath
import io.github.boomboom.logic.PlaybackEngine
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.logic.SessionStore
import io.github.boomboom.logic.SimulatedEngine
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.BoomNavigator
import io.github.boomboom.ui.player.MiniPlayerBar
import io.github.boomboom.ui.player.NowPlayingOverlay
import io.github.boomboom.ui.preview.FormatReportingEngine
import io.github.boomboom.ui.preview.PreviewMusicAPI
import io.github.boomboom.ui.preview.PreviewSoundSink
import io.github.boomboom.ui.sound.SoundScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After

/**
 * What the player and sound tests share: the app as the app module will put it together (the frame with the mini
 * player in its slot, the full player over it, the Sound screen over everything), on canned data, with the engine and
 * the sound processor's stand-ins in reach so a test can make a song fail or the limiter work.
 */
abstract class PlayerTest : ScreenTest() {
    private val playerScope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }
    val time = Clock { scheduler.currentTime }
    lateinit var engine: SimulatedEngine
    val sink = PreviewSoundSink()
    val isExpanded = mutableStateOf(false)
    val isSoundShown = mutableStateOf(false)
    val opened = ArrayList<BrowseEndpoint>()
    val haptics = RecordingHaptics()
    val soundScroll = ScrollState(0)

    /** Where the stores keep their settings: one per test, so reopening a screen finds what it left. */
    val storage: KeyValueStore = InMemoryKeyValueStore()

    fun playerServices(
        api: MusicAPI = PreviewMusicAPI(delays = false),
        wrap: (SimulatedEngine) -> PlaybackEngine = { FormatReportingEngine(it) },
    ): AppServices {
        engine = SimulatedEngine(playerScope, time)
        val services = AppServices(
            api = api,
            player = PlayerStore(api, wrap(engine), storage, playerScope, time),
            equalizer = EqualizerStore(KotlinSoundMath, storage, playerScope, time),
            session = SessionStore.preview(api, playerScope, PreviewMusicAPI.previewAccount),
            cache = CacheStore(backend = null, storage = storage),
            downloads = DownloadStore(backend = null, storage = storage, scope = playerScope, time = time),
        )
        services.equalizer.attach(sink)
        return services
    }

    /** The whole app: frame, mini player, full player, Sound screen. */
    fun showPlayerApp(services: AppServices) {
        compose.setContent { PlayerApp(services) }
        compose.waitForIdle()
    }

    @Composable
    fun PlayerApp(services: AppServices) {
        Box(Modifier.fillMaxSize()) {
            BoomApp(
                services, playerBar = { MiniPlayerBar(services, onExpand = { isExpanded.value = true }) },
                onOpenEqualizer = { isSoundShown.value = true }, onSignIn = {}, controller = controller,
                onWillOpen = { isExpanded.value = false }, workContext = dispatcher,
            )
            val navigator = remember {
                BoomNavigator(
                    open = { endpoint ->
                        opened += endpoint
                        controller.open(endpoint)
                    },
                    openEqualizer = { isSoundShown.value = true },
                )
            }
            NowPlayingOverlay(
                services, isExpanded.value, onCollapse = { isExpanded.value = false }, navigator = navigator, time = time,
                workContext = dispatcher,
            )
            if (isSoundShown.value) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    SoundScreen(services, onDone = { isSoundShown.value = false }, scrollState = soundScroll)
                }
            }
        }
    }

    /** The Sound screen alone, as the app shows it. */
    fun showSound(services: AppServices) {
        isSoundShown.value = true
        compose.setContent {
            if (isSoundShown.value) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    SoundScreen(services, onDone = { isSoundShown.value = false }, scrollState = soundScroll)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Plays a list and lets it start (the simulated engine "loads" for a quarter second). */
    fun playList(services: AppServices, songs: List<Song>, startAt: Int = 0) {
        compose.runOnIdle {
            val source = QueueSource(
                QueueSource.Kind.ALBUM, "Album", "Night Signals",
                BrowseEndpoint("MPREb_nightsignals", pageType = BrowseEndpoint.PageType.ALBUM),
            )
            services.player.play(songs, startAt, source)
        }
        advance(400)
    }

    fun expand() {
        isExpanded.value = true
        compose.waitForIdle()
    }

    /** Scrolls the Sound screen so the node with [tag] sits just under the fixed header. */
    fun scrollSoundTo(tag: String) {
        // Positions, not bounds: bounds are clipped to what shows, and the node may be below the fold.
        val viewport = compose.onNodeWithTag("sound.scroll").fetchSemanticsNode().positionInRoot.y
        val top = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val margin = with(compose.density) { 12.dp.toPx() }
        compose.runOnIdle { runBlocking { soundScroll.scrollTo((soundScroll.value + top - viewport - margin).toInt()) } }
        compose.waitForIdle()
    }

    /** Lets the meters take readings for a while (they read thirty times a second on the stores' clock). */
    fun meter(millis: Long = 1500) {
        repeat((millis / 33).toInt()) {
            scheduler.advanceTimeBy(33)
            scheduler.runCurrent()
        }
        compose.waitForIdle()
    }

    @After
    fun stopPlayer() {
        playerScope.cancel()
    }
}

/** Records the haptics a screen asks for, instead of buzzing. */
class RecordingHaptics : HapticFeedback {
    val played = ArrayList<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        played += hapticFeedbackType
    }
}

/** The preview's data with lyrics that have no times, for the plain lyrics view. */
class PlainLyricsAPI(private val inner: PreviewMusicAPI = PreviewMusicAPI(delays = false)) : MusicAPI by inner {
    override suspend fun lyrics(browseId: String): Lyrics {
        val timed = inner.lyrics(browseId)
        return Lyrics(timed.lines.map { Lyrics.Line(it.text) }, timed.source)
    }
}
