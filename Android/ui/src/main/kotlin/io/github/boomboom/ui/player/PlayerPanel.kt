package io.github.boomboom.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.Shelf
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the "Lyrics" and "Related" tabs fetched for a song, kept while the player is open so switching tabs does not
 * fetch again.
 */
internal class PlayerExtras {
    /** One tab's fetch and the song (video id) it is for. Changed only in [load]. */
    sealed class Load<out T> {
        data object Idle : Load<Nothing>()

        /** [ticket] tells this request from a later one for the same tab, which replaces it. */
        data class Loading(val song: String?, val ticket: Int) : Load<Nothing>()
        data class Loaded<T>(val song: String?, val value: T) : Load<T>()
        data class Failed(val song: String?) : Load<Nothing>()

        /**
         * What the tab shows while [song] is playing. Content for another song is never shown: it reads as loading
         * until this song's own arrives.
         */
        fun shown(song: String?): Shown<T> = when {
            this is Loaded && this.song == song -> Shown.Ready(value)
            this is Failed && this.song == song -> Shown.Failed
            else -> Shown.Loading
        }
    }

    sealed class Shown<out T> {
        data object Loading : Shown<Nothing>()
        data class Ready<T>(val value: T) : Shown<T>()
        data object Failed : Shown<Nothing>()
    }

    private var lyricsLoad by mutableStateOf<Load<Lyrics?>>(Load.Idle)
    private var relatedLoad by mutableStateOf<Load<List<Shelf>>>(Load.Idle)
    private var tickets = 0

    fun lyrics(song: String?): Shown<Lyrics?> = lyricsLoad.shown(song)
    fun related(song: String?): Shown<List<Shelf>> = relatedLoad.shown(song)

    /** Fetches the current song's lyrics unless they are already here. Also the "Try again" action. */
    suspend fun loadLyrics(player: PlayerStore) {
        load(player.current?.videoId, { lyricsLoad }, { lyricsLoad = it }) { player.lyrics() }
    }

    suspend fun loadRelated(player: PlayerStore) {
        load(player.current?.videoId, { relatedLoad }, { relatedLoad = it }) { player.related() }
    }

    private suspend fun <T> load(song: String?, read: () -> Load<T>, write: (Load<T>) -> Unit, fetch: suspend () -> T) {
        val before = read()
        if (before is Load.Loaded && before.song == song) return
        tickets += 1
        val ticket = tickets
        write(Load.Loading(song, ticket))
        val outcome: Load<T> = try {
            Load.Loaded(song, fetch())
        } catch (cancelled: CancellationException) {
            // Given up because the tab closed or the song changed, not failed: the next look fetches again.
            val now = read()
            if (now is Load.Loading && now.ticket == ticket) write(Load.Idle)
            throw cancelled
        } catch (failure: Exception) {
            Load.Failed(song)
        }
        // Only the latest request may land, so a slow answer (or failure) for an earlier song or try is dropped.
        val now = read()
        if (now is Load.Loading && now.ticket == ticket) write(outcome)
    }
}

/** The pulled-up panel over the player with "Up next", "Lyrics" and "Related". */
@Composable
internal fun PanelHost(extras: PlayerExtras) {
    val model = LocalOverlayModel.current
    val panel = model.panel
    // The last tab stays drawn while the panel slides away.
    var lastTab by remember { mutableStateOf(NowPlayingPanel.UP_NEXT) }
    if (panel != null) lastTab = panel
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(panel) { if (panel != null) drag.snapTo(0f) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(panel != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - (drag.value / 1200f).coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { model.showPanel(null) }
                    .testTag("panel.scrim"),
            )
        }
        AnimatedVisibility(
            panel != null,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it },
        ) {
            PanelSheet(
                lastTab, extras,
                headerDrag = Modifier.pointerInput(model) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag.value > 120.dp.toPx()) {
                                model.showPanel(null)
                            } else {
                                scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f) } },
                    ) { change, amount ->
                        change.consume()
                        val next = drag.value + amount
                        scope.launch { drag.snapTo(if (next >= 0) next else -(-next).pow(0.6f)) }
                    }
                },
                modifier = Modifier.offset { IntOffset(0, drag.value.coerceAtLeast(0f).roundToInt()) },
            )
        }
    }
}

@Composable
private fun PanelSheet(tab: NowPlayingPanel, extras: PlayerExtras, headerDrag: Modifier, modifier: Modifier = Modifier) {
    val model = LocalOverlayModel.current
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
            .shadow(20.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(BoomColor.surface)
            // Touches on the sheet stay on the sheet: a tap does not reach the dimming behind it (which closes the
            // panel), and a drag that nothing inside took does not move the player.
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = Offset.Zero
                    var owned = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!owned) {
                            // Something inside (a list, a row's swipe, a handle) took it: theirs.
                            if (change.isConsumed) break
                            moved += change.positionChange()
                            if (moved.getDistance() < slop) continue
                            owned = true
                        }
                        change.consume()
                    }
                }
            }
            .testTag("panel"),
    ) {
        Column(headerDrag.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(36.dp, 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.3f)),
                )
            }
            PanelNowPlayingRow(Modifier.padding(horizontal = BoomMetrics.margin, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = BoomMetrics.margin)) {
                for (item in NowPlayingPanel.entries) {
                    val isSelected = item == tab
                    val underline by animateDpAsState(if (isSelected) 44.dp else 0.dp, label = "underline")
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Tab) { model.showPanel(item) }
                            .semantics { selected = isSelected }
                            .testTag("panel.tab.${item.tag}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                            color = if (isSelected) BoomColor.textPrimary else BoomColor.textTertiary,
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .size(underline, 3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(BoomColor.accent),
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = BoomColor.separator)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            when (tab) {
                NowPlayingPanel.UP_NEXT -> UpNextView()
                NowPlayingPanel.LYRICS -> LyricsPanel(extras)
                NowPlayingPanel.RELATED -> RelatedPanel(extras)
            }
        }
    }
}

/** The song and its controls at the top of the panel, so the player stays in reach while the panel covers it. */
@Composable
private fun PanelNowPlayingRow(modifier: Modifier = Modifier) {
    val player = LocalAppServices.current.player
    val state by player.state.collectAsState()
    val song = state.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArtworkView(song?.artwork ?: Artwork.EMPTY, width = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "", style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song?.artistLine ?: "", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerIconButton(
            if (state.isPlayingOrAboutTo) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (state.isPlayingOrAboutTo) "Pause" else "Play", { player.togglePlayPause() }, Modifier.testTag("panel.playPause"),
        )
        PlayerIconButton(Icons.Filled.SkipNext, "Next", { player.next() }, enabled = state.hasNext)
    }
}
