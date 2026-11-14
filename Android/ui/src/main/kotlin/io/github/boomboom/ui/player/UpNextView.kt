package io.github.boomboom.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.QueueEntry
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.NowPlayingBars
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import io.github.boomboom.ui.design.boomTime
import io.github.boomboom.ui.sound.SoundSwitchKnob
import java.util.UUID
import kotlinx.coroutines.flow.collectLatest

/** Where the list is in showing the queue the first time. */
private enum class Stage {
    /** Nothing shown yet. */
    EMPTY,

    /** Rows shown; the list still has to scroll to the playing entry once it is among them. */
    WAITING_FOR_CURRENT,
    SETTLED,
}

/**
 * A row being dragged by its handle: [order] is the queue as it looks under the finger, [offset] how far the row is
 * from its slot in that order (pixels), [from] where it was in the queue when the drag began.
 */
private data class QueueDrag(val entry: UUID, val from: Int, val order: List<UUID>, val offset: Float)

/**
 * The "Up next" tab: the whole queue, the playing entry marked. Each row's handle drags it to a new place (one entry
 * moves, once, when the finger lifts; held at the list's edge, the list scrolls on); a swipe to the left removes it (never the playing entry); a tap plays it; a long
 * press offers Play now, Play next and Remove.
 */
@Composable
internal fun UpNextView() {
    val player = LocalAppServices.current.player
    val state by player.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        QueueHeader(player)
        if (state.queue.isEmpty()) {
            PanelMessage(Icons.AutoMirrored.Filled.QueueMusic, "Nothing queued")
        } else {
            QueueList(player, state.queue, state.currentEntryId, state.status == PlaybackStatus.Playing)
        }
    }
}

@Composable
private fun QueueHeader(player: PlayerStore) {
    val state by player.state.collectAsState()
    Column(Modifier.padding(horizontal = BoomMetrics.margin, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playing from", style = BoomFont.caption, color = BoomColor.textTertiary)
                Text(
                    state.source?.title ?: "Your queue", style = BoomFont.itemTitle, color = BoomColor.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.upNext.isNotEmpty()) {
                PillButton(
                    "Clear", { player.clearUpNext() }, Modifier.testTag("queue.clear"), style = PillStyle.TONAL, compact = true,
                    contentDescription = "Clear up next",
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = BoomMetrics.minimumTarget)
                .toggleable(state.isAutoplayEnabled, role = Role.Switch) { player.isAutoplayEnabled = it }
                .testTag("queue.autoplay"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Autoplay", style = BoomFont.itemTitle, color = BoomColor.textPrimary)
                Text("Similar songs play when the queue ends", style = BoomFont.caption, color = BoomColor.textTertiary)
            }
            SoundSwitchKnob(state.isAutoplayEnabled)
        }
    }
}

@Composable
private fun QueueList(player: PlayerStore, entries: List<QueueEntry>, currentId: UUID?, isPlaying: Boolean) {
    val listState = rememberLazyListState()
    var stage by remember { mutableStateOf(Stage.EMPTY) }
    var drag by remember { mutableStateOf<QueueDrag?>(null) }
    val latestEntries by rememberUpdatedState(entries)

    val byId = entries.associateBy { it.id }
    val shown = drag?.order?.mapNotNull { byId[it] } ?: entries
    val currentIndex = entries.indexOfFirst { it.id == currentId }

    LaunchedEffect(entries.isNotEmpty(), currentIndex) {
        if (stage == Stage.EMPTY && entries.isNotEmpty()) stage = Stage.WAITING_FOR_CURRENT
        if (stage == Stage.WAITING_FOR_CURRENT && currentIndex >= 0) {
            stage = Stage.SETTLED
            listState.scrollToItem(currentIndex)
        }
    }

    fun onDrag(entry: UUID, delta: Float) {
        val now = drag ?: return
        if (now.entry != entry) return
        var order = now.order
        var offset = now.offset + delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val at = order.indexOf(entry)
        val size = visible.firstOrNull { it.index == at }?.size?.toFloat() ?: return
        // Past half the neighbour's height, the row takes its place.
        if (offset > 0 && at + 1 < order.size) {
            val next = visible.firstOrNull { it.index == at + 1 }?.size?.toFloat() ?: size
            if (offset > next / 2) {
                order = order.toMutableList().apply { add(at + 1, removeAt(at)) }
                offset -= next
            }
        } else if (offset < 0 && at > 0) {
            val previous = visible.firstOrNull { it.index == at - 1 }?.size?.toFloat() ?: size
            if (-offset > previous / 2) {
                order = order.toMutableList().apply { add(at - 1, removeAt(at)) }
                offset += previous
            }
        }
        drag = now.copy(order = order, offset = offset)
    }

    // A row held near the list's top or bottom edge scrolls the list, faster the nearer the edge, so it can be
    // carried past what shows. The row stays under the finger: what the list moves, the row's offset takes back.
    // Frames are asked for only while the row is at an edge and the list can still move.
    val dragging = drag?.entry
    val density = LocalDensity.current
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val fastest = with(density) { 14.dp.toPx() }
        fun step(): Float {
            val now = drag ?: return 0f
            if (now.entry != dragging) return 0f
            val layout = listState.layoutInfo
            val at = now.order.indexOf(dragging)
            val item = layout.visibleItemsInfo.firstOrNull { it.index == at } ?: return 0f
            val top = item.offset + now.offset
            val bottom = top + item.size
            val start = layout.viewportStartOffset.toFloat()
            val end = (layout.viewportEndOffset - layout.afterContentPadding).toFloat()
            return when {
                top < start + edge -> -fastest * ((start + edge - top) / edge).coerceIn(0f, 1f)
                bottom > end - edge -> fastest * ((bottom - (end - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
        }
        snapshotFlow { drag?.offset }.collectLatest {
            while (true) {
                if (step() == 0f) break
                withFrameMillis { }
                val amount = step()
                if (amount == 0f) break
                val moved = listState.scrollBy(amount)
                if (moved == 0f) break
                onDrag(dragging, moved)
            }
        }
    }

    fun endDrag() {
        val now = drag ?: return
        drag = null
        val to = now.order.indexOf(now.entry)
        // The queue may have changed under the finger (a song ended): move by where the entry is now.
        val from = latestEntries.indexOfFirst { it.id == now.entry }
        if (from < 0 || to < 0 || from == to) return
        player.moveQueue(listOf(from), if (to > from) to + 1 else to)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("queue.list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(shown, key = { it.id }) { entry ->
            val index = entries.indexOfFirst { it.id == entry.id }
            val isDragged = drag?.entry == entry.id
            QueueRow(
                entry = entry,
                isCurrent = entry.id == currentId,
                isPast = currentIndex >= 0 && index < currentIndex,
                isPlaying = isPlaying,
                player = player,
                onMove = { step ->
                    // TalkBack's way to reorder: one place up or down.
                    val from = entries.indexOfFirst { it.id == entry.id }
                    val to = from + step
                    if (from < 0 || to !in entries.indices) {
                        false
                    } else {
                        player.moveQueue(listOf(from), if (to > from) to + 1 else to)
                        true
                    }
                },
                handle = Modifier.pointerInput(entry.id) {
                    // Upward or downward only: a sideways swipe that starts on the handle still removes the row.
                    detectVerticalDragGestures(
                        onDragStart = {
                            val ids = latestEntries.map { it.id }
                            drag = QueueDrag(entry.id, ids.indexOf(entry.id), ids, 0f)
                        },
                        onDragEnd = { endDrag() },
                        onDragCancel = { drag = null },
                    ) { change, amount ->
                        change.consume()
                        onDrag(entry.id, amount)
                    }
                },
                modifier = Modifier
                    .then(if (isDragged) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    .graphicsLayer {
                        if (isDragged) {
                            translationY = drag?.offset ?: 0f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .then(if (isDragged) Modifier.background(BoomColor.surfaceRaised) else Modifier),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    isPast: Boolean,
    isPlaying: Boolean,
    player: PlayerStore,
    handle: Modifier,
    onMove: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val song = entry.song
    var isMenuOpen by remember { mutableStateOf(false) }
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // The playing entry cannot be swiped away.
            if (value == SwipeToDismissBoxValue.EndToStart && !latestIsCurrent) {
                player.removeFromQueue(setOf(entry.id))
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !isCurrent,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(BoomColor.destructive)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Remove", style = BoomFont.button, color = Color.White)
            }
        },
        modifier = modifier.testTag(if (isCurrent) "queue.row.current" else "queue.row"),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BoomColor.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = if (isCurrent) "Playing now" else "Plays this song",
                            onLongClick = { if (!isCurrent) isMenuOpen = true },
                        ) { if (!isCurrent) player.jump(entry.id) }
                        .semantics {
                            selected = isCurrent
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("Move up") { onMove(-1) },
                                CustomAccessibilityAction("Move down") { onMove(1) },
                                if (isCurrent) null else CustomAccessibilityAction("Remove from queue") {
                                    player.removeFromQueue(setOf(entry.id))
                                    true
                                },
                            )
                        }
                        .padding(start = BoomMetrics.margin, top = 6.dp, bottom = 6.dp)
                        .alpha(if (isPast && !isCurrent) 0.5f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        ArtworkView(song.artwork, width = 48.dp)
                        if (isCurrent) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(BoomMetrics.coverRadius)),
                            )
                            NowPlayingBars(isPlaying, Modifier.size(18.dp, 16.dp), color = BoomColor.accent)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title, style = BoomFont.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) BoomColor.accent else BoomColor.textPrimary,
                        )
                        Text(
                            song.artistLine, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    song.duration?.let { Text(boomTime(it), style = BoomFont.time, color = BoomColor.textTertiary) }
                }
                // The handle sits beside the row's tap target, not in it: a drag here only ever reorders.
                Box(
                    handle
                        .size(BoomMetrics.minimumTarget)
                        .semantics { contentDescription = "Reorder" }
                        .testTag("queue.handle"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = Color.White.copy(alpha = 0.45f))
                }
            }
            DropdownMenu(isMenuOpen, onDismissRequest = { isMenuOpen = false }, containerColor = BoomColor.surfaceRaised) {
                DropdownMenuItem(
                    text = { Text("Play now") }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                    onClick = {
                        isMenuOpen = false
                        player.jump(entry.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Play next") }, leadingIcon = { Icon(Icons.Filled.QueuePlayNext, null) },
                    onClick = {
                        isMenuOpen = false
                        val target = player.currentIndex
                        val source = player.queue.indexOfFirst { it.id == entry.id }
                        if (target != null && source >= 0) player.moveQueue(listOf(source), target + 1)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove from queue", color = BoomColor.destructive) },
                    leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null, tint = BoomColor.destructive) },
                    onClick = {
                        isMenuOpen = false
                        player.removeFromQueue(setOf(entry.id))
                    },
                )
            }
        }
    }
}
