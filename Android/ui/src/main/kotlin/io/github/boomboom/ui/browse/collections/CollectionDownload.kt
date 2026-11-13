package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.DownloadsState
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.DownloadBadge
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.design.DownloadRing
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalDownloadCollector
import io.github.boomboom.ui.design.songsText

/**
 * What an album or playlist page hands its download button: which collection, and how to gather its songs from what
 * the page already holds.
 *
 * @param continuation The token of the playlist's next part, when more songs exist than the page has loaded.
 */
data class CollectionDownloadSource(
    val kind: DownloadCollection.Kind,
    val entry: DownloadCollection,
    val songs: List<Song>,
    val continuation: String?,
) {
    val id: String get() = entry.id
}

/**
 * The looks of an album's or playlist's download button. Decided in one place ([of]) from the collector and the
 * store, so the button and its status line can never disagree.
 */
sealed class CollectionDownloadLook {
    data object NotDownloaded : CollectionDownloadLook()

    /** Its songs are being gathered (a long playlist comes in parts). */
    data class Collecting(val found: Int) : CollectionDownloadLook()

    /** Songs still to come. */
    data class Arriving(val done: Int, val total: Int) : CollectionDownloadLook()

    data class Downloaded(val total: Int, val bytes: Long) : CollectionDownloadLook()

    /** Nothing left to come, but some songs could not be fetched. */
    data class PartlyFailed(val done: Int, val total: Int) : CollectionDownloadLook()

    /** Drawn in the accent: everything is on the phone. */
    val isComplete: Boolean get() = this is Downloaded

    val label: String
        get() = when (this) {
            NotDownloaded -> "Download"
            is Collecting, is Arriving -> "Downloading"
            is Downloaded -> "Downloaded"
            is PartlyFailed -> "Partly downloaded"
        }

    /** "12 of 40" and the like, for TalkBack and for tests. */
    val value: String
        get() = when (this) {
            NotDownloaded -> ""
            is Collecting -> "Collecting, $found found"
            is Arriving -> "$done of $total"
            is Downloaded -> "$total songs"
            is PartlyFailed -> "$done of $total, some failed"
        }

    fun question(title: String): String = when (this) {
        NotDownloaded -> ""
        is Collecting, is Arriving -> "Stop downloading “$title”?"
        is Downloaded -> "Remove “$title” from downloads?"
        is PartlyFailed -> "Some songs of “$title” couldn't be downloaded"
    }

    fun explanation(freed: Long): String = when (this) {
        NotDownloaded -> ""
        is Collecting, is Arriving ->
            "The songs downloaded so far are removed too. Songs you also downloaded on their own stay."
        is Downloaded ->
            "This frees ${DownloadSize.text(freed)}. Songs you also downloaded on their own, or that another download holds, stay."
        is PartlyFailed -> "$done of $total are on the phone. The others can be tried again."
    }

    companion object {
        fun of(id: String, state: DownloadsState, collecting: Map<String, Int>): CollectionDownloadLook {
            collecting[id]?.let { return Collecting(it) }
            if (state.collections.none { it.id == id }) return NotDownloaded
            val songs = state.songsOf(id)
            var done = 0
            var pending = 0
            for (song in songs) {
                when (song.state) {
                    DownloadItem.State.Downloaded -> done += 1
                    DownloadItem.State.Waiting, is DownloadItem.State.Downloading -> pending += 1
                    is DownloadItem.State.Failed -> Unit
                }
            }
            if (pending > 0) return Arriving(done, songs.size)
            if (done < songs.size) return PartlyFailed(done, songs.size)
            return Downloaded(songs.size, state.bytesOf(id))
        }
    }
}

/**
 * Space freed by removing these albums and playlists: their songs, less those the listener downloaded on their own or
 * another downloaded collection still holds (as `DownloadStore.removeCollection` keeps them).
 */
fun freedBytes(collections: Set<String>, state: DownloadsState): Long {
    val kept = state.collections.filter { it.id !in collections }.flatMap { it.songIds }.toHashSet() +
        state.items.filter { it.isStandalone }.map { it.id }
    val gone = state.collections.filter { it.id in collections }.flatMap { it.songIds }.toSet() - kept
    return state.items.sumOf { if (it.id in gone && it.isDownloaded) it.bytes else 0L }
}

/**
 * The round download button of an album's or playlist's header, in four looks: an arrow; a ring while songs arrive
 * (a spinner while the list is gathered); the accent when all are in; a warning when some could not be fetched.
 * Every tap but the first asks what to do, in a dialog raised by this button.
 */
@Composable
fun CollectionDownloadButton(source: CollectionDownloadSource) {
    val services = LocalAppServices.current
    val collector = LocalDownloadCollector.current
    val state by services.downloads.state.collectAsState()
    val look = CollectionDownloadLook.of(source.id, state, collector.collecting)
    /** The look the listener tapped: the dialog's words follow it, even if the look moves on while it is open. */
    var asked by remember { mutableStateOf<CollectionDownloadLook?>(null) }

    Box(
        Modifier
            .size(BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .background(if (look.isComplete) BoomColor.accent else Color.White.copy(alpha = 0.1f))
            .clickable(role = Role.Button) {
                if (look == CollectionDownloadLook.NotDownloaded) start(source, collector, services) else asked = look
            }
            .semantics {
                contentDescription = look.label
                stateDescription = look.value
            }
            .testTag("collection.download"),
        contentAlignment = Alignment.Center,
    ) {
        when (look) {
            CollectionDownloadLook.NotDownloaded ->
                Icon(Icons.Filled.Download, null, tint = BoomColor.textPrimary, modifier = Modifier.size(22.dp))
            is CollectionDownloadLook.Collecting -> CircularProgressIndicator(
                color = BoomColor.textPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
            )
            is CollectionDownloadLook.Arriving -> Box(contentAlignment = Alignment.Center) {
                DownloadRing(
                    if (look.total > 0) look.done.toDouble() / look.total else 0.0, Modifier.size(26.dp), lineWidth = 2.5.dp,
                )
                Icon(Icons.Filled.Stop, null, tint = BoomColor.accent, modifier = Modifier.size(12.dp))
            }
            is CollectionDownloadLook.Downloaded ->
                Icon(Icons.Filled.Download, null, tint = BoomColor.onAccent, modifier = Modifier.size(22.dp))
            is CollectionDownloadLook.PartlyFailed ->
                Icon(Icons.Filled.PriorityHigh, null, tint = BoomColor.destructive, modifier = Modifier.size(22.dp))
        }
    }

    asked?.let { shown ->
        val dismiss = { asked = null }
        val remove = {
            collector.cancel(source.id)
            services.downloads.removeCollection(source.id)
            asked = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            containerColor = BoomColor.surfaceRaised,
            modifier = Modifier.testTag("collection.download.dialog"),
            title = { Text(shown.question(source.entry.title), style = BoomFont.sectionTitle) },
            text = {
                Text(shown.explanation(freedBytes(setOf(source.id), state)), style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    when (shown) {
                        is CollectionDownloadLook.PartlyFailed -> {
                            DialogButton("Try the missing songs again") { retry(source, services); asked = null }
                            DialogButton("Remove download", destructive = true, onClick = remove)
                        }
                        is CollectionDownloadLook.Downloaded -> {
                            DialogButton("Remove download", destructive = true, onClick = remove)
                            // A playlist grows: downloading it again adds the songs that joined it since.
                            if (source.kind == DownloadCollection.Kind.PLAYLIST) {
                                DialogButton("Add new songs") { start(source, collector, services); asked = null }
                            }
                        }
                        is CollectionDownloadLook.Collecting, is CollectionDownloadLook.Arriving ->
                            DialogButton("Stop and remove", destructive = true, onClick = remove)
                        CollectionDownloadLook.NotDownloaded -> Unit
                    }
                    DialogButton("Cancel", onClick = dismiss)
                }
            },
        )
    }
}

@Composable
private fun DialogButton(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick) {
        Text(text, color = if (destructive) BoomColor.destructive else BoomColor.accent, style = BoomFont.button)
    }
}

private fun start(source: CollectionDownloadSource, collector: DownloadCollector, services: io.github.boomboom.logic.AppServices) {
    collector.download(
        source.kind, source.id, services.api, services.downloads,
        DownloadCollector.Seed(source.entry, source.songs, source.continuation),
    )
}

/** Asking for the collection again with its own songs sets its failed ones going; other failures stay put. */
private fun retry(source: CollectionDownloadSource, services: io.github.boomboom.logic.AppServices) {
    val store = services.downloads
    val entry = store.collections.firstOrNull { it.id == source.id } ?: return
    store.download(store.songsOf(source.id).map { it.song }, entry)
}

/**
 * The line under an album's or playlist's buttons that says where its download stands: "Collecting the list… 300
 * songs", "Downloading 12 of 40", "Downloaded · 40 songs · 212 MB", "2 couldn't be downloaded · Retry".
 */
@Composable
fun CollectionDownloadStatus(source: CollectionDownloadSource) {
    val services = LocalAppServices.current
    val collector = LocalDownloadCollector.current
    val state by services.downloads.state.collectAsState()
    val look = CollectionDownloadLook.of(source.id, state, collector.collecting)
    if (look == CollectionDownloadLook.NotDownloaded) return
    Column(
        Modifier.padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.testTag("collection.download.status"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DownloadBadge(badge(look), size = 12.dp)
            Text(statusText(look, state.isWaitingForWiFi), style = BoomFont.time, color = BoomColor.textSecondary)
            if (look is CollectionDownloadLook.PartlyFailed) {
                Text(
                    "Retry", style = BoomFont.caption.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.accent,
                    modifier = Modifier
                        .clickable(role = Role.Button) { retry(source, services) }
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                        .testTag("collection.download.retry"),
                )
            }
        }
        if (collector.capped[source.id] == true) {
            Text(
                "Only the first ${DownloadCollector.SONG_LIMIT} songs are downloaded.", style = BoomFont.caption,
                color = BoomColor.textTertiary,
            )
        }
    }
}

private fun badge(look: CollectionDownloadLook): DownloadItem.State? = when (look) {
    CollectionDownloadLook.NotDownloaded, is CollectionDownloadLook.Collecting -> null
    is CollectionDownloadLook.Arriving -> DownloadItem.State.Downloading(if (look.total > 0) look.done.toDouble() / look.total else 0.0)
    is CollectionDownloadLook.Downloaded -> DownloadItem.State.Downloaded
    is CollectionDownloadLook.PartlyFailed -> DownloadItem.State.Failed("")
}

private fun statusText(look: CollectionDownloadLook, waitingForWiFi: Boolean): String = when (look) {
    CollectionDownloadLook.NotDownloaded -> ""
    is CollectionDownloadLook.Collecting ->
        if (look.found > 0) "Collecting the list… ${songsText(look.found)}" else "Collecting the list…"
    is CollectionDownloadLook.Arriving ->
        if (waitingForWiFi) "${look.done} of ${look.total} · waiting for Wi-Fi" else "Downloading ${look.done} of ${look.total}"
    is CollectionDownloadLook.Downloaded -> "Downloaded · ${songsText(look.total)} · ${DownloadSize.text(look.bytes)}"
    is CollectionDownloadLook.PartlyFailed -> "${look.done} of ${look.total} downloaded · ${look.total - look.done} couldn't be"
}
