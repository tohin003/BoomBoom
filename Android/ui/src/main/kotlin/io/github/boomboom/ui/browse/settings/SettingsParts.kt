package io.github.boomboom.ui.browse.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.sound.SoundCard

// The pieces Settings is made of: a titled card per section (the Sound screen's card), rows inside it, a line
// under it. Switches are the Sound screen's own (`SoundSwitch`).

/** A section: its title, its rows in a card, and an optional line of explanation under the card. */
@Composable
internal fun SettingsSection(title: String, tag: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title, style = BoomFont.button, color = BoomColor.textSecondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )
        SoundCard { Column(content = content) }
        if (footer != null) {
            Text(footer, style = BoomFont.caption, color = BoomColor.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The thin line between two rows of a card. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = BoomColor.separator)
}

/**
 * A row of a card: a title (and a line under it), a value on the right, and a chevron when it leads somewhere. The
 * whole row is the target when [onClick] is given.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    valueColor: Color = BoomColor.textSecondary,
    titleColor: Color = BoomColor.textPrimary,
    icon: ImageVector? = null,
    showsChevron: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = titleColor, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = BoomFont.itemTitle, color = titleColor)
            if (detail != null) Text(detail, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
        }
        if (value != null) {
            Text(
                value, style = BoomFont.itemSubtitle.copy(fontFeatureSettings = "tnum"), color = valueColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (showsChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BoomColor.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}
