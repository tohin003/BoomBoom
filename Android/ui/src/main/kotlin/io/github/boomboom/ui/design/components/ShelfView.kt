package io.github.boomboom.ui.design

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** How the songs of a shelf play when tapped. */
sealed class ShelfSongMode {
    /** Each song plays alone with its radio (Home, search). */
    data object Radio : ShelfSongMode()

    /** The shelf is a tracklist: a tap plays the shelf's songs from that one ("Top songs" on an artist). */
    data class Tracklist(val source: QueueSource?) : ShelfSongMode()
}

/**
 * A [Shelf], drawn by its layout, under a header with an optional strapline, round picture and "More" pill.
 *
 * @param rowLimit For list layouts, shows at most this many rows and a "Show all" button under them.
 * @param onShowAll Applies a search shelf's `moreFilter`. Without it, only `more` endpoints get a button.
 */
@Composable
fun ShelfView(
    shelf: Shelf,
    modifier: Modifier = Modifier,
    songMode: ShelfSongMode = ShelfSongMode.Radio,
    rowLimit: Int? = null,
    onShowAll: ((SearchFilter) -> Unit)? = null,
) {
    val navigator = LocalBoomNavigator.current
    val moreFilter = shelf.moreFilter
    val more = shelf.more
    val moreAction: (() -> Unit)? = when {
        moreFilter != null && onShowAll != null -> { { onShowAll(moreFilter) } }
        more != null -> { { navigator.open(more) } }
        else -> null
    }
    val songs = shelf.items.mapNotNull { it.asSong }
    val context = when (songMode) {
        ShelfSongMode.Radio -> PlayContext.Single
        is ShelfSongMode.Tracklist -> PlayContext.InList(songs, songMode.source)
    }
    // All plain songs ("Top songs"), so the rows need not say "Song" each time.
    val isOneKind = shelf.items.all { it.asSong?.kind == Song.Kind.SONG }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (shelf.layout != Shelf.Layout.TOP_RESULT || shelf.title.isNotEmpty()) {
            ShelfHeader(shelf.title, strapline = shelf.strapline, artwork = shelf.headerArtwork, action = moreAction)
        }
        when (shelf.layout) {
            Shelf.Layout.CAROUSEL -> ShelfCarousel(shelf.items, context)
            Shelf.Layout.COLUMNS -> ShelfColumns(shelf.items, context)
            Shelf.Layout.LIST -> ShelfRows(shelf, context, rowLimit, moreAction, showsKind = !isOneKind) { RowLeading.Cover }
            Shelf.Layout.RANKED -> ShelfRows(shelf, context, rowLimit, moreAction, showsKind = false) { RowLeading.Rank(it + 1) }
            Shelf.Layout.GRID -> ShelfGrid(shelf.items, context)
            Shelf.Layout.TOP_RESULT -> shelf.items.firstOrNull()?.let { first ->
                TopResultCard(first, Modifier.padding(horizontal = BoomMetrics.margin))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in shelf.items.drop(1)) ItemRow(item, context = context)
                }
            }
            Shelf.Layout.TEXT -> shelf.text?.takeIf { it.isNotEmpty() }?.let {
                ExpandableText(it, Modifier.padding(horizontal = BoomMetrics.margin))
            }
        }
    }
}

@Composable
private fun ShelfRows(
    shelf: Shelf,
    context: PlayContext,
    rowLimit: Int?,
    moreAction: (() -> Unit)?,
    showsKind: Boolean,
    leading: (Int) -> RowLeading,
) {
    val shown = shelf.items.take(rowLimit ?: shelf.items.size)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEachIndexed { index, item ->
            ItemRow(item, leading = leading(index), context = context, showsKind = showsKind)
        }
    }
    if (rowLimit != null && (shelf.items.size > rowLimit || shelf.more != null) && moreAction != null) {
        PillButton("Show all", moreAction, Modifier.padding(horizontal = BoomMetrics.margin).testTag("shelf.showAll"))
    }
}

/**
 * A shelf's header: strapline, round picture, title and the "More" pill.
 */
@Composable
fun ShelfHeader(
    title: String,
    modifier: Modifier = Modifier,
    strapline: String? = null,
    artwork: Artwork? = null,
    action: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (artwork != null) ArtworkView(artwork, width = 48.dp, isCircle = true)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() }
                .testTag("shelf.header"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!strapline.isNullOrEmpty()) {
                Text(
                    strapline.uppercase(Locale.UK), style = BoomFont.strapline, color = BoomColor.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(title, style = BoomFont.shelfTitle, color = BoomColor.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            PillButton("More", action, compact = true, contentDescription = "More $title", modifier = Modifier.testTag("shelf.more"))
        }
    }
}

/** Side-scrolling cards that come to rest on a card. */
@Composable
private fun ShelfCarousel(items: List<MusicItem>, context: PlayContext) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = BoomMetrics.margin),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        flingBehavior = rememberSnapFlingBehavior(state),
    ) {
        items(items, key = { it.id }) { ItemCard(it, context = context) }
    }
}

/** "Quick picks": rows in columns of four that scroll sideways and come to rest on a column. */
@Composable
private fun ShelfColumns(items: List<MusicItem>, context: PlayContext) {
    val state = rememberLazyListState()
    val columns = items.chunked(4)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = floor(maxWidth.value * 0.88f).dp
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = BoomMetrics.margin),
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            items(columns, key = { it.first().id }) { column ->
                Column(Modifier.width(columnWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in column) {
                        ItemRow(item, Modifier.padding(end = 14.dp), context = context, showsKind = false, inset = 0.dp)
                    }
                }
            }
        }
    }
}

/** Cards two across. */
@Composable
private fun ShelfGrid(items: List<MusicItem>, context: PlayContext) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin),
    ) {
        val spacing = 12.dp
        val cover = max(80f, floor((maxWidth - spacing).value / 2)).dp
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for (pair in items.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    for (item in pair) {
                        ItemCard(item, cover = if (item.isVideo) cover * 9 / 16 else cover, context = context)
                    }
                }
            }
        }
    }
}

/** The big "Top result" card in search: picture, name, kind, and Play with Radio, Shuffle or Play next. */
@Composable
fun TopResultCard(item: MusicItem, modifier: Modifier = Modifier) {
    val actions = rememberItemActions()
    var isMenuOpen by remember { mutableStateOf(false) }
    val likeOverride = remember { mutableStateOf<LikeStatus?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BoomMetrics.cardRadius))
            .background(BoomColor.surface)
            .padding(14.dp)
            .testTag("topResult"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { actions.tap(item) }
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtworkView(
                item.artwork, width = if (item.isVideo) 128.dp else 80.dp, height = 80.dp,
                cornerRadius = BoomMetrics.cardRadius, isCircle = item.isArtist,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.title, style = BoomFont.headline, color = BoomColor.textPrimary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isExplicit) ExplicitBadge()
                }
                Text(item.subtitle, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            MoreButton(item.title) { isMenuOpen = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val half = Modifier.weight(1f)
            when (item) {
                is MusicItem.OfArtist -> {
                    PillButton("Shuffle", { actions.play(item, shuffled = true) }, half, PillStyle.FILLED, Icons.Filled.Shuffle, fillWidth = true)
                    PillButton("Radio", { actions.startRadio(item) }, half, PillStyle.TONAL, Icons.Outlined.Sensors, fillWidth = true)
                }
                is MusicItem.OfSong -> {
                    PillButton("Play", { actions.play(item) }, half, PillStyle.FILLED, Icons.Filled.PlayArrow, fillWidth = true)
                    PillButton("Play next", { actions.enqueue(item, next = true) }, half, PillStyle.TONAL, Icons.Filled.QueuePlayNext, fillWidth = true)
                }
                else -> {
                    PillButton("Play", { actions.play(item) }, half, PillStyle.FILLED, Icons.Filled.PlayArrow, fillWidth = true)
                    PillButton("Shuffle", { actions.play(item, shuffled = true) }, half, PillStyle.TONAL, Icons.Filled.Shuffle, fillWidth = true)
                }
            }
        }
    }
    if (isMenuOpen) ItemMenuSheet(item, onDismiss = { isMenuOpen = false }, likeOverride = likeOverride)
}

/** Text that shows a few lines and opens fully on "More". */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 3) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Text(
            text, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!isExpanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || isExpanded) {
            Text(
                if (isExpanded) "Less" else "More",
                style = BoomFont.button.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textPrimary,
                modifier = Modifier
                    .heightIn(min = BoomMetrics.minimumTarget)
                    .clickable(role = Role.Button, onClickLabel = if (isExpanded) "Show less" else "Show more") {
                        isExpanded = !isExpanded
                    }
                    .padding(vertical = 14.dp),
            )
        } else {
            Spacer(Modifier.heightIn(min = 4.dp))
        }
    }
}
