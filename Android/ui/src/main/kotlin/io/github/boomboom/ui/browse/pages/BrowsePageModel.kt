package io.github.boomboom.ui.browse.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.BrowsePage
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

/** A page of shelves: what to load, and the page once loaded. Main thread only. */
class BrowsePageModel(source: Source, private val api: MusicAPI, scope: CoroutineScope) {
    sealed class Source {
        data class Endpoint(val endpoint: BrowseEndpoint) : Source()
        data class Charts(val country: String?) : Source()
        data object Explore : Source()
    }

    /** The loaded page: its title and chips, then the shelves. */
    data class Content(val title: String?, val chips: List<Chip>, val shelves: Shelves)

    sealed class Shelves {
        /** A chip was tapped: its shelves are on their way, under the title and chips that stay. */
        data object Switching : Shelves()
        data class Shown(val list: Paged<List<Shelf>>) : Shelves()
    }

    /** How the page looks while a load is out. */
    private enum class Reload {
        /** Skeletons for the whole page (the first load, Retry). */
        WHOLE,

        /** The content stays until the new one arrives, and stays if the load fails (pull to refresh). */
        KEEPING_CONTENT,

        /** The title and chips stay; the shelves turn to skeletons (a chip tap). */
        SHELVES_ONLY,
    }

    var source by mutableStateOf(source)
        private set
    var state by mutableStateOf<LoadState<Content>>(LoadState.Loading)
        private set

    /** The page itself: a chip tap, Retry or a refresh replaces the request out. */
    private val page = RequestSlot(scope)

    /** The next part of the shelves on screen; dropped whenever they are replaced. */
    private val more = RequestSlot(scope)

    /** The first load, and again after a failure when the page comes back. A request already out is left to finish. */
    fun loadIfNeeded() {
        if (state.isLoaded || page.isBusy) return
        load(Reload.WHOLE)
    }

    fun refresh(): Job = load(Reload.KEEPING_CONTENT)

    fun retry() {
        load(Reload.WHOLE)
    }

    /** A chip on the page (a country on Charts, a filter elsewhere). */
    fun select(chip: Chip) {
        source = when (source) {
            is Source.Charts -> Source.Charts(chip.endpoint?.params)
            is Source.Endpoint, Source.Explore -> Source.Endpoint(chip.endpoint ?: return)
        }
        load(Reload.SHELVES_ONLY)
    }

    /** The chip shown as selected. */
    fun selectedChip(chips: List<Chip>): Chip? = when (val shown = source) {
        is Source.Charts -> chips.firstOrNull { it.endpoint?.params == (shown.country ?: "ZZ") } ?: chips.firstOrNull { it.isSelected }
        is Source.Endpoint -> chips.firstOrNull { it.endpoint == shown.endpoint } ?: chips.firstOrNull { it.isSelected }
        Source.Explore -> chips.firstOrNull { it.isSelected }
    }

    private suspend fun fetch(source: Source): BrowsePage = when (source) {
        is Source.Endpoint -> api.browse(source.endpoint)
        is Source.Charts -> api.charts(source.country)
        Source.Explore -> api.explore()
    }

    private fun load(reload: Reload): Job {
        val source = source
        val previous = state.valueOrNull
        when (reload) {
            Reload.WHOLE -> show(LoadState.Loading)
            Reload.KEEPING_CONTENT -> if (previous == null) show(LoadState.Loading)
            Reload.SHELVES_ONLY -> show(previous?.let { LoadState.Loaded(it.copy(shelves = Shelves.Switching)) } ?: LoadState.Loading)
        }
        return page.start({ fetch(source) }) { result ->
            result.onSuccess { page ->
                val chips = if (page.chips.isEmpty() && reload == Reload.SHELVES_ONLY) previous?.chips ?: emptyList() else page.chips
                show(
                    LoadState.Loaded(
                        Content(
                            page.title ?: previous?.title, chips,
                            Shelves.Shown(Paged(page.shelves, MoreState.of(page.continuation))),
                        ),
                    ),
                )
            }.onFailure { error ->
                // A refresh that fails keeps shelves on screen, but not skeletons left by a chip tap it replaced.
                val now = state.valueOrNull
                if (reload == Reload.KEEPING_CONTENT && now != null && now.shelves is Shelves.Shown) return@onFailure
                show(LoadState.Failed(userMessage(error)))
            }
        }
    }

    /** The only place the shelves are replaced. A load-more for the old shelves goes with them. */
    private fun show(new: LoadState<Content>) {
        more.cancel()
        state = new
    }

    fun loadMore() {
        val content = state.valueOrNull ?: return
        val list = (content.shelves as? Shelves.Shown)?.list ?: return
        val token = list.more.askable ?: return
        state = LoadState.Loaded(content.copy(shelves = Shelves.Shown(list.copy(more = MoreState.Loading(token)))))
        more.start({ api.browseContinuation(token) }) { result ->
            val now = state.valueOrNull ?: return@start
            val shown = (now.shelves as? Shelves.Shown)?.list ?: return@start
            if (shown.more != MoreState.Loading(token)) return@start
            val next = result.fold(
                { Paged(appending(it.shelves, shown.content), MoreState.of(it.continuation)) },
                { shown.copy(more = MoreState.Failed(token)) },
            )
            state = LoadState.Loaded(now.copy(shelves = Shelves.Shown(next)))
        }
    }
}
