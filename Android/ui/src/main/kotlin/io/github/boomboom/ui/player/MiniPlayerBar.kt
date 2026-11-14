package io.github.boomboom.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.PlaybackClock
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The bar above the tab bar while a song is loaded: cover, title, artist, play/pause (with a spinning ring while the
 * song is on its way, Retry after a failure), next, and a thin progress line along its top. A tap or a swipe up opens
 * the full player; a sideways swipe skips to the next song or back.
 *
 * Meant for [io.github.boomboom.ui.BoomApp]'s `playerBar` slot, which gives it its height.
 */
@Composable
fun MiniPlayerBar(services: AppServices, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val player = services.player
    val state by player.state.collectAsState()
    val latestExpand by rememberUpdatedState(onExpand)
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxWidth()
            .height(BoomMetrics.miniPlayerHeight)
            .background(BoomColor.surface)
            .testTag("miniplayer"),
    ) {
        val song = state.current ?: return@Box
        val failure = state.status.failureMessage
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(player) {
                        // Sideways: the song follows the finger and skips when let go far enough; upward: opens.
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var total = Offset.Zero
                            var axis: OverlayModel.Axis? = null
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    val velocity = tracker.calculateVelocity()
                                    val dx = total.x
                                    val dy = total.y
                                    when (axis) {
                                        OverlayModel.Axis.VERTICAL -> if (dy < -40.dp.toPx()) latestExpand()
                                        OverlayModel.Axis.HORIZONTAL -> when {
                                            dx < -70.dp.toPx() || velocity.x < -1200f -> if (player.hasNext) player.next()
                                            dx > 70.dp.toPx() || velocity.x > 1200f -> player.previous()
                                        }
                                        null -> Unit
                                    }
                                    if (axis != null) change.consume()
                                    break
                                }
                                if (change.isConsumed) break
                                total += change.positionChange()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (axis == null) {
                                    if (total.getDistance() < slop) continue
                                    axis = if (abs(total.x) > abs(total.y)) OverlayModel.Axis.HORIZONTAL else OverlayModel.Axis.VERTICAL
                                }
                                change.consume()
                                if (axis == OverlayModel.Axis.HORIZONTAL) scope.launch { swipe.snapTo(total.x * 0.9f) }
                            }
                            // Springs back by itself when the finger lifts or the system takes the touch away.
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.75f)) }
                        }
                    }
                    .clickable(role = Role.Button, onClickLabel = "Opens the player") { latestExpand() }
                    .semantics {
                        contentDescription = "${song.title}, ${song.artistLine}"
                        customActions = listOf(
                            CustomAccessibilityAction("Next song") { player.next(); true },
                            CustomAccessibilityAction("Previous song") { player.previous(); true },
                        )
                    }
                    .testTag("miniplayer.song")
                    .graphicsLayer {
                        translationX = swipe.value
                        alpha = 1f - minOf(abs(swipe.value) / 220.dp.toPx(), 0.6f)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkView(song.artwork, width = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MarqueeText(song.title, style = BoomFont.itemTitle, speed = 26f, modifier = Modifier.testTag("miniplayer.title"))
                    Text(
                        failure ?: song.artistLine, style = BoomFont.itemSubtitle,
                        color = if (failure != null) BoomColor.accent else BoomColor.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MiniPlayButton(state.status, state.isPlayingOrAboutTo) { player.togglePlayPause() }
            PlayerIconButton(
                Icons.Filled.SkipNext, "Next", { player.next() }, Modifier.testTag("miniplayer.next"),
                iconSize = 28.dp, enabled = state.hasNext,
            )
        }
        MiniProgressLine(player.clock)
    }
}

@Composable
private fun MiniPlayButton(status: PlaybackStatus, isPlayingOrAboutTo: Boolean, onClick: () -> Unit) {
    val isFailed = status is PlaybackStatus.Failed
    Box(
        Modifier
            .size(BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (isFailed) "Retry" else if (isPlayingOrAboutTo) "Pause" else "Play"
                // The spinner around the glyph, in words: the song is on its way, not yet heard.
                if (status.isWaiting) stateDescription = "Loading"
            }
            .testTag("miniplayer.playPause"),
        contentAlignment = Alignment.Center,
    ) {
        if (status.isWaiting) SpinnerRing(Modifier.size(36.dp), lineWidth = 2.5.dp)
        Icon(
            when {
                isFailed -> Icons.Filled.Refresh
                isPlayingOrAboutTo -> Icons.Filled.Pause
                else -> Icons.Filled.PlayArrow
            },
            contentDescription = null, tint = BoomColor.textPrimary, modifier = Modifier.size(28.dp),
        )
    }
}

/** The 2 dp line along the bar's top edge. The only part of the bar that follows the clock, and only while drawing. */
@Composable
private fun MiniProgressLine(clock: PlaybackClock) {
    val time = clock.state.collectAsState()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .testTag("miniplayer.progress"),
    ) {
        drawRect(BoomColor.separator)
        drawRect(BoomColor.accent, size = Size(size.width * time.value.progress.toFloat(), size.height))
    }
}
