package io.github.boomboom.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How a pill is drawn. */
enum class PillStyle {
    /** Outlined: "More", "Retry", "Subscribe". */
    OUTLINE,

    /** White with black text: "Play" on a top result. */
    FILLED,

    /** Translucent grey: secondary actions ("Shuffle", "Radio" on an artist). */
    TONAL,
}

/**
 * A pill-shaped button. The pill is drawn [visualHeight] tall, but the touch target is at least
 * [BoomMetrics.minimumTarget] high, so small pills are still easy to hit.
 *
 * @param compact The small header variant ("More").
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.OUTLINE,
    icon: ImageVector? = null,
    compact: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    fillWidth: Boolean = false,
) {
    val visualHeight = if (compact) 30.dp else 36.dp
    val foreground = if (style == PillStyle.FILLED) Color.Black else BoomColor.textPrimary
    Box(
        modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        val pill = Modifier
            .heightIn(min = visualHeight)
            .then(
                when (style) {
                    PillStyle.OUTLINE -> Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    PillStyle.FILLED -> Modifier.background(Color.White, CircleShape)
                    PillStyle.TONAL -> Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape)
                },
            )
            .padding(horizontal = if (compact) 12.dp else 18.dp)
        Row(
            if (fillWidth) pill.fillMaxWidth() else pill,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
            }
            Text(
                text,
                style = if (compact) BoomFont.caption.copy(fontWeight = FontWeight.SemiBold) else BoomFont.button,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The big round Play button of album, playlist and artist pages: a yellow circle with a black glyph. */
@Composable
fun AccentPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    icon: ImageVector = Icons.Filled.PlayArrow,
    label: String = "Play",
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(BoomColor.accent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = BoomColor.onAccent, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A round icon button on a dark page: download, save, shuffle beside the Play button.
 *
 * @param isActive Drawn in the accent ("saved").
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(22.dp),
            tint = if (isActive) BoomColor.accent else BoomColor.textPrimary,
        )
    }
}

/** A plain icon button with a 48 dp target: search, back, clear. */
@Composable
fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BoomColor.textPrimary,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(BoomMetrics.minimumTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
