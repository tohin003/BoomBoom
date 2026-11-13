package io.github.boomboom.ui.browse.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchResults
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.models.SuggestedQuery
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.Paged
import io.github.boomboom.ui.browse.RequestSlot
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.quietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Suggestions while typing: each keystroke cancels the previous request, waits a moment, then asks. An answer is
 * shown only if the field still holds the words it was asked for, so slow or out-of-order answers never appear.
 *
 * The pause runs on [scope]'s dispatcher, so a test dispatcher makes it virtual.
 */
class SuggestionLoader(
    private val api: MusicAPI,
    private val scope: CoroutineScope,
    private val debounce: Duration = 180.milliseconds,
) {
    /** An answer and the words it answers. */
    data class Answer(val text: String = "", val suggestions: List<SearchSuggestion> = emptyList())

    /** The newest answer. It stays while the next is on its way, so [visible] can keep matching lines up. */
    var answer by mutableStateOf(Answer())
        private set

    /** The words a request is out for (waiting out the pause, or asked); null when none is. */
    var pending by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    val suggestions: List<SearchSuggestion> get() = answer.suggestions

    /** True between a keystroke and its answer. */
    val isLoading: Boolean get() = pending != null

    fun update(newText: String) {
        job?.cancel()
        val words = newText.trim(' ', '\t')
        if (words.isEmpty()) {
            job = null
            answer = Answer()
            pending = null
            return
        }
        pending = newText
        job = scope.launch {
            delay(debounce)
            val result = quietly { api.searchSuggestions(words) }
            // A client that does not stop when cancelled still answers: the words decide whether it shows.
            if (!isActive || pending != newText) return@launch
            if (result != null) answer = Answer(newText, result)
            pending = null
            job = null
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
    }

    /**
     * What to show for [current] right now: the answer for these exact words, or, while the next answer is on its way,
     * the earlier word suggestions that still begin with what is typed (never items or unrelated lines).
     */
    fun visible(current: String): List<SearchSuggestion> {
        if (answer.text == current) return answer.suggestions
        val typed = current.trim(' ', '\t').lowercase(Locale.ROOT)
        if (typed.isEmpty()) return emptyList()
        return answer.suggestions.mapNotNull { suggestion ->
            val query = (suggestion as? SearchSuggestion.Query)?.query ?: return@mapNotNull null
            if (!query.text.lowercase(Locale.ROOT).startsWith(typed) || query.text.length <= typed.length) return@mapNotNull null
            SearchSuggestion.Query(
                SuggestedQuery(
                    query.text,
                    listOf(
                        SuggestedQuery.Run(query.text.take(typed.length), isBold = false),
                        SuggestedQuery.Run(query.text.drop(typed.length), isBold = true),
                    ),
                    query.isFromHistory,
                ),
            )
        }
    }
}

/**
 * Search results: the unfiltered shelves, or one filtered list that loads more as it scrolls. A newer search replaces
 * an older one, and answers to the older one are dropped.
 */
class ResultsLoader(private val api: MusicAPI, scope: CoroutineScope) {
    /** What was searched for. The filter shows as selected while its results load. */
    data class Request(val query: String = "", val filter: SearchFilter? = null)

    sealed class Phase {
        data object Idle : Phase()
        data object Loading : Phase()
        data class Failed(val message: String) : Phase()

        /** Without a filter: the shelves. */
        data class Loaded(val page: SearchResults) : Phase()

        /** With a filter: one list (the items of the answer's shelves) that loads more as it scrolls. */
        data class InList(val page: SearchResults, val list: Paged<List<MusicItem>>) : Phase()
    }

    var request by mutableStateOf(Request())
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    private val searchRequest = RequestSlot(scope)

    /** The next part of the list on screen; dropped whenever the list is replaced. */
    private val moreRequest = RequestSlot(scope)

    val query: String get() = request.query
    val filter: SearchFilter? get() = request.filter

    /** The filtered list so far; empty without a filter. */
    val items: List<MusicItem> get() = (phase as? Phase.InList)?.list?.content ?: emptyList()

    /** The filtered list's next part; the end without a filter. */
    val more: MoreState get() = (phase as? Phase.InList)?.list?.more ?: MoreState.End

    fun search(newQuery: String, newFilter: SearchFilter?) {
        request = Request(newQuery, newFilter)
        show(Phase.Loading)
        searchRequest.start({ api.search(newQuery, newFilter) }) { result ->
            result.onSuccess { page ->
                if (newFilter != null) {
                    val items = unique(page.shelves.flatMap { it.items }, emptyList())
                    show(Phase.InList(page, Paged(items, MoreState.of(page.continuation))))
                } else {
                    show(Phase.Loaded(page))
                }
            }.onFailure { show(Phase.Failed(userMessage(it))) }
        }
    }

    fun retry() {
        search(request.query, request.filter)
    }

    /** The words were edited away: drops the search and anything it is still loading. */
    fun cancel() {
        searchRequest.cancel()
        show(Phase.Idle)
    }

    /** The only place the results are replaced. A load-more for the old list goes with them. */
    private fun show(new: Phase) {
        moreRequest.cancel()
        phase = new
    }

    fun loadMore() {
        val shown = phase as? Phase.InList ?: return
        val token = shown.list.more.askable ?: return
        phase = shown.copy(list = shown.list.copy(more = MoreState.Loading(token)))
        moreRequest.start({ api.searchContinuation(token) }) { result ->
            val now = phase as? Phase.InList ?: return@start
            if (now.list.more != MoreState.Loading(token)) return@start
            val next = result.fold(
                { Paged(now.list.content + unique(it.items, now.list.content), MoreState.of(it.continuation)) },
                { now.list.copy(more = MoreState.Failed(token)) },
            )
            phase = now.copy(list = next)
        }
    }

    /** [new] without the items already listed (a list keyed by id cannot hold one twice). */
    private fun unique(new: List<MusicItem>, old: List<MusicItem>): List<MusicItem> {
        val seen = old.map { it.id }.toHashSet()
        return new.filter { seen.add(it.id) }
    }
}

/** The listener's earlier searches, newest first, kept on the phone. */
class RecentSearches(private val storage: KeyValueStore) {
    var all by mutableStateOf(load())
        private set

    private fun load(): List<String> = storage.getString(KEY)?.let {
        try {
            Json.decodeFromString(serializer, it)
        } catch (failure: IllegalArgumentException) {
            null
        }
    } ?: emptyList()

    fun add(query: String) {
        val words = query.trim()
        if (words.isEmpty()) return
        all = (listOf(words) + all.filterNot { it.equals(words, ignoreCase = true) }).take(LIMIT)
        save()
    }

    fun remove(query: String) {
        all = all.filterNot { it == query }
        save()
    }

    fun clear() {
        all = emptyList()
        save()
    }

    private fun save() {
        storage.putString(KEY, Json.encodeToString(serializer, all))
    }

    companion object {
        private const val KEY = "search.recent"
        const val LIMIT = 20
        private val serializer = ListSerializer(String.serializer())
    }
}

/** The search screen's state: what is typed, whether suggestions or results show, and the loaders behind them. */
class SearchModel(
    api: MusicAPI,
    scope: CoroutineScope,
    storage: KeyValueStore,
    debounce: Duration = 180.milliseconds,
) {
    sealed class Mode {
        /** Typing: suggestions, or recent searches while the field is empty. */
        data object Suggestions : Mode()

        /** Results for these submitted words. Editing the field away from them goes back to suggestions. */
        data class Results(val submitted: String) : Mode()
    }

    /** The field, with where the cursor is. */
    var fieldValue by mutableStateOf(TextFieldValue(""))
        private set
    var mode by mutableStateOf<Mode>(Mode.Suggestions)
        private set

    val suggestions = SuggestionLoader(api, scope, debounce)
    val results = ResultsLoader(api, scope)
    val recents = RecentSearches(storage)

    val text: String get() = fieldValue.text
    val isShowingResults: Boolean get() = mode is Mode.Results

    /** Every change of the field goes through here: typing, clearing, filling. */
    fun edit(value: TextFieldValue) {
        val changed = value.text != fieldValue.text
        fieldValue = value
        if (changed) textChanged()
    }

    /** Puts words in the field with the cursor at the end. */
    fun setText(words: String) {
        edit(TextFieldValue(words, TextRange(words.length)))
    }

    private fun textChanged() {
        val shown = mode
        if (shown is Mode.Results) {
            // The field echoing the submitted words changes nothing.
            if (text == shown.submitted) return
            mode = Mode.Suggestions
            results.cancel()
        }
        suggestions.update(text)
    }

    /** The search key, or a tap on a suggested query or a recent search. */
    fun submit(query: String? = null) {
        val words = (query ?: text).trim()
        if (words.isEmpty()) return
        mode = Mode.Results(words)
        fieldValue = TextFieldValue(words, TextRange(words.length))
        suggestions.cancel()
        recents.add(words)
        results.search(words, null)
    }

    /** A filter pill: applies it, or clears it when it is the selected one. */
    fun select(filter: SearchFilter) {
        if (!isShowingResults) return
        results.search(results.query, if (results.filter == filter) null else filter)
    }

    /** The arrow beside a suggestion: puts its words in the field to keep typing, without searching. */
    fun fill(query: String) {
        setText("$query ")
    }
}
