package io.github.boomboom.ui.browse.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LoadStateView
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.PageTitle
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ChipBar
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.ShelfView

/** A titled page of shelves for any endpoint: a "More" page, a mood, New releases, Charts. */
@Composable
fun BrowseScreen(source: BrowsePageModel.Source) {
    val services = LocalAppServices.current
    val model = rememberPageModel { BrowsePageModel(source, services.api, it) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(40.dp)
    LaunchedEffect(Unit) { model.loadIfNeeded() }

    PageFrame(model.state.valueOrNull?.title ?: "", isTitleShown) {
        LoadStateView(model.state, retry = { model.retry() }) { content ->
            RefreshBox({ model.refresh() }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("page.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    content.title?.let { title ->
                        item(key = "title") {
                            PageTitle(title, Modifier.padding(top = 4.dp, bottom = if (content.chips.isEmpty()) 0.dp else 12.dp))
                        }
                    }
                    if (content.chips.isNotEmpty()) {
                        item(key = "chips") {
                            ChipBar(content.chips, model.selectedChip(content.chips), { it.title }) { model.select(it) }
                        }
                    }
                    when (val shelves = content.shelves) {
                        BrowsePageModel.Shelves.Switching -> item(key = "switching") {
                            LoadingView(modifier = Modifier.padding(top = 20.dp))
                        }
                        is BrowsePageModel.Shelves.Shown -> {
                            if (shelves.list.content.isEmpty()) item(key = "empty") { MessageView("Nothing here yet") }
                            items(shelves.list.content, key = { "shelf:" + it.id }) { shelf ->
                                ShelfView(shelf, Modifier.padding(top = BoomMetrics.shelfSpacing))
                            }
                            if (shelves.list.more != MoreState.End) {
                                item(key = "more") { LoadMoreRow(shelves.list.more) { model.loadMore() } }
                            }
                        }
                    }
                }
            }
        }
    }
}
