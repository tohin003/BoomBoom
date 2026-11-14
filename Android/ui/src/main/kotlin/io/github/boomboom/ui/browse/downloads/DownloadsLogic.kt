package io.github.boomboom.ui.browse.downloads

import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.DownloadsState
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.songsText
import java.text.Collator
import java.util.Locale

// The Downloads screen's states and sums, kept free of Compose so they read plainly and can be checked alone.

/** The two ways of looking at downloads. */
enum class DownloadsView(val title: String) {
    SONGS("Songs"),
    COLLECTIONS("Albums & playlists"),
}

/** The order of the Songs view. */
enum class DownloadSort(val rawValue: String, val title: String) {
    /** Newest first: the store's own order. */
    RECENT("recent", "Recently added"),
    TITLE("title", "Title"),

    /** Largest first: the one for freeing space. */
    SIZE("size", "Size"),
    ;

    fun sorted(items: List<DownloadItem>): List<DownloadItem> = when (this) {
        RECENT -> items
        TITLE -> {
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
            items.sortedWith { a, b -> collator.compare(a.song.title, b.song.title) }
        }
        // Songs still to come weigh nothing yet: they go last, newest first among themselves (the sort is stable).
        SIZE -> items.sortedByDescending { it.bytes }
    }

    companion object {
        fun of(raw: String?): DownloadSort = entries.firstOrNull { it.rawValue == raw } ?: RECENT
    }
}

/** Browsing, or picking rows to delete. The one place select mode lives. */
sealed class DownloadsMode {
    data object Browsing : DownloadsMode()

    /** The ids picked: songs' video ids in the Songs view, collections' ids in the other. */
    data class Selecting(val ids: Set<String>) : DownloadsMode()

    val isSelecting: Boolean get() = this is Selecting

    val selection: Set<String> get() = (this as? Selecting)?.ids ?: emptySet()

    /** A tap on a row while selecting. */
    fun toggling(id: String): DownloadsMode {
        val selecting = this as? Selecting ?: return this
        return Selecting(if (id in selecting.ids) selecting.ids - id else selecting.ids + id)
    }
}

/** What the Downloads screen may be asking. Only one question shows at a time. */
sealed class DownloadsQuestion {
    data object None : DownloadsQuestion()
    data object DeleteSelection : DownloadsQuestion()
    data object RemoveAll : DownloadsQuestion()

    /** "Remove all" asked a second time: it is the destructive one. */
    data object RemoveAllAgain : DownloadsQuestion()
    data object MobileData : DownloadsQuestion()

    /** A whole album or playlist swiped away: it goes only once the listener says so. */
    data class RemoveCollection(val collection: DownloadCollection) : DownloadsQuestion()
}

/** Sums and words for the screen. */
object DownloadsMath {
    /** Space freed by removing these songs: what of them is on the phone. */
    fun freedBytes(songs: Set<String>, state: DownloadsState): Long =
        state.items.sumOf { if (it.id in songs && it.isDownloaded) it.bytes else 0L }

    /** The line under a pending or failed song, or its size and quality once downloaded. */
    fun detail(item: DownloadItem, waitingForWiFi: Boolean): String = when (val state = item.state) {
        DownloadItem.State.Downloaded ->
            listOf(DownloadSize.text(item.bytes), item.quality ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        DownloadItem.State.Waiting -> if (waitingForWiFi) "Waiting for Wi-Fi" else "Waiting to download"
        is DownloadItem.State.Downloading -> "Downloading ${Math.round(state.fraction * 100)}%"
        is DownloadItem.State.Failed -> if (state.message.isEmpty()) "Couldn't download" else "Couldn't download: ${state.message}"
    }

    /** "12 songs", or "12 songs · 1 album or playlist", or "… · 3 albums & playlists". */
    fun countLine(state: DownloadsState): String {
        val songs = songsText(state.downloaded.size)
        return when (val count = state.collections.size) {
            0 -> songs
            1 -> "$songs · 1 album or playlist"
            else -> "$songs · $count albums & playlists"
        }
    }

    /** "Album · Juniper Vale", "Playlist". */
    fun kindLine(collection: DownloadCollection): String {
        val kind = if (collection.kind == DownloadCollection.Kind.ALBUM) "Album" else "Playlist"
        val subtitle = collection.subtitle
        return if (subtitle.isNullOrEmpty()) kind else "$kind · $subtitle"
    }

    /** Where the queue says it is playing from when a download plays from here. */
    val source = QueueSource(QueueSource.Kind.LIBRARY, "Downloads", "Downloads")

    fun source(collection: DownloadCollection): QueueSource = when (collection.kind) {
        DownloadCollection.Kind.ALBUM -> QueueSource(
            QueueSource.Kind.ALBUM, "Album", collection.title,
            BrowseEndpoint(collection.id, pageType = BrowseEndpoint.PageType.ALBUM),
        )
        DownloadCollection.Kind.PLAYLIST -> QueueSource(
            QueueSource.Kind.PLAYLIST, "Playlist", collection.title,
            BrowseEndpoint(collection.id, pageType = BrowseEndpoint.PageType.PLAYLIST),
        )
    }
}
