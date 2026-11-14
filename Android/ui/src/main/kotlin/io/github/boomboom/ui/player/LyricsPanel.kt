package io.github.boomboom.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.ClockState
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.models.Lyrics
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The "Lyrics" tab: plain lyrics as large text, or timed lyrics that follow the song. */
@Composable
internal fun LyricsPanel(extras: PlayerExtras) {
    val player = LocalAppServices.current.player
    val state by player.state.collectAsState()
    val song = state.current?.videoId
    val scope = rememberCoroutineScope()
    LaunchedEffect(song) { extras.loadLyrics(player) }
    Box(Modifier.fillMaxSize().testTag("lyrics")) {
        when (val shown = extras.lyrics(song)) {
            PlayerExtras.Shown.Loading -> CircularProgressIndicator(
                color = BoomColor.textSecondary, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center),
            )
            PlayerExtras.Shown.Failed -> PanelMessage(Icons.Outlined.WifiOff, "Couldn't load the lyrics") {
                scope.launch { extras.loadLyrics(player) }
            }
            is PlayerExtras.Shown.Ready -> {
                val lyrics = shown.value
                when {
                    lyrics == null || lyrics.lines.isEmpty() -> PanelMessage(Icons.Outlined.FormatQuote, "No lyrics for this song")
                    lyrics.isTimed -> TimedLyrics(lyrics, player)
                    else -> PlainLyrics(lyrics)
                }
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BoomMetrics.margin + 4.dp, vertical = 20.dp)
            .testTag("lyrics.plain"),
    ) {
        SelectionContainer {
            Text(
                lyrics.lines.joinToString("\n") { it.text }, color = BoomColor.textPrimary,
                fontSize = 20.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SourceLine(lyrics.source)
    }
}

@Composable
private fun SourceLine(source: String?) {
    if (source == null) return
    Text(source, style = BoomFont.caption, color = BoomColor.textTertiary, modifier = Modifier.padding(top = 28.dp, bottom = 40.dp))
}

/** Whether the list keeps the current line in view. Changed only through `move`. */
private sealed class Follow {
    data object Following : Follow()

    /** The listener's finger, or the fling it left, is moving the list. */
    data object ScrolledByHand : Follow()

    /** The listener stopped scrolling; following starts again a few seconds later. */
    data class Resting(val serial: Int) : Follow()
}

/** The line the clock is in: the last that has started (lines are in time order). */
internal fun activeLine(position: Double, lines: List<Lyrics.Line>): Int? {
    var low = 0
    var high = lines.size - 1
    var found: Int? = null
    while (low <= high) {
        val middle = (low + high) / 2
        val start = lines[middle].start
        if (start == null) {
            // An untimed line among timed ones: search linearly.
            return lines.indexOfLast { (it.start ?: Double.POSITIVE_INFINITY) <= position + 0.15 }.takeIf { it >= 0 }
        }
        if (start <= position + 0.15) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

/**
 * Timed lyrics: the current line lit and held in the upper third; a tap on a line plays from there. Scrolling by hand
 * stops the following, until the list has rested for a few seconds.
 */
@Composable
private fun TimedLyrics(lyrics: Lyrics, player: PlayerStore) {
    val listState = rememberLazyListState()
    val clock: State<ClockState> = player.clock.state.collectAsState()
    // Read the clock in a derived state, so the list recomposes when the line changes, not ten times a second.
    val active by remember(lyrics) { derivedStateOf { activeLine(clock.value.position, lyrics.lines) } }
    var follow by remember { mutableStateOf<Follow>(Follow.Following) }
    var rests by remember { mutableIntStateOf(0) }

    fun move(next: Follow) {
        if (follow != next) follow = next
    }

    // A drag on the list, and the fling it leaves, are the listener's: they stop the following.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) move(Follow.ScrolledByHand)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && follow == Follow.ScrolledByHand) {
                rests += 1
                move(Follow.Resting(rests))
            }
        }
    }
    val resting = follow as? Follow.Resting
    LaunchedEffect(resting) {
        if (resting == null) return@LaunchedEffect
        delay(3000)
        if (follow == resting) move(Follow.Following)
    }
    val upperThird = with(LocalDensity.current) { 140.dp.toPx() }.toInt()
    LaunchedEffect(active, follow) {
        val line = active ?: return@LaunchedEffect
        if (follow != Follow.Following) return@LaunchedEffect
        listState.animateScrollToItem(line, -upperThird)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("lyrics.timed"),
        contentPadding = PaddingValues(start = BoomMetrics.margin + 4.dp, end = BoomMetrics.margin + 4.dp, top = 24.dp, bottom = 240.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == active
            val isPast = active?.let { index < it } ?: false
            val colour by animateColorAsState(
                when {
                    isActive -> BoomColor.textPrimary
                    isPast -> BoomColor.textTertiary
                    else -> Color.White.copy(alpha = 0.38f)
                },
                tween(250), label = "line",
            )
            val scale by animateFloatAsState(if (isActive) 1f else 0.96f, tween(250), label = "scale")
            Text(
                line.text.ifEmpty { "♪" }, color = colour, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, enabled = line.start != null) {
                        val start = line.start ?: return@clickable
                        move(Follow.Following)
                        player.seek(start)
                        if (player.status == PlaybackStatus.Paused) player.resume()
                    }
                    .semantics { selected = isActive }
                    .testTag(if (isActive) "lyrics.line.active" else "lyrics.line"),
            )
        }
        item { SourceLine(lyrics.source) }
    }
}
