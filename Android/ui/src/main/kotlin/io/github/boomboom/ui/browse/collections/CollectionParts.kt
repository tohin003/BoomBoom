package io.github.boomboom.ui.browse.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.MusicItem
import io.github.boomboom.ui.browse.PageBarHeight
import io.github.boomboom.ui.browse.headerOffset
import io.github.boomboom.ui.design.AccentPlayButton
import io.github.boomboom.ui.design.ArtworkBackdrop
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.CircleIconButton
import io.github.boomboom.ui.design.ExpandableText
import io.github.boomboom.ui.design.ExplicitBadge
import io.github.boomboom.ui.design.ItemMenuSheet

/**
 * The soft copy of a cover behind an album's or playlist's header (and its bar), fading into the background and sliding
 * up with the page.
 */
@Composable
fun BoxScope.CollectionBackdrop(artwork: Artwork, list: LazyListState, height: Dp = 470.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                translationY = -list.headerOffset().coerceAtMost(size.height)
                alpha = 0.6f
            },
    ) {
        ArtworkBackdrop(artwork, Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to BoomColor.background.copy(alpha = 0.15f),
                        0.55f to BoomColor.background.copy(alpha = 0.55f),
                        1f to BoomColor.background,
                    ),
                ),
        )
    }
}

/** The large centred cover, title and lines at the top of an album or playlist. */
@Composable
fun CollectionHeader(
    artwork: Artwork,
    title: String,
    isExplicit: Boolean = false,
    description: String? = null,
    lines: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkView(
            artwork, width = 240.dp, cornerRadius = BoomMetrics.cardRadius,
            modifier = Modifier.shadow(20.dp, RoundedCornerShape(BoomMetrics.cardRadius), ambientColor = Color.Black, spotColor = Color.Black),
        )
        Row(
            Modifier.padding(top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title, style = BoomFont.pageTitle.copy(fontSize = BoomFont.shelfTitle.fontSize, lineHeight = BoomFont.shelfTitle.lineHeight),
                color = BoomColor.textPrimary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { heading() }
                    .testTag("collection.title"),
            )
            if (isExplicit) ExplicitBadge()
        }
        CompositionLocalProvider(
            LocalTextStyle provides BoomFont.itemSubtitle.copy(textAlign = TextAlign.Center),
            LocalContentColor provides BoomColor.textSecondary,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), content = lines)
        }
        val hasDescription = !description.isNullOrEmpty()
        if (hasDescription) ExpandableText(description!!, Modifier.padding(top = 12.dp), collapsedLines = 2)
        Box(Modifier.padding(top = if (hasDescription) 4.dp else 18.dp)) { actions() }
    }
}

/** The row of buttons under a header: two round buttons, the big yellow Play, two more round buttons. */
@Composable
fun CollectionActions(
    play: () -> Unit,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), content = leading)
        AccentPlayButton(play, Modifier.padding(horizontal = 22.dp).testTag("collection.play"))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.Start), content = trailing)
    }
}

/** A round "more" button that opens an item's menu. */
@Composable
fun CircleMenuButton(item: MusicItem) {
    var isOpen by remember { mutableStateOf(false) }
    CircleIconButton(Icons.Filled.MoreHoriz, "More actions", { isOpen = true }, Modifier.testTag("collection.more"))
    if (isOpen) ItemMenuSheet(item, onDismiss = { isOpen = false })
}

/** How far down the page's own title sits, for when the bar starts showing it. */
val CollectionTitleThreshold = 330.dp - PageBarHeight
