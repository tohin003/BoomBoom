package io.github.boomboom.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The shape of the page being loaded, for [LoadingView]. */
enum class LoadingStyle {
    /** Shelves of cards and rows (Home, Explore, a page of shelves). */
    SHELVES,

    /** A plain list of rows (filtered search, a library tab). */
    LIST,

    /** A big centred cover, title lines and a tracklist (album, playlist). */
    COLLECTION,

    /** A wide banner, a name and shelves (artist). */
    ARTIST,
}

/** Placeholders shaped like the content that is on its way, with a soft shimmer. */
@Composable
fun LoadingView(style: LoadingStyle = LoadingStyle.SHELVES, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .shimmering()
            .clearAndSetSemantics { contentDescription = "Loading" }
            .testTag("loading"),
        verticalArrangement = Arrangement.spacedBy(BoomMetrics.shelfSpacing),
    ) {
        when (style) {
            LoadingStyle.SHELVES -> {
                SkeletonColumns()
                SkeletonCarousel()
                SkeletonCarousel()
            }
            LoadingStyle.LIST -> SkeletonRows(9)
            LoadingStyle.COLLECTION -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SkeletonBox(240.dp, 240.dp, BoomMetrics.cardRadius)
                    SkeletonBox(200.dp, 22.dp)
                    SkeletonBox(140.dp, 14.dp)
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(2) { SkeletonBox(44.dp, 44.dp, 22.dp) }
                        SkeletonBox(64.dp, 64.dp, 32.dp)
                        repeat(2) { SkeletonBox(44.dp, 44.dp, 22.dp) }
                    }
                }
                SkeletonRows(6, covers = false)
            }
            LoadingStyle.ARTIST -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBox(null, 260.dp, 0.dp)
                    SkeletonBox(120.dp, 14.dp, modifier = Modifier.padding(horizontal = BoomMetrics.margin))
                    Row(
                        Modifier.padding(horizontal = BoomMetrics.margin),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SkeletonBox(110.dp, 36.dp, 18.dp)
                        SkeletonBox(110.dp, 36.dp, 18.dp)
                    }
                }
                SkeletonRows(5)
            }
        }
    }
}

/** A grey block in a skeleton. A null [width] fills the row. */
@Composable
fun SkeletonBox(width: Dp?, height: Dp, radius: Dp = 4.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(BoomColor.surfaceRaised),
    )
}

@Composable
private fun SkeletonRows(count: Int, covers: Boolean = true) {
    Column(
        Modifier.padding(horizontal = BoomMetrics.margin),
        verticalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        repeat(count) { index ->
            Row(
                Modifier.height(BoomMetrics.rowCover + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (covers) {
                    SkeletonBox(BoomMetrics.rowCover, BoomMetrics.rowCover)
                } else {
                    SkeletonBox(16.dp, 14.dp, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(listOf(180, 140, 210, 160)[index % 4].dp, 14.dp)
                    SkeletonBox(listOf(120, 160, 100, 140)[index % 4].dp, 12.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonCarousel() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBox(170.dp, 22.dp, modifier = Modifier.padding(horizontal = BoomMetrics.margin))
        Row(
            Modifier
                .padding(horizontal = BoomMetrics.margin)
                .fillMaxWidth()
                .clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(BoomMetrics.cardCover, BoomMetrics.cardCover, BoomMetrics.cardRadius)
                    SkeletonBox(120.dp, 13.dp)
                    SkeletonBox(90.dp, 11.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonColumns() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBox(90.dp, 11.dp, modifier = Modifier.padding(horizontal = BoomMetrics.margin))
        SkeletonBox(150.dp, 22.dp, modifier = Modifier.padding(horizontal = BoomMetrics.margin))
        SkeletonRows(4)
    }
}

/** A light band sweeping across, for skeletons. Drawn only over what the content drew. */
@Composable
fun Modifier.shimmering(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.6f
            val start = phase * size.width * 1.4f
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                    startX = start, endX = start + band,
                ),
                topLeft = Offset.Zero, size = size, blendMode = BlendMode.SrcAtop,
            )
        }
}

/**
 * How many times the phone has got a connection back since the app started (the app counts and provides it). A page
 * that could not load watches it and tries again by itself, so the listener need not go looking for a button after
 * leaving a lift.
 */
val LocalNetworkReturns = compositionLocalOf { 0 }

/** A message with a Retry button, for a page that could not load. It retries by itself when the connection returns. */
@Composable
fun ErrorView(
    message: String = "Couldn't load this page.",
    modifier: Modifier = Modifier,
    retry: (() -> Unit)? = null,
) {
    val returns = LocalNetworkReturns.current
    val seen = remember { returns }
    val latestRetry by rememberUpdatedState(retry)
    LaunchedEffect(returns) {
        if (returns != seen) {
            // A network that has just come up needs a moment before names resolve.
            delay(1200)
            latestRetry?.invoke()
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 80.dp)
            .padding(32.dp)
            .testTag("error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = BoomColor.textTertiary, modifier = Modifier.size(40.dp))
        Text(message, style = BoomFont.itemTitle, color = BoomColor.textSecondary, textAlign = TextAlign.Center)
        if (retry != null) PillButton("Retry", retry, Modifier.testTag("error.retry"))
    }
}

/** A quiet message for an empty list ("No results", "Nothing here yet"). */
@Composable
fun MessageView(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.AutoMirrored.Outlined.QueueMusic,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 60.dp)
            .padding(32.dp)
            .testTag("message"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = BoomColor.textTertiary, modifier = Modifier.size(40.dp))
        Text(title, style = BoomFont.messageTitle, color = BoomColor.textPrimary, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, textAlign = TextAlign.Center)
        }
    }
}
