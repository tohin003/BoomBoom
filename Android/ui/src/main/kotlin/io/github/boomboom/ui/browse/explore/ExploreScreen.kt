package io.github.boomboom.ui.browse.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.BrowseTab
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.PageErrorView
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.home.TopBar
import io.github.boomboom.ui.browse.pages.BrowsePageModel
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.ShelfView

/** Explore: three big entries (New releases, Charts, Moods & genres), then Explore's own shelves. */
@Composable
fun ExploreScreen() {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val model = rememberPageModel { BrowsePageModel(BrowsePageModel.Source.Explore, services.api, it) }
    val list = rememberPageListState()
    LaunchedEffect(Unit) { model.loadIfNeeded() }
    val reselect = router.reselectTick(BrowseTab.EXPLORE)
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Explore")
        RefreshBox({ model.refresh() }) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag("explore.list"),
                state = list,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "tiles") {
                    Column(
                        Modifier.padding(horizontal = BoomMetrics.margin).padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ExploreTile("New releases", Icons.Filled.AutoAwesome) {
                            router.push(BrowseRoute.Page(BrowseEndpoint("FEmusic_new_releases")))
                        }
                        ExploreTile("Charts", Icons.AutoMirrored.Filled.TrendingUp) { router.push(BrowseRoute.Charts) }
                        ExploreTile("Moods & genres", Icons.Outlined.Mood) { router.push(BrowseRoute.Moods) }
                    }
                }
                when (val state = model.state) {
                    LoadState.Loading -> item(key = "loading") { LoadingView(modifier = Modifier.padding(top = BoomMetrics.shelfSpacing)) }
                    is LoadState.Failed -> item(key = "error") { PageErrorView(state.message) { model.retry() } }
                    is LoadState.Loaded -> when (val shelves = state.value.shelves) {
                        BrowsePageModel.Shelves.Switching -> item(key = "switching") {
                            LoadingView(modifier = Modifier.padding(top = BoomMetrics.shelfSpacing))
                        }
                        is BrowsePageModel.Shelves.Shown -> items(shelves.list.content, key = { "shelf:" + it.id }) { shelf ->
                            ShelfView(shelf, Modifier.padding(top = BoomMetrics.shelfSpacing))
                        }
                    }
                }
            }
        }
    }
}

/** One of Explore's three big entries. */
@Composable
private fun ExploreTile(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(BoomMetrics.cardRadius))
            .background(BoomColor.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("explore.tile")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = BoomColor.accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            title, style = BoomFont.sectionTitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BoomColor.textTertiary)
    }
}
