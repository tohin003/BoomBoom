package io.github.boomboom.ui.browse.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle

/** What a screen of personal data says while nobody is signed in, with the way to sign in. */
@Composable
fun SignedOutView(title: String, message: String, modifier: Modifier = Modifier) {
    val navigator = LocalBoomNavigator.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 60.dp)
            .padding(32.dp)
            .testTag("signedOut"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = BoomColor.textTertiary, modifier = Modifier.size(40.dp))
        Text(title, style = BoomFont.messageTitle, color = BoomColor.textPrimary, textAlign = TextAlign.Center)
        Text(message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, textAlign = TextAlign.Center)
        PillButton(
            "Sign in", { navigator.signIn() }, Modifier
                .padding(top = 8.dp)
                .testTag("signedOut.signIn"),
            style = PillStyle.FILLED,
        )
    }
}

/** The two rows pinned above the playlists. */
enum class PinnedKind(val title: String, val subtitle: String, val tag: String) {
    NEW_PLAYLIST("New playlist", "Name it, then add songs", "library.newPlaylist"),
    LIKED_MUSIC("Liked music", "Songs you've liked", "library.liked"),
}

/** "New playlist" or "Liked music": a drawn cover and two lines, the whole row one target. */
@Composable
fun PinnedRow(kind: PinnedKind, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BoomMetrics.coverRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BoomMetrics.rowCover + 8.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(kind.tag)
            .padding(horizontal = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        when (kind) {
            PinnedKind.NEW_PLAYLIST -> Box(
                Modifier
                    .size(BoomMetrics.rowCover)
                    .background(BoomColor.surfaceRaised, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, null, tint = BoomColor.textPrimary, modifier = Modifier.size(26.dp))
            }
            PinnedKind.LIKED_MUSIC -> Box(
                Modifier
                    .size(BoomMetrics.rowCover)
                    .background(Brush.linearGradient(listOf(BoomColor.accent, BoomColor.accentDeep)), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ThumbUp, null, tint = BoomColor.onAccent, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(kind.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind.subtitle, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
