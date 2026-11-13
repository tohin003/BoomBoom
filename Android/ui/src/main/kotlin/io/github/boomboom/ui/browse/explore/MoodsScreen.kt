package io.github.boomboom.ui.browse.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.Chip
import io.github.boomboom.models.ChipGroup
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.LoadStateView
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.PageTitle
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.RequestSlot
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.ShelfHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** The mood and genre groups. Main thread only. */
class MoodsModel(private val api: MusicAPI, scope: CoroutineScope) {
    var state by mutableStateOf<LoadState<List<ChipGroup>>>(LoadState.Loading)
        private set

    /** Retry or a refresh replaces the request out; leaving the page cancels it. */
    private val request = RequestSlot(scope)

    /** The first load, and again after a failure when the page comes back. A request already out is left to finish. */
    fun loadIfNeeded() {
        if (state.isLoaded || request.isBusy) return
        load()
    }

    fun retry() {
        load()
    }

    /** Pull to refresh: the tiles stay until the new ones arrive, and stay if it fails. */
    fun refresh(): Job = load()

    private fun load(): Job {
        if (!state.isLoaded) state = LoadState.Loading
        return request.start({ api.moodsAndGenres() }) { result ->
            result.onSuccess { state = LoadState.Loaded(it) }
                .onFailure { if (!state.isLoaded) state = LoadState.Failed(userMessage(it)) }
        }
    }
}

/** The mood and genre tiles, in titled groups, with their colour on the left edge. */
@Composable
fun MoodsScreen() {
    val services = LocalAppServices.current
    val model = rememberPageModel { MoodsModel(services.api, it) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(40.dp)
    LaunchedEffect(Unit) { model.loadIfNeeded() }

    PageFrame("Moods & genres", isTitleShown) {
        LoadStateView(model.state, retry = { model.retry() }) { groups ->
            RefreshBox({ model.refresh() }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("moods.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "title") { PageTitle("Moods & genres", Modifier.padding(top = 4.dp)) }
                    for (group in groups) {
                        item(key = "group:" + group.id) {
                            ShelfHeader(group.title, Modifier.padding(top = BoomMetrics.shelfSpacing, bottom = 12.dp))
                        }
                        items(group.chips.chunked(2), key = { "tiles:" + group.id + it.first().id }) { pair ->
                            Row(
                                Modifier
                                    .padding(horizontal = BoomMetrics.margin)
                                    .padding(bottom = 10.dp)
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (chip in pair) MoodTile(chip, Modifier.weight(1f).fillMaxHeight())
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodTile(chip: Chip, modifier: Modifier = Modifier) {
    val router = LocalShellRouter.current
    val endpoint = chip.endpoint
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BoomColor.surfaceRaised)
            .then(
                if (endpoint != null) {
                    Modifier.clickable(role = Role.Button) { router.push(BrowseRoute.Page(endpoint)) }
                } else {
                    Modifier
                },
            )
            .testTag("mood.tile"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(BoomColor.hex(chip.colorHex ?: 0x777777)),
        )
        Text(
            chip.title, style = BoomFont.itemSubtitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
