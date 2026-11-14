package io.github.boomboom.ui.browse.downloads

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.models.MusicItem
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.collections.CollectionDownloadLook
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.DownloadBadge
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.ItemMenuSheet
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalDownloadCollector
import io.github.boomboom.ui.design.MoreButton
import io.github.boomboom.ui.design.NowPlayingBars
import io.github.boomboom.ui.design.collectionState
import io.github.boomboom.ui.design.songsText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a row of the Downloads screen answers a tap. */
sealed class DownloadRowMode {
    data class Normal(val onTap: () -> Unit) : DownloadRowMode()

    /** Select mode: the row shows a round mark and a tap picks it. */
    data class Selecting(val isSelected: Boolean, val onToggle: () -> Unit) : DownloadRowMode()
}

/**
 * A row that a leftward swipe removes, the red "Remove" showing from under it as it goes.
 *
 * The row is only taken once the finger has moved past the touch slop mostly sideways and to the left: a drag that
 * starts upward or downward is left to the list, which scrolls, and nothing is removed; a sideways drag that is taken
 * is never also a tap on the row. Let go past 40% of the row's width (or flicked left past 15%) it slides out and
 * [onRemove] runs; short of that it springs back.
 *
 * @param asksFirst The row springs back and [onRemove] asks the listener, rather than sliding out (a whole album).
 */
@Composable
fun SwipeToRemove(
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    asksFirst: Boolean = false,
    label: String = "Remove",
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val latestRemove by rememberUpdatedState(onRemove)

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .clipToBounds()
            .semantics {
                if (enabled) customActions = listOf(CustomAccessibilityAction(label) { latestRemove(); true })
            }
            .pointerInput(enabled, asksFirst) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    var total = Offset.Zero
                    var dragX = offset.value
                    var owned = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!owned) break
                            val full = width.toFloat().coerceAtLeast(1f)
                            val flung = velocity.calculateVelocity().x < -1500f && dragX < -full * 0.15f
                            val removes = dragX < -full * 0.4f || flung
                            scope.launch {
                                if (removes && !asksFirst) {
                                    offset.animateTo(-full, tween(180))
                                    latestRemove()
                                    // The list takes the row away; if it stays (the store kept it), it comes back.
                                    offset.snapTo(0f)
                                } else {
                                    if (removes) latestRemove()
                                    offset.animateTo(0f, spring(dampingRatio = 0.8f))
                                }
                            }
                            change.consume()
                            break
                        }
                        // Taken by someone else first (the list began to scroll).
                        if (!owned && change.isConsumed) break
                        val delta = change.positionChange()
                        total += delta
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!owned) {
                            if (total.getDistance() < slop) continue
                            // Upward, downward or rightward: not a removal. The list scrolls; the row stays.
                            if (abs(total.y) >= abs(total.x) || total.x > 0) break
                            owned = true
                            dragX = total.x
                        } else {
                            dragX += delta.x
                        }
                        dragX = dragX.coerceIn(-width.toFloat(), 0f)
                        change.consume()
                        val target = dragX
                        scope.launch { offset.snapTo(target) }
                    }
                }
            },
    ) {
        if (offset.value < 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(BoomColor.destructive)
                    .padding(horizontal = 20.dp)
                    .testTag("swipe.background"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(label, style = BoomFont.button, color = Color.White)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(BoomColor.background),
        ) { content() }
    }
}

/** The round mark at the front of a row in select mode. */
@Composable
fun SelectionMark(isSelected: Boolean) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
            tint = if (isSelected) BoomColor.accent else BoomColor.textTertiary, modifier = Modifier.size(24.dp),
        )
    }
}

/** The whole-row target: a tap in normal mode, a check box in select mode. */
private fun Modifier.rowTarget(mode: DownloadRowMode, label: String): Modifier = when (mode) {
    is DownloadRowMode.Normal -> clickable(role = Role.Button, onClickLabel = label, onClick = mode.onTap)
    is DownloadRowMode.Selecting -> toggleable(mode.isSelected, role = Role.Checkbox) { mode.onToggle() }
}

/**
 * One downloaded or pending song: cover, title, artist, and its size and quality (or how it stands while pending).
 * Outside select mode it also carries Retry when it failed, its own remove button and the song's menu.
 */
@Composable
fun DownloadSongRow(
    item: DownloadItem,
    mode: DownloadRowMode,
    isWaitingForWiFi: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val services = LocalAppServices.current
    val player by services.player.state.collectAsState()
    val song = item.song
    val isCurrent = player.current?.videoId == song.videoId
    val isFailed = item.state is DownloadItem.State.Failed
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoomMetrics.rowCover + 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = BoomMetrics.rowCover + 16.dp)
                .rowTarget(mode, if (item.isDownloaded) "Play" else "Play over the network")
                .semantics(mergeDescendants = true) {}
                .testTag("downloads.row")
                .padding(start = BoomMetrics.margin, end = if (mode is DownloadRowMode.Normal) 0.dp else BoomMetrics.margin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
        ) {
            if (mode is DownloadRowMode.Selecting) SelectionMark(mode.isSelected)
            Box(contentAlignment = Alignment.Center) {
                ArtworkView(song.artwork, width = BoomMetrics.rowCover)
                if (isCurrent) {
                    Box(
                        Modifier
                            .size(BoomMetrics.rowCover)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(BoomMetrics.coverRadius)),
                        contentAlignment = Alignment.Center,
                    ) { NowPlayingBars(player.isPlayingOrAboutTo, Modifier.size(18.dp, 16.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    song.title, style = BoomFont.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) BoomColor.accent else BoomColor.textPrimary,
                )
                Text(song.artistLine, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A failure's reason may take a second line; the other states fit on one.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DownloadBadge(item.state, size = 12.dp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        DownloadsMath.detail(item, isWaitingForWiFi), style = BoomFont.time, maxLines = if (isFailed) 2 else 1,
                        overflow = TextOverflow.Ellipsis, color = if (isFailed) BoomColor.destructive else BoomColor.textTertiary,
                        modifier = Modifier.testTag("downloads.row.detail"),
                    )
                }
            }
        }
        if (mode is DownloadRowMode.Normal) {
            if (isFailed) {
                BarIconButton(
                    Icons.Filled.Refresh, "Retry ${song.title}", onRetry, Modifier.testTag("downloads.row.retry"),
                    tint = BoomColor.accent, iconSize = 22.dp,
                )
            }
            BarIconButton(
                Icons.Outlined.Delete, "Remove download of ${song.title}", onRemove, Modifier.testTag("downloads.row.remove"),
                tint = BoomColor.textSecondary, iconSize = 22.dp,
            )
            MoreButton(song.title, Modifier.padding(end = 6.dp)) { isMenuOpen = true }
        }
    }
    if (isMenuOpen) ItemMenuSheet(MusicItem.OfSong(song), onDismiss = { isMenuOpen = false })
}

/** One downloaded album or playlist: cover, title, what it is, and "40 songs · 212 MB" or how far it has come. */
@Composable
fun DownloadCollectionRow(collection: DownloadCollection, mode: DownloadRowMode) {
    val services = LocalAppServices.current
    val collector = LocalDownloadCollector.current
    val state by services.downloads.state.collectAsState()
    val look = CollectionDownloadLook.of(collection.id, state, collector.collecting)
    val detail = when (look) {
        is CollectionDownloadLook.Arriving ->
            if (state.isWaitingForWiFi) "${look.done} of ${look.total} · waiting for Wi-Fi" else "${look.done} of ${look.total} downloaded"
        is CollectionDownloadLook.PartlyFailed -> "${look.done} of ${look.total} · ${look.total - look.done} couldn't be downloaded"
        else -> "${songsText(state.songsOf(collection.id).size)} · ${DownloadSize.text(state.bytesOf(collection.id))}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoomMetrics.rowCover + 16.dp)
            .rowTarget(mode, "Open")
            .semantics(mergeDescendants = true) {}
            .testTag("downloads.collection")
            .padding(horizontal = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        if (mode is DownloadRowMode.Selecting) SelectionMark(mode.isSelected)
        ArtworkView(collection.artwork, width = BoomMetrics.rowCover)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(collection.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(DownloadsMath.kindLine(collection), style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DownloadBadge(state.collectionState(collection.id), size = 12.dp)
                Text(detail, style = BoomFont.time, color = BoomColor.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mode is DownloadRowMode.Normal) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BoomColor.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

/** The row at the top of the Library tab that opens Downloads. Shown signed in or not, and with no connection. */
@Composable
fun DownloadsEntryRow(modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val state by services.downloads.state.collectAsState()
    val count = state.downloaded.size
    val subtitle = if (count == 0 && state.pendingCount == 0) {
        "Songs that play without a connection"
    } else {
        listOfNotNull(
            "${songsText(count)} · ${DownloadSize.text(state.totalBytes)}",
            if (state.pendingCount > 0) "${state.pendingCount} to come" else null,
        ).joinToString(" · ")
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BoomMetrics.rowCover + 8.dp)
            .clickable(role = Role.Button, onClickLabel = "Open") { router.push(BrowseRoute.Downloads) }
            .semantics(mergeDescendants = true) {}
            .testTag("library.downloads")
            .padding(horizontal = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        Box(
            Modifier
                .size(BoomMetrics.rowCover)
                .background(BoomColor.surfaceRaised, RoundedCornerShape(BoomMetrics.coverRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DownloadForOffline, null, tint = BoomColor.accent, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Downloads", style = BoomFont.itemTitle, color = BoomColor.textPrimary)
            Text(subtitle, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BoomColor.textTertiary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Tries one failed song again. [DownloadStore] has no call for one song: a song that came with a downloaded album or
 * playlist is asked for again with that collection (which sets going only the collection's failed songs), and a song
 * kept on its own is downloaded again on its own.
 */
fun retry(item: DownloadItem, store: DownloadStore) {
    val holder = store.collections.firstOrNull { item.id in it.songIds }
    if (holder != null && !item.isStandalone) {
        store.download(store.songsOf(holder.id).map { it.song }, holder)
    } else {
        store.download(item.song)
    }
}
