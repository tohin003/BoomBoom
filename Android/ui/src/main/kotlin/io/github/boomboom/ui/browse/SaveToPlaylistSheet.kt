package io.github.boomboom.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.PlaylistPrivacy
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.quietly
import kotlinx.coroutines.launch

/** "Save to playlist": the listener's own playlists, and a way to make a new one with the songs in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<LoadState<List<PlaylistItem>>>(LoadState.Loading) }
    var message by remember { mutableStateOf<String?>(null) }
    var isNaming by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    LaunchedEffect(Unit) {
        val page = quietly { services.api.library(LibrarySection.PLAYLISTS) }
        load = if (page == null) {
            LoadState.Failed("Couldn't load your playlists.")
        } else {
            // Only playlists the listener can add to: not the built-in "Liked music" and the like.
            LoadState.Loaded(page.shelves.flatMap { it.items }.mapNotNull { it.asPlaylist }.filter { it.id.startsWith("PL") })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BoomColor.surface,
        scrimColor = Color.Black.copy(alpha = 0.6f), modifier = Modifier.testTag("saveToPlaylist"),
    ) {
        Text(
            "Save to playlist", style = BoomFont.sectionTitle, color = BoomColor.textPrimary,
            modifier = Modifier.padding(horizontal = BoomMetrics.margin, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { isNaming = true }
                        .padding(horizontal = BoomMetrics.margin),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
                ) {
                    Icon(Icons.Filled.Add, null, tint = BoomColor.accent, modifier = Modifier.size(24.dp))
                    Text("New playlist", style = BoomFont.itemTitle, color = BoomColor.accent)
                }
            }
            message?.let { text ->
                item { Text(text, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, modifier = Modifier.padding(BoomMetrics.margin)) }
            }
            when (val shown = load) {
                LoadState.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BoomColor.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
                is LoadState.Failed -> item {
                    Text(shown.message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, modifier = Modifier.padding(BoomMetrics.margin))
                }
                is LoadState.Loaded -> {
                    if (shown.value.isEmpty()) item {
                        Text(
                            "No playlists yet. Make one above.", style = BoomFont.itemSubtitle,
                            color = BoomColor.textSecondary, modifier = Modifier.padding(BoomMetrics.margin),
                        )
                    }
                    items(shown.value, key = { it.id }) { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    appScope.launch {
                                        val saved = quietly { services.api.addToPlaylist(playlist.id, songs.map { it.videoId }) } != null
                                        if (saved) close() else message = "Couldn't save to ${playlist.title}."
                                    }
                                }
                                .padding(horizontal = BoomMetrics.margin, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
                        ) {
                            ArtworkView(playlist.artwork, width = BoomMetrics.rowCover)
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                playlist.detailText?.let {
                                    Text(it, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (isNaming) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { isNaming = false },
            containerColor = BoomColor.surfaceRaised,
            title = { Text("New playlist", style = BoomFont.sectionTitle) },
            text = {
                OutlinedTextField(
                    title, { title = it }, label = { Text("Name") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BoomColor.accent, cursorColor = BoomColor.accent, focusedLabelColor = BoomColor.accent),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = title.trim()
                        if (name.isEmpty()) return@TextButton
                        isNaming = false
                        appScope.launch {
                            val made = quietly {
                                services.api.createPlaylist(name, "", PlaylistPrivacy.PRIVATE, songs.map { it.videoId })
                            }
                            if (made != null) close() else message = "Couldn't create the playlist."
                        }
                    },
                ) { Text("Create", color = BoomColor.accent) }
            },
            dismissButton = { TextButton({ isNaming = false }) { Text("Cancel", color = BoomColor.textSecondary) } },
        )
    }
}
