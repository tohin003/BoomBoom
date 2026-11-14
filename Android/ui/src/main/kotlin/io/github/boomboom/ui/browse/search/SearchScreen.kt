package io.github.boomboom.ui.browse.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchResults
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.models.SuggestedQuery
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageErrorView
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ChipBar
import io.github.boomboom.ui.design.ItemRow
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalSettingsStore
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.ShelfView
import kotlinx.coroutines.launch

/** The filters, in the order of the pills. */
private val searchFilters = listOf(
    SearchFilter.SONGS, SearchFilter.VIDEOS, SearchFilter.ALBUMS, SearchFilter.ARTISTS,
    SearchFilter.COMMUNITY_PLAYLISTS, SearchFilter.FEATURED_PLAYLISTS, SearchFilter.PODCASTS, SearchFilter.EPISODES,
    SearchFilter.PROFILES,
)

/** Search: a field with suggestions while typing, recent searches when empty, and results with filters. */
@Composable
fun SearchScreen(initialQuery: String?, submitsInitialQuery: Boolean) {
    val services = LocalAppServices.current
    val storage = LocalSettingsStore.current
    val model = rememberPageModel { SearchModel(services.api, it, storage) }
    val focus = remember { FocusRequester() }
    // The first appearance only: coming back to the page keeps what the listener left.
    val started = rememberPageModel("started") { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        if (started[0]) return@LaunchedEffect
        started[0] = true
        if (!initialQuery.isNullOrEmpty()) {
            model.setText(initialQuery)
            if (submitsInitialQuery) model.submit() else focus.requestFocus()
        } else {
            focus.requestFocus()
        }
    }

    // Results are for reading: however the search was started (the keyboard's key, a suggestion, a recent search),
    // the keyboard goes away and stops covering half of them. Tapping the field brings it back.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val showsResults = model.mode is SearchModel.Mode.Results
    LaunchedEffect(showsResults) {
        if (showsResults) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BoomColor.background),
    ) {
        SearchBar(model, focus)
        when (model.mode) {
            SearchModel.Mode.Suggestions ->
                if (model.text.isBlank()) RecentsList(model) else SuggestionsList(model)
            is SearchModel.Mode.Results -> ResultsView(model)
        }
    }
}

@Composable
private fun SearchBar(model: SearchModel, focus: FocusRequester) {
    val router = LocalShellRouter.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = BoomMetrics.margin, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { router.back() }, Modifier.testTag("page.back"))
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(CircleShape)
                .background(BoomColor.surfaceRaised)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (model.text.isEmpty()) {
                    Text("Search songs, albums, artists", style = BoomFont.itemTitle, color = BoomColor.textTertiary, maxLines = 1)
                }
                BasicTextField(
                    value = model.fieldValue,
                    onValueChange = { model.edit(it) },
                    singleLine = true,
                    textStyle = BoomFont.itemTitle.copy(color = BoomColor.textPrimary),
                    cursorBrush = SolidColor(BoomColor.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = {
                        model.submit()
                        keyboard?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "Search" }
                        .testTag("search.field"),
                )
            }
            if (model.text.isNotEmpty()) {
                BarIconButton(
                    Icons.Filled.Cancel, "Clear search",
                    {
                        model.setText("")
                        focus.requestFocus()
                    },
                    Modifier.testTag("search.clear"), tint = BoomColor.textTertiary, iconSize = 20.dp,
                )
            }
        }
    }
}

/** Earlier searches, newest first, each removable. */
@Composable
private fun RecentsList(model: SearchModel) {
    val recents = model.recents.all
    if (recents.isEmpty()) {
        MessageView(
            "Find your music", message = "Search for songs, albums, artists and playlists.", icon = Icons.Outlined.Search,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("search.recents")) {
        item(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = BoomMetrics.margin, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recent searches", style = BoomFont.sectionTitle, color = BoomColor.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Text(
                    "Clear", style = BoomFont.button, color = BoomColor.textSecondary,
                    modifier = Modifier
                        .heightIn(min = BoomMetrics.minimumTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Clear recent searches") { model.recents.clear() }
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                        .testTag("search.recents.clear"),
                )
            }
        }
        items(recents, key = { it }) { query ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { model.submit(query) }
                        .padding(start = BoomMetrics.margin)
                        .testTag("search.recent"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Outlined.History, null, tint = BoomColor.textSecondary, modifier = Modifier.size(22.dp))
                    Text(query, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BarIconButton(
                    Icons.Outlined.Close, "Remove $query from recent searches", { model.recents.remove(query) },
                    Modifier.padding(end = 4.dp), tint = BoomColor.textSecondary, iconSize = 20.dp,
                )
            }
        }
    }
}

/** The lines under the field while typing. */
@Composable
private fun SuggestionsList(model: SearchModel) {
    val shown = model.suggestions.visible(model.text)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("search.suggestions"), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.id }) { suggestion ->
                when (suggestion) {
                    is SearchSuggestion.Query -> QuerySuggestionRow(
                        suggestion.query, submit = { model.submit(suggestion.query.text) },
                        fill = { model.fill(suggestion.query.text) },
                    )
                    is SearchSuggestion.Item -> ItemRow(suggestion.item, Modifier.padding(vertical = 2.dp))
                }
            }
        }
        if (model.suggestions.isLoading && shown.isEmpty()) {
            CircularProgressIndicator(
                color = BoomColor.textSecondary, strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .size(24.dp),
            )
        }
    }
}

@Composable
private fun QuerySuggestionRow(query: SuggestedQuery, submit: () -> Unit, fill: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Search") { submit() }
                .semantics(mergeDescendants = true) { contentDescription = query.text }
                .padding(start = BoomMetrics.margin)
                .testTag("search.suggestion"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (query.isFromHistory) Icons.Outlined.History else Icons.Outlined.Search, null,
                tint = BoomColor.textSecondary, modifier = Modifier.size(22.dp),
            )
            // The typed part plain, the completion bold.
            val styled = buildAnnotatedString {
                for (run in query.runs) {
                    withStyle(SpanStyle(fontWeight = if (run.isBold) FontWeight.Bold else FontWeight.Normal)) { append(run.text) }
                }
            }
            Text(styled, style = BoomFont.itemTitle.copy(fontWeight = FontWeight.Normal), color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIconButton(
            Icons.Outlined.NorthWest, "Use ${query.text}", fill, Modifier.padding(end = 4.dp),
            tint = BoomColor.textSecondary, iconSize = 20.dp,
        )
    }
}

/** Results: the filter pills, "Showing results for …", then the shelves or the filtered list. */
@Composable
private fun ResultsView(model: SearchModel) {
    val results = model.results
    val list = rememberPageListState("results")
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("search.results"),
        state = list,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "filters") {
            ChipBar(searchFilters, results.filter, { it.title }, Modifier.padding(vertical = 4.dp)) { filter ->
                model.select(filter)
                scope.launch { list.scrollToItem(0) }
            }
        }
        when (val phase = results.phase) {
            ResultsLoader.Phase.Idle, ResultsLoader.Phase.Loading -> item(key = "loading") {
                LoadingView(if (results.filter == null) LoadingStyle.SHELVES else LoadingStyle.LIST, Modifier.padding(top = 12.dp))
            }
            is ResultsLoader.Phase.Failed -> item(key = "error") { PageErrorView(phase.message) { results.retry() } }
            is ResultsLoader.Phase.Loaded -> {
                phase.page.correctedQuery?.let { item(key = "corrected") { CorrectionLine(it) } }
                shelves(phase.page, model)
            }
            is ResultsLoader.Phase.InList -> {
                phase.page.correctedQuery?.let { item(key = "corrected") { CorrectionLine(it) } }
                if (phase.list.content.isEmpty()) {
                    item(key = "empty") {
                        MessageView("No results", message = "Try a different filter.", icon = Icons.Outlined.Search)
                    }
                }
                items(phase.list.content, key = { "item:" + it.id }) { ItemRow(it, Modifier.padding(vertical = 2.dp)) }
                if (phase.list.more != MoreState.End) {
                    item(key = "more") { LoadMoreRow(phase.list.more) { results.loadMore() } }
                }
            }
        }
    }
}

private fun LazyListScope.shelves(page: SearchResults, model: SearchModel) {
    val shelves = page.shelves.filter { it.items.isNotEmpty() || !it.text.isNullOrEmpty() }
    if (shelves.isEmpty()) {
        item(key = "empty") { MessageView("No results", message = "Try different words.", icon = Icons.Outlined.Search) }
    }
    items(shelves, key = { "shelf:" + it.id }) { shelf ->
        val top = if (shelf.id == shelves.first().id) 12.dp else BoomMetrics.shelfSpacing
        ShelfView(shelf, Modifier.padding(top = top), onShowAll = { model.select(it) })
    }
}

/** "Showing results for …" when the words were corrected. */
@Composable
private fun CorrectionLine(corrected: String) {
    val text = buildAnnotatedString {
        append("Showing results for ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(corrected) }
    }
    Text(
        text, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .padding(top = 10.dp),
    )
}
