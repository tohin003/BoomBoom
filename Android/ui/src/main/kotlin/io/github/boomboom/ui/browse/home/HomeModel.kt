package io.github.boomboom.ui.browse.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.boomboom.models.Chip
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.Shelf
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.Paged
import io.github.boomboom.ui.browse.RequestSlot
import io.github.boomboom.ui.browse.appending
import io.github.boomboom.ui.browse.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Home's content: the feed for the selected chip, and more shelves as the listener scrolls. Main thread only. */
class HomeModel(private val api: MusicAPI, scope: CoroutineScope) {
    var chips by mutableStateOf<List<Chip>>(emptyList())
        private set

    /** Null: the default feed. */
    var selectedChip by mutableStateOf<Chip?>(null)
        private set

    /** The shelves on screen and the next part of the feed. */
    var state by mutableStateOf<LoadState<Paged<List<Shelf>>>>(LoadState.Loading)
        private set

    /** The feed itself: a chip tap, Retry or a refresh replaces the request out. */
    private val feed = RequestSlot(scope)

    /** The next part of the shelves on screen; dropped whenever they are replaced. */
    private val more = RequestSlot(scope)

    /** The session's revision the feed was loaded for. */
    private var knownRevision: Int? = null

    /**
     * Called each time Home appears and whenever the active account changes: the feed is that account's, so a new
     * account (or none) gets its own from the start, chips included.
     */
    fun sync(revision: Int) {
        val known = knownRevision
        knownRevision = revision
        if (known == null || known == revision) {
            loadIfNeeded()
            return
        }
        selectedChip = null
        load(keepContent = false)
    }

    /** The first load, and again after a failure when Home comes back. A request already out is left to finish. */
    fun loadIfNeeded() {
        if (state.isLoaded || feed.isBusy) return
        load(keepContent = false)
    }

    /** Pull to refresh: the shelves stay (and keep loading more) until the new ones arrive, and stay if it fails. */
    fun refresh(): Job = load(keepContent = true)

    /** A chip tap: loads its feed; the selected chip again goes back to the default feed. */
    fun select(chip: Chip) {
        selectedChip = if (chip.title == selectedChip?.title) null else chip
        load(keepContent = false)
    }

    fun retry() {
        load(keepContent = false)
    }

    private fun load(keepContent: Boolean): Job {
        val chip = selectedChip
        if (!keepContent || !state.isLoaded) show(LoadState.Loading)
        return feed.start({ api.home(chip) }) { result ->
            result.onSuccess { page ->
                if (page.chips.isNotEmpty()) chips = page.chips
                show(LoadState.Loaded(Paged(page.shelves, MoreState.of(page.continuation))))
            }.onFailure { error ->
                if (!state.isLoaded) show(LoadState.Failed(userMessage(error)))
            }
        }
    }

    /** The only place the shelves are replaced. A load-more for the old shelves goes with them. */
    private fun show(new: LoadState<Paged<List<Shelf>>>) {
        more.cancel()
        state = new
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
                    { next -> Paged(appending(next.shelves, now.content), MoreState.of(next.continuation)) },
                    { now.copy(more = MoreState.Failed(token)) },
                ),
            )
        }
    }
}
