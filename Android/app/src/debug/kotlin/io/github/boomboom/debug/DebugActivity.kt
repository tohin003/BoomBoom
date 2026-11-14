package io.github.boomboom.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.Song
import io.github.boomboom.runtime.BoomApplication
import io.github.boomboom.runtime.BoomRuntime
import io.github.boomboom.runtime.Diagnostics
import io.github.boomboom.web.SignInActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The milestone's only screen, and the launcher for now: search, tap a song to play it with its radio, the transport,
 * the stream's real format, the equaliser's switch and a few presets, and the hidden tab's state. Plain on purpose:
 * the real screens come from the `:ui` module later. It also takes the diagnostics launch extras (see [Diagnostics]).
 */
class DebugActivity : ComponentActivity() {
    private val runtime: BoomRuntime get() = (application as BoomApplication).runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) Diagnostics.take(intent, runtime)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFD60A), onPrimary = Color.Black)) {
                Surface(Modifier.fillMaxSize()) { DebugScreen(runtime) { profile -> startActivity(SignInActivity.intent(this, profile)) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Diagnostics.take(intent, runtime)
    }
}

@Composable
private fun DebugScreen(runtime: BoomRuntime, signIn: (String) -> Unit) {
    val player = runtime.player
    val state by player.state.collectAsState()
    val clock by player.clock.state.collectAsState()
    val equalizer by runtime.services.equalizer.state.collectAsState()
    val session by runtime.services.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var tab by remember { mutableStateOf("") }

    // The tab's phase and the fetch are not flows: looked at twice a second.
    LaunchedEffect(Unit) {
        while (true) {
            val fetching = runtime.broker.fetchingVideoId
            tab = runtime.browser.phase.label + (fetching?.let { " · fetching $it" } ?: "")
            delay(500)
        }
    }

    fun search() {
        val words = query.trim()
        if (words.isEmpty()) return
        searchNote = "Searching…"
        scope.launch {
            try {
                val found = runtime.api.search(words, null)
                results = found.shelves.flatMap { it.items }.mapNotNull { (it as? MusicItem.OfSong)?.song }.distinctBy { it.videoId }
                searchNote = if (results.isEmpty()) "No songs found." else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MusicAPIError) {
                searchNote = "Search failed: ${failure.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BoomBoom (debug)", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search songs") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Button(onClick = { search() }) { Text("Search") }
        }
        searchNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.videoId }) { song ->
                Column(Modifier.fillMaxWidth().clickable { player.play(song) }.padding(vertical = 8.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }

        val song = state.current
        Text(song?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song?.artistLine ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("${describe(state.status)} · tab $tab", style = MaterialTheme.typography.bodySmall)
        Text(listOfNotNull(state.audioFormat, state.audioOutput).joinToString("  ·  ").ifEmpty { "-" }, style = MaterialTheme.typography.bodySmall)
        val duration = clock.duration.toFloat()
        Slider(
            value = (dragging ?: clock.position.toFloat()).coerceIn(0f, maxOf(duration, 0.001f)),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seek(it.toDouble()) }
                dragging = null
            },
            valueRange = 0f..maxOf(duration, 0.001f),
            enabled = song != null && duration > 0,
        )
        Text(
            "${time(clock.position)} / ${time(clock.duration)} · fetched ${(clock.bufferedFraction * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { player.previous() }, enabled = song != null) { Text("Previous") }
            Button(onClick = { player.togglePlayPause() }, enabled = song != null) {
                Text(if (state.isPlayingOrAboutTo) "Pause" else "Play")
            }
            Button(onClick = { player.next() }, enabled = state.hasNext) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = equalizer.settings.isEnabled, onCheckedChange = { runtime.services.equalizer.setEnabled(it) })
            Text("Equaliser")
            for (id in listOf("flat", "bass-boost", "vocal")) {
                val preset = equalizer.presets.firstOrNull { it.id == id } ?: continue
                FilterChip(
                    selected = equalizer.selectedPresetId == id,
                    onClick = { runtime.services.equalizer.select(preset) },
                    label = { Text(preset.name) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (session.isSignedIn) "Signed in" else "Signed out", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (session.isSignedIn) {
                Button(onClick = { scope.launch { runtime.services.session.signOut() } }) { Text("Sign out") }
            } else {
                Button(onClick = { runtime.services.session.beginAddingAccount()?.let(signIn) }) { Text("Sign in") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun describe(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.Idle -> "Idle"
    PlaybackStatus.Loading -> "Loading"
    PlaybackStatus.Playing -> "Playing"
    PlaybackStatus.Paused -> "Paused"
    PlaybackStatus.Buffering -> "Buffering"
    is PlaybackStatus.Failed -> "Failed: ${status.message}"
}

private fun time(seconds: Double): String {
    val whole = maxOf(0, seconds.toInt())
    return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60)
}
