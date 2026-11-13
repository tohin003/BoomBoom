package io.github.boomboom.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.ui.browse.collections.AlbumScreen
import io.github.boomboom.ui.browse.collections.ArtistScreen
import io.github.boomboom.ui.browse.collections.PlaylistScreen
import io.github.boomboom.ui.browse.explore.ExploreScreen
import io.github.boomboom.ui.browse.explore.MoodsScreen
import io.github.boomboom.ui.browse.home.HomeScreen
import io.github.boomboom.ui.browse.pages.BrowsePageModel
import io.github.boomboom.ui.browse.pages.BrowseScreen
import io.github.boomboom.ui.browse.search.SearchScreen
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices

/**
 * The app's frame: Home, Explore and Library, each with its own stack, a flat tab bar, and the player bar above it
 * while a song is loaded.
 *
 * The content and the bars are stacked, never overlaid: the content ends where the bars begin, so the last row of
 * any list scrolls fully into view above them. With the keyboard up the bars step aside and the content ends above
 * the keyboard.
 *
 * @param playerBar The mini player, drawn [BoomMetrics.miniPlayerHeight] tall above the tab bar while a song is loaded.
 */
@Composable
fun BrowseShell(router: ShellRouter, playerBar: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val player by services.player.state.collectAsState()
    // The keyboard's own height, not `isImeVisible`: that one reads "up" until the first insets arrive.
    val isKeyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = router.canGoBack) { router.back() }
    CompositionLocalProvider(LocalShellRouter provides router) {
        Column(
            modifier
                .fillMaxSize()
                .background(BoomColor.background),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (isKeyboardUp) Modifier.imePadding() else Modifier)
                    .testTag("shell.content"),
            ) {
                val entry = router.current
                key(entry.id) {
                    CompositionLocalProvider(LocalBackStackEntry provides entry) { RouteScreen(entry.route) }
                }
            }
            if (!isKeyboardUp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(BoomColor.surface),
                ) {
                    AnimatedVisibility(
                        player.current != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(BoomMetrics.miniPlayerHeight)
                                .testTag("shell.playerBar"),
                        ) { playerBar() }
                    }
                    BoomTabBar(router.selected) { router.select(it) }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

/** The screen for a route. */
@Composable
private fun RouteScreen(route: BrowseRoute) {
    when (route) {
        is BrowseRoute.Root -> when (route.tab) {
            BrowseTab.HOME -> HomeScreen()
            BrowseTab.EXPLORE -> ExploreScreen()
            BrowseTab.LIBRARY -> ComingScreen("Library", showsBack = false)
        }
        is BrowseRoute.Page -> {
            val endpoint = route.endpoint
            when (endpoint.pageType) {
                BrowseEndpoint.PageType.ALBUM -> AlbumScreen(endpoint.browseId)
                BrowseEndpoint.PageType.ARTIST, BrowseEndpoint.PageType.USER_CHANNEL -> ArtistScreen(endpoint.browseId)
                BrowseEndpoint.PageType.PLAYLIST -> PlaylistScreen(endpoint.browseId)
                BrowseEndpoint.PageType.PODCAST, BrowseEndpoint.PageType.GENERIC ->
                    BrowseScreen(BrowsePageModel.Source.Endpoint(endpoint))
            }
        }
        is BrowseRoute.Search -> SearchScreen(route.query, route.submitted)
        BrowseRoute.Charts -> BrowseScreen(BrowsePageModel.Source.Charts(null))
        BrowseRoute.Moods -> MoodsScreen()
        BrowseRoute.Settings -> ComingScreen("Settings", showsBack = true)
        BrowseRoute.Downloads -> ComingScreen("Downloads", showsBack = true)
        is BrowseRoute.DownloadedCollection -> ComingScreen("Downloads", showsBack = true)
    }
}

/** The flat tab bar: three buttons, the selected one in the accent. */
@Composable
fun BoomTabBar(selected: BrowseTab, onSelect: (BrowseTab) -> Unit) {
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = BoomColor.separator)
        Row(
            Modifier
                .fillMaxWidth()
                .height(BoomMetrics.tabBarHeight)
                .testTag("tabBar"),
        ) {
            for (tab in BrowseTab.entries) {
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = isSelected }
                        .testTag(tab.tag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val color = if (isSelected) BoomColor.accent else BoomColor.textSecondary
                    Icon(tab.icon(isSelected), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(
                        tab.title, color = color, maxLines = 1,
                        fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun BrowseTab.icon(selected: Boolean): ImageVector = when (this) {
    BrowseTab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    BrowseTab.EXPLORE -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
    BrowseTab.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
}
