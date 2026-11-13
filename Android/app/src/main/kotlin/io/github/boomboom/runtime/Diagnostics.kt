package io.github.boomboom.runtime

import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.models.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * For checking the engine from the command line, like the iPhone app's launch arguments:
 *
 *     adb shell am start -n io.github.boomboom.android/io.github.boomboom.debug.DebugActivity \
 *         --es BoomPlay <videoId> --ez BoomTrace true
 *
 *   `BoomPlay <videoId>`    plays that song as soon as the app is up (with its radio queue)
 *   `BoomSeek <seconds>`    jumps in the current song
 *   `BoomCommand <c>`       next, previous, play or pause
 *   `BoomTrace true`        logs one `BOOM t=… status=… pos=… buffered=… format=… tab=…` line a second
 *   `BoomEval64 <base64>`   evaluates that JavaScript expression in the hidden tab every ten seconds, from
 *                           `BoomEvalAfter` seconds on (12 by default), and logs the answer ("BOOM eval")
 *
 * Lines start with "BOOM" (logcat tag `BOOM`) so they are easy to pick out.
 */
object Diagnostics {
    private var trace: Job? = null
    private var evaluation: Job? = null

    fun take(intent: Intent?, runtime: BoomRuntime) {
        val extras = intent?.extras ?: return
        extras.getString("BoomPlay")?.takeIf { it.isNotBlank() }?.let { videoId ->
            BoomTrace.note("diagnostics: play $videoId")
            runtime.player.play(Song(videoId = videoId, title = "Diagnostics $videoId"))
        }
        if (extras.getBoolean("BoomTrace", false)) startTrace(runtime)
        extras.getString("BoomSeek")?.toDoubleOrNull()?.let { seconds ->
            BoomTrace.note("diagnostics: seek to $seconds")
            runtime.player.seek(seconds)
        }
        when (extras.getString("BoomCommand")) {
            "next" -> runtime.player.next()
            "previous" -> runtime.player.previous()
            "play" -> runtime.player.resume()
            "pause" -> runtime.player.pause()
            null -> Unit
            else -> BoomTrace.note("diagnostics: unknown command")
        }
        extras.getString("BoomEval64")?.let { encoded ->
            val expression = try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (failure: IllegalArgumentException) {
                return@let
            }
            val wait = extras.getString("BoomEvalAfter")?.toDoubleOrNull() ?: 12.0
            evaluation?.cancel()
            evaluation = runtime.scope.launch {
                delay((wait * 1000).toLong())
                while (isActive) {
                    val answer = runtime.browser.inspect(expression)
                    // In pieces: logcat cuts long lines.
                    answer.chunked(900).forEachIndexed { part, text -> BoomTrace.console("BOOM eval[$part] $text") }
                    delay(10_000)
                }
            }
        }
    }

    private fun startTrace(runtime: BoomRuntime) {
        if (trace != null) return
        val started = SystemClock.elapsedRealtime()
        trace = runtime.scope.launch {
            while (isActive) {
                delay(1000)
                val player = runtime.player
                val clock = player.clock.state.value
                val output = io.github.boomboom.engine.Engine.outputInfo
                val format = runtime.engine.currentFormat?.let { "${it.codecName} ${it.bitrate / 1000} kbps (itag ${it.itag})" } ?: "-"
                BoomTrace.console(
                    String.format(
                        Locale.ROOT,
                        "BOOM t=%.0f status=%s song=%s pos=%.1f/%.1f buffered=%.0f%% queue=%d format=%s heard=%s tab=%s fetching=%s frames=%d sounding=%d xruns=%d",
                        (SystemClock.elapsedRealtime() - started) / 1000.0, describe(player.status),
                        player.current?.videoId ?: "-", clock.position, clock.duration, clock.bufferedFraction * 100,
                        player.queue.size, format, player.audioFormat ?: "-", runtime.browser.phase.label,
                        runtime.broker.fetchingVideoId ?: "-", output.frames, output.soundingFrames, output.xruns,
                    ),
                )
            }
        }
    }

    private fun describe(status: PlaybackStatus): String = when (status) {
        PlaybackStatus.Idle -> "idle"
        PlaybackStatus.Loading -> "loading"
        PlaybackStatus.Playing -> "playing"
        PlaybackStatus.Paused -> "paused"
        PlaybackStatus.Buffering -> "buffering"
        is PlaybackStatus.Failed -> "failed(${status.message})"
    }
}
