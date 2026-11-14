package io.github.boomboom.ui.player

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.SleepTimer
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.shareUrl
import io.github.boomboom.models.MusicItem
import kotlinx.coroutines.launch

/**
 * The player's menu, as a bottom sheet like every item menu in the app: Start radio, Save to playlist, Download, Go
 * to album, Go to artist, Share, the sleep timer's choices and the equaliser. The moon button opens it straight at
 * the sleep timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerMenuSheet(start: PlayerMenuPage, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val player = services.player
    val state by player.state.collectAsState()
    val downloads by services.downloads.state.collectAsState()
    val time = LocalPlayerTime.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(start) }
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
        modifier = Modifier.testTag("player.menu"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            val song = state.current
            when (page) {
                PlayerMenuPage.Main -> {
                    if (song != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = BoomMetrics.margin, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtworkView(song.artwork, width = 48.dp)
                            Spacer(Modifier.width(BoomMetrics.rowSpacing))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = BoomFont.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    song.artistLine, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider(color = BoomColor.separator, modifier = Modifier.padding(vertical = 4.dp))
                        MenuRow("Start radio", Icons.Outlined.Sensors, "player.menu.radio") { act { player.startRadio(song) } }
                        MenuRow("Save to playlist", Icons.AutoMirrored.Filled.PlaylistAdd, "player.menu.save") {
                            act { navigator.addToPlaylist(listOf(song)) }
                        }
                        when (downloads.stateOf(song.videoId)) {
                            null, is DownloadItem.State.Failed -> MenuRow("Download", Icons.Outlined.Download, "player.menu.download") {
                                act { services.downloads.download(song) }
                            }
                            else -> MenuRow(
                                "Remove download", Icons.Outlined.Delete, "player.menu.removeDownload", tint = BoomColor.destructive,
                            ) { act { services.downloads.remove(setOf(song.videoId)) } }
                        }
                        song.album?.id?.let { albumId ->
                            MenuRow("Go to album", Icons.Outlined.Album, "player.menu.album") {
                                act { navigator.open(BrowseEndpoint(albumId, pageType = BrowseEndpoint.PageType.ALBUM)) }
                            }
                        }
                        song.firstLinkedArtist?.id?.let { artistId ->
                            MenuRow("Go to artist", Icons.Outlined.Person, "player.menu.artist") {
                                act { navigator.open(BrowseEndpoint(artistId, pageType = BrowseEndpoint.PageType.ARTIST)) }
                            }
                        }
                        MenuRow("Share", Icons.Outlined.Share, "player.menu.share") {
                            act {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, song.title)
                                    putExtra(Intent.EXTRA_TEXT, MusicItem.OfSong(song).shareUrl)
                                }
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    MenuRow(
                        "Sleep timer", Icons.Outlined.Bedtime, "player.menu.sleepTimer",
                        detail = sleepTimerCountdown(state.sleepTimer), opensPage = true,
                    ) { page = PlayerMenuPage.SleepTimer }
                    MenuRow("Equaliser", Icons.Filled.Tune, "player.menu.equaliser") { act { navigator.openEqualizer() } }
                }
                PlayerMenuPage.SleepTimer -> {
                    if (start == PlayerMenuPage.Main) {
                        MenuRow("Back", Icons.AutoMirrored.Filled.ArrowBack, "player.menu.back") { page = PlayerMenuPage.Main }
                    }
                    Text(
                        "Sleep timer", style = BoomFont.sectionTitle, color = BoomColor.textPrimary,
                        modifier = Modifier.padding(horizontal = BoomMetrics.margin, vertical = 10.dp),
                    )
                    for (minutes in sleepTimerMinutes) {
                        MenuRow("$minutes minutes", Icons.Outlined.Timer, "player.sleep.$minutes") {
                            act { player.setSleepTimer(SleepTimer.At(time.nowMillis() + minutes * 60_000L)) }
                        }
                    }
                    MenuRow(
                        "End of song", Icons.Outlined.Timer, "player.sleep.endOfSong",
                        checked = state.sleepTimer == SleepTimer.EndOfSong,
                    ) { act { player.setSleepTimer(SleepTimer.EndOfSong) } }
                    if (state.sleepTimer != null) {
                        MenuRow("Turn off timer", Icons.Outlined.TimerOff, "player.sleep.off", tint = BoomColor.destructive) {
                            act { player.setSleepTimer(null) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    icon: ImageVector,
    tag: String,
    tint: Color = BoomColor.textPrimary,
    detail: String? = null,
    opensPage: Boolean = false,
    checked: Boolean = false,
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
        Text(title, style = BoomFont.itemTitle, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = BoomFont.time, color = BoomColor.accent)
        if (checked) Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = BoomColor.accent, modifier = Modifier.size(22.dp))
        if (opensPage) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = BoomColor.textTertiary)
        }
    }
}
