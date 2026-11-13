package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LoadStateView
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.CircleIconButton
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.design.ItemActions
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.PlayContext
import io.github.boomboom.ui.design.ShelfView
import io.github.boomboom.ui.design.SongRow
import io.github.boomboom.ui.design.rememberItemActions

/** A playlist: cover, title, author, description, Play and Shuffle, the songs (more as you scroll), then shelves. */
@Composable
fun PlaylistScreen(id: String) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val router = LocalShellRouter.current
    val model = rememberPageModel { PlaylistModel(id, services.api, it, appScope) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(CollectionTitleThreshold)
    /** The editor is up (the listener's own playlists only). */
    var isEditing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.loadIfNeeded() }
    val loaded = model.state.valueOrNull

    PageFrame(
        loaded?.content?.playlist?.title ?: "", isTitleShown,
        backdrop = loaded?.let { { CollectionBackdrop(it.content.playlist.artwork, list) } },
    ) {
        LoadStateView(model.state, retry = { model.retry() }, style = LoadingStyle.COLLECTION) { paged ->
            val page = paged.content
            RefreshBox({ model.refresh() }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("playlist.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "header") { PlaylistHeader(page, paged.more.tokenOrNull, model) { isEditing = true } }
                    val context = PlayContext.InList(page.tracks, ItemActions.source(page.playlist))
                    // Append-only, and songs may repeat: rows are keyed by position.
                    itemsIndexed(page.tracks, key = { index, _ -> "track:$index" }) { _, track ->
                        SongRow(track, context = context, showsKind = false)
                    }
                    if (paged.more != MoreState.End) {
                        item(key = "more") { LoadMoreRow(paged.more) { model.loadMore() } }
                    } else if (page.tracks.isEmpty()) {
                        item(key = "empty") { MessageView("This playlist is empty") }
                    }
                    items(page.shelves, key = { "shelf:" + it.id }) { shelf ->
                        ShelfView(shelf, Modifier.padding(top = BoomMetrics.shelfSpacing))
                    }
                }
            }
        }
    }

    val editing = loaded?.content
    if (isEditing && editing != null) {
        PlaylistEditorSheet(
            editing,
            onChanged = { model.refresh() },
            onDismiss = { isEditing = false },
            // The playlist is gone: leave its page.
            onDeleted = { router.back() },
        )
    }
}

@Composable
private fun PlaylistHeader(page: PlaylistPage, continuation: String?, model: PlaylistModel, edit: () -> Unit) {
    val actions = rememberItemActions()
    val services = actions.services
    val session by services.session.state.collectAsState()
    val playlist = page.playlist
    val source = ItemActions.source(playlist)
    CollectionHeader(
        playlist.artwork, playlist.title, description = page.description,
        lines = {
            page.authorName?.let { author ->
                Row(
                    Modifier.heightIn(min = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    page.authorArtwork?.let { ArtworkView(it, width = 22.dp, isCircle = true) }
                    Text(author, style = BoomFont.itemSubtitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary)
                }
            }
            (page.statsText ?: playlist.detailText)?.let { Text(it) }
        },
        actions = {
            val download = CollectionDownloadSource(
                DownloadCollection.Kind.PLAYLIST, DownloadCollector.entry(page), page.tracks, continuation,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CollectionActions(
                    play = { actions.playList(page.tracks, shuffled = false, source = source) },
                    leading = {
                        if (page.isEditable) {
                            CircleIconButton(Icons.Filled.Edit, "Edit playlist", edit, Modifier.testTag("playlist.edit"))
                        } else {
                            val saved = model.saved.isOn
                            CircleIconButton(
                                if (saved) Icons.Filled.Check else Icons.Filled.Add,
                                if (saved) "Remove from library" else "Save to library",
                                { if (session.isSignedIn) model.toggleSaved() else actions.navigator.signIn() },
                                Modifier.testTag("collection.save"), isActive = saved,
                            )
                        }
                        CollectionDownloadButton(download)
                    },
                    trailing = {
                        CircleIconButton(Icons.Filled.Shuffle, "Shuffle", { actions.playList(page.tracks, shuffled = true, source = source) })
                        CircleMenuButton(MusicItem.OfPlaylist(playlist))
                    },
                )
                CollectionDownloadStatus(download)
            }
        },
    )
}
