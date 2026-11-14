package io.github.boomboom.ui.browse.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.LibrarySection
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.Paged
import io.github.boomboom.ui.browse.RequestSlot
import io.github.boomboom.ui.browse.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The items of one Library section, in the order YouTube Music lists them, and where the next part stands. */
data class LibraryContent(val items: List<MusicItem>, val more: MoreState) {
    val songs: List<Song> get() = items.mapNotNull { it.asSong }
}

/** A short message about something that did not work, shown as a dialog. */
data class LibraryNotice(val title: String, val message: String)

/** The listener's own playlists have ids that start with "PL"; "LM" is liked music. */
val PlaylistItem.isOwn: Boolean get() = id.startsWith("PL")

/**
 * The Library tab's data: every section keeps its own state, so switching sections shows what was loaded before and
 * an answer can never land in a section it was not asked for. Main thread only.
 */
class LibraryModel(private val api: MusicAPI, private val scope: CoroutineScope) {
    private val states = mutableStateMapOf<LibrarySection, LoadState<LibraryContent>>()

    /** The section on screen: a chip tap changes it. */
    var section by mutableStateOf(LibrarySection.PLAYLISTS)
        private set

    var notice by mutableStateOf<LibraryNotice?>(null)

    /** Each section's own request, and the request for its next part. */
    private val loads = HashMap<LibrarySection, RequestSlot>()
    private val mores = HashMap<LibrarySection, RequestSlot>()

    /** The session's revision the sections were loaded for. */
    private var knownRevision: Int? = null

    private fun load(section: LibrarySection) = loads.getOrPut(section) { RequestSlot(scope) }
    private fun more(section: LibrarySection) = mores.getOrPut(section) { RequestSlot(scope) }

    fun state(of: LibrarySection): LoadState<LibraryContent> = states[of] ?: LoadState.Loading

    fun select(section: LibrarySection) {
        if (section == this.section) return
        cancelLoadingMore()
        this.section = section
    }

    /** Forgets everything when the active account changed since the last call: the library is that account's. */
    fun sync(revision: Int) {
        if (revision == knownRevision) return
        if (knownRevision != null) reset()
        knownRevision = revision
    }

    fun reset() {
        loads.values.forEach { it.cancel() }
        cancelLoadingMore()
        states.clear()
    }

    fun cancelLoadingMore() {
        mores.values.forEach { it.cancel() }
        for ((section, state) in states.toMap()) {
            val content = (state as? LoadState.Loaded)?.value ?: continue
            if (content.more is MoreState.Loading) states[section] = LoadState.Loaded(content.copy(more = MoreState.Ready(content.more.token)))
        }
    }

    /** The first load of a section, and again after a failure when it comes back. A request out is left to finish. */
    fun loadIfNeeded(section: LibrarySection) {
        if (state(section).isLoaded || load(section).isBusy) return
        start(section, keepContent = false)
    }

    /** Pull to refresh and refresh after a change: the old items stay on screen until the new ones arrive. */
    fun refresh(section: LibrarySection): Job = start(section, keepContent = true)

    fun retry(section: LibrarySection) {
        start(section, keepContent = false)
    }

    private fun start(section: LibrarySection, keepContent: Boolean): Job {
        more(section).cancel()
        if (!keepContent || !state(section).isLoaded) states[section] = LoadState.Loading
        return load(section).start({ api.library(section) }) { result ->
            result.onSuccess { page -> states[section] = LoadState.Loaded(content(page)) }
                .onFailure { error -> if (!state(section).isLoaded) states[section] = LoadState.Failed(userMessage(error)) }
        }
    }

    fun loadMore(section: LibrarySection) {
        val loaded = states[section] as? LoadState.Loaded ?: return
        val token = loaded.value.more.askable ?: return
        states[section] = LoadState.Loaded(loaded.value.copy(more = MoreState.Loading(token)))
        more(section).start({ api.browseContinuation(token) }) { result ->
            val now = (states[section] as? LoadState.Loaded)?.value ?: return@start
            if (now.more != MoreState.Loading(token)) return@start
            states[section] = LoadState.Loaded(
                result.fold(
                    { page ->
                        val next = content(page)
                        val known = now.items.map { it.id }.toHashSet()
                        LibraryContent(now.items + next.items.filter { known.add(it.id) }, next.more)
                    },
                    { now.copy(more = MoreState.Failed(token)) },
                ),
            )
        }
    }

    /**
     * Takes the playlist out of the list at once, asks YouTube Music to delete it, and puts it back where it was if
     * that fails. Runs in [appScope], so it finishes even if the Library is left.
     */
    fun deletePlaylist(playlist: PlaylistItem, appScope: CoroutineScope) {
        val item = MusicItem.OfPlaylist(playlist)
        val content = (states[LibrarySection.PLAYLISTS] as? LoadState.Loaded)?.value ?: return
        val index = content.items.indexOf(item)
        if (index < 0) return
        states[LibrarySection.PLAYLISTS] = LoadState.Loaded(content.copy(items = content.items - item))
        appScope.launch {
            try {
                api.deletePlaylist(playlist.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val current = (states[LibrarySection.PLAYLISTS] as? LoadState.Loaded)?.value
                if (current != null && item !in current.items) {
                    val back = current.items.toMutableList().apply { add(minOf(index, size), item) }
                    states[LibrarySection.PLAYLISTS] = LoadState.Loaded(current.copy(items = back))
                }
                notice = LibraryNotice(
                    "Couldn't delete “${playlist.title}”",
                    userMessage(failure) + " The playlist is still in your library.",
                )
            }
        }
    }

    companion object {
        /**
         * One flat list of the page's items. Playlists leave out "Liked music", which has its own row; repeats are
         * dropped so that every row has an identity of its own.
         */
        fun content(page: BrowsePage): LibraryContent {
            val seen = HashSet<String>()
            val items = page.shelves.flatMap { it.items }.filter { item ->
                if (item.asPlaylist?.id == "LM") return@filter false
                seen.add(item.id)
            }
            return LibraryContent(items, MoreState.of(page.continuation))
        }
    }
}

/** What the listener played, by day, and more days as they scroll. Main thread only. */
class HistoryModel(private val api: MusicAPI, scope: CoroutineScope) {
    var state by mutableStateOf<LoadState<Paged<List<Shelf>>>>(LoadState.Loading)
        private set

    private val feed = RequestSlot(scope)
    private val more = RequestSlot(scope)
    private var knownRevision: Int? = null

    /** Forgets the history when the active account changed since the last call. */
    fun sync(revision: Int) {
        if (revision == knownRevision) return
        if (knownRevision != null) {
            feed.cancel()
            more.cancel()
            state = LoadState.Loading
        }
        knownRevision = revision
    }

    fun loadIfNeeded() {
        if (state.isLoaded || feed.isBusy) return
        load(keepContent = false)
    }

    fun refresh(): Job = load(keepContent = true)

    fun retry() {
        load(keepContent = false)
    }

    private fun load(keepContent: Boolean): Job {
        more.cancel()
        if (!keepContent || !state.isLoaded) state = LoadState.Loading
        return feed.start({ api.history() }) { result ->
            result.onSuccess { page ->
                more.cancel()
                state = LoadState.Loaded(Paged(page.shelves.filter { it.items.isNotEmpty() }, MoreState.of(page.continuation)))
            }.onFailure { error -> if (!state.isLoaded) state = LoadState.Failed(userMessage(error)) }
        }
    }

    fun loadMore() {
        val loaded = state as? LoadState.Loaded ?: return
        val token = loaded.value.more.askable ?: return
        state = LoadState.Loaded(loaded.value.copy(more = MoreState.Loading(token)))
        more.start({ api.browseContinuation(token) }) { result ->
            val now = (state as? LoadState.Loaded)?.value ?: return@start
            if (now.more != MoreState.Loading(token)) return@start
            state = LoadState.Loaded(
                result.fold(
                    { page -> Paged(merged(page.shelves, now.content), MoreState.of(page.continuation)) },
                    { now.copy(more = MoreState.Failed(token)) },
                ),
            )
        }
    }

    companion object {
        /** More days after the ones shown. A day that continues ("Yesterday" again) joins the day already there. */
        fun merged(new: List<Shelf>, days: List<Shelf>): List<Shelf> {
            val result = days.toMutableList()
            for (shelf in new) {
                if (shelf.items.isEmpty()) continue
                val index = result.indexOfFirst { it.title == shelf.title }
                if (index >= 0) result[index] = result[index].copy(items = result[index].items + shelf.items) else result.add(shelf)
            }
            return result
        }
    }
}
