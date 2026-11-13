package io.github.boomboom.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicItem
import kotlin.math.roundToInt

/** The card's full width for a given cover size, so carousels can size their pages: videos are 16:9. */
fun cardWidth(item: MusicItem, cover: Dp = BoomMetrics.cardCover): Dp =
    if (item.isVideo) (cover.value * 16 / 9).roundToInt().dp else cover

/**
 * A carousel card: a cover (square, wide for videos, round for artists) over two lines of title and two of subtitle.
 * A tap follows [ItemActions.tap]; a long press shows the item's menu.
 *
 * @param cover The cover's height (and width for square items).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemCard(
    item: MusicItem,
    modifier: Modifier = Modifier,
    cover: Dp = BoomMetrics.cardCover,
    context: PlayContext = PlayContext.Single,
) {
    val actions = rememberItemActions()
    val player by actions.services.player.state.collectAsState()
    val coverWidth = cardWidth(item, cover)
    val isCurrent = item.asSong?.let { it.videoId == player.current?.videoId } ?: false
    val likeOverride = remember { mutableStateOf<LikeStatus?>(null) }
    var isMenuOpen by remember { mutableStateOf(false) }
    val centred = item.isArtist
    val subtitle = if (item is MusicItem.OfArtist) item.artist.detailText ?: "" else item.subtitle

    Column(
        modifier
            .width(coverWidth)
            .clip(RoundedCornerShape(BoomMetrics.cardRadius))
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (item.asSong == null) "Open" else "Play",
                onLongClickLabel = "More actions",
                onLongClick = { isMenuOpen = true },
                onClick = { actions.tap(item, context) },
            )
            .semantics(mergeDescendants = true) {}
            .testTag("card.${item.kindTag}"),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ArtworkView(
                item.artwork, width = coverWidth, height = cover, cornerRadius = BoomMetrics.cardRadius,
                isCircle = item.isArtist,
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .size(coverWidth, cover)
                        .clip(RoundedCornerShape(BoomMetrics.cardRadius))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingBars(player.isPlayingOrAboutTo, Modifier.size(26.dp, 22.dp))
                }
            }
        }
        Column(
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.isExplicit) {
                    Box(Modifier.size(14.dp, 21.dp), contentAlignment = Alignment.Center) { ExplicitBadge() }
                }
                Text(
                    item.title, style = BoomFont.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) BoomColor.accent else BoomColor.textPrimary,
                    textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                )
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                )
            }
        }
    }
    if (isMenuOpen) ItemMenuSheet(item, onDismiss = { isMenuOpen = false }, likeOverride = likeOverride)
}
