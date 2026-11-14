package io.github.boomboom.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.Clock
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.SleepTimer
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.Song
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * "Now" for the player's screens: the sleep timer is set and counted down in the stores' own time, which a test
 * controls. The app leaves it at the system clock.
 */
val LocalPlayerTime = staticCompositionLocalOf<Clock> { Clock.System }

/**
 * A glyph button with a 48 dp target that shrinks and dims a little while pressed, like the player's controls on a
 * phone should.
 */
@Composable
internal fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    tint: Color = BoomColor.textPrimary,
    enabled: Boolean = true,
    state: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            }
            .graphicsLayer {
                val pressed = isPressed && enabled
                scaleX = if (pressed) 0.88f else 1f
                scaleY = if (pressed) 0.88f else 1f
            }
            .alpha(if (!enabled) 0.35f else if (isPressed) 0.7f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A turning arc, drawn around a play button while the song loads or buffers. */
@Composable
internal fun SpinnerRing(modifier: Modifier = Modifier, color: Color = BoomColor.accent, lineWidth: Dp = 3.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val turn by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier.testTag("spinner")) {
        val stroke = lineWidth.toPx()
        drawArc(
            color, startAngle = turn - 90f, sweepAngle = 0.28f * 360f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * One line of text that scrolls sideways when it does not fit, then rests, then scrolls again. Text that fits sits
 * still at [align].
 */
@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = BoomColor.textPrimary,
    align: TextAlign = TextAlign.Start,
    /** dp per second. */
    speed: Float = 30f,
) {
    var boxWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember(text) { mutableIntStateOf(0) }
    var phase by remember(text) { mutableFloatStateOf(0f) }
    val gap = with(LocalDensity.current) { 40.dp.toPx() }
    val pixelsPerSecond = with(LocalDensity.current) { speed.dp.toPx() }
    val overflows = boxWidth > 0 && textWidth > boxWidth + 1
    if (overflows) {
        LaunchedEffect(text, textWidth, boxWidth) {
            while (true) {
                phase = 0f
                delay(2500)
                val distance = textWidth + gap
                var start = -1L
                // An endless animation (the frame loop) as far as tests are concerned, so they never wait for it.
                while (true) {
                    val now = withInfiniteAnimationFrameMillis { it }
                    if (start < 0) start = now
                    val moved = (now - start) / 1000f * pixelsPerSecond
                    if (moved >= distance) break
                    phase = -moved
                }
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { boxWidth = it.width }
            .then(if (overflows) Modifier.fadeEdges() else Modifier)
            .semantics { contentDescription = text },
    ) {
        if (overflows) {
            Row(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = phase },
            ) {
                Text(text, style = style, color = color, maxLines = 1, softWrap = false)
                Box(Modifier.width(with(LocalDensity.current) { gap.toDp() }))
                Text(text, style = style, color = color, maxLines = 1, softWrap = false)
            }
        } else {
            Text(
                text, style = style, color = color, maxLines = 1, softWrap = false, textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Measures the text at its natural width.
        Text(
            text, style = style, maxLines = 1, softWrap = false,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .alpha(0f)
                .onSizeChanged { textWidth = it.width },
        )
    }
}

/** Fades a scrolling line out at both ends, so text slides in and out softly instead of being cut. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.04f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** The first artist that has a page. */
internal val Song.firstLinkedArtist: ArtistRef? get() = artists.firstOrNull { it.id != null }

/** Loading or buffering: the song is on its way, not yet heard. */
internal val PlaybackStatus.isWaiting: Boolean
    get() = this == PlaybackStatus.Loading || this == PlaybackStatus.Buffering

internal val PlaybackStatus.failureMessage: String? get() = (this as? PlaybackStatus.Failed)?.message

/** The sleep timer's choices, shared by the player's menu and its moon button. */
internal val sleepTimerMinutes = listOf(5, 10, 15, 30, 45, 60)

/** What the moon button shows beside itself: the minutes and seconds left, or "End". */
@Composable
internal fun sleepTimerCountdown(timer: SleepTimer?): String? {
    val time = LocalPlayerTime.current
    return when (timer) {
        null -> null
        SleepTimer.EndOfSong -> "End"
        is SleepTimer.At -> {
            var now by remember { mutableLongStateOf(time.nowMillis()) }
            LaunchedEffect(timer) {
                while (true) {
                    now = time.nowMillis()
                    delay(1000)
                }
            }
            val left = ceil((timer.atMillis - now).coerceAtLeast(0) / 1000.0).toLong()
            "%d:%02d".format(left / 60, left % 60)
        }
    }
}

/** A centred note for an empty or failed panel, with "Try again" when there is something to try. */
@Composable
internal fun PanelMessage(icon: ImageVector, text: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = BoomColor.textTertiary, modifier = Modifier.size(36.dp))
        Text(text, style = BoomFont.itemTitle, color = BoomColor.textSecondary, textAlign = TextAlign.Center)
        if (retry != null) PillButton("Try again", retry, Modifier.testTag("panel.retry"), style = PillStyle.FILLED)
    }
}
