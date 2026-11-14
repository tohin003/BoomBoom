package io.github.boomboom.ui.browse.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.PageTitle
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ErrorView
import io.github.boomboom.ui.design.ItemRow
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.ShelfHeader

/** History: the songs played, a titled list per day, newest first. A tap plays the song with its radio. */
@Composable
fun HistoryScreen() {
    val services = LocalAppServices.current
    val session by services.session.state.collectAsState()
    val model = rememberPageModel { HistoryModel(services.api, it) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(40.dp)

    LaunchedEffect(session.revision, session.isSignedIn) {
        model.sync(session.revision)
        if (session.isSignedIn) model.loadIfNeeded()
    }

    PageFrame("History", isTitleShown) {
        if (!session.isSignedIn) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PageTitle("History", Modifier.padding(top = 4.dp))
                SignedOutView(
                    "Sign in to see your history",
                    "The songs you play are kept in your own account, so there is nothing to show yet.",
                )
            }
        } else {
            HistoryList(model, list)
        }
    }
}

@Composable
private fun HistoryList(model: HistoryModel, list: LazyListState) {
    RefreshBox({ model.refresh() }) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .testTag("history.list"),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "title") { PageTitle("History", Modifier.padding(top = 4.dp)) }
            when (val state = model.state) {
                LoadState.Loading -> item(key = "loading") { LoadingView(LoadingStyle.LIST, Modifier.padding(top = 16.dp)) }
                is LoadState.Failed -> item(key = "error") { ErrorView(state.message) { model.retry() } }
                is LoadState.Loaded -> {
                    val days = state.value.content
                    if (days.isEmpty()) {
                        item(key = "empty") {
                            MessageView("Nothing played yet", message = "Songs you play show up here, newest first.", icon = Icons.Outlined.History)
                        }
                    }
                    for (day in days) {
                        item(key = "day:" + day.title) {
                            ShelfHeader(day.title, Modifier.padding(top = BoomMetrics.shelfSpacing, bottom = 10.dp))
                        }
                        // The same song may be played twice in a day, so rows are keyed by position.
                        itemsIndexed(day.items, key = { index, _ -> "day:${day.title}:$index" }) { _, item ->
                            ItemRow(item, showsKind = false)
                        }
                    }
                    if (state.value.more != MoreState.End) {
                        item(key = "more") { LoadMoreRow(state.value.more) { model.loadMore() } }
                    }
                }
            }
        }
    }
}
