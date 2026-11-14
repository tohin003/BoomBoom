package io.github.boomboom.ui.browse.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.collections.freedBytes
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ConfirmDialog
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import io.github.boomboom.ui.design.songsText

/**
 * One downloaded album or playlist, from the phone alone: its songs in its own order, Play and Shuffle, a swipe or a
 * row's button to remove a song, and removing the whole of it. Opens with no connection.
 */
@Composable
fun DownloadedCollectionScreen(id: String) {
    val services = LocalAppServices.current
    val store = services.downloads
    val router = LocalShellRouter.current
    val state by store.state.collectAsState()
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(260.dp)
    var isAskingRemove by remember { mutableStateOf(false) }
    val collection = state.collections.firstOrNull { it.id == id }

    PageFrame(collection?.title ?: "", isTitleShown) {
        if (collection == null) {
            MessageView("Removed", message = "This album or playlist is no longer downloaded.", icon = Icons.Outlined.DownloadForOffline)
        } else {
            val songs = state.songsOf(id)
            val playable = songs.filter { it.isDownloaded }.map { it.song }
            val source = DownloadsMath.source(collection)
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag("downloadedCollection.list"),
                state = list,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    Header(collection, songs, state.bytesOf(id), canPlay = playable.isNotEmpty(),
                        onPlay = { services.player.play(playable, startAt = 0, source = source) },
                        onShuffle = { services.player.play(playable.shuffled(), startAt = 0, source = source) },
                    )
                }
                items(songs, key = { "song:" + it.id }) { item ->
                    SwipeToRemove(enabled = true, onRemove = { store.remove(setOf(item.id)) }, modifier = Modifier.animateItem(fadeInSpec = null)) {
                        DownloadSongRow(
                            item,
                            DownloadRowMode.Normal {
                                val index = playable.indexOfFirst { it.videoId == item.id }
                                if (index >= 0) services.player.play(playable, startAt = index, source = source) else services.player.play(item.song)
                            },
                            isWaitingForWiFi = state.isWaitingForWiFi,
                            onRetry = { retry(item, store) },
                            onRemove = { store.remove(setOf(item.id)) },
                        )
                    }
                }
                item(key = "remove") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        PillButton("Remove download", { isAskingRemove = true }, Modifier.testTag("downloads.removeCollectionButton"))
                    }
                }
            }
            if (isAskingRemove) {
                ConfirmDialog(
                    "Remove “${collection.title}” from downloads?",
                    "This frees ${DownloadSize.text(freedBytes(setOf(id), state))}. Songs you also downloaded on their own, or " +
                        "that another download holds, stay.",
                    confirm = "Remove download", tag = "downloads.removeCollection",
                    onConfirm = {
                        store.removeCollection(id)
                        router.back()
                    },
                    onDismiss = { isAskingRemove = false },
                )
            }
        }
    }
}

@Composable
private fun Header(
    collection: DownloadCollection,
    songs: List<DownloadItem>,
    bytes: Long,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val done = songs.count { it.isDownloaded }
    val size = DownloadSize.text(bytes)
    val summary = if (done < songs.size) "$done of ${songs.size} downloaded · $size" else "${songsText(songs.size)} · $size"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkView(
            collection.artwork, width = 180.dp, cornerRadius = BoomMetrics.cardRadius,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .shadow(16.dp, RoundedCornerShape(BoomMetrics.cardRadius), ambientColor = Color.Black, spotColor = Color.Black),
        )
        Text(
            collection.title, style = BoomFont.shelfTitle, color = BoomColor.textPrimary, textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { heading() }
                .testTag("downloadedCollection.title"),
        )
        Text(DownloadsMath.kindLine(collection), style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, textAlign = TextAlign.Center)
        Text(summary, style = BoomFont.itemSubtitle.copy(fontFeatureSettings = "tnum"), color = BoomColor.textTertiary)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Play", onPlay, Modifier.testTag("downloadedCollection.play"), style = PillStyle.FILLED, icon = Icons.Filled.PlayArrow, enabled = canPlay)
            PillButton("Shuffle", onShuffle, style = PillStyle.TONAL, icon = Icons.Filled.Shuffle, enabled = canPlay)
        }
    }
}
