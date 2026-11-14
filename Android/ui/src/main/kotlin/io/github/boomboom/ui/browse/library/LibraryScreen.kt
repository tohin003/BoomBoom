package io.github.boomboom.ui.browse.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageErrorView
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.collections.PlaylistEditorSheet
import io.github.boomboom.ui.browse.downloads.DownloadsEntryRow
import io.github.boomboom.ui.browse.home.TopBar
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ChipBar
import io.github.boomboom.ui.design.ConfirmDialog
import io.github.boomboom.ui.design.ItemCard
import io.github.boomboom.ui.design.ItemRow
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.LocalSettingsStore
import io.github.boomboom.ui.design.MenuExtra
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.PlayContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** The one thing the Library can have over it at a time. */
private sealed class LibraryPrompt {
    data object None : LibraryPrompt()
    data object NewPlaylist : LibraryPrompt()
    data class Editing(val playlist: PlaylistItem) : LibraryPrompt()
    data class Deleting(val playlist: PlaylistItem) : LibraryPrompt()
}

private const val GRID_KEY = "library.showsGrid"

/**
 * The Library tab: Downloads first (signed out too: they are on the phone and play without a connection), then the
 * listener's playlists, songs, albums, artists and subscriptions, one section at a time, as a list or two across.
 * Pull to refresh reloads the section; a change of account forgets every section and loads the new one's.
 */
@Composable
fun LibraryScreen() {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val navigator = LocalBoomNavigator.current
    val appScope = LocalAppScope.current
    val settings = LocalSettingsStore.current
    val session by services.session.state.collectAsState()
    val model = rememberPageModel { LibraryModel(services.api, it) }
    val list = rememberPageListState()
    var prompt by remember { mutableStateOf<LibraryPrompt>(LibraryPrompt.None) }
    var showsGrid by remember { mutableStateOf(settings.getBoolean(GRID_KEY) ?: false) }
    val section = model.section

    LaunchedEffect(session.revision, session.isSignedIn, section) {
        model.sync(session.revision)
        if (session.isSignedIn) model.loadIfNeeded(section)
    }
    // A tap on Library while it is shown at its root: back to the top.
    val reselect = router.reselectTick(BrowseTab.LIBRARY)
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Library") {
                BarIconButton(Icons.Outlined.History, "History", { router.push(BrowseRoute.History) }, Modifier.testTag("library.history"))
            }
            RefreshBox({ if (session.isSignedIn) model.refresh(section) else Job().apply { complete() } }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("library.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "downloads") { DownloadsEntryRow(Modifier.padding(top = 4.dp)) }
                    if (!session.isSignedIn) {
                        item(key = "signedOut") {
                            SignedOutView(
                                "Your music, all in one place",
                                "Your playlists, liked songs, albums and artists come from your own account. Sign in to see them here.",
                            )
                        }
                    } else {
                        item(key = "chips") {
                            ChipBar(LibrarySection.entries, section, { it.title }, Modifier.padding(vertical = 4.dp)) { model.select(it) }
                        }
                        when (val state = model.state(section)) {
                            LoadState.Loading -> item(key = "loading") { LoadingView(LoadingStyle.LIST, Modifier.padding(top = 8.dp)) }
                            is LoadState.Failed -> item(key = "error") { PageErrorView(state.message) { model.retry(section) } }
                            is LoadState.Loaded -> loaded(
                                section, state.value, showsGrid,
                                toggleGrid = {
                                    showsGrid = !showsGrid
                                    settings.putBoolean(GRID_KEY, showsGrid)
                                },
                                prompt = { prompt = it },
                                openLiked = { navigator.open(BrowseEndpoint("LM", pageType = BrowseEndpoint.PageType.PLAYLIST)) },
                                loadMore = { model.loadMore(section) },
                            )
                        }
                    }
                }
            }
        }

        when (val shown = prompt) {
            LibraryPrompt.None -> Unit
            LibraryPrompt.NewPlaylist -> NewPlaylistPrompt(
                onClose = { prompt = LibraryPrompt.None },
                onCreated = { id ->
                    model.refresh(LibrarySection.PLAYLISTS)
                    navigator.open(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.PLAYLIST))
                },
            )
            is LibraryPrompt.Editing -> PlaylistEditorLoader(
                shown.playlist, onChanged = { model.refresh(LibrarySection.PLAYLISTS) }, onClose = { prompt = LibraryPrompt.None },
            )
            is LibraryPrompt.Deleting -> ConfirmDialog(
                "Delete “${shown.playlist.title}”?",
                "The playlist and the order of its songs will be gone. The songs themselves stay on BoomBoom.",
                confirm = "Delete", tag = "library.delete",
                onConfirm = { model.deletePlaylist(shown.playlist, appScope) },
                onDismiss = { prompt = LibraryPrompt.None },
            )
        }
        model.notice?.let { notice ->
            AlertDialog(
                onDismissRequest = { model.notice = null },
                containerColor = BoomColor.surfaceRaised,
                modifier = Modifier.testTag("library.notice"),
                title = { Text(notice.title, style = BoomFont.sectionTitle) },
                text = { Text(notice.message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary) },
                confirmButton = { TextButton({ model.notice = null }) { Text("OK", color = BoomColor.accent) } },
            )
        }
    }
}

private fun LazyListScope.loaded(
    section: LibrarySection,
    content: LibraryContent,
    showsGrid: Boolean,
    toggleGrid: () -> Unit,
    prompt: (LibraryPrompt) -> Unit,
    openLiked: () -> Unit,
    loadMore: () -> Unit,
) {
    val isGrid = showsGrid && section != LibrarySection.SONGS
    if (section != LibrarySection.SONGS && content.items.isNotEmpty()) {
        item(key = "layout") {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp)) {
                Spacer(Modifier.weight(1f))
                BarIconButton(
                    if (showsGrid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    if (showsGrid) "Show as list" else "Show as grid", toggleGrid, Modifier.testTag("library.layout"),
                    tint = BoomColor.textSecondary, iconSize = 22.dp,
                )
            }
        }
    }
    if (section == LibrarySection.PLAYLISTS) {
        item(key = "pinned.new") { PinnedRow(PinnedKind.NEW_PLAYLIST) { prompt(LibraryPrompt.NewPlaylist) } }
        item(key = "pinned.liked") { PinnedRow(PinnedKind.LIKED_MUSIC, openLiked) }
    }
    if (content.items.isEmpty()) {
        item(key = "empty") {
            val (title, message, icon) = emptyWords(section)
            MessageView(title, message = message, icon = icon)
        }
    } else if (isGrid) {
        items(content.items.chunked(2), key = { pair -> "grid:" + pair.first().id }) { pair ->
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
                val cover = ((maxWidth - BoomMetrics.margin * 2 - 12.dp) / 2).coerceAtLeast(80.dp)
                Row(Modifier.padding(horizontal = BoomMetrics.margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (item in pair) {
                        ItemCard(item, cover = cover, menuExtras = extras(item, prompt))
                    }
                }
            }
        }
    } else {
        val songs = content.songs
        val source = QueueSource(QueueSource.Kind.LIBRARY, "Library", "Songs")
        items(content.items, key = { "item:" + it.id }) { item ->
            when (item) {
                is MusicItem.OfSong -> ItemRow(item, context = PlayContext.InList(songs, source), showsKind = false)
                else -> ItemRow(item, menuExtras = extras(item, prompt))
            }
        }
    }
    if (content.more != MoreState.End) {
        item(key = "more") { LoadMoreRow(content.more, loadMore) }
    }
}

/** Edit and Delete, for the listener's own playlists. */
private fun extras(item: MusicItem, prompt: (LibraryPrompt) -> Unit): List<MenuExtra> {
    val playlist = item.asPlaylist?.takeIf { it.isOwn } ?: return emptyList()
    return listOf(
        MenuExtra("Edit", Icons.Outlined.Edit, "menu.edit") { prompt(LibraryPrompt.Editing(playlist)) },
        MenuExtra("Delete", Icons.Outlined.Delete, "menu.delete", isDestructive = true) { prompt(LibraryPrompt.Deleting(playlist)) },
    )
}

private fun emptyWords(section: LibrarySection): Triple<String, String, ImageVector> = when (section) {
    LibrarySection.PLAYLISTS -> Triple("No playlists yet", "Playlists you make or save show up here.", Icons.AutoMirrored.Outlined.QueueMusic)
    LibrarySection.SONGS -> Triple("No songs yet", "Songs you add to your library show up here.", Icons.Outlined.MusicNote)
    LibrarySection.ALBUMS -> Triple("No albums yet", "Albums you add to your library show up here.", Icons.Outlined.LibraryMusic)
    LibrarySection.ARTISTS -> Triple("No artists yet", "Artists you follow show up here.", Icons.Outlined.People)
    LibrarySection.SUBSCRIPTIONS -> Triple("No subscriptions yet", "Channels you subscribe to show up here.", Icons.Outlined.People)
}

/** Loads the playlist's page for the editor, with a spinner over the Library while it comes. */
@Composable
private fun PlaylistEditorLoader(playlist: PlaylistItem, onChanged: () -> Unit, onClose: () -> Unit) {
    val services = LocalAppServices.current
    var page by remember { mutableStateOf<LoadState<PlaylistPage>>(LoadState.Loading) }
    LaunchedEffect(playlist.id) {
        page = try {
            LoadState.Loaded(services.api.playlist(playlist.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoadState.Failed(userMessage(failure))
        }
    }
    when (val shown = page) {
        LoadState.Loading -> {
            BackHandler { onClose() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }
                    .testTag("library.editor.loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = BoomColor.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            }
        }
        is LoadState.Failed -> AlertDialog(
            onDismissRequest = onClose,
            containerColor = BoomColor.surfaceRaised,
            title = { Text("Couldn't open “${playlist.title}”", style = BoomFont.sectionTitle) },
            text = { Text(shown.message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary) },
            confirmButton = { TextButton(onClose) { Text("OK", color = BoomColor.accent) } },
        )
        is LoadState.Loaded -> PlaylistEditorSheet(shown.value, onChanged = onChanged, onDismiss = onClose)
    }
}
