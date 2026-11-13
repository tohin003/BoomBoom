package io.github.boomboom.ui.browse.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.browse.LoadMoreRow
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.PageErrorView
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ChipBar
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.ShelfView
import kotlinx.coroutines.launch

/** Home: the brand bar, the mood chips, then the shelves, more of them as the listener scrolls. */
@Composable
fun HomeScreen() {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val model = rememberPageModel { HomeModel(services.api, it) }
    val list = rememberPageListState()
    val scope = rememberCoroutineScope()
    val session by services.session.state.collectAsState()

    LaunchedEffect(session.revision) { model.sync(session.revision) }
    // A tap on Home while it is shown at its root: back to the top.
    val reselect = router.reselectTick(BrowseTab.HOME)
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar()
        RefreshBox({ model.refresh() }) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag("home.list"),
                state = list,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (model.chips.isNotEmpty()) {
                    item(key = "chips") {
                        val selected = model.selectedChip?.let { chosen -> model.chips.firstOrNull { it.title == chosen.title } }
                        ChipBar(model.chips, selected, { it.title }, Modifier.padding(vertical = 4.dp)) { chip ->
                            model.select(chip)
                            scope.launch { list.scrollToItem(0) }
                        }
                    }
                }
                when (val state = model.state) {
                    LoadState.Loading -> item(key = "loading") { LoadingView(modifier = Modifier.padding(top = 16.dp)) }
                    is LoadState.Failed -> item(key = "error") { PageErrorView(state.message) { model.retry() } }
                    is LoadState.Loaded -> {
                        val shelves = state.value.content
                        items(shelves, key = { "shelf:" + it.id }) { shelf ->
                            val top = if (shelf.id == shelves.first().id) BoomMetrics.shelfSpacing - 12.dp else BoomMetrics.shelfSpacing
                            ShelfView(shelf, Modifier.padding(top = top))
                        }
                        if (state.value.more != MoreState.End) {
                            item(key = "more") { LoadMoreRow(state.value.more) { model.loadMore() } }
                        }
                    }
                }
            }
        }
    }
}
