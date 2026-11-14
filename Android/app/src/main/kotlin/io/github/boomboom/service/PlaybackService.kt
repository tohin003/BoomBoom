package io.github.boomboom.service

import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.boomboom.MainActivity
import io.github.boomboom.R
import io.github.boomboom.runtime.BoomApplication
import io.github.boomboom.runtime.BoomRuntime
import io.github.boomboom.runtime.BoomTrace

/**
 * Keeps the process alive and playing with every screen closed: a Media3 session over the player, from which the
 * system draws the notification and the lock-screen controls, and through which headset, Bluetooth and car buttons
 * arrive. Media3 puts the service in the foreground while music plays (and for a while after a pause, so a headset
 * button can start it again); the service also stays in the foreground while the hidden tab is fetching a song.
 *
 * Removing the app from recents leaves it playing (Media3's default: it stops only when nothing plays).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var runtime: BoomRuntime

    override fun onCreate() {
        super.onCreate()
        val app = application as BoomApplication
        if (app.startFailure != null) {
            // The app cannot run on this phone as it is (see BoomApplication): there is nothing to play.
            stopSelf()
            return
        }
        runtime = app.runtime
        // The app's own mark in the status bar and on the notification, in place of Media3's stand-in.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat_boom) },
        )
        val player = StorePlayer(runtime.player, runtime.scope)
        // A tap on the notification opens the app on the full player.
        val open = PendingIntent.getActivity(
            this, 0, MainActivity.playerIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val made = MediaSession.Builder(this, player).setSessionActivity(open).build()
        session = made
        // Media3 looks after the notification (and the foreground) only for sessions added to the service. One is
        // added when a Media3 controller connects; here the service is started by the app itself, with none.
        addSession(made)
        runtime.onFetchingChange = {
            val session = session
            // A fetch under way with a song queued: in the foreground, so it is not stopped with the screen off.
            if (session != null && runtime.broker.isFetching && session.player.mediaItemCount > 0) {
                onUpdateNotification(session, true)
            }
        }
        BoomTrace.note("playback service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!::runtime.isInitialized) return
        val fetching = runtime.broker.isFetching && session.player.mediaItemCount > 0
        super.onUpdateNotification(session, startInForegroundRequired || fetching)
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) {
            BoomTrace.note("playback service destroyed")
            runtime.onFetchingChange = null
            session?.run {
                player.release()
                release()
            }
            session = null
            runtime.serviceStopped()
        }
        super.onDestroy()
    }
}
