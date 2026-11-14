package io.github.boomboom.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.QueueSource
import io.github.boomboom.logic.RepeatMode
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.PreviewArt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The full player's page: header, cover, title, scrubber, controls, and the tabs to "Up next", "Lyrics", "Related"
 * (pulled up over the page by [PanelHost]).
 */
@Composable
internal fun PlayerScreen() {
    val services = LocalAppServices.current
    val player = services.player
    val state by player.state.collectAsState()
    val extras = remember { PlayerExtras() }
    var menu by remember { mutableStateOf<PlayerMenuPage?>(null) }

    val artwork = state.current?.artwork
    var backdrop by remember { mutableStateOf(BoomColor.surface) }
    val shownBackdrop by animateColorAsState(backdrop, tween(600), label = "backdrop")
    val context = LocalContext.current
    LaunchedEffect(artwork) {
        // A cover that fails to load must not leave the previous song's colour behind.
        backdrop = artwork?.let { backdropColor(context, it) } ?: BoomColor.surface
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("player"),
    ) {
        PlayerBackdrop(shownBackdrop)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            PlayerTopBar(state.source, onMore = { menu = PlayerMenuPage.Main }, Modifier.padding(horizontal = 4.dp))
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = max(80.dp.value, min(maxWidth.value - 48, maxHeight.value - 24)).dp
                CoverCarousel(player, side)
            }
            TitleBlock(Modifier.padding(horizontal = BoomMetrics.margin - 4.dp))
            Scrubber(player, Modifier.padding(start = BoomMetrics.margin + 8.dp, end = BoomMetrics.margin + 8.dp, top = 10.dp))
            TransportRow(player, Modifier.padding(horizontal = BoomMetrics.margin, vertical = 2.dp))
            FailureNotice(player)
            SecondaryRow(onSleepTimer = { menu = PlayerMenuPage.SleepTimer }, Modifier.padding(horizontal = BoomMetrics.margin + 16.dp))
            PanelTabsBar()
        }
        PanelHost(extras)
        menu?.let { page -> PlayerMenuSheet(page, onDismiss = { menu = null }) }
    }
}

/** The page's gradient from the cover's colour, rounded at the top while it is dragged down. */
@Composable
private fun PlayerBackdrop(color: Color) {
    val model = LocalOverlayModel.current
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // Read while drawing: following the finger does not recompose anything.
                val radius = min(max(model.offset.value, 0f) / 4f, 40.dp.toPx())
                val brush = Brush.verticalGradient(
                    0f to color, 0.55f to lerp(color, BoomColor.background, 0.45f), 1f to BoomColor.background,
                    startY = 0f, endY = size.height,
                )
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            0f, 0f, size.width, size.height + radius,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                }
                drawPath(path, brush)
            },
    )
}

/** The cover's colour, its brightness capped, as the iPhone app works it out: the whole picture averaged to a pixel. */
private suspend fun backdropColor(context: Context, artwork: Artwork): Color? {
    val url = artwork.url(96) ?: return null
    val bitmap: Bitmap = PreviewArt.bitmap(url)?.asAndroidBitmap() ?: try {
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        result.image.toBitmap()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return null
    }
    // Averaged over every pixel of a small copy (a one-pixel scale would only sample the middle).
    val (red, green, blue) = withContext(Dispatchers.Default) {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        Triple(
            pixels.sumOf { it shr 16 and 0xFF } / (pixels.size * 255f),
            pixels.sumOf { it shr 8 and 0xFF } / (pixels.size * 255f),
            pixels.sumOf { it and 0xFF } / (pixels.size * 255f),
        )
    }
    // Keep the hue, cap the brightness.
    val scale = min(1f, 0.42f / maxOf(red, green, blue, 0.001f))
    return Color(red * scale, green * scale, blue * scale)
}

// Header

@Composable
private fun PlayerTopBar(source: QueueSource?, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val model = LocalOverlayModel.current
    val navigator = LocalBoomNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            Icons.Filled.KeyboardArrowDown, "Close player", { model.dismiss() }, Modifier.testTag("player.close"),
            iconSize = 30.dp,
        )
        val endpoint = source?.endpoint
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .then(
                    if (endpoint != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = "Opens it") { navigator.open(endpoint) }
                    } else {
                        Modifier
                    },
                )
                .testTag("player.source"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "PLAYING FROM ${source?.label?.uppercase() ?: ""}".trim(), color = BoomColor.textSecondary,
                fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                source?.title ?: "Your queue", color = BoomColor.textPrimary, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerIconButton(Icons.Filled.MoreVert, "More", onMore, Modifier.testTag("player.more"))
    }
}

// Title and rating

@Composable
private fun TitleBlock(modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val player = services.player
    val state by player.state.collectAsState()
    val song = state.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RateButton(LikeStatus.DISLIKE, state.likeStatus, enabled = song != null)
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MarqueeText(
                song?.title ?: "", style = BoomFont.shelfTitle, align = TextAlign.Center, speed = 32f,
                modifier = Modifier.testTag("player.title"),
            )
            val artistId = song?.firstLinkedArtist?.id
            Text(
                song?.artistLine ?: "", style = BoomFont.itemTitle.copy(fontWeight = FontWeight.Normal),
                color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier
                    .then(
                        if (artistId != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Opens the artist") {
                                navigator.open(BrowseEndpoint(artistId, pageType = BrowseEndpoint.PageType.ARTIST))
                            }
                        } else {
                            Modifier
                        },
                    )
                    .testTag("player.artist"),
            )
        }
        RateButton(LikeStatus.LIKE, state.likeStatus, enabled = song != null)
    }
}

@Composable
private fun RateButton(status: LikeStatus, current: LikeStatus, enabled: Boolean) {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val isOn = current == status
    val isLike = status == LikeStatus.LIKE
    PlayerIconButton(
        icon = when {
            isLike && isOn -> Icons.Filled.ThumbUp
            isLike -> Icons.Outlined.ThumbUp
            isOn -> Icons.Filled.ThumbDown
            else -> Icons.Outlined.ThumbDown
        },
        label = if (isLike) "Like" else "Dislike",
        onClick = {
            // A rating needs an account: signed out, it would only flip and silently flip back.
            if (!services.session.isSignedIn) {
                navigator.signIn()
            } else {
                services.player.setLike(if (isOn) LikeStatus.INDIFFERENT else status)
            }
        },
        modifier = Modifier
            .semantics { selected = isOn }
            .testTag(if (isLike) "player.like" else "player.dislike"),
        tint = if (isOn) BoomColor.accent else BoomColor.textPrimary,
        enabled = enabled,
    )
}

// Controls

@Composable
private fun TransportRow(player: PlayerStore, modifier: Modifier = Modifier) {
    val state by player.state.collectAsState()
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlayerIconButton(
            Icons.Filled.Shuffle, if (state.isShuffled) "Shuffle on" else "Shuffle off", { player.toggleShuffle() },
            Modifier.testTag("player.shuffle"), iconSize = 24.dp,
            tint = if (state.isShuffled) BoomColor.accent else BoomColor.textPrimary,
        )
        PlayerIconButton(
            Icons.Filled.SkipPrevious, "Previous", { player.previous() }, Modifier.testTag("player.previous"),
            iconSize = 38.dp, enabled = state.current != null,
        )
        BigPlayButton(state.status, state.isPlayingOrAboutTo) { player.togglePlayPause() }
        PlayerIconButton(
            Icons.Filled.SkipNext, "Next", { player.next() }, Modifier.testTag("player.next"),
            iconSize = 38.dp, enabled = state.hasNext,
        )
        PlayerIconButton(
            if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            when (state.repeatMode) {
                RepeatMode.OFF -> "Repeat off"
                RepeatMode.ALL -> "Repeat all"
                RepeatMode.ONE -> "Repeat one"
            },
            { player.cycleRepeatMode() }, Modifier.testTag("player.repeat"), iconSize = 24.dp,
            tint = if (state.repeatMode == RepeatMode.OFF) BoomColor.textPrimary else BoomColor.accent,
        )
    }
}

/** White disc, black glyph; a spinning yellow ring around it while the song is on its way; Retry after a failure. */
@Composable
private fun BigPlayButton(status: PlaybackStatus, isPlayingOrAboutTo: Boolean, onClick: () -> Unit) {
    val isFailed = status is PlaybackStatus.Failed
    val label = if (isFailed) "Retry" else if (isPlayingOrAboutTo) "Pause" else "Play"
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (status.isWaiting) stateDescription = "Loading"
            }
            .testTag("player.playPause"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .background(BoomColor.textPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    isFailed -> Icons.Filled.Refresh
                    isPlayingOrAboutTo -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = null, tint = BoomColor.background, modifier = Modifier.size(38.dp),
            )
        }
        if (status.isWaiting) SpinnerRing(Modifier.size(80.dp), lineWidth = 3.5.dp)
    }
}

@Composable
private fun FailureNotice(player: PlayerStore) {
    val state by player.state.collectAsState()
    val message = state.status.failureMessage
    AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BoomMetrics.margin + 8.dp)
                .testTag("player.failure"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = BoomColor.accent, modifier = Modifier.size(20.dp))
            Text(
                message ?: "", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .heightIn(min = BoomMetrics.minimumTarget)
                    .clickable(role = Role.Button) { player.retry() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Retry", style = BoomFont.button, color = BoomColor.onAccent,
                    modifier = Modifier
                        .background(BoomColor.accent, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SecondaryRow(onSleepTimer: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val equalizer by services.equalizer.state.collectAsState()
    val player by services.player.state.collectAsState()
    val context = LocalContext.current
    val eqOn = equalizer.settings.isEnabled
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlayerIconButton(
            Icons.Filled.Tune, if (eqOn) "Sound, equaliser on" else "Sound, equaliser off", { navigator.openEqualizer() },
            Modifier.testTag("player.sound"), iconSize = 22.dp, tint = if (eqOn) BoomColor.accent else BoomColor.textSecondary,
        )
        PlayerIconButton(
            Icons.Outlined.SpeakerGroup, "Output", { openOutputSwitcher(context) }, Modifier.testTag("player.output"),
            iconSize = 22.dp, tint = BoomColor.textSecondary,
        )
        val countdown = sleepTimerCountdown(player.sleepTimer)
        val isSet = player.sleepTimer != null
        Row(
            Modifier
                .heightIn(min = BoomMetrics.minimumTarget)
                .widthIn(min = BoomMetrics.minimumTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onSleepTimer)
                .semantics { contentDescription = if (isSet) "Sleep timer on" else "Sleep timer" }
                .testTag("player.sleepTimer"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            val tint = if (isSet) BoomColor.accent else BoomColor.textSecondary
            Icon(if (isSet) Icons.Filled.Bedtime else Icons.Outlined.Bedtime, null, tint = tint, modifier = Modifier.size(22.dp))
            if (countdown != null) Text(countdown, style = BoomFont.time, color = tint)
        }
    }
}

/** The system's own picker of where the sound goes (speaker, headphones, Bluetooth, cast). */
private fun openOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return
    val intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME) else Intent(Settings.ACTION_SOUND_SETTINGS)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (missing: ActivityNotFoundException) {
        // A phone without either: nothing to show.
    }
}

// Tabs at the foot

@Composable
private fun PanelTabsBar() {
    val model = LocalOverlayModel.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BoomMetrics.margin)
            .pointerInput(model) {
                // A swipe up on the tabs pulls up "Up next".
                var moved = 0f
                detectVerticalDragGestures(
                    onDragStart = { moved = 0f },
                    onDragEnd = { if (moved < -24.dp.toPx()) model.showPanel(NowPlayingPanel.UP_NEXT) },
                ) { change, amount ->
                    change.consume()
                    moved += amount
                }
            },
    ) {
        for (tab in NowPlayingPanel.entries) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = BoomMetrics.minimumTarget)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { model.showPanel(tab) }
                    .testTag("player.tab.${tab.tag}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.title, color = BoomColor.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, maxLines = 1,
                )
            }
        }
    }
}

/** Which list the player's menu shows. */
internal enum class PlayerMenuPage { Main, SleepTimer }
