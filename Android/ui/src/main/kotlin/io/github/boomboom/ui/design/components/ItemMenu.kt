package io.github.boomboom.ui.design

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.Song
import kotlinx.coroutines.launch

/**
 * The newest rating sent from a menu for each song that has one on its way: when two ratings of one song cross, only
 * the newest refusal may put the old one back. Touched on the main thread only.
 */
private object RatingRequests {
    private val newest = HashMap<String, Int>()
    private var count = 0

    fun begin(videoId: String): Int {
        count += 1
        newest[videoId] = count
        return count
    }

    fun isNewest(request: Int, videoId: String) = newest[videoId] == request

    fun end(request: Int, videoId: String) {
        if (newest[videoId] == request) newest.remove(videoId)
    }
}

/** Which list the menu shows: its entries, or the artists of an item credited to several. */
private sealed class MenuPage {
    data object Entries : MenuPage()
    data class Artists(val artists: List<ArtistRef>) : MenuPage()
}

/** An entry a screen adds after an item's usual ones ("Edit" and "Delete" on a playlist of the listener's own). */
data class MenuExtra(
    val title: String,
    val icon: ImageVector,
    val tag: String,
    val isDestructive: Boolean = false,
    val action: () -> Unit,
)

/**
 * An item's menu as a bottom sheet: Play next, Add to queue, Start radio; Save to playlist, Like, Download; Go to
 * album, Go to artist, Share. Shown by the row's or card's own "more" button or long press, which owns its open
 * state.
 *
 * @param likeOverride Where a row keeps a rating made from this menu, so the next opening shows it.
 * @param extras Entries the screen adds after the usual ones, below a line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMenuSheet(
    item: MusicItem,
    onDismiss: () -> Unit,
    likeOverride: MutableState<LikeStatus?>? = null,
    extras: List<MenuExtra> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<MenuPage>(MenuPage.Entries) }
    // Every entry acts at once, then the sheet slides away.
    val act: (() -> Unit) -> Unit = { action ->
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BoomColor.surface,
        contentColor = BoomColor.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.testTag("menu"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            MenuHeader(item)
            HorizontalDivider(color = BoomColor.separator, modifier = Modifier.padding(vertical = 4.dp))
            when (val shown = page) {
                MenuPage.Entries -> {
                    MenuEntries(item, likeOverride, act) { page = MenuPage.Artists(it) }
                    if (extras.isNotEmpty()) {
                        HorizontalDivider(color = BoomColor.separator, modifier = Modifier.padding(vertical = 4.dp))
                        for (extra in extras) {
                            MenuEntry(
                                extra.title, extra.icon, extra.tag,
                                tint = if (extra.isDestructive) BoomColor.destructive else BoomColor.textPrimary,
                            ) { act(extra.action) }
                        }
                    }
                }
                is MenuPage.Artists -> {
                    MenuEntry("Back", Icons.AutoMirrored.Filled.ArrowBack, "menu.back") { page = MenuPage.Entries }
                    val navigator = LocalBoomNavigator.current
                    for (artist in shown.artists) {
                        val id = artist.id ?: continue
                        MenuEntry(artist.name, Icons.Outlined.Person, "menu.artist") {
                            act { navigator.open(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.ARTIST)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(item: MusicItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        ArtworkView(
            item.artwork, width = if (item.isVideo) 48.dp * 16 / 9 else 48.dp, height = 48.dp,
            isCircle = item.isArtist,
        )
        Column(Modifier.weight(1f)) {
            Text(item.title, style = BoomFont.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MenuEntries(
    item: MusicItem,
    likeOverride: MutableState<LikeStatus?>?,
    act: (() -> Unit) -> Unit,
    showArtists: (List<ArtistRef>) -> Unit,
) {
    val actions = rememberItemActions()
    val navigator = actions.navigator
    val context = LocalContext.current

    MenuEntry("Play next", Icons.Filled.QueuePlayNext, "menu.playNext") { act { actions.enqueue(item, next = true) } }
    MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic, "menu.addToQueue") {
        act { actions.enqueue(item, next = false) }
    }
    MenuEntry("Start radio", Icons.Outlined.Sensors, "menu.startRadio") { act { actions.startRadio(item) } }

    val isCollection = item is MusicItem.OfAlbum || item is MusicItem.OfPlaylist
    if (item is MusicItem.OfSong || isCollection) {
        MenuEntry("Save to playlist", Icons.AutoMirrored.Filled.PlaylistAdd, "menu.saveToPlaylist") {
            act {
                actions.launch {
                    val songs = actions.tracks(item)
                    if (songs.isNotEmpty()) navigator.addToPlaylist(songs)
                }
            }
        }
    }
    item.asSong?.let { song -> LikeEntry(song, likeOverride, act) }
    DownloadEntries(item, act)

    val album = item.asSong?.album
    val albumId = album?.id
    if (albumId != null) {
        MenuEntry("Go to album", Icons.Outlined.Album, "menu.goToAlbum") {
            act { navigator.open(BrowseEndpoint(albumId, pageType = BrowseEndpoint.PageType.ALBUM)) }
        }
    }
    val artists = when (item) {
        is MusicItem.OfSong -> item.song.artists
        is MusicItem.OfAlbum -> item.album.artists
        else -> emptyList()
    }.filter { it.id != null }
    if (artists.size == 1) {
        val id = artists[0].id!!
        MenuEntry("Go to artist", Icons.Outlined.Person, "menu.goToArtist") {
            act { navigator.open(BrowseEndpoint(id, pageType = BrowseEndpoint.PageType.ARTIST)) }
        }
    } else if (artists.size > 1) {
        MenuEntry("Go to artist", Icons.Outlined.Person, "menu.goToArtist") { showArtists(artists) }
    }
    MenuEntry("Share", Icons.Outlined.Share, "menu.share") {
        act {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, item.shareUrl)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun LikeEntry(song: Song, likeOverride: MutableState<LikeStatus?>?, act: (() -> Unit) -> Unit) {
    val actions = rememberItemActions()
    val services = actions.services
    val player by services.player.state.collectAsState()
    val isCurrent = player.current?.videoId == song.videoId
    val status = if (isCurrent) player.likeStatus else likeOverride?.value ?: song.likeStatus
    val liked = status == LikeStatus.LIKE
    MenuEntry(
        if (liked) "Remove like" else "Like",
        if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        "menu.like",
    ) {
        act {
            // A rating needs an account: signed out, it would only flip and silently flip back.
            if (!services.session.isSignedIn) {
                actions.navigator.signIn()
                return@act
            }
            val newStatus = if (liked) LikeStatus.INDIFFERENT else LikeStatus.LIKE
            likeOverride?.value = newStatus
            if (isCurrent) {
                services.player.setLike(newStatus)
            } else {
                val request = RatingRequests.begin(song.videoId)
                actions.launch {
                    val sent = quietly { services.api.rate(song.videoId, newStatus) } != null
                    // A newer rating of the song decides what shows; only the newest refusal puts this one back.
                    if (!sent && RatingRequests.isNewest(request, song.videoId)) likeOverride?.value = status
                    RatingRequests.end(request, song.videoId)
                }
            }
        }
    }
}

/** "Download", or "Remove download" once the song (or the album or playlist) is kept or on its way. */
@Composable
private fun DownloadEntries(item: MusicItem, act: (() -> Unit) -> Unit) {
    val services = LocalAppServices.current
    val downloads = services.downloads
    val state by downloads.state.collectAsState()
    when (item) {
        is MusicItem.OfSong -> {
            val song = item.song
            if (!song.isAvailable) return
            when (state.stateOf(song.videoId)) {
                null -> MenuEntry("Download", Icons.Outlined.Download, "menu.download") { act { downloads.download(song) } }
                is DownloadItem.State.Failed -> {
                    MenuEntry("Try the download again", Icons.Outlined.Refresh, "menu.retryDownload") {
                        act { downloads.download(song) }
                    }
                    RemoveDownloadEntry { act { downloads.remove(setOf(song.videoId)) } }
                }
                else -> RemoveDownloadEntry { act { downloads.remove(setOf(song.videoId)) } }
            }
        }
        is MusicItem.OfAlbum -> CollectionEntries(DownloadCollection.Kind.ALBUM, item.album.id, act)
        is MusicItem.OfPlaylist -> CollectionEntries(DownloadCollection.Kind.PLAYLIST, item.playlist.id, act)
        is MusicItem.OfArtist -> Unit
    }
}

@Composable
private fun CollectionEntries(kind: DownloadCollection.Kind, id: String, act: (() -> Unit) -> Unit) {
    val services = LocalAppServices.current
    val downloads = services.downloads
    val state by downloads.state.collectAsState()
    val collector = LocalDownloadCollector.current
    val key = DownloadCollector.key(id)
    when {
        collector.isCollecting(key) ->
            MenuEntry("Stop downloading", Icons.Outlined.Cancel, "menu.stopDownload") { act { collector.cancel(key) } }
        state.collections.any { it.id == key } ->
            RemoveDownloadEntry { act { downloads.removeCollection(key) } }
        else -> MenuEntry("Download", Icons.Outlined.Download, "menu.download") {
            act { collector.download(kind, key, services.api, downloads) }
        }
    }
}

@Composable
private fun RemoveDownloadEntry(onClick: () -> Unit) {
    MenuEntry("Remove download", Icons.Outlined.Delete, "menu.removeDownload", tint = BoomColor.destructive, onClick = onClick)
}

@Composable
private fun MenuEntry(
    title: String,
    icon: ImageVector,
    tag: String,
    tint: Color = BoomColor.textPrimary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(title, style = BoomFont.itemTitle, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
