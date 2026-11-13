package io.github.boomboom.logic

import io.github.boomboom.models.Artwork
import io.github.boomboom.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One song the listener asked to keep on the phone. */
@Serializable
data class DownloadItem(
    val song: Song,
    val state: State,
    /** The file's size once downloaded, 0 before. */
    val bytes: Long = 0,
    /** "Opus 270 kbps", once downloaded. */
    val quality: String? = null,
    /** Milliseconds since 1970. */
    val addedAt: Long,
    /**
     * True when the listener downloaded this song by itself (rather than only as part of an album or playlist): it
     * then stays when such a collection is removed.
     */
    val isStandalone: Boolean = false,
) {
    @Serializable
    sealed class State {
        /** In the queue. */
        @Serializable
        @SerialName("waiting")
        data object Waiting : State()

        /** Arriving; the fraction in hand, 0...1. */
        @Serializable
        @SerialName("downloading")
        data class Downloading(val fraction: Double) : State()

        /** On the phone: plays at once and without a connection. */
        @Serializable
        @SerialName("downloaded")
        data object Downloaded : State()

        /** Could not be fetched. The text is for the listener. */
        @Serializable
        @SerialName("failed")
        data class Failed(val message: String) : State()
    }

    val id: String get() = song.videoId

    val isDownloaded: Boolean get() = state == State.Downloaded
}

/** An album or playlist the listener downloaded as a whole. */
@Serializable
data class DownloadCollection(
    /** The album's or playlist's own id (its browse id). */
    val id: String,
    val kind: Kind,
    val title: String,
    /** The artist, or who made the playlist. */
    val subtitle: String? = null,
    val artwork: Artwork = Artwork.EMPTY,
    val songIds: List<String> = emptyList(),
    /** Milliseconds since 1970. */
    val addedAt: Long = System.currentTimeMillis(),
) {
    @Serializable
    enum class Kind {
        @SerialName("album") ALBUM,
        @SerialName("playlist") PLAYLIST,
    }
}

/** How far a downloaded album or playlist has come: songs on the phone, and songs in all. */
data class CollectionProgress(val downloaded: Int, val total: Int)

/** What the backend says happened to a song. */
sealed class DownloadEvent {
    /** How much of it has arrived, 0...1. */
    data class Progress(val videoId: String, val fraction: Double) : DownloadEvent()

    /** It is on the phone. [quality] is for the listener: "Opus 270 kbps". */
    data class Finished(val videoId: String, val bytes: Long, val quality: String?) : DownloadEvent()

    /** It could not be fetched or written. The text is for the listener. */
    data class Failed(val videoId: String, val message: String) : DownloadEvent()
}

/** The fetching and the files behind [DownloadStore]. The audio engine implements it. */
interface DownloadBackend {
    var onDownloadEvent: ((DownloadEvent) -> Unit)?

    /** Queues songs to be kept, in this order. Ones already on the phone answer `Finished` at once. */
    fun startDownloads(videoIds: List<String>)

    /** Removes kept songs from the phone, and stops fetching them. */
    fun removeDownloads(videoIds: List<String>)

    /** Whether songs may be fetched for keeping over mobile data (or in a data saver). */
    var allowsDownloadsOnMobileData: Boolean

    /** True while downloads are held back because the phone is on mobile data and that is not allowed. */
    val isHoldingDownloadsForWiFi: Boolean

    /** Called when [isHoldingDownloadsForWiFi] changes. */
    var onDownloadHoldChange: (() -> Unit)?
}

/** What a screen shows of the downloads. */
data class DownloadsState(
    /** Every song asked for, newest first: downloaded, on its way, waiting or failed. */
    val items: List<DownloadItem> = emptyList(),
    /** Albums and playlists downloaded as a whole, newest first. */
    val collections: List<DownloadCollection> = emptyList(),
    /** True while downloads wait for Wi-Fi because mobile data is not allowed for them. */
    val isWaitingForWiFi: Boolean = false,
    /** Whether to download over mobile data too. Off unless the listener turns it on. */
    val isMobileDataAllowed: Boolean = false,
) {
    /** The state of a song, null when the listener never asked to keep it. */
    fun stateOf(videoId: String): DownloadItem.State? = items.firstOrNull { it.id == videoId }?.state

    /** The downloaded songs only, newest first. */
    val downloaded: List<DownloadItem> get() = items.filter { it.isDownloaded }

    /** Bytes on the phone taken by downloads. */
    val totalBytes: Long get() = items.sumOf { if (it.isDownloaded) it.bytes else 0L }

    /** How many songs are still to come (waiting or arriving). */
    val pendingCount: Int
        get() = items.count { it.state is DownloadItem.State.Waiting || it.state is DownloadItem.State.Downloading }

    val failedCount: Int get() = items.count { it.state is DownloadItem.State.Failed }

    /** The songs of a downloaded album or playlist, in its order. */
    fun songsOf(collectionId: String): List<DownloadItem> {
        val collection = collections.firstOrNull { it.id == collectionId } ?: return emptyList()
        return collection.songIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
    }

    fun progressOf(collectionId: String): CollectionProgress {
        val songs = songsOf(collectionId)
        return CollectionProgress(songs.count { it.isDownloaded }, songs.size)
    }

    fun bytesOf(collectionId: String): Long = songsOf(collectionId).sumOf { if (it.isDownloaded) it.bytes else 0L }
}

@Serializable
private class DownloadIndex(
    val items: List<DownloadItem> = emptyList(),
    val collections: List<DownloadCollection> = emptyList(),
)

/**
 * The listener's downloads: songs, albums and playlists kept on the phone at the best quality the account gets, until
 * the listener removes them. They play with no connection and never count against the automatic cache.
 *
 * Fetching shares the hidden tab with playback and always gives way to it, so a long playlist takes its time while
 * music plays. The queue is remembered and carries on the next time the app is open.
 *
 * Observe [state]. It changes with every progress report of every song (a new list each time: a screen that shows one
 * song's progress should pick it out with `map { it.stateOf(id) }`).
 *
 * @param backend What fetches and stores. Null for previews: songs then "download" instantly, in memory.
 * @param indexFile Where the list is kept between launches, as JSON. Null keeps it in memory only. It is written on
 *   the store's own thread, a moment after a change (it is one small file).
 */
class DownloadStore(
    private val backend: DownloadBackend?,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
    private val indexFile: File? = null,
    private val time: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(DownloadsState(isMobileDataAllowed = storage.getBoolean(MOBILE_DATA_KEY) ?: false))
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private var saveJob: Job? = null

    val items: List<DownloadItem> get() = _state.value.items
    val collections: List<DownloadCollection> get() = _state.value.collections
    val isWaitingForWiFi: Boolean get() = _state.value.isWaitingForWiFi
    val downloaded: List<DownloadItem> get() = _state.value.downloaded
    val totalBytes: Long get() = _state.value.totalBytes
    val pendingCount: Int get() = _state.value.pendingCount
    val failedCount: Int get() = _state.value.failedCount

    /** Whether to download over mobile data too. Off unless the listener turns it on. */
    var isMobileDataAllowed: Boolean
        get() = _state.value.isMobileDataAllowed
        set(value) {
            if (value == isMobileDataAllowed) return
            update { copy(isMobileDataAllowed = value) }
            storage.putBoolean(MOBILE_DATA_KEY, value)
            backend?.allowsDownloadsOnMobileData = value
            refreshHold()
        }

    private fun update(change: DownloadsState.() -> DownloadsState) {
        _state.value = _state.value.change()
    }

    init {
        indexFile?.let { file ->
            val index = try {
                if (file.isFile) decodeOrNull(DownloadIndex.serializer(), file.readText()) else null
            } catch (failure: java.io.IOException) {
                null
            }
            if (index != null) {
                update {
                    copy(
                        // What was arriving when the app last closed starts over from what is in hand.
                        items = index.items.map {
                            if (it.state is DownloadItem.State.Downloading) it.copy(state = DownloadItem.State.Waiting) else it
                        },
                        collections = index.collections,
                    )
                }
            }
        }
        backend?.allowsDownloadsOnMobileData = isMobileDataAllowed
        backend?.onDownloadEvent = { event -> handle(event) }
        backend?.onDownloadHoldChange = { refreshHold() }
        // Carry on with what was still to do. Kept songs are confirmed against the files (one may have been lost).
        val pending = items.filter { it.state == DownloadItem.State.Waiting }.map { it.id }
        val kept = items.filter { it.isDownloaded }.map { it.id }
        if (pending.isNotEmpty() || kept.isNotEmpty()) backend?.startDownloads(kept + pending)
        refreshHold()
    }

    // MARK: Looking

    /** The state of a song, null when the listener never asked to keep it. */
    fun stateOf(videoId: String): DownloadItem.State? = _state.value.stateOf(videoId)

    /** The songs of a downloaded album or playlist, in its order. */
    fun songsOf(collectionId: String): List<DownloadItem> = _state.value.songsOf(collectionId)

    /** How far a downloaded album or playlist has come: songs on the phone, and songs in all. */
    fun progressOf(collectionId: String): CollectionProgress = _state.value.progressOf(collectionId)

    fun bytesOf(collectionId: String): Long = _state.value.bytesOf(collectionId)

    // MARK: Downloading

    /** Keeps one song. */
    fun download(song: Song) {
        add(listOf(song), standalone = true)
    }

    /**
     * Keeps an album or a playlist: its songs in order, and the collection itself so that it can be found and removed
     * as a whole. Downloading it again adds songs that have joined it since.
     */
    fun download(songs: List<Song>, collection: DownloadCollection) {
        val playable = songs.filter { it.isAvailable }
        val existing = collections.firstOrNull { it.id == collection.id }
        val entry = collection.copy(
            songIds = playable.map { it.videoId },
            addedAt = existing?.addedAt ?: collection.addedAt,
        )
        update {
            copy(
                collections = if (existing != null) {
                    collections.map { if (it.id == entry.id) entry else it }
                } else {
                    listOf(entry) + collections
                },
            )
        }
        add(playable, standalone = false)
    }

    private fun add(songs: List<Song>, standalone: Boolean) {
        val started = ArrayList<String>()
        // Newest first in the list, while the songs of one request keep their own order.
        val fresh = ArrayList<DownloadItem>()
        val list = items.toMutableList()
        for (song in songs) {
            if (!song.isAvailable) continue
            val index = list.indexOfFirst { it.id == song.videoId }
            if (index >= 0) {
                if (standalone) list[index] = list[index].copy(isStandalone = true)
                if (list[index].state is DownloadItem.State.Failed) {
                    list[index] = list[index].copy(state = DownloadItem.State.Waiting)
                    started.add(song.videoId)
                }
            } else if (fresh.none { it.id == song.videoId }) {
                fresh.add(
                    DownloadItem(
                        song = song, state = DownloadItem.State.Waiting, bytes = 0, quality = null,
                        addedAt = time.nowMillis(), isStandalone = standalone,
                    ),
                )
                started.add(song.videoId)
            }
        }
        update { copy(items = fresh + list) }
        save()
        if (started.isEmpty()) return
        if (backend != null) {
            backend.startDownloads(started)
        } else {
            // Previews have nothing to fetch from.
            for (id in started) handle(DownloadEvent.Finished(id, 6_400_000, "Opus 256 kbps"))
        }
        refreshHold()
    }

    /** Tries the failed ones again. */
    fun retryFailed() {
        val again = items.filter { it.state is DownloadItem.State.Failed }.map { it.id }
        if (again.isEmpty()) return
        update {
            copy(items = items.map {
                if (it.state is DownloadItem.State.Failed) it.copy(state = DownloadItem.State.Waiting) else it
            })
        }
        save()
        backend?.startDownloads(again)
    }

    // MARK: Removing

    /** Takes songs off the phone (downloaded or not yet) and out of every downloaded album and playlist. */
    fun remove(videoIds: Set<String>) {
        if (videoIds.isEmpty()) return
        update {
            copy(
                items = items.filter { it.id !in videoIds },
                collections = collections
                    .map { it.copy(songIds = it.songIds.filter { id -> id !in videoIds }) }
                    .filter { it.songIds.isNotEmpty() },
            )
        }
        backend?.removeDownloads(videoIds.toList())
        save()
        refreshHold()
    }

    /**
     * Removes a downloaded album or playlist: its songs go too, except ones the listener also downloaded on their own
     * or that belong to another downloaded album or playlist.
     */
    fun removeCollection(collectionId: String) {
        val gone = collections.firstOrNull { it.id == collectionId } ?: return
        update { copy(collections = collections.filter { it.id != collectionId }) }
        val elsewhere = collections.flatMap { it.songIds }.toSet()
        val standalone = items.filter { it.isStandalone }.map { it.id }.toSet()
        val orphans = gone.songIds.toSet() - elsewhere - standalone
        if (orphans.isEmpty()) save() else remove(orphans)
    }

    fun removeAll() {
        val all = items.map { it.id }.toSet()
        update { copy(collections = emptyList()) }
        remove(all)
        save()
    }

    // MARK: What the backend reports

    private fun handle(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Progress -> {
                val index = items.indexOfFirst { it.id == event.videoId }
                if (index < 0 || items[index].isDownloaded) return
                replaceItem(index) { it.copy(state = DownloadItem.State.Downloading(event.fraction)) }
            }
            is DownloadEvent.Finished -> {
                val index = items.indexOfFirst { it.id == event.videoId }
                if (index < 0) return
                replaceItem(index) {
                    it.copy(state = DownloadItem.State.Downloaded, bytes = event.bytes, quality = event.quality ?: it.quality)
                }
                save()
            }
            is DownloadEvent.Failed -> {
                val index = items.indexOfFirst { it.id == event.videoId }
                if (index < 0) return
                replaceItem(index) { it.copy(state = DownloadItem.State.Failed(event.message), bytes = 0) }
                save()
            }
        }
        refreshHold()
    }

    private fun replaceItem(index: Int, change: (DownloadItem) -> DownloadItem) {
        update { copy(items = items.toMutableList().also { it[index] = change(it[index]) }) }
    }

    private fun refreshHold() {
        val holding = (backend?.isHoldingDownloadsForWiFi ?: false) && pendingCount > 0
        if (holding != isWaitingForWiFi) update { copy(isWaitingForWiFi = holding) }
    }

    /** Writes the list a moment from now (events come in bursts). */
    private fun save() {
        if (indexFile == null) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            write(indexFile)
        }
    }

    /** Writes the list now. For tests, and before the app goes away. */
    fun saveNow() {
        if (indexFile == null) return
        saveJob?.cancel()
        write(indexFile)
    }

    /** All or nothing: the new list goes to a file beside the old one, which it then replaces. */
    private fun write(file: File) {
        val text = BoomJson.encodeToString(DownloadIndex.serializer(), DownloadIndex(items, collections))
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val partial = File(file.absoluteFile.parentFile, file.name + ".tmp")
            partial.writeText(text)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: java.io.IOException) {
            // The list is a convenience: the next change writes it again.
        }
    }

    private companion object {
        const val MOBILE_DATA_KEY = "downloads.mobileData"
    }
}
