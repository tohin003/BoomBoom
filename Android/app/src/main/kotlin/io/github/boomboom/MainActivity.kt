package io.github.boomboom

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.runtime.BoomApplication
import io.github.boomboom.runtime.BoomRuntime
import io.github.boomboom.runtime.BoomTrace
import io.github.boomboom.runtime.TestHooks
import io.github.boomboom.runtime.ProblemReport
import io.github.boomboom.ui.BoomApp
import io.github.boomboom.ui.BoomAppController
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomNavigator
import io.github.boomboom.ui.design.BoomTheme
import io.github.boomboom.ui.player.MiniPlayerBar
import io.github.boomboom.ui.player.NowPlayingOverlay
import io.github.boomboom.ui.sound.SoundScreen
import io.github.boomboom.web.SignInActivity
import kotlinx.coroutines.flow.first

/**
 * The app's one window: the browsing frame with its tabs, the mini player in it, the full player over it, and the
 * Sound screen over everything. The music itself does not live here: the runtime belongs to the process, so this
 * screen can be closed, turned and made again while a song plays on.
 */
class MainActivity : ComponentActivity() {
    /** Set when the listener comes in through the playback notification: the full player opens. */
    private var showsPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bars with light icons whatever the phone's own theme: the app is always dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The phone's volume buttons mean the music's volume here, also before the first song plays.
        volumeControlStream = AudioManager.STREAM_MUSIC
        val app = application as BoomApplication
        val failure = app.startFailure
        if (failure != null) {
            setContent { BoomTheme { StartNotice(failure, ::openWebViewInStore) { ProblemReport.share(this) } } }
            return
        }
        val runtime = app.runtime
        if (savedInstanceState == null) take(intent, runtime)
        setContent {
            BoomRoot(
                runtime = runtime,
                showsPlayer = showsPlayer,
                onPlayerShown = { showsPlayer = false },
                signIn = { profile -> startActivity(SignInActivity.intent(this, profile)) },
                askForNotifications = ::askForNotifications,
                reportProblem = { ProblemReport.share(this) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as BoomApplication
        if (app.startFailure == null) take(intent, app.runtime)
    }

    private fun take(intent: Intent?, runtime: BoomRuntime) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_PLAYER, false) == true && runtime.player.current != null) showsPlayer = true
        // The command-line test hooks (play this, run that in the hidden tab): the debug build's only. In a release
        // build this call is an empty stand-in, as this window can be opened by any app on the phone.
        TestHooks.take(intent, runtime)
    }

    /**
     * Android 13 and later ask before an app may show notifications. Asked once, when the first song starts: that is
     * when the notification with the controls would appear. The music plays either way.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun openWebViewInStore() {
        val id = (application as BoomApplication).browserPackage ?: "com.google.android.webview"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (noStore: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
            } catch (noBrowser: ActivityNotFoundException) {
                BoomTrace.note("no app to open the store page with")
            }
        }
    }

    companion object {
        private const val EXTRA_SHOW_PLAYER = "showPlayer"

        /** Opens the app on the full player: what a tap on the playback notification does. */
        fun playerIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SHOW_PLAYER, true)
    }
}

private const val ASKED_NOTIFICATIONS_KEY = "app.askedForNotifications"

@Composable
private fun BoomRoot(
    runtime: BoomRuntime,
    showsPlayer: Boolean,
    onPlayerShown: () -> Unit,
    signIn: (profile: String) -> Unit,
    askForNotifications: () -> Unit,
    reportProblem: () -> Unit,
) {
    val services = runtime.services
    val controller = remember { BoomAppController() }
    val networkReturns by runtime.networkReturns.collectAsState()
    var isPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var isSoundOpen by rememberSaveable { mutableStateOf(false) }

    // Null means the account the tab is on now; otherwise a profile made for another account.
    val signInTo = { profile: String? -> signIn(profile ?: runtime.browser.activeProfileId) }

    LaunchedEffect(showsPlayer) {
        if (showsPlayer) {
            isPlayerOpen = true
            onPlayerShown()
        }
    }
    LaunchedEffect(Unit) {
        if (runtime.storage.getString(ASKED_NOTIFICATIONS_KEY) != null) return@LaunchedEffect
        services.player.state.first { it.status == PlaybackStatus.Playing }
        runtime.storage.putString(ASKED_NOTIFICATIONS_KEY, "1")
        askForNotifications()
    }

    val playerNavigator = remember(controller) {
        BoomNavigator(
            open = { endpoint -> controller.open(endpoint) },
            openEqualizer = { isSoundOpen = true },
            addToPlaylist = { songs -> controller.saveToPlaylist(songs) },
            signIn = { signInTo(null) },
            addAccount = { services.session.beginAddingAccount()?.let(signIn) },
            reportProblem = reportProblem,
        )
    }

    // What lies under a screen that covers it is not there for a screen reader either: it would read out, and let a
    // listener using one press, things they cannot see.
    val covered = Modifier.clearAndSetSemantics { }
    Box(Modifier.fillMaxSize().background(BoomColor.background)) {
        BoomApp(
            services = services,
            playerBar = { MiniPlayerBar(services, onExpand = { isPlayerOpen = true }) },
            onOpenEqualizer = { isSoundOpen = true },
            onSignIn = signInTo,
            modifier = if (isPlayerOpen || isSoundOpen) covered else Modifier,
            controller = controller,
            onWillOpen = { isPlayerOpen = false },
            onReportProblem = reportProblem,
            networkReturns = networkReturns,
            settings = runtime.storage,
        )
        NowPlayingOverlay(
            services = services,
            isExpanded = isPlayerOpen,
            onCollapse = { isPlayerOpen = false },
            navigator = playerNavigator,
            modifier = if (isSoundOpen) covered else Modifier,
        )
        if (isSoundOpen) {
            BackHandler { isSoundOpen = false }
            BoomTheme {
                SoundScreen(services, onDone = { isSoundOpen = false }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Shown instead of the app when it cannot run on this phone as it is: says what to do about it. */
@Composable
private fun StartNotice(message: String, onUpdate: () -> Unit, onReport: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BoomColor.background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp).testTag("startNotice"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("BoomBoom can't start yet", style = BoomFont.sectionTitle, color = Color.White, textAlign = TextAlign.Center)
            Text(message, style = BoomFont.itemSubtitle, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = BoomColor.accent, contentColor = Color.Black),
            ) { Text("Open the Play Store") }
            TextButton(onClick = onReport) { Text("Report a problem", color = Color.White.copy(alpha = 0.75f)) }
        }
    }
}
