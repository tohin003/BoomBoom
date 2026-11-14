package io.github.boomboom.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.PlayerState
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.RepeatMode
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.boomTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// Cover

/** One cover in the carousel: the current song at 0, the one before at -1, the one after at +1. */
private data class Slot(val id: UUID, val song: Song, val position: Int)

/** What a touch on the cover is doing. Changed only through `move`. */
private sealed class Swipe {
    data object Idle : Swipe()

    /** A finger moves the covers sideways. (A drag that starts downward is not the cover's: it moves the player.) */
    data object Dragging : Swipe()

    /** Sliding the rest of the way to a neighbour; that entry plays once it gets there. Touches wait. */
    data class Settling(val onto: UUID) : Swipe()
}

private fun slots(state: PlayerState): List<Slot> {
    val index = state.currentIndex ?: return emptyList()
    val queue = state.queue
    val slots = arrayListOf(Slot(queue[index].id, queue[index].song, 0))
    if (index > 0) slots += Slot(queue[index - 1].id, queue[index - 1].song, -1)
    val following = if (index + 1 < queue.size) queue[index + 1] else if (state.repeatMode == RepeatMode.ALL) queue.first() else null
    if (following != null && slots.none { it.id == following.id }) slots += Slot(following.id, following.song, 1)
    return slots
}

/**
 * The large cover, with the songs before and after it waiting off to the sides: a sideways swipe slides to them, and
 * plays the one it lands on (once, fixed when the finger lifts, so a song that ends during the slide does not make it
 * skip one further). A drag that starts downward is left to the player, which follows it down.
 */
@Composable
internal fun CoverCarousel(player: PlayerStore, side: Dp, modifier: Modifier = Modifier) {
    val state by player.state.collectAsState()
    val slots = slots(state)
    val density = LocalDensity.current
    val sidePx = with(density) { side.toPx() }
    val step = sidePx + with(density) { 28.dp.toPx() }
    val slide = remember { Animatable(0f) }
    var swipe by remember { mutableStateOf<Swipe>(Swipe.Idle) }
    val scope = rememberCoroutineScope()
    val currentSlots by rememberUpdatedState(slots)

    fun move(next: Swipe) {
        if (swipe != next) swipe = next
    }

    fun settle(onto: UUID, target: Float) {
        move(Swipe.Settling(onto))
        scope.launch {
            slide.animateTo(target, tween(280))
            if (player.currentEntryId != onto) player.jump(onto)
            slide.snapTo(0f)
            move(Swipe.Idle)
        }
    }

    Box(
        modifier
            .size(side)
            .testTag("player.cover")
            .pointerInput(player, sidePx, step) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (swipe is Swipe.Settling) return@awaitEachGesture
                    var total = Offset.Zero
                    var owned = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!owned) break
                            val dx = total.x
                            val following = currentSlots.firstOrNull { it.position == 1 }
                            // Straight to the song before, even late into this one: the cover already shows it.
                            val before = currentSlots.firstOrNull { it.position == -1 }
                            when {
                                dx < -sidePx * 0.28f && following != null -> settle(following.id, -step)
                                dx > sidePx * 0.28f && before != null -> settle(before.id, step)
                                else -> {
                                    move(Swipe.Idle)
                                    scope.launch { slide.animateTo(0f, spring(dampingRatio = 0.7f)) }
                                }
                            }
                            break
                        }
                        if (change.isConsumed) break
                        total += change.positionChange()
                        if (!owned) {
                            if (total.getDistance() < slop) continue
                            // Upward or downward: the player's own drag takes it.
                            if (abs(total.y) >= abs(total.x)) break
                            owned = true
                            move(Swipe.Dragging)
                        }
                        change.consume()
                        val hasNeighbour = currentSlots.any { it.position == (if (total.x < 0) 1 else -1) }
                        val target = if (hasNeighbour) total.x else total.x / 3
                        scope.launch { slide.snapTo(target) }
                    }
                    if (owned && swipe == Swipe.Dragging) {
                        // The system took the touch away: back to the middle.
                        move(Swipe.Idle)
                        scope.launch { slide.animateTo(0f) }
                    }
                }
            },
    ) {
        if (slots.isEmpty()) {
            ArtworkView(Artwork.EMPTY, width = side, cornerRadius = BoomMetrics.cardRadius)
        }
        for (slot in slots) {
            Box(
                Modifier
                    .graphicsLayer { translationX = slide.value + slot.position * step }
                    .testTag(if (slot.position == 0) "player.cover.current" else "player.cover.side"),
            ) {
                CoverImage(slot.song, side)
            }
        }
    }
}

/** A square cover, or a video still letterboxed inside the square. */
@Composable
private fun CoverImage(song: Song, side: Dp) {
    val shape = RoundedCornerShape(BoomMetrics.cardRadius)
    Box(
        Modifier
            .size(side)
            .shadow(22.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (song.kind == Song.Kind.VIDEO) {
            ArtworkView(song.artwork, width = side, height = side * 9 / 16, cornerRadius = 0.dp)
        } else {
            ArtworkView(song.artwork, width = side, cornerRadius = BoomMetrics.cardRadius)
        }
    }
}

// Scrubber

/**
 * The thumb's situation. Each case names the queue entry it was for: once another song is playing it no longer
 * applies, so it is neither shown nor sought. Changed only through `move`.
 */
private sealed class Scrub {
    data object Idle : Scrub()

    /** A finger holds the thumb at [fraction]; nothing is sought until it lifts. */
    data class Dragging(val fraction: Double, val entry: UUID) : Scrub()

    /**
     * Lifted and sought. The engine moves a moment later than asked, so the thumb holds [fraction] until the clock
     * reports the new place (or a short while passes) instead of flicking back to where the song was. Not [armed]
     * for the first quarter second: the clock's first report is only the store echoing the asked-for time.
     */
    data class Landing(val fraction: Double, val entry: UUID, val serial: Int, val armed: Boolean) : Scrub()
}

/**
 * The song's position: thin track, buffered part, played part in the accent, and under it the time played, the
 * stream's real format, and the length (a tap shows the time left instead). Dragging previews the time and seeks
 * when the finger lifts. The only part of the page that redraws as time runs.
 */
@Composable
internal fun Scrubber(player: PlayerStore, modifier: Modifier = Modifier) {
    val clock by player.clock.state.collectAsState()
    val state by player.state.collectAsState()
    val currentId = state.currentEntryId
    val duration = clock.duration
    var scrub by remember { mutableStateOf<Scrub>(Scrub.Idle) }
    var landings by remember { mutableIntStateOf(0) }
    var showsRemaining by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(1) }

    fun move(next: Scrub) {
        if (scrub != next) scrub = next
    }

    val held: Double? = when (val now = scrub) {
        is Scrub.Dragging -> if (now.entry == currentId) now.fraction else null
        is Scrub.Landing -> if (now.entry == currentId) now.fraction else null
        Scrub.Idle -> null
    }
    val fraction = held ?: clock.progress
    val isDragging = (scrub as? Scrub.Dragging)?.entry?.let { it == currentId } ?: false

    // A song change ends a landing at once. A drag stays (its finger is still down) but no longer counts.
    LaunchedEffect(currentId) { if (scrub is Scrub.Landing) move(Scrub.Idle) }
    // The landing's two clocks: armed after a quarter second, given up after a second and a half.
    val landing = scrub as? Scrub.Landing
    LaunchedEffect(landing?.serial) {
        val started = landing ?: return@LaunchedEffect
        delay(250)
        if ((scrub as? Scrub.Landing)?.serial == started.serial) move(started.copy(armed = true))
        delay(1250)
        if ((scrub as? Scrub.Landing)?.serial == started.serial) move(Scrub.Idle)
    }
    // Only a report a little after the release, near the target, means the engine got there.
    LaunchedEffect(clock.position, landing?.armed) {
        val now = scrub as? Scrub.Landing ?: return@LaunchedEffect
        if (now.armed && abs(clock.position - now.fraction * duration) < 1.0) move(Scrub.Idle)
    }

    val latestDuration by rememberUpdatedState(duration)
    val latestCurrent by rememberUpdatedState(currentId)
    val trackHeight by animateDpAsState(if (isDragging) 5.dp else 3.dp, label = "track")
    val thumb by animateDpAsState(if (isDragging) 20.dp else 12.dp, label = "thumb")
    val elapsedColour by animateColorAsState(if (isDragging) BoomColor.accent else BoomColor.textSecondary, label = "elapsed")

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { width = maxOf(it.width, 1) }
                .testTag("scrubber")
                .semantics {
                    contentDescription = "Position"
                    stateDescription = "${boomTime(clock.position)} of ${boomTime(duration)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(clock.position.toFloat(), 0f..maxOf(duration, 0.0).toFloat())
                    setProgress { seconds ->
                        player.seek(seconds.toDouble())
                        true
                    }
                }
                .pointerInput(player) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The scrubber owns the finger from touch-down: the player does not move under it.
                        down.consume()
                        val entry = latestCurrent
                        if (latestDuration <= 0 || entry == null) return@awaitEachGesture
                        fun at(x: Float) = (x / width).toDouble().coerceIn(0.0, 1.0)
                        move(Scrub.Dragging(at(down.position.x), entry))
                        var lifted = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                val dragging = scrub as? Scrub.Dragging ?: break
                                if (!change.pressed) {
                                    lifted = true
                                    if (latestDuration <= 0 || dragging.entry != latestCurrent) {
                                        move(Scrub.Idle)
                                        break
                                    }
                                    val target = at(change.position.x)
                                    player.seek(target * latestDuration)
                                    landings += 1
                                    move(Scrub.Landing(target, dragging.entry, landings, armed = false))
                                    break
                                }
                                // A drag begun on an earlier song stays dead until the finger lifts.
                                if (dragging.entry == latestCurrent) move(dragging.copy(fraction = at(change.position.x)))
                            }
                        } finally {
                            if (!lifted && scrub is Scrub.Dragging) move(Scrub.Idle)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
            ) {
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = radius)
                val buffered = maxOf(clock.bufferedFraction, fraction).toFloat()
                drawRoundRect(Color.White.copy(alpha = 0.3f), size = Size(size.width * buffered, size.height), cornerRadius = radius)
                drawRoundRect(BoomColor.accent, size = Size(size.width * fraction.toFloat(), size.height), cornerRadius = radius)
            }
            val thumbPx = with(LocalDensity.current) { thumb.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((fraction * width - thumbPx / 2).roundToInt(), 0) }
                    .size(thumb)
                    .shadow(3.dp, CircleShape)
                    .background(BoomColor.accent, CircleShape)
                    .testTag("scrubber.thumb"),
            )
            if (isDragging) {
                val bubble = with(LocalDensity.current) { 48.dp.toPx() }
                val x = (fraction * width - bubble / 2).coerceIn(0.0, (width - bubble).toDouble().coerceAtLeast(0.0))
                Text(
                    boomTime(fraction * duration), style = BoomFont.time.copy(fontWeight = FontWeight.SemiBold),
                    color = BoomColor.onAccent, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), -(22.dp.toPx()).roundToInt()) }
                        .size(48.dp, 20.dp)
                        .background(BoomColor.accent, CircleShape)
                        .padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                boomTime(held?.let { it * duration } ?: clock.position), style = BoomFont.time, color = elapsedColour,
                modifier = Modifier.testTag("scrubber.elapsed"),
            )
            // What is really being played, measured from the stream itself.
            Text(
                state.audioFormat ?: "", style = BoomFont.caption.copy(fontWeight = FontWeight.Medium),
                color = BoomColor.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .testTag("player.format"),
            )
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { showsRemaining = !showsRemaining }
                    .semantics { contentDescription = if (showsRemaining) "Show length" else "Show time left" }
                    .padding(start = 12.dp)
                    .testTag("scrubber.length"),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    if (showsRemaining) "-" + boomTime(maxOf(0.0, duration - fraction * duration)) else boomTime(duration),
                    style = BoomFont.time, color = BoomColor.textSecondary,
                )
            }
        }
    }
}
