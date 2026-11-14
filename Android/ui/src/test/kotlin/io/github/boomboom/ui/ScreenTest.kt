package io.github.boomboom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.CacheStore
import io.github.boomboom.logic.Clock
import io.github.boomboom.logic.DownloadBackend
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.logic.KotlinSoundMath
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.SessionStore
import io.github.boomboom.logic.SimulatedEngine
import io.github.boomboom.models.Account
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.preview.PreviewMusicAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule

/**
 * What every screen test shares: the app on canned data, with every wait on a test scheduler (nothing sleeps), at a
 * phone's size in the dark theme (see `@Config` on the test classes), and a way to record the screen as a PNG.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    val scheduler = TestCoroutineScheduler()

    /** Where the screens' and stores' coroutines run. Unconfined: work starts at once, and waits only on [scheduler]. */
    open val dispatcher: TestDispatcher by lazy { UnconfinedTestDispatcher(scheduler) }

    /** Where the stores run; cancelled after each test. */
    val storeScope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    val controller = BoomAppController()

    fun services(api: PreviewMusicAPI, signedIn: Boolean = true, others: List<Account> = emptyList()): AppServices =
        AppServices.preview(
            api, storeScope, account = if (signedIn) PreviewMusicAPI.previewAccount else null, otherAccounts = others,
            time = Clock { scheduler.currentTime },
        )

    /** Like [services], with downloads that go through [backend], so a test decides when and how they arrive. */
    fun services(api: PreviewMusicAPI, backend: FakeDownloadBackend): AppServices {
        val storage = InMemoryKeyValueStore()
        val time = Clock { scheduler.currentTime }
        return AppServices(
            api = api,
            player = PlayerStore(api, SimulatedEngine(storeScope, time), storage, storeScope, time),
            equalizer = EqualizerStore(KotlinSoundMath, storage, storeScope, time),
            session = SessionStore.preview(api, storeScope, PreviewMusicAPI.previewAccount),
            cache = CacheStore(backend = null, storage = storage),
            downloads = DownloadStore(backend, storage, storeScope, time = time),
        )
    }

    /** Lets the scheduler's clock move on and runs what came due (the search pause, a delayed answer). */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    fun showApp(
        services: AppServices,
        start: BrowseStart = BrowseStart.Tab(BrowseTab.HOME),
        onSignIn: (String?) -> Unit = {},
        onOpenEqualizer: () -> Unit = {},
        onReportProblem: () -> Unit = {},
    ) {
        compose.setContent {
            BoomApp(
                services, playerBar = { StandInPlayerBar(services) }, onOpenEqualizer = onOpenEqualizer, onSignIn = onSignIn,
                start = start, controller = controller, onReportProblem = onReportProblem, workContext = dispatcher,
            )
        }
        compose.waitForIdle()
    }

    /** Records the whole screen, sheets and dialogs included. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @After
    fun stopStores() {
        storeScope.cancel()
    }
}

/** Stands in the player bar's slot (another part of the work fills it): the current song, so the slot can be seen. */
@Composable
fun StandInPlayerBar(services: AppServices) {
    val player by services.player.state.collectAsState()
    val song = player.current ?: return
    Row(
        Modifier
            .fillMaxSize()
            .background(BoomColor.surface)
            .padding(horizontal = 12.dp)
            .testTag("standInPlayerBar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkView(song.artwork, width = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistLine, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.Pause, null, tint = BoomColor.textPrimary, modifier = Modifier.size(28.dp))
    }
}

/** Downloads that wait for the test: it says what arrives, and when. */
class FakeDownloadBackend : DownloadBackend {
    override var onDownloadEvent: ((DownloadEvent) -> Unit)? = null
    override var allowsDownloadsOnMobileData = false
    override val isHoldingDownloadsForWiFi = false
    override var onDownloadHoldChange: (() -> Unit)? = null
    val started = ArrayList<String>()

    override fun startDownloads(videoIds: List<String>) {
        started.addAll(videoIds)
    }

    override fun removeDownloads(videoIds: List<String>) {
        started.removeAll(videoIds.toSet())
    }

    fun send(event: DownloadEvent) {
        onDownloadEvent?.invoke(event)
    }
}
