package io.github.boomboom.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.Song
import kotlin.math.abs
import kotlin.math.sin

/** What sits at the front of a row. */
sealed class RowLeading {
    /** The item's cover (round for artists, wide for videos). */
    data object Cover : RowLeading()

    /** A chart position before the cover. */
    data class Rank(val rank: Int) : RowLeading()

    /** An album track's number, without a cover. */
    data class TrackNumber(val number: Int) : RowLeading()
}

/**
 * One line of a list: a song, an album, an artist or a playlist.
 *
 * A tap follows [ItemActions.tap] unless [onTap] is given. A long press and the trailing "more" button show the item's
 * menu. While the row's song is the player's current one, the title turns accent and bars move over the cover.
 *
 * @param context How a song plays when tapped: alone with its radio, or as part of a list.
 * @param showsKind Starts a song's second line with "Song" or "Video". Lists of one kind (charts, playlists) leave it
 *   out to make room for the artist and plays.
 * @param inset The side padding. Containers that pad themselves pass 0.
 * @param menuExtras Entries added to the item's menu after the usual ones.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    item: MusicItem,
    modifier: Modifier = Modifier,
    leading: RowLeading = RowLeading.Cover,
    context: PlayContext = PlayContext.Single,
    showsMore: Boolean = true,
    showsKind: Boolean = true,
    inset: Dp = BoomMetrics.margin,
    menuExtras: List<MenuExtra> = emptyList(),
    onTap: (() -> Unit)? = null,
) {
    val actions = rememberItemActions()
    val services = actions.services
    val player by services.player.state.collectAsState()
    val downloads by services.downloads.state.collectAsState()
    val song = item.asSong
    val isCurrent = song != null && player.current?.videoId == song.videoId
    val isAvailable = song?.isAvailable ?: true
    val likeOverride = remember { mutableStateOf<LikeStatus?>(null) }
    var isMenuOpen by remember { mutableStateOf(false) }
    val download = when (item) {
        is MusicItem.OfSong -> downloads.stateOf(item.song.videoId)
        is MusicItem.OfAlbum -> downloads.collectionState(item.album.id)
        is MusicItem.OfPlaylist -> downloads.collectionState(item.playlist.id)
        is MusicItem.OfArtist -> null
    }
    val second = secondLine(item, leading, showsKind)

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BoomMetrics.rowCover + 8.dp)
            .alpha(if (isAvailable) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = BoomMetrics.rowCover + 8.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (song == null) "Open" else "Play",
                    onLongClickLabel = "More actions",
                    onLongClick = { isMenuOpen = true },
                    onClick = { if (onTap != null) onTap() else actions.tap(item, context) },
                )
                .semantics(mergeDescendants = true) { selected = isCurrent }
                .testTag("row.${item.kindTag}")
                .padding(start = inset, end = if (showsMore) 0.dp else inset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
        ) {
            Leading(item, leading, isCurrent, player.isPlayingOrAboutTo)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.title, style = BoomFont.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) BoomColor.accent else BoomColor.textPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isExplicit) ExplicitBadge()
                }
                if (second.isNotEmpty() || download != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        DownloadBadge(download)
                        Text(
                            second, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val duration = song?.duration
            if (leading is RowLeading.TrackNumber && duration != null) {
                Text(boomTime(duration), style = BoomFont.time, color = BoomColor.textSecondary)
            }
        }
        if (showsMore) {
            MoreButton(item.title, Modifier.padding(end = (inset - 10.dp).coerceAtLeast(0.dp))) { isMenuOpen = true }
        }
    }
    if (isMenuOpen) ItemMenuSheet(item, onDismiss = { isMenuOpen = false }, likeOverride = likeOverride, extras = menuExtras)
}

/** [ItemRow] for a song. */
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    leading: RowLeading = RowLeading.Cover,
    context: PlayContext = PlayContext.Single,
    showsMore: Boolean = true,
    showsKind: Boolean = true,
) {
    ItemRow(MusicItem.OfSong(song), modifier, leading, context, showsMore, showsKind)
}

/** The trailing three dots of a row or card: opens the item's menu. */
@Composable
fun MoreButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BarIconButton(
        Icons.Filled.MoreVert, "More actions for $title", onClick, modifier.testTag("row.more"),
        tint = BoomColor.textSecondary, iconSize = 22.dp,
    )
}

@Composable
private fun Leading(item: MusicItem, leading: RowLeading, isCurrent: Boolean, isPlaying: Boolean) {
    when (leading) {
        RowLeading.Cover -> RowCover(item, isCurrent, isPlaying)
        is RowLeading.Rank -> {
            Text(
                "${leading.rank}", style = BoomFont.time.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = BoomColor.textPrimary, modifier = Modifier.widthIn(min = 24.dp),
            )
            RowCover(item, isCurrent, isPlaying)
        }
        is RowLeading.TrackNumber -> Box(Modifier.size(28.dp, 44.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                NowPlayingBars(isPlaying, Modifier.size(16.dp, 14.dp), color = BoomColor.accent)
            } else {
                Text("${leading.number}", style = BoomFont.time.copy(fontSize = 14.sp), color = BoomColor.textSecondary)
            }
        }
    }
}

@Composable
private fun RowCover(item: MusicItem, isCurrent: Boolean, isPlaying: Boolean) {
    val size = BoomMetrics.rowCover
    val width = if (item.isVideo) size * 16 / 9 else size
    Box(contentAlignment = Alignment.Center) {
        ArtworkView(item.artwork, width = width, height = size, isCircle = item.isArtist)
        if (isCurrent) {
            Box(
                Modifier
                    .size(width, size)
                    .clip(RoundedCornerShape(BoomMetrics.coverRadius))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                NowPlayingBars(isPlaying, Modifier.size(18.dp, 16.dp))
            }
        }
    }
}

/** "Song • Artist • 3:45": the item's own line, then plays or the length where known. */
private fun secondLine(item: MusicItem, leading: RowLeading, showsKind: Boolean): String {
    val song = item.asSong ?: return item.subtitle
    if (leading is RowLeading.TrackNumber) {
        return listOf(song.artistLine, song.playsText ?: "").filter { it.isNotEmpty() }.joinToString(" • ")
    }
    val extra = song.playsText ?: song.duration?.let(::boomTime) ?: ""
    val first = if (showsKind) item.subtitle else song.artistLine
    return listOf(first, extra).filter { it.isNotEmpty() }.joinToString(" • ")
}

/** The small "E" next to an explicit title. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BoomColor.textSecondary)
            .semantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "E", color = BoomColor.background, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp,
            style = BoomFont.caption.copy(lineHeight = 9.sp),
        )
    }
}

/** Three bars that bounce while playing and rest when paused. */
@Composable
fun NowPlayingBars(isAnimating: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    var millis by remember { mutableLongStateOf(0L) }
    if (isAnimating) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) withInfiniteAnimationFrameMillis { millis = it - start }
        }
    }
    Canvas(
        modifier.semantics { stateDescription = if (isAnimating) "Now playing" else "Paused" },
    ) {
        val gap = size.width * 0.18f
        val bar = (size.width - gap * 2) / 3
        val time = millis / 1000.0
        for (index in 0 until 3) {
            val phase = time * (5.2 + index * 1.7) + index * 1.3
            val level = if (isAnimating) 0.3 + 0.7 * abs(sin(phase)) else listOf(0.45, 0.8, 0.6)[index]
            val height = (size.height * level).toFloat()
            drawRoundRect(
                color, Offset(index * (bar + gap), size.height - height), Size(bar, height),
                CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}
