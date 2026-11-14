package io.github.boomboom.ui.browse.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.DownloadsState
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.PageBarHeight
import io.github.boomboom.ui.browse.PageTitle
import io.github.boomboom.ui.browse.collections.freedBytes
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ChipBar
import io.github.boomboom.ui.design.ConfirmDialog
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalSettingsStore
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import io.github.boomboom.ui.design.songsText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer

private const val SORT_KEY = "downloads.sort"

/** What the Downloads screen keeps while it is in the stack: the view, the mode, and the question being asked. */
private class DownloadsPage {
    var view by mutableStateOf(DownloadsView.SONGS)
    var mode by mutableStateOf<DownloadsMode>(DownloadsMode.Browsing)
    var question by mutableStateOf<DownloadsQuestion>(DownloadsQuestion.None)
}

/**
 * The listener's downloads: the space they take, what is still coming, the songs or the albums and playlists, and the
 * ways to remove them (a row's own button, a swipe, select and delete, remove all). Reached from Library, Settings and
 * a page that could not load; works signed out and with no connection, since everything on it is on the phone.
 */
@Composable
fun DownloadsScreen() {
    val services = LocalAppServices.current
    val store = services.downloads
    val settings = LocalSettingsStore.current
    val router = LocalShellRouter.current
    val state by store.state.collectAsState()
    val page = rememberPageModel { DownloadsPage() }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(40.dp)
    var sort by remember { mutableStateOf(DownloadSort.of(settings.getString(SORT_KEY))) }
    val mode = page.mode
    val view = page.view

    val shown = sort.sorted(state.items)
    val selectable = when (view) {
        DownloadsView.SONGS -> state.items.map { it.id }.toSet()
        DownloadsView.COLLECTIONS -> state.collections.map { it.id }.toSet()
    }
    // The picked rows that still exist (a download may finish or be removed meanwhile).
    val picked = mode.selection intersect selectable
    val freed = when (view) {
        DownloadsView.SONGS -> DownloadsMath.freedBytes(picked, state)
        DownloadsView.COLLECTIONS -> freedBytes(picked, state)
    }

    LaunchedEffect(state.items.isEmpty()) { if (state.items.isEmpty()) page.mode = DownloadsMode.Browsing }
    BackHandler(enabled = mode.isSelecting) { page.mode = DownloadsMode.Browsing }

    fun play(item: DownloadItem, playable: List<DownloadItem>) {
        val songs = playable.map { it.song }
        val index = songs.indexOfFirst { it.videoId == item.id }
        // Not on the phone yet: it plays like any song, over the network.
        if (index >= 0) services.player.play(songs, startAt = index, source = DownloadsMath.source) else services.player.play(item.song)
    }

    fun playAll(shuffled: Boolean) {
        val songs = shown.filter { it.isDownloaded }.map { it.song }
        if (songs.isEmpty()) return
        services.player.play(if (shuffled) songs.shuffled() else songs, startAt = 0, source = DownloadsMath.source)
    }

    fun deleteSelection() {
        when (view) {
            DownloadsView.SONGS -> store.remove(picked)
            DownloadsView.COLLECTIONS -> picked.forEach { store.removeCollection(it) }
        }
        page.mode = DownloadsMode.Browsing
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BoomColor.background)
            .testTag("downloads"),
    ) {
        DownloadsBar(
            mode, isTitleShown, hasItems = state.items.isNotEmpty(), isAllPicked = selectable.isNotEmpty() && picked == selectable,
            onBack = { router.back() },
            onSelect = { page.mode = DownloadsMode.Selecting(emptySet()) },
            onSelectAll = { page.mode = DownloadsMode.Selecting(if (picked == selectable) emptySet() else selectable) },
            onDone = { page.mode = DownloadsMode.Browsing },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("downloads.list"),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "title") { PageTitle("Downloads", Modifier.padding(top = 4.dp, bottom = 8.dp)) }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    MessageView(
                        "No downloads yet",
                        message = "Choose Download in a song's menu, or tap the download button beside Play on an album or " +
                            "playlist. Downloads stay on this phone until you remove them, and play without a connection.",
                        icon = Icons.Outlined.DownloadForOffline,
                        modifier = Modifier.padding(top = 0.dp),
                    )
                }
                return@LazyColumn
            }
            item(key = "header") {
                DownloadsHeader(
                    state, mode.isSelecting,
                    onRetryAll = { store.retryFailed() },
                    onUseMobileData = { page.question = DownloadsQuestion.MobileData },
                    onPlay = { playAll(shuffled = false) },
                    onShuffle = { playAll(shuffled = true) },
                )
            }
            item(key = "chips") {
                ChipBar(DownloadsView.entries, view, { it.title }, Modifier.padding(vertical = 4.dp)) { chosen ->
                    if (chosen == view) return@ChipBar
                    // A selection belongs to one view: song ids and collection ids do not mix.
                    page.mode = DownloadsMode.Browsing
                    page.view = chosen
                }
            }
            when (view) {
                DownloadsView.SONGS -> {
                    item(key = "sort") {
                        SortButton(sort) {
                            sort = it
                            settings.putString(SORT_KEY, it.rawValue)
                        }
                    }
                    val playable = shown.filter { it.isDownloaded }
                    items(shown, key = { "song:" + it.id }) { item ->
                        SwipeToRemove(
                            enabled = !mode.isSelecting, onRemove = { store.remove(setOf(item.id)) },
                            modifier = Modifier
                                .animateItem(fadeInSpec = null)
                                .testTag("downloads.swipe"),
                        ) {
                            DownloadSongRow(
                                item,
                                mode = when (mode) {
                                    DownloadsMode.Browsing -> DownloadRowMode.Normal { play(item, playable) }
                                    is DownloadsMode.Selecting -> DownloadRowMode.Selecting(item.id in mode.ids) {
                                        page.mode = page.mode.toggling(item.id)
                                    }
                                },
                                isWaitingForWiFi = state.isWaitingForWiFi,
                                onRetry = { retry(item, store) },
                                onRemove = { store.remove(setOf(item.id)) },
                            )
                        }
                    }
                }
                DownloadsView.COLLECTIONS -> {
                    if (state.collections.isEmpty()) {
                        item(key = "noCollections") {
                            MessageView(
                                "No albums or playlists",
                                message = "Download a whole album or playlist with the download button beside Play on its page.",
                                icon = Icons.Outlined.LibraryMusic,
                                modifier = Modifier.padding(top = 0.dp),
                            )
                        }
                    }
                    items(state.collections, key = { "collection:" + it.id }) { collection ->
                        // A whole album or playlist goes only once the listener says so.
                        SwipeToRemove(
                            enabled = !mode.isSelecting, asksFirst = true,
                            onRemove = { page.question = DownloadsQuestion.RemoveCollection(collection) },
                            modifier = Modifier.animateItem(fadeInSpec = null),
                        ) {
                            DownloadCollectionRow(
                                collection,
                                when (mode) {
                                    DownloadsMode.Browsing -> DownloadRowMode.Normal { router.push(BrowseRoute.DownloadedCollection(collection.id)) }
                                    is DownloadsMode.Selecting -> DownloadRowMode.Selecting(collection.id in mode.ids) {
                                        page.mode = page.mode.toggling(collection.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (!mode.isSelecting) {
                item(key = "removeAll") {
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        RemoveAllButton { page.question = DownloadsQuestion.RemoveAll }
                    }
                }
            }
        }
        if (mode.isSelecting) {
            SelectionBar(picked.size, view, freed) { page.question = DownloadsQuestion.DeleteSelection }
        }
    }

    val dismiss = { page.question = DownloadsQuestion.None }
    when (val question = page.question) {
        DownloadsQuestion.None -> Unit
        DownloadsQuestion.DeleteSelection -> {
            val noun = noun(picked.size, view)
            ConfirmDialog(
                "Remove ${picked.size} $noun?", "This frees ${DownloadSize.text(freed)}. They can be downloaded again later.",
                confirm = "Remove ${picked.size}", tag = "downloads.deleteQuestion", onConfirm = ::deleteSelection, onDismiss = dismiss,
            )
        }
        DownloadsQuestion.RemoveAll -> ConfirmDialog(
            "Remove all downloads?", "Every downloaded song, album and playlist leaves this phone.",
            confirm = "Remove all", tag = "downloads.removeAllQuestion",
            // Asked once more, in its own dialog.
            onConfirm = { page.question = DownloadsQuestion.RemoveAllAgain }, onDismiss = dismiss,
        )
        DownloadsQuestion.RemoveAllAgain -> ConfirmDialog(
            "Remove every download?",
            "All ${songsText(state.items.size)} leave this phone, freeing ${DownloadSize.text(state.totalBytes)}. " +
                "Getting them back means downloading them again.",
            confirm = "Remove all", dismiss = "Keep them", tag = "downloads.removeAllAgain",
            onConfirm = { store.removeAll() }, onDismiss = dismiss,
        )
        DownloadsQuestion.MobileData -> ConfirmDialog(
            "Download over mobile data?", "A song is about 4 to 10 MB. This stays on until you turn it off in Settings.",
            confirm = "Use mobile data", isDestructive = false, tag = "downloads.mobileDataQuestion",
            onConfirm = { store.isMobileDataAllowed = true }, onDismiss = dismiss,
        )
        is DownloadsQuestion.RemoveCollection -> ConfirmDialog(
            "Remove “${question.collection.title}” from downloads?",
            "This frees ${DownloadSize.text(freedBytes(setOf(question.collection.id), state))}. Songs you also downloaded on " +
                "their own, or that another download holds, stay.",
            confirm = "Remove download", tag = "downloads.removeCollection",
            onConfirm = { store.removeCollection(question.collection.id) }, onDismiss = dismiss,
        )
    }
}

private fun noun(count: Int, view: DownloadsView): String = when (view) {
    DownloadsView.SONGS -> if (count == 1) "song" else "songs"
    DownloadsView.COLLECTIONS -> if (count == 1) "album or playlist" else "albums and playlists"
}

/**
 * The bar: back, the title once the big one has scrolled away, and Select. In select mode: Select all (or Deselect
 * all) and Done; the back gesture leaves select mode first.
 */
@Composable
private fun DownloadsBar(
    mode: DownloadsMode,
    isTitleShown: Boolean,
    hasItems: Boolean,
    isAllPicked: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    val titleAlpha by animateFloatAsState(if (isTitleShown) 1f else 0f, label = "title")
    Row(
        Modifier
            .fillMaxWidth()
            .background(BoomColor.background)
            .statusBarsPadding()
            .height(PageBarHeight)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode.isSelecting) {
            BarTextButton(if (isAllPicked) "Deselect all" else "Select all", "downloads.selectAll", onClick = onSelectAll)
        } else {
            BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, Modifier.testTag("page.back"))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Downloads", style = BoomFont.sectionTitle, color = BoomColor.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = if (mode.isSelecting) 0f else titleAlpha },
        )
        if (mode.isSelecting) {
            BarTextButton("Done", "downloads.done", bold = true, onClick = onDone)
        } else if (hasItems) {
            BarTextButton("Select", "downloads.select", onClick = onSelect)
        }
    }
}

@Composable
private fun BarTextButton(text: String, tag: String, bold: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = BoomMetrics.minimumTarget).testTag(tag)) {
        Text(text, style = BoomFont.button.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold), color = BoomColor.accent)
    }
}

/** The space used, written out, then what is still coming, what failed, and Play and Shuffle. */
@Composable
private fun DownloadsHeader(
    state: DownloadsState,
    isSelecting: Boolean,
    onRetryAll: () -> Unit,
    onUseMobileData: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val space = DownloadSize.text(state.totalBytes)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) { contentDescription = "$space on this phone" }
                .testTag("downloads.space"),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                space, color = BoomColor.accent, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                style = BoomFont.time.copy(fontSize = 34.sp, lineHeight = 40.sp),
            )
            Text("on this phone", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(DownloadsMath.countLine(state), style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, modifier = Modifier.testTag("downloads.count"))
        if (state.pendingCount > 0) {
            Row(
                Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("downloads.pending"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Held for Wi-Fi, nothing is moving: a pause sign rather than the arriving one.
                if (state.isWaitingForWiFi) {
                    Icon(Icons.Filled.PauseCircle, null, tint = BoomColor.textSecondary, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.Downloading, null, tint = BoomColor.accent, modifier = Modifier.size(18.dp))
                }
                Text(
                    "${state.pendingCount} still to download",
                    style = BoomFont.itemSubtitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary,
                )
            }
        }
        if (state.failedCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Error, null, tint = BoomColor.destructive, modifier = Modifier.size(18.dp))
                Text(
                    "${state.failedCount} couldn't be downloaded", style = BoomFont.itemSubtitle, color = BoomColor.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                PillButton("Retry", onRetryAll, Modifier.testTag("downloads.retry"), compact = true, contentDescription = "Retry the failed downloads")
            }
        }
        if (state.isWaitingForWiFi) {
            WiFiBanner(onUseMobileData)
        } else if (state.pendingCount > 0) {
            Text(
                "Downloads carry on while BoomBoom is open or playing music, and pick up where they left off next time otherwise.",
                style = BoomFont.caption, color = BoomColor.textTertiary,
            )
        }
        if (state.downloaded.isNotEmpty() && !isSelecting) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Play", onPlay, Modifier.testTag("downloads.play"), style = PillStyle.FILLED, icon = Icons.Filled.PlayArrow)
                PillButton("Shuffle", onShuffle, Modifier.testTag("downloads.shuffle"), style = PillStyle.TONAL, icon = Icons.Filled.Shuffle)
            }
        }
    }
}

@Composable
private fun WiFiBanner(onUseMobileData: () -> Unit) {
    val shape = RoundedCornerShape(BoomMetrics.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(BoomColor.surfaceRaised, shape)
            .border(1.dp, BoomColor.accent.copy(alpha = 0.5f), shape)
            .padding(12.dp)
            .testTag("downloads.wifi"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Wifi, null, tint = BoomColor.textPrimary, modifier = Modifier.size(20.dp))
            Text("Waiting for Wi-Fi", style = BoomFont.itemTitle, color = BoomColor.textPrimary)
        }
        Text(
            "The phone is on mobile data, and downloads use Wi-Fi only. They start again as soon as you are on Wi-Fi.",
            style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
        )
        PillButton("Use mobile data", onUseMobileData, Modifier.testTag("downloads.mobileData"), compact = true)
    }
}

/** The order of the songs, as a small drop-down. */
@Composable
private fun SortButton(sort: DownloadSort, onChange: (DownloadSort) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = BoomMetrics.margin - 4.dp)) {
        Row(
            Modifier
                .heightIn(min = BoomMetrics.minimumTarget)
                .clickable(role = Role.DropdownList) { isOpen = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Sort by"
                    stateDescription = sort.title
                }
                .testTag("downloads.sort")
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.SwapVert, null, tint = BoomColor.textPrimary, modifier = Modifier.size(18.dp))
            Text(sort.title, style = BoomFont.button, color = BoomColor.textPrimary)
        }
        DropdownMenu(isOpen, { isOpen = false }, containerColor = BoomColor.surfaceRaised, modifier = Modifier.testTag("downloads.sort.menu")) {
            for (choice in DownloadSort.entries) {
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    leadingIcon = {
                        if (choice == sort) Icon(Icons.Filled.Check, null, tint = BoomColor.accent) else Spacer(Modifier.width(24.dp))
                    },
                    onClick = {
                        isOpen = false
                        onChange(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun RemoveAllButton(onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("downloads.removeAll"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Remove all downloads", style = BoomFont.button, color = BoomColor.destructive)
        }
    }
}

/** Under the list in select mode: how many are picked, the space they free, and Delete. */
@Composable
private fun SelectionBar(count: Int, view: DownloadsView, freed: Long, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BoomColor.surface)
            .testTag("downloads.selectionBar"),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = BoomColor.separator)
        Row(
            Modifier.padding(horizontal = BoomMetrics.margin, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (count == 0) "Pick what to remove" else "$count ${noun(count, view)} selected",
                    style = BoomFont.itemTitle, color = BoomColor.textPrimary, modifier = Modifier.testTag("downloads.selected"),
                )
                Text(
                    if (count == 0) "Tap rows to select them" else "Frees ${DownloadSize.text(freed)}",
                    style = BoomFont.itemSubtitle.copy(fontFeatureSettings = "tnum"), color = BoomColor.textSecondary,
                    modifier = Modifier.testTag("downloads.frees"),
                )
            }
            Box(
                Modifier
                    .heightIn(min = BoomMetrics.minimumTarget)
                    .clickable(enabled = count > 0, role = Role.Button, onClick = onDelete)
                    .testTag("downloads.delete"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .background(if (count == 0) BoomColor.surfaceRaised else BoomColor.destructive, CircleShape)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Delete ($count)", style = BoomFont.button, color = if (count == 0) BoomColor.textTertiary else Color.White)
                }
            }
        }
    }
}
