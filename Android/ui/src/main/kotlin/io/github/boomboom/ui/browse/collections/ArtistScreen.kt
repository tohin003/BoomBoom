package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.Shelf
import io.github.boomboom.ui.browse.LoadStateView
import io.github.boomboom.ui.browse.PageBarHeight
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.RefreshBox
import io.github.boomboom.ui.browse.headerOffset
import io.github.boomboom.ui.browse.rememberPageListState
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.browse.scrolledPast
import io.github.boomboom.ui.design.AccentPlayButton
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.CircleIconButton
import io.github.boomboom.ui.design.ExpandableText
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import io.github.boomboom.ui.design.ShelfHeader
import io.github.boomboom.ui.design.ShelfSongMode
import io.github.boomboom.ui.design.ShelfView
import io.github.boomboom.ui.design.rememberItemActions

/** How tall the artist's picture is, below the status bar. */
private val BannerHeight = 340.dp

/** An artist: a wide banner with the name over it, the audience, Subscribe / Radio / Shuffle / Play, shelves, About. */
@Composable
fun ArtistScreen(id: String) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val model = rememberPageModel { ArtistModel(id, services.api, it, appScope) }
    val list = rememberPageListState()
    val isTitleShown by list.scrolledPast(BannerHeight - 120.dp - PageBarHeight)
    LaunchedEffect(Unit) { model.loadIfNeeded() }
    val page = model.state.valueOrNull

    PageFrame(
        page?.artist?.name ?: "", isTitleShown,
        backdrop = page?.let { { ArtistBanner(if (it.banner.isEmpty) it.artist.artwork else it.banner, list) } },
    ) {
        LoadStateView(model.state, retry = { model.retry() }, style = LoadingStyle.ARTIST) { loaded ->
            val source = QueueSource(
                QueueSource.Kind.ARTIST, "Artist", loaded.artist.name,
                BrowseEndpoint(loaded.artist.id, pageType = BrowseEndpoint.PageType.ARTIST),
            )
            val firstList = loaded.shelves.firstOrNull { it.layout == Shelf.Layout.LIST }?.id
            RefreshBox({ model.refresh() }) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag("artist.list"),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "header") { ArtistHeader(loaded, model, source) }
                    items(loaded.shelves, key = { "shelf:" + it.id }) { shelf ->
                        if (shelf.id == firstList) {
                            ShelfView(
                                shelf, Modifier.padding(top = BoomMetrics.shelfSpacing),
                                songMode = ShelfSongMode.Tracklist(source), rowLimit = 5,
                            )
                        } else {
                            ShelfView(shelf, Modifier.padding(top = BoomMetrics.shelfSpacing))
                        }
                    }
                    loaded.description?.takeIf { it.isNotEmpty() }?.let { description ->
                        item(key = "about") { About(description, loaded.subscribersText) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(page: ArtistPage, model: ArtistModel, source: QueueSource) {
    val actions = rememberItemActions()
    val services = actions.services
    val session by services.session.state.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .padding(top = BannerHeight - 190.dp - PageBarHeight + 40.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            page.artist.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
                color = BoomColor.textPrimary, shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 24f),
            ),
            modifier = Modifier
                .semantics { heading() }
                .testTag("artist.name"),
        )
        val audience = listOfNotNull(page.monthlyListenersText, page.subscribersText)
        if (audience.isNotEmpty()) {
            Text(audience.joinToString(" • "), style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val subscribed = model.subscription.isOn
            PillButton(
                if (subscribed) "Subscribed" else "Subscribe",
                { if (session.isSignedIn) model.toggleSubscription() else actions.navigator.signIn() },
                Modifier
                    .semantics { selected = subscribed }
                    .testTag("artist.subscribe"),
            )
            val radio = page.radio
            PillButton(
                "Radio", { radio?.let { services.player.play(it, source) } }, style = PillStyle.TONAL,
                icon = Icons.Outlined.Sensors, enabled = radio != null,
            )
            Spacer(Modifier.weight(1f))
            val shuffle = page.shuffle
            CircleIconButton(Icons.Filled.Shuffle, "Shuffle", { shuffle?.let { services.player.play(it, source) } }, enabled = shuffle != null)
            AccentPlayButton({ playTopSongs(page, source, actions) }, size = 56.dp, modifier = Modifier.testTag("artist.play"))
        }
    }
}

private fun playTopSongs(page: ArtistPage, source: QueueSource, actions: io.github.boomboom.ui.design.ItemActions) {
    val top = page.shelves.firstOrNull { it.layout == Shelf.Layout.LIST }?.items?.mapNotNull { it.asSong } ?: emptyList()
    if (top.isNotEmpty()) {
        actions.playList(top, shuffled = false, source = source)
    } else {
        (page.shuffle ?: page.radio)?.let { actions.services.player.play(it, source) }
    }
}

@Composable
private fun About(description: String, subscribers: String?) {
    Column(Modifier.padding(top = BoomMetrics.shelfSpacing), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfHeader("About")
        Column(
            Modifier
                .padding(horizontal = BoomMetrics.margin)
                .fillMaxWidth()
                .clip(RoundedCornerShape(BoomMetrics.cardRadius))
                .background(BoomColor.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpandableText(description, collapsedLines = 4)
            subscribers?.let {
                Text(it, style = BoomFont.itemSubtitle.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary)
            }
        }
    }
}

/** The artist's wide picture across the top (behind the status bar too), fading into the background as the page moves. */
@Composable
private fun ArtistBanner(artwork: Artwork, list: LazyListState) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height: Dp = BannerHeight + top
    val heightPx = with(LocalDensity.current) { height.toPx() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                val offset = list.headerOffset()
                translationY = -minOf(offset, heightPx) * 0.6f
                alpha = 1f - minOf(1f, offset / heightPx)
            },
    ) {
        ArtworkView(artwork, width = maxWidth, height = height, cornerRadius = 0.dp)
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        0.75f to BoomColor.background.copy(alpha = 0.7f),
                        1f to BoomColor.background,
                    ),
                ),
        )
    }
}
