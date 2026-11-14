package io.github.boomboom.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.ShelfView
import kotlinx.coroutines.launch

/**
 * The "Related" tab: YouTube Music's shelves for the song, drawn with the app's own shelves. A song plays with its
 * radio; an album, artist or playlist closes the player and opens in the frame.
 */
@Composable
internal fun RelatedPanel(extras: PlayerExtras) {
    val player = LocalAppServices.current.player
    val state by player.state.collectAsState()
    val song = state.current?.videoId
    val scope = rememberCoroutineScope()
    LaunchedEffect(song) { extras.loadRelated(player) }
    Box(Modifier.fillMaxSize().testTag("related")) {
        when (val shown = extras.related(song)) {
            PlayerExtras.Shown.Loading -> CircularProgressIndicator(
                color = BoomColor.textSecondary, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center),
            )
            PlayerExtras.Shown.Failed -> PanelMessage(Icons.Outlined.WifiOff, "Couldn't load related music") {
                scope.launch { extras.loadRelated(player) }
            }
            is PlayerExtras.Shown.Ready -> {
                val shelves = shown.value
                if (shelves.isEmpty()) {
                    PanelMessage(Icons.Outlined.LibraryMusic, "Nothing related to show")
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .testTag("related.list"),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(BoomMetrics.shelfSpacing),
                    ) {
                        itemsIndexed(shelves, key = { index, shelf -> "$index|${shelf.id}" }) { _, shelf -> ShelfView(shelf) }
                    }
                }
            }
        }
    }
}
