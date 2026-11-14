package io.github.boomboom.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.Song
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseShell
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.browse.SaveToPlaylistSheet
import io.github.boomboom.ui.browse.ShellRouter
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomNavigator
import io.github.boomboom.ui.design.BoomTheme
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.LocalDownloadCollector
import io.github.boomboom.ui.design.LocalSettingsStore
import io.github.boomboom.ui.design.LocalNetworkReturns
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What the app (outside the screens) can ask of them: open a page from elsewhere (the full player's "Go to album"),
 * and, for tests, see where the frame is.
 */
class BoomAppController {
    internal var pendingPage by mutableStateOf<BrowseEndpoint?>(null)

    /** The frame's navigation, once [BoomApp] is on screen. */
    var router: ShellRouter? = null
        internal set

    /** Shows a page in the current tab. */
    fun open(endpoint: BrowseEndpoint) {
        pendingPage = endpoint
    }

    internal var pendingSave by mutableStateOf<List<Song>?>(null)

    /** Shows "Save to playlist" for these songs (or the sign-in, when nobody is signed in), over whatever is open. */
    fun saveToPlaylist(songs: List<Song>) {
        pendingSave = songs
    }
}

/** The sheets the frame shows over any screen. Only one shows at a time, so one value says which. */
private sealed class AppSheet {
    data object None : AppSheet()
    data class SaveToPlaylist(val songs: List<Song>) : AppSheet()
}

/**
 * The whole browsing app on screen: the frame with its three tabs, the player bar above the tab bar, the sheets any
 * screen can ask for, and BoomBoom's look around all of it.
 *
 * @param playerBar The mini player. Drawn while a song is loaded; the app module fills it.
 * @param onOpenEqualizer Shows the equaliser and amplifier.
 * @param onSignIn Shows the sign-in for a browser profile: null for the active one, or the id of a new profile made
 *   for another account (from [io.github.boomboom.logic.SessionStore.beginAddingAccount]).
 * @param onWillOpen Called before a page opens, so the app can close the full player first.
 * @param onReportProblem Shares the app's own record of what it did, for Settings' "Report a problem".
 * @param networkReturns How many times the phone got its connection back; a page that failed to load tries again
 *   when it changes.
 * @param settings Where the screens keep their own small saved values (recent searches).
 * @param workContext Added to every coroutine the screens start; tests pass a test dispatcher so that waiting is
 *   virtual.
 */
@Composable
fun BoomApp(
    services: AppServices,
    playerBar: @Composable () -> Unit,
    onOpenEqualizer: () -> Unit,
    onSignIn: (profile: String?) -> Unit,
    modifier: Modifier = Modifier,
    start: BrowseStart = BrowseStart.Tab(BrowseTab.HOME),
    controller: BoomAppController = remember { BoomAppController() },
    onWillOpen: () -> Unit = {},
    onReportProblem: () -> Unit = {},
    networkReturns: Int = 0,
    settings: KeyValueStore = remember { InMemoryKeyValueStore() },
    workContext: CoroutineContext = EmptyCoroutineContext,
) {
    val scope = rememberCoroutineScope { workContext }
    val router = remember { ShellRouter(start, scope) }
    val collector = remember { DownloadCollector(scope) }
    var sheet by remember { mutableStateOf<AppSheet>(AppSheet.None) }
    controller.router = router

    val navigator = remember(services, router) {
        BoomNavigator(
            open = { endpoint ->
                onWillOpen()
                router.push(BrowseRoute.Page(endpoint))
            },
            openEqualizer = { onOpenEqualizer() },
            addToPlaylist = { songs ->
                if (services.session.isSignedIn) sheet = AppSheet.SaveToPlaylist(songs) else onSignIn(null)
            },
            signIn = { onSignIn(null) },
            addAccount = { services.session.beginAddingAccount()?.let(onSignIn) },
            reportProblem = { onReportProblem() },
        )
    }

    // A page asked for from outside the frame.
    val pending = controller.pendingPage
    LaunchedEffect(pending) {
        if (pending != null) {
            controller.pendingPage = null
            navigator.open(pending)
        }
    }

    // "Save to playlist" asked for from outside the frame (the full player's menu).
    val pendingSave = controller.pendingSave
    LaunchedEffect(pendingSave) {
        if (pendingSave != null) {
            controller.pendingSave = null
            navigator.addToPlaylist(pendingSave)
        }
    }

    BoomTheme {
        CompositionLocalProvider(
            LocalAppServices provides services,
            LocalBoomNavigator provides navigator,
            LocalAppScope provides scope,
            LocalDownloadCollector provides collector,
            LocalSettingsStore provides settings,
            LocalNetworkReturns provides networkReturns,
        ) {
            BrowseShell(router, playerBar, modifier)
            when (val shown = sheet) {
                AppSheet.None -> Unit
                is AppSheet.SaveToPlaylist -> SaveToPlaylistSheet(shown.songs) { sheet = AppSheet.None }
            }
            // Says when an album or playlist could not be collected for downloading, or was cut at the song limit: the
            // download may have been started from a menu whose screen is gone.
            collector.notice?.let { notice ->
                AlertDialog(
                    onDismissRequest = { collector.notice = null },
                    confirmButton = { TextButton({ collector.notice = null }) { Text("OK", color = BoomColor.accent) } },
                    title = { Text(notice.title, style = BoomFont.sectionTitle) },
                    text = { Text(notice.message, style = BoomFont.itemSubtitle) },
                    containerColor = BoomColor.surfaceRaised,
                    modifier = Modifier.testTag("notice"),
                )
            }
        }
    }
}
