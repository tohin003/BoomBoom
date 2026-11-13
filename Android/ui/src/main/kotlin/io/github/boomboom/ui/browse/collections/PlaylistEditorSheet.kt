package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.models.PlaylistPrivacy
import io.github.boomboom.models.Song
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.quietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val PlaylistPrivacy.title: String
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> "Private"
        PlaylistPrivacy.UNLISTED -> "Unlisted"
        PlaylistPrivacy.PUBLIC -> "Public"
    }

val PlaylistPrivacy.explanation: String
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> "Only you can see this playlist."
        PlaylistPrivacy.UNLISTED -> "Anyone with the link can see it. It does not show up in search."
        PlaylistPrivacy.PUBLIC -> "Anyone can find and play this playlist."
    }

/** Where the editor's own requests stand. One at a time. */
private sealed class EditorStatus {
    data object Idle : EditorStatus()
    data object Saving : EditorStatus()
    data object Deleting : EditorStatus()
    data class Failed(val message: String) : EditorStatus()

    val isBusy: Boolean get() = this == Saving || this == Deleting
}

/** A song can sit in a playlist twice; the entry's own id tells the two apart. */
private val Song.entryKey: String get() = videoId + "|" + (setVideoId ?: "")

/**
 * Edit one of the listener's playlists: its name, description and privacy, which songs it holds, or delete it.
 *
 * Removing a song takes effect at once on screen and is put back, with a message, if the service refuses. Name,
 * description and privacy are sent with "Save".
 *
 * @param onChanged Called after anything was saved or a song was removed, so the page behind can reload.
 * @param onDeleted Called after the playlist was deleted (the sheet has closed): the page behind should go back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditorSheet(page: PlaylistPage, onChanged: () -> Unit, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<EditorStatus>(EditorStatus.Idle) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !status.isBusy })
    var title by remember { mutableStateOf(page.playlist.title) }
    var details by remember { mutableStateOf(page.description ?: "") }
    /** Null keeps the privacy as it is: a playlist page does not say what it currently is. */
    var privacy by remember { mutableStateOf<PlaylistPrivacy?>(null) }
    var songs by remember { mutableStateOf(page.tracks) }
    var continuation by remember { mutableStateOf(page.continuation) }
    var removalNotice by remember { mutableStateOf<String?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }

    val trimmedTitle = title.trim()
    val trimmedDetails = details.trim()
    val hasChanges = trimmedTitle != page.playlist.title || trimmedDetails != (page.description ?: "") || privacy != null
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    // A long playlist arrives in parts; the rest is fetched so that every song can be removed.
    LaunchedEffect(Unit) {
        while (true) {
            val token = continuation ?: break
            val more = quietly { services.api.playlistContinuation(token) }
            if (more == null) {
                continuation = null
                break
            }
            val known = songs.map { it.entryKey }.toHashSet()
            songs = songs + more.songs.filter { it.entryKey !in known }
            continuation = more.continuation
        }
    }

    fun save() {
        if (!hasChanges || trimmedTitle.isEmpty() || status.isBusy) return
        status = EditorStatus.Saving
        val newTitle = trimmedTitle.takeIf { it != page.playlist.title }
        val newDetails = trimmedDetails.takeIf { it != (page.description ?: "") }
        val newPrivacy = privacy
        appScope.launch {
            try {
                services.api.editPlaylist(page.playlist.id, newTitle, newDetails, newPrivacy)
                onChanged()
                status = EditorStatus.Idle
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = EditorStatus.Failed("Couldn't save your changes. " + userMessage(failure))
            }
        }
    }

    fun delete() {
        if (status.isBusy) return
        status = EditorStatus.Deleting
        appScope.launch {
            try {
                services.api.deletePlaylist(page.playlist.id)
                onChanged()
                status = EditorStatus.Idle
                close()
                onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = EditorStatus.Failed("Couldn't delete the playlist. " + userMessage(failure))
            }
        }
    }

    fun remove(song: Song) {
        val index = songs.indexOfFirst { it.entryKey == song.entryKey }
        if (index < 0) return
        songs = songs.toMutableList().also { it.removeAt(index) }
        appScope.launch {
            try {
                services.api.removeFromPlaylist(page.playlist.id, listOf(song))
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (songs.none { it.entryKey == song.entryKey }) {
                    songs = songs.toMutableList().also { it.add(minOf(index, it.size), song) }
                }
                removalNotice = "“${song.title}” is still in the playlist. " + userMessage(failure)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!status.isBusy) onDismiss() },
        sheetState = sheetState,
        containerColor = BoomColor.surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
        modifier = Modifier.testTag("playlist.editor"),
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(close, enabled = !status.isBusy) { Text(if (hasChanges) "Cancel" else "Done", color = BoomColor.accent) }
                Text(
                    "Edit playlist", style = BoomFont.sectionTitle, color = BoomColor.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    if (status == EditorStatus.Saving) {
                        CircularProgressIndicator(color = BoomColor.accent, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 20.dp).size(20.dp))
                    } else {
                        TextButton(::save, enabled = hasChanges && trimmedTitle.isNotEmpty() && !status.isBusy, modifier = Modifier.testTag("editor.save")) {
                            Text("Save", fontWeight = FontWeight.SemiBold, color = if (hasChanges && trimmedTitle.isNotEmpty()) BoomColor.accent else BoomColor.textTertiary)
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                item(key = "details") {
                    DetailsSection(
                        title, { title = it }, details, { details = it }, privacy, { privacy = it }, status,
                    )
                }
                item(key = "songsHeader") {
                    Text(
                        if (songs.size == 1) "1 song" else "${songs.size} songs", style = BoomFont.button,
                        color = BoomColor.textSecondary,
                        modifier = Modifier.padding(horizontal = BoomMetrics.margin).padding(top = 20.dp, bottom = 8.dp),
                    )
                }
                items(songs, key = { "song:" + it.entryKey }) { song -> EditorSongRow(song) { remove(song) } }
                if (continuation != null) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = BoomColor.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                    }
                } else if (songs.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "This playlist is empty", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                            modifier = Modifier.padding(horizontal = BoomMetrics.margin, vertical = 12.dp),
                        )
                    }
                }
                item(key = "delete") {
                    Box(
                        Modifier
                            .padding(BoomMetrics.margin)
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(BoomMetrics.cardRadius))
                            .background(BoomColor.surfaceRaised)
                            .clickable(enabled = !status.isBusy, role = Role.Button) { isConfirmingDelete = true }
                            .heightIn(min = 52.dp)
                            .testTag("editor.delete"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (status == EditorStatus.Deleting) {
                            CircularProgressIndicator(color = BoomColor.destructive, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Text("Delete playlist", style = BoomFont.itemTitle, color = BoomColor.destructive)
                        }
                    }
                    // Raised by the delete button that asks it.
                    if (isConfirmingDelete) {
                        AlertDialog(
                            onDismissRequest = { isConfirmingDelete = false },
                            containerColor = BoomColor.surfaceRaised,
                            title = { Text("Delete “${page.playlist.title}”?", style = BoomFont.sectionTitle) },
                            text = {
                                Text(
                                    "The playlist and the order of its songs will be gone. The songs themselves stay on BoomBoom.",
                                    style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                                )
                            },
                            confirmButton = {
                                TextButton({ isConfirmingDelete = false; delete() }, Modifier.testTag("editor.delete.confirm")) {
                                    Text("Delete playlist", color = BoomColor.destructive)
                                }
                            },
                            dismissButton = { TextButton({ isConfirmingDelete = false }) { Text("Cancel", color = BoomColor.accent) } },
                        )
                    }
                }
            }
        }
    }

    removalNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { removalNotice = null },
            containerColor = BoomColor.surfaceRaised,
            title = { Text("Couldn't remove the song", style = BoomFont.sectionTitle) },
            text = { Text(message, style = BoomFont.itemSubtitle) },
            confirmButton = { TextButton({ removalNotice = null }) { Text("OK", color = BoomColor.accent) } },
        )
    }
}

@Composable
private fun DetailsSection(
    title: String,
    onTitle: (String) -> Unit,
    details: String,
    onDetails: (String) -> Unit,
    privacy: PlaylistPrivacy?,
    onPrivacy: (PlaylistPrivacy?) -> Unit,
    status: EditorStatus,
) {
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BoomColor.accent, cursorColor = BoomColor.accent, focusedLabelColor = BoomColor.accent,
    )
    var isPickingPrivacy by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title, onTitle, label = { Text("Name") }, singleLine = true, enabled = !status.isBusy, colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor.name"),
        )
        OutlinedTextField(
            details, onDetails, label = { Text("Description (optional)") }, maxLines = 4, enabled = !status.isBusy,
            colors = colors, modifier = Modifier.fillMaxWidth(),
        )
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !status.isBusy, role = Role.DropdownList) { isPickingPrivacy = true }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Privacy", style = BoomFont.itemTitle, color = BoomColor.textPrimary, modifier = Modifier.weight(1f))
                Text(privacy?.title ?: "Keep as is", style = BoomFont.itemTitle, color = BoomColor.textSecondary)
                Icon(Icons.Filled.ArrowDropDown, null, tint = BoomColor.textSecondary)
            }
            DropdownMenu(isPickingPrivacy, { isPickingPrivacy = false }, containerColor = BoomColor.surfaceRaised) {
                DropdownMenuItem({ Text("Keep as is") }, { onPrivacy(null); isPickingPrivacy = false })
                for (choice in PlaylistPrivacy.entries) {
                    DropdownMenuItem({ Text(choice.title) }, { onPrivacy(choice); isPickingPrivacy = false })
                }
            }
        }
        privacy?.let { Text(it.explanation, style = BoomFont.caption, color = BoomColor.textSecondary) }
        if (status is EditorStatus.Failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, tint = BoomColor.destructive, modifier = Modifier.size(16.dp))
                Text(status.message, style = BoomFont.caption, color = BoomColor.destructive)
            }
        }
    }
}

@Composable
private fun EditorSongRow(song: Song, remove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = BoomMetrics.margin, end = 4.dp)
            .alpha(if (song.isAvailable) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        ArtworkView(song.artwork, width = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistLine, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (song.setVideoId != null) {
            BarIconButton(
                Icons.Outlined.RemoveCircleOutline, "Remove ${song.title}", remove, Modifier.testTag("editor.remove"),
                tint = BoomColor.destructive, iconSize = 22.dp,
            )
        }
    }
}
