package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.ui.browse.LoadStateView
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.CircleIconButton
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.design.ItemActions
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.PlayContext
import io.github.boomboom.ui.design.RowLeading
import io.github.boomboom.ui.design.ShelfView
import io.github.boomboom.ui.design.SongRow
import io.github.boomboom.ui.design.rememberItemActions

/** An album: the cover over its soft backdrop, title, artist, description, actions, the tracklist, then shelves. */
@Composable
fun AlbumScreen(id: String) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val model = rememberPageModel { AlbumModel(id, services.api, it, appScope) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(CollectionTitleThreshold)
    LaunchedEffect(Unit) { model.loadIfNeeded() }
    val page = model.state.valueOrNull

    PageFrame(
        page?.album?.title ?: "", isTitleShown,
        backdrop = page?.let { { CollectionBackdrop(it.album.artwork, list) } },
    ) {
        LoadStateView(model.state, retry = { model.retry() }, style = LoadingStyle.COLLECTION) { loaded ->
            RefreshBox({ model.refresh() }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("album.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "header") { AlbumHeader(loaded, model) }
                    val context = PlayContext.InList(loaded.tracks, ItemActions.source(loaded.album))
                    itemsIndexed(loaded.tracks, key = { index, _ -> "track:$index" }) { index, track ->
                        SongRow(track, leading = RowLeading.TrackNumber(index + 1), context = context)
                    }
                    loaded.statsText?.let { stats ->
                        item(key = "stats") {
                            Text(
                                stats, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = BoomMetrics.margin)
                                    .padding(top = 16.dp),
                            )
                        }
                    }
                    items(loaded.shelves, key = { "shelf:" + it.id }) { shelf ->
                        ShelfView(shelf, Modifier.padding(top = BoomMetrics.shelfSpacing))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(page: AlbumPage, model: AlbumModel) {
    val actions = rememberItemActions()
    val services = actions.services
    val navigator = actions.navigator
    val session by services.session.state.collectAsState()
    val album = page.album
    val source = ItemActions.source(album)
    CollectionHeader(
        album.artwork, album.title, isExplicit = album.isExplicit, description = page.description,
        lines = {
            album.artists.firstOrNull()?.let { artist ->
                val artistId = artist.id
                Text(
                    album.artists.joinToString(", ") { it.name },
                    style = BoomFont.itemSubtitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .then(
                            if (artistId != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = "Open the artist") {
                                    navigator.open(BrowseEndpoint(artistId, pageType = BrowseEndpoint.PageType.ARTIST))
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("album.artist"),
                )
            }
            Text(listOf(album.typeText ?: "Album", album.year ?: "").filter { it.isNotEmpty() }.joinToString(" • "))
        },
        actions = {
            val download = CollectionDownloadSource(
                DownloadCollection.Kind.ALBUM, DownloadCollector.entry(page), page.tracks, continuation = null,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CollectionActions(
                    play = { actions.playList(page.tracks, shuffled = false, source = source) },
                    leading = {
                        if (page.libraryTokens != null) {
                            val saved = model.library.isOn
                            CircleIconButton(
                                if (saved) Icons.Filled.Check else Icons.Filled.Add,
                                if (saved) "Remove from library" else "Save to library",
                                { if (session.isSignedIn) model.toggleLibrary() else navigator.signIn() },
                                Modifier.testTag("collection.save"), isActive = saved,
                            )
                        }
                        CollectionDownloadButton(download)
                    },
                    trailing = {
                        // Share lives in the menu: the row keeps two buttons a side.
                        CircleIconButton(Icons.Filled.Shuffle, "Shuffle", { actions.playList(page.tracks, shuffled = true, source = source) })
                        CircleMenuButton(MusicItem.OfAlbum(album))
                    },
                )
                CollectionDownloadStatus(download)
            }
        },
    )
}
