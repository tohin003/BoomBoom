package io.github.boomboom.ui.browse.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.BackendState
import io.github.boomboom.logic.SessionAccount
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ConfirmDialog
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.PillButton
import kotlinx.coroutines.launch

/**
 * Settings' first section: the accounts (up to three, each kept apart), the active one marked. A tap on another
 * switches to it; a row's menu signs one out, which asks first. Signed out entirely, it is the one "Sign in" row.
 * When the hidden browser could not load, it says so here with "Try again".
 */
@Composable
internal fun AccountsSection() {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val appScope = LocalAppScope.current
    val session by services.session.state.collectAsState()
    /** The account being switched to: its row shows progress until the switch is done. One switch at a time. */
    var switchingTo by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf<SessionAccount?>(null) }

    fun switchTo(entry: SessionAccount) {
        if (entry.isActive || switchingTo != null) return
        switchingTo = entry.id
        appScope.launch {
            try {
                services.session.switchAccount(entry.id)
            } finally {
                switchingTo = null
            }
        }
    }

    SettingsSection(
        if (session.accounts.isEmpty()) "Account" else "Accounts", tag = "settings.accounts",
        footer = if (session.accounts.size > 1) "Each account keeps its own library, likes and history. Tap one to switch." else null,
    ) {
        var rows = 0
        (session.backendState as? BackendState.Failed)?.let { failed ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag("settings.backendFailed"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = BoomColor.destructive, modifier = Modifier.size(18.dp))
                    Text(failed.message, style = BoomFont.itemSubtitle, color = BoomColor.textPrimary)
                }
                PillButton("Try again", { services.session.retryBackend() }, Modifier.testTag("settings.tryAgain"), compact = true)
            }
            rows += 1
        }
        for (entry in session.accounts) {
            if (rows > 0) RowDivider()
            AccountRow(entry, isSwitching = switchingTo == entry.id, onSwitch = { switchTo(entry) }, onSignOut = { signingOut = entry })
            rows += 1
        }
        if (!session.isSignedIn) {
            if (rows > 0) RowDivider()
            ActionRow(Icons.Outlined.AccountCircle, "Sign in", "To see your library, likes and playlists", "settings.signIn") { navigator.signIn() }
        } else if (session.canAddAccount) {
            if (rows > 0) RowDivider()
            ActionRow(
                Icons.Outlined.PersonAdd, "Add another account", "${session.accounts.size} of ${session.accountLimit} accounts used",
                "settings.addAccount",
            ) { navigator.addAccount() }
        }
    }

    signingOut?.let { entry ->
        val others = session.accounts.size > 1
        val message = when {
            entry.isActive && others -> "Its library, likes and history are hidden, and BoomBoom switches to your other account."
            entry.isActive -> "Your library, likes and history will be hidden until you sign in again."
            else -> "It is removed from BoomBoom until you add it again."
        }
        ConfirmDialog(
            entry.account?.let { "Sign out of ${it.name}?" } ?: "Sign out of this account?", message,
            confirm = "Sign out", tag = "settings.signOutQuestion",
            onConfirm = { appScope.launch { services.session.signOut(entry.id) } },
            onDismiss = { signingOut = null },
        )
    }
}

@Composable
private fun AccountRow(entry: SessionAccount, isSwitching: Boolean, onSwitch: () -> Unit, onSignOut: () -> Unit) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val account = entry.account
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClickLabel = if (entry.isActive) null else "Switch to this account", onClick = onSwitch)
                .semantics(mergeDescendants = true) {
                    stateDescription = when {
                        entry.isActive -> "Active"
                        isSwitching -> "Switching"
                        else -> ""
                    }
                }
                .testTag(if (entry.isActive) "settings.account.active" else "settings.account")
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
        ) {
            if (account != null) {
                ArtworkView(account.artwork, width = 44.dp, isCircle = true)
            } else {
                Icon(Icons.Filled.AccountCircle, null, tint = BoomColor.textSecondary, modifier = Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account?.name ?: "Signed-in account", style = BoomFont.itemTitle, color = BoomColor.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                account?.handle?.let {
                    Text(it, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                isSwitching -> CircularProgressIndicator(color = BoomColor.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                entry.isActive -> Icon(Icons.Filled.CheckCircle, null, tint = BoomColor.accent, modifier = Modifier.size(22.dp))
            }
        }
        Box {
            BarIconButton(
                Icons.Filled.MoreVert, "Account options", { isMenuOpen = true }, Modifier.testTag("settings.account.menu"),
                tint = BoomColor.textSecondary, iconSize = 22.dp,
            )
            DropdownMenu(isMenuOpen, { isMenuOpen = false }, containerColor = BoomColor.surfaceRaised) {
                if (!entry.isActive) {
                    DropdownMenuItem(
                        text = { Text("Switch to this account") }, leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) },
                        onClick = {
                            isMenuOpen = false
                            onSwitch()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Sign out", color = BoomColor.destructive) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = BoomColor.destructive) },
                    onClick = {
                        isMenuOpen = false
                        onSignOut()
                    },
                    modifier = Modifier.testTag("settings.account.signOut"),
                )
            }
        }
    }
}

/** "Sign in" or "Add another account": an accent title with a line under it. */
@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoomMetrics.rowSpacing),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = BoomColor.accent, modifier = Modifier.size(30.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = BoomFont.itemTitle, color = BoomColor.accent)
            Text(detail, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
        }
    }
}
